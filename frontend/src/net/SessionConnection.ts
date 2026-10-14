import type { Op } from "../ot/ops";
import { OtClient } from "../ot/client";
import { sha256Hex } from "../ot/hash";
import { ApiError, mintTicket } from "../api/client";
import {
  normalizeOp,
  type PresenceUser,
  type Role,
  type WsInbound,
  type WsOutbound,
} from "../ws/messages";

// The editor exposes its document to the connection so the resync self-heal can
// compare hashes, replace content, and apply rebased remote ops without echoing
// them back as local edits.
export interface DocHost {
  getText(): string;
  applyRemote(op: Op): void;
  replaceText(text: string): void;
}

export interface SessionHandlers {
  onInit(p: { document: string; version: number; role: Role; token: string; fileName: string; language: string }): void;
  onRename(fileName: string): void;
  onLanguage(language: string): void;
  onResyncDoc(document: string, version: number): void;
  onPresence(users: PresenceUser[]): void;
  onCursor(clientId: string, pos: number): void;
  onCursorRemove(clientId: string): void;
  onWaiting(token: string, hostOnline: boolean): void;
  onHostStatus(online: boolean): void;
  onJoinRequest(p: { joinKey: string; name: string; color: string }): void;
  onJoinResolved(joinKey: string): void;
  onDenied(reason?: string): void;
  onKicked(): void;
  onSessionEnded(): void;
  onAdminChanged(clientId: string): void;
  onAdminOffer(open: boolean): void; // host left: prompt (true) / withdraw the prompt (false)
  onUnavailable(): void; // socket closed before admission — session gone
  onReconnecting(): void; // socket dropped while admitted; retrying with backoff
  onDisconnected(): void; // reconnect attempts exhausted
}

const PROBE_DELAY_MS = 700;
const HEARTBEAT_MS = 25_000; // under the ~60s idle cut-off of common proxies
const RECONNECT_BASE_MS = 1_000;
const RECONNECT_MAX_MS = 30_000;
const MAX_RECONNECT_ATTEMPTS = 10; // ~3-4 min of backoff before showing "disconnected"
const GAP_WAIT_MS = 400; // out-of-order op: wait this long for the gap to fill, then ask for a catchup
const NACK_RETRY_MS = 300; // rate-limited op: resend after this
// Inserts are split so a frame stays under the server's 64 KB cap even when every
// char JSON-escapes to 6 bytes (8000 * 6 = 48 KB plus envelope).
const MAX_INSERT_CHUNK = 8000;

// Ports the codeshare.js connection lifecycle: connect with optional token,
// admit on `init`, run the Jupiter OT loop on `op`, self-heal on idle via
// `resync`, relay cursors/presence/admin events. One per session, created when
// the user creates or asks to join.
export class SessionConnection {
  readonly clientId: string;
  readonly sessionId: string;
  name: string;
  color: string;
  token: string | null;
  role: Role = "JOINER";

  admitted = false;
  denied = false;
  private intentionalClose = false;
  private everAdmitted = false;
  private reconnectAttempts = 0;
  private awaitingPong = false;
  private waiting = false; // in the waiting room: a drop reconnects instead of "not found"
  // After a reconnect, op sends are held until the missed ops have been replayed.
  private resuming = false;
  // Ops that arrived ahead of a gap in the version sequence, applied once it fills.
  private readonly early = new Map<number, Op>();
  private gapTimer: ReturnType<typeof setTimeout> | null = null;

  private ws: WebSocket | null = null;
  private docHost: DocHost | null = null;
  private probeTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly ot: OtClient;

  constructor(
    opts: { sessionId: string; clientId: string; name: string; color: string; token: string | null },
    private readonly h: SessionHandlers,
  ) {
    this.sessionId = opts.sessionId;
    this.clientId = opts.clientId;
    this.name = opts.name;
    this.color = opts.color;
    this.token = opts.token;
    this.ot = new OtClient(
      (op) => { if (!this.resuming) this.sendRaw(op); },
      (op) => this.docHost?.applyRemote(op),
    );
  }

  setDocHost(host: DocHost | null): void {
    this.docHost = host;
  }

  /** Current editor document (for export / GitHub push). */
  getDocumentText(): string {
    return this.docHost?.getText() ?? "";
  }

  connect(): void {
    // Fire-and-forget: mint a single-use ticket (token in the POST body, not the
    // URL), then open the socket carrying only the opaque ticket.
    void this.openWithTicket();
  }

  private async openWithTicket(): Promise<void> {
    let ticket: string;
    try {
      ticket = (await mintTicket(this.sessionId, this.token, this.clientId)).ticket;
    } catch (e) {
      // Session gone (404/400) is terminal; anything else (network, 5xx) is a failed
      // attempt like a failed upgrade: unavailable before admission, retried after.
      if (e instanceof ApiError && e.status === 410) {
        this.denied = true; // ended by the host: final, not "not found"
        this.h.onSessionEnded();
        return;
      }
      if (e instanceof ApiError && (e.status === 404 || e.status === 400)) this.reconnectAttempts = 0;
      this.onClose();
      return;
    }
    if (this.intentionalClose) return; // closed while the ticket was in flight

    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const params = new URLSearchParams({
      sessionId: this.sessionId,
      clientId: this.clientId,
      name: this.name,
      color: this.color,
      ticket,
    });
    const ws = new WebSocket(`${proto}//${location.host}/ws/ot?${params.toString()}`);
    this.ws = ws;
    ws.onopen = () => this.startHeartbeat();
    ws.onmessage = (ev) => this.onMessage(ev);
    ws.onerror = (e) => console.error("WS error", e);
    ws.onclose = (ev) => this.onClose(ev.reason);
  }

  // App-level ping; any inbound frame proves liveness. A whole interval with no
  // reply while admitted means a half-open socket (no onclose will come), so drop
  // it and reconnect. Waiting-room sockets only ping, to survive proxy idle cuts.
  private startHeartbeat(): void {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.awaitingPong = false;
    this.heartbeatTimer = setInterval(() => {
      if (this.awaitingPong && this.admitted) {
        this.dropSocket();
        return;
      }
      this.awaitingPong = true;
      this.sendRaw({ type: "ping" });
    }, HEARTBEAT_MS);
  }

  private dropSocket(): void {
    const ws = this.ws;
    if (!ws) return;
    ws.onclose = null;
    ws.onmessage = null;
    ws.close();
    this.ws = null;
    this.onClose();
  }

  private stopTimers(): void {
    if (this.probeTimer) clearTimeout(this.probeTimer);
    if (this.gapTimer) clearTimeout(this.gapTimer);
    this.gapTimer = null;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.heartbeatTimer = this.reconnectTimer = null;
  }

  // ---- editor -> wire ----
  localInsert(pos: number, text: string): void {
    for (let i = 0; i < text.length; ) {
      let end = Math.min(i + MAX_INSERT_CHUNK, text.length);
      const code = text.charCodeAt(end - 1);
      if (end < text.length && code >= 0xd800 && code <= 0xdbff) end--; // don't split a surrogate pair
      this.ot.submitLocal({ type: "insert", clientId: this.clientId, baseVersion: this.ot.baseVersion, pos: pos + i, text: text.slice(i, end) });
      i = end;
    }
  }

  localDelete(pos: number, length: number): void {
    this.ot.submitLocal({ type: "delete", clientId: this.clientId, baseVersion: this.ot.baseVersion, pos, length });
  }

  sendCursor(pos: number): void {
    if (!this.admitted) return;
    this.sendRaw({ type: "cursor", clientId: this.clientId, pos, sessionId: this.sessionId });
  }

  /** Rename the shared document (base name only; already sanitized by the caller). */
  rename(baseName: string): void {
    if (!this.admitted) return;
    this.sendRaw({ type: "rename", fileName: baseName });
  }

  /** Pick the document's language for everyone ("" = back to auto-detect). */
  setLanguage(language: string): void {
    if (!this.admitted) return;
    this.sendRaw({ type: "setLanguage", language });
  }

  // ---- admin actions ----
  approve(joinKey: string): void {
    this.sendRaw({ type: "approve", joinKey });
  }
  reject(joinKey: string): void {
    this.sendRaw({ type: "reject", joinKey });
  }
  kick(clientId: string): void {
    this.sendRaw({ type: "kick", clientId });
  }
  endSession(): void {
    this.sendRaw({ type: "endsession" });
  }
  acceptAdmin(): void {
    this.sendRaw({ type: "acceptAdmin" });
  }
  declineAdmin(): void {
    this.sendRaw({ type: "declineAdmin" });
  }

  /** Tear down on purpose (cancel waiting, start a new session, reconnect). Marks
   *  the close as intentional so onClose doesn't mistake it for the session
   *  becoming unavailable and bounce the user to the declined screen. */
  close(): void {
    this.intentionalClose = true;
    this.stopTimers();
    this.ws?.close();
    this.ws = null;
  }

  // ---- inbound ----
  private onMessage(ev: MessageEvent): void {
    let msg: WsInbound;
    try {
      msg = JSON.parse(ev.data as string) as WsInbound;
    } catch {
      return;
    }
    this.awaitingPong = false;
    switch (msg.type) {
      case "init": {
        // Auto-reconnect keeps the mounted editor: rebase its unacked edits onto
        // the server copy (as a forced resync does); the idle probe then verifies.
        // Auto-reconnect keeps the mounted editor and its OT state: replay the ops we
        // missed through the normal loop (catchup), which transforms our unacked edits
        // and dedupes one whose ack was lost; unacked sends wait until caughtUp.
        const resuming = this.everAdmitted && this.docHost !== null;
        this.admitted = true;
        this.everAdmitted = true;
        this.waiting = false;
        this.reconnectAttempts = 0;
        this.token = msg.token || this.token;
        this.role = msg.role || "JOINER";
        this.early.clear();
        if (resuming) {
          this.resuming = true;
          this.sendRaw({ type: "catchup", from: this.ot.baseVersion });
        } else {
          this.ot.reset(msg.version || 0);
        }
        this.h.onInit({
          document: msg.document || "",
          version: msg.version || 0,
          role: this.role,
          token: this.token!,
          fileName: msg.fileName ?? "",
          language: msg.language ?? "",
        });
        break;
      }
      case "op":
        this.onServerOp(normalizeOp(msg.op), msg.version);
        break;
      case "caughtUp":
        if (this.resuming) {
          this.resuming = false;
          this.ot.resendOutstanding(); // never sent on this socket
        }
        if (this.ot.isQuiescent()) this.scheduleProbe();
        break;
      case "nack":
        if (msg.reason === "invalid") this.ot.discardOutstanding(); // a forced resync follows
        else setTimeout(() => { if (!this.resuming) this.ot.resendOutstanding(); }, NACK_RETRY_MS);
        break;
      case "resync":
        this.handleResync(msg.document || "", msg.version || 0, msg.hash, msg.required ?? false);
        break;
      case "cursor":
        if (msg.clientId !== this.clientId) this.h.onCursor(msg.clientId, msg.pos);
        break;
      case "cursorRemove":
        this.h.onCursorRemove(msg.clientId);
        break;
      case "rename":
        this.h.onRename(msg.fileName ?? "");
        break;
      case "language":
        this.h.onLanguage(msg.language ?? "");
        break;
      case "presence":
        for (const u of msg.users) if (u.clientId === this.clientId) this.role = u.role;
        this.h.onPresence(msg.users);
        break;
      case "waitingForApproval":
        this.waiting = true;
        this.reconnectAttempts = 0;
        this.token = msg.token;
        this.h.onWaiting(msg.token, msg.hostOnline !== false);
        break;
      case "hostStatus":
        this.h.onHostStatus(msg.online);
        break;
      case "joinRequest":
        this.h.onJoinRequest({ joinKey: msg.joinKey, name: msg.name, color: msg.color });
        break;
      case "joinResolved":
        this.h.onJoinResolved(msg.joinKey);
        break;
      case "joinDenied":
        this.denied = true;
        this.admitted = false;
        this.h.onDenied(msg.reason);
        break;
      case "adminChanged":
        if (msg.clientId === this.clientId) this.role = "CREATOR";
        this.h.onAdminChanged(msg.clientId);
        break;
      case "adminOffer":
        this.h.onAdminOffer(true);
        break;
      case "adminOfferWithdrawn":
        this.h.onAdminOffer(false);
        break;
      case "kicked":
      case "sessionEnded":
        this.terminal(msg.type);
        break;
    }
  }

  private terminal(type: "kicked" | "sessionEnded"): void {
    if (this.denied) return;
    this.denied = true;
    this.admitted = false;
    if (type === "kicked") this.h.onKicked();
    else this.h.onSessionEnded();
  }

  // Two resync flavors:
  //  - required: the server forced it (our base fell below the compaction floor).
  //    Capture unacked local edits, adopt the authoritative snapshot, then replay
  //    those edits — applied locally and re-sent from the new base — so they're
  //    rebased onto the server doc instead of being silently dropped.
  //  - routine self-heal: only adopt the snapshot if we're still caught up and
  //    actually diverged (SHA-256 mismatch), so we never clobber edits made while
  //    the resync was in flight.
  // Ops carry consecutive versions. Duplicates (catchup overlap) are skipped; one
  // that arrives ahead of a gap (concurrent commits can be broadcast out of order)
  // waits for the gap to fill, and a gap that persists triggers a catchup.
  private onServerOp(op: Op, version: number): void {
    if (version <= this.ot.baseVersion) return;
    if (version > this.ot.baseVersion + 1) {
      this.early.set(version, op);
      if (!this.gapTimer) {
        this.gapTimer = setTimeout(() => {
          this.gapTimer = null;
          if (this.early.size) this.sendRaw({ type: "catchup", from: this.ot.baseVersion });
        }, GAP_WAIT_MS);
      }
      return;
    }
    this.ot.onServerOp(op, version, this.clientId);
    let next: Op | undefined;
    while ((next = this.early.get(this.ot.baseVersion + 1))) {
      this.early.delete(this.ot.baseVersion + 1);
      this.ot.onServerOp(next, this.ot.baseVersion + 1, this.clientId);
    }
    for (const v of this.early.keys()) if (v <= this.ot.baseVersion) this.early.delete(v);
    if (this.ot.isQuiescent()) this.scheduleProbe();
  }

  private handleResync(document: string, version: number, hash: string, required: boolean): void {
    if (required) {
      this.resuming = false;
      this.early.clear();
      this.rebaseOnto(document, version);
      this.h.onResyncDoc(document, version);
      return;
    }
    if (!this.ot.isQuiescent()) return;
    const local = this.docHost?.getText() ?? "";
    if (sha256Hex(local) === hash) return;
    this.docHost?.replaceText(document);
    this.ot.reset(version);
    this.h.onResyncDoc(document, version);
    this.scheduleProbe();
  }

  // Last resort when the server's history no longer reaches our base (compaction or a
  // restart): adopt the snapshot and replay unacked edits from the new base.
  // ponytail: replayed edits keep their old positions here; only hit past ~2000 missed ops.
  private rebaseOnto(document: string, version: number): void {
    const pending = this.ot.capturePending();
    this.docHost?.replaceText(document);
    this.ot.reset(version);
    for (const op of pending) this.docHost?.applyRemote(op); // reflect locally
    for (const op of pending) this.ot.submitLocal(op);       // re-issue from the new base
    this.scheduleProbe();
  }

  private scheduleProbe(): void {
    if (this.probeTimer) clearTimeout(this.probeTimer);
    this.probeTimer = setTimeout(() => {
      if (this.ot.isQuiescent()) this.sendRaw({ type: "resync" });
    }, PROBE_DELAY_MS);
  }

  // The server also puts the terminal type in the close reason, because the
  // text frame can be dropped if the socket was mid-write when it was closed.
  private onClose(reason = ""): void {
    if (reason === "kicked" || reason === "sessionEnded") this.terminal(reason);
    this.stopTimers();
    this.ws = null;
    if (this.intentionalClose || this.denied) return; // user left, or terminal state already surfaced
    if (this.admitted || this.waiting || this.reconnectAttempts > 0) {
      this.admitted = false; // dropped while admitted/waiting, or a retry failed: back off and redial
      this.scheduleReconnect();
    } else {
      this.h.onUnavailable();
    }
  }

  // Exponential backoff with jitter (so a server restart isn't hit by every client
  // at once). Each attempt re-POSTs for a fresh single-use ticket with the stored token.
  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this.reconnectAttempts = 0;
      this.h.onDisconnected();
      return;
    }
    const delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** this.reconnectAttempts);
    this.reconnectAttempts++;
    this.h.onReconnecting();
    this.reconnectTimer = setTimeout(() => void this.openWithTicket(), delay * (0.5 + Math.random() / 2));
  }

  private sendRaw(obj: WsOutbound): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  }
}
