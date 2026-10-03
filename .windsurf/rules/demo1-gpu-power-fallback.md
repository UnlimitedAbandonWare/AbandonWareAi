---
trigger: model_decision
description: "로컬 GPU나 Ollama가 실패한 뒤 API 폴백을 고를 때. 3090 전원 문제는 해결된 상태다"
---
# demo-1 GPU power fallback (pointer)

- RTX 3090 전원 이슈는 **해결됨**(2026-09, 보조 PSU 전원 투입) — 3090 로컬 우선, "3090 불안정" 사유의 상시 API 우회 금지. **명시적 로컬 실패**(Ollama/임베딩/LLM timeout·무응답) 또는 watch alert `ollama_timeout_streak`/`hw_power_brake*`/`hw_slowdown*`/`new_error_events` 발생 시에만: 로컬 재시도 ≤1 (`power_limit_suspect`/`driver_reset`/`oom_suspect`이면 0), `configs/api-routing.yaml` 순서(free_local→low_cost→paid_quality)로 API 폴백. 절차·하드스톱 전문: `$demo1-gpu-power-fallback` (`.agents/skills/demo1-gpu-power-fallback/SKILL.md`); 판정 도구 `python -B scripts/gpu_power_fallback.py decide`.
- Soft-auto: 첫 분류된 로컬 실패 → API 폴백은 질문 카드 없이 AUTO. Hard stop: 시크릿 값 출력/커밋, `AWX_AGENT_ALLOW_PAID_MODELS=0` kill-switch 하의 paid 모델(에이전트 비용 게이트 SSOT `$demo1-agent-api-spend-guard`), `add -A`/push, 라우팅 YAML 임의 개편, GPU 클럭/PL/PSU 변경.
- `demo1-api-routing-inventory`/`demo1-agent-api-spend-guard`/`demo1-rtx3090-health-watch`와 역할 분리: 이 룰은 폴백 판정 포인터만 담는다.
