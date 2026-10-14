import { EditorView, Decoration, type DecorationSet, WidgetType } from "@codemirror/view";
import { StateField, StateEffect } from "@codemirror/state";

interface RemoteCursor {
  pos: number;
  color: string;
  name: string;
}

// Set (or, with pos === null, clear) a peer's cursor.
export const setRemoteCursor = StateEffect.define<{ clientId: string; pos: number | null; color?: string; name?: string }>();

// Pick legible flag text color for a given participant background.
function textOn(bg: string): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(bg.trim());
  if (!m) return "#0a0c10";
  const n = parseInt(m[1], 16);
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return lum > 0.6 ? "#0a0c10" : "#ffffff";
}

class CursorWidget extends WidgetType {
  constructor(
    readonly color: string,
    readonly name: string,
  ) {
    super();
  }

  eq(other: CursorWidget): boolean {
    return other.color === this.color && other.name === this.name;
  }

  toDOM(): HTMLElement {
    const wrap = document.createElement("span");
    wrap.className = "cm-remote-cursor";
    wrap.style.cssText = `position:relative; border-left:2px solid ${this.color}; margin-left:-1px; pointer-events:none;`;
    const flag = document.createElement("span");
    flag.textContent = this.name || "·";
    flag.style.cssText =
      `position:absolute; bottom:100%; left:-1px; transform:translateY(2px); background:${this.color};` +
      ` color:${textOn(this.color)}; font-family:"JetBrains Mono",monospace; font-size:10px; font-weight:600;` +
      ` letter-spacing:0.05em; text-transform:uppercase; padding:0 4px; border-radius:3px 3px 3px 0;` +
      ` white-space:nowrap; line-height:14px; opacity:0.95;`;
    wrap.appendChild(flag);
    return wrap;
  }

  ignoreEvent(): boolean {
    return true;
  }
}

// Map<clientId, cursor> kept in editor state; positions follow document edits.
const cursorMap = StateField.define<Map<string, RemoteCursor>>({
  create: () => new Map(),
  update(value, tr) {
    let map = value;
    if (tr.docChanged) {
      const mapped = new Map<string, RemoteCursor>();
      for (const [id, c] of value) {
        const pos = tr.changes.mapPos(c.pos, 1);
        mapped.set(id, { ...c, pos });
      }
      map = mapped;
    }
    for (const e of tr.effects) {
      if (e.is(setRemoteCursor)) {
        map = new Map(map);
        if (e.value.pos === null) {
          map.delete(e.value.clientId);
        } else {
          const prev = map.get(e.value.clientId);
          map.set(e.value.clientId, {
            pos: e.value.pos,
            color: e.value.color ?? prev?.color ?? "#888888",
            name: e.value.name ?? prev?.name ?? "",
          });
        }
      }
    }
    return map;
  },
});

const cursorDecorations = EditorView.decorations.compute([cursorMap], (state) => {
  const map = state.field(cursorMap);
  const docLen = state.doc.length;
  const ranges = [...map.values()]
    .filter((c) => c.pos >= 0 && c.pos <= docLen)
    .sort((a, b) => a.pos - b.pos)
    .map((c) => Decoration.widget({ widget: new CursorWidget(c.color, c.name), side: 1 }).range(c.pos));
  return Decoration.set(ranges, true) as DecorationSet;
});

export const remoteCursorsExtension = [cursorMap, cursorDecorations];
