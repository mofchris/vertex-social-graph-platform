"use client";
import { useEffect, useState } from "react";
import { ApiError, status as fetchStatus, type PlatformStatus } from "@/lib/api";

export type Health = { state: "loading" } | { state: "unreachable"; message: string } | { state: "ok"; data: PlatformStatus };

const ORDER = ["gateway", "identity", "profile", "graph", "feed", "notify", "recommend"];

/** Poll the gateway's public /api/status while the tab is visible. */
export function useHealth(intervalMs = 15000): Health {
  const [health, setHealth] = useState<Health>({ state: "loading" });
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let alive = true;
    const tick = async () => {
      try {
        const { data, ms } = await fetchStatus();
        // The gateway answered this request itself, so it is up; its row is the round trip.
        const withGateway = { ...data, services: [{ name: "gateway", status: "UP", latencyMs: ms }, ...data.services] };
        if (alive) setHealth({ state: "ok", data: withGateway });
      } catch (e) {
        const message =
          e instanceof ApiError && e.status === 0
            ? "Your browser could not reach Vertex."
            : "The edge is up but the server behind it is not answering.";
        if (alive) setHealth({ state: "unreachable", message });
      }
      if (alive) timer = setTimeout(tick, document.hidden ? intervalMs * 4 : intervalMs);
    };
    tick();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [intervalMs]);
  return health;
}

/** Turns a health reading into one plain sentence a visitor can act on. */
export function describe(h: Health): { title: string; body: string } {
  if (h.state === "loading") return { title: "Checking the services", body: "Asking each service for its own health." };
  if (h.state === "unreachable")
    return {
      title: "The backend is offline",
      body: `${h.message} It is probably restarting or being redeployed. This page checks again every 15 seconds.`,
    };
  const up = h.data.services.filter((s) => s.status === "UP").length;
  const total = h.data.services.length;
  if (h.data.status === "UP")
    return { title: `All ${total} services are up`, body: "The gateway asked each service for its own health; times are from the gateway, except its own." };
  return {
    title: `${up} of ${total} services are up`,
    body: "The rest are still starting. After a restart, all seven take about two minutes to come up on this small server.",
  };
}

export function StatusPanel() {
  const health = useHealth();
  const text = describe(health);
  const services =
    health.state === "ok"
      ? [...health.data.services].sort((a, b) => ORDER.indexOf(a.name) - ORDER.indexOf(b.name))
      : ORDER.map((name) => ({ name, status: health.state === "loading" ? "WAIT" : "DOWN", latencyMs: -1 }));
  const checked = health.state === "ok" ? new Date(health.data.checkedAt).toISOString().slice(11, 19) : null;

  return (
    <section className="status screen" aria-labelledby="status-title" aria-live="polite">
      <div className="status__head">
        <h2 id="status-title" className="status__title">
          Live channels
        </h2>
        <span className="legend">{checked ? `${checked} UTC` : health.state === "loading" ? "probing" : "no signal"}</span>
      </div>
      <ul className="status__rows">
        {services.map((s) => (
          <li className="status__row" key={s.name}>
            <span
              className={`dot ${s.status === "UP" ? "dot--up" : s.status === "WAIT" ? "dot--wait" : "dot--down"}`}
              aria-hidden="true"
            />
            <span>{s.name}</span>
            <span className="status__ms">
              {s.status === "UP" ? `${s.latencyMs} ms${s.name === "gateway" ? " from you" : ""}` : s.status === "WAIT" ? "..." : "down"}
              <span className="sr-only"> {s.status === "UP" ? "up" : s.status === "WAIT" ? "checking" : "not answering"}</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="status__foot">
        <strong>{text.title}.</strong> {text.body}
      </p>
    </section>
  );
}
