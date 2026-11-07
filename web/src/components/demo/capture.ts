// A capture is one action traced across the lanes. Every start/end is milliseconds after the
// click, built only from numbers the system returned: the browser's round trip, the gateway's
// Server-Timing, and server timestamps from Graph's outbox and Notify's ledger.
import type { Timed } from "@/lib/api";
import type { LaneSpec } from "../Lanes";

export type Hop = {
  id: string;
  label: string;
  start: number | null;
  end: number | null;
  mark: string;
  fault?: boolean;
};

export type Capture = {
  kind: "follow" | "followBack" | "post" | "replay" | "poison";
  title: string;
  state: "running" | "done" | "partial" | "error";
  hops: Hop[];
  meta: [string, string][];
  note: string;
  error?: string;
};

export const ms = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(2)} s` : `${Math.round(n)} ms`);

/**
 * Place the request on the visitor's clock. The browser measured the round trip; the gateway
 * reported its own time and the downstream service's. Network time is split evenly each way,
 * which is the one assumption in a capture.
 */
export function requestHops(t: Timed<unknown>, serviceId: string, serviceLabel: string): { hops: Hop[]; anchor: number } {
  const rtt = t.ms;
  if (t.gatewayMs === null || t.upstreamMs === null) {
    return {
      hops: [
        { id: "browser", label: "Browser", start: 0, end: rtt, mark: `${ms(rtt)} round trip` },
        { id: "gateway", label: "Gateway", start: 0, end: rtt, mark: "no timing reported" },
        { id: serviceId, label: serviceLabel, start: 0, end: rtt, mark: "inside the round trip" },
      ],
      anchor: rtt / 2,
    };
  }
  const gw = Math.min(t.gatewayMs, rtt);
  const up = Math.min(t.upstreamMs, gw);
  const a = (rtt - gw) / 2;
  return {
    hops: [
      { id: "browser", label: "Browser", start: 0, end: rtt, mark: `${ms(rtt)} round trip` },
      { id: "gateway", label: "Gateway", start: a, end: a + gw, mark: `${ms(gw)} in gateway` },
      { id: serviceId, label: serviceLabel, start: a + gw - up, end: a + gw, mark: `${ms(up)} in ${serviceLabel.toLowerCase()}` },
    ],
    anchor: a + gw,
  };
}

/** Map a server timestamp onto the visitor's clock, anchored at the end of the service's work. */
export const fromServer = (anchor: number, anchorIso: string) => (iso: string) =>
  anchor + (Date.parse(iso) - Date.parse(anchorIso));

export function toLanes(c: Capture): { lanes: LaneSpec[]; span: number } {
  const ends = c.hops.flatMap((h) => (h.end !== null ? [h.end] : []));
  const span = Math.max(1, ...ends) * 1.04;
  const pct = (v: number) => 2 + (Math.max(0, v) / span) * 94;
  return {
    span,
    lanes: c.hops.map((h) => {
      if (h.start === null || h.end === null) return { id: h.id, label: h.label, start: 101, end: 101, mark: h.mark, on: false, fault: h.fault };
      const s = pct(h.start);
      const e = Math.max(pct(h.end), s + 1.2);
      return { id: h.id, label: h.label, start: s, end: e, mark: h.mark, on: true, fault: h.fault };
    }),
  };
}
