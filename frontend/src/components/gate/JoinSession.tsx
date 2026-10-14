import { useState } from "react";
import { GateLayout } from "./GateLayout";
import { ColorPicker } from "./ColorPicker";
import { DEFAULT_COLOR } from "./palette";
import { Icon } from "../ui/Icon";

export function JoinSession({
  sessionId,
  onJoin,
}: {
  sessionId: string;
  onJoin: (name: string, color: string) => void;
}) {
  const [name, setName] = useState("");
  const [color, setColor] = useState<string>(DEFAULT_COLOR);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    onJoin(name.trim() || "Ghost", color);
  }

  return (
    <GateLayout>
      <form
        onSubmit={submit}
        className="relative mx-auto w-full max-w-[480px] overflow-hidden rounded-xl border border-border-subtle bg-surface-container p-8"
      >
        <h1 className="text-headline-md text-text-primary">Join Session</h1>
        <p className="mt-2 text-body-md text-text-muted">
          You're joining session{" "}
          <code className="rounded border border-primary/20 bg-primary/10 px-1.5 py-0.5 font-mono text-code-md text-primary">
            {sessionId}
          </code>
          . The host has to let you in.
        </p>

        <div className="mt-8 space-y-7">
          <label className="block">
            <span className="mb-2 flex items-center gap-1.5 font-mono text-label-caps uppercase text-text-muted">
              <Icon name="person" className="text-[14px]" /> Your name
            </span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={40}
              placeholder="e.g. Sarah Networks"
              autoFocus
              className="w-full rounded-lg border border-border-subtle bg-input-bg px-4 py-3 text-body-lg text-text-primary placeholder:text-text-muted/70 outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/30"
            />
          </label>

          <div>
            <span className="mb-2 flex items-center gap-1.5 font-mono text-label-caps uppercase text-text-muted">
              <Icon name="palette" className="text-[14px]" /> Cursor color
            </span>
            <ColorPicker value={color} onChange={setColor} />
          </div>

          <button
            type="submit"
            className="group flex w-full items-center justify-center gap-2 rounded-lg bg-primary py-3.5 text-body-lg font-semibold text-on-primary transition duration-150 hover:brightness-110 active:scale-[0.99]"
          >
            Ask to join
            <Icon name="arrow_forward" className="text-[18px] transition-transform group-hover:translate-x-0.5" />
          </button>

          <p className="flex items-center justify-center gap-1.5 text-body-md text-text-muted">
            <Icon name="notifications" className="text-[14px]" /> The host will be notified.
          </p>
        </div>
      </form>
    </GateLayout>
  );
}
