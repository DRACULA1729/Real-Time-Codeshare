import { CURSOR_COLORS } from "./palette";

export function ColorPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (color: string) => void;
}) {
  return (
    <div className="grid grid-cols-5 gap-3">
      {CURSOR_COLORS.map((color) => {
        const selected = value.toLowerCase() === color.toLowerCase();
        return (
          <button
            key={color}
            type="button"
            onClick={() => onChange(color)}
            aria-label={`Cursor color ${color}`}
            aria-pressed={selected}
            className="h-11 rounded-lg transition-[transform,box-shadow] duration-150 hover:brightness-110"
            style={{
              backgroundColor: color,
              outline: selected ? "2px solid rgb(var(--primary))" : "none",
              outlineOffset: "2px",
            }}
          />
        );
      })}
    </div>
  );
}
