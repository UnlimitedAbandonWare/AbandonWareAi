---
name: demo1-p6-r2-debug-kit
description: Use when Codex P6-R2 work (owned routes / vector receipts / verify triage) is stuck — 증상 → 읽기 전용 stdlib 도구 → 출력 위치 한 장 표
---

# demo1-p6-r2-debug-kit

P6-R2 작업 중 막혔을 때 쓰는 증상-도구-출력 표. 모든 도구는 stdlib-only,
읽기 전용(루프백만), 제품 소스 불변. 상세 명령 한 줄 모음:
`data/agent-handoff/devin-p6-r2/FOR_CODEX_R2.md`.

| 증상 | 돌릴 도구 | 출력 위치 |
|---|---|---|
| 404/경로 문제, "내 run만 열어야" | `python -B scripts\p6r2dbg_owner_route_matrix.py` (live는 `--base http://127.0.0.1:18180`) | `data/agent-handoff/devin-p6-r2/t01-owner-matrix.{md,json}` |
| 저장·checkpoint 의심 | `python -B scripts\p6r2dbg_receipt_fixtures.py judge-all` + `python -B scripts\check_vector_checkpoint_receipt.py` | `data/fixtures/p6r2_receipts/` + stdout |
| 빌드/테스트 실패 | `python -B scripts\p6dbg_debug_card.py <TestClass>` | debug card stdout |
| 경고·partial 의미 | `data/agent-handoff/devin-p6-r2/verify-pretriage.md` 읽기 (재실행: `classify_h2_ddl_warnings.py --latest`) | pretriage 문서 |
| "끝났다" 주장 검증 | `python -B scripts\p6r2dbg_verify_codex_r2.py verify` (없으면 PENDING exit 2) | stdout JSON |
| 장애 재현 28종 | `python -B scripts\fault_matrix_harness.py` | stdout |

규칙: HTTP 200/SSE 시작은 성공 아님 · 쿠키/토큰 저장 금지 · live는 이미 떠
있는 서버만 · `exit 2` = 입력 없음(PENDING 등), `exit 3` = 판정 실패/위반.
