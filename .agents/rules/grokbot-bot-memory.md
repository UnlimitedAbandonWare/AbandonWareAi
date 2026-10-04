# Grok Bot bot memory pointer — SSOT: docs/GROKBOT_BOT_RULES.md · episodes: data/agent-handoff/grokbot/bot_episodes.jsonl
- R1 말투: 한국어 쉬운 존댓말, 결론 첫 줄, 중요한 답 끝 `한 줄:`.
- R13 비용(화력 위주): Codex 크레딧 → 외부 유료 API → 무료 → 로컬 Ollama 맨 마지막; 지시서마다 라이브 호출 상한; 401/403/429 재시도 없음.
- R12b AUTO 기본 + R20 범위 예산: 되돌릴 수 있는 작은 확장(≤3파일·≤300줄)은 AUTO + SCOPE_EXPAND 기록.
- 전체 규칙은 docs/GROKBOT_BOT_RULES.md; 재가져오기 `python -B scripts/grokbot_bot_import.py --src var/grokbot-export-20261003 --apply`.
