import { GateLayout } from "./GateLayout";
import { LoaderRing } from "./LoaderRing";
import { Icon } from "../ui/Icon";

export function WaitingRoom({ sessionId, hostOnline, onCancel }: { sessionId: string; hostOnline: boolean; onCancel: () => void }) {
  return (
    <GateLayout>
      <div className="mx-auto flex w-full max-w-[440px] flex-col items-center rounded-xl border border-border-subtle bg-surface-raised p-10 text-center">
        <LoaderRing />
        {hostOnline ? (
          <>
            <h1 className="mt-6 text-headline-md text-text-primary">Waiting for the host to let you in…</h1>
            <p className="mt-2 text-body-lg text-text-muted">This can take a moment.</p>
          </>
        ) : (
          <>
            <h1 className="mt-6 text-headline-md text-text-primary">The host isn&apos;t here right now</h1>
            <p className="mt-2 text-body-lg text-text-muted">
              Keep this tab open and you&apos;ll be let in when they return, or ask them to reopen the session link.
            </p>
          </>
        )}

        <div className="my-7 h-px w-full bg-border-subtle" />

        <div className="flex w-full items-center justify-between rounded-lg border border-border-subtle bg-surface-container px-4 py-3">
          <span className="font-mono text-label-caps uppercase text-text-muted">Session</span>
          <code className="font-mono text-code-md text-secondary">{sessionId}</code>
        </div>

        <button
          type="button"
          onClick={onCancel}
          className="mt-6 flex items-center gap-1.5 font-mono text-label-caps uppercase text-text-muted transition-colors hover:text-danger"
        >
          <Icon name="close" className="text-[14px]" /> Cancel request
        </button>
      </div>
    </GateLayout>
  );
}
