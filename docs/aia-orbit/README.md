# AIA Orbit: one slide and one-minute film

| File | What it is |
|---|---|
| `EXCO-One-Slide-AIA-Orbit.pdf` / `.png` / `.pptx` | One-slide EXCO view, 16:9 (the `.pptx` holds the slide as a picture) |
| `AIA-Orbit-1min-talking.mp4` | 64 s film, 1920×1080, 25 fps. Orbit talks: mouth and moustache move with the voice, blinks, bob and glow, live captions, animation triggered line by line |
| `source/` | Scene pages, narration script, timeline builder and frame renderer to rebuild both |

Orbit's artwork is from `cobalt-chat-ui/public/aia-orbit-hero.png` and `aia-orbit-icon.png`. Voice: Azure OpenAI gpt-4o-mini-tts ("ash"), sped up 20%.

## Narration

| Start | Orbit says |
|---|---|
| 0:00 | Hi, I'm Orbit, AIA's knowledge copilot. |
| 0:03 | The rules that run our business live in old COBOL programs. Only a few specialists can read them. |
| 0:10 | A complicated question takes them seven to fifteen days to answer by hand. |
| 0:14 | With me, you ask in plain English, or put a real scenario to me, and I trace it through the programs in minutes. |
| 0:22 | You get the answer in business language, with the source shown. |
| 0:25 | I map what a change would touch, draft the requirement and tests, and my coding agent proposes the change, compiler-checked, for a person to review. |
| 0:34 | For business owners: answers on demand, impact before committing, better requirements. |
| 0:40 | For AIA: knowledge no longer locked in a few heads, faster change with less risk, a stronger audit trail. |
| 0:47 | And the timing is right: MAS is proposing tighter change controls for insurers. |
| 0:53 | A hundred routine questions a month is about two hundred hours back. An illustration, to prove with AIA's own numbers. |
| 1:00 | Let's prove it together. Just ask Orbit. |

## Figures on the slide
- 7 to 15 days: the COBOL team's estimate of elapsed time for a complicated question by hand. "Minutes" is to be confirmed in a pilot.
- 70%+ of business transactions on COBOL: an IBM claim as reported by NextBigFuture (2020), a vendor figure.
- About 15% projected 2026 IT-spend growth for Asia-Pacific life insurers: Celent survey of 219 insurers.
- MAS: a proposal to tighten change-management controls for insurers (consultation closed 31 Jul 2026, 12-month transition once final), per law-firm summaries. Check the MAS paper before quoting.
- About 200 hours a month: an illustration (100 routine questions x 2 hours), not a forecast.

## Version 2: cosmic concept (`AIA-Orbit-1min-talking-v2.mp4`)

Same narration and 64 s length as v1, with a new look. Instead of animating the mascot's face, Orbit speaks from a porthole ringed by a live voice equaliser, with eyes that glow with the voice, a gentle lean and float, and the lightbulb lighting at insight moments. The story flies through space, with a warp between scenes:

- a galaxy of real program names (PLCYINQ, CLMPRC, PREMINQ, BNFUPD, POLRNWL) where only a few "specialist" beacons light up the code
- a 15-day clock with a planet crawling one day at a time, then a comet that traces a scenario through the programs in one sweep ("minutes")
- an answer card with a business/technical toggle and a decision table (illustrative example)
- impact rings expanding from a change, with Requirement, Test scenarios and Code change orbiting, then "Compiler-checked" and "A person reviews"
- glass cards for business owners and for AIA (a lock that opens), the MAS shield, an hours gauge filling to about 200, and Orbit's ring returning for "Just ask Orbit."

`source-v2/` holds the scene page (`orbit2.html`), renderer and narration audio. v1 and its source are unchanged.
