"use client";
import { useRef, useState } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { usePage } from "@/engine/next";
import { prefersReducedMotion, isMobile } from "@/engine";
import { followLanes } from "@/data/site";
import { Lanes, type LaneSpec } from "./Lanes";

const MARKS = ["POST /follow", "token ok", "edge + outbox row", "published", "social.events", "ledger + row", "SSE push"];

// Where each lane's pulse sits on the time axis (schematic, not measured: order is the point).
const SPEC: LaneSpec[] = followLanes.map((l, i) => ({
  id: l.id,
  label: l.label,
  mark: MARKS[i],
  start: 4 + i * 12.5,
  end: 4 + i * 12.5 + 7,
}));

/**
 * The signature: one follow, scrubbed by scroll. A single driver (progress 0 to 1) moves the
 * cursor, draws each lane up to it and picks the caption, so they always agree. At rest a faint
 * sweep keeps running across the scope, like a live time base.
 */
export function TraceSchematic() {
  const stage = useRef<HTMLDivElement>(null);
  const cursor = useRef<HTMLDivElement>(null);
  const sweep = useRef<HTMLDivElement>(null);
  const waves = useRef<(SVGSVGElement | null)[]>([]);
  const [step, setStep] = useState(0);
  const [still, setStill] = useState(false);

  usePage(() => {
    if (prefersReducedMotion()) {
      setStill(true);
      return;
    }
    const lanes = stage.current?.querySelectorAll<HTMLElement>(".lane");
    const track = stage.current?.querySelector<HTMLElement>(".lane__track");
    if (!lanes || !track) return;

    let last = -1;
    const drive = (p: number) => {
      const t = p * 100;
      gsap.set(cursor.current, { left: `${t}%` });
      waves.current.forEach((svg, i) => {
        if (!svg) return;
        gsap.set(svg, { clipPath: `inset(0% ${100 - t}% 0% 0%)` });
        lanes[i].classList.toggle("is-on", t >= SPEC[i].start);
      });
      const current = Math.max(0, SPEC.findLastIndex((s) => t >= s.start));
      if (current !== last) {
        last = current;
        setStep(current);
      }
    };
    drive(0);

    const st = ScrollTrigger.create({
      trigger: stage.current,
      start: "top top",
      end: () => `+=${innerHeight * (isMobile() ? 1.6 : 2.2)}`,
      pin: true,
      scrub: 0.6,
      invalidateOnRefresh: true,
      onUpdate: (self) => drive(self.progress),
    });

    // Idle sweep on the one clock, only while the scope is on screen.
    let visible = false;
    const io = new IntersectionObserver(([e]) => (visible = e.isIntersecting));
    if (stage.current) io.observe(stage.current);
    const tick = (time: number) => {
      if (!visible || !sweep.current) return;
      gsap.set(sweep.current, { left: `${((time * 9) % 100).toFixed(2)}%` });
    };
    gsap.ticker.add(tick);

    return () => {
      st.kill();
      io.disconnect();
      gsap.ticker.remove(tick);
    };
  });

  const lanes = still ? SPEC.map((s) => ({ ...s, on: true })) : SPEC;

  return (
    <section id="trace" className="trace-section screen" aria-labelledby="trace-title">
      <div ref={stage} className="trace-stage wrap">
        <div className="grid trace-stage__grid">
          <div className="trace-intro">
            <p className="legend">Schematic of one follow</p>
            <h2 id="trace-title" className="display">
              What happens when you press Follow
            </h2>
            <ol className={still ? "prose" : "sr-only"} style={still ? { display: "grid", gap: "0.6rem", paddingLeft: "1.2rem" } : undefined}>
              {followLanes.map((l) => (
                <li key={l.id}>
                  <strong>{l.label}.</strong> {l.detail}
                </li>
              ))}
            </ol>
            {!still && (
              <div className="trace-step" aria-hidden="true">
                <span className="legend trace-step__count">
                  {followLanes[step].label}, {step + 1} of {followLanes.length}
                </span>
                <p className="trace-step__text">{followLanes[step].detail}</p>
              </div>
            )}
            <p className="legend">The live demo draws this from the real timings your follow produces.</p>
          </div>
          <div className="trace-scope" style={{ background: "var(--c-screen-2)" }}>
            <div className="trace-scope__head legend">
              <span>CH 1 to 7</span>
              <span>trigger: follow</span>
            </div>
            <Lanes
              lanes={lanes}
              axis={["request", "delivered"]}
              cursorRef={still ? undefined : cursor}
              sweepRef={still ? undefined : sweep}
              waveRef={(i) => (el) => {
                waves.current[i] = el;
              }}
            />
          </div>
        </div>
      </div>
    </section>
  );
}
