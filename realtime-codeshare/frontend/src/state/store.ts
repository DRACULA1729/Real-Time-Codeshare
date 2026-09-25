import { create } from "zustand";
import type { PresenceUser, Role } from "../ws/messages";

export type Phase = "create" | "join" | "waiting" | "workspace" | "denied";
export type ConnectionState = "connecting" | "online" | "reconnecting" | "disconnected";

export interface JoinRequest {
  joinKey: string;
  name: string;
  color: string;
}

interface SessionState {
  phase: Phase;
  sessionId: string | null;
  clientId: string | null;
  name: string;
  color: string;
  token: string | null;
  role: Role;
  version: number;
  initialDocument: string; // seed for the editor on admission
  users: PresenceUser[];
  joinRequests: JoinRequest[];
  fileName: string;
  language: string;
  languageExt: string;
  /** Language id picked for the room ("" = auto-detect); shared by everyone. */
  languageChoice: string;
  connection: ConnectionState;
  deniedReason: string | null;
  reconnecting: boolean;
  adminOffer: boolean; // the host left and the seat is offered to us
  cursorLine: number;
  cursorCol: number;

  setPhase: (phase: Phase) => void;
  setReconnecting: (v: boolean) => void;
  setCursorPos: (line: number, col: number) => void;
  setIdentity: (p: { sessionId: string; clientId: string; name: string; color: string; token: string | null }) => void;
  applyInit: (p: { document: string; version: number; role: Role; token: string; fileName: string; languageChoice: string }) => void;
  setRole: (role: Role) => void;
  setVersion: (version: number) => void;
  setPresence: (users: PresenceUser[]) => void;
  addJoinRequest: (r: JoinRequest) => void;
  removeJoinRequest: (joinKey: string) => void;
  setConnection: (c: ConnectionState) => void;
  setDenied: (reason: string) => void;
  setAdminOffer: (v: boolean) => void;
  setFileName: (name: string) => void;
  setLanguage: (lang: string, ext: string) => void;
  setLanguageChoice: (id: string) => void;
  reset: () => void;
}

const initial = {
  phase: "create" as Phase,
  sessionId: null as string | null,
  clientId: null as string | null,
  name: "Ghost",
  color: "#60A5FA",
  token: null as string | null,
  role: "JOINER" as Role,
  version: 0,
  initialDocument: "",
  users: [] as PresenceUser[],
  joinRequests: [] as JoinRequest[],
  fileName: "",
  language: "Plain Text",
  languageExt: "txt",
  languageChoice: "",
  connection: "connecting" as ConnectionState,
  deniedReason: null as string | null,
  reconnecting: false,
  adminOffer: false,
  cursorLine: 1,
  cursorCol: 1,
};

export const useSessionStore = create<SessionState>((set) => ({
  ...initial,

  setPhase: (phase) => set({ phase }),
  setReconnecting: (reconnecting) => set({ reconnecting }),
  setCursorPos: (cursorLine, cursorCol) => set({ cursorLine, cursorCol }),
  setIdentity: ({ sessionId, clientId, name, color, token }) =>
    set({ sessionId, clientId, name, color, token }),
  applyInit: ({ document, version, role, token, fileName, languageChoice }) =>
    // fileName/languageChoice ride along atomically: a reconnect adopts the
    // authoritative values, covering changes broadcast while this client was away.
    set({ initialDocument: document, version, role, token, fileName, languageChoice, phase: "workspace", connection: "online", reconnecting: false, adminOffer: false }),
  setRole: (role) => set({ role }),
  setVersion: (version) => set({ version }),
  setPresence: (users) => set({ users }),
  addJoinRequest: (r) =>
    set((s) => (s.joinRequests.some((j) => j.joinKey === r.joinKey) ? s : { joinRequests: [...s.joinRequests, r] })),
  removeJoinRequest: (joinKey) => set((s) => ({ joinRequests: s.joinRequests.filter((j) => j.joinKey !== joinKey) })),
  setConnection: (connection) => set({ connection }),
  setDenied: (reason) => set({ phase: "denied", deniedReason: reason, reconnecting: false }),
  setAdminOffer: (adminOffer) => set({ adminOffer }),
  setFileName: (fileName) => set({ fileName }),
  setLanguage: (language, languageExt) => set({ language, languageExt }),
  setLanguageChoice: (languageChoice) => set({ languageChoice }),
  reset: () => set({ ...initial }),
}));
