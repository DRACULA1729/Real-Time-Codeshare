import { useSession } from "../../session/SessionProvider";
import { Icon } from "../ui/Icon";

// Shown when the host disconnects and the server offers us the admin seat. Nobody
// is promoted without accepting; declining (or ignoring it) passes it on.
export function AdminOfferPrompt() {
  const session = useSession();

  return (
    <div role="dialog" aria-label="Take over as host" className="glass-panel absolute right-4 top-4 z-30 w-72 animate-slide-in-left overflow-hidden shadow-2xl">
      <div className="flex items-center gap-2 border-b border-border-subtle px-4 py-3">
        <Icon name="admin_panel_settings" className="text-[18px] text-primary" />
        <span className="text-body-md font-semibold text-text-primary">Host left</span>
      </div>
      <div className="p-3">
        <p className="text-body-md text-text-muted">The host left. Take over as host?</p>
        <div className="mt-3 flex gap-2">
          <button
            type="button"
            onClick={session.declineAdmin}
            className="flex-1 rounded-lg border border-danger/30 bg-danger/15 py-1.5 text-body-md font-medium text-danger transition hover:bg-danger/25"
          >
            Decline
          </button>
          <button
            type="button"
            onClick={session.acceptAdmin}
            className="flex-1 rounded-lg border border-success/30 bg-success/15 py-1.5 text-body-md font-medium text-success transition hover:bg-success/25"
          >
            Accept
          </button>
        </div>
      </div>
    </div>
  );
}
