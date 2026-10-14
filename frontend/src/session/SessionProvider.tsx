import { createContext, useContext, useEffect, useMemo, useRef, type ReactNode } from "react";
import { createSession } from "../api/client";
import { SessionConnection, type DocHost, type SessionHandlers } from "../net/SessionConnection";
import { newClientId, storeProfile, storedColor, storedName, storedToken } from "../lib/profile";
import { sanitizeBaseName } from "../lib/fileName";
import { useSessionStore } from "../state/store";
import { RemoteCursorBus } from "./RemoteCursorBus";
import { DEFAULT_COLOR } from "../components/gate/palette";

export interface SessionController {
  cursors: RemoteCursorBus;
  createAndConnect: (name: string, color: string) => Promise<void>;
  askToJoin: (name: string, color: string) => void;
  cancel: () => void;
  startOwnSession: () => void;
  approve: (joinKey: string) => void;
  reject: (joinKey: string) => void;
  kick: (clientId: string) => void;
  endSession: () => void;
  acceptAdmin: () => void;
  declineAdmin: () => void;
  localInsert: (pos: number, text: string) => void;
  localDelete: (pos: number, length: number) => void;
  sendCursor: (pos: number) => void;
  rename: (baseName: string) => void;
  setLanguage: (language: string) => void;
  setDocHost: (host: DocHost | null) => void;
  getDocument: () => string;
  inviteLink: () => string;
}

const Ctx = createContext<SessionController | null>(null);

export function useSession(): SessionController {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useSession must be used within SessionProvider");
  return ctx;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const connRef = useRef<SessionConnection | null>(null);
  const cursors = useMemo(() => new RemoteCursorBus(), []);
  const store = useSessionStore;

  // Stable handler set that pushes connection events into the store / cursor bus.
  const handlers = useMemo<SessionHandlers>(
    () => ({
      onInit: ({ document, version, role, token, fileName, language }) => {
        const s = store.getState();
        if (s.sessionId) storeProfile(s.sessionId, token, s.name, s.color);
        s.applyInit({ document, version, role, token, fileName, languageChoice: language });
      },
      onRename: (fileName) => store.getState().setFileName(fileName),
      onLanguage: (language) => store.getState().setLanguageChoice(language),
      onResyncDoc: (_document, version) => store.getState().setVersion(version),
      onPresence: (users) => store.getState().setPresence(users),
      onCursor: (clientId, pos) => cursors.push(clientId, pos),
      onCursorRemove: (clientId) => cursors.remove(clientId),
      onWaiting: (token) => {
        const s = store.getState();
        if (s.sessionId) storeProfile(s.sessionId, token, s.name, s.color);
        s.setReconnecting(false);
        s.setPhase("waiting");
      },
      onJoinRequest: (r) => store.getState().addJoinRequest(r),
      onJoinResolved: (joinKey) => store.getState().removeJoinRequest(joinKey),
      onDenied: () => store.getState().setDenied("The host declined your request."),
      onKicked: () => store.getState().setDenied("You were removed from the session."),
      onSessionEnded: () => store.getState().setDenied("The host ended this session."),
      onAdminChanged: (clientId) => {
        if (connRef.current && clientId === connRef.current.clientId) store.getState().setRole("CREATOR");
        store.getState().setAdminOffer(false); // seat taken (by us or anyone): the offer is moot
      },
      onAdminOffer: (open) => store.getState().setAdminOffer(open),
      onUnavailable: () => {
        const s = store.getState();
        if (s.phase !== "denied") s.setDenied("This session was not found or is no longer available.");
      },
      onReconnecting: () => store.getState().setConnection("reconnecting"),
      onDisconnected: () => store.getState().setConnection("disconnected"),
    }),
    [cursors, store],
  );

  function startConnection(p: { sessionId: string; name: string; color: string; token: string | null }) {
    connRef.current?.close();
    const conn = new SessionConnection(
      { sessionId: p.sessionId, clientId: newClientId(), name: p.name, color: p.color, token: p.token },
      handlers,
    );
    connRef.current = conn;
    store.getState().setIdentity({
      sessionId: p.sessionId,
      clientId: conn.clientId,
      name: p.name,
      color: p.color,
      token: p.token,
    });
    conn.connect();
  }

  // Decide the initial screen from the URL: returning member -> reconnect;
  // shared link -> join; otherwise -> create.
  useEffect(() => {
    const sid = new URLSearchParams(location.search).get("sessionId");
    const s = store.getState();
    if (sid) {
      const token = storedToken(sid);
      const name = storedName(sid);
      const color = storedColor(sid) || DEFAULT_COLOR;
      if (token && name) {
        s.setReconnecting(true);
        startConnection({ sessionId: sid, name, color, token });
      } else {
        s.setIdentity({ sessionId: sid, clientId: "", name: s.name, color: s.color, token: null });
        s.setPhase("join");
      }
    } else {
      s.setPhase("create");
    }
    return () => connRef.current?.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const controller = useMemo<SessionController>(
    () => ({
      cursors,
      async createAndConnect(name, color) {
        const data = await createSession(name, color);
        storeProfile(data.sessionId, data.adminToken, name, color);
        history.replaceState({}, "", "?sessionId=" + encodeURIComponent(data.sessionId));
        startConnection({ sessionId: data.sessionId, name, color, token: data.adminToken });
      },
      askToJoin(name, color) {
        const sid = store.getState().sessionId;
        if (!sid) return;
        storeProfile(sid, null, name, color);
        store.getState().setPhase("waiting");
        startConnection({ sessionId: sid, name, color, token: storedToken(sid) });
      },
      cancel() {
        connRef.current?.close();
        connRef.current = null;
        store.getState().setPhase(store.getState().sessionId ? "join" : "create");
      },
      startOwnSession() {
        connRef.current?.close();
        connRef.current = null;
        history.replaceState({}, "", location.pathname);
        store.getState().reset();
        store.getState().setPhase("create");
      },
      approve: (joinKey) => connRef.current?.approve(joinKey),
      reject: (joinKey) => connRef.current?.reject(joinKey),
      kick: (clientId) => connRef.current?.kick(clientId),
      endSession: () => connRef.current?.endSession(),
      acceptAdmin: () => {
        store.getState().setAdminOffer(false);
        connRef.current?.acceptAdmin();
      },
      declineAdmin: () => {
        store.getState().setAdminOffer(false);
        connRef.current?.declineAdmin();
      },
      localInsert: (pos, text) => connRef.current?.localInsert(pos, text),
      localDelete: (pos, length) => connRef.current?.localDelete(pos, length),
      sendCursor: (pos) => connRef.current?.sendCursor(pos),
      rename: (baseName) => {
        const clean = sanitizeBaseName(baseName);
        // Optimistic: the server echo re-applies the authoritative (identically
        // sanitized) value; concurrent renames converge last-write-wins.
        store.getState().setFileName(clean);
        connRef.current?.rename(clean);
      },
      setLanguage: (language) => {
        // Optimistic like rename; the server echo carries the authoritative value.
        store.getState().setLanguageChoice(language);
        connRef.current?.setLanguage(language);
      },
      setDocHost: (host) => connRef.current?.setDocHost(host),
      getDocument: () => connRef.current?.getDocumentText() ?? "",
      inviteLink: () => {
        const sid = store.getState().sessionId ?? "";
        return `${location.origin}/?sessionId=${encodeURIComponent(sid)}`;
      },
    }),
    [cursors, store],
  );

  return <Ctx.Provider value={controller}>{children}</Ctx.Provider>;
}
