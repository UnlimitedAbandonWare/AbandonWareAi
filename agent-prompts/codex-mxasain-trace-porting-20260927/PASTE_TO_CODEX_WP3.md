# Codex 붙여넣기 — mxasain 웹 트레이스·디버깅 선별 이식 (WP3)

작성: 2026-09-27 Asia/Seoul · A(WP0–WP2) 완료 보고 접수 후 다음 단위 승인용 지시서

## 0. 역할

너는 **구현 담당(Codex)**이다. `CODEX_TRACE_PORTING_A: DONE`(WP0–WP2)은 **승인됨** —
F01/F02와 checkpoint scanner 수정을 재감사·재작업하지 말 것. 이번 세션은 지시서의 다음
작업 묶음 **WP3 하나만** 실행한다.

- **정본(수정 대상):** `C:\AbandonWare\demo-1\demo-1\src` (Project Root). 첨부/ZIP보다
  LIVE가 우선.
- **기준 문서:** 네가 쓴 `docs/diagnostics/trace-porting-baseline.md`(A baseline·완료 기록),
  `agent-prompts/codex-mxasain-trace-porting-20260927/PASTE_TO_CODEX.md`(A 지시서, 보존/금지
  규칙 그대로 계승), `evidence/mxasain_trace_porting_directive_2026-09-27.md` §4 WP3·§5 검증
  행렬.
- **v2 범위 규칙 유지:** 한 활성 단위만. **이번 세션 = WP3.** WP4~WP8(B 나머지, C, D)은
  이번에 구현 금지 — checkpoint 후 사용자 확인 대기로 종료.

## 1. Goal (한 문장)

기존 v2 답변 바인딩을 유지한 채, 상세 캡처가 **budget·필터·disabled·생성 실패·종료 경계**
중 무엇으로 없어졌는지 구체 상태로 남기고, 라이브와 재조회가 같은 답변에 연결되게 한다.

## 2. Self-Ask (시작 전 5줄만)

1. 요청 동작: 상세 없음 = 원인 코드로 표시됨; 재시작·reload 후에도 같은 assistant에 바인딩.
2. 현재 증거: baseline `still_present` E08/F03(상세=메모리, 포인터=2KiB 요약),
   E14/F09(`captureCustom`이 `consumeBudget` 경유, 필터 종료 시점 한계).
3. 모호: 지시서 줄번호는 ZIP 기준 — 메서드명·해시로 LIVE 재매핑.
4. 바꾸면 안 됨: `?TRACESNAP?v2` 의미, A에서 확정된 정제·표 계약, DOMPurify/secret mask,
   bounded SSE, session/routing/budget, proto-open, foreign lease/staging.
5. 최소 seam: `ChatApiController` 최종 `extraMeta` 복사본 1회 확정 경로 + persister/store/
   restorer/response builder의 해당 메서드만.

## 3. 보존 / 금지

### 보존 (A 계승 + WP3 특화)
- `assistantMessageId` + `?TRACESNAP?v2` 포인터 의미·바인딩·충돌 처리
- metadata-only snapshot 두 producer shape
- 답변 성공과 저장 성공의 분리 — 답변 실패로 전파하거나 둔갑시키지 않음
- debug on/off의 모델·검색 호출 수·route·budget·prompt fingerprint — 실제 차이가
  확인될 때만 관측 옵션을 진단 계산에서 제한적으로 분리

### 금지
- 못 찾은 스냅샷을 **최신 전역 snapshot**으로 대체 (전역 latest fallback 0건이 완료 조건)
- cancel 전 assistant 미생성 상황에 assistant ID를 만들어 붙이기 (run 단위 진단으로 둠)
- secret·raw prompt·ownerToken·clientToken·URI credential 허용 확대
- 로그·상세 조회를 위한 **LLM/검색/probe 재실행** (관측 경로는 read-only)
- DOMPurify·secret mask off, 임의 path tail, root DEBUG 상시
- pointer v2 의미 변경·무표기 migration — additive 필드만, migration은 버전 명시
- push / `git add -A` / 타인 staging 덮기 / 실패 테스트 skip·기대값 완화
- **WP4+ 시작 금지** (서버 exact 조회·SSE cursor·영속 repository·로그 adapter·설명 패널)

## 4. WP3 — 작업 항목 (지시서 §4 WP3 그대로)

**터치(해당 메서드만):**
`main/java/com/example/lms/api/ChatApiController.java`,
`api/ChatTraceSnapshotPointerPersister.java`, `api/ChatTraceMetaMessageRestorer.java`,
`api/ChatSessionDetailResponseBuilder.java`, `trace/TraceSnapshotStore.java`.
기존 동등 seam이 있으면 재사용하고 새 병렬 저장소·엔드포인트를 만들지 않는다.

1. **먼저 회귀 테스트(RED):** 기존 v1·v2 포인터, 잘못된 ID, 충돌 포인터, 동일
   timestamp 답변, 삭제된 assistant, owner 불일치 fixture.
2. 최종 `extraMeta` 복사본을 snapshot/projection/terminal recorder가 공유하도록 연결.
   최종화는 **현재 run의 terminal owner에서 한 번만**.
3. 상세 snapshot의 `budget 소진` / `필터 제외` / `disabled` / `생성 실패`를 각각 구분된
   진단 상태로 남긴다. pointer가 없다고 "기록할 사건 없음"으로 표현하지 않는다.
4. 응답 DTO에 additive한 `diagnosticAvailability` 추가(예:
   `available|memory_only|capture_skipped|budget_exhausted|disabled|filter_excluded|
   io_failed|unknown` — 실제 분기를 enum으로). pointer v2 의미는 그대로.
5. sync·stream·재연결·cancel·오류 각 1회 이상 검증.
6. debug 표시 on/off에서 호출 수·route·budget·prompt fingerprint 비교 — 차이가 있을 때만
   분리하고, 차이 없으면 그 사실을 보고한다.

**완료:** live 상태와 이후 session reload가 같은 답변에 연결되고, 상세가 없는 이유가
구체 상태로 보인다. 전역 latest fallback 0건. 지시서 §5의 WP3 행(4조합 disabled/empty/
missing, capture budget 소진, v1·v2·충돌 포인터, debug on/off)이 fresh 테스트로 통과.

## 5. 검증·보고

- 검증은 focused 단위 + 기존 회귀 suite 범위. 전체 `:test`/`chatUiTest` 요구 아님
  (돌렸으면 subset 관계를 명시). mock 모델/검색 호출 수 변경 전후 동일 확인.
- Java 변경 시 `compileJava` → dev 재기동(Start-RAG/DevWatch) → served 파일 해시 또는
  동급 provenance. `node --check`는 syntax만이며 DOM/서버 증거 대체 아님.
- A와 같은 규율: work journal·checkpoint·baseline 문서에 WP3 결과를 append
  (`docs/diagnostics/trace-porting-baseline.md`에 WP3 절 추가), 변경 파일 + 전·후
  SHA-256, 복구 경로 명시.
- Git: 커밋 강제 아님. 한다면 `scripts/agent_git_vibe_commit.py --path <owned>...`만.
  공유 파일에 pre-task hunk가 섞여 있으면 A처럼 deferred로 기록. foreign staged 유지.
- 보고 블록:

```text
CODEX_TRACE_PORTING_WP3: DONE|PARTIAL
baseline: docs/diagnostics/trace-porting-baseline.md (WP3 절)
changed: (파일 + 전/후 sha256)
commands: (실행한 test 명령과 실제 결과)
pass/fail:
NOT_RUN: (전체 suite / 서버 E2E / 실 SSE 네트워크 등)
rollback: (체크포인트 역순 적용; DOMPurify·secret mask off 금지)
next: 사용자 승인 후 WP4 또는 stop
```

## 6. 환경·충돌 메모 (재확인 필수, 아래 값은 A 시점 기록)

- Gradle 8.7 / Java 17.0.13 / node 24.13.0; build root = Project Root.
- `AWX_AGENT_ALLOW_PAID_MODELS` 없으면 유료 호출 금지 — WP3 검증은 단위·fixture만으로
  충분해야 한다.
- task entry에서 `agent_preflight.py --root .`로 저널·lease 재확인. A 시점 lease
  (`.clinerules`/`AGENTS.md` 계열, `read_rag_debug_trail*.ps1`)는 만료됐을 수 있으므로
  **live 상태를 다시 읽고** 추정으로 편집하지 않는다. 규칙 파일 편집은 이 WP의 범위 밖.
- proto-open 유지: admin login/logout-block/`proto-open=false`를 완료 조건에 넣지 않는다.

## 7. Stop rule

WP3의 합의된 테스트가 통과하면 checkpoint하고 **여기서 종료**한다. WP4+는 다음 사용자
확인 후 별도 지시. 이미 고쳐진 항목 재작성·동일 가설 반복 스캔·새 엔진/대시보드 추가 금지.
