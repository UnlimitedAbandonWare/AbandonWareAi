# 06 — 조화 트리거 표 (세트 효과)

Contract: `DEMO1-DEVIN-ARTIFACT-REFINE-MEMORY-PERM-20260928`

도구는 조건 트리거로 적재한다. 아래 매핑의 `load`는 **권장 최소 세트** — 스킬은 이미 repo-local로 존재, 새 스킬 없음.

| Trigger | Load (최소 세트) |
|---|---|
| intent: display/hint break | `$demo1-meta-display-simple-caption` + `$frontend-display-debug` + `chat_session_debug_export.py` + evidence gate |
| intent: rag/zero-result | `$rag-search-diagnosis` + `$search-zero-result-recovery` + `demo1-agent-api-spend-guard` + `start-rag-reload` |
| intent: git/lock·커밋 | `conditional_local_git.py` + `git_doctor.py` + `git_staged_guard.py` + `agent_git_vibe_commit.py` |
| intent: codex waste / 성유물 정제 | `quarantine_seed_mine.py` + `grade_scan.py`(본 dir) + `demo1-tool-placement-scan` |
| intent: db/local H2 | `db_agent.py` / `db-agent.ps1` (`$demo1-db-agent-cli`, exit3=JVM 보유=잠금 정상) |
| intent: lease/충돌 | `agent_scope_lease.py check|claim` + `lease_conflict_autoflow.py` + `source_edit_session.ps1 status` |
| **before any write** | `agent_scope_lease.py claim` + `agent_work_guard.py check` + `codex_work_checkpoint.py begin` |
| **after claim Done** | `agent_code_evidence_gate.py` MUST pass — 실패면 `work_journal close`가 아니라 HOLD + `agent_done_evidence_guard.py` |
| intent: SEED/롤아웃 채굴 | `quarantine_seed_mine.py --seed … --root .` (read-only, stream-full) |
| intent: GPU/ollama lane | `ollama-status-snapshot.ps1` 사전 스냅샷 의무 + `$demo1-gpu-lane-evidence` |

## Devin orchestrate 계선

```
python -B scripts/devin_task_orchestrate.py plan --brief-file <brief>
```

주의(실측): 본 계약 브리프에 대해 `fold-wear-debug-record` 플레이북으로 미스라우트됨 — orchestrate plan은 참고 의견일 뿐 실행 권한·스킬 선택을 대체하지 않음. 매칭 실패/오매칭 시 직접 진행 + `$demo1-tool-placement-scan` 자문.

## 세트 효과

- 단건 사용 → `tool-memory growth append`(승인 시) 누적 → 실패 태그 빈도 상위는 `grade_scan` 재실행 시 C/D 재등급 후보, 성공 상위는 S 승격 후보.
- before-write/after-done 두 트리거는 **항상 발동** — 조건부 적재에서 유일한 무조건 구간.
