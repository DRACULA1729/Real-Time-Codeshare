// Per-session identity persistence (mirrors codeshare.js cs-<key>:<sessionId>).
// The token survives reconnects, the GitHub OAuth full-page round-trip, and a
// closed tab: localStorage, not sessionStorage, so a host who closes the tab and
// reopens the invite link is still the host instead of a stranded joiner.

const key = (part: string, sessionId: string) => `cs-${part}:${sessionId}`;

export function storeProfile(sessionId: string, token: string | null, name?: string, color?: string): void {
  if (!sessionId) return;
  if (token) localStorage.setItem(key("token", sessionId), token);
  if (name) localStorage.setItem(key("name", sessionId), name);
  if (color) localStorage.setItem(key("color", sessionId), color);
}

export const storedToken = (sessionId: string) => localStorage.getItem(key("token", sessionId));
export const storedName = (sessionId: string) => localStorage.getItem(key("name", sessionId));
export const storedColor = (sessionId: string) => localStorage.getItem(key("color", sessionId));

// Client id is per-tab (a fresh random id each load), exactly like the legacy client.
export const newClientId = (): string => "client-" + Math.floor(Math.random() * 1e9);
