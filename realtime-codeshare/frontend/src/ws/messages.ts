import type { Op } from "../ot/ops";

export type Role = "CREATOR" | "JOINER";

export interface PresenceUser {
  clientId: string;
  name: string;
  color: string;
  role: Role;
}

// ---- server -> client (the exact camelCase `type` strings the backend emits) ----
export interface InitMsg {
  type: "init";
  document: string;
  version: number;
  token: string;
  role: Role;
  clientId?: string;
}
export interface OpMsg {
  type: "op";
  op: OpWire;
  version: number;
}
export interface ResyncMsg {
  type: "resync";
  document: string;
  version: number;
  hash: string; // SHA-256 hex; matches sha256Hex() on the client
  required?: boolean; // server-forced resync (client must rebase its unacked edits)
}
export interface CursorMsg {
  type: "cursor";
  clientId: string;
  pos: number;
}
export interface CursorRemoveMsg {
  type: "cursorRemove";
  clientId: string;
}
export interface PresenceMsg {
  type: "presence";
  users: PresenceUser[];
}
export interface WaitingMsg {
  type: "waitingForApproval";
  token: string;
}
export interface JoinRequestMsg {
  type: "joinRequest";
  joinKey: string;
  name: string;
  color: string;
}
export interface JoinResolvedMsg {
  type: "joinResolved";
  joinKey: string;
}
export interface JoinDeniedMsg {
  type: "joinDenied";
  reason?: string;
}
export interface AdminChangedMsg {
  type: "adminChanged";
  clientId: string;
}
export interface KickedMsg {
  type: "kicked";
}
export interface SessionEndedMsg {
  type: "sessionEnded";
}

export type WsInbound =
  | InitMsg
  | OpMsg
  | ResyncMsg
  | CursorMsg
  | CursorRemoveMsg
  | PresenceMsg
  | WaitingMsg
  | JoinRequestMsg
  | JoinResolvedMsg
  | JoinDeniedMsg
  | AdminChangedMsg
  | KickedMsg
  | SessionEndedMsg;

// The server serializes Op via Jackson: `type` is the enum name ("INSERT" /
// "DELETE"). Normalize to our lowercase wire model before transforming.
export interface OpWire {
  type: string;
  clientId: string;
  baseVersion?: number;
  pos: number;
  text?: string;
  length?: number;
}

export function normalizeOp(w: OpWire): Op {
  const type = String(w.type).toLowerCase();
  if (type === "insert") {
    return {
      type: "insert",
      clientId: w.clientId,
      baseVersion: w.baseVersion ?? 0,
      pos: w.pos,
      text: w.text ?? "",
    };
  }
  return {
    type: "delete",
    clientId: w.clientId,
    baseVersion: w.baseVersion ?? 0,
    pos: w.pos,
    length: w.length ?? 0,
  };
}

// ---- client -> server ----
export type WsOutbound =
  | Op // insert | delete
  | { type: "cursor"; clientId: string; pos: number; sessionId: string }
  | { type: "resync" }
  | { type: "approve"; joinKey: string }
  | { type: "reject"; joinKey: string }
  | { type: "kick"; clientId: string }
  | { type: "endsession" };
