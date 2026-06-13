import { cn } from "@/lib/utils";

type Node = { id: number; x: number; y: number; r: number; hub?: boolean };

const nodes: Node[] = [
  { id: 1, x: 320, y: 178, r: 11, hub: true },
  { id: 2, x: 150, y: 92, r: 6 },
  { id: 3, x: 470, y: 80, r: 6 },
  { id: 4, x: 92, y: 212, r: 5 },
  { id: 5, x: 250, y: 298, r: 6 },
  { id: 6, x: 430, y: 300, r: 5 },
  { id: 7, x: 540, y: 200, r: 6 },
  { id: 8, x: 204, y: 182, r: 5 },
  { id: 9, x: 380, y: 92, r: 5 },
  { id: 10, x: 520, y: 122, r: 4 },
  { id: 11, x: 124, y: 308, r: 4 },
  { id: 12, x: 560, y: 300, r: 4 },
  { id: 13, x: 300, y: 58, r: 4 },
  { id: 14, x: 62, y: 122, r: 4 },
];

const byId = (id: number) => nodes.find((n) => n.id === id)!;

// [from, to, isHubEdge]
const edges: [number, number, boolean][] = [
  [1, 2, true], [1, 3, true], [1, 5, true], [1, 6, true],
  [1, 7, true], [1, 8, true], [1, 9, true],
  [2, 8, false], [2, 14, false], [3, 9, false], [3, 10, false],
  [5, 11, false], [6, 12, false], [7, 10, false], [9, 13, false],
  [4, 8, false], [4, 11, false],
];

const stats = [
  { value: "2.1M", label: "users" },
  { value: "480M", label: "edges" },
  { value: "38ms", label: "p99 read" },
  { value: "99.98%", label: "uptime" },
];

export function GraphVisual({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border bg-card/80 shadow-sm backdrop-blur-sm",
        className
      )}
    >
      {/* window chrome */}
      <div className="flex items-center gap-2 border-b px-4 py-2.5">
        <span className="size-2.5 rounded-full bg-primary/70" />
        <span className="size-2.5 rounded-full bg-chart-4/70" />
        <span className="size-2.5 rounded-full bg-chart-3/70" />
        <span className="ml-2 font-mono text-muted-foreground text-xs">
          graph-service · live
        </span>
      </div>

      <svg
        viewBox="0 0 640 360"
        className="block w-full"
        role="img"
        aria-label="Social graph visualization with connected user nodes"
      >
        {edges.map(([a, b, hub], i) => {
          const n1 = byId(a);
          const n2 = byId(b);
          return (
            <line
              key={i}
              x1={n1.x}
              y1={n1.y}
              x2={n2.x}
              y2={n2.y}
              className={hub ? "stroke-primary/40" : "stroke-border"}
              strokeWidth={hub ? 1.6 : 1}
            />
          );
        })}

        {nodes.map((n) =>
          n.hub ? (
            <g key={n.id}>
              <circle cx={n.x} cy={n.y} r={n.r + 9} className="fill-primary/15" />
              <circle cx={n.x} cy={n.y} r={n.r} className="fill-primary" />
            </g>
          ) : (
            <circle
              key={n.id}
              cx={n.x}
              cy={n.y}
              r={n.r}
              className="fill-foreground/65"
            />
          )
        )}
      </svg>

      {/* metrics strip */}
      <div className="grid grid-cols-4 divide-x border-t">
        {stats.map((s) => (
          <div key={s.label} className="px-3 py-3 text-center">
            <div className="font-semibold text-sm tabular-nums md:text-base">
              {s.value}
            </div>
            <div className="text-muted-foreground text-[0.65rem] tracking-wide md:text-xs">
              {s.label}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
