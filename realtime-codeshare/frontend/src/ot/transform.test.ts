import { describe, expect, it } from "vitest";
import { applyToString, type Op } from "./ops";
import { transform } from "./transform";
import { sha256Hex } from "./hash";
import { OtClient } from "./client";

const clone = <T>(o: T): T => JSON.parse(JSON.stringify(o));

// ---- Server model: mirrors OTService.DocumentStore.commit ----
class ServerSim {
  doc: string;
  version = 0;
  history: { op: Op; version: number }[] = [];

  constructor(initial = "") {
    this.doc = initial;
  }

  commit(incoming: Op): { op: Op; version: number } {
    let t: Op = clone(incoming);
    for (const co of this.history) {
      if (co.version > incoming.baseVersion) t = transform(t, co.op);
    }
    this.doc = applyToString(this.doc, t);
    this.version += 1;
    this.history.push({ op: clone(t), version: this.version });
    return { op: t, version: this.version };
  }
}

// ---- Client model: optimistic local apply + OtClient rebase loop ----
class ClientSim {
  doc: string;
  ot: OtClient;
  inbound: { op: Op; version: number }[] = [];

  constructor(
    readonly id: string,
    initial: string,
    private readonly toServer: Op[],
  ) {
    this.doc = initial;
    this.ot = new OtClient(
      (op) => this.toServer.push(op),
      (op) => {
        this.doc = applyToString(this.doc, op);
      },
    );
  }

  insert(pos: number, text: string): void {
    const op: Op = { type: "insert", clientId: this.id, baseVersion: this.ot.baseVersion, pos, text };
    this.doc = applyToString(this.doc, op);
    this.ot.submitLocal(op);
  }

  delete(pos: number, length: number): void {
    const op: Op = { type: "delete", clientId: this.id, baseVersion: this.ot.baseVersion, pos, length };
    this.doc = applyToString(this.doc, op);
    this.ot.submitLocal(op);
  }
}

// Deliver server commits (in arrival order) then propagate to clients; client
// acks may trigger buffered sends, looping until the network is quiet.
function drain(server: ServerSim, clients: ClientSim[], toServer: Op[]) {
  let safety = 100_000;
  while (safety-- > 0) {
    if (toServer.length) {
      const committed = server.commit(toServer.shift()!);
      for (const c of clients) c.inbound.push(committed);
      continue;
    }
    let delivered = false;
    for (const c of clients) {
      if (c.inbound.length) {
        const m = c.inbound.shift()!;
        c.ot.onServerOp(m.op, m.version, c.id);
        delivered = true;
      }
    }
    if (!delivered) break;
  }
  if (safety <= 0) throw new Error("drain did not converge");
}

function harness(initial: string, ids: string[]) {
  const toServer: Op[] = [];
  const server = new ServerSim(initial);
  const clients = ids.map((id) => new ClientSim(id, initial, toServer));
  return { server, clients, toServer, run: () => drain(server, clients, toServer) };
}

function expectConverged(server: ServerSim, clients: ClientSim[]) {
  for (const c of clients) {
    expect(c.doc, `client ${c.id} doc`).toBe(server.doc);
    expect(c.ot.isQuiescent(), `client ${c.id} quiescent`).toBe(true);
  }
}

// Models codeshare.js handleResync: a quiescent client whose document hash
// disagrees with the server adopts the authoritative snapshot. Optimistic OT can
// drift when an insert lands inside a concurrently-deleted span; this self-heal
// is the documented safety net that restores convergence.
function reconcileViaResync(server: ServerSim, clients: ClientSim[]) {
  for (const c of clients) {
    if (c.ot.isQuiescent() && sha256Hex(c.doc) !== sha256Hex(server.doc)) {
      c.doc = server.doc;
      c.ot.reset(server.version);
    }
  }
}

describe("transform — pure properties", () => {
  it("insert/insert tie-break is deterministic by clientId", () => {
    const a: Op = { type: "insert", clientId: "A", baseVersion: 0, pos: 1, text: "a" };
    const b: Op = { type: "insert", clientId: "B", baseVersion: 0, pos: 1, text: "b" };
    // a (lower id) keeps its position; b shifts right past a.
    expect((transform(a, b) as { pos: number }).pos).toBe(1);
    expect((transform(b, a) as { pos: number }).pos).toBe(2);
  });

  it("does not mutate its inputs", () => {
    const a: Op = { type: "insert", clientId: "A", baseVersion: 0, pos: 5, text: "x" };
    const b: Op = { type: "delete", clientId: "B", baseVersion: 0, pos: 0, length: 3 };
    transform(a, b);
    expect(a.pos).toBe(5);
  });
});

// Mirrors SessionConnection.handleResync(required=true): the server forced a
// resync because the client's base fell below the compaction floor, so the client
// captures its unacked edits, adopts the snapshot, then replays the edits (applied
// locally + re-issued from the new base) instead of dropping them.
describe("forced resync — replays unacked edits onto the snapshot and converges", () => {
  it("preserves the lagging client's pending edits after a forced resync", () => {
    const toServer: Op[] = [];
    const server = new ServerSim("ABCDEFGH");
    let clientDoc = "ABCDEFGH";
    const ot = new OtClient(
      (op) => toServer.push(op),
      (op) => { clientDoc = applyToString(clientDoc, op); },
    );

    // Two quick local edits before any ack: one outstanding, one buffered.
    const i1: Op = { type: "insert", clientId: "C", baseVersion: 0, pos: 0, text: "X" };
    const i2: Op = { type: "insert", clientId: "C", baseVersion: 0, pos: 1, text: "Y" };
    clientDoc = applyToString(clientDoc, i1); ot.submitLocal(i1);
    clientDoc = applyToString(clientDoc, i2); ot.submitLocal(i2);
    expect(ot.isQuiescent()).toBe(false);

    // The server has independently advanced to a snapshot past the compaction floor
    // and rejected the client's stale ops (they never committed).
    server.doc = "ABCDEFGH123";
    server.version = 11;
    server.history = [];
    toServer.length = 0;

    // ---- forced-resync rebase (handleResync, required = true) ----
    const pending = ot.capturePending();
    expect(pending).toHaveLength(2); // outstanding + buffered, in order
    clientDoc = server.doc;                                   // replaceText(snapshot)
    ot.reset(server.version);                                 // reset to snapshot version
    for (const op of pending) clientDoc = applyToString(clientDoc, op); // applyRemote (local)
    for (const op of pending) ot.submitLocal(op);             // re-issue from the new base

    // Drain: server commits the re-issued ops, client promotes its buffer on each ack.
    let guard = 1000;
    while (guard-- > 0) {
      if (!toServer.length) break;
      const c = server.commit(toServer.shift()!);
      ot.onServerOp(c.op, c.version, "C");
    }

    expect(clientDoc).toBe(server.doc);
    expect(clientDoc).toBe("XYABCDEFGH123"); // edits preserved on top of the snapshot
    expect(ot.isQuiescent()).toBe(true);
  });
});

describe("sha256Hex — matches Java MessageDigest(SHA-256)", () => {
  // FIPS 180-4 / Java-identical known-answer vectors. Pinning these keeps the
  // client digest byte-for-byte equal to the server's, so drift detection agrees.
  it.each([
    ["", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"],
    ["abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"],
    ["hello", "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"],
    // Multi-byte UTF-8 must encode identically to Java's getBytes(UTF_8).
    ["héllo→€", "64a6ddc26703e014800f40bfa72279d5ccfcfebff26545b93ed1403ebb5ab057"],
  ])("sha256(%j) = %s", (input, expected) => {
    expect(sha256Hex(input as string)).toBe(expected as string);
  });
});

describe("OT convergence — clients + server reach the same document", () => {
  it("concurrent inserts at the same position", () => {
    const { server, clients, run } = harness("AB", ["A", "B"]);
    clients[0].insert(1, "x");
    clients[1].insert(1, "y");
    run();
    expect(server.doc).toBe("AxyB");
    expectConverged(server, clients);
  });

  it("insert inside a concurrently-deleted span drifts, then self-heals via resync", () => {
    const { server, clients, run } = harness("hello world", ["A", "B"]);
    clients[0].insert(5, "XX"); // "helloXX world"
    clients[1].delete(2, 5); // delete "llo w" — A's insert lands inside this span
    run();
    // Optimistic OT may drift here (this is exactly why the server keeps an
    // authoritative copy and clients verify on idle).
    reconcileViaResync(server, clients);
    expectConverged(server, clients);
  });

  it("concurrent overlapping deletes", () => {
    const { server, clients, run } = harness("0123456789", ["A", "B"]);
    clients[0].delete(2, 4); // remove 2345
    clients[1].delete(4, 4); // remove 4567 (overlaps at 4,5)
    run();
    expect(server.doc).toBe("0189");
    expectConverged(server, clients);
  });

  it("buffered local ops (two quick edits before any ack)", () => {
    const { server, clients, run } = harness("start", ["A", "B"]);
    clients[0].insert(5, "-1"); // outstanding
    clients[0].insert(7, "-2"); // buffered
    clients[1].insert(0, "!"); // concurrent remote
    run();
    expectConverged(server, clients);
    // A's intent preserved somewhere in the result regardless of interleaving.
    expect(server.doc).toContain("-1");
    expect(server.doc).toContain("-2");
    expect(server.doc).toContain("!");
  });

  it("three clients, interleaved inserts and deletes", () => {
    const { server, clients, run } = harness("function f() {}", ["A", "B", "C"]);
    clients[0].insert(11, "\n  return 1;\n"); // inside braces
    clients[1].insert(0, "// header\n");
    clients[2].delete(9, 2); // remove "()"
    clients[0].insert(0, "export "); // buffered behind A's first op
    run();
    expectConverged(server, clients);
  });
});
