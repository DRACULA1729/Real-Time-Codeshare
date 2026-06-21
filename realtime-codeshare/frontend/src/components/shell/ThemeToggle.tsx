import { useTheme } from "../../theme/ThemeProvider";
import { Icon } from "../ui/Icon";

export function ThemeToggle({ className = "" }: { className?: string }) {
  const { theme, toggle } = useTheme();
  const isDark = theme === "dark";
  return (
    <button
      type="button"
      onClick={toggle}
      title={isDark ? "Switch to light theme" : "Switch to dark theme"}
      aria-label="Toggle color theme"
      className={`grid h-9 w-9 place-items-center rounded-lg border border-border-subtle text-text-muted transition-colors duration-150 hover:bg-surface-container hover:text-text-primary ${className}`}
    >
      <Icon name={isDark ? "light_mode" : "dark_mode"} className="text-[20px]" />
    </button>
  );
}
