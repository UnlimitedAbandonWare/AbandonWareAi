<!-- moved-from: AGENTS.md L412-L420 sha256=72e96555533e40c760956f7148c486576c1e8b1ecccac4111e2da7e23c525a48 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-VERCEL-AI-GATEWAY-CREDIT -->
## Vercel AI Gateway / Jev 비용 메모 (agent-visible)

- Team AbandonWare AI Gateway용 AI Credit 관측값(2026-09-24): 잔액 약 USD 22 ($22.32). 잔액은 Vercel 팀 Billing의 AI Credit이며, API 키 문자열 안에 잔액이 들어 있지 않다. 인증은 AI_GATEWAY_API_KEY(또는 OIDC)다.
- Jev(typesafe-ai/jev)는 Gateway evaluation만. 로컬 GML/GLM·Ollama는 생성/임베딩. 채팅 LLM을 Jev로 교체 금지. Jev는 계획 선택 보조(decision)만.
- 프로모 Free는 2026-09-25까지(시각/TZ 미확정). 이후 종량. demo.jev.free-only=true / allow-paid=false면 무료 확인 만료·가격 불명확 시 호출 스킵+기존 경로 유지. 자동 유료 전환 금지.
- Pro 플랜은 해지/미유지 전제. Hobby+카드+AI Credit만 가정. 에이전트가 Pro 업그레이드·Auto-reload·Buy Credit를 유도하지 마라.
- 401=auth_invalid, 403+plan/Pro/ZDR 문구=plan_gate, 기타 403=permission_denied, 429=rate_limited, 5xx=upstream_error. 잔액≠키 유효. 키값 출력 금지. smoke PASS 전 제품 배선 금지. ZDR 규칙 SSOT: `docs/API_ROUTING_SPEC.md` "Vercel AI Gateway — Jev" (기본 OFF; Hobby라 ON이면 403 plan_gate).
<!-- END DEMO1-VERCEL-AI-GATEWAY-CREDIT -->
