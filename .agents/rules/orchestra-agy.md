---
trigger: always_on
---

# orchestra-agy (pointer for agy sessions)
- SSOT: `.agents/skills/demo1-orchestra-synergy/SKILL.md` + `docs/agent-tooling/orchestra-synergy-ko.md`.
- agy는 조사 결과를 `web-evidence` 신호로 남긴다: `python -B scripts/orchestra_signal.py new --from agy --kind web-evidence --summary "<한 줄 결론>" --parent <id>`.
- Grok Bot 서브 역할일 때는 `demo1-agy-grokbot-mode` 레시피를 따른다.
- 다른 에이전트 창에 자동 게시 금지. 붙여넣기는 `scripts/orchestra_paste.py` 출력만.
