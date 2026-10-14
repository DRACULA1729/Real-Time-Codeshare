import type { DeleteOp, InsertOp, Op } from "./ops";

// Operational-transform engine — a verbatim port of the legacy client
// (codeshare.js transformII/ID/DI/DD) and semantically identical to the server
// (OTService.OTEngine). transform(a, b) returns a copy of `a` rebased so it can
// be applied after `b`. Correctness depends on the tie-break and clamping
// matching the server byte-for-byte; do not "clean this up".

// Code-unit comparison so the insert tie-break is identical to Java's
// String.compareTo (localeCompare can order differently and cause divergence).
function clientWinsInsertTie(a: string, b: string): boolean {
  return String(a) < String(b);
}

function copyOp<T extends Op>(op: T): T {
  return JSON.parse(JSON.stringify(op)) as T;
}

export function transform(a: Op, b: Op): Op {
  if (a.type === "insert" && b.type === "insert") return transformII(copyOp(a), b);
  if (a.type === "insert" && b.type === "delete") return transformID(copyOp(a), b);
  if (a.type === "delete" && b.type === "insert") return transformDI(copyOp(a), b);
  return transformDD(copyOp(a as DeleteOp), b as DeleteOp);
}

function transformII(a: InsertOp, b: InsertOp): InsertOp {
  if (a.pos < b.pos) {
    // no change
  } else if (a.pos > b.pos) {
    a.pos += b.text.length;
  } else if (!clientWinsInsertTie(a.clientId, b.clientId)) {
    a.pos += b.text.length;
  }
  return a;
}

function transformID(a: InsertOp, b: DeleteOp): InsertOp {
  if (a.pos <= b.pos) {
    // no change
  } else if (a.pos > b.pos + b.length) {
    a.pos -= b.length;
  } else {
    a.pos = b.pos;
  }
  return a;
}

function transformDI(a: DeleteOp, b: InsertOp): DeleteOp {
  if (b.pos <= a.pos) {
    a.pos += b.text.length;
  } else if (b.pos < a.pos + a.length) {
    a.length += b.text.length;
  }
  return a;
}

function transformDD(a: DeleteOp, b: DeleteOp): DeleteOp {
  const aStart = a.pos;
  const aEnd = a.pos + a.length;
  const bStart = b.pos;
  const bEnd = b.pos + b.length;

  if (aEnd <= bStart) {
    // no change
  } else if (aStart >= bEnd) {
    a.pos -= b.length;
  } else {
    const overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
    a.length -= overlap;
    if (aStart >= bStart) a.pos -= Math.max(0, Math.min(bEnd, aStart) - bStart);
  }
  if (a.length < 0) a.length = 0;
  return a;
}
