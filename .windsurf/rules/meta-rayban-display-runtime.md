---
trigger: glob
globs:
  - "**/assets/display/**"
  - "**/meta-display/**"
  - "**/*Conversate*"
  - "**/application-meta-display.yml"
  - "**/META_DISPLAY*"
  - "**/lms/assist/**"
---

# meta-rayban-display-runtime

Split **OFFICIAL** (Meta docs) vs **FIELD_TESTED** (this repo wear-test). Do not swap them.

Canonical contract — read before changing any of it: `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (display/output rules + `Runtime policy (FIELD_TESTED)` section). Knobs live in `application-meta-display.yml`.

Thin safety layer kept here because it is the most-broken invariant set:
- Length: generation default ~**480–540** Hangul, hard cap default **612**; on-lens rendering is a **measured shared rendered-line budget** (default ~13) shared by transcript+hint (transcript newest-units-first, default max ~4 lines beside a hint; hint lines/page default ~11; all configurable via dev settings) — **4–8** lines stays the hint generation shape, not a display cap. Transcript style (small/gray) stays fixed regardless of hint presence. **Do not** arbitrarily change force-hint **180 s/Δ50 chars**, the ~3-minute refresh, or the deterministic rolling trigger.
- Lifecycle: a hint owns its TTL **from first show** (default **20 s**, configurable; server+client must move together) — never per poll; `hintId` (`Card.requestId`) is the identity, text is fallback only. Transcript hold / hint hold / page interval are **independent 1–100 s integer settings** (defaults 20 s / 20 s / 5 s; transcript `transcriptTtlMs` anchors at first show of each distinct caption, identical re-polls never extend, expired content is not revived). Auto-advance is **on by default (~5 s**, configurable, 0 = off — the lens has no key input so it is the only page-turn path; **the configured interval is used as-is — never shortened to beat a hint's remaining TTL**) with manual next/prev always available; bump `?v=` in `meta/index.html` when `receiver.js` changes.
- OFFICIAL: 600×600 additive; black = transparent; D-pad/Enter focus; scroll/composer exist — do not absolutize "no scroll" / "no text input". Adaptive routing; no permanent #1 model.
- Live recycle: compile + ForceRestart/DevWatch; refresh `build/desktop-meta-display/...` copies when live loads from build. Ambiguous: Skill `positive-negative-neutral-judge` (not `@rules:`).
