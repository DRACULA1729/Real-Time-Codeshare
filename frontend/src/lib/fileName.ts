/** Max length of the shared base file name (matches the server's VARCHAR(64) column). */
export const MAX_BASENAME_LEN = 64;

/**
 * Normalize a user-supplied base file name: cut at the first dot (the extension is
 * derived from language detection, never user-set), strip control chars and path
 * separators, trim, cap at MAX_BASENAME_LEN. May return "" (displayed as "untitled").
 * Must match OTService.sanitizeBaseName on the server — the rename broadcast echoes
 * the server-sanitized value, and matching rules keep the optimistic update stable.
 */
export function sanitizeBaseName(raw: string): string {
  let s = raw ?? "";
  const dot = s.indexOf(".");
  if (dot >= 0) s = s.slice(0, dot);
  // eslint-disable-next-line no-control-regex
  s = s.replace(/[\u0000-\u001f\u007f/\\]/g, "").trim();
  return s.slice(0, MAX_BASENAME_LEN);
}

/** Display name for the sidebar: base name (or "untitled") plus the detected extension. */
export function displayFileName(base: string, ext: string): string {
  return `${base.trim() || "untitled"}.${ext}`;
}
