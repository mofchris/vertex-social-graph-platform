import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "Not found", description: "There is no page at this address." };

export default function NotFound() {
  return (
    <section className="lost" aria-labelledby="lost-title">
      <h1 id="lost-title" className="lost__title display">
        No record at this offset.
      </h1>
      <div className="capture screen" role="img" aria-label="A consumer read that found nothing at the requested offset">
        <div className="capture__title">
          <span>GET this page</span>
          <span>404</span>
        </div>
        <div className="capture__line">
          <span>Gateway</span>
          <span>no route matched</span>
        </div>
        <div className="capture__line">
          <span>Log</span>
          <span className="bad">nothing was ever written here</span>
        </div>
        <div className="capture__line">
          <span>Retry</span>
          <span className="sig">start from the beginning</span>
        </div>
      </div>
      <p>
        <Link className="btn" href="/">
          Back to the start
          <span className="btn__arrow" aria-hidden="true">
            →
          </span>
        </Link>
      </p>
    </section>
  );
}
