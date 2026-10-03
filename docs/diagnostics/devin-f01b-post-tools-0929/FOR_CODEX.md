# FOR_CODEX — PASTE_DEVIN_F01B_POST_TOOLS (DEMO1-CODEX-POSTF01B-TRACE-R2-TOOLMAP-20260929)

Devin 산출물 2종: Codex **WP-4 (job/receipt 읽기 전용)** 와 **P1-2 (ddl-auto 소음 분류)**
에 바로 쓰는 CLI. 전부 read-only — SELECT 만, DDL/쓰기/재시도/비밀 출력 없음.

- Project Root: `C:\AbandonWare\demo-1\demo-1\src`
- exit: `0` 관측 완료 / `1` usage·IO / `3` 관측 불완전(lane 오류 — 실패 아님)
- lane: 전부 `scripts/db_agent.py` 경유. `--via auto` = file H2 → lock 시 live HTTP
  (`/api/internal/db/meta/query`, SqlGate SELECT-only) 자동 fallback.

## 1. job/receipt 조회 CLI — `scripts/f01b_job_receipt_read.py` (WP-4)

```powershell
cd C:\AbandonWare\demo-1\demo-1\src
python -B scripts/f01b_job_receipt_read.py --pretty
python -B scripts/f01b_job_receipt_read.py --recent 20 --table awx_jobs \
  --json-out data/diagnostics/f01b-trace-access-0929/job-receipt-read.json
```

- `--recent N` (기본 10, 상한 200, 0=recent 생략), `--table <name>` 단일 테이블,
  `--via auto|file|live`, `--json-out`, `--pretty`.
- 출력 `tables.<name>`: `status` PRESENT|ABSENT|ERROR, `totalRows`,
  `stateCounts`/`jobTypeCounts`/`errorCodeCounts`(사유코드)/`phaseCounts`/
  `callbackStateCounts`(컬럼 있을 때만), `recent` 마스킹 행,
  `columnsPresent`/`columnsNotSelected`, `masked` 적용된 변환 목록.
- 마스킹(allowlist 방식): `payload`/`body` 원문은 **절대 SELECT 안 함**
  (`LENGTH()` → `payload_len`/`body_len` 바이트 수만). `worker_token`/
  `callback_token` → `worker_tok_presence`/`callback_tok_presence`
  (present|absent — live lane 컬럼명 마스킹 회피 위해 `tok` 별칭).
  `owner_hash`/`admission_key`/`request_fingerprint`/`effect_key`/
  `owner_namespace`/`result_sha256`/`body_sha256` → `LEFT(col,8)` 접두사.
  정책에 없는 컬럼은 선택하지 않고 `columnsNotSelected`에 열거.
- `awx_job_results`에는 `body` 대신 `body_bytes` + `body_sha256_prefix`만.
- ABSENT = 관측된 사실(마이그레이션 전 상태). exit 0, `status:"ABSENT"`.
- observation: `complete` 모든 쿼리 성공 / `partial` 일부 lane 오류 /
  `none` 관측 불가 → exit 3.

**2026-09-29 baseline (live-http, fileLocked=true):** awx_jobs·awx_job_results·
awx_understanding_receipts 전부 PRESENT (F01B 추가 7컬럼 포함), totalRows 0,
observation=complete, exit 0.

## 2. ddl 소음 분류기 — `scripts/classify_h2_ddl_warnings.py` (P1-2 확장)

```powershell
python -B scripts/classify_h2_ddl_warnings.py --latest --json-out \
  data/diagnostics/f01b-trace-access-0929/ddl-noise.json
python -B scripts/classify_h2_ddl_warnings.py <out.log 경로>
```

- `--latest` = 최신 `var/rag-launcher/*/chat-ui-vibe-listener-*.out.log`.
- `--entities-root`(기본 main/java), `--migrations-dir`(기본
  main/resources/db/migration), `--json-out`.
- 기존 `kindCounts`/`topObjects`/`ddlStatementPrefixes` 위에 추가:
  - `manualOverlap`: 수동 마이그레이션의 awx_* 객체(테이블/인덱스/제약) 수집 →
    `entityTablesMatchingManual`(JPA @Table/@Entity ∩ awx_*),
    `manualObjectsSeenInLog`(로그 객체명 ∩ 수동 객체), `overlap`,
    `recommendation`(권고 문구 — overlap 시에도 권고만, ddl-auto 변경은 ASK_ONCE).
  - `verdict.classification`: `all-already-exists` / `has-non-already-exists`
    (진짜 에러 1건 → FIX-n 승격 신호) / `no-ddl-exception-lines`.
- 수동 객체 수집은 `awx_*` 식별자 전부라 V20260912_04/05 의
  `awx_chat_request*`/`awx_chat_run_*` 도 포함됨.

**2026-09-29 baseline (최신 launcher 로그):** alreadyExistsCount 66,
nonAlreadyExistsCount 0 → `all-already-exists`; entityTablesScanned 32,
entityTablesMatchingManual [], manualObjectsSeenInLog [] → `overlap: false`.
(PASTE 의 exceptions=132 는 count 기준 차이 가능 — 분류 결론은 동일 판정)

## 3. 테스트

```powershell
python -B scripts/test_f01b_job_receipt_read.py   # 5/5
python -B scripts/test_classify_h2_ddl_warnings.py # 5/5
```

## 4. NEVER (유지)

쓰기/DDL 적용, 재시도 버튼/액션, task_ask·callback·InMemory 활성화,
payload/body 원문 SELECT, commit/push, 운영/공유 DB, ddl-auto 모드 변경
(겹침 발견 시에도 권고만 기록 → ASK_ONCE), TRACE PASS = F01-B 승인 오독.

## 산출물 위치

- `scripts/f01b_job_receipt_read.py` + `scripts/test_f01b_job_receipt_read.py`
- `scripts/classify_h2_ddl_warnings.py` 확장 + `scripts/test_classify_h2_ddl_warnings.py`
- handoff: `data/agent-handoff/codex-autonomy/devin-f01b-post-tools-0929-9a710b46/`
