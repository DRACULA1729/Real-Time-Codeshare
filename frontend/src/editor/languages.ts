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

// highlightAuto runs every grammar over the text and is super-linear on long
// unbroken runs (70k chars of one token took ~60s, on every client, after every
// edit). The head of a file identifies its language; 2,000 chars stays ~50ms worst case.
const DETECT_SAMPLE = 2000;

// Comment lines say almost nothing about the language ("#" is Python, Ruby, shell,
// YAML, Tantu...), yet a chatty document full of them drowns out the few lines of
// real code. Detect on the code lines when there are enough of them; otherwise
// (a script that is mostly comments) fall back to the whole text. Preprocessor
// lines (#include, #define, #pragma) and a shebang are code, not comments.
const COMMENT_LINE = /^\s*(#(?!(include|define|pragma|if|ifdef|ifndef|endif|else|elif|!)\b)|\/\/|--|\/\*|\*)/;
const MIN_CODE = { lines: 2, chars: 40 };

function codeOnly(sample: string): string {
  const lines = sample.split("\n").filter((l) => l.trim() && !COMMENT_LINE.test(l));
  const code = lines.join("\n");
  return lines.length >= MIN_CODE.lines && code.length >= MIN_CODE.chars ? code : sample;
}

/** Auto-detect the language of `code` (mirrors the legacy hljs.highlightAuto). */
export function detectLanguage(code: string): DetectedLanguage {
  if (!code.trim()) return PLAIN;
  const head = code.slice(0, DETECT_SAMPLE);
  // highlight.js always names a winner, even for chat. Code in every listed
  // language is dense with punctuation; prose is not. Below the line it's text.
  if (!looksLikeCode(head)) return PLAIN;
  const sample = codeOnly(head);
  let id: string | undefined;
  try {
    id = hljs.highlightAuto(sample, SUBSET).language;
  } catch {
    id = undefined;
  }
  if (!id) return PLAIN;
  // highlight.js scores Java as TypeScript surprisingly often (shared syntax, no
  // type annotations to tip it). Java's package/import lines or a typed main
  // method are unambiguous, so trust them over any score.
  if (id !== "java" && JAVA_TELLS.test(sample)) id = "java";
  return languageFor(id);
}

const CODE_PUNCT = /[;{}()[\]=<>:#$*/\\|&@`]/g;
const MIN_PUNCT_RATIO = 0.02;

function looksLikeCode(text: string): boolean {
  const punct = text.match(CODE_PUNCT)?.length ?? 0;
  return punct / text.length >= MIN_PUNCT_RATIO;
}

const JAVA_TELLS = /^\s*(package|import)\s+[\w.]+;|\bpublic\s+static\s+void\s+main\s*\(|\bSystem\.out\.print/m;

/** xml grammar also covers HTML; this returns a stable extension for the id. */
export function languageExtensionFor(id: string): Extension {
  return TABLE[id]?.make() ?? [];
}
