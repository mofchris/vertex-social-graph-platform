"use client";
import { useSyncExternalStore } from "react";

// The theme lives on <html data-theme>, set before first paint by the script in layout.tsx.
// This button flips it and remembers the choice; without a stored choice the system setting wins.
const KEY = "vertex.theme";
type Theme = "light" | "dark";

const read = (): Theme => (document.documentElement.dataset.theme === "dark" ? "dark" : "light");
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((fn) => fn());

function subscribe(fn: () => void) {
  listeners.add(fn);
  const mq = matchMedia("(prefers-color-scheme: dark)");
  const onSystem = () => {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(KEY);
    } catch {
      /* storage blocked: follow the system */
    }
    if (stored !== "light" && stored !== "dark") {
      document.documentElement.dataset.theme = mq.matches ? "dark" : "light";
      notify();
    }
  };
  mq.addEventListener("change", onSystem);
  return () => {
    listeners.delete(fn);
    mq.removeEventListener("change", onSystem);
  };
}

export function ThemeToggle() {
  const theme = useSyncExternalStore(subscribe, read, () => "light" as Theme);
  const next: Theme = theme === "dark" ? "light" : "dark";
  return (
    <button
      type="button"
      className="theme-toggle"
      aria-pressed={theme === "dark"}
      title={`Switch to ${next} theme`}
      onClick={() => {
        document.documentElement.dataset.theme = next;
        try {
          localStorage.setItem(KEY, next);
        } catch {
          /* not remembered, still switched for this page view */
        }
        notify();
      }}
    >
      <span className="theme-toggle__lamp" aria-hidden="true" />
      Dark<span className="sr-only"> theme</span>
    </button>
  );
}
