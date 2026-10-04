<!-- moved-from: AGENTS.md L457-L461 sha256=5b398e4b968923fa50305b66ee9673e6a3cc328ed56c5f90a585368e82d82222 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-APIKIT -->
외부 API 되나 확인: .\scripts\apikit.ps1 check (0원). Java 없이.
- 실패 분류 SSOT: `docs/API_ROUTING_SPEC.md` §External API failure classification — 401 `KEY_INVALID_OR_EXPIRED`, 403은 본문으로 `PLAN_GATE`/`FORBIDDEN_REGION_OR_IP` 구분(외부 API에 `auth-blocked` 미사용). 키 만료 원장 `configs/api-key-expiry.json`(env/sha8/last4/expiresAt만, 값 금지).
- 외부 API 관련 보고 첫 행: `provider | http | classification | key sha8 | cost | evidence`. mock/fixture 통과는 `NOT_RUN(실제 확인 안 함)` 표기.
<!-- END DEMO1-APIKIT -->
