# Vertex web: design system

## Brief

- **Subject:** Vertex, a distributed social platform: seven Spring Boot services (identity, profile,
  graph, feed, notify, recommend, gateway), Postgres, Redis and Kafka, running live on one free VM.
- **Audience:** recruiters and engineers deciding in a minute whether the builder understands
  distributed systems; curious strangers.
- **Job:** make the architecture legible in 30 seconds, then let the visitor operate the real
  system and see what each action did inside it.
- **Constraints:** every number on screen is real (measured, counted or returned by the API).
  No invented users, metrics or features. Must not resemble the owner's portfolio (warm paper,
  Host Grotesk, Mandarin orange, giant name with a light sweep, italic margin notes).

## Concepts

| | Idea | Anchor | Signature | O / F / Fe / P |
|---|---|---|---|---|
| A. Material | **The site is a logic analyzer.** Each service is a channel lane; every action is a signal you watch propagate across the lanes, with real timestamps | Vertex's real event path: gateway, Graph's outbox, Kafka partition and offset, Notify's dedupe ledger, the SSE push | the live trace: lanes light up in order as the real timings come back | 4 / 5 / 4 / 1 |
| B. Name | Vertex as a node: the page is a graph and you become a vertex that gains edges | the product name | your vertex appears and edges draw as you follow | 3 / 4 / 4 / 4 |
| C. Formal | The commit log: the page is an append-only log; sections are records at offsets | Kafka's log | your actions append records with real offsets | 4 / 4 / 4 / 2 |

**Pick A.** Highest fit, lowest portability: the lanes, the order and the numbers can only come from
this system. From C: the 404 is "no record at this offset". From B: nothing (graph-as-hero is the
cliché the old site used).

## Concept card

```
Idea:        A social network taken apart on an instrument bench: every action is a signal you can trace.
Metaphor:    a logic analyzer / oscilloscope: channels, a time axis, a trigger, a probe, a capture.
Signature:   The trace. Lanes (Browser, Gateway, Graph, Outbox, Kafka, Notify, Partner) and a pulse that
             propagates lane to lane. On home it is a scroll-scrubbed schematic with an idle sweep;
             in the demo it is a live capture drawn from the timings the API returns.
Emotion:     precise, calm, a little thrilling: "I can see inside it."
Motion verb: propagate. Things travel along a line and latch: a signal crosses, then the state sets.
Screenshot:  the demo with a finished capture: eight lanes, the pulse stopped on Partner, real
             millisecond deltas and "partition 0, offset 412" under the Kafka lane.
```

## System

**Palette** (light, one committed theme; the instrument panels are the dark surfaces)
| Role | Hex | Source |
|---|---|---|
| ground | `#E8ECEF` | bench grey of a lab instrument casing |
| ink | `#0B0F14` | front-panel black |
| muted | `#4D5864` | silk-screened panel legends (7.0:1 on ground) |
| line | `#C3CBD3` | graticule |
| screen | `#0E1418` | the analyzer's display |
| signal | `#1D4BFF` | the probe colour; the event. Used for the pulse, focus and primary action only |
| phosphor | `#9FB8FF` | signal on the dark screen (AA on screen) |
| fault | `#D1342F` | dead letters and errors only |

Rejected defaults: dark + acid green (AI tell, and too close to "hacker"), cream + serif (the
owner's portfolio). The light casing with dark screens comes from real bench gear.

**Type**
- Display: **Archivo** expanded (wdth 112 to 125), weight 800, tracking -0.035em, leading 0.9.
  Wide letterforms read as silk-screened panel lettering.
- Text and UI: **Archivo** normal width 400 / 600. One family, two personalities.
- Data: **Martian Mono** 400 / 500 for timings, offsets, ids, lane labels: tabular by design.
- Scale: `--t-mega clamp(3.4rem, 1rem + 10vw, 11rem)` down to `--t-xs 0.75rem`.

**Grid & rhythm:** 12 columns, fluid gutter. Three hanging lines: col 1, col 5, col 9. Section
rhythm alternates tight (hero meta), airy (statement), full viewport (trace).

**Shape:** radius 2px on controls, 0 on panels. Hairlines 1px `line`.

**Motion language:** ease `expo.out` for entrances, `expo.inOut` for the curtain, `power3.out` for
UI. Durations: 0.25 s UI, 0.6 s blocks, 1.1 s display. Propagate in practice:
- primary buttons: a signal-blue fill travels left to right under the label, the arrow latches;
- nav and text links: an underline draws left to right (a trace being drawn);
- page transition: a screen-coloured panel sweeps across like the analyzer's time base,
  left to right, then off to the right;
- reveals: lines rise into place; lanes draw left to right.

**Idle state:** the home trace keeps a slow sweep running (one gsap ticker, paused off screen,
off under reduced motion). The demo is still at rest by decision: it is a tool, and its live
SSE stream is the only thing allowed to move it.

## Composition plan

```
HOME
[header]  VERTEX ............................ How it works  Services  Run it  GitHub  [Open the live demo]
[hero: statement + instrument]
  h1 (cols 1-8, t-xl, 3 lines)                       | status panel (cols 9-12, screen)
  lede (cols 1-6)                                    |  gateway  UP   4 ms
  [Open the live demo]  Read the code                |  identity UP   3 ms ...
[trace: pinned full viewport, scrubbed]
  lanes across 12 cols, step captions in col 1-4, pulse moves lane to lane
[services: index list]
  row: name (t-l) | what it owns | store | lane colour tick
[break it: asymmetric pair] text cols 1-5, two capture snippets cols 7-12
[facts: data row] only true counts
[run it: statement + code]
[footer: finale] VERTEX at mega, hosting line, links

DEMO (console)
[status bar: backend state | you | partner | rate budget]
[col 1-3 steps]  [col 4-9 trace screen]  [col 10-12 partner live notifications]
[below: your feed | notifications | people to follow]
404: "No record at this offset."
```

Genericness check: a generic model would land on a dark dashboard with a glowing node graph and
gradient cards. Changed: light casing with dark screens, lanes instead of node graph, one signal
colour, the wide silk-screen display face, and real captures instead of decorative charts.
