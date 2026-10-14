import { useState } from "react";
import { TopBar } from "../shell/TopBar";
import { Sidebar } from "../shell/Sidebar";
import { StatusBar } from "../shell/StatusBar";
import { EditorPane } from "./EditorPane";
import { JoinRequestPanel } from "./JoinRequestPanel";
import { AdminOfferPrompt } from "./AdminOfferPrompt";
import { ActionsPopover } from "./ActionsPopover";
import { useSessionStore } from "../../state/store";

export function Workspace() {
  const [actionsOpen, setActionsOpen] = useState(false);
  const role = useSessionStore((s) => s.role);
  const hasRequests = useSessionStore((s) => s.joinRequests.length > 0);
  const adminOffer = useSessionStore((s) => s.adminOffer);

  return (
    <div className="flex h-full flex-col bg-surface-page">
      <TopBar />
      <div className="flex min-h-0 flex-1">
        <Sidebar onOpenActions={() => setActionsOpen(true)} />
        <section className="relative flex min-h-0 flex-1 flex-col">
          <div className="min-h-0 flex-1 bg-editor-bg">
            <EditorPane />
          </div>
          <StatusBar />
          {role === "CREATOR" && hasRequests && <JoinRequestPanel />}
          {adminOffer && <AdminOfferPrompt />}
          {actionsOpen && <ActionsPopover onClose={() => setActionsOpen(false)} />}
        </section>
      </div>
    </div>
  );
}
