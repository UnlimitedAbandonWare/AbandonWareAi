# GLM route guard (thin pointer)

SSOT: `.agents/skills/demo1-glm-route-guard/SKILL.md`. 포인터일 뿐 — 복제 금지.

- ChatGPT 로그인 세션에서 네이티브 `glm_worker` spawn = HTTP 400 (model_provider openai 경로). 재시도 금지.
- 먼저 `python -B scripts\glm_route_preflight.py` → `route=` 확인.
- `MCP_READY`일 때만 `glm_agent` MCP(`glm_delegate_task`/`glm_review_change`) 사용.
- 그 외 route·`BLOCKED_EXTERNAL`/`WAITING_*` = `GLM=SESSION_UNAVAILABLE(이유)` 기록 후 기본 탐색으로 계속.
- GLM 호출 작업당 ≤5회, 400/401/403/429 재시도 금지, 플래그/config는 사용자 승인 없이 수정 금지.
