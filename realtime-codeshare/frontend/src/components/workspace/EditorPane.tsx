import { useEffect, useRef } from "react";
import { EditorView, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, keymap } from "@codemirror/view";
import { EditorState, Compartment, Annotation, type ChangeSet } from "@codemirror/state";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { editorBaseTheme, highlightFor } from "../../editor/theme";
import { remoteCursorsExtension, setRemoteCursor } from "../../editor/remoteCursors";
import { detectLanguage, languageFor } from "../../editor/languages";
import { useSession } from "../../session/SessionProvider";
import { useSessionStore } from "../../state/store";
import { useTheme } from "../../theme/ThemeProvider";
import type { Op } from "../../ot/ops";

// Marks transactions whose changes came from a remote op, so the update listener
// doesn't echo them back to the server as local edits.
const REMOTE = Annotation.define<boolean>();
const CURSOR_THROTTLE_MS = 70;
const DETECT_DEBOUNCE_MS = 600;

export function EditorPane() {
  const host = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const syntaxCompartment = useRef<Compartment | null>(null);
  const applyLanguage = useRef<() => void>(() => {});
  const session = useSession();
  const { theme } = useTheme();
  const initialDocument = useSessionStore((s) => s.initialDocument);
  const languageChoice = useSessionStore((s) => s.languageChoice);

  // Build the editor once, on admission.
  useEffect(() => {
    if (!host.current) return;
    const syntax = new Compartment();
    const language = new Compartment();

    const cursorThrottle = { timer: 0 as ReturnType<typeof setTimeout> | 0, pending: -1 };
    const sendCursor = (posOf: () => number) => {
      cursorThrottle.pending = posOf();
      if (cursorThrottle.timer) return;
      cursorThrottle.timer = setTimeout(() => {
        cursorThrottle.timer = 0;
        if (cursorThrottle.pending >= 0) session.sendCursor(cursorThrottle.pending);
      }, CURSOR_THROTTLE_MS);
    };

    // A picked language wins; otherwise detect from the content.
    applyLanguage.current = () => {
      const view = viewRef.current;
      if (!view) return;
      const choice = useSessionStore.getState().languageChoice;
      const lang = choice ? languageFor(choice) : detectLanguage(view.state.doc.toString());
      useSessionStore.getState().setLanguage(lang.label, lang.ext);
      view.dispatch({ effects: language.reconfigure(lang.extension) });
    };

    let detectTimer: ReturnType<typeof setTimeout> | 0 = 0;
    const scheduleDetect = () => {
      if (useSessionStore.getState().languageChoice) return; // locked; nothing to detect
      if (detectTimer) clearTimeout(detectTimer);
      detectTimer = setTimeout(() => applyLanguage.current(), DETECT_DEBOUNCE_MS);
    };

    const emitChanges = (changes: ChangeSet) => {
      const edits: { from: number; to: number; insert: string }[] = [];
      changes.iterChanges((fromA, toA, _fromB, _toB, inserted) => {
        edits.push({ from: fromA, to: toA, insert: inserted.toString() });
      });
      // Apply high -> low so each op keeps old-document coordinates: a higher
      // edit never shifts a lower one, so positions stay valid as the server
      // applies them in order.
      for (let i = edits.length - 1; i >= 0; i--) {
        const e = edits[i];
        if (e.to > e.from) session.localDelete(e.from, e.to - e.from);
        if (e.insert.length > 0) session.localInsert(e.from, e.insert);
      }
    };

    const updateListener = EditorView.updateListener.of((u) => {
      for (const tr of u.transactions) {
        if (tr.docChanged && !tr.annotation(REMOTE)) emitChanges(tr.changes);
      }
      if (u.docChanged) scheduleDetect();
      if (u.selectionSet) {
        const head = u.state.selection.main.head;
        sendCursor(() => head);
        const line = u.state.doc.lineAt(head);
        useSessionStore.getState().setCursorPos(line.number, head - line.from + 1);
      }
    });

    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initialDocument,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightActiveLine(),
          history(),
          drawSelection(),
          EditorView.lineWrapping,
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          language.of([]),
          syntax.of(highlightFor(theme)),
          editorBaseTheme,
          remoteCursorsExtension,
          updateListener,
        ],
      }),
    });
    viewRef.current = view;
    syntaxCompartment.current = syntax;

    // The connection drives the document through this host (resync + remote ops).
    const clamp = (n: number) => Math.max(0, Math.min(n, view.state.doc.length));
    session.setDocHost({
      getText: () => view.state.doc.toString(),
      applyRemote: (op: Op) => {
        if (op.type === "insert") {
          const from = clamp(op.pos);
          view.dispatch({ changes: { from, insert: op.text }, annotations: REMOTE.of(true) });
        } else {
          const from = clamp(op.pos);
          const to = clamp(op.pos + op.length);
          if (to > from) view.dispatch({ changes: { from, to }, annotations: REMOTE.of(true) });
        }
      },
      replaceText: (text: string) => {
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: text },
          annotations: REMOTE.of(true),
        });
      },
    });

    return () => {
      session.setDocHost(null);
      if (detectTimer) clearTimeout(detectTimer);
      if (cursorThrottle.timer) clearTimeout(cursorThrottle.timer);
      view.destroy();
      viewRef.current = null;
    };
    // Build once; initialDocument is fixed at admission. Theme handled below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Seed on mount, and re-apply when anyone picks a language (or goes back to auto-detect).
  useEffect(() => applyLanguage.current(), [languageChoice]);

  // Swap syntax highlighting when the app theme toggles.
  useEffect(() => {
    const view = viewRef.current;
    const comp = syntaxCompartment.current;
    if (view && comp) view.dispatch({ effects: comp.reconfigure(highlightFor(theme)) });
  }, [theme]);

  // Stream remote cursor positions straight into the editor decorations.
  useEffect(() => {
    return session.cursors.subscribe((e) => {
      const view = viewRef.current;
      if (!view) return;
      if (e.pos === null) {
        view.dispatch({ effects: setRemoteCursor.of({ clientId: e.clientId, pos: null }) });
        return;
      }
      const user = useSessionStore.getState().users.find((u) => u.clientId === e.clientId);
      const pos = Math.max(0, Math.min(e.pos, view.state.doc.length));
      view.dispatch({
        effects: setRemoteCursor.of({ clientId: e.clientId, pos, color: user?.color, name: user?.name }),
      });
    });
  }, [session]);

  return <div ref={host} className="h-full w-full overflow-hidden custom-scrollbar [&_.cm-editor]:h-full [&_.cm-editor]:outline-none" />;
}
