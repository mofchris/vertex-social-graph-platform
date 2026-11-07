"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { site } from "@/data/site";
import { Mark } from "./Mark";

export function Header() {
  const path = usePathname();
  const onDemo = path.startsWith("/demo");
  return (
    <header className="header">
      <Link href="/" className="wordmark" aria-label="Vertex, home">
        <Mark />
        VERTEX
      </Link>
      <nav className="nav" aria-label="Primary">
        <Link className="nav__link nav__link--wide" href="/#trace">
          How it works
        </Link>
        <Link className="nav__link nav__link--wide" href="/#services">
          Services
        </Link>
        <a className="nav__link" href={site.github} target="_blank" rel="noreferrer">
          Code
        </a>
        <Link className="btn btn--small" href="/demo/" aria-current={onDemo ? "page" : undefined}>
          {onDemo ? "Live demo" : "Open the live demo"}
          <span className="btn__arrow" aria-hidden="true">
            →
          </span>
        </Link>
      </nav>
    </header>
  );
}
