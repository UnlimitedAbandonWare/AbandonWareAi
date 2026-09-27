# Codex kickoff — /chat 답변별 디버깅 트레이스 복원 (IMPLEMENT)

> Devin assist layer가 정리한 실행 카드. 제품 소스 수정 권한은 Codex(`mgain-trace-restore-0926` lease)에만 있다.
> 이 문서는 지시서를 대체하지 않는다 — SSOT를 먼저 읽는다.

## 경로 고정 (한 줄 가드)

- **Project Root = `C:\AbandonWare\demo-1\demo-1\src`** — Downloads·ZIP·`%USERPROFILE%\.codex\attachments`·`main/` 추출물을 루트로 쓰지 않는다. 모든 상대 경로(`main/java`, `main/resources`, `src/test`, `scripts/`)는 이 루트 기준.
- **SSOT (읽기 전용) = `C:\Users\nninn\Downloads\MGAIN_DEBUG_TRACE_RESTORE_2026-09-26\`**
  - `goal-objective.md` — HARD 완료 조건
  - `mgain_debug_trace_restore_directive_2026-09-26.md` — 구현 지시서 §1–§10
  - `source_evidence.md` — E01–E16 줄 근거 / `source_manifest.json` — 파일 해시
  - `tools/read_only_renderer_probe.mjs` — **패치 전 baseline 재현 전용**. 패치 후 합격 테스트로 쓰지 않는다.
    `node tools\read_only_renderer_probe.mjs C:\AbandonWare\demo-1\demo-1\src\main\resources\static\js\chat.js`

## HARD 완료 조건 (goal-objective.md)

완료 = (1) Project Root 소스 패치 적용 **그리고** (2) T01–T16 결과(PASS/FAIL/NOT_RUN+사유) 보고.
지시서·goal을 읽기만 하고 종료하면 미완료. 완료표는 `t01-t16-verification-checklist.md`를 사용한다.

## 한 줄 규칙

**debug-trace restore = 기존 경로(`TraceHtmlBuilder → ChatStreamEvent.trace → POST SSE → 답변별 패널`) reconnect. 신규 엔진·EventSource 교체·WebSocket 금지. WeakMap<assistantNode, panelNode> 소유권. DOMPurify `RETURN_DOM_FRAGMENT`. 공용 parent `querySelector('[data-role="trace"]')` 금지.**

## 구현 순서 (지시서 §6 Task A–E)

1. 관리자 ON/OFF 토글 → stream 요청 `debug=true` **query param** → 서버측 authz (`debug || exposeTrace` 와 `isAdmin`). `ChatRequestDto` body에 debug 필드 추가는 `@RequestParam`과 안 맞음.
2. `chat-trace-ui.js`(신규, 단일 namespace, 기존 defer 로딩 방식) + scoped `chat-trace.css`. DOMPurify는 `chat-ui.html`이 이미 로드하는 것 재사용 (현재 `dompurify-3.4.15.min.js`).
3. WeakMap assistant별 upsert — prefetch/final/replay는 같은 패널 갱신, 다른 답변 건드리지 않음. 메시지는 모두 `dom.chatMessages`의 직접 자식(`appendMessage`)이므로 부모 공유.
4. `rawTrace == null`이어도 B/C 섹션·상태 요약 생성 — `TraceHtmlBuilder` 조기 `return ""`와 `ChatApiController`의 `if (rawTrace != null)`를 **함께** 수정.
5. `turnTraces` 유지; 패널 열 때 `turnTraces[].snapshotId`로 `/api/diagnostics/trace/snapshots/{id}/html` 지연 조회. `traceTurnId`≠`snapshotId`≠`turnId` 혼동 금지. `/state.traceHtml`은 해시 요약 문자열 — HTML로 렌더하지 않음.
6. 재접속은 기존 `attachInteractiveExact`; replay 시 **현재 구독자 권한**으로 상세 투영. token/final/cancel/ACK 보존.

## 막힐 때 유도 (Devin → Codex)

| 증상 | 유도 |
|---|---|
| 공용 parent upsert로 다른 답을 덮음 | `WeakMap` 키를 **assistant 메시지 노드 자체**로. 패널은 해당 메시지 바로 뒤 삽입. |
| `textContent`→`innerHTML` 직치환 | 금지. `DOMPurify.sanitize(html,{RETURN_DOM_FRAGMENT:true})` → `replaceChildren(fragment)`. |
| `rawTrace` null이면 패널 없음 | null = "관측 없음"이 아님. Vector-only/라우팅/메타만 있어도 B/C·상태 섹션. |
| 전역 latest snapshot을 답변 근거로 사용 | `latest-harmony`/`latest-trace-memory`는 특정 답변 증거가 아님 — `turnTraces[].snapshotId`만. |
| `?debug=1`을 페이지 URL에 붙였는데 안 나옴 | 페이지 query≠스트림 query. 토글 상태를 URL 생성 helper에서 스트림 POST의 `debug=true`로 전달. |
| goal만 읽고 종료 | HARD 완료 조건 미달 — 아래 재킥오프 문구를 다시 붙인다. |
| snapshot 404 | 퇴출 vs 재시작 단정 금지. 요약 유지 + "상세 스냅샷을 현재 저장소에서 찾을 수 없음" 표시. |

## MUST NOT (위반 시 되돌림 대상)

- 전체 `chat.js`/`ChatApiController`/보안 설정 레거시 ZIP 교체, `script[data-trace-script]` 재실행, `eval`/`new Function`
- `SafeRedactor` 완화, `WEB_TRACE_EXPOSE=true` 기본값, `/api/diagnostics/**` `permitAll` — **proto-open 유지하되 diagnostics ADMIN 체인은 그대로**
- 프런트 DOM/localStorage로 서버 권한 판정, `permitAll` 유도, 마스킹 해제
- 새 모델 호출/유료 API/외부 서비스/별도 디버그 WebSocket·상시 SSE
- Display reconnect·SelfAsk 고아 삭제 등 다른 THE ONE과 혼합, `git push`/`add -A`/`commit -a`/`--no-verify`
- `docs/PROJECT_STATUS.md`는 복수 lease 대상 — 다른 작업과 동시 편집 주의(자신의 lease scope에 있을 때만)

## 지원 산출물 (읽기 전용 참고)

| 산출물 | 용도 |
|---|---|
| `agent-prompts/mgain-debug-trace-devin-assist-20260926/t01-t16-verification-checklist.md` | T01–T16 PASS/FAIL/NOT_RUN 기록표 |
| `scripts/mgain_trace_smoke.py` | 정적 스모크 + 라이브 앵커 맵 (`python -B scripts/mgain_trace_smoke.py [--strict] [--json]`) |
| `.agents/skills/mgain-debug-trace-restore/SKILL.md` | 스킬 라우터용 1줄 규칙 포인터 |

## 재킥오프 문구 (goal만 읽고 끝낸 경우 그대로 전달)

> 완료 조건 미달입니다. goal-objective.md의 HARD 조건: Project Root 소스 패치 + T01–T16 실행 보고. `mgain-trace-restore-0926` lease는 유효합니다 — 지시서 §6 Task A→E 순으로 진행하고 `t01-t16-verification-checklist.md`에 명령·exit code·관측 결과를 채워 보고하세요. 읽기/분석만으로는 완료가 아닙니다.

## 보고 형식 (지시서 §10)

Changed files | 실행 명령+exit | T01–T16 표 | NOT_RUN 목록+사유 | 잔여 미검증/롤백 방법 — 6항목 이내.
