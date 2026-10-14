// Monogram avatar — the participant's initial on a chip in their cursor color.
// (No photo avatars: identity here is just name + color.)
function textOn(bg: string): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(bg.trim());
  if (!m) return "#0a0c10";
  const n = parseInt(m[1], 16);
  const lum = (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
  return lum > 0.6 ? "#0a0c10" : "#ffffff";
}

export function Avatar({ name, color, size = 28 }: { name: string; color: string; size?: number }) {
  const initial = (name?.trim()?.[0] ?? "?").toUpperCase();
  return (
    <span
      className="inline-grid shrink-0 place-items-center rounded-full font-mono font-semibold"
      style={{
        width: size,
        height: size,
        backgroundColor: color,
        color: textOn(color),
        fontSize: Math.round(size * 0.42),
      }}
      title={name}
    >
      {initial}
    </span>
  );
}
