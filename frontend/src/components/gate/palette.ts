// Selectable cursor/identity colors. These are concrete hex values (not theme
// tokens): a participant's color is sent to the backend and must stay stable and
// legible on both the light and dark editor backgrounds.
export const CURSOR_COLORS = [
  "#60A5FA", // blue
  "#F472B6", // magenta
  "#34D399", // teal
  "#FBBF24", // amber
  "#A78BFA", // violet
] as const;

export const DEFAULT_COLOR = CURSOR_COLORS[0];
