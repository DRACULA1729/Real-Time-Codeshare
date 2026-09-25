import hljs from "highlight.js/lib/common";
import type { Extension } from "@codemirror/state";
import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { java } from "@codemirror/lang-java";
import { cpp } from "@codemirror/lang-cpp";
import { css } from "@codemirror/lang-css";
import { html } from "@codemirror/lang-html";
import { json } from "@codemirror/lang-json";
import { sql } from "@codemirror/lang-sql";
import { tantuHljs, tantuLanguage } from "./tantu";

hljs.registerLanguage("tantu", tantuHljs);

export interface DetectedLanguage {
  /** highlight.js id, also shown (uppercased) in the presence-strip chip. */
  id: string;
  /** Human label for the chip. */
  label: string;
  /** Default file extension for downloads. */
  ext: string;
  /** CodeMirror language support (may be empty for plain text). */
  extension: Extension;
}

interface Entry {
  label: string;
  ext: string;
  make: () => Extension;
}

// hljs language id -> CodeMirror support + download extension. Languages without
// a bundled CM grammar fall back to plain text (no syntax, still editable).
const TABLE: Record<string, Entry> = {
  javascript: { label: "JavaScript", ext: "js", make: () => javascript() },
  typescript: { label: "TypeScript", ext: "ts", make: () => javascript({ typescript: true }) },
  jsx: { label: "JSX", ext: "jsx", make: () => javascript({ jsx: true }) },
  tsx: { label: "TSX", ext: "tsx", make: () => javascript({ jsx: true, typescript: true }) },
  python: { label: "Python", ext: "py", make: () => python() },
  java: { label: "Java", ext: "java", make: () => java() },
  cpp: { label: "C++", ext: "cpp", make: () => cpp() },
  c: { label: "C", ext: "c", make: () => cpp() },
  csharp: { label: "C#", ext: "cs", make: () => cpp() },
  css: { label: "CSS", ext: "css", make: () => css() },
  scss: { label: "SCSS", ext: "scss", make: () => css() },
  xml: { label: "HTML/XML", ext: "html", make: () => html() },
  html: { label: "HTML", ext: "html", make: () => html() },
  json: { label: "JSON", ext: "json", make: () => json() },
  sql: { label: "SQL", ext: "sql", make: () => sql() },
  tantu: { label: "Tantu", ext: "tn", make: () => tantuLanguage },
};

const PLAIN: DetectedLanguage = { id: "plaintext", label: "Plain Text", ext: "txt", extension: [] };

const SUBSET = Object.keys(TABLE).filter((k) => k !== "tsx" && k !== "jsx");

/** Choices for the language picker, alphabetical. */
export const LANGUAGE_OPTIONS = Object.entries(TABLE)
  .map(([id, e]) => ({ id, label: e.label }))
  .sort((a, b) => a.label.localeCompare(b.label));

/** Language for a known id; an id this build doesn't know shows as plain text. */
export function languageFor(id: string): DetectedLanguage {
  const entry = TABLE[id];
  if (!entry) return { id, label: id, ext: "txt", extension: [] };
  return { id, label: entry.label, ext: entry.ext, extension: entry.make() };
}

/** Auto-detect the language of `code` (mirrors the legacy hljs.highlightAuto). */
export function detectLanguage(code: string): DetectedLanguage {
  if (!code.trim()) return PLAIN;
  let id: string | undefined;
  try {
    id = hljs.highlightAuto(code, SUBSET).language;
  } catch {
    id = undefined;
  }
  if (!id) return PLAIN;
  return languageFor(id);
}

/** xml grammar also covers HTML; this returns a stable extension for the id. */
export function languageExtensionFor(id: string): Extension {
  return TABLE[id]?.make() ?? [];
}
