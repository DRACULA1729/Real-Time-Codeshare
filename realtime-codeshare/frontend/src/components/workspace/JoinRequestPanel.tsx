import { useRef, useState } from "react";
import { useSession } from "../../session/SessionProvider";
import { useSessionStore } from "../../state/store";
import { Avatar } from "../ui/Avatar";
import { Icon } from "../ui/Icon";

// Floating, draggable, non-blocking glass panel for the host. Lists people in the
// waiting room with Admit / Reject. Doesn't cover the code (top-left, narrow).
export function JoinRequestPanel() {
  const session = useSession();
  const requests = useSessionStore((s) => s.joinRequests);
  const [pos, setPos] = useState<{ x: number; y: number }>({ x: 16, y: 16 });
  const drag = useRef<{ startX: number; startY: number; baseLeft: number; baseTop: number } | null>(null);

  function onPointerDown(e: React.PointerEvent) {
    // Delta-based drag: remember where the pointer and panel started, then move
    // the panel by exactly the pointer's delta. left/top are relative to the
    // editor section (the offset parent), and the pointer delta is identical in
    // both spaces, so the cursor stays glued to the panel.
    drag.current = { startX: e.clientX, startY: e.clientY, baseLeft: pos.x, baseTop: pos.y };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  }
  function onPointerMove(e: React.PointerEvent) {
    if (!drag.current) return;
    const panel = e.currentTarget.closest("[data-panel]") as HTMLElement | null;
    const parent = panel?.offsetParent as HTMLElement | null;
    let x = drag.current.baseLeft + (e.clientX - drag.current.startX);
    let y = drag.current.baseTop + (e.clientY - drag.current.startY);
    if (panel && parent) {
      x = Math.max(0, Math.min(x, parent.clientWidth - panel.offsetWidth));
      y = Math.max(0, Math.min(y, parent.clientHeight - panel.offsetHeight));
    }
    setPos({ x, y });
  }
  function onPointerUp(e: React.PointerEvent) {
    drag.current = null;
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      /* ignore */
    }
  }

  const style = { left: pos.x, top: pos.y };

  return (
    <div
      data-panel
      style={style}
      className="glass-panel absolute z-30 w-72 animate-slide-in-left overflow-hidden shadow-2xl"
    >
      <div
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        className="flex cursor-move items-center gap-2 border-b border-border-subtle px-4 py-3"
      >
        <Icon name="person_add" className="text-[18px] text-primary" />
        <span className="text-body-md font-semibold text-text-primary">Join Requests</span>
        {requests.length > 0 && (
          <span className="ml-auto grid h-5 min-w-5 place-items-center rounded-full bg-primary px-1.5 font-mono text-label-caps text-on-primary">
            {requests.length}
          </span>
        )}
      </div>

      <div className="max-h-72 overflow-y-auto p-2 custom-scrollbar">
        {requests.length === 0 ? (
          <p className="px-2 py-6 text-center text-body-md text-text-muted">No one waiting.</p>
        ) : (
          requests.map((r) => (
            <div key={r.joinKey} className="rounded-lg p-2">
              <div className="flex items-center gap-2">
                <Avatar name={r.name} color={r.color} size={28} />
                <div className="min-w-0">
                  <p className="truncate text-body-md font-medium" style={{ color: r.color }}>
                    {r.name || "Ghost"}
                  </p>
                  <p className="font-mono text-label-caps uppercase text-text-muted">Wants to collaborate</p>
                </div>
              </div>
              <div className="mt-2 flex gap-2">
                <button
                  type="button"
                  onClick={() => session.reject(r.joinKey)}
                  className="flex-1 rounded-lg border border-danger/30 bg-danger/15 py-1.5 text-body-md font-medium text-danger transition hover:bg-danger/25"
                >
                  Reject
                </button>
                <button
                  type="button"
                  onClick={() => session.approve(r.joinKey)}
                  className="flex-1 rounded-lg border border-success/30 bg-success/15 py-1.5 text-body-md font-medium text-success transition hover:bg-success/25"
                >
                  Admit
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
