# Self-Ask 4단 + NEUTRAL JUDGE verdict 표 (receipt DDL AUTO / commit ASK_ONCE / foreign lease HOLD)
- card-id: selfask-judge-checklist-s41c
- kind: checklist
- status: still-true
- date: 2026-09-29 KST
- evidence: docs/diagnostics/vibe-selfask-judge-auto-checklist.md ; .agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md
- reverify: `Test-Path .agents\skills\demo1-vibe-selfask-judge-auto\SKILL.md`

## 근거
ASK 직전 루프 4단 실행 → verdict 하나만. 판정 예시 표:
- receipt DDL additive → AUTO (dry-run 통과 시)
- `git commit` 요청 → ASK_ONCE (staged set 순수 own-path 확인 질문 1개)
- task_ask 활성화 플래그 → HOLD (계약상 forbidden)
- foreign lease 덮어쓰기 → HOLD (lease 만료/해제 후 재개)
- 스킬 docs 한 줄 수정 → AUTO
