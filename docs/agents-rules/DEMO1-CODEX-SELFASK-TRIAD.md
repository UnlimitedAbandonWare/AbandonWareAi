<!-- moved-from: AGENTS.md L438-L442 sha256=8b8cdeab8a4582f2dfbb7fa43d417fa04c69d581a904ba27e2010e4a0a7c5bfa movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-CODEX-SELFASK-TRIAD -->
## Codex Self-Ask triad (UAW 3-axis subagent fan-out)
- 판정 갈림(예상 밖 RED/GREEN·가설 2회 실패·근거 충돌·P0/P1 완료 주장 직전·ASK_ONCE/HOLD 선택 직전)이면 `.codex/agents-staged/`의 `selfask_definer`·`selfask_aliaser`·`selfask_challenger`(read-only·중첩 금지·모델 상속)를 부착: `python -B scripts/selfask_triad.py packet ...` → `spawn_agent` 축별 1회 → `... judge`가 `agent_vibe_auto_decision.py`와 같은 AUTO/ASK_ONCE/HOLD(exit 0/3/4)를 낸다. 절차·금지 조건 SSOT: `.agents/skills/demo1-codex-selfask-triad/SKILL.md` — `$demo1-vibe-selfask-judge-auto`·`$demo1-triad-deliberation`을 대체하지 않는다.
- 판정 후 journal에 `SELFASK_TRIAD AUTO|ASK_ONCE|HOLD | <reason> | <slug>` 한 줄. 오타·명확한 단일 수정·토큰 절약/중단 지시 시 부착 금지; 작업당 최대 3회. 설치: `scripts/selfask_triad_install.py`(dry-run 기본, 기존 파일 덮지 않음).
<!-- END DEMO1-CODEX-SELFASK-TRIAD -->
