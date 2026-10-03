# GATE0_PROBE — F01-B narrow JDBC schema gate

- contractId: `DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929`
- generatedAtUtc: 2026-09-29T02:39:25+00:00
- mode: `both`
- verdict: **GATE0_FAIL**
- productEnablement: `blocked_until_codex`

## FILE evidence

- `durableJobs` C:\AbandonWare\demo-1\demo-1\src\main\resources\db\migration\V20260912__durable_jobs.sql: exists=True sha256=b575731f24154875… mtimeUtc=2026-09-12T02:49:59+00:00
- `jobIdempotency` C:\AbandonWare\demo-1\demo-1\src\main\resources\db\migration\V20260912_03__job_idempotency.sql: exists=True sha256=784b9c766145cc54… mtimeUtc=2026-09-12T05:32:39+00:00
### checks
- awx_jobs_create: True
- awx_job_results_create: True
- admission_key_column: True
- request_fingerprint_column: True
- unique_admission_key_index: True

## LIVE probe

- reachable: True via=live-http error=None
- tables: {'awx_jobs': 'ABSENT', 'awx_job_results': 'ABSENT'}
- columns: {'admission_key': 'ABSENT', 'request_fingerprint': 'ABSENT'}
- index: {'awx_jobs_admission_key': 'ABSENT'}

## NEVER

- DDL apply 없음 (read-only INFORMATION_SCHEMA only)
- `abandonware.understanding.deferred.enabled` 플립 금지
- GATE0 exit 2/3 → Codex stay F01-A + evidence_needed
