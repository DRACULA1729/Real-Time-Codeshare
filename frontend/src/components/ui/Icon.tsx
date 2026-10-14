// Material Symbols Outlined glyph. The font is bundled locally (see main.tsx),
// so this never touches a CDN. Pass a symbol name, e.g. <Icon name="content_copy" />.
export function Icon({
  name,
  className = "",
  fill = false,
  style,
}: {
  name: string;
  className?: string;
  fill?: boolean;
  style?: React.CSSProperties;
}) {
  return (
    <span
      className={`material-symbols-outlined ${className}`}
      style={{ fontVariationSettings: fill ? '"FILL" 1' : undefined, ...style }}
      aria-hidden="true"
    >
      {name}
    </span>
  );
}
