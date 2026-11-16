// Declarative motion through data attributes:
//   data-split="lines|words"   masked text reveal (SplitText), on scroll or with the page intro
//   data-reveal="up|fade|draw" block reveal ("draw" wipes in left to right, like a trace)
//   data-intro                 play with the page intro instead of on scroll
//   data-delay="0.2"           extra delay (seconds)
// Every effect is skipped under reduced motion; CSS keeps content visible without JS.
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { ease, prefersReducedMotion } from "./env";

gsap.registerPlugin(ScrollTrigger, SplitText);

const durations = { lines: 1.1, words: 1 } as const;
const staggers = { lines: 0.08, words: 0.03 } as const;

function splitTween(el: HTMLElement, isIntro: boolean) {
  const type = (el.dataset.split || "lines") as keyof typeof durations;
  const delay = parseFloat(el.dataset.delay || "0");
  const s = { tween: undefined as gsap.core.Tween | undefined, done: false, requested: false };
  SplitText.create(el, {
    type: type === "lines" ? "lines" : `lines,${type}`,
    mask: "lines",
    autoSplit: true,
    linesClass: "split-line",
    onSplit(self) {
      gsap.set(el, { visibility: "visible" });
      if (s.done) return;
      const targets = type === "lines" ? self.lines : self.words;
      s.tween = gsap.from(targets, {
        yPercent: 110,
        duration: durations[type],
        stagger: staggers[type],
        ease: ease.out,
        delay,
        paused: isIntro && !s.requested,
        onComplete: () => {
          s.done = true;
        },
        scrollTrigger: isIntro ? undefined : { trigger: el, start: "top 88%", once: true },
      });
      return s.tween;
    },
  });
  return () => {
    s.requested = true;
    s.tween?.play();
  };
}

function blockTween(el: HTMLElement, paused: boolean) {
  const kind = el.dataset.reveal || "up";
  const delay = parseFloat(el.dataset.delay || "0");
  const from: gsap.TweenVars =
    kind === "fade"
      ? { autoAlpha: 0 }
      : kind === "draw"
        ? { clipPath: "inset(0% 100% 0% 0%)", autoAlpha: 1 }
        : { y: 32, autoAlpha: 0 };
  const to: gsap.TweenVars = kind === "draw" ? { clipPath: "inset(0% 0% 0% 0%)" } : {};
  gsap.set(el, { visibility: "visible" });
  return gsap.fromTo(el, from, {
    ...to,
    y: 0,
    autoAlpha: 1,
    duration: kind === "fade" ? 0.8 : 1.1,
    ease: ease.out,
    delay,
    paused,
    scrollTrigger: paused ? undefined : { trigger: el, start: "top 90%", once: true },
  });
}

/** Wire every reveal inside `root`. Returns the intro timeline (paused) for the page entrance. */
export function initReveals(root: ParentNode = document) {
  const intro = gsap.timeline({ paused: true });
  if (prefersReducedMotion()) return intro;

  root.querySelectorAll<HTMLElement>("[data-split]").forEach((el) => {
    const isIntro = el.hasAttribute("data-intro");
    const play = splitTween(el, isIntro);
    if (isIntro) intro.add(play, 0);
  });
  root.querySelectorAll<HTMLElement>("[data-reveal]").forEach((el) => {
    const isIntro = el.hasAttribute("data-intro");
    const t = blockTween(el, isIntro);
    if (isIntro) intro.add(() => void t.play(), 0);
  });
  return intro;
}
