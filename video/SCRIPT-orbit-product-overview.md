# AIA Orbit: 2-minute product overview (video, PowerPoint, PDF)

| File | What it is |
|---|---|
| `AIA-Orbit-Product-Overview-2min.mp4` | 2:01, 1920×1080, 25 fps, narrated by Orbit, with word-by-word captions and a progress bar |
| `AIA-Orbit-Product-Overview.pptx` | 13 editable slides (native text and shapes); the narration is in the speaker notes |
| `AIA-Orbit-Product-Overview.pdf` | The same 13 slides as a PDF |
| `source/` | Everything needed to rebuild all three from one file, `deck.json` |

Same look as the AIA Voice Insights overview and the Orbit one-slide: AIA red, white cards, a crimson "business impact" panel on each content slide. Artwork is from `cobalt-chat-ui/public` (`aia-orbit-light-bg.png`, `aia-orbit-hero.png`, `aia-orbit-icon.png`). Voice: Azure OpenAI gpt-4o-mini-tts ("ash", the same voice as the earlier Orbit films), sped up about 12%.

## Storyline: what it does, and why it matters

Every content slide pairs **what it does** (left) with **business impact** (right).

| Start | Slide | Orbit says | Business impact |
|---|---|---|---|
| 0:00 | Meet Orbit: Ask the system itself. | Hi, I'm Orbit, AIA's knowledge copilot. Here's what I do, in two minutes. | (introduction) |
| 0:07 | The Problem: The rules are locked in old code | The rules that run our business live in old COBOL programs. Only a few specialists can read them, so a hard question takes seven to fifteen days, and the knowledge walks out when they leave. | Slow, risky, dependent |
| 0:19 | Ask: Ask in plain English | Ask me in plain English. Choose a business view or a technical view, watch the answer stream in live, and I'll suggest what to ask next. | Answers on demand |
| 0:29 | Rules Made Visible: The logic becomes readable | I make the rules visible: business rules, decision tables, process flows and a data dictionary, all built from the code itself. | Rules the business can read |
| 0:39 | Scenario Walkthrough: Try a what-if on real logic | Put a real scenario to me. I trace it step by step through the programs and show the outcome, in minutes, not days. | Test a situation first |
| 0:48 | Traceable And Safe: Answers you can trace | Every answer cites the programs behind it, and you can open the code. Guards block prompt injection and personal-data requests. | Verifiable, not black-box |
| 0:56 | Impact Analysis: See what a change will touch | Before anything changes, I show what it would touch: directly, also, and downstream, in an impact map and a report. | Impact before you commit |
| 1:06 | Requirements And Tests: A first draft that matches reality | Then I draft the functional requirement and the test scenarios, from how the system really behaves, ready to export. | Better requirements, faster |
| 1:13 | Coding Agent: From question to a change proposal | My coding agent goes further. It finds the place, writes the change, checks it with a real COBOL compiler, and a person reviews the difference. | A head start on change |
| 1:23 | Built To Run: Run it like a service | It's built to run as a service: sign-in, history, admin insights on quality and usage, and security monitoring. | Managed, measurable |
| 1:31 | What It Means For Aia: Value for every group | For business owners, answers on demand. For specialists, fewer repeat questions. For AIA, faster change, lower risk and a stronger audit trail. | Value for every group |
| 1:42 | Why Now: Systems stay. Specialists are scarce. | The timing is right. Seventy percent of business transactions are said to run on COBOL, and MAS is proposing tighter change controls. A hundred routine questions a month is about two hundred hours back, an illustration. | Why now: scarce specialists, tighter MAS controls |
| 1:56 | Let'S Prove It Together: Just ask Orbit. | Let's prove it together. Just ask Orbit. | A pilot on AIA's own questions |

## What each slide is based on in the product

| Slide | Where it is in the product |
|---|---|
| Ask in plain English | Chat with streaming ("Live") or complete answers, Business / Tech view, follow-up suggestions |
| Rules made visible | Business rules, decision table, business flow, data dictionary, key relationships |
| Scenario walkthrough | "Scenario walkthrough" section (steps and outcome), shown for a concrete what-if question in Business view |
| Traceable and safe | Source citations and the code viewer; like, regenerate and improve; the prompt-injection pre-filter, personal-data checks and rate limiter |
| Impact analysis | "Impact analysis" and the change impact report; shown in Tech view for a change request |
| Requirements and tests | Functional requirement and test scenario reports, with export |
| Coding agent | `propose-change`: locate, generate, splice; checked with a real GnuCOBOL compiler; side-by-side diff for a person to review |
| Built to run | Sign-in, history and branches, admin insights (usage, quality, health) and security statistics |

## Figures and caveats

- **7 to 15 days** is the COBOL team's estimate of elapsed time for a complicated question, not effort. **"Minutes"** is not yet measured; the video says so on screen and the pilot should confirm it.
- **70%+ of business transactions on COBOL** is an IBM claim as reported by NextBigFuture (2020), a vendor figure.
- **≈15% projected 2026 IT-spend growth** for Asia-Pacific life insurers is from the Celent survey of 219 insurers.
- **MAS** is a proposal to tighten change-management controls for insurers (consultation closed 31 Jul 2026, 12 months to comply once final), per law-firm summaries. Check the MAS paper before quoting.
- **≈200 hours a month** is an illustration (100 routine questions × 2 hours), not a forecast. Establish AIA's own baseline first.
- The coding agent's output is a **proposal for a person to review**. Nothing is applied automatically.

## Rebuild

From `source/`, with the Azure settings in `../../voice-insights-azureapi/.env` (never print the key):

```
python tts3.py                      # narration mp3s into a/ (skips existing)
python prep3.py <ffmpeg> 121        # master.wav + timeline.js
python render3.py video 120.9       # frames (needs Chrome and the python websockets package)
<ffmpeg> -framerate 25 -i frames/f%05d.jpg -i master.wav -c:v libx264 -pix_fmt yuv420p -crf 18 -c:a aac -b:a 160k -shortest ../AIA-Orbit-Product-Overview-2min.mp4
python render3.py pdf ../AIA-Orbit-Product-Overview.pdf
python assets3.py && NODE_PATH=<node_modules with pptxgenjs> node build-pptx.js
```

To change the story, edit `deck.json` (narration in `beats`, slide content in `slides`), delete the matching `a/bNN.mp3`, and rerun.
