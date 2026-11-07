import Link from "next/link";
import { CopyCode } from "@/components/CopyCode";
import { Footer } from "@/components/Footer";
import { StatusPanel } from "@/components/StatusPanel";
import { TraceSchematic } from "@/components/TraceSchematic";
import { facts, hosting, services, site } from "@/data/site";

export default function Home() {
  return (
    <>
      <section className="hero wrap grid" aria-labelledby="hero-title">
        <h1 id="hero-title" className="hero__title display" data-split="lines" data-intro>
          A social network, taken apart so you can watch it work.
        </h1>
        <div className="hero__copy" data-reveal="up" data-intro data-delay="0.25">
          <p className="lede">
            Seven Java services on Postgres, Redis and Kafka, running live on one free server. Follow someone, and
            the demo shows the event cross the gateway, an outbox, Kafka and an idempotent consumer.
          </p>
          <div className="hero__actions">
            <Link className="btn" href="/demo/">
              Open the live demo
              <span className="btn__arrow" aria-hidden="true">
                →
              </span>
            </Link>
            <a className="link" href={site.github} target="_blank" rel="noreferrer">
              Read the code
            </a>
          </div>
          <p className="hero__note">
            No email needed. The demo makes you a throwaway account and pairs you with one of 80 seeded people, so you
            can see both ends of every event.
          </p>
        </div>
        <div className="hero__panel" data-reveal="fade" data-intro data-delay="0.4">
          <StatusPanel />
        </div>
      </section>

      <TraceSchematic />

      <section id="services" className="section wrap" aria-labelledby="services-title">
        <div className="grid">
          <div className="section__head">
            <h2 id="services-title" className="h2 display" data-split="lines">
              Seven services, one front door
            </h2>
            <p className="prose">
              Each service owns its data and its schema. They find each other by name on a private network; only the
              gateway is reachable, and only through the tunnel.
            </p>
          </div>
        </div>
        <ul className="index" role="list">
          {services.map((s) => (
            <li className="index__row" key={s.name}>
              <h3 className="index__name display">{s.name}</h3>
              <span className="index__port legend">:{s.port}</span>
              <p className="index__owns">{s.owns}</p>
              <span className="index__store legend">{s.store}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="section section--tight wrap" aria-labelledby="break-title">
        <div className="grid pair">
          <div className="pair__text">
            <h2 id="break-title" className="h2 display" data-split="lines">
              Then try to break it
            </h2>
            <p className="prose">
              Kafka delivers <strong>at least once</strong>, so Notify has to survive seeing the same event twice. In the
              demo you can replay one of your own events with the same id, and watch Notify count the duplicate instead
              of notifying anyone again.
            </p>
            <p className="prose">
              You can also send a record Notify cannot read. Instead of blocking the partition behind it, the consumer
              parks it on <code>social.events.DLT</code>, and the demo shows you the dead letter with its error.
            </p>
          </div>
          <div className="pair__media" aria-label="What the demo shows for each test">
            <div className="capture screen" data-reveal="draw">
              <div className="capture__title">
                <span>Replay, same event id</span>
                <span>idempotency</span>
              </div>
              <div className="capture__line">
                <span>Graph outbox</span>
                <span className="sig">new row, kind REPLAY</span>
              </div>
              <div className="capture__line">
                <span>Kafka</span>
                <span>delivered again</span>
              </div>
              <div className="capture__line">
                <span>Notify ledger</span>
                <span className="ok">already seen, duplicate counted</span>
              </div>
              <div className="capture__line">
                <span>Partner</span>
                <span>no second notification</span>
              </div>
            </div>
            <div className="capture screen" data-reveal="draw" data-delay="0.15">
              <div className="capture__title">
                <span>Malformed record</span>
                <span>dead-letter queue</span>
              </div>
              <div className="capture__line">
                <span>Graph outbox</span>
                <span className="sig">new row, kind POISON</span>
              </div>
              <div className="capture__line">
                <span>Notify consumer</span>
                <span className="bad">cannot parse it</span>
              </div>
              <div className="capture__line">
                <span>social.events.DLT</span>
                <span className="ok">parked with its error</span>
              </div>
              <div className="capture__line">
                <span>Partition</span>
                <span>keeps flowing</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="section section--tight wrap" aria-labelledby="facts-title">
        <h2 id="facts-title" className="sr-only">
          Vertex in numbers
        </h2>
        <ul className="facts" role="list">
          {facts.map((f) => (
            <li key={f.label}>
              <span className="facts__value display">{f.value}</span>
              <span className="facts__label">{f.label}</span>
            </li>
          ))}
        </ul>
      </section>

      <section id="run" className="section wrap" aria-labelledby="run-title">
        <div className="grid run">
          <div className="run__text">
            <h2 id="run-title" className="h2 display" data-split="lines">
              Run all of it on your laptop
            </h2>
            <p className="prose">
              One command builds the seven services, starts Postgres, Redis and a single-node Kafka, waits for every
              health check and seeds the 80 demo people. The first build takes about ten minutes.
            </p>
          </div>
          <div className="run__code">
            <CopyCode label="Clone the repository" code={`git clone ${site.github}.git && cd vertex-social-graph-platform`} />
            <CopyCode label="Start the whole platform" code="docker compose up -d --build" />
          </div>
        </div>
        <div className="grid run" style={{ marginTop: "clamp(3rem, 2rem + 4vw, 6rem)" }}>
          <div className="run__text">
            <h3 className="h2 display">How it stays online for free</h3>
          </div>
          <ul className="run__code hosting" role="list">
            {hosting.map((h) => (
              <li key={h.name}>
                <span>{h.name}</span>
                <span>{h.what}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <Footer />
    </>
  );
}
