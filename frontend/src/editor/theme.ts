import { EditorView } from "@codemirror/view";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import type { Extension } from "@codemirror/state";
import type { Theme } from "../theme/ThemeProvider";

// Layout/chrome theme. Colors reference the same CSS variables as the rest of
// the UI, so editor chrome (background, gutter, active line, selection) follows
// the light/dark toggle automatically. Only syntax colors are swapped per theme.
export const editorBaseTheme: Extension = EditorView.theme({
  "&": {
    height: "100%",
    color: "rgb(var(--text-primary))",
    backgroundColor: "rgb(var(--editor-bg))",
  },
  ".cm-scroller": {
    fontFamily: '"JetBrains Mono", ui-monospace, monospace',
    fontSize: "14px",
    lineHeight: "22px",
  },
  ".cm-content": { padding: "16px 0", caretColor: "rgb(var(--primary))" },
  ".cm-gutters": {
    backgroundColor: "rgb(var(--editor-bg))",
    color: "rgb(var(--text-muted))",
    border: "none",
  },
  ".cm-lineNumbers .cm-gutterElement": { padding: "0 12px 0 20px", minWidth: "48px" },
  ".cm-activeLine": { backgroundColor: "rgb(var(--primary) / 0.05)" },
  ".cm-activeLineGutter": { backgroundColor: "transparent", color: "rgb(var(--text-primary))" },
  ".cm-cursor, &.cm-focused .cm-cursor": { borderLeftColor: "rgb(var(--primary))" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": {
    backgroundColor: "rgb(var(--primary) / 0.20)",
  },
  ".cm-content ::selection": { backgroundColor: "rgb(var(--primary) / 0.20)" },
  ".cm-foldPlaceholder": { backgroundColor: "transparent", border: "none", color: "rgb(var(--text-muted))" },
});

const darkHighlight = HighlightStyle.define([
  { tag: [t.keyword, t.controlKeyword, t.operatorKeyword, t.moduleKeyword], color: "#C084FC" },
  { tag: [t.string, t.special(t.string), t.regexp], color: "#34D399" },
  { tag: [t.number, t.bool, t.null, t.atom], color: "#60A5FA" },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: "#FBBF24" },
  { tag: [t.propertyName, t.attributeName], color: "#60A5FA" },
  { tag: [t.typeName, t.className, t.namespace], color: "#FBBF24" },
  { tag: [t.tagName, t.angleBracket], color: "#F87171" },
  { tag: [t.variableName, t.definition(t.variableName)], color: "#F9FAFB" },
  { tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: "#6B7280", fontStyle: "italic" },
  { tag: [t.operator, t.punctuation, t.separator, t.bracket], color: "#9CA3AF" },
  { tag: [t.meta, t.processingInstruction], color: "#9CA3AF" },
  { tag: t.invalid, color: "#EF4444" },
]);

const lightHighlight = HighlightStyle.define([
  { tag: [t.keyword, t.controlKeyword, t.operatorKeyword, t.moduleKeyword], color: "#7C3AED" },
  { tag: [t.string, t.special(t.string), t.regexp], color: "#059669" },
  { tag: [t.number, t.bool, t.null, t.atom], color: "#2563EB" },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: "#B45309" },
  { tag: [t.propertyName, t.attributeName], color: "#2563EB" },
  { tag: [t.typeName, t.className, t.namespace], color: "#B45309" },
  { tag: [t.tagName, t.angleBracket], color: "#DC2626" },
  { tag: [t.variableName, t.definition(t.variableName)], color: "#111827" },
  { tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: "#9CA3AF", fontStyle: "italic" },
  { tag: [t.operator, t.punctuation, t.separator, t.bracket], color: "#6B7280" },
  { tag: [t.meta, t.processingInstruction], color: "#6B7280" },
  { tag: t.invalid, color: "#DC2626" },
]);

export function highlightFor(theme: Theme): Extension {
  return syntaxHighlighting(theme === "dark" ? darkHighlight : lightHighlight);
}
