// Concentric spinning arcs (the waiting-room loader from the mockup), built with
// pure CSS so it works offline.
export function LoaderRing({ size = 72 }: { size?: number }) {
  const rings = [0, 1, 2, 3];
  return (
    <div className="relative" style={{ width: size, height: size }}>
      {rings.map((i) => (
        <span
          key={i}
          className="absolute rounded-full"
          style={{
            inset: i * 6,
            border: "3px solid transparent",
            borderTopColor: "rgb(var(--primary))",
            animation: "spin-ring 1.2s cubic-bezier(0.5,0,0.5,1) infinite",
            animationDelay: `${-0.15 * (rings.length - i)}s`,
          }}
        />
      ))}
    </div>
  );
}
