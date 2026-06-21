import { useSessionStore } from "../../state/store";
import { Icon } from "../ui/Icon";
import { PresencePanel } from "./PresencePanel";

export function Sidebar({ onOpenActions }: { onOpenActions: () => void }) {
  const fileName = useSessionStore((s) => s.fileName);
  const language = useSessionStore((s) => s.language);
  const languageExt = useSessionStore((s) => s.languageExt);
  const userCount = useSessionStore((s) => s.users.length);

  const displayName = fileName.trim() || `untitled.${languageExt}`;

  return (
    <aside className="hidden w-60 shrink-0 flex-col border-r border-border-subtle bg-surface-raised md:flex">
      {/* project header */}
      <div className="flex items-center gap-3 border-b border-border-subtle px-4 py-4">
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-primary/15 text-primary">
          <Icon name="description" className="text-[20px]" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-body-md font-semibold text-text-primary">{displayName}</p>
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
