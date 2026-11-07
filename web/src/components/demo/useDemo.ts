"use client";
// The demo's state and actions. Every action calls the real API and builds a capture from what
// comes back; nothing on screen is simulated.
import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "@/lib/api";
import type { Account, Notification, Post, Suggestion, Timed } from "@/lib/api";
import { type Capture, type Hop, fromServer, ms, requestHops } from "./capture";
import { DEMO_PASSWORD, pickPartnerIndex, randomHandle, randomPassword, seededEmail, seededName } from "./seeded";

export type Session = { me: Account; partner: Account; partnerIndex: number };
export type StepId = "follow" | "followBack" | "post" | "replay" | "poison";
/** A real run, captured against the live backend, that the site replays when the backend is offline. */
export type Recording = {
  recordedAt: string;
  me: { userId: string; displayName: string };
  partner: { userId: string; displayName: string };
  steps: Partial<Record<StepId, Capture>>;
  myFeed: Post[];
  myNotes: Notification[];
  partnerNotes: Notification[];
  people: Suggestion[];
  names: Record<string, string>;
};
type Arrival = { n: Notification; at: number; to: "me" | "partner" };

const KEY = "vertex.demo.v1";
const WAIT_MS = 20000;
const sleep = (n: number) => new Promise((r) => setTimeout(r, n));
const short = (id: string) => id.slice(0, 8);

function load(): { session: Session | null; done: StepId[]; lastEvent: string | null } {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    /* storage unavailable: start fresh */
  }
  return { session: null, done: [], lastEvent: null };
}

/** Poll `fn` until it returns a value or the deadline passes. */
async function until<T>(fn: () => Promise<T | null | undefined>, deadline = Date.now() + WAIT_MS, every = 350): Promise<T | null> {
  while (Date.now() < deadline) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) {
      if (e instanceof api.ApiError && e.status !== 404) throw e;
    }
    await sleep(every);
  }
  return null;
}

export function useDemo() {
  const [session, setSession] = useState<Session | null>(null);
  const [done, setDone] = useState<StepId[]>([]);
  const [lastEvent, setLastEvent] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [capture, setCapture] = useState<Capture | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [names, setNames] = useState<Record<string, string>>({});
  const [myFeed, setMyFeed] = useState<Post[] | null>(null);
  const [myNotes, setMyNotes] = useState<Notification[] | null>(null);
  const [partnerNotes, setPartnerNotes] = useState<(Notification & { at?: number })[] | null>(null);
  const [people, setPeople] = useState<Suggestion[] | null>(null);
  const [following, setFollowing] = useState<Record<string, boolean>>({});
  const [streams, setStreams] = useState({ me: false, partner: false });
  const arrivals = useRef<((a: Arrival) => void)[]>([]);
  const namesRef = useRef(names);
  useEffect(() => {
    namesRef.current = names;
  }, [names]);

  // ---------- persistence ----------
  useEffect(() => {
    const s = load();
    // Reading sessionStorage is a one-time sync with the browser; it can't happen during the
    // static prerender, so it happens here.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSession(s.session);
    setDone(s.done);
    setLastEvent(s.lastEvent);
    setHydrated(true);
  }, []);
  useEffect(() => {
    if (!hydrated) return;
    try {
      sessionStorage.setItem(KEY, JSON.stringify({ session, done, lastEvent }));
    } catch {
      /* storage unavailable: the demo still works for this page view */
    }
  }, [session, done, lastEvent, hydrated]);
  useEffect(() => {
    api.setTokenListener(() => setSession((s) => (s ? { ...s } : s)));
  }, []);

  // ---------- recording (only with ?record, used by scripts/record-run.mjs) ----------
  // Collects each finished capture and the panels as they stood, so the site can replay a real run
  // when the backend is offline. Ids and display names only; never tokens.
  useEffect(() => {
    if (!session || typeof window === "undefined" || !new URLSearchParams(window.location.search).has("record")) return;
    const w = window as unknown as { __vertexRecording?: Recording };
    const prev = w.__vertexRecording;
    w.__vertexRecording = {
      recordedAt: prev?.recordedAt ?? new Date().toISOString(),
      me: { userId: session.me.userId, displayName: session.me.displayName },
      partner: { userId: session.partner.userId, displayName: session.partner.displayName },
      steps: { ...prev?.steps, ...(capture && capture.state === "done" ? { [capture.kind]: capture } : {}) },
      myFeed: myFeed ?? [],
      myNotes: myNotes ?? [],
      partnerNotes: (partnerNotes ?? []).map(({ at: _at, ...n }) => n), // eslint-disable-line @typescript-eslint/no-unused-vars
      people: people ?? [],
      names,
    };
  }, [session, capture, myFeed, myNotes, partnerNotes, people, names]);

  const track = useCallback(<T,>(t: Timed<T>) => {
    if (t.remaining !== null) setRemaining(t.remaining);
    return t;
  }, []);
  const markDone = (id: StepId) => setDone((d) => (d.includes(id) ? d : [...d, id]));

  // ---------- names ----------
  const resolveNames = useCallback(
    async (ids: string[]) => {
      if (!session) return;
      const missing = [...new Set(ids)].filter((id) => !namesRef.current[id]);
      if (!missing.length) return;
      try {
        const { data } = await api.profiles(session.me, missing.slice(0, 100));
        const found: Record<string, string> = {};
        data.items.forEach((p) => (found[p.userId] = p.displayName || "Someone"));
        missing.forEach((id) => (found[id] ??= "A private profile"));
        setNames((n) => ({ ...n, ...found }));
      } catch {
        /* names are cosmetic; ids still render */
      }
    },
    [session],
  );
  const nameOf = useCallback(
    (id: string) => {
      if (session && id === session.me.userId) return `${session.me.displayName} (you)`;
      if (session && id === session.partner.userId) return session.partner.displayName;
      return names[id] ?? "...";
    },
    [names, session],
  );

  // ---------- data refresh ----------
  const refresh = useCallback(async () => {
    if (!session) return;
    const { me, partner } = session;
    const results = await Promise.allSettled([
      api.feed(me, 20),
      api.notifications(me, 20),
      api.notifications(partner, 10),
      api.suggestions(me, 6),
    ]);
    const [f, n, pn, sg] = results;
    const ids: string[] = [];
    if (f.status === "fulfilled") {
      setMyFeed(f.value.data.items);
      f.value.data.items.forEach((p) => ids.push(p.authorId));
    }
    if (n.status === "fulfilled") {
      setMyNotes(n.value.data.items);
      n.value.data.items.forEach((x) => ids.push(x.latestActorId));
    }
    if (pn.status === "fulfilled") {
      setPartnerNotes(pn.value.data.items);
      pn.value.data.items.forEach((x) => ids.push(x.latestActorId));
    }
    if (sg.status === "fulfilled") {
      setPeople(sg.value.data.items);
      sg.value.data.items.forEach((x) => ids.push(x.userId));
    }
    resolveNames(ids);
  }, [session, resolveNames]);

  useEffect(() => {
    if (!session) return;
    // Load the lists from the services whenever the signed-in pair changes (state is set after the
    // requests resolve, not synchronously).
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [session?.me.userId, session?.partner.userId]); // eslint-disable-line react-hooks/exhaustive-deps

  // ---------- live streams ----------
  useEffect(() => {
    if (!session) return;
    const emit = (a: Arrival) => arrivals.current.forEach((fn) => fn(a));
    const upsert = <T extends Notification>(list: T[] | null, n: T) => [n, ...(list ?? []).filter((x) => x.id !== n.id)];
    const stopMe = api.streamNotifications(session.me, {
      onOpen: () => setStreams((s) => ({ ...s, me: true })),
      onDrop: () => setStreams((s) => ({ ...s, me: false })),
      onNotification: (n, at) => {
        setMyNotes((l) => upsert(l, n));
        resolveNames([n.latestActorId]);
        emit({ n, at, to: "me" });
      },
    });
    const stopPartner = api.streamNotifications(session.partner, {
      onOpen: () => setStreams((s) => ({ ...s, partner: true })),
      onDrop: () => setStreams((s) => ({ ...s, partner: false })),
      onNotification: (n, at) => {
        setPartnerNotes((l) => upsert(l, { ...n, at }));
        resolveNames([n.latestActorId]);
        emit({ n, at, to: "partner" });
      },
    });
    return () => {
      stopMe();
      stopPartner();
      setStreams({ me: false, partner: false });
    };
  }, [session?.me.userId, session?.partner.userId]); // eslint-disable-line react-hooks/exhaustive-deps

  /** Resolve with the click-relative arrival time of the first matching push, or null on timeout. */
  const waitForPush = (to: "me" | "partner", actorId: string, t0: number, timeout = WAIT_MS) =>
    new Promise<number | null>((resolve) => {
      const fn = (a: Arrival) => {
        if (a.to === to && a.n.latestActorId === actorId && a.n.type === "FOLLOW") finish(a.at - t0);
      };
      const timer = setTimeout(() => finish(null), timeout);
      const finish = (v: number | null) => {
        clearTimeout(timer);
        arrivals.current = arrivals.current.filter((x) => x !== fn);
        resolve(v);
      };
      arrivals.current.push(fn);
    });

  // ---------- actions ----------
  const run = async (label: string, fn: () => Promise<void>) => {
    if (busy) return;
    setBusy(label);
    setError(null);
    try {
      await fn();
    } catch (e) {
      const message = e instanceof Error ? e.message : "Something went wrong.";
      setError(message);
      setCapture((c) => (c && c.state === "running" ? { ...c, state: "error", error: message } : c));
    } finally {
      setBusy(null);
    }
  };

  const start = (displayName: string) =>
    run("start", async () => {
      const handle = randomHandle();
      const password = randomPassword();
      const me = await api.signUp(`guest-${handle}@vertex.demo`, `guest_${handle}`, password, displayName);
      await api.putProfile(me, displayName);
      const partnerIndex = pickPartnerIndex();
      const partner = await api.signIn(seededEmail(partnerIndex), DEMO_PASSWORD);
      partner.displayName = seededName(partnerIndex);
      setDone([]);
      setLastEvent(null);
      setCapture(null);
      setSession({ me, partner, partnerIndex });
    });

  const reset = () => {
    setSession(null);
    setDone([]);
    setLastEvent(null);
    setCapture(null);
    setMyFeed(null);
    setMyNotes(null);
    setPartnerNotes(null);
    setPeople(null);
  };

  const pending = (id: string, label: string, mark = "waiting"): Hop => ({ id, label, start: null, end: null, mark });

  /** Follow `target` as `actor` and trace the event to the recipient's open stream. */
  const traceFollow = async (kind: "follow" | "followBack", actor: Account, target: Account, to: "me" | "partner") => {
    const recipientName = to === "partner" ? session!.partner.displayName : "you";
    let hops: Hop[] = [
      pending("browser", "Browser"),
      pending("gateway", "Gateway"),
      pending("graph", "Graph"),
      pending("outbox", "Outbox"),
      pending("kafka", "Kafka"),
      pending("notify", "Notify"),
      pending("partner", to === "partner" ? "Partner" : "You"),
    ];
    const base: Capture = {
      kind,
      title: kind === "follow" ? `You follow ${session!.partner.displayName}` : `${session!.partner.displayName} follows you back`,
      state: "running",
      hops,
      meta: [],
      note: "Capturing. Each lane fills in as its timing comes back.",
    };
    setCapture(base);
    const update = (patch: Partial<Capture>) => setCapture((c) => (c ? { ...c, ...patch, hops: [...hops] } : c));

    // A follow edge that already exists produces no new event, so start from a clean edge.
    const rel = await api.relationship(actor, target.userId);
    if (rel.data.following) await api.unfollow(actor, target.userId);

    const t0 = Date.now();
    const arrival = waitForPush(to, actor.userId, t0);
    const res = track(await api.follow(actor, target.userId));
    const req = requestHops(res, "graph", "Graph");
    hops = [...req.hops, ...hops.slice(3)];
    update({});

    const row = await until(async () => {
      const { data } = await api.myEvents(actor, 10);
      return data.items.find(
        (r) => r.kind === "EVENT" && r.type === "FOLLOW" && r.recipientId === target.userId && Date.parse(r.createdAt) > t0 - 300000 && r.publishedAt,
      );
    });
    if (!row || !row.eventId) {
      hops[3] = { ...hops[3], mark: "not published yet" };
      update({ state: "partial", note: "The outbox row has not been published after 20 seconds. The relay may be catching up; try again in a moment." });
      return;
    }
    const at = fromServer(req.anchor, row.createdAt);
    hops[3] = { id: "outbox", label: "Outbox", start: at(row.createdAt), end: at(row.publishedAt!), mark: `${ms(Date.parse(row.publishedAt!) - Date.parse(row.createdAt))} until the relay sent it` };
    update({ meta: [["event id", row.eventId]] });

    const got = await until(async () => (await api.delivery(actor, row.eventId!)).data);
    if (!got) {
      hops[4] = { ...hops[4], mark: "not consumed yet" };
      update({ state: "partial", note: "Kafka has the event, but Notify has not recorded it after 20 seconds." });
      return;
    }
    hops[4] = { id: "kafka", label: "Kafka", start: at(row.publishedAt!), end: at(got.processedAt), mark: `p${got.partition} offset ${got.offset}, consumed in ${ms(Date.parse(got.processedAt) - Date.parse(row.publishedAt!))}` };
    hops[5] = { id: "notify", label: "Notify", start: at(got.processedAt), end: at(got.processedAt), mark: "new id: ledger row + notification" };
    update({});

    const arrived = await arrival;
    hops[6] = arrived === null
      ? { ...hops[6], mark: "no push seen (stream reconnecting?)" }
      : { id: "partner", label: hops[6].label, start: arrived, end: arrived, mark: `arrived ${ms(arrived)} after the click` };

    setLastEvent((prev) => (kind === "follow" ? row.eventId : prev));
    markDone(kind);
    update({
      state: arrived === null ? "partial" : "done",
      meta: [
        ["event id", row.eventId],
        ["topic", `${row.topic}, partition ${got.partition}`],
        ["offset", String(got.offset)],
        ["outbox wait", ms(Date.parse(row.publishedAt!) - Date.parse(row.createdAt))],
        ["click to push", arrived === null ? "not seen" : ms(arrived)],
      ],
      note:
        `Graph committed the follow and outbox row ${short(row.eventId)} together. The relay published it, Notify had never seen that id, ` +
        `so it wrote one ledger row and one notification and pushed it to ${recipientName}.`,
    });
    refresh();
  };

  const follow = () => run("follow", () => traceFollow("follow", session!.me, session!.partner, "partner"));
  const followBack = () => run("followBack", () => traceFollow("followBack", session!.partner, session!.me, "me"));

  const post = (content: string) =>
    run("post", async () => {
      const { me, partner } = session!;
      let hops: Hop[] = [pending("browser", "Browser"), pending("gateway", "Gateway"), pending("feed", "Feed"), pending("partner", "Partner's feed")];
      setCapture({ kind: "post", title: "You post", state: "running", hops, meta: [], note: "Capturing." });
      const t0 = Date.now();
      const res = track(await api.createPost(me, content));
      const req = requestHops(res, "feed", "Feed");
      hops = [...req.hops, hops[3]];
      const [{ data: c }, { data: rel }] = await Promise.all([api.counts(me, me.userId), api.relationship(partner, me.userId)]);
      const fanout = c.followers > 20 ? "pull" : "push";

      let found: number | null = null;
      if (rel.following) {
        const hit = await until(async () => ((await api.feed(partner, 20)).data.items.some((p) => p.id === res.data.id) ? Date.now() : null), Date.now() + 8000, 300);
        found = hit === null ? null : hit - t0;
      }
      hops[3] = found !== null
        ? { id: "partner", label: "Partner's feed", start: found, end: found, mark: `there on the first read after ${ms(found)}` }
        : { ...hops[3], mark: rel.following ? "not found yet" : "not a follower, nothing delivered" };
      markDone("post");
      setCapture({
        kind: "post",
        title: "You post",
        state: "done",
        hops,
        meta: [
          ["post id", res.data.id],
          ["your followers", String(c.followers)],
          ["fan-out", fanout === "push" ? "on write" : "on read"],
        ],
        note:
          fanout === "push"
            ? `You have ${c.followers} follower${c.followers === 1 ? "" : "s"}, at or under Feed's threshold of 20, so Feed asked Graph for them and wrote the post into each timeline before answering.` +
              (rel.following ? "" : ` ${partner.displayName} doesn't follow you yet, so nobody received it. Run step 2 first.`)
            : `You have ${c.followers} followers, over Feed's threshold of 20, so Feed stored the post once; followers pull it when they read.`,
      });
      refresh();
    });

  const replayLast = () =>
    run("replay", async () => {
      const { me, partner } = session!;
      if (!lastEvent) throw new Error("Run step 1 first: the replay needs one of your follow events.");
      let hops: Hop[] = [pending("browser", "Browser"), pending("gateway", "Gateway"), pending("graph", "Graph"), pending("outbox", "Outbox"), pending("kafka", "Kafka"), pending("notify", "Notify"), pending("partner", "Partner")];
      const update = (patch: Partial<Capture>) => setCapture((c) => (c ? { ...c, ...patch, hops: [...hops] } : c));
      setCapture({ kind: "replay", title: `Replay event ${short(lastEvent)}`, state: "running", hops, meta: [], note: "Capturing." });
      const before = (await api.delivery(me, lastEvent)).data.duplicatesIgnored;
      const t0 = Date.now();
      let pushed: number | null = null;
      const push = waitForPush("partner", me.userId, t0, WAIT_MS + 4000).then((v) => (pushed = v));
      const res = track(await api.replay(me, lastEvent));
      const req = requestHops(res, "graph", "Graph");
      hops = [...req.hops, ...hops.slice(3)];
      hops[2] = { ...hops[2], mark: `${hops[2].mark}: new outbox row, same id` };
      update({});
      const row = await until(async () => (await api.myEvents(me, 10)).data.items.find((r) => r.outboxId === res.data.outboxId && r.publishedAt));
      if (!row) {
        update({ state: "partial", note: "The replayed row has not been published after 20 seconds." });
        return;
      }
      const at = fromServer(req.anchor, row.createdAt);
      hops[3] = { id: "outbox", label: "Outbox", start: at(row.createdAt), end: at(row.publishedAt!), mark: "published again" };
      update({});
      const dup = await until(async () => {
        const d = (await api.delivery(me, lastEvent)).data;
        return d.duplicatesIgnored > before && d.lastDuplicateAt ? d : null;
      });
      if (!dup) {
        update({ state: "partial", note: "Notify has not reported the duplicate after 20 seconds." });
        return;
      }
      hops[4] = { id: "kafka", label: "Kafka", start: at(row.publishedAt!), end: at(dup.lastDuplicateAt!), mark: "delivered a second time" };
      hops[5] = { id: "notify", label: "Notify", start: at(dup.lastDuplicateAt!), end: at(dup.lastDuplicateAt!), mark: `id already in the ledger, ignored` };
      update({});
      await Promise.race([push, sleep(3000)]);
      hops[6] = pushed === null
        ? { id: "partner", label: "Partner", start: null, end: null, mark: "nothing pushed, as intended" }
        : { id: "partner", label: "Partner", start: pushed, end: pushed, mark: "pushed: idempotency failed", fault: true };
      markDone("replay");
      update({
        state: "done",
        meta: [
          ["event id", lastEvent],
          ["duplicates ignored", `${before} before, ${dup.duplicatesIgnored} now`],
          ["original offset", String(dup.offset)],
        ],
        note: `The same event id travelled the whole pipeline again. Notify's ledger already had it, so it counted the duplicate and wrote nothing. ${partner.displayName} got no second notification.`,
      });
    });

  const poison = () =>
    run("poison", async () => {
      const { me } = session!;
      let hops: Hop[] = [pending("browser", "Browser"), pending("gateway", "Gateway"), pending("graph", "Graph"), pending("outbox", "Outbox"), pending("kafka", "Kafka"), pending("notify", "Notify"), pending("dlt", "DLT")];
      const update = (patch: Partial<Capture>) => setCapture((c) => (c ? { ...c, ...patch, hops: [...hops] } : c));
      setCapture({ kind: "poison", title: "Send a record Notify can't read", state: "running", hops, meta: [], note: "Capturing." });
      const seen = new Set((await api.deadLetters(me, 10)).data.items.map((d) => d.id));
      const res = track(await api.poison(me));
      const req = requestHops(res, "graph", "Graph");
      hops = [...req.hops, ...hops.slice(3)];
      hops[2] = { ...hops[2], mark: `${hops[2].mark}: truncated JSON queued` };
      update({});
      const row = await until(async () => (await api.myEvents(me, 10)).data.items.find((r) => r.outboxId === res.data.outboxId && r.publishedAt));
      if (!row) {
        update({ state: "partial", note: "The poison row has not been published after 20 seconds." });
        return;
      }
      const at = fromServer(req.anchor, row.createdAt);
      hops[3] = { id: "outbox", label: "Outbox", start: at(row.createdAt), end: at(row.publishedAt!), mark: "published" };
      update({});
      const dl = await until(async () => (await api.deadLetters(me, 10)).data.items.find((d) => !seen.has(d.id)), Date.now() + 30000, 500);
      if (!dl) {
        update({ state: "partial", note: "No dead letter yet. Spring Kafka retries a failed record before parking it; give it a few more seconds and run this again." });
        return;
      }
      hops[4] = { id: "kafka", label: "Kafka", start: at(row.publishedAt!), end: at(dl.receivedAt), mark: `partition ${dl.originalPartition}, offset ${dl.originalOffset}` };
      hops[5] = { id: "notify", label: "Notify", start: at(dl.receivedAt), end: at(dl.receivedAt), mark: "could not parse it", fault: true };
      hops[6] = { id: "dlt", label: "DLT", start: at(dl.receivedAt), end: at(dl.receivedAt), mark: "parked on social.events.DLT" };
      markDone("poison");
      update({
        state: "done",
        meta: [
          ["error", dl.error.split(" at [Source")[0]],
          ["from", `${dl.originalTopic}, partition ${dl.originalPartition}, offset ${dl.originalOffset}`],
          ["payload", dl.payloadPreview],
        ],
        note: "Notify failed to parse the record, so its error handler moved it to the dead-letter topic and committed past it. The partition kept flowing; nothing behind it was blocked.",
      });
    });

  const followPerson = (userId: string) =>
    run(`person:${userId}`, async () => {
      track(await api.follow(session!.me, userId));
      setFollowing((f) => ({ ...f, [userId]: true }));
      refresh();
    });

  return {
    hydrated, session, done, lastEvent, capture, busy, error, remaining, streams,
    myFeed, myNotes, partnerNotes, people, following, nameOf,
    start, reset, follow, followBack, post, replay: replayLast, poison, followPerson, refresh, clearError: () => setError(null),
  };
}
