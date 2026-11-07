"use client";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { gsap } from "gsap";
import { ease, prefersReducedMotion } from "@/engine";
import { describe, StatusPanel, useHealth } from "../StatusPanel";
import { Lanes } from "../Lanes";
import { toLanes, ms, type Capture } from "./capture";
import { useDemo, type Recording, type StepId } from "./useDemo";
import recordingData from "@/data/recording.json";
import { randomHandle } from "./seeded";
import { site } from "@/data/site";
import type { Notification } from "@/lib/api";

const time = (iso: string | number) => new Date(iso).toISOString().slice(11, 19);
const recording = recordingData as unknown as Recording;
const recordedOn = new Date(recording.recordedAt).toISOString().slice(0, 16).replace("T", " ");

function noteText(n: Notification, nameOf: (id: string) => string, subject: string) {
  const who = nameOf(n.latestActorId);
  const others = n.actorCount > 1 ? ` and ${n.actorCount - 1} other${n.actorCount > 2 ? "s" : ""}` : "";
  const verb: Record<string, string> = {
    FOLLOW: "followed",
    FRIEND_REQUEST: "sent a friend request to",
    FRIEND_ACCEPT: "accepted a friend request from",
  };
  return `${who}${others} ${verb[n.type] ?? "interacted with"} ${subject}`;
}

export function Console() {
  const d = useDemo();
  const health = useHealth();
  const [replaying, setReplaying] = useState(false);
  const watch = () => {
    setReplaying(true);
    window.scrollTo({ top: 0 });
  };
  if (!d.hydrated) return <div className="console" aria-busy="true" />;
  if (replaying) return <Replay health={health} onExit={() => setReplaying(false)} />;
  if (!d.session) return <Start onStart={d.start} onReplay={watch} busy={d.busy === "start"} error={d.error} health={health} />;
  return <Bench d={d} health={health} onReplay={watch} />;
}

/* ---------------- start ---------------- */

function Start({
  onStart,
  onReplay,
  busy,
  error,
  health,
}: {
  onStart: (name: string) => void;
  onReplay: () => void;
  busy: boolean;
  error: string | null;
  health: ReturnType<typeof useHealth>;
}) {
  // Start only renders on the client (after the session is read), so a random default is safe here.
  const [name, setName] = useState(() => `Visitor ${randomHandle().slice(0, 4).toUpperCase()}`);
  const down = health.state === "unreachable" || (health.state === "ok" && health.data.status !== "UP");
  const text = describe(health);
  const root = useRef<HTMLElement>(null);
  // This screen mounts on the client after the engine's page reveals ran, so it plays its own
  // entrance: the parts rise in order, as the page intro does elsewhere.
  useLayoutEffect(() => {
    if (prefersReducedMotion() || !root.current) return;
    const ctx = gsap.context(() => {
      gsap.from("[data-enter]", { y: 28, autoAlpha: 0, duration: 1, ease: ease.out, stagger: 0.12 });
    }, root);
    return () => ctx.revert();
  }, []);
  return (
    <section ref={root} className="start grid" aria-labelledby="start-title">
      <h2 id="start-title" className="start__title display">
        Put a probe on a live social network.
      </h2>
      <div className="start__body" data-enter>
        <p className="lede">
          You get a throwaway account and a partner: one of the 80 seeded people, signed in beside you so you can watch
          both ends of every event.
        </p>
        <form
          className="start__form"
          onSubmit={(e) => {
            e.preventDefault();
            if (!down && name.trim()) onStart(name.trim().slice(0, 40));
          }}
        >
          <label htmlFor="display-name">Your display name</label>
          <input id="display-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={40} autoComplete="off" required />
          <p className="legend">No email or password to remember. Everything resets at {site.resetUtc}.</p>
          <div style={{ marginTop: "0.75rem" }}>
            <button className="btn" type="submit" disabled={busy || down} aria-describedby={down ? "start-blocked" : undefined}>
              {busy ? "Creating your account" : "Start the demo"}
              <span className="btn__arrow" aria-hidden="true">
                →
              </span>
            </button>
          </div>
          {down && (
            <div id="start-blocked" className="notice" role="status">
              <p>
                <strong>{text.title}.</strong> {text.body}
              </p>
              <p style={{ marginTop: "0.5rem" }}>
                Meanwhile you can watch a run recorded against the real services on {recordedOn} UTC.
              </p>
              <button type="button" className="btn btn--small" style={{ marginTop: "0.75rem" }} onClick={onReplay}>
                Watch the recorded run
                <span className="btn__arrow" aria-hidden="true">
                  →
                </span>
              </button>
            </div>
          )}
          {error && (
            <p className="notice notice--fault" role="alert">
              {error}
            </p>
          )}
        </form>
      </div>
      <div className="start__panel" data-enter>
        <StatusPanel />
      </div>
    </section>
  );
}

/* ---------------- bench ---------------- */

type D = ReturnType<typeof useDemo>;

const STEPS: { id: StepId; title: (p: string) => string; body: (p: string) => string }[] = [
  { id: "follow", title: (p) => `Follow ${p}`, body: (p) => `Watch the follow travel from your browser to ${p}'s open notification stream.` },
  { id: "followBack", title: (p) => `Have ${p} follow you back`, body: () => "Same pipeline, the other way: the notification lands in your own list." },
  { id: "post", title: () => "Post something", body: (p) => `With ${p} following you, Feed writes your post into their timeline as you send it.` },
  { id: "replay", title: () => "Replay your follow event", body: () => "Send the same event id through Kafka again. Notify should ignore it." },
  { id: "poison", title: () => "Send a record Notify can't read", body: () => "A malformed record should land in the dead-letter topic, not block the partition." },
];

function Bench({ d, health, onReplay }: { d: D; health: ReturnType<typeof useHealth>; onReplay: () => void }) {
  const { session } = d;
  const p = session!.partner.displayName.split(" ")[0];
  const current = STEPS.find((s) => !d.done.includes(s.id))?.id ?? null;
  const [draft, setDraft] = useState("");
  const text = describe(health);
  const healthy = health.state === "ok" && health.data.status === "UP";

  const actions: Record<StepId, () => void> = {
    follow: d.follow,
    followBack: d.followBack,
    post: () => d.post(draft.trim() || `Hello from ${session!.me.displayName}.`),
    replay: d.replay,
    poison: d.poison,
  };

  return (
    <div className="console">
      <div className="console__bar screen" role="region" aria-label="Session">
        <div className="console__bar-group">
          <span className="console__bar-item">
            <span className={`dot ${healthy ? "dot--up" : health.state === "loading" ? "dot--wait" : "dot--down"}`} aria-hidden="true" />
            <b>{text.title}</b>
          </span>
          <span className="console__bar-item">
            you <b>{session!.me.displayName}</b>
          </span>
          <span className="console__bar-item">
            partner <b>{session!.partner.displayName}</b>
          </span>
          <span className="console__bar-item" title="X-RateLimit-Remaining from the gateway's last answer">
            rate budget <b>{d.remaining ?? "..."}</b>
          </span>
        </div>
        <button type="button" className="btn btn--small btn--screen-ghost" onClick={d.reset}>
          Start over
        </button>
      </div>

      {!healthy && health.state !== "loading" && (
        <p className="notice notice--fault" role="status">
          <strong>{text.title}.</strong> {text.body}{" "}
          <button type="button" className="link" onClick={onReplay}>
            Watch the recorded run instead
          </button>
          .
        </p>
      )}

      <div className="console__main">
        <section className="console__steps panel" aria-labelledby="steps-title">
          <div className="panel__head">
            <h2 id="steps-title" className="panel__title">
              What to try
            </h2>
            <span className="legend">
              {d.done.length} of {STEPS.length}
            </span>
          </div>
          <ol className="steps">
            {STEPS.map((s, i) => {
              const isDone = d.done.includes(s.id);
              const isCurrent = s.id === current;
              return (
                <li key={s.id} className={`step${isDone ? " is-done" : ""}${isCurrent ? " is-current" : ""}`}>
                  <span className="step__mark" aria-hidden="true">
                    {isDone ? "✓" : i + 1}
                  </span>
                  <div>
                    <h3 className="step__title">
                      {s.title(p)}
                      {isDone && <span className="sr-only"> (done)</span>}
                    </h3>
                    <p className="step__body">{s.body(p)}</p>
                    {s.id === "post" && (isCurrent || isDone) && (
                      <label className="sr-only" htmlFor="step-post">
                        Post text
                      </label>
                    )}
                    {s.id === "post" && (isCurrent || isDone) && (
                      <textarea
                        id="step-post"
                        className="panel"
                        style={{ width: "100%", marginTop: "0.6rem", padding: "0.6rem", minHeight: "4rem", resize: "vertical" }}
                        maxLength={280}
                        placeholder={`Hello from ${session!.me.displayName}.`}
                        value={draft}
                        onChange={(e) => setDraft(e.target.value)}
                      />
                    )}
                    {(isCurrent || isDone) && (
                      <div className="step__actions">
                        <button
                          type="button"
                          className={`btn btn--small${isDone ? " btn--ghost" : ""}`}
                          onClick={actions[s.id]}
                          disabled={!!d.busy || !healthy}
                        >
                          {d.busy === s.id ? "Tracing" : isDone ? "Run again" : "Run"}
                        </button>
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>
          {d.error && (
            <p className="notice notice--fault" role="alert" style={{ margin: "0 1rem 1rem" }}>
              {d.error}
            </p>
          )}
          {!current && (
            <p className="notice" style={{ margin: "0 1rem 1rem" }}>
              That is the whole pipeline. Next: follow a few people below and read your feed, or{" "}
              <a className="link" href={site.github} target="_blank" rel="noopener noreferrer">
                read how each service is built
              </a>
              .
            </p>
          )}
        </section>

        <Scope capture={d.capture} />

        <section className="console__partner panel screen" aria-labelledby="partner-title">
          <div className="panel__head" style={{ borderColor: "var(--c-screen-line)" }}>
            <h2 id="partner-title" className="panel__title">
              {session!.partner.displayName}&rsquo;s live stream
            </h2>
            <span className="legend">
              <span className={`dot ${d.streams.partner ? "dot--up" : "dot--wait"}`} aria-hidden="true" />{" "}
              {d.streams.partner ? "open" : "connecting"}
            </span>
          </div>
          <NoteList subject={p} notes={d.partnerNotes} nameOf={d.nameOf} empty={`Nothing yet. When you follow ${p}, the notification arrives here over Server-Sent Events.`} />
        </section>
      </div>

      <div className="console__lower">
        <section className="panel" aria-labelledby="feed-title">
          <div className="panel__head">
            <h2 id="feed-title" className="panel__title">
              Your feed
            </h2>
            <button type="button" className="link link--quiet legend" onClick={d.refresh}>
              Refresh
            </button>
          </div>
          {d.myFeed === null ? (
            <Loading />
          ) : d.myFeed.length === 0 ? (
            <p className="empty">
              Empty, because you follow no one yet. Follow someone popular from People: accounts with more than 20
              followers appear at once, since Feed pulls their posts when you read. Other people&rsquo;s older posts
              don&rsquo;t backfill; only their new posts are pushed to you.
            </p>
          ) : (
            <ul className="feed-list">
              {d.myFeed.map((post) => (
                <li key={post.id}>
                  <div className="post__who">
                    <b>{d.nameOf(post.authorId)}</b>
                    <span className="legend">{time(post.createdAt)} UTC</span>
                  </div>
                  <p className="post__text">{post.content}</p>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel" aria-labelledby="notes-title">
          <div className="panel__head">
            <h2 id="notes-title" className="panel__title">
              Your notifications
            </h2>
            <span className="legend">
              <span className={`dot ${d.streams.me ? "dot--up" : "dot--wait"}`} aria-hidden="true" style={{ color: d.streams.me ? "#1f8a4c" : undefined }} />{" "}
              {d.streams.me ? "live" : "connecting"}
            </span>
          </div>
          <NoteList subject="you" notes={d.myNotes} nameOf={d.nameOf} empty={`None yet. Step 2 makes ${p} follow you; the notification arrives here live.`} />
        </section>

        <section className="panel" aria-labelledby="people-title">
          <div className="panel__head">
            <h2 id="people-title" className="panel__title">
              People to follow
            </h2>
            <span className="legend">from Recommend</span>
          </div>
          {d.people === null ? (
            <Loading />
          ) : d.people.length === 0 ? (
            <p className="empty">No suggestions right now. Recommend found no one you aren&rsquo;t already connected to.</p>
          ) : (
            <ul className="people">
              {d.people.map((s) => (
                <li key={s.userId}>
                  <span>
                    {d.nameOf(s.userId)}
                    <span className="people__why">
                      {s.reason === "POPULAR" ? "Popular: you have no friends in common yet" : `${s.mutualFriends} mutual friend${s.mutualFriends === 1 ? "" : "s"}`}
                    </span>
                  </span>
                  <button
                    type="button"
                    className="btn btn--small btn--ghost"
                    disabled={!!d.busy || d.following[s.userId]}
                    onClick={() => d.followPerson(s.userId)}
                  >
                    {d.following[s.userId] ? "Following" : "Follow"}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}

function Loading() {
  return (
    <div className="empty" aria-busy="true" aria-label="Loading">
      <span className="skeleton" style={{ width: "70%" }} />
      <span className="skeleton" style={{ width: "45%", marginTop: "0.6rem" }} />
    </div>
  );
}

function NoteList({
  notes,
  nameOf,
  empty,
  subject,
}: {
  notes: (Notification & { at?: number })[] | null;
  nameOf: (id: string) => string;
  empty: string;
  subject: string;
}) {
  if (notes === null) return <Loading />;
  if (notes.length === 0) return <p className="empty">{empty}</p>;
  return (
    <ul className="note-list" aria-live="polite">
      {notes.slice(0, 8).map((n) => (
        // Keyed on the update time: a coalesced notification that changes remounts and flashes once.
        <li key={n.id + n.updatedAt} className={n.at ? "is-new" : undefined}>
          <span>{noteText(n, nameOf, subject)}</span>
          <span className="legend">
            {time(n.updatedAt)} UTC{n.actorCount > 1 ? `, coalesced from ${n.actorCount} events` : ""}
          </span>
        </li>
      ))}
    </ul>
  );
}

/* ---------------- the scope ---------------- */

function Scope({ capture: c, replay = false }: { capture: Capture | null; replay?: boolean }) {
  const view = useMemo(() => (c ? toLanes(c) : null), [c]);
  return (
    <section className="console__scope panel screen" aria-labelledby="scope-title">
      <div className="panel__head" style={{ borderColor: "var(--c-screen-line)" }}>
        <h2 id="scope-title" className="panel__title">
          {c ? `Capture: ${c.title}` : "Capture"}
        </h2>
        <span className="legend" aria-live="polite">
          {c ? (c.state === "running" ? (replay ? "replaying" : "recording") : c.state === "done" ? `${ms(view!.span / 1.04)} end to end` : c.state === "partial" ? "partial" : "failed") : "armed"}
        </span>
      </div>
      <div className="panel__body">
        {!c || !view ? (
          <div className="scope-empty">
            <p>No capture yet.</p>
            <p>
              {replay
                ? "Play step 1 and each lane fills in at the speed it did in the recorded run."
                : "Run step 1 and each lane fills in from the timings Vertex returns."}
            </p>
          </div>
        ) : (
          <>
            <Lanes lanes={view.lanes} axis={["click", `+${ms(view.span)}`]} />
            <ol className="sr-only">
              {c.hops.map((h) => (
                <li key={h.id}>
                  {h.label}: {h.mark}
                </li>
              ))}
            </ol>
            {c.meta.length > 0 && (
              <dl className="scope-meta">
                {c.meta.map(([k, v]) => (
                  <div key={k}>
                    <dt>{k}</dt>
                    <dd>{v}</dd>
                  </div>
                ))}
              </dl>
            )}
            <p className="scope-note" aria-live="polite">
              {c.error ? <strong>{c.error} </strong> : null}
              {c.note}
            </p>
            <p className="scope-note legend" style={{ marginTop: "0.6rem" }}>
              Browser times are on your clock; server times are placed by aligning the gateway&rsquo;s answer with the
              middle of your round trip.
            </p>
          </>
        )}
      </div>
    </section>
  );
}

/* ---------------- the recorded run ---------------- */

/** Plays a recorded capture back at its real speed: each lane appears when it ended in the run. */
function useReplay() {
  const [capture, setCapture] = useState<Capture | null>(null);
  const [done, setDone] = useState<StepId[]>([]);
  const [playing, setPlaying] = useState<StepId | null>(null);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  const play = (id: StepId) => {
    const full = recording.steps[id];
    if (!full) return;
    timers.current.forEach(clearTimeout);
    timers.current = [];
    const finish = () => {
      setCapture(full);
      setPlaying(null);
      setDone((d) => (d.includes(id) ? d : [...d, id]));
    };
    if (prefersReducedMotion()) return finish();
    setPlaying(id);
    setCapture({
      ...full,
      state: "running",
      meta: [],
      note: "Replaying the recorded capture at the speed it ran.",
      hops: full.hops.map((h) => ({ ...h, start: null, end: null, mark: "waiting" })),
    });
    let last = 0;
    full.hops.forEach((h, i) => {
      if (h.end === null) return;
      const at = Math.min(h.end, 6000);
      last = Math.max(last, at);
      timers.current.push(
        setTimeout(() => setCapture((c) => (c ? { ...c, hops: c.hops.map((x, j) => (j === i ? full.hops[i] : x)) } : c)), at),
      );
    });
    timers.current.push(setTimeout(finish, last + 250));
  };
  return { capture, done, playing, play };
}

function Replay({ health, onExit }: { health: ReturnType<typeof useHealth>; onExit: () => void }) {
  const r = useReplay();
  const { me, partner } = recording;
  const p = partner.displayName.split(" ")[0];
  const live = health.state === "ok" && health.data.status === "UP";
  const current = STEPS.find((s) => !r.done.includes(s.id))?.id ?? null;
  const nameOf = (id: string) =>
    id === me.userId ? `${me.displayName} (you)` : id === partner.userId ? partner.displayName : (recording.names[id] ?? "Someone");

  return (
    <div className="console">
      <div className="console__bar screen" role="region" aria-label="Recorded run">
        <div className="console__bar-group">
          <span className="console__bar-item">
            <span className="dot dot--wait" aria-hidden="true" />
            <b>Recorded run</b>
          </span>
          <span className="console__bar-item">
            captured <b>{recordedOn} UTC</b>
          </span>
          <span className="console__bar-item">
            you <b>{me.displayName}</b>
          </span>
          <span className="console__bar-item">
            partner <b>{partner.displayName}</b>
          </span>
        </div>
        <button type="button" className="btn btn--small btn--screen-ghost" onClick={onExit}>
          {live ? "Start the live demo" : "Back"}
        </button>
      </div>

      <p className="notice" role="status">
        <strong>This is a recording, not the live system.</strong> The backend is offline right now, so this page
        replays a run captured against the real services on {recordedOn} UTC. Every timing, offset and id below came
        from that run; nothing is sent while you watch.
        {live ? " The backend is back up, so you can start the live demo instead." : ""}
      </p>

      <div className="console__main">
        <section className="console__steps panel" aria-labelledby="steps-title">
          <div className="panel__head">
            <h2 id="steps-title" className="panel__title">
              What happened
            </h2>
            <span className="legend">
              {r.done.length} of {STEPS.length}
            </span>
          </div>
          <ol className="steps">
            {STEPS.map((s, i) => {
              const isDone = r.done.includes(s.id);
              const isCurrent = s.id === current;
              return (
                <li key={s.id} className={`step${isDone ? " is-done" : ""}${isCurrent ? " is-current" : ""}`}>
                  <span className="step__mark" aria-hidden="true">
                    {isDone ? "✓" : i + 1}
                  </span>
                  <div>
                    <h3 className="step__title">
                      {s.title(p)}
                      {isDone && <span className="sr-only"> (played)</span>}
                    </h3>
                    <p className="step__body">{s.body(p)}</p>
                    <div className="step__actions">
                      <button
                        type="button"
                        className={`btn btn--small${isCurrent ? "" : " btn--ghost"}`}
                        onClick={() => r.play(s.id)}
                        disabled={!!r.playing || !recording.steps[s.id]}
                      >
                        {r.playing === s.id ? "Replaying" : isDone ? "Play again" : "Play"}
                      </button>
                    </div>
                  </div>
                </li>
              );
            })}
          </ol>
        </section>

        <Scope capture={r.capture} replay />

        <section className="console__partner panel screen" aria-labelledby="partner-title">
          <div className="panel__head" style={{ borderColor: "var(--c-screen-line)" }}>
            <h2 id="partner-title" className="panel__title">
              {partner.displayName}&rsquo;s stream
            </h2>
            <span className="legend">at the end of the run</span>
          </div>
          <NoteList subject={p} notes={recording.partnerNotes} nameOf={nameOf} empty="Nothing arrived in this run." />
        </section>
      </div>

      <div className="console__lower">
        <section className="panel" aria-labelledby="feed-title">
          <div className="panel__head">
            <h2 id="feed-title" className="panel__title">
              Your feed
            </h2>
            <span className="legend">as recorded</span>
          </div>
          {recording.myFeed.length === 0 ? (
            <p className="empty">The feed was empty in this run.</p>
          ) : (
            <ul className="feed-list">
              {recording.myFeed.map((post) => (
                <li key={post.id}>
                  <div className="post__who">
                    <b>{nameOf(post.authorId)}</b>
                    <span className="legend">{time(post.createdAt)} UTC</span>
                  </div>
                  <p className="post__text">{post.content}</p>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel" aria-labelledby="notes-title">
          <div className="panel__head">
            <h2 id="notes-title" className="panel__title">
              Your notifications
            </h2>
            <span className="legend">as recorded</span>
          </div>
          <NoteList subject="you" notes={recording.myNotes} nameOf={nameOf} empty="None arrived in this run." />
        </section>

        <section className="panel" aria-labelledby="people-title">
          <div className="panel__head">
            <h2 id="people-title" className="panel__title">
              People to follow
            </h2>
            <span className="legend">from Recommend, as recorded</span>
          </div>
          {recording.people.length === 0 ? (
            <p className="empty">Recommend had no suggestions in this run.</p>
          ) : (
            <ul className="people">
              {recording.people.map((s) => (
                <li key={s.userId}>
                  <span>
                    {nameOf(s.userId)}
                    <span className="people__why">
                      {s.reason === "POPULAR" ? "Popular: no friends in common yet" : `${s.mutualFriends} mutual friend${s.mutualFriends === 1 ? "" : "s"}`}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
