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

  it("redials with a fresh ticket after a drop and replays edits made while offline", async () => {
    const { SessionConnection } = await import("./SessionConnection");
    const onReconnecting = vi.fn();
    // Every other handler is a no-op.
    const handlers = new Proxy({ onReconnecting }, { get: (t, k) => (t as Record<string | symbol, unknown>)[k] ?? (() => {}) });
    const conn = new SessionConnection(
      { sessionId: "S", clientId: "c1", name: "A", color: "#fff", token: "tok" },
      handlers as never,
    );
    let text = "";
    conn.setDocHost({
      getText: () => text,
      replaceText: (t) => (text = t),
      applyRemote: (op: Op) => {
        text = op.type === "insert"
          ? text.slice(0, op.pos) + op.text + text.slice(op.pos)
          : text.slice(0, op.pos) + text.slice(op.pos + op.length);
      },
    });

    conn.connect();
    await vi.advanceTimersByTimeAsync(0);
    const first = FakeWebSocket.instances[0];
    first.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });

    // Proxy drops the socket; the user keeps typing while we're offline.
    first.readyState = 3;
    first.onclose?.();
    expect(onReconnecting).toHaveBeenCalled();
    text = "hi";
    conn.localInsert(0, "hi");

    await vi.advanceTimersByTimeAsync(RECONNECT_WAIT_MS);
    expect(mintTicket).toHaveBeenLastCalledWith("S", "tok", "c1");
    const second = FakeWebSocket.instances[1];
    expect(second).toBeDefined();

    second.deliver({ type: "init", document: "", version: 0, token: "tok", role: "JOINER" });
    expect(text).toBe("hi");
    expect(second.sent).toContainEqual(expect.objectContaining({ type: "insert", text: "hi", baseVersion: 0 }));
  });
});
