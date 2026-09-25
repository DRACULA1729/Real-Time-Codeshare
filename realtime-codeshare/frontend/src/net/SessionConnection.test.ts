import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Op } from "../ot/ops";

const mintTicket = vi.fn(async () => ({ ticket: "tk", ttlSeconds: 30 }));
vi.mock("../api/client", () => ({ mintTicket, ApiError: class extends Error {} }));

const RECONNECT_WAIT_MS = 2_000; // first backoff step is <= 1s (with jitter)

class FakeWebSocket {
  static OPEN = 1;
  static instances: FakeWebSocket[] = [];
  readyState = FakeWebSocket.OPEN;
  sent: unknown[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
  }
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close() {}
  deliver(msg: object) {
    this.onmessage?.({ data: JSON.stringify(msg) });
  }
}

const op = (clientId: string, pos: number, text: string) => ({ type: "INSERT", clientId, pos, text });
const drop = (ws: FakeWebSocket) => {
  ws.readyState = 3;
  ws.onclose?.();
};
const inserts = (ws: FakeWebSocket) => ws.sent.filter((m) => (m as { type: string }).type === "insert") as { text: string }[];

describe("SessionConnection auto-reconnect", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.stubGlobal("location", { protocol: "http:", host: "localhost" });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    FakeWebSocket.instances = [];
  });

  async function setup() {
    const { SessionConnection } = await import("./SessionConnection");
    const onReconnecting = vi.fn();
    // Every other handler is a no-op.
    const handlers = new Proxy({ onReconnecting }, { get: (t, k) => (t as Record<string | symbol, unknown>)[k] ?? (() => {}) });
    const conn = new SessionConnection(
      { sessionId: "S", clientId: "c1", name: "A", color: "#fff", token: "tok" },
      handlers as never,
    );
    const doc = { text: "" };
    conn.setDocHost({
      getText: () => doc.text,
      replaceText: (t) => (doc.text = t),
      applyRemote: (o: Op) => {
        doc.text = o.type === "insert"
          ? doc.text.slice(0, o.pos) + o.text + doc.text.slice(o.pos)
          : doc.text.slice(0, o.pos) + doc.text.slice(o.pos + o.length);
      },
    });
    conn.connect();
    await vi.advanceTimersByTimeAsync(0);
    return { conn, doc, first: FakeWebSocket.instances[0], onReconnecting };
  }

  async function reconnect() {
    await vi.advanceTimersByTimeAsync(RECONNECT_WAIT_MS);
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  }

  it("redials with a fresh ticket, catches up, then sends the edit made offline", async () => {
    const { conn, doc, first, onReconnecting } = await setup();
    first.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });

    drop(first); // proxy drops the socket; the user keeps typing
    expect(onReconnecting).toHaveBeenCalled();
    doc.text = "hi";
    conn.localInsert(0, "hi");

    const second = await reconnect();
    expect(mintTicket).toHaveBeenLastCalledWith("S", "tok", "c1");
    second.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });
    expect(second.sent).toContainEqual({ type: "catchup", from: 0 });
    expect(inserts(second)).toHaveLength(0); // held until caught up
    expect(doc.text).toBe("hi"); // the local copy is kept, not replaced

    second.deliver({ type: "caughtUp", version: 0 });
    expect(second.sent).toContainEqual(expect.objectContaining({ type: "insert", text: "hi", baseVersion: 0 }));
  });

  it("transforms an offline edit past ops it missed, so it lands where it was typed", async () => {
    const { conn, doc, first } = await setup();
    first.deliver({ type: "init", document: "abc", version: 0, token: "tok", role: "JOINER" });
    doc.text = "abc";
    drop(first);
    doc.text = "abc!";
    conn.localInsert(3, "!"); // typed after "abc" while offline

    const second = await reconnect();
    second.deliver({ type: "init", document: "XYabc", version: 1, token: "tok", role: "JOINER" });
    second.deliver({ type: "op", op: op("other", 0, "XY"), version: 1 }); // missed while offline
    second.deliver({ type: "caughtUp", version: 1 });

    expect(doc.text).toBe("XYabc!");
    expect(second.sent).toContainEqual(expect.objectContaining({ type: "insert", text: "!", pos: 5, baseVersion: 1 }));
  });

  it("does not resend an edit that was committed just before the drop (ack lost)", async () => {
    const { conn, doc, first } = await setup();
    first.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });
    doc.text = "hi";
    conn.localInsert(0, "hi"); // sent on the first socket, committed as v1...
    drop(first);               // ...but the ack never arrived

    const second = await reconnect();
    second.deliver({ type: "init", document: "hi", version: 1, token: "tok", role: "JOINER" });
    second.deliver({ type: "op", op: op("c1", 0, "hi"), version: 1 }); // our own op = the lost ack
    second.deliver({ type: "caughtUp", version: 1 });

    expect(doc.text).toBe("hi");
    expect(inserts(second)).toHaveLength(0);
  });

  it("applies ops that arrive out of order in version order", async () => {
    const { doc, first } = await setup();
    first.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });
    first.deliver({ type: "op", op: op("other", 1, "b"), version: 2 }); // ahead of a gap: held
    expect(doc.text).toBe("");
    first.deliver({ type: "op", op: op("other", 0, "a"), version: 1 });
    expect(doc.text).toBe("ab");
  });

  it("retries a rate-limited op instead of waiting forever", async () => {
    const { conn, first } = await setup();
    first.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });
    conn.localInsert(0, "a");
    expect(inserts(first)).toHaveLength(1);
    first.deliver({ type: "nack", reason: "rate" });
    await vi.advanceTimersByTimeAsync(500);
    expect(inserts(first)).toHaveLength(2);
  });

  it("splits a huge paste into frames under the server cap", async () => {
    const { conn, first } = await setup();
    first.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });
    conn.localInsert(0, "x".repeat(20_000));
    expect(inserts(first)).toHaveLength(1); // one in flight; the rest wait for acks
    expect(inserts(first)[0].text.length).toBe(8000);
  });
});
