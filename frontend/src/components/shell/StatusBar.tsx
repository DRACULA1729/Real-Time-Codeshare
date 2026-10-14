import { useSessionStore } from "../../state/store";
import { Icon } from "../ui/Icon";
import { useSession } from "../../session/SessionProvider";
import { LANGUAGE_OPTIONS } from "../../editor/languages";

const CONN = {
  online: { label: "Live Session", color: "bg-success", text: "text-text-muted" },
  connecting: { label: "Connecting", color: "bg-participant-3", text: "text-text-muted" },
  reconnecting: { label: "Reconnecting", color: "bg-participant-3", text: "text-text-muted" },
  disconnected: { label: "Disconnected", color: "bg-danger", text: "text-danger" },
} as const;

export function StatusBar() {
  const session = useSession();
  const connection = useSessionStore((s) => s.connection);
  const languageChoice = useSessionStore((s) => s.languageChoice);
  const language = useSessionStore((s) => s.language);
  const line = useSessionStore((s) => s.cursorLine);
  const col = useSessionStore((s) => s.cursorCol);
  const conn = CONN[connection];

  return (
    <footer className="flex h-8 shrink-0 items-center justify-between border-t border-border-subtle bg-surface-raised px-4 font-mono text-label-caps uppercase text-text-muted">
      <div className="flex items-center gap-4">
        <span className={`flex items-center gap-1.5 ${conn.text}`}>
          <span className={`h-1.5 w-1.5 rounded-full ${conn.color} ${connection === "online" ? "animate-pulse-soft" : ""}`} />
          {conn.label}
        </span>
        <span className="hidden sm:inline">UTF-8</span>
        <label className="flex items-center gap-1">
          <Icon name="code" className="text-[13px]" />
          <span className="sr-only">Language</span>
          {/* Native select: keyboard + screen reader support for free. The choice is
              shared with everyone in the session; "" goes back to auto-detect. */}
          <select
            value={languageChoice}
            onChange={(e) => session.setLanguage(e.target.value)}
            title="Language for everyone in this session"
            className="cursor-pointer bg-transparent uppercase text-text-muted outline-none hover:text-text-primary"
          >
            <option value="">{languageChoice ? "Auto-detect" : `${language} (auto)`}</option>
            {LANGUAGE_OPTIONS.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="flex items-center gap-4">
        <span>
          Ln {line}, Col {col}
        </span>
        <span className="hidden items-center gap-1.5 sm:flex">
          <Icon name="sync" className="text-[13px]" />
          {connection === "online" ? "Synced" : "—"}
        </span>
      </div>
    </footer>
  );
}
