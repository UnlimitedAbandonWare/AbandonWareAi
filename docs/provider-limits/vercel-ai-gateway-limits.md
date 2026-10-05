# Vercel AI Gateway limits (SSOT)

```yaml
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 90
expiresAt: "2027-01-03"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation"
accountPlan: "Hobby + purchased AI Gateway Credits (user_statement)"
accountEvidenceCapturedAt: "2026-09-24 (balance observation, not re-verified)"
accountExactLimits: "unknown"
remainingCredits: "~USD 22.32 observed 2026-09-24 (stale observation, not current proof)"
credentialStatus: "present_in_store_not_revalidated"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This page records public Vercel AI Gateway pricing/credit mechanics as of
  2026-10-05 plus this project's usage policy. The ~$22.32 balance is a
  2026-09-24 dashboard observation, not a live entitlement. AI_GATEWAY_API_KEY
  presence does not prove remaining credits or call permission.
sourceUrls:
  - "https://vercel.com/docs/ai-gateway/pricing"
  - "https://vercel.com/docs/ai-gateway/rate-limits"
  - "https://vercel.com/docs/ai-gateway/faq"
  - "https://vercel.com/docs/ai-gateway"
```

## What this file is / is not

- 공식 공개 문서의 요금·크레딧 구조만 기록한다. 팀 Billing 실시간 잔액·유효
  크레딧은 `unknown`이다(콘솔 로그인·Billing 스크레이핑을 자동 허용하지 않는다).
- 프로젝트 정책 SSOT 는 `docs/agents-rules/DEMO1-VERCEL-AI-GATEWAY-CREDIT.md`와
  `docs/API_ROUTING_SPEC.md` "Vercel AI Gateway — Jev" 절이다.

## Public pricing / credit mechanics (checked 2026-10-05)

| Item | Free tier | Paid tier |
|---|---|---|
| Monthly credit | $5/month included | none — pay as you go via purchased **AI Gateway Credits** |
| Model access | free-tier-eligible models | all available models |
| Token markup | zero (provider list price) | zero, including BYOK |
| Rate limits | lower per-model limits | none from AI Gateway; provider limits still apply |
| Credit expiry | monthly refresh | purchased credits expire **1 year** after purchase |

- Purchasing AI Gateway Credits moves the team to the paid tier; the monthly
  free credit then no longer applies.
- Balance APIs: `GET /v1/credits` (balance + lifetime spend),
  `GET /v1/generation` (per-request cost). Rejection classes: `429` = rate
  limit (retry after short wait), `402` + `quota_for_entity_exceeded` =
  budget/credits exhausted.
- BYOK: provider-key requests are metered separately; a failed BYOK request may
  fall back to system credentials and then bills AI Gateway Credits.

## Project policy (Auth codex credit — Jev only)

- Env: `AI_GATEWAY_API_KEY` (in providers store) or OIDC. Key value is never
  recorded or printed.
- Jev (typesafe-ai/jev) uses the Gateway **for evaluation/decision-assist only**
  (`docs/agents-rules/DEMO1-VERCEL-AI-GATEWAY-CREDIT.md`). Local GML/GLM·Ollama
  do generation/embedding. Replacing a chat LLM with Jev is forbidden.
- Spend ledger: `scripts/apikit/jev_ledger.py` requires `AWX_AGENT_SESSION`;
  a live send without it is refused `budget_refused:session-missing`.
- `demo.jev.free-only=true` / `allow-paid=false` skips calls when free status
  is expired or pricing is unclear — **no automatic paid-tier conversion**.
- Pro plan assumed terminated/not maintained. Agents must not induce Pro
  upgrade, Auto-reload, or Buy Credit.
- Error classification: `401`=auth_invalid · `403`+plan/Pro/ZDR wording=
  plan_gate · other `403`=permission_denied · `429`=rate_limited ·
  `5xx`=upstream_error. Balance ≠ key validity.

## Agent guidance

- API 관련 작업에서만 이 문서를 읽는다.
- `capturedAt`이 90일 이내이고 공식 정책 변경 증거가 없으면 재사용한다.
  만료 감지·갱신: `python -B scripts/verify_provider_limits_freshness.py --check`.
- 잔액 갱신이 필요하면 공개 엔드포인트(`GET /v1/credits`) 결과만 기록하고,
  콘솔 로그인·결제 수단 등록·플랜 변경은 사용자의 명시적 지시 없이 하지 않는다.
