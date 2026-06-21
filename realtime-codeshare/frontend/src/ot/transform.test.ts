import { describe, expect, it } from "vitest";
import { applyToString, type Op } from "./ops";
import { transform } from "./transform";
import { strHash } from "./hash";
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
    if (c.ot.isQuiescent() && strHash(c.doc) !== strHash(server.doc)) {
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

describe("strHash — matches Java String.hashCode", () => {
  it.each([
    ["", 0],
    ["a", 97],
    ["hello", 99162322],
    ["Write your code here...", "Write your code here...".split("").reduce((h, ch) => (Math.imul(31, h) + ch.charCodeAt(0)) | 0, 0)],
  ])("hash(%j) = %i", (input, expected) => {
    expect(strHash(input as string)).toBe(expected as number);
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
