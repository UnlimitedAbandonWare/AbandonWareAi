<!-- moved-from: AGENTS.md L451-L456 sha256=5baa3adde049fccbd4bc874e11e23945b3dfcb1d4b448fda52ace42b8b5b5b9b movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-CLEAN-QUARANTINE-RAILS -->
## Clean-quarantine mining & vibe AUTO rails (2026-09-29)
- Done/ASK 주장 전 필수 레일(위 Self-Ask 스킬의 CLI 미러): `scripts/agent_done_evidence_guard.py`(exit 2 = 근거 없음·인용 증거 경로 부재), `scripts/agent_vibe_auto_decision.py --action "..." --paths "a,b"`(exit 0=AUTO / 3=ASK_ONCE / 4=HOLD, foreign live lease 자동 감지).
- 격리 Codex rollout 재마이닝: `scripts/quarantine_codex_rollout_mine.py`(줄 단위 스트리밍, 136MB+ hugeline 안전, raw 텍스트를 리포트에 복사하지 않음) →アン티패턴 카탈로그 `docs/diagnostics/codex-quarantine-vibe-antipatterns-20260929.md`; 일괄 프로브 `scripts/agent_access_bundle.py --tracks vibe`.
- session-watch P14 `child-stale-no-evidence`로 부모 미청취 좀비 자식 세션을 --stale-hours 전에 info로 조기 감지한다. Handoff: `data/agent-handoff/clean-quarantine-opt-20260929/`(FOR_CODEX/FOR_DEVIN/STATUS).
<!-- END DEMO1-CLEAN-QUARANTINE-RAILS -->
