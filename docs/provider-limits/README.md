# Provider limits reference SSOT

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-09-24"
sourceType: "official_public_documentation"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  This directory is a read-only documentation SSOT, not a live entitlement or balance check.
  Exact account limits, remaining credits, and key validity are unknown unless separate
  account evidence is provided. Do not treat these pages as a runtime admission proof.
sourceUrls:
  - "https://console.groq.com/docs/rate-limits"
  - "https://ai.google.dev/gemini-api/docs/rate-limits"
  - "https://ai.google.dev/gemini-api/docs/billing"
  - "https://developers.openai.com/api/docs/guides/rate-limits"
  - "https://developers.openai.com/api/docs/guides/error-codes"
  - "https://developers.openai.com/api/docs/models/text-embedding-3-small"
  - "https://brave.com/search/api/"
  - "https://docs.tavily.com/documentation/api-credits"
  - "https://docs.tavily.com/documentation/rate-limits"
```

## Purpose

API 호출·모델 접근·비용·한도와 관련된 작업에서만 이 문서들을 참고한다.
일반 UI·빌드·로컬 코드 작업에 전체 provider 조사를 선행 조건으로 걸지 마라.

- 공개 자료만 확인하고, 확인할 수 없는 계정 정보는 `unknown`/`not_published`/`stale`으로 남긴다.
- `unknown`은 정상 상태다. 이를 로그인·요금제 퀴즈·브라우저 설치 사유로 자동 전환하지 마라.
- 90일은 프로젝트 재검토 기준이며, 공급자 보증 기간이나 런타임 증빙 유효기간이 아니다.

## When to read / refresh

- 작업이 API 호출·모델·검색·임베딩·비용·라우팅과 관련될 때만 해당 provider 문서를 연다.
- `capturedAt`이 90일 이내이고 모순 증거가 없으면 기존 자료를 재사용한다.
- 429/401/403, 모델 접근 실패, 문서 없음, 90일 경과 등이 있어도 브라우저 설치·콘솔 로그인·Billing 스크레이핑·요금제 퀴즈를 자동으로 허용하지 마라.
- 공개 페이지가 로그인 화면으로 바뀌면 미확인으로 기록하고 중단한다.

## Evidence tiers — do not mix

| Tier | Meaning | How we record it |
|---|---|---|
| public policy | 공식 공개 문서의 모델·티어·한도·단가 | `sourceType: official_public_documentation` |
| user statement | 사용자가 직접 말한 현재 플랜 | `accountPlan: <value> (user_statement)` |
| account evidence | 실제 제공된 비밀 제거 계정 증빙 | `accountEvidenceCapturedAt`, `accountExactLimits` |
| runtime observation | 해당 요청·모델·시점의 응답 | 일시적; 영구 한도로 쓰지 않는다 |
| project policy | 라우팅 분류·자체 throttle·spend-guard | 별도 문서/소스 참조 |

API 키 이름·존재, `free_local`/`low_cost`/`paid_quality` 분류, 모델의 `openai/` 접두사,
`ChatGPT` 구독 여부만으로 무료 가격·잔여 크레딧·실행 권한을 단정하지 마라.

## Important conflict with runtime guards

`main/java/com/example/lms/agent/GroqFreeTierGuard.java`는 계정 증빙에 설정 가능한
`groq.free-tier.evidence-max-age-ms`(기본 **90일**)를 적용한다. 이는 API 키 만료나
공개 문서 재검토 날짜와 별개이며, 명시적 proof 만료 검사도 유지한다.

- 공개 문서 확인만으로 Groq 증빙 JSON을 생성/갱신하거나 가드를 해제하지 마라.
  증빙 TTL 정책 변경은 명시적 사용자 요청과 소스·회귀 검증을 따른다.
- 모델 자동 활성화·라우팅 순서 변경·새 hard cap/soft cap·spend-guard 설정 변경도
  이번 범위가 아니다.
- 가드 실패가 `AUTH_MISSING`으로 묶여도 원래 `disabledReason`을 읽는다.
  계정 증빙 만료를 API 키 누락이나 로그인 필요로 단정하지 마라.

이 충돌이 남으면 보고서에 "문서 정책 정리 완료 / 런타임 admission 미변경"이라고 적는다.

## Documents

- [`groq-limits.md`](./groq-limits.md)
- [`gemini-limits.md`](./gemini-limits.md)
- [`openai-api-limits.md`](./openai-api-limits.md)
- [`brave-search-limits.md`](./brave-search-limits.md)
- [`tavily-limits.md`](./tavily-limits.md)
- [`openrouter-desktop-routing.md`](./openrouter-desktop-routing.md) — OpenRouter **Desktop** 라우팅 정책(Space Bunny = secondary pre-screen만; CLI 설치·주력 대체·비밀 전송 금지)

이전 `groq-free-limits.md`는 [`groq-limits.md`](./groq-limits.md)로 병합되었다.
숫자표 중복 없이 링크 호환성을 유지하기 위해 기존 파일은 리디렉션 stub로 남겨둔다.

## Date rules

- 공개 자료를 오늘 실제로 읽었다면 그 자료의 `capturedAt`은 `2026-09-24`로 기록할 수 있다.
- 과거 사용자 캡처나 코드의 날짜(예: `2026-09-16`)를 오늘의 계정 확인값으로 다시 날짜 찍지 마라.
- 계정 증빙이 없으면 `accountEvidenceCapturedAt`은 `null` 또는 `unknown`으로 남긴다.
- 단순 검토만 했다면 `reviewedAt`만 갱신하고 원래 `capturedAt`은 보존한다.

## Completion criteria

- 각 provider 문서와 본 README에 `capturedAt`, `timezone`, `reviewedAt`, `sourceType`,
  `sourceUrls`, `disclaimer`가 있다.
- `accountPlan`/`accountExactLimits`/`credentialStatus`가 증거 유무에 맞게 구분된다.
- 런타임 설정/소스 변경 없이 문서만 정리했다.

## Source URLs consulted (2026-09-24)

- Groq: <https://console.groq.com/docs/rate-limits>
- Gemini limits: <https://ai.google.dev/gemini-api/docs/rate-limits>
- Gemini billing: <https://ai.google.dev/gemini-api/docs/billing>
- OpenAI rate limits: <https://developers.openai.com/api/docs/guides/rate-limits>
- OpenAI error codes: <https://developers.openai.com/api/docs/guides/error-codes>
- OpenAI `text-embedding-3-small`: <https://developers.openai.com/api/docs/models/text-embedding-3-small>
- Brave Search API: <https://brave.com/search/api/>
- Tavily credits: <https://docs.tavily.com/documentation/api-credits>
- Tavily rate limits: <https://docs.tavily.com/documentation/rate-limits>
