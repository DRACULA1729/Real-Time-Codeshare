import { GateLayout } from "./GateLayout";
import { LoaderRing } from "./LoaderRing";

export function Connecting() {
  return (
    <GateLayout>
      <div className="mx-auto flex w-full max-w-[440px] flex-col items-center rounded-xl border border-border-subtle bg-surface-raised p-10 text-center">
        <LoaderRing />
        <h1 className="mt-6 text-headline-md text-text-primary">Connecting…</h1>
        <p className="mt-2 text-body-lg text-text-muted">Restoring your session.</p>
      </div>
    </GateLayout>
  );
}
