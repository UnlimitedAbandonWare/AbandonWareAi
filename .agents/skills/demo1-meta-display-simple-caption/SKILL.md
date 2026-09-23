---
name: demo1-meta-display-simple-caption
description: Use when Meta Display work decides what appears on the lens or caption
---

# Meta Display · 대화·힌트만 단순 표시

안경(Meta Ray-Ban Display)에 그릴 내용은 **대화 전사/요약**과 **짧은 힌트**만이다. Fold6·웹은 수음·제어·설정을 맡고, 렌즈는 읽기 전용 캡션 표면으로 둔다.

## 기준 구현 (SSOT 행동)

1. **정상 표시가 확인된 단순 페이지를 기준**으로 삼는다. 예: `display-test-01` 계열(어두운 배경, 밝은 테두리, 큰 글씨 1~2덩어리, 스크롤·장식·진단 오버레이 없음).
2. 기존 복잡한 Display 페이지를 여러 방식(DAT / 공식 Web App / 커스텀 릴레이)으로 섞어 추적하지 않는다. 경로가 커스텀 companion·정적 HTML이면 그 경로만 최소 수정한다.
3. 렌즈 본문 허용 필드만 사용한다:
   - `conversation` — 최신 전사 또는 짧은 대화 덩어리
   - `hint` — 짧은 힌트 카드(기본 생성 계약: 목표 약 480–540자, hard cap 612자, 렌즈에는 측정된 페이지(기본 약 8줄/페이지)로 표시; 목표·상한·페이지 줄 수는 개발자 설정으로 조절 가능하며 기본값이 아래의 FIELD_TESTED 수치)
   - `hintId` — `Card.requestId`; 힌트 세대 식별자. 같은 본문의 재생성(새 id)과 같은 힌트의 재수신(같은 id)을 구분한다
4. 렌즈에 넣지 않는다: 디버그 배너, ACK/CONNECTED/owner 원문, QR, 설정 패널, 버튼 다수, 긴 RAG 덤프, 다중 카드, 자동 스크롤 피드.
5. 한 화면에 **한 덩어리**만 보여 준다. 길면 다음 페이지/다음 힌트로 넘긴다(힌트 생성 계약 기본 최대 612자; 표시 페이징이지 임의 truncate가 아니다). 600×600·무스크롤·고대비를 유지한다. 각 힌트는 **최초 표시 기준으로만** 만료된다(기본 수명 20초; Fold 안경 표시 설정에서 전사 유지·힌트 유지·페이지 간격을 서로 독립적으로 1–100초 정수 입력 — 서버·클라이언트가 같은 값을 쓰게 한다) — 동일 힌트 반복 수신·전사 갱신·페이지 전환은 수명을 연장하지 않고, 만료/교체된 힌트는 다시 수신돼도 재등장하지 않는다. 한 페이지 기본 줄 수(기본 11줄)를 넘는 힌트는 측정된 줄 높이로 페이지 분할해 전체를 보여 준다(자동 넘김 기본 5초 — 안경에는 키 입력 경로가 없어 유일한 페이지 전환 수단이다 — + 수동 이동 병행, 간격은 1–100초 설정 가능하고 0=끄기; **수명보다 간격이 길면 다음 페이지 전에 힌트가 사라져도 간격을 당기거나 수명을 늘리지 않는다**; 모든 페이지가 같은 수명을 공유하고, 수동 이동 경로는 항상 남겨 둔다). 전사와 힌트가 함께 보일 때 두 영역은 합산 줄 예산(기본 약 13개 실측 줄, 설정 가능)을 공유하며, 전사는 최신 단위(마지막 문장·질문 포함)를 우선 유지하고 오래된 단위부터 줄인다. 이번 작업이 표시시간만 바꿀 때는 글자 크기·줄 수·힌트 본문을 줄여 맞추지 않는다.
6. ACK 증가·서버 CONNECTED만으로 “렌즈에 보였다”고 단정하지 않는다. 단순 텍스트가 안경에서 보인 뒤에만 기능을 한 단계씩 붙인다.
7. 이 스킬은 표시 규칙을 정의한다. 장시간 수음·전사 파이프라인 구현 세부는 별도 작업/스킬에 두고, 여기서는 **안경 출력 계약**만 강제한다.

## Codex가 할 일 / 하지 말 일

| 할 일 | 하지 말 일 |
|---|---|
| 정적/기존 Display HTML을 test-01 수준으로 단순화 | 새 프레임워크·웹뷰 스택 도입 |
| Fold6 입력 → 수음 → 전사 → 힌트 → 렌즈 텍스트 연결만 | 렌즈에 컨트롤 UI 이식 |
| 안 보이면 콘텐츠를 더 줄여 재검증(표시시간-only 작업은 제외: 레이아웃 유지) | 진단 문구를 본문 위에 상시 고정 |
| Exa/웹서치로 Meta Web App 제약 확인 시 표시 제약만 반영 | Web App 규격과 커스텀 릴레이 API를 동일시 |

## 최소 수용 기준

- 안경에서 밝은 테두리 안 텍스트가 읽힌다.
- 보이는 문자열은 대화/힌트 계열뿐이다.
- Fold6(또는 웹 컨트롤) 없이 렌즈만으로 설정을 바꾸려 하지 않는다.

## 롤백

이 스킬 폴더와 `AGENTS.md`의 simple-caption 포인터만 제거한다. `display-test-01` 및 기존 Display 역할 스킬은 보존한다.

## Soft-evidence mode (hints > silence)

When RAG citations/evidence are empty, the chat/display pipeline should still emit a non-empty `conversation`/`hint`. Local/dev softens gates via:

- `gate.evidence.allow-empty=true` — EvidenceGate soft-allows totalEvidence==0
- `gate.citation.log-only=true`, `guard.citation.require_official=false`, low `min` counts
- `gate.finalSigmoid.mode=log-only`, `jammini.guard.mode=soft`
- AnswerExpander must not instruct `[NO_EVIDENCE]`; keep the original draft if a marker still appears

Do **not** show only "근거 없다" on the lens. Prefer a short conversational hint. Re-tighten for production by flipping those properties off.

## Runtime policy (FIELD_TESTED)

Canonical runtime details moved here from `AGENTS.md`. Numbers below are **factory defaults**. Live Fold `#lens-display` prefs win; persist via `lensSettings` / `saveSetting('lensDisplay')`.

- **OFFICIAL (Meta-verified):** 600×600 additive display; black background is a transparent canvas. D-pad/Enter focus; interactive controls follow `.focusable`. Scrolling containers, Neural Band, on-glasses text composer, and opt-in drag exist — do **not** absolutize "no scroll" / "no text input".
- **Length policy (configurable; FIELD_TESTED defaults):** generation target default ~**480–540** Hangul chars; hard cap default **612** only when context needs it. **4–8** complete lines stays the default hint-generation shape (`ConversateCardPrompt`), not a rendering cap. These numbers are **adjustable defaults, not immutable constants**: the user may raise/lower the generation target·cap via dev settings or `application-meta-display.yml` knobs when measured evidence (under-generation vs `finishReason=length`) supports it — never blanket-double tokens/calls/hint counts, and keep other cue routes' default behavior unchanged. On-lens display uses a **measured shared line budget** (transcript+hint), default ~13 rendered lines but **configurable**: `#transcript` keeps newest complete units first, default transcript max ~4 lines (configurable) beside a hint; `#hint` renders a configurable number of lines per page (default ~8). Font default **26–30px**; the user may adjust transcript/hint font size via dev settings — adjust layout only by measured fit (`scrollHeight`/`clientHeight`), never by silently overriding a user-chosen size. Transcript size/color/weight must **not** switch when a hint appears or disappears: keep one configured style from first show, and avoid layout jumps on hint show/hide. Goal = a complete hint fast/reliably — not shortest possible.
- **Lens lifecycle:** a hint owns its TTL **from first show** (default **20 s**). Fold 안경 표시 설정 exposes three **independent** integer-second fields in range **1–100** (step 1, not presets): transcript hold, hint hold, page interval. Defaults stay 20 s / 20 s / 5 s until the user changes them. Server `LensDisplayPrefs` (`transcriptTtlMs` sibling + existing `hintTtlMs` + `autoPageMs`) and client `receiver.js` `state.display` must use the **same applied values** — never a leftover `HINT_TTL_MS` constant or a hidden 20 s / 2 s floor. Identical re-polls, transcript updates and page turns never extend the hint; expired/superseded hints stay hidden (judge expiry by expiry time, not by timer callback firing alone). Changing settings must not restart an already-running hint clock or revive an expired hint. `/api/assist/display/lens/text` = `{conversation,hint,hintId,hintExpiresAt,conversationExpiresAt,display}` (`hintId` = `Card.requestId`; text is fallback identity only). `#hint` caps a configurable number of lines per page (default ~11); longer hints are paged client-side (auto-advance **on by default, 5 s** — the glasses have no key input, so it is the only page-turn path — interval 1–100 s, 0 = off). **Do not shrink/stretch the page interval so the last page still appears before expiry** — if hint TTL is 5 s and page interval is 10 s, page 2 may never show; that is intended. Manual next/prev must always work, `힌트 n/m` in `#status`. `#transcript` fills the remaining share of the shared line budget (default ~13) and drops oldest complete units first — never the latest sentence. A settings round trip (UI → server validation → `lens/text` / relay `display` → lens render) must apply without a rebuild/restart **after this code is live**; `localStorage` alone never proves on-glasses application. Out-of-range input is rejected at the input/`invalid_lens_settings` boundary — never silently clamped to another value. Show both the typed seconds and the applied seconds in the Fold menu. Bump `?v=` in `meta/index.html` when `receiver.js` changes.
- **Force-hint / rolling trigger (generation cycle — settings-driven, not frozen):** YAML factory defaults are force-hint **180 s** / **Δ≥50** chars, rolling quiet **2.5 s** (`trigger-quiet-ms`), cooldown **10 s**, card hold `hintHoldUntil` from hint TTL / `display-ttl-ms` **20 s**. The **time** knobs (quiet, cooldown, force-after, display/hint hold, page interval) follow persisted Fold 안경 표시 설정 — after save and after refresh/reconnect the running cycle must change. Do not keep a leftover YAML floor when the user saved a different value (including a saved 180 s force-hint). Char thresholds (Δ50, 120) stay count gates, not seconds. Rolling path stays deterministic: final utterance with normalized transcript delta ≥ **120** chars fires `utterance_end`; accumulated ≥120 plus quiet ≥ the **set** `trigger-quiet-ms` fires `transcript_delta`; both gated by the **set** `cooldown-ms` and `hintHoldUntil`. `sample-probability` was removed — do not reintroduce.
- **Tokens vs characters:** `max-output-tokens` is an API output budget, not characters. For 480–612 Hangul hints, review/raise per-model output budget so replies are not cut; change only with cut evidence (`finishReason=length`) or measured under-generation. A mid-layer Card/DTO/render truncating early is a **hidden cap** — fix that path.
- **Routing / JSON / logs:** adaptive routing from latency EWMA, success rate, timeout/validation failures, and cost — no hardcoded permanent #1 model. JSON: provider native structured output → strict validation → limited recovery → fallback; lenient parsers only for real format drift. Log on existing structured logs; no parallel log system. Do not mark a model stale from its name alone — only official deprecation/retirement, real probe failure, or credential issues.
- **Ops:** Fold6/web = mic/ASR/controls; lens = quiet text. Keep cueHint/Conversate + soft-evidence; no parallel hint stack. Live Java/YAML: compile then ForceRestart/DevWatch; never claim live success from a stale bootRun. Refresh `build/desktop-meta-display*/...` static copies via `processResources` when live loads from build.

## Authorized source patch: Fold display + generation timing

When the user wants Fold 개발자 메뉴에서 전사 유지·힌트 유지·페이지 간격 **또는 힌트 생성 주기**(quiet / cooldown / force-after / display hold)를 직접 입력하게 하면, **this is an authorized application-source patch**, not a report, not E0 resume, not a new settings page. Layout/font/output volume stay as they are. `$demo1-meta-display-resume` `sourceMutationAllowed=false` does **not** apply. Skip triad / three-way preflight. Still use work-ledger + `source_edit_session` lease/preimage.

### Menu (reuse, do not duplicate)

Fold `main/resources/static/assets/display/index.html` → **연결·입력 설정** → **안경 표시 설정** (`#lens-display`). Reuse `ld-cap-ttl` / `ld-hint-ttl` / `ld-auto-page` + `app.js` `lensSettings` / `saveSetting('lensDisplay')`. Add sibling integer-second inputs for cue quiet / cooldown / force-after if missing. Do **not** reuse `#segment-preset` (ASR restart). Keep 자동 넘김 `0=끔`. Inputs: `type=number` `step=1`; hold/page 1–100 s; force-after range must still accept the factory **180 s**. Show typed seconds and server-echoed applied seconds in `#ld-status`. Persist so refresh/reconnect restores the last saved cycle.

### Classify leftover time floors (connect, do not freeze)

| Code | What it limits | This patch |
|---|---|---|
| `LensDisplayPrefs` auto-page / TTL mins and `receiver.js` clamps | page-turn and hold floors | **connect** to saved prefs (`0` still disables auto-page) |
| `receiver.js` hidden 20 s caption cap | transcript hold | **connect** to `transcriptTtlMs` |
| `schedulePages` last-page shrink / 1.2 s floor | page interval | **remove shrink**; use the set interval as-is |
| `ConversateSessionService` YAML `triggerQuietMs` / `forceAfterMs` / `cue.cooldown-ms` | generation cycle | **connect** to persisted Fold prefs — live rolling/force must change after save |
| `hintHoldUntil` / `display-ttl-ms` | card hold while a hint is showing | **follow** the set hint hold, not a leftover 20 s |
| Char gates Δ50 / 120 | generation **counts**, not seconds | leave unless the user asked to tune those |

Entering `1` for a time field must yield 1 s, not a hidden 2 s. A saved 180 s force-hint must not be silently clamped down.

### Independent clocks

`transcriptTtlMs`, `hintTtlMs`, `autoPageMs`, and the cue time prefs are independent. Do not max/min them together. Hint lifetime starts at first show; re-receive / page turn / settings edit must not reset a running clock or revive an expired hint. Rolling-caption start (`ROLLING_EXPIRY`) stays as-is — only the hold **value** becomes the setting. Overflow still uses existing page split; do not shrink fonts or truncate hint text to avoid paging.

### Wire-through files (minimal)

`LensDisplayPrefs.java` (prefs + `describe`/`defaults`/`Patch`), `ConversateSessionService.java` (caption vs hint TTL split; quiet/cooldown/force read prefs), `assets/display/index.html` + `app.js`, `assets/display/meta/receiver.js`, tests. Bump `receiver.js?v=` in `meta/index.html`. First Java deploy: wear `Close-Meta-Display.bat` then `Start-Meta-Display.bat`. After that, changing the numbers must apply without rebuild/restart. Log setting apply / page turn / expiry / cue trigger with applied ms + timestamp only — no transcript, no secrets.

### Verify

1 s and max values, mid values such as 7/23/46, transcript vs hint vs page vs quiet/cooldown/force set independently, `1/2 → 2/2` follows page interval, TTL 5 s + page 10 s with **no** auto-correction, refresh/reconnect keeps last saved prefs **and** the generation cycle.

## Focus display (Nova Focus) — separate from hints

Nova Focus answers travel as a separate additive `focus` projection, **never** inside `hint` (its char cap, TTL, and hints-OFF would hide them). Their display contract is sequential: grapheme-by-grapheme reveal, oldest completed line rolls off when full, last char → tail hold → fade — no page numbers or next/prev. The followup-idle clock starts at `presentation_done` (final answer received + queue drained + last char in DOM), **not** at first render or answer-generated. Contract, phases, and gates: `.agents/skills/demo1-nova-focus/SKILL.md`. Every rule above (`hint`/`conversation` TTL, paging, shared line budget, settings) keeps applying to `hint`/`conversation` only — do not apply them to `focus` and do not route focus answers through `hint`.
