import { useState } from "react";
import { SessionProvider, useSession } from "./session/SessionProvider";
import { useSessionStore } from "./state/store";
import { CreateSession } from "./components/gate/CreateSession";
import { JoinSession } from "./components/gate/JoinSession";
import { WaitingRoom } from "./components/gate/WaitingRoom";
import { RequestDeclined } from "./components/gate/RequestDeclined";
import { Connecting } from "./components/gate/Connecting";
import { Workspace } from "./components/workspace/Workspace";

function Gate() {
  const phase = useSessionStore((s) => s.phase);
  const reconnecting = useSessionStore((s) => s.reconnecting);
  const sessionId = useSessionStore((s) => s.sessionId) ?? "";
  const deniedReason = useSessionStore((s) => s.deniedReason);
  const hostOnline = useSessionStore((s) => s.hostOnline);
  const session = useSession();

  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  if (phase === "denied") {
    return (
      <RequestDeclined
        message={deniedReason ?? "Your request was declined."}
        onStartOwn={session.startOwnSession}
      />
    );
  }
  if (phase === "workspace") return <Workspace />;
  if (reconnecting) return <Connecting />;
  if (phase === "waiting") return <WaitingRoom sessionId={sessionId} hostOnline={hostOnline} onCancel={session.cancel} />;
  if (phase === "join") return <JoinSession sessionId={sessionId} onJoin={session.askToJoin} />;

  return (
    <CreateSession
      busy={creating}
      error={createError}
      onCreate={async (name, color) => {
        setCreating(true);
        setCreateError(null);
        try {
          await session.createAndConnect(name, color);
        } catch {
          setCreateError("Could not create a session. Is the server reachable?");
          setCreating(false);
        }
      }}
    />
  );
}

export function App() {
  return (
    <SessionProvider>
      <Gate />
    </SessionProvider>
  );
}
