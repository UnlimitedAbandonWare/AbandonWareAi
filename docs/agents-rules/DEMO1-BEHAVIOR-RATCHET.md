# DEMO1 Behavior Ratchet (one-way lock)

라이브 트리에 한 번 들어온 동작·룰은 사용자 승인 ADR 없이 되돌릴 수 없다.
검사기 `python -B scripts/behavior_ratchet.py`(stdlib). SSOT:
`configs/behavior-ratchet.json` 항목 + `configs/behavior-ratchet.lock.json` 잠금 상태.

## Commands / States

- `check [--json] [--id ID]` 읽기 전용 — exit 0 정상, 4 REVERTED, 1 설정 오류.
- `update [--dry-run] [--task T]` — LANDED만 잠금; live lease 걸린 파일은 건너뛰고
  `skippedLeased`로 보고. test_names 잠금은 합집합으로만 자란다.
- `unlock --id ID --adr docs/architecture/decisions/<file>` — ADR frontmatter
  `status: ACCEPTED` + `approvedBy: user` + `ratchet: <id>` 있을 때만(stack-fit 관례).
  잠긴 항목을 config에서 지워도 REVERTED — 지우지 말고 ADR/unlock을 쓴다.
- 상태: `PENDING`(아직 안 들어옴·실패 아님)/`LANDED`(들어옴·미잠금)/`LOCKED`/
  `IN_FLIGHT`(깨졌지만 파일에 live lease — 실패 아님)/`REVERTED`(깨짐+lease 없음→exit4).
- 종류: `present`(file+pattern[+oldPattern][+section indent])/`absent`(files glob
  패턴 0건)/`test_names`(java|js 테스트 이름 유지).

## Locked invariants (2026-10-05)

- `defaults.*` chat.defaults `search-mode:'AUTO'`·`use-web-search:true`·`use-rag:true`;
  UI AUTO selected + RAG checked; bridge DEFAULTS AUTO/true. 명시적 `useWebSearch:false`
  → OFF 유지. Merger FACTORY→ADMIN_DB→USER→SESSION/REQUEST. STANDARD는 UI 프리셋
  이름일 뿐 SearchMode enum 값 아님. 페이지 로드 init 저장은 persist 안 함.
- `rrf.*` plain RRF = 1/(60+rank) (k=60). weighted RRF ≠ plain. RRF·BM25·QTX에
  temperature 없음.
- `tests.*` 신·변경 테스트 파일의 테스트 이름 잠금(세션 lease 종료 후 update).
- `rules.*` 이 문서 + GUARD-COMMON 포인터 존재, 룰 표면에 낡은 "검색 기본 OFF"·
  "STANDARD enum"·"RRF temperature" 주장 없음.

## 잠금 대기 (이름 미확정 — 세션 보고/diff 확인 뒤 추가, 지어내기 금지)

- MemoryMode 기본 HYBRID·FULL만 장기 기억·세션 간 자동 켜기 없음·customInstructions
  (≤2000자)는 PromptBuilder 사용자 컨텍스트(systemPrompt 아님).
- Temperature requested/resolved/effective/wire + omitReason; OAuth Responses는
  temperature 미전송; 명시 0은 값·null은 상속; SelfAsk 상한 일반 0.55·창작 1.0.
- 사용자 executionMode AUTO|STRIKE|SELF_ASK ≠ OrchestrationSignals STRIKE;
  orch.strike 위조 금지; SELF_ASK 쿼리 ≤3·요청당 ≤6·작업당 ≤12; AUTO 승격 최대 1회.
- 컨텍스트 게이지: 최대치 모르면 "한도 확인 안 됨"; usage(proc)는 출력 토큰.
- Graph: co-occurrence ≠ 인과; source 단위 행 chunk 위장 금지; Jev ≤4칸 재정렬;
  isFinal = ASR partial/final; 새 graph DB/reindex 없음; telemetry
  USED/SKIPPED(reason)/UNAVAILABLE; 근거 그래프 5노드/4링크(펼치면 12/16)·
  latestEvidenceRailItems 금지·SVG sanitizer 완화 금지·발췌 지어내기 금지.
- 검증: `:app:test` NO-SOURCE ≠ PASS; prompt.contextInjected.delivered는 마커 확인.
- `verifier.failsoft-unknown-releases` — 검증 unknown/fail-soft는 본문 유지·
  메모리 금지(`knowledgeWriteAllowed=false`)·HOLD 금지; `!outcomeKnown →
  HOLD/verification_outcome_unknown` 구계약 absent + `verification_unknown_release`
  present. (Codex 지시서 sha12 8b6e0b617ddb 랜딩 후 실명 확인해 config에 추가 —
  탐침 `scripts/probe_verifier_failsoft_release.py`.)

## 절차

1. 패치가 들어오고 세션 lease가 끝나면 `update --task <taskId>` → `check` exit 0.
   lease 중인 항목은 `skippedLeased`로 남겨 다음 update에서 잠근다.
2. 의도된 설계 변경은 사용자 ADR → `unlock --id <id> --adr <file>`.
3. `check` exit 4 = 잠긴 동작 소실 — 원복하거나 unlock. 패턴 완화로 회피 금지.
