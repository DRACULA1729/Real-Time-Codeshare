// Typed REST client for SessionController + the GitHub controllers. Paths are
// same-origin (the SPA is served by Spring Boot, and dev proxies /api -> :8080).

export interface CreateSessionResponse {
  sessionId: string;
  adminToken: string;
  wsUrl: string;
}

export interface ExistsResponse {
  exists: boolean;
  alive: boolean;
}

export interface GitHubStatus {
  authorized: boolean;
  configured: boolean;
}

export interface GitHubPushRequest {
  owner: string;
  repo: string;
  path: string;
  branch?: string;
  message?: string;
  sessionId: string;
  sessionToken: string;
}

export interface GitHubPushResponse {
  ok: boolean;
  path: string;
  branch: string;
  commitSha: string;
  htmlUrl: string;
}

// Double-submit CSRF: echo the JS-readable XSRF-TOKEN cookie in a request header.
// The backend sets the cookie on first contact and requires the header on unsafe
// /api requests.
function csrfHeaders(base: Record<string, string> = {}): Record<string, string> {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? { ...base, "X-CSRF-Token": decodeURIComponent(m[1]) } : base;
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const resp = await fetch(url, {
    method: "POST",
    headers: csrfHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
  });
  const data = (await resp.json().catch(() => ({}))) as T & { error?: string };
  if (!resp.ok) {
    throw new ApiError((data as { error?: string }).error || `request failed (${resp.status})`, resp.status, data);
  }
  return data;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function createSession(name: string, color: string): Promise<CreateSessionResponse> {
  return postJson<CreateSessionResponse>("/api/sessions", { name, color });
}

export interface TicketResponse {
  ticket: string;
  ttlSeconds: number;
}

/** Mint a single-use connection ticket so the token travels in this POST body
 *  (over HTTPS) instead of the WebSocket URL. `token` is null for a new joiner. */
export async function mintTicket(
  sessionId: string,
  token: string | null,
  clientId: string,
): Promise<TicketResponse> {
  return postJson<TicketResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/ticket`, { token, clientId });
}

export async function sessionExists(id: string): Promise<ExistsResponse> {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(id)}/exists`);
  if (!resp.ok) throw new ApiError(`exists check failed (${resp.status})`, resp.status, null);
  return (await resp.json()) as ExistsResponse;
}

export const github = {
  async status(): Promise<GitHubStatus> {
    const resp = await fetch("/api/github/status");
    if (!resp.ok) throw new ApiError(`github status failed (${resp.status})`, resp.status, null);
    return (await resp.json()) as GitHubStatus;
  },

  /** Full-page redirect out to GitHub; `returnTo` brings the user back here. */
  authorize(returnTo: string): void {
    window.location.href = "/api/github/authorize?returnTo=" + encodeURIComponent(returnTo);
  },

  async disconnect(): Promise<void> {
    await fetch("/api/github/disconnect", { method: "POST", headers: csrfHeaders() });
  },

  async push(req: GitHubPushRequest): Promise<GitHubPushResponse> {
    return postJson<GitHubPushResponse>("/api/github/push", req);
  },
};
