import type React from "react";
import { cn } from "@/lib/utils";

/**
 * Vertex brand mark — a graph "vertex": one hub node (primary accent) linked to
 * three neighbours, evoking a social / identity graph.
 */
export const LogoIcon = (props: React.ComponentProps<"svg">) => (
  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" {...props}>
    <line x1="12" y1="12" x2="4" y2="6" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.4" />
    <line x1="12" y1="12" x2="20" y2="7" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.4" />
    <line x1="12" y1="12" x2="12" y2="21" stroke="currentColor" strokeOpacity="0.45" strokeWidth="1.4" />
    <circle cx="4" cy="6" r="1.9" fill="currentColor" />
    <circle cx="20" cy="7" r="1.9" fill="currentColor" />
    <circle cx="12" cy="21" r="1.9" fill="currentColor" />
    <circle cx="12" cy="12" r="2.8" className="fill-primary" />
  </svg>
);

/** Full logo: mark + "Vertex" wordmark. Sized by the height in `className`. */
export const Logo = ({ className }: { className?: string }) => (
  <span
    className={cn("inline-flex items-center gap-1.5 text-foreground", className)}
  >
    <LogoIcon className="h-full w-auto" />
    <span className="font-semibold text-[0.95rem] leading-none tracking-tight">
      Vertex
    </span>
  </span>
);
