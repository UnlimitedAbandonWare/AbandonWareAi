---
name: demo1-uaw-harmony-foundation
description: Use when a demo-1 session must lay UAW/Abandon_X harmony foundation rails (spine probe, spine matrix, setup-order docs) before any product patch — read-only observation only; product code stays Codex-owned
---

# demo1 UAW Harmony Foundation

UAW/Abandon_X 큰 그림과 live 스파인의 **조화 밑밥**을 만드는 진입 스킬.
기능 로직 패치 전에 도구·탐침·지도를 정렬한다. 제품 코드(main/java, static,
plans, application*)는 Codex MAX-PUSH/승인 소유 — 이 스킬은 읽기 전용 관측과
`docs/diagnostics/`·`scripts/`·`.agents/` 산출물만 만든다.

## Entry

1. `python -B scripts/agent_preflight.py --root .` → lease/journal/signals 확인.
   파일 변경이면 `$demo1-work-ledger` (goal-switch check → journal open →
   checkpoint begin). `agent-scope-lease`로 `uaw-harmony-foundation` scope claim.
2. 읽기 순서: `UAW.txt`(의도만) → `Abandon_X.txt` §0/§4/§5(교정 SSOT) →
   `docs/diagnostics/uaw-vibe-longrun-20260928/00_HARMONY_MAP.md` →
   `docs/diagnostics/uaw-harmony-foundation-0928/01_SPINE_MATRIX.md`.
3. 스파인 스냅샷: `python -B scripts/uaw_spine_probe.py --root .`
   → S1~S10 verdict. exit 0 = 스캔 완료(verdict와 무관).

## Live anchors (재검증 없이 인용 금지)

- Boot: `LmsApplication` (scan `com.example.lms`+`com.nova.protocol`),
  `build.gradle.kts` mainClass. `AgentApplication` = 존재 경고만.
- imports 6종: `main/resources/META-INF/spring/…AutoConfiguration.imports`.
- RAG spine: `UnifiedRagOrchestrator`(planDsl.status=`not_used`) +
  `DynamicRetrievalHandlerChain` + `PlanHintApplier`.
- Order/policy: `RetrievalOrderService` — RuleBreak(SPEED_FIRST)/Guard/CFVM
  분기는 `retrieval.order.mode`≠fixed일 때만 도달(기본 fixed).
- Gates: `com.example.lms.guard.*` + `service.rag.auth.DomainWhitelist` +
  `service.guard.PIISanitizer`. 게이트 우회 레이어 신설 금지.
- Failure/Zero100: `NovaFailurePattern*`/`NovaZero100*` autoconfig 경유.
- UAW product: `com.example.lms.uaw.*` — 기본 disabled 존중, "켜기"는
  02_SETUP_ORDER §6 체크리스트 + 승인 후.

## Don't

- 제품 소스/JS/plans/설정 값 패치, `allow-bean-definition-overriding` 도입,
  dormant(`com.abandonware.*`, 루트 패키지)/zerobreak 계보 활성화, ABSENT
  이름(GRANDAS, JamminiMemory.atomic.yaml) 복제, 새 autoconfig 남발.
- Codex MAX-PUSH lease와 겹치는 파일 수정(→ HOLD). commit/push.
- 포트폴리오 수치를 Done 근거로 사용. 스킬 남발(이 스킬이 유일 진입점).

## Verify / report

- verdict 분리: tool-ran ≠ target-verified. probe exit 0은 스캔 완료지
  스파인 건강 증명이 아니다. 미실행 항목은 `evidence_needed`/`NOT_RUN`.
- 산출물 위치: `docs/diagnostics/uaw-harmony-foundation-0928/`.

## Related

`demo1-uaw-vibe-longrun`(메타 루프), `demo1-ablation-harmony-tracker`(scorecard→directive),
`demo1-devin-source-orchestrator`(phase 시퀀싱), `agent-scope-lease`,
`demo1-work-ledger`, `demo1-goal-complete-stop`.
