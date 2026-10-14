import type { ReactNode } from "react";
import { ThemeToggle } from "../shell/ThemeToggle";
import { Icon } from "../ui/Icon";

// Shared chrome for the gate screens: ambient background glow, centered content,
// a brand wordmark, and the theme toggle. `brand=false` hides the top wordmark
// for screens that render their own (e.g. waiting room).
export function GateLayout({ children, brand = true }: { children: ReactNode; brand?: boolean }) {
  return (
    <div className="relative flex min-h-full flex-col items-center justify-center overflow-hidden p-margin-mobile md:p-margin-desktop">
      {/* ambient atmosphere */}
      <div
        aria-hidden
        className="pointer-events-none absolute -left-40 -top-40 h-[420px] w-[420px] rounded-full blur-[120px]"
        style={{ background: "rgb(var(--primary) / 0.10)" }}
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -bottom-40 -right-40 h-[420px] w-[420px] rounded-full blur-[120px]"
        style={{ background: "rgb(var(--secondary) / 0.08)" }}
      />

      <div className="absolute right-margin-mobile top-margin-mobile md:right-margin-desktop md:top-margin-desktop">
        <ThemeToggle />
      </div>

      {brand && (
        <div className="mb-8 flex items-center gap-3">
          <span className="grid h-9 w-9 place-items-center rounded-lg bg-primary/15 text-primary ring-1 ring-primary/25">
            <Icon name="code" className="text-[22px]" />
          </span>
          <span className="text-headline-md text-text-primary">CodeShare</span>
        </div>
      )}

      <div className="relative w-full">{children}</div>
    </div>
  );
}
