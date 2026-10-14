import { GateLayout } from "./GateLayout";
import { Icon } from "../ui/Icon";

// One layout, three messages (declined / removed / ended) — the headline is the
// stored reason. Terminal-styled per the mockup, minus the aspirational IDE
// chrome we don't implement.
export function RequestDeclined({
  message,
  onStartOwn,
}: {
  message: string;
  onStartOwn: () => void;
}) {
  return (
    <GateLayout brand={false}>
      <div className="mx-auto w-full max-w-2xl overflow-hidden rounded-xl border border-border-subtle bg-surface-raised">
        {/* terminal header */}
        <div className="flex h-9 items-center justify-between border-b border-border-subtle bg-surface-container px-4">
          <div className="flex gap-1.5">
            <span className="h-2.5 w-2.5 rounded-full bg-danger/60" />
            <span className="h-2.5 w-2.5 rounded-full bg-participant-3/60" />
            <span className="h-2.5 w-2.5 rounded-full bg-participant-2/60" />
          </div>
          <span className="font-mono text-label-caps uppercase text-text-muted">ACCESS_DENIED</span>
        </div>

        <div className="flex flex-col items-center px-8 py-12 text-center md:px-12">
          <span className="mb-6 grid h-20 w-20 place-items-center rounded-full border border-danger/30 bg-danger/10 text-danger">
            <Icon name="block" className="text-[40px]" />
          </span>
          <h1 className="max-w-md text-headline-lg text-text-primary">{message}</h1>
          <p className="mt-3 max-w-md text-body-md text-text-muted">
            Access to the session was closed. You can start your own session and invite others to collaborate.
          </p>

          <button
            type="button"
            onClick={onStartOwn}
            className="mt-8 flex items-center gap-2 rounded-lg bg-primary px-6 py-3 text-body-lg font-semibold text-on-primary transition duration-150 hover:brightness-110 active:scale-[0.99]"
          >
            <Icon name="add_box" className="text-[18px]" /> Start your own session
          </button>
        </div>

        <div className="border-t border-border-subtle bg-surface-container-lowest px-6 py-3">
          <span className="flex items-center gap-2 font-mono text-[12px] text-text-muted">
            <span className="h-1.5 w-1.5 rounded-full bg-danger" /> Session closed
          </span>
        </div>
      </div>
    </GateLayout>
  );
}
