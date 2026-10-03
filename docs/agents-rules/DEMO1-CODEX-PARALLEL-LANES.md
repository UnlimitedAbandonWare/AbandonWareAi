<!-- moved-from: AGENTS.md L463-L467 sha256=87bf080f055c0dbced06abc457fce1c5b4c6c938d31c14bc8aeb59bbb40ab1fb movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-CODEX-PARALLEL-LANES -->
## Codex parallel lanes (multi-chat same-tree operation)
- 여러 Codex/Devin 채팅을 일부러 동시에 돌릴 때: `$demo1-codex-parallel-lanes` — `codex_lane_plan.py`(레인 분할, 쓰기 범위 비겹침 보장) → 채팅마다 `lane-<id>.txt` 머리말 → 각 채팅 첫 수정 전 `codex_parallel_preflight.py --goal-key <key> --lane <plan/id> --scope <paths>`(OWNER|VERIFIER|TAKEOVER|WAIT, UNCLAIMED_EDIT·STALE_CLAIM dry-run) → claim + writer-begin + `codex_lane_quota.py` 쿼터 → `codex_lane_integrate.py check` 통합 게이트.
- 새 잠금/데몬 없음: 기존 journal·lease·checkpoint 위의 읽기 기반 판정 + 레인 계획 + 쿼터 토큰뿐. 상세: `docs/agent-tooling/codex-parallel-quickstart-ko.md`.
<!-- END DEMO1-CODEX-PARALLEL-LANES -->
