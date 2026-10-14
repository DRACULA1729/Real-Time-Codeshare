import { useState } from "react";
import { useSession } from "../../session/SessionProvider";
import { useSessionStore } from "../../state/store";
import { Avatar } from "../ui/Avatar";
import { Icon } from "../ui/Icon";
import { ThemeToggle } from "./ThemeToggle";

export function TopBar() {
  const session = useSession();
  const sessionId = useSessionStore((s) => s.sessionId) ?? "";
  const users = useSessionStore((s) => s.users);
  const [copied, setCopied] = useState(false);

  async function copyInvite() {
    const link = session.inviteLink();
    try {
      await navigator.clipboard.writeText(link);
    } catch {
      /* clipboard may be blocked; the link is still shown in the chip title */
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-border-subtle bg-surface-raised px-4">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-lg bg-primary/15 text-primary ring-1 ring-primary/25">
          <Icon name="code" className="text-[20px]" />
        </span>
        <span className="text-body-lg font-semibold text-text-primary">CodeShare</span>
      </div>

      <div className="flex items-center gap-3">
        {/* presence avatar cluster */}
        <div className="hidden items-center sm:flex">
          {users.slice(0, 5).map((u, i) => (
            <span key={u.clientId} style={{ marginLeft: i === 0 ? 0 : -8, zIndex: 10 - i }} className="ring-2 ring-surface-raised rounded-full">
              <Avatar name={u.name} color={u.color} size={26} />
            </span>
          ))}
          {users.length > 5 && (
            <span className="ml-1 font-mono text-label-caps text-text-muted">+{users.length - 5}</span>
          )}
        </div>

        {/* session id chip + copy */}
        <button
          type="button"
          onClick={copyInvite}
          title={session.inviteLink()}
          className="flex items-center gap-2 rounded-lg border border-border-subtle bg-surface-container px-3 py-1.5 transition-colors hover:border-primary/40"
        >
          <span className="font-mono text-label-caps uppercase text-text-muted">Session</span>
          <span className="max-w-[140px] truncate font-mono text-code-md text-text-primary">{sessionId}</span>
          <Icon name={copied ? "check" : "content_copy"} className={`text-[16px] ${copied ? "text-success" : "text-text-muted"}`} />
        </button>

        <button
          type="button"
          onClick={copyInvite}
          className="hidden items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-body-md font-semibold text-on-primary transition hover:brightness-110 md:flex"
        >
          <Icon name="ios_share" className="text-[16px]" /> {copied ? "Copied!" : "Share Session"}
        </button>

        <ThemeToggle />
      </div>
    </header>
  );
}
