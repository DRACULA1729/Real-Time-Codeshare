import type { Op } from "./ops";
import { transform } from "./transform";

// Jupiter-style client control loop, ported verbatim from codeshare.js.
//
// Exactly one op is in flight (`outstanding`) at a time; later local edits wait
// in `buffer` and are sent one-by-one as acks arrive. Pipelining every op with
// the same stale base version would make the server re-transform ops against
// edits they were already built on, and the documents would drift apart.
//
// This class is editor-agnostic: it calls `send` to put an op on the wire and
// `applyRemote` to apply a rebased remote op to the local document. The host
// (editor integration or a test harness) owns the actual document.
export class OtClient {
  baseVersion = 0;
  outstanding: Op | null = null;
  readonly buffer: Op[] = [];

  constructor(
    private readonly send: (op: Op) => void,
    private readonly applyRemote: (op: Op) => void,
  ) {}

  /** (Re)initialize from an `init`/`resync` snapshot version. */
  reset(version: number): void {
    this.baseVersion = version;
    this.outstanding = null;
    this.buffer.length = 0;
  }

  isQuiescent(): boolean {
    return this.outstanding === null && this.buffer.length === 0;
  }

  /** Un-acked local ops in order (outstanding first, then buffered), for resync
   *  replay. Returned as copies so the caller can re-submit them after a reset. */
  capturePending(): Op[] {
    const ops: Op[] = [];
    if (this.outstanding) ops.push({ ...this.outstanding });
    for (const op of this.buffer) ops.push({ ...op });
    return ops;
  }

  /** A local edit produced by the editor. Sent immediately if nothing is in
   *  flight, otherwise queued. */
  submitLocal(op: Op): void {
    if (this.outstanding === null) {
      this.outstanding = { ...op, baseVersion: this.baseVersion };
      this.send(this.outstanding);
    } else {
      this.buffer.push(op);
    }
  }

  /** A committed op broadcast by the server (including our own, echoed back). */
  onServerOp(serverOp: Op, version: number, ownClientId: string): void {
    if (serverOp.clientId === ownClientId) {
      // Ack for our outstanding op — promote the next buffered op, re-based to
      // the now-current version, so only ever one op is in flight.
      this.baseVersion = version;
      if (this.buffer.length > 0) {
        this.outstanding = { ...this.buffer.shift()!, baseVersion: this.baseVersion };
        this.send(this.outstanding);
      } else {
        this.outstanding = null;
      }
      return;
    }

    // Remote op: rebase it past our outstanding + buffered ops to apply it at
    // the right place locally, and rebase those past it so they stay valid.
    let r = serverOp;
    if (this.outstanding !== null) {
      const rNext = transform(r, this.outstanding);
      this.outstanding = transform(this.outstanding, r);
      r = rNext;
    }
    for (let i = 0; i < this.buffer.length; i++) {
      const rNext = transform(r, this.buffer[i]);
      this.buffer[i] = transform(this.buffer[i], r);
      r = rNext;
    }
    this.applyRemote(r);
    this.baseVersion = version;
  }
}
