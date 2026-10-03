# coop-verify-0928 · 01_DESIGN_FIT — 지시서 계약 → 기존 장치 매핑

근거: `AWX_cooperative_verification_directive_2026-09-28.md`(같은 폴더 사본) + `00_PROBE.md` 실측.
원칙: 기존 journal/lease가 상태의 단일 기준. coop_verify 산출물은 **파생 기록**(ticket/receipt/writer marker)일 뿐 소유권의 제2 진실을 만들지 않는다.

## 1. 지시서 개념 → 기존 장치 매핑

| 지시서 개념 | 기존 장치 | coop_verify의 위치 |
|---|---|---|
| writer = 논리적 편집 묶음(edit_batch) | source-edit lease = **파일 집합** 소유권 (target-scoped) | writer 레코드가 `edit_batch_id`로 묶음을 표현하고 `leaseId`를 참조. lease 대체 아님 |
| writer 등록 원자성 | lease contract의 충돌 판정(`Get-AwxSourceEditConflictDecision`) + pre-edit hook | `writer-begin`이 `.coop.lock` 상호배제 안에서 writer 레코드 생성 + 활성 검증 소유권 확인을 한 전이로 수행 |
| heartbeat | `source-edit-heartbeats/<leaseId>.json` (TTL 갱신) | writer heartbeat는 coop 쪽 `heartbeat_at`만 갱신 — `last_source_change_at`/quiet timer는 건드리지 않음(지시서 §4.1) |
| 검증 ticket/receipt | 없음 (MISS 확인) | `data/agent-handoff/coop-verify/` 신설: `state.json` + `receipts/<ticketId>.json` + `runner.json` |
| 검증 배타권 (max_heavy_verifiers=1) | 없음 | `.verify.lock` `open("xb")` 배타 생성 + `pid_alive` 사망 회수 |
| 검증 실행 | `run_verified_command.py` / gradlew / Debug-RAG verify | `run-once`가 선택된 ticket의 `verifyCommand`를 subprocess로 실행(기본 `cmd` 인자 그대로, 셸 파이프 없음) |
| 완료 판정 (Stop 정책) | `demo1-work-ledger` §3 "modified ≠ verified" + evidence gate; 훅 파일에 Stop 훅 **없음** | `status --agent X`가 `turnEnd` 블록(APPLIED_PENDING_VERIFICATION 허용 여부 + 보류 ticket 참조)을 반환 — Codex 측 완료정책 문구가 이 필드를 읽게 FOR_CODEX로 인계 |

## 2. 상태 계약 (지시서 §3·§5 축소 없이 채택)

`APPLIED_PENDING_VERIFICATION`(보고 상태), `DEFERRED`, `QUIESCING`, `VERIFYING`, `VERIFIED_PASS`, `FAILED`, `INVALIDATED`, `ENVIRONMENT_ERROR`, `TIMEOUT`, `BLOCKED_UNKNOWN_OWNER`, `WAITING_FOR_RUNNER`, `SUPERSEDED`.

## 3. 레코드 스키마 (state.json 안)

```jsonc
// writer
{ "editBatchId", "agent", "sessionId", "taskId", "leaseId" /* nullable 참조 */,
  "state": "EDITING|CHECKPOINT|RELEASED",
  "heartbeatAtUtc", "lastSourceChangeAtUtc", "begunAtUtc", "endedAtUtc",
  "ownerPid", "ownerIdentity", "changedPaths": [ ... ] }

// ticket
{ "ticketId", "requester", "agent", "taskId", "profile", "scope": [paths],
  "requestedAtUtc", "targetMode": "latest|exact", "targetIdentity",
  "requiredStages": [ ... ], "verifyCommand": [argv...],
  "state": "...", "waitingOn": [...], "coveredRequests": [...],
  "receiptPath", "updatedAtUtc" }

// runner (watch 가 기록)
{ "runnerId", "pid", "heartbeatAtUtc" }
```

## 4. exit code 계약 (프로젝트 자체 계약 — 훅에 그대로 전달 금지)

| 명령 | exit | 의미 |
|---|---|---|
| `status`, `writer-*`, `request`, `recover`, `watch`(정상 종료) | 0 | **처리 성공**일 뿐 검증 PASS 아님 — 상태는 JSON `state`/`verificationStatus` 필드로만 판독 |
| `run-once` | 0 | `VERIFIED_PASS` — 필수 단계 실제 성공 receipt 존재할 때만 |
| `run-once` | 10 | `DEFERRED` — foreign writer 활성 / quiet 미충족 / verifier-busy |
| `run-once` | 11 | `INVALIDATED` — 검증 중 입력/소유권 변경 감지 |
| `run-once` | 20 | `FAILED` — 안정 입력에서 검증 명령 실패 |
| `run-once` | 21 | `ENVIRONMENT_ERROR`/`TIMEOUT` — 실행 환경/시간 문제, 코드 결함 아님 |
| `run-once` | 30 | `BLOCKED_UNKNOWN_OWNER` — 소유 불명/상태 파손 (PASS 경로 없음) |
| `run-once` | 44 | `NO_PENDING_TICKET` — 처리할 ticket 부재 (유휴; PASS 아님) |
| 모든 명령 | 2 | 사용법/계약 위반 (`argparse`·`require` 실패) |

## 5. 시간/임계 기본값 (지시서 §7 제안값 채택, config 오버라이드 가능)

`cheap_state_poll_seconds=2`, `heartbeat_seconds=10`, `stale_suspect_seconds=90`, `source_quiet_seconds=15`, `validation_priority_after_seconds=120`, `max_heavy_verifiers=1`, `build_timeout_seconds=900`. `--config <json>` 또는 `--set key=sec`로 테스트에서 축소 가능(기본값은 SSOT 문구 그대로).

## 6. 금지 조항 → 구현 대응

| 금지 | 대응 |
|---|---|
| busy flag로 PASS 생성 | `VERIFIED_PASS`는 run-once가 receipt를 쓴 경우에만; `DEFERRED`/`QUIESCING` 등은 어디에서도 PASS로 환산하지 않음 |
| heartbeat 만료 = 완료 | stale heartbeat → `recover`가 `BLOCKED_UNKNOWN_OWNER`로 표시할 뿐 자동 해제/완료 아님 |
| quiet를 heartbeat로 리셋 | `writer-heartbeat`는 `heartbeatAtUtc`만; quiet 기준은 `lastSourceChangeAtUtc` |
| ticket 무한 큐 / 기아 | `request`는 `latest` 호환 ticket에 병합, `requestedAtUtc` 최소값 유지, `coveredRequests` 누적; `exact`는 병합 금지 |
| 새 서버/MCP/busy.lock 단독진실 | 단일 `state.json` + 상호배제 lock 파일만; 기존 lease/journal이 소유권 진실 유지 |
| 전체 소스 해시 매 폴링 | `run-once` 시작/종료 시에만 ticket `scope` 경로 해시(입력 동일성 증거) |
| 제품 Java 건드리기 | 이번 계약 diff 0 (산출: 스크립트/스킬/문서/PASTE뿐) |

## 7. watch runner 정직 선언

`watch`는 실제 프로세스로 실행되어야 runner. 이번 산출은 `run-once`/`watch` 명령 구현 + 문서이며, **상주 runner 설치·기동은 하지 않았다**(미설치 상태에서 `status`는 `runner=not_observed`/`WAITING_FOR_RUNNER`를 정직하게 보고).
