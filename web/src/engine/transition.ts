// Page transitions. The adapter runs leave() while the next page loads.
// The Vertex transition is the analyzer's time base: a screen-coloured panel sweeps in from the
// left with a signal-blue leading edge, then sweeps off to the right.
import { gsap } from "gsap";
import { ease, prefersReducedMotion } from "./env";

export type Transition = {
  leave: (curtain: HTMLElement) => Promise<unknown> | gsap.core.Animation;
  enter: (curtain: HTMLElement) => Promise<unknown> | gsap.core.Animation;
};

const sweep: Transition = {
  leave: (c) =>
    gsap
      .timeline()
      .set(c, { autoAlpha: 1 })
      .fromTo(
        c.querySelector(".curtain__panel"),
        { clipPath: "inset(0% 100% 0% 0%)" },
        { clipPath: "inset(0% 0% 0% 0%)", duration: 0.7, ease: ease.inOut },
      ),
  enter: (c) =>
    gsap
      .timeline()
      .to(c.querySelector(".curtain__panel"), {
        clipPath: "inset(0% 0% 0% 100%)",
        duration: 0.8,
        ease: ease.inOut,
      })
      .set(c, { autoAlpha: 0 }),
};

const reduced: Transition = {
  leave: (c) => gsap.timeline().set(c, { autoAlpha: 1 }).fromTo(c, { opacity: 0 }, { opacity: 1, duration: 0.2 }),
  enter: (c) => gsap.to(c, { autoAlpha: 0, duration: 0.2 }),
};

const pick = () => (prefersReducedMotion() ? reduced : sweep);
const curtain = () => document.getElementById("curtain") as HTMLElement;

export const playLeave = () => Promise.resolve(pick().leave(curtain()));
export const playEnter = () => Promise.resolve(pick().enter(curtain()));
