# FOR_CODEX_SCRIPTS — DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929

Codex가 그대로 복사해서 쓰는 명령 모음. 두 트랙(F01B / TRACE)은 병합 금지 —
TRACE PASS 는 F01-B 승인이 아니고, F01B schema GATE 는 TRACE 구현이 아니다.

- Project Root: `C:\AbandonWare\demo-1\demo-1\src`
- 공통 출력 디렉터리: `data/diagnostics/f01b-trace-access-0929/` (자동 생성)
- exit 통일: 0 PASS / 1 usage·IO / 2 FAIL·missing / 3 PARTIAL / 4 safety abort
- 전부 read-only/dry-run — DDL apply 없음, 제품 플래그 변경 없음

## Track F01B (Codex DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929)

```powershell
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/f01b_schema_gate.py --mode both --json-out data/diagnostics/f01b-trace-access-0929/f01b_schema_gate.json
python -B scripts/f01b_tm_probe.py --json-out data/diagnostics/f01b-trace-access-0929/f01b_tm_probe.json
python -B scripts/f01b_admission_key_demo.py --demo
python -B scripts/f01b_evidence_pack.py
```

- GATE0 exit 2/3 → stay F01-A + evidence_needed; enqueue 금지,
  `abandonware.understanding.deferred.enabled` 플립 금지.
- GATE0 exit 0 → schema 만 준비됨. 제품 활성화는 Codex RED→GREEN 테스트 이후.
- `f01b_tm_probe.py` 는 정적 힌트다 — `runtimeProofRequired: true` 고정.
  PRIVATE_TM_SUSPECT 이면 GATE-1 제품 수정 + 런타임 failure-injection 필요.
- `--mode file` 은 live DB 없이 파일 증거만. `--h2-file <path>` 로 다른 H2
  지정 가능. `--jdbc-url-env <NAME>` 은 env 이름만 기록(값 출력 금지).
- live probe 는 `scripts/db_agent.py query --via auto` 위임
  (file H2 → live HTTP fallback, INFORMATION_SCHEMA 읽기 전용 SELECT만).

**2026-09-29 baseline 측정값 (live-http lane):**
`awx_jobs` / `awx_job_results` / `admission_key` / `request_fingerprint` /
`awx_jobs_admission_key` = 전부 **ABSENT** → `GATE0_FAIL` (exit 2).
파일 DDL(V20260912__durable_jobs.sql, V20260912_03__job_idempotency.sql)은 존재 —
파일 존재 ≠ DB 적용.
tm_probe verdict = `PRIVATE_TM_SUSPECT`
(`JdbcJobService.java` 에 `new DataSourceTransactionManager`).
(02:39 UTC 재측정 동일 — schema gate exit 2 `GATE0_FAIL` via live-http.)

## Track TRACE (Codex DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929)

```powershell
python -B scripts/trace_dock_cost_guard.py --json-out data/diagnostics/f01b-trace-access-0929/trace_dock_cost_guard.json
python -B scripts/trace_dock_a11y_scan.py --json-out data/diagnostics/f01b-trace-access-0929/trace_dock_a11y_scan.json
```

- cost_guard FAIL pre-patch = expected — Codex가 visible ON 과 `debug=true`
  조립을 분리하기 전까지. baseline만 기록하려면 `--baseline-allow-fail`.
  Codex 사전 GATE 는 strict(기본).
- a11y exit 3 `SELECTORS_ABSENT_PRE_PATCH` = dock selector 미구현 = 정상.
  구현 후 exit 0 (숨김 속성 없음) 이어야 PASS. 브라우저 실측 a11y snapshot 은
  이 스크립트 범위 밖(NOT_RUN 명시됨) — Codex 브라우저 검증 별도.

**2026-09-29 baseline 측정값:** cost_guard = `FAIL` (exit 2) —
`chat-trace-ui.js:24` `withDebugQuery` 가 `enabled()`(visible toggle) 게이트로
`debug=true` 부착 + `chat.js:6882` stream URL이 `chatTraceRequestUrl` 경유.
a11y = `PARTIAL` exit 3 `SELECTORS_ABSENT_PRE_PATCH`.

**2026-09-29 ~02:39 UTC 재측정 (NEXT contract WP1):** dock markup 이 live 에 이미
착지(`chat-ui.html:52-61,357-360`, `chat-trace-ui.js:18-19,39-40`) →
a11y = `FAIL` exit 2 `HIDDEN_ATTR_ON_DOCK` (`hidden` + `th:if=chatDiagnosticsEnabled`
게이팅). cost_guard 여전히 `FAIL` — coupling site 는 `chat.js:6892` 로 이동.
상세: `docs/diagnostics/devin-next-f01b-trace-from-source-0929/FOR_CODEX.md` §3.

## One-shot (두 트랙 한 명령 — 판정/JSON 은 트랙별 분리)

```powershell
python -B scripts/agent_access_bundle.py --skip-live-db
# live DB 포함: 옵션 없이 실행
python -B scripts/agent_access_bundle.py
# F01B 만: --tracks f01b  / TRACE 만: --tracks trace
```

overall exit = worst of children (0 = 전부 0 또는 예상 3 PARTIAL;
2 = 어느 하나 FAIL). pre-patch 기대값: `overallExit=2`
(TRACE_COST=2 가 문서화된 baseline).

## NEVER (두 트랙 공통)

InMemoryJobQueue F01-B store / task_ask / n8n callback / Autograde B /
F02 scanner / commit·push / secrets·DSN·토큰 출력 / DDL --apply /
TRACE PASS = F01-B 승인 오독 금지.

## 참조 Codex pastes (Downloads, READ ONLY)

- `C:\Users\nninn\Downloads\PASTE_CODEX_F01B_NARROW_JDBC_UNDERSTANDING_20260929.txt`
- `C:\Users\nninn\Downloads\PASTE_CODEX_TRACE_DOCK_ALWAYS_ON_20260929.txt`

## 산출물 위치

- scripts/: f01b_schema_gate.py, f01b_tm_probe.py, f01b_admission_key_demo.py,
  f01b_evidence_pack.py, trace_dock_cost_guard.py, trace_dock_a11y_scan.py,
  agent_access_bundle.py (+ 각 test_*.py companion)
- docs/diagnostics/f01b-narrow-jdbc-0929/: README.md, GATE0_PROBE.md
  (live FAIL 결과 embed), FOR_CODEX.md, decision.json
- data/diagnostics/f01b-trace-access-0929/: 스크립트별 JSON 결과
