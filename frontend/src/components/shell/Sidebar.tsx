import { useRef, useState } from "react";
import { useSessionStore } from "../../state/store";
import { useSession } from "../../session/SessionProvider";
import { displayFileName } from "../../lib/fileName";
import { Icon } from "../ui/Icon";
import { PresencePanel } from "./PresencePanel";

export function Sidebar({ onOpenActions }: { onOpenActions: () => void }) {
  const session = useSession();
  const fileName = useSessionStore((s) => s.fileName);
  const language = useSessionStore((s) => s.language);
  const languageExt = useSessionStore((s) => s.languageExt);
  const userCount = useSessionStore((s) => s.users.length);

  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  // Blur fires after Enter/Escape already closed the editor; skip the double commit.
  const closedByKey = useRef(false);

  const displayName = displayFileName(fileName, languageExt);

  function startEditing() {
    setDraft(fileName);
    closedByKey.current = false;
    setEditing(true);
  }

  function commit() {
    session.rename(draft);
    setEditing(false);
  }

  return (
    <aside className="hidden w-60 shrink-0 flex-col border-r border-border-subtle bg-surface-raised md:flex">
      {/* project header */}
      <div className="group flex items-center gap-3 border-b border-border-subtle px-4 py-4">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-primary/15 text-primary">
          <Icon name="description" className="text-[20px]" />
        </span>
        <div className="min-w-0 flex-1">
          {editing ? (
            <div className="flex items-baseline gap-0.5">
              <input
                autoFocus
                value={draft}
                onChange={(e) => setDraft(e.target.value.replace(/\./g, ""))}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    closedByKey.current = true;
                    commit();
                  } else if (e.key === "Escape") {
                    closedByKey.current = true;
                    setEditing(false);
                  }
                }}
                onBlur={() => {
                  if (!closedByKey.current) commit();
                }}
                maxLength={64}
                aria-label="File name"
                className="w-full min-w-0 rounded-lg border border-border-subtle bg-input-bg px-2 py-1 font-mono text-body-md text-text-primary focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
              <span className="shrink-0 font-mono text-body-md text-text-muted">.{languageExt}</span>
            </div>
          ) : (
            <p
              onDoubleClick={startEditing}
              title="Double-click to rename"
              className="flex items-center gap-1 truncate text-body-md font-semibold text-text-primary"
            >
              <span className="truncate">{displayName}</span>
              <button
                type="button"
                onClick={startEditing}
                aria-label="Rename file"
                className="shrink-0 text-text-muted opacity-0 transition hover:text-text-primary focus:opacity-100 group-hover:opacity-100"
              >
                <Icon name="edit" className="text-[14px]" />
              </button>
            </p>
          )}
          <p className="font-mono text-label-caps uppercase text-text-muted">{language}</p>
        </div>
      </div>

      {/* collaborators */}
      <div className="flex-1 overflow-y-auto px-3 py-4 custom-scrollbar">
        <div className="mb-2 flex items-center gap-2 px-2">
          <Icon name="group" className="text-[16px] text-text-muted" />
          <span className="font-mono text-label-caps uppercase text-text-muted">Collaborators</span>
          <span className="ml-auto rounded-full bg-surface-container px-1.5 font-mono text-label-caps text-text-muted">
            {userCount}
          </span>
        </div>
        <PresencePanel />
      </div>

      {/* actions */}
      <div className="border-t border-border-subtle p-3">
        <button
          type="button"
          onClick={onOpenActions}
          className="flex w-full items-center gap-2 rounded-lg border border-border-subtle bg-surface-container px-3 py-2.5 text-body-md text-text-primary transition hover:border-primary/40"
        >
          <Icon name="bolt" className="text-[18px] text-primary" />
          Actions
          <Icon name="chevron_right" className="ml-auto text-[18px] text-text-muted" />
        </button>
      </div>
    </aside>
  );
}
