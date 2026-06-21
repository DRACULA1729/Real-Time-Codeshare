// Operation model — mirrors the server's OTService.OpDTO and the legacy client.
// `type` is lowercase on the wire ("insert" | "delete").

export interface InsertOp {
  type: "insert";
  clientId: string;
  baseVersion: number;
  pos: number;
  text: string;
}

export interface DeleteOp {
  type: "delete";
  clientId: string;
  baseVersion: number;
  pos: number;
  length: number;
}

export type Op = InsertOp | DeleteOp;

export function isInsert(op: Op): op is InsertOp {
  return op.type === "insert";
}

/** Apply an op to a plain string, clamping exactly like the server's commit(). */
export function applyToString(doc: string, op: Op): string {
  if (isInsert(op)) {
    const p = Math.max(0, Math.min(op.pos, doc.length));
    return doc.slice(0, p) + op.text + doc.slice(p);
  }
  const p = Math.max(0, Math.min(op.pos, doc.length));
  const end = Math.max(p, Math.min(doc.length, p + op.length));
  return doc.slice(0, p) + doc.slice(end);
}
