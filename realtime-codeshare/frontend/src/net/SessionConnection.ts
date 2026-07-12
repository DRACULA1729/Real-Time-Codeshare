import type { Op } from "../ot/ops";
import { OtClient } from "../ot/client";
import { sha256Hex } from "../ot/hash";
import { mintTicket } from "../api/client";
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
  onInit(p: { document: string; version: number; role: Role; token: string; fileName: string }): void;
  onRename(fileName: string): void;
  onResyncDoc(document: string, version: number): void;
  onPresence(users: PresenceUser[]): void;
  onCursor(clientId: string, pos: number): void;
  onCursorRemove(clientId: string): void;
  onWaiting(token: string): void;
  onJoinRequest(p: { joinKey: string; name: string; color: string }): void;
  onJoinResolved(joinKey: string): void;
  onDenied(reason?: string): void;
  onKicked(): void;
  onSessionEnded(): void;
  onAdminChanged(clientId: string): void;
  onUnavailable(): void; // socket closed before admission — session gone
  onDisconnected(): void; // socket dropped while admitted
}

const PROBE_DELAY_MS = 700;

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

  private ws: WebSocket | null = null;
  private docHost: DocHost | null = null;
  private probeTimer: ReturnType<typeof setTimeout> | null = null;
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
      (op) => this.sendRaw(op),
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
    } catch {
      // Session gone, or the ticket request failed — surface it the same way a
      // failed upgrade would (unavailable before admission, disconnected after).
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
    ws.onmessage = (ev) => this.onMessage(ev);
    ws.onerror = (e) => console.error("WS error", e);
    ws.onclose = () => this.onClose();
  }

  // ---- editor -> wire ----
  localInsert(pos: number, text: string): void {
    this.ot.submitLocal({ type: "insert", clientId: this.clientId, baseVersion: this.ot.baseVersion, pos, text });
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

  /** Tear down on purpose (cancel waiting, start a new session, reconnect). Marks
   *  the close as intentional so onClose doesn't mistake it for the session
   *  becoming unavailable and bounce the user to the declined screen. */
  close(): void {
    this.intentionalClose = true;
    if (this.probeTimer) clearTimeout(this.probeTimer);
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
    switch (msg.type) {
      case "init":
        this.admitted = true;
        this.token = msg.token || this.token;
        this.role = msg.role || "JOINER";
        this.ot.reset(msg.version || 0);
        this.h.onInit({
          document: msg.document || "",
          version: msg.version || 0,
          role: this.role,
          token: this.token!,
          fileName: msg.fileName ?? "",
        });
        break;
      case "op":
        this.ot.onServerOp(normalizeOp(msg.op), msg.version, this.clientId);
        if (this.ot.isQuiescent()) this.scheduleProbe();
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
      case "presence":
        for (const u of msg.users) if (u.clientId === this.clientId) this.role = u.role;
        this.h.onPresence(msg.users);
        break;
      case "waitingForApproval":
        this.token = msg.token;
        this.h.onWaiting(msg.token);
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
      case "kicked":
        this.denied = true;
        this.admitted = false;
        this.h.onKicked();
        break;
      case "sessionEnded":
        this.denied = true;
        this.admitted = false;
        this.h.onSessionEnded();
        break;
    }
  }

  // Two resync flavors:
  //  - required: the server forced it (our base fell below the compaction floor).
  //    Capture unacked local edits, adopt the authoritative snapshot, then replay
  //    those edits — applied locally and re-sent from the new base — so they're
  //    rebased onto the server doc instead of being silently dropped.
  //  - routine self-heal: only adopt the snapshot if we're still caught up and
  //    actually diverged (SHA-256 mismatch), so we never clobber edits made while
  //    the resync was in flight.
  private handleResync(document: string, version: number, hash: string, required: boolean): void {
    if (required) {
      const pending = this.ot.capturePending();
      this.docHost?.replaceText(document);
      this.ot.reset(version);
      for (const op of pending) this.docHost?.applyRemote(op); // reflect locally
      for (const op of pending) this.ot.submitLocal(op);       // re-issue from the new base
      this.h.onResyncDoc(document, version);
      this.scheduleProbe();
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

  private scheduleProbe(): void {
    if (this.probeTimer) clearTimeout(this.probeTimer);
    this.probeTimer = setTimeout(() => {
      if (this.ot.isQuiescent()) this.sendRaw({ type: "resync" });
    }, PROBE_DELAY_MS);
  }

  private onClose(): void {
    if (this.probeTimer) clearTimeout(this.probeTimer);
    if (this.intentionalClose || this.denied) return; // user left, or terminal state already surfaced
    if (this.admitted) {
      this.admitted = false;
      this.h.onDisconnected();
    } else {
      this.h.onUnavailable();
    }
  }

  private sendRaw(obj: WsOutbound): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  }
}
