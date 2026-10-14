import { StreamLanguage } from "@codemirror/language";
import type { HLJSApi, Language } from "highlight.js";

// Tantu (https://tantu-play.netlify.app/): indentation-based, `#` line comments,
// double-quoted strings, `fn name(...) -> Type:` signatures, `=>` match arms.
const KEYWORDS = [
  "fn", "let", "var", "if", "elif", "else", "while", "for", "in", "return", "match",
  "enum", "struct", "type", "import", "loop", "break", "spawn",
];
const TYPES = ["Int", "Float", "Bool", "Str", "Unit", "List", "Option", "Result", "Chan"];
const CONSTRUCTORS = ["Some", "None", "Ok", "Err"];
const BUILTINS = [
  "print", "str", "len", "push", "get", "concat", "range", "float", "trunc", "parse_int",
  "split", "substring", "chars", "chan", "send", "recv", "close", "sleep",
];

/** highlight.js grammar, used only for auto-detection scoring. */
export function tantuHljs(hljs: HLJSApi): Language {
  return {
    name: "Tantu",
    aliases: ["tn"],
    keywords: {
      keyword: KEYWORDS.join(" "),
      literal: "true false",
      type: TYPES.join(" "),
      built_in: [...BUILTINS, ...CONSTRUCTORS].join(" "),
    },
    contains: [
      hljs.HASH_COMMENT_MODE,
      hljs.QUOTE_STRING_MODE,
      hljs.C_NUMBER_MODE,
      // Shapes no other bundled grammar uses — these carry the detection score.
      { match: /\bfn\s+[A-Za-z_]\w*(?=\s*[[(])/, relevance: 10 },
      { match: /->\s*[A-Z][\w[\], ]*:/, relevance: 5 },
      { match: /^\s*(enum|struct)\s+[A-Z]\w*\s*:/, relevance: 5 },
    ],
  };
}

const keywordSet = new Set(KEYWORDS);
const typeSet = new Set([...TYPES, ...CONSTRUCTORS]);

/** CodeMirror syntax highlighting. */
export const tantuLanguage = StreamLanguage.define({
  name: "tantu",
  token(stream) {
    if (stream.eatSpace()) return null;
    if (stream.peek() === "#") {
      stream.skipToEnd();
      return "comment";
    }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/)) return "string";
    if (stream.match(/^\d+(?:\.\d+)?/)) return "number";
    if (stream.match(/^(?:->|=>|==|!=|<=|>=|[-+*/%=<>?])/)) return "operator";
    if (stream.match(/^[A-Za-z_]\w*/)) {
      const word = stream.current();
      if (keywordSet.has(word)) return "keyword";
      if (word === "true" || word === "false") return "bool";
      if (typeSet.has(word) || /^[A-Z]/.test(word)) return "typeName";
      if (stream.peek() === "(" || stream.peek() === "[") return "variableName.function";
      return "variableName";
    }
    stream.next();
    return "punctuation";
  },
  languageData: { commentTokens: { line: "#" } },
});
