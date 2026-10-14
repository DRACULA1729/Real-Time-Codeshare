// Per-session identity persistence (mirrors codeshare.js cs-<key>:<sessionId>).
// The token survives reconnects — notably the GitHub OAuth full-page round-trip,
// after which we reconnect straight to the workspace without re-asking to join.

const key = (part: string, sessionId: string) => `cs-${part}:${sessionId}`;

export function storeProfile(sessionId: string, token: string | null, name?: string, color?: string): void {
  if (!sessionId) return;
  if (token) sessionStorage.setItem(key("token", sessionId), token);
  if (name) sessionStorage.setItem(key("name", sessionId), name);
  if (color) sessionStorage.setItem(key("color", sessionId), color);
}

export const storedToken = (sessionId: string) => sessionStorage.getItem(key("token", sessionId));
export const storedName = (sessionId: string) => sessionStorage.getItem(key("name", sessionId));
export const storedColor = (sessionId: string) => sessionStorage.getItem(key("color", sessionId));

// Client id is per-tab (a fresh random id each load), exactly like the legacy client.
export const newClientId = (): string => "client-" + Math.floor(Math.random() * 1e9);
