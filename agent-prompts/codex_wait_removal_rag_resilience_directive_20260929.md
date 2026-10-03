# Codex 소스수정 지시서 — 대기 제거 + RAG/웹서치 + 장애내성 (2026-09-29)

- 계약 ID: `DEMO1-CODEX-WAIT-RAG-RESILIENCE-20260929`
- 대상 루트: `C:\AbandonWare\demo-1\demo-1\src` (canonical — 다른 경로로 재유도 금지)
- 작성 근거: 2026-09-18 소스·영상 분석 정정판 `data/agent-handoff/devin-display-path-analysis-20260918/report.md` + 2026-09-29 라이브 트리 대조(사실 표기는 이번 체크아웃 파일:라인, 미확정은 `추정`/`NOT_VERIFIED`)
- 소스 스냅샷: `maAWSDSin.zip` sha256 `ca09ddafb49d70a6b1dbcecf6f50f8ebd72cb05092bc77250098d8ab06fe2e4b` (6,052,002B, 3,022 entries, `main/` 프리픽스) — 참조용, 라이브 트리와 불일치 시 **라이브 트리가 진실**

## 0. 3줄 요약

1. 사용자 체감 "대기"의 소스상 주 원인은 렌더링이 아니라 **생성 억제/직렬 대기**: 성공 힌트 후 `displayTtl`(기본 20s) 동안 모든 신규 트리거 차단(`display_hold`), 생성 중 신규 발화 무시(`generating`), 그리고 타임아우트 라우트가 예산을 직렬로 소진하는 fallback 구조.
2. RAG/웹서치는 fail-soft 기구(빈결과 rescue, cache-only, completion-poll, receipts)가 이미 존재 — 중복 구현 금지, cue 경로가 이 기구를 실제로 타는지 검증 후 갭만 메운다.
3. 2026-09-18에 적용된 F1–F6(적응형 정렬, 쿨다운 에스컬레이션, structured output, validation 기록, Card 360 정렬, routesSkipped)는 **유지** — 재수정 금지.

## 1. 무결성/경계

| 항목 | 값 |
|---|---|
| 이번 지시 대상 | `ConversateSessionService`, `ConversateApiCueService`, `ConversateCueRoutingPolicy`, cue 검색 경로(`UnifiedRagOrchestrator` 경유 web 단일사이클) |
| 명시적 비대상 | `/api/chat/sync`·음성 메인 경로 연결, 렌즈 표시 계약(90/360/1180), STT/STT 계정, 인증 |
| Git | 전면 금지 (status/diff 포함 읽기도 불필요) |
| 시크릿 | 출력·로그·커밋 금지, env 이름만 |

**금지 항목 (반드시 준수)**
- 채팅/음성 메인 경로(`ChatService`, `/api/chat/*` admission 경로)에 새 배선 연결 금지
- 모델명 하드코딩 금지(적응형 정렬만), 새 서비스 계층/중복 retriever 금지
- `lensSettings`/Fold 저장 pref 침범 금지 — quiet/cooldown/force-after/ttl/autoPage는 설정 주도, **마지막 페이지 간격 축소 금지**, 저장값의 사일런트 클램프 금지
- `parseHint` strict 계약, Card 360cp, `cost.enforce-limits=false`, `conversate.cue` 디버그 맵 하위호환 불변
- 유료 fanout·신규 provider 호출 추가 금지 (spend guard 범위 내에서만)
- 공장 기본값(yml) 자체의 무분별한 변경 금지 — 명시된 항목 외 `application-meta-display.yml` 수치 손대지 말 것
- commit/push 금지, `verify` 판정 분리(`awx.debug.verify.v2` 규칙: 실행됨/검증됨/빌드됨/완료를 구분)

## 2. 우선순위 표

| 우선 | 항목 | 분류 | 근거 |
|---|---|---|---|
| WP1 | `display_hold` 20s 억제 중 명시적 신규 질문 우회 | confirmed | `ConversateSessionService.java:230,264,516,538` |
| WP2 | 생성 중(inflight) 신규 final 발화 무시 → 큐잉으로 전환 | confirmed | `:230`(skip="generating" early return) `:236`(cancelWork은 skip 통과 후에만 도달) |
| WP3 | per-attempt timeout을 라우트 EWMA 지연 기반으로 파생 | confirmed | `ConversateApiCueService.java:444-472`, `ConversateCueRoutingPolicy.java:120-122,164-178` |
| WP4 | work expiry 15s < 실제 worst-case 경로 → 완료 결과 사일런트 폐기 | confirmed | `ConversateSessionService.java:237,280`(`work.expiresAt()` gate, `s.expired++`), `ConversateApiCueService.java:67` (total 12s) + retrieval 최대 3s(`:146`) + 큐잉 |
| WP5 | cue web 검색이 fail-soft 기구를 실제로 타는지 검증 → 미통과 seam만 최소 수정 | conditional | `ConversateApiCueService.java:297-356` (`web.failsoft.*` trace 읽기는 존재; `retrieval` 빈 배선 경로 확인 필요) |
| WP6 | `primary_timeout_division_for_fallback: false`(yaml) vs gate `remaining/2`(code) 계약 대조 | conditional | `configs/api-routing.yaml:17`, `ConversateApiCueService.java:470` |
| WP7 | `caller_timeout_releases_capacity: false` — 호출자 포기 후 lease/worker 잔류 시간 계측 | conditional | `configs/api-routing.yaml:16`, `ConversateSessionService.java:252` |
| — | 샘플링/쿨다운/force-after/quiet 기본값 변경 | **not-reproduced → 금지** | 설정 주도 계약; 영상 증거는 "트리거가 안 뜨는/늦는" 문제지 주기값 자체가 아님 |

## 3. WP 상세 (증거 → 재현 → 최소 수정 → 회귀 테스트 → 완료 조건)

### WP1 — `display_hold` 신규 질문 우회 [confirmed]

- **증거**: 성공 힌트마다 `s.hintHoldUntil = okAt + displayTtl` (`ConversateSessionService.java:264`). `utterance_end` 트리거 경로는 `:230`에서 `now < s.hintHoldUntil` → `CUE_SKIPPED reason=display_hold`로 early return. 누적 트리거(`:516`)와 force 트리거(`:538`)도 동일하게 차단. 결과: 힌트가 렌즈에 뜬 뒤 20초 동안 사용자가 새 질문을 해도 아무 일도 안 일어남 — 영상에서 관찰된 "렌즈가 멈춘" 구간과 일치(추정: 영상 증거는 09-18 보고서 경유).
- **재현(RED 먼저)**: `ConversateApiCueServiceTest`/`DisplayConversateHttpTest` 계열에 fake-clock 세션 — 힌트 성공 → `displayTtl` 이내 새 final 발화 → 현재 `CUE_SKIPPED display_hold` 관측, 수정 후 신규 Work enqueue 관측.
- **최소 수정(택1, Codex 판단)**: (a) `utterance_end` 트리거에서 `display_hold` 스킵을 **새 질문 조건**(델타 ≥ `triggerMinDelta` AND 최신 발화가 question-like — 기존 `ConversateQuestionPolicy` 판정 재사용)에만 우회. (b) hold를 질문-우회 + 동일-토픽 억제로 분리. **(c) 공장 기본값이나 pref 범위 변경은 금지** — 사이클 수치가 아니라 "무엇을 차단하는가"의 조건만 바꾼다.
- **회귀 테스트**: (i) 동일 토픽 누적 델타는 여전히 hold 기간 중 차단, (ii) 새 질문은 hold 관계없이 큐 진입, (iii) hold 중 enqueue된 작업이 이전 카드를 정상 supersede(version/cueVersion 증가), (iv) `hintsEnabled=false` 불변.
- **완료 조건**: 기존 cue 테스트 전수 + 신규 케이스 GREEN; `CUE_SKIPPED` 진단 행에 `bypassedBy=new_question` 필드 추가(기존 키 유지).

### WP2 — `generating` 중 신규 발화 무시 → 큐잉 [confirmed]

- **증거**: `:230` skip 체인이 `:236` `cancelWork`보다 **먼저** 실행. inflight 존재 시 새 final 발화는 `CUE_SKIPPED reason=generating`으로 반환 — 이전 생성은 완주하고 새 질문은 폐기. 사용자 입장에선 3–5s 생성 중 말한 내용이 소실.
- **재현**: inflight 중 새 final 발화 2건 → 현재 둘 다 drop; 수정 후 최신 1건이 inflight 종료 직후 dispatch.
- **최소 수정**: `generating` 스킵 분기에서 early return 대신 `s.queue`에 Work enqueue(`cancelWork`는 실행 중 worker를 물리 종료 전까지 유지하므로 supersede 의미론 유지 — `:235` 주석). 큐 길이 상한 기존(`s.dropped`/`s.expired` 카운터 재사용, 새 큐 레이어 금지). inflight 결과 도착 시 `work.expiresAt`·epoch 검사는 기존 로직 그대로.
- **회귀 테스트**: inflight-중 enqueue → 완료 후 최신 작업만 실행(중간 작업 drop 카운트), inflight 실패 시에도 큐 dispatch, 중복 발화 dedup 유지.
- **완료 조건**: `generating` 스킵 진단이 `queued_superseding` 같은 구분 reason으로 전환되고, 관측된 신규 질문 손실 = 0(테스트).

### WP3 — per-attempt timeout EWMA 파생 [confirmed]

- **증거**: `call()` 루프(`ConversateApiCueService.java:458-545`)에서 비-gate 시도는 `attemptMs=remaining` (`:470`) — 1순위 라우트가 타임아웃 나면 stage 예산 대부분을 소진 후 fallback(09-18 라이브: openai 3,289ms timeout → gemini 1,294ms 성공 = 4.6s). 라우트별 EWMA 지연은 이미 `Health.latency`/`expectedLatencyMs`에 존재(`ConversateCueRoutingPolicy.java:164-178`) — 정렬에는 쓰지만 타임아웃에는 안 씀.
- **최소 수정**: `attemptMs = clamp(expectedLatencyMs × k(예: 2.5), floor(500ms), remaining)` — EWMA 관측 없으면 기존 동작 유지. gate 시도의 `remaining/2` 분할은 유지(WP6 대조 후 결정). 새 상수는 모두 `limit()` 경유 yml 바인딩.
- **회귀 테스트**: 느린 첫 라우트 타임아웃 시 두 번째 라우트에 남는 예산 증가, EWMA 없는 라우트는 기존 타임아웃, `max-attempts`·비용 가드 불변.
- **완료 조건**: 시뮬레이션된 openai-timeout → gemini 시나리오에서 총 대기 감소(측정값 기록), 진단 맵 `attemptMs` 필드 추가(하위호환).

### WP4 — work expiry vs 실제 경로 예산 정합 [confirmed]

- **증거**: apiCues `workExpiresAt = now + 15_000`(`:237`, accum/force 경로 `:531`,`:560`). 결과 게이트 `work.expiresAt() > clock.millis()`(`:280`) — 15s 초과 완료는 `s.expired++`로 **사일런트 폐기**. 한편 힌트 단계 예산만 최대 12s(`total-timeout-ms`, `ConversateApiCueService.java:67`) + retrieval 최대 ~3s(`:146`의 3000ms bound, 상위 예산 min으로 더 작을 수 있음) + admission 대기 → worst-case가 15s를 넘을 수 있음(추정: 실측 초과 빈도 미확인 — `expired` 카운터로 먼저 계측).
- **재현**: fake clock으로 생성이 15.5s에 끝나는 시나리오 → 현재 `expired++`·카드 미표시; 수정 후 카드 표시 또는 명시적 사유 진단.
- **최소 수정(택1)**: (a) apiCues expiry를 `retrieval bound + total-timeout-ms + 슬랙(예: 2s)`으로 유도 — 상수 하드코딩 금지, `limit()`/yml 바인딩. (b) 만료된 완료 결과도 stale 아닌 한 표시 + `expired_result_delivered` 진단 추가. **(a) 채택 시 publicDisplay 85s/기타 20s 분기(`:237`)의 다른 경로는 건드리지 말 것.**
- **완료 조건**: 만료 결과가 조용히 버려지는 경로 제거 또는 카운터+진단으로 명시화; 기존 만료 폐기 테스트 갱신.

### WP5 — cue web 검색의 fail-soft 실경로 검증 [conditional]

- **증거**: cue retrieval은 `UnifiedRagOrchestrator.QueryRequest`(useWeb=true, web 단일사이클, topK 8) → `retrieval.query(request)` (`:297-312`). fail-soft 수신 receipt(`web.failsoft.runs`, `conversate.search.NAVER|BRAVE`)를 `:334-356`이 이미 읽음 → 경유 추정(사실: trace 키 존재; 추정: 실제 aspect 경유 여부).
- **작업**: `retrieval` 빈의 실제 타입 → `HybridWebSearchProvider.search` 경유 여부를 소스로 확정. 경유 시 → **변경 없음, 검증 결과만 보고**. 미경유(병렬 포트) 시 → 왜 bypass인지 원인 기록 후, 기존 `WebFailSoftSearchAspect`/`HybridWebSearchEmptyFallbackAspect` 포인트컷을 좁게 확장하는 방향으로만 최소 수정(새 retriever/새 failover 계층 금지).
- **완료 조건**: `stage.web`/`web.retriever` 진단 값으로 경유 판정이 관측 가능(필요 시 진단 필드 1개 추가).

### WP6 — `primary_timeout_division_for_fallback` 계약 대조 [conditional]

- **증거**: `configs/api-routing.yaml:17` = `false`(분할 금지), 코드는 gate 시도에 `remaining/2` (`:470`, 주석 "Reserve fallback time only when another eligible route can actually use it").
- **작업**: yaml 의도가 "gate도 primary인가"인지 소스 주석·관련 테스트로 확인 → 불일치 확정 시 yaml 문서를 코드에 맞춰 정정하거나(문서 수정) 코드를 분할 없이 `remaining`으로 통일(테스트 필요). 어느 쪽이든 **둘 다 건드리는 모호한 변경 금지** — 대조 결과를 보고서에 명시.

### WP7 — 호출자 포기 후 capacity 잔류 계측 [conditional]

- **증거**: `caller_timeout_releases_capacity: false`(yaml:16); admission lease `tryAcquire` + worker는 생성 완주까지 점유(`:252`,`:298` finally의 `dispatch(s)` 재진입). 취소된 작업도 worker가 물리 종료해야 capacity 반환.
- **작업**: 취소 경로에서 lease 반환까지의 시간을 `cancelled`/`expired` 카운터 또는 진단 필드로 계측. 코드 수정은 계측 결과가 손실을 증명할 때만 — 측정만이면 측정으로 끝냄.

## 4. 유지(재수정 금지) — 2026-09-18 적용 원장

`ConversateCueRoutingPolicy` 복합 정렬키·routesSkipped·쿨다운 에스컬레이션(20→60→300s), `ApiFailureRecorder` scope/evidence/consecutive/maskedBy 확장 + `recordValidation`, `LlmRouterAspect` 4-arg apiAttempt + masked-fallback incident, `ConversateCardPrompt` ~320자 프롬프트 + `wireSchema` JsonSchema, `GeminiGateway` 스키마 전달, `ConversateApiCueService` recovery-only 파싱 + `jsonRecoveryUsed`/`truncatedByTokenBudget`/`hintTextChars`/`cardTextChars`, Card 280→360cp 정렬 — 전부 유지. 필드 삭제·의미 변경 금지.

## 5. DO-NOT-TOUCH (FIELD_TESTED 기준값)

26~30px 글자 크기 · 약 320자/4–8줄 힌트 생성 shape · 360cp/8줄 hard cap · `max-output-tokens`(현재 1536, `docs/volatile-knobs.md`) · rolling context 2000자/TTL 120s · local_rules gate · quiet 2500/cooldown 10000/force-after 180000 공장 기본값(Fold pref가 덮음) · 폴백 최대 3회 시간분할(구조 변경은 WP3 범위만) · 검색 1회/$0.01 · 렌즈 1s 폴링 · 렌즈 계약 90/360/1180 · PCM 백프레셔 · autoPage 설정값 사일런트 축소 · `demo.auth.proto-open`.

## 6. Vibe 규칙 (Codex 측)

- 가역·로컬 변경(코드/테스트)은 Self-Ask(긍정/부정/반례→중립)로 AUTO — 사용자 퀴즈 금지.
- ASK 1회만: 운영/공유 DB, DELETE/TRUNCATE, commit/push, task_ask·callback 활성화, 보안/권한 확대, 공장 기본값 수치 변경 제안이 필요해질 때.
- Prototype Light: admin fail-close는 주목표 아님. `PROTO_OPEN`에서 fresh-admin = NOT_OBSERVED.
- 새 러너 금지 — 기존 스위트 재사용: `ConversateApiCueServiceTest`, `ConversateCueRoutingPolicyTest`, `ConversateApiFailureClassificationTest`, `ConversateLocalCueAdmissionTest`, `DisplayConversateHttpTest`, `DisplayStickyLensTest`.
- 컴파일 `.\gradlew.bat :compileJava -x test`, 라이브 반영은 DevWatch/ForceRestart — stale JVM 200은 증거 아님.

## 7. 보고 형식

- 변경 파일 + sha256, WP별 상태표(재현 RED → 수정 → 회귀 GREEN), 증거 분리표: mock / build / live boot / real API / browser. 미실행 = `NOT_RUN`, 추정과 사실 구분, `policy-conflict`는 조용한 반전 없이 기록.
- `verify` 보고는 `run`/`target verified`/`build+tests`/`fullVerification` 분리 — 부분 실행분만 PASS 주장.
