import type { CSSProperties, Ref } from "react";

export type LaneSpec = { id: string; label: string; mark?: string; start: number; end: number; on?: boolean; fault?: boolean };

/**
 * Logic-analyzer lanes. Each lane is a baseline with one pulse between `start` and `end`
 * (0 to 100 along the time axis). Pure markup: the home schematic scrubs it with GSAP, the demo
 * renders it from real timings.
 */
export function Lanes({
  lanes,
  axis,
  cursorRef,
  sweepRef,
  waveRef,
  style,
}: {
  lanes: LaneSpec[];
  axis: [string, string];
  cursorRef?: Ref<HTMLDivElement>;
  sweepRef?: Ref<HTMLDivElement>;
  waveRef?: (i: number) => Ref<SVGSVGElement>;
  style?: CSSProperties;
}) {
  return (
    <div style={style}>
      <div className="lanes" role="presentation">
        {lanes.map((l, i) => (
          <div className={`lane${l.on ? " is-on" : ""}`} key={l.id} data-lane={l.id}>
            <span className="lane__label">{l.label}</span>
            <div className="lane__track">
              <svg
                ref={waveRef?.(i)}
                className="lane__wave"
                viewBox="0 0 100 10"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <path
                  d={l.start >= 100 ? "M0 7 H100" : `M0 7 H${l.start} V2 H${l.end} V7 H100`}
                  style={l.fault ? { stroke: "var(--c-fault-screen)" } : undefined}
                />
              </svg>
              {l.mark && (
                <span
                  className="lane__mark"
                  style={{
                    left: `calc(${Math.min(l.start >= 100 ? 2 : l.end, 70)}% + 0.5rem)`,
                    ...(l.fault ? { color: "var(--c-fault-screen)" } : {}),
                    ...(l.start >= 100 ? { color: "var(--c-screen-muted)" } : {}),
                  }}
                >
                  {l.mark}
                </span>
              )}
            </div>
          </div>
        ))}
        {(cursorRef || sweepRef) && (
          <div
            aria-hidden="true"
            style={{ position: "absolute", top: 0, bottom: 0, left: "var(--lane-gap, 6.5rem)", right: 0, pointerEvents: "none" }}
          >
            {sweepRef && <div ref={sweepRef} className="lanes__cursor" style={{ opacity: 0.18 }} />}
            {cursorRef && <div ref={cursorRef} className="lanes__cursor" />}
          </div>
        )}
      </div>
      <div className="lanes__axis" aria-hidden="true">
        <span />
        <div>
          <span>{axis[0]}</span>
          <span>{axis[1]}</span>
        </div>
      </div>
    </div>
  );
}
