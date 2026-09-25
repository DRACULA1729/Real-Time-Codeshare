import { useSessionStore } from "../../state/store";
import { Icon } from "../ui/Icon";

const CONN = {
  online: { label: "Live Session", color: "bg-success", text: "text-text-muted" },
  connecting: { label: "Connecting", color: "bg-participant-3", text: "text-text-muted" },
  reconnecting: { label: "Reconnecting", color: "bg-participant-3", text: "text-text-muted" },
  disconnected: { label: "Disconnected", color: "bg-danger", text: "text-danger" },
} as const;

export function StatusBar() {
  const connection = useSessionStore((s) => s.connection);
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
        <span className="flex items-center gap-1">
          <Icon name="code" className="text-[13px]" />
          {language}
        </span>
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
