<!-- moved-from: AGENTS.md L131-L135 sha256=e116aa747e26530503e37a640a58e415215c1a3bf681f0d543364f2639e37330 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
## Meta Ray-Ban Display runtime (short)
- Display/output contract + runtime policy (settings-driven hold/paging/cue-cycle/budgets): SSOT `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (`$demo1-meta-display-simple-caption`) + always-on layer `.windsurf/rules/meta-rayban-display-runtime.md`. **Live Fold `#lens-display` prefs win over `application-meta-display.yml` factory defaults** — settings-driven knobs persisted via `lensSettings`; never silently clamp; last-page interval shrink forbidden. Numbers: `docs/volatile-knobs.md` (re-read; stale skill numbers lose).
- Live Java/YAML change -> compile then ForceRestart/DevWatch (`$demo1-dev-reload`, DEMO1-SPRING-VIBE-RELOAD); never claim live success from a stale bootRun. Ambiguous Display/RAG/LLM tradeoffs: `$demo1-triad-deliberation` / `$positive-negative-neutral-judge`.
- **[규칙] Meta Ray-Ban Display 로컬 모델 금지 (NO_LOCAL_ON_DISPLAY)** (2026-10-06):
  - 스마트안경(Ray-Ban Meta) 실시간 런타임(Cue 힌트 및 Nova Focus 실시간 질의)은 1~2초 내 초저지연 HUD 표시가 필수이므로, 느린 로컬 모델(Ollama) 사용을 전면 금지한다.
  - 실시간 큐 및 포커스 폴백은 항상 초경량·초고속 고효율 API 모델(Gemini Flash-Lite, Groq, Luna 5.6 등)을 최우선으로 배선한다.
  - 기존 로컬 모델 코드(`ConversateLocalCardGenerator.java`)는 삭제하지 않고 보존하여, 오프라인 테스트/픽스처 및 명시적 로컬 전용 격리 모드(`conversate.cue.local-support-enabled=true` opt-in)에서만 재활용한다.
<!-- END DEMO1-META-RAYBAN-DISPLAY-RUNTIME -->
