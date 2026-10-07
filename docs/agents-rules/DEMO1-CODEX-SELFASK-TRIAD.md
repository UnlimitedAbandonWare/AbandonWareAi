<!-- moved-from: AGENTS.md L446-L450 sha256=8b8cdeab8a4582f2dfbb7fa43d417fa04c69d581a904ba27e2010e4a0a7c5bfa movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-CODEX-SELFASK-TRIAD -->
## Codex Self-Ask triad (UAW 3-axis subagent fan-out)
- 판정 갈림(예상 밖 RED/GREEN·가설 2회 실패·근거 충돌·P0/P1 완료 주장 직전·ASK_ONCE/HOLD 선택 직전)이면 `.codex/agents-staged/`의 `selfask_definer`·`selfask_aliaser`·`selfask_challenger`(read-only·중첩 금지·모델 상속)를 부착: `python -B scripts/selfask_triad.py packet ...` → `spawn_agent` 축별 1회 → `... judge`는 AUTO/ASK_ONCE/HOLD(exit 0/3/4) 형식 추천을 낸다. 내용·반례·baseline과 최신 허용 범위의 최종 판정은 부모가 한다. 절차·금지 조건 SSOT: `.agents/skills/demo1-codex-selfask-triad/SKILL.md` — `$demo1-vibe-selfask-judge-auto`·`$demo1-triad-deliberation`을 대체하지 않는다.
- 판정 후 journal에 `SELFASK_TRIAD AUTO|ASK_ONCE|HOLD | <reason> | <slug>` 한 줄. 오타·명확한 단일 수정·토큰 절약/중단 지시 시 부착 금지; 작업당 최대 3회. 설치: `scripts/selfask_triad_install.py`(dry-run 기본, 기존 파일 덮지 않음).
- 질의재작성/바이패스: `selfask_triad.py rewrite` — `--trigger` 없이 존재하는 `--paths`면 helper가 `bypass=strong_evidence`+AUTO를 표시하지만 내용 검증·권한 증거가 아니다; 0-hit·예상 밖 RED·모호/다중 홉 시 3축 재작성 질의 생성(오프라인). 원 질문 조건을 보존해 해당 축에 전달하고 4관점 판단을 새 모델 호출 수로 해석하지 않는다.
<!-- END DEMO1-CODEX-SELFASK-TRIAD -->
