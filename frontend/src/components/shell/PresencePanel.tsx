import { useSession } from "../../session/SessionProvider";
import { useSessionStore } from "../../state/store";
import { Avatar } from "../ui/Avatar";
import { Icon } from "../ui/Icon";

// "Users online" — everyone admitted, in their color. Host marked; the host sees
// a kick control on every other row.
export function PresencePanel() {
  const session = useSession();
  const users = useSessionStore((s) => s.users);
  const myClientId = useSessionStore((s) => s.clientId);
  const myRole = useSessionStore((s) => s.role);

  return (
    <div className="space-y-0.5">
      {users.map((u) => {
        const isYou = u.clientId === myClientId;
        const isHost = u.role === "CREATOR";
        const canKick = myRole === "CREATOR" && !isYou;
        return (
          <div
            key={u.clientId}
            className="group flex items-center gap-2 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-container"
          >
            <Avatar name={u.name} color={u.color} size={26} />
            <span className="flex-1 truncate text-body-md text-text-primary">
              {u.name || "Ghost"}
              {isYou && <span className="text-text-muted"> (you)</span>}
            </span>
            {isHost && <Icon name="star" fill className="text-[15px] text-participant-3" />}
            {canKick && (
              <button
                type="button"
                onClick={() => session.kick(u.clientId)}
                title={`Remove ${u.name}`}
                className="grid h-6 w-6 place-items-center rounded text-text-muted opacity-0 transition hover:bg-danger/15 hover:text-danger group-hover:opacity-100"
              >
                <Icon name="close" className="text-[16px]" />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}
