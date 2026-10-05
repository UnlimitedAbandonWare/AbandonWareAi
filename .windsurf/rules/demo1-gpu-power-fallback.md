---
trigger: model_decision
description: "로컬 GPU나 Ollama가 실패한 뒤 API 폴백을 고를 때. 3090 전원 문제는 해결된 상태다"
---
# demo-1 GPU power fallback (pointer)

- 전문·절차·하드스톱: `$demo1-gpu-power-fallback` (`.agents/skills/demo1-gpu-power-fallback/SKILL.md`); 판정 도구 `python -B scripts/gpu_power_fallback.py decide`.
- 로컬 GPU 작업(임베딩·로컬 LLM): 3090 로컬 우선 — 분류된 실패 시에만 재시도 ≤1(전원/드라이버/OOM 신호면 0) → `configs/api-routing.yaml` API 순서 폴백.
- 제품 main `/chat` 생성: API·OAuth 우선, 로컬 Ollama 마지막 폴백(사용자 결정 2026-10-02) — 런타임 순서 변경은 Codex 제품 작업, 이 룰이 값을 고치지 않음.
- 3090 사고 플래그(`var/incident/gpu.json`, `scripts/gpu_incident.py status` exit 3)가 서 있으면 `decide`가 자동 반영(`--from-incident` 호환) — llm은 `chatgpt_oauth` 1순위, embed는 `ollama:11435` 우선, 로컬 재시도 0 (`docs/agents-rules/DEMO1-RTX3090-WATCH.md` INCIDENT 모드).
