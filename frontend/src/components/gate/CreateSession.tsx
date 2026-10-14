import { useState } from "react";
import { GateLayout } from "./GateLayout";
import { ColorPicker } from "./ColorPicker";
import { DEFAULT_COLOR } from "./palette";
import { Icon } from "../ui/Icon";

export function CreateSession({
  busy = false,
  error,
  onCreate,
}: {
  busy?: boolean;
  error?: string | null;
  onCreate: (name: string, color: string) => void;
}) {
  const [name, setName] = useState("");
  const [color, setColor] = useState<string>(DEFAULT_COLOR);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    onCreate(name.trim() || "Ghost", color);
  }

  return (
    <GateLayout brand={false}>
      <form
        onSubmit={submit}
        className="mx-auto w-full max-w-[440px] overflow-hidden rounded-xl border border-border-subtle bg-surface-raised"
      >
        <div className="px-8 pb-8 pt-10">
          <div className="mb-8 flex flex-col items-center text-center">
            <span className="mb-4 grid h-12 w-12 place-items-center rounded-lg bg-primary/10 text-primary ring-1 ring-primary/20">
              <Icon name="code" className="text-[26px]" />
            </span>
            <h1 className="text-headline-md text-text-primary">CodeShare</h1>
            <p className="mt-2 font-mono text-label-caps uppercase text-text-muted">
              Start a collaborative coding session
            </p>
          </div>

          <div className="space-y-7">
            <label className="block">
              <span className="mb-2 flex items-center gap-1.5 font-mono text-label-caps uppercase text-text-muted">
                <Icon name="person" className="text-[14px]" /> Your name
              </span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={40}
                placeholder="Ghost"
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
              disabled={busy}
              className="flex w-full items-center justify-center gap-2 rounded-lg bg-primary py-3.5 text-body-lg font-semibold text-on-primary transition duration-150 hover:brightness-110 active:scale-[0.99] disabled:opacity-60"
            >
              {busy ? "Creating…" : "Create session"}
              {!busy && <Icon name="arrow_forward" className="text-[18px]" />}
            </button>

            {error ? (
              <p className="text-center text-body-md text-danger">{error}</p>
            ) : (
              <p className="flex items-center justify-center gap-2 font-mono text-label-caps uppercase text-text-muted">
                <span className="h-1.5 w-1.5 animate-pulse-soft rounded-full bg-success" />
                Systems operational
              </p>
            )}
          </div>
        </div>
      </form>
    </GateLayout>
  );
}
