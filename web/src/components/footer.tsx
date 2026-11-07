import Link from "next/link";
import { site } from "@/data/site";

export function Footer() {
  return (
    <footer className="footer screen" aria-labelledby="footer-title">
      <h2 id="footer-title" className="footer__mega display">
        <span>Trace it</span>
        <span>yourself.</span>
      </h2>
      <div className="footer__row">
        <p className="legend">
          Built by {site.author}. Demo data resets every day at {site.resetUtc}.
        </p>
        <ul className="footer__links" role="list">
          <li>
            <Link className="link" href="/demo/">
              Live demo
            </Link>
          </li>
          <li>
            <a className="link" href={site.github} target="_blank" rel="noreferrer">
              Source on GitHub
            </a>
          </li>
          <li>
            <a className="link" href={`${site.github}#architecture`} target="_blank" rel="noreferrer">
              Architecture notes
            </a>
          </li>
        </ul>
      </div>
    </footer>
  );
}
