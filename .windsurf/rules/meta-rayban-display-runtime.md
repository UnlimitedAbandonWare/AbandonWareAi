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

Canonical contract — read before changing any of it: `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (display/output rules + `Runtime policy (FIELD_TESTED)` section). Knobs live in `application-meta-display.yml`; current values: `docs/volatile-knobs.md` (stale copies lose).

Thin safety layer kept here because it is the most-broken invariant set:
- Length: generation target/cap are configurable — **current values: `docs/volatile-knobs.md` + `application-meta-display.yml`; Fold `lensSettings` wins**. On-lens rendering is a **measured shared rendered-line budget** shared by transcript+hint (transcript newest-units-first; all configurable via dev settings) — **4–8** lines stays the hint generation shape (`ConversateCardPrompt`), not a display cap. Transcript style (small/gray) stays fixed regardless of hint presence. **Do not** freeze or silently clamp the settings-driven cue cycle (quiet/cooldown/force-after) or the deterministic rolling trigger — persisted Fold values win.
- Lifecycle: a hint owns its TTL **from first show** (configurable — current defaults in `docs/volatile-knobs.md`; server+client must move together) — never per poll; `hintId` (`Card.requestId`) is the identity, text is fallback only. Transcript hold / hint hold / page interval are **independent integer-second settings** (validated range in `docs/volatile-knobs.md`; transcript `transcriptTtlMs` anchors at first show of each distinct caption, identical re-polls never extend, expired content is not revived). Auto-advance is **on by default** (configurable, 0 = off — the lens has no key input so it is the only page-turn path; **the configured interval is used as-is — never shortened to beat a hint's remaining TTL**) with manual next/prev always available; bump `?v=` in `meta/index.html` when `receiver.js` changes.
- OFFICIAL: 600×600 additive; black = transparent; D-pad/Enter focus; scroll/composer exist — do not absolutize "no scroll" / "no text input". Adaptive routing; no permanent #1 model.
- **[규칙] Meta Ray-Ban Display 로컬 모델 금지 (NO_LOCAL_ON_DISPLAY)** (2026-10-06): 스마트안경(Ray-Ban Meta) 실시간 런타임(Cue 힌트 및 Nova Focus 실시간 질의)은 1~2초 내 초저지연 HUD 표시가 필수이므로, 느린 로컬 모델(Ollama) 사용을 전면 금지한다. 실시간 큐 및 포커스 폴백은 항상 초경량·초고속 고효율 API 모델(Gemini Flash-Lite, Groq, Luna 5.6 등)을 최우선으로 배선한다. 기존 로컬 모델 코드(`ConversateLocalCardGenerator.java`)는 삭제하지 않고 보존하여, 오프라인 테스트/픽스처 및 명시적 로컬 전용 격리 모드(`conversate.cue.local-support-enabled=true` opt-in)에서만 재활용한다.
- Live recycle: compile + ForceRestart/DevWatch; refresh `build/desktop-meta-display/...` copies when live loads from build. Ambiguous: Skill `positive-negative-neutral-judge` (not `@rules:`).
- Surface role: display/interview assets are the local debug surface, not the primary one — primary = main `/chat` chat-ui (`docs/PRIMARY_SURFACE.md`).
