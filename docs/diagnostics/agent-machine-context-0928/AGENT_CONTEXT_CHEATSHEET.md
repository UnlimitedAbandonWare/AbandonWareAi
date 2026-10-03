# Agent Machine Context — 5줄 치트시트 (Devin/Grok/Codex 공통)

1. 항상 Project Root(`C:\AbandonWare\demo-1\demo-1\src`)에서 실행:
   `python -B scripts/agent_machine_context.py` → stdout 단일 JSON (`--pretty`로 들여쓰기).
2. 섹션 한정: `--section paths|env|tools|db|gpu|git|skills` (기본 `all`).
   env는 이름만 — 비밀 값은 절대 출력되지 않는다(`secretValues: REDACTED_POLICY`).
3. JSON만 신뢰. 없는 값은 `evidence_needed`/`error` 필드로 표현 — 추측 경로 금지.
4. 쓰기는 위임: DB → `python -B scripts/db_agent.py apply --dry-run`/`upsert-admin`
   (`demo1-db-agent-cli`), 파일 → lease+checkpoint (`demo1-work-ledger`).
   DB `locked`(exit 3)은 서버 점유 = 정상 — kill 금지.
5. 스냅샷 저장: `--write-report docs/diagnostics/agent-machine-context-0928/last.json`
   (루트 밖 경로는 거부). 스킬: `$demo1-agent-machine-context`.
