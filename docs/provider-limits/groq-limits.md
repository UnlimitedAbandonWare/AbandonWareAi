# Groq limits (SSOT)

```yaml
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 90
expiresAt: "2027-01-03"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation"
accountPlan: "free (user_statement)"
accountEvidenceCapturedAt: null
accountExactLimits: "unknown"
credentialStatus: "not_checked"
reviewAfterDays: 90
runtimeEnforcement: "configurable evidence max-age; default 90 days"
disclaimer: >
  This page is a public-documentation reference, not an account entitlement, balance,
  or runtime admission proof. The user stated the current Groq plan is Free with no
  paid/Developer billing history; this is recorded as a user_statement, not a console
  verified value. Exact organization limits live in the account's Limits page.
sourceUrls:
  - "https://console.groq.com/docs/rate-limits"
```

## 에이전트 빠른 참조

| 항목 | 값 |
|---|---|
| Canonical env | `GROQ_API_KEY` (값 기록·출력 금지) |
| 엔드포인트 | `https://api.groq.com/openai/v1` — OpenAI 호환. `openai/gpt-oss-*` 모델 ID는 Groq 것이지 OpenAI 호출이 아니다 |
| 주력 모델(2026-10-05 공개 표) | `openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `qwen/qwen3.8-27b`, `whisper-large-v3(-turbo)`, `canopylabs/orpheus-*`, `meta-llama/llama-prompt-guard-2-*` |
| 한도 단위 | RPM/RPD/TPM/TPD/ASH/ASD + 계정별 **ITPM/OTPM**(input/output 분리; Limits 페이지 "X in / Y out" 표기) — **organization 레벨** |
| 캐시 | [Cached tokens](https://console.groq.com/docs/prompt-caching)는 rate limit에 미계산 |
| 플랜 | Free ↔ Developer(상한 상승·Batch·Flex). 아래 표는 Free 기준 공개값 |
| 429 | `429 Too Many Requests` + `retry-after`(429일 때만) + `x-ratelimit-*` 헤더(RPD/TPM 잔여·리셋) |
| 401/403 | 키·모델 권한 문제 — 가드 거부 코드(`groq_model_or_key_unverified` 등)와 구분해 기록 |

## What this file is / is not

- `capturedAt: 2026-09-24`은 공개 문서 확인일이다. 계정 증빙 캡처 날짜가 아니다.
- 사용자가 `Free`라고 명시했으므로 `declaredPlan: free`, `planEvidence: user_statement`,
  `declaredAt: 2026-09-24`로 기록한다. 이를 콘솔 로그인으로 재증명하라고 요구하지 않는다.
- 아래 숫자표는 Groq Console의 공개 Rate Limits 페이지에서 확인한 **요약 참고값**이다.
  사용자 콘솔의 정확한 조직 한도가 아니다. 플랜 귀속이 불명확하면 그 부분도 미확인으로 남긴다.
- `main/java/com/example/lms/agent/GroqFreeTierGuard.java`의 런타임 계정 증빙 유효기간은
  `groq.free-tier.evidence-max-age-ms`로 설정하며 기본 90일(`7776000000` ms)이다.
  환경 변수는 `GROQ_FREE_EVIDENCE_MAX_AGE_MS`이고, 허용 범위는 1~365일이다.
  범위 밖이거나 숫자가 아닌 설정은 admission을 거부한다(자동 보정하지 않음).
  이는 API 키 만료가 아니며 공개 문서의 90일 재검토 기준도 proof를 대신하지 않는다.
  `verifiedAtMs`의 실제 확인 시점은 보존하고, 정책 변경 시 `expiresAtMs`는
  `verifiedAtMs + max-age`에 맞춘다. 명시적 expiresAtMs 만료 검사도 계속 적용한다.
  Free plan, keyHash, 조직/ledger 경로, 모델별 한도 검사는 유지되며 일일 사용량 창은 24시간이다.

## Published reference summary

Groq rate limits are measured as RPM, RPD, TPM, TPD, ASH, ASD per model/endpoint.
Limits apply at the **organization level**, and exceptions may exist per account.

### Chat / text models

| Model | RPM | RPD | TPM | TPD |
|---|---|---|---|---|
| meta-llama/llama-prompt-guard-2-22m | 30 | 14.4K | 15K | 500K |
| meta-llama/llama-prompt-guard-2-86m | 30 | 14.4K | 15K | 500K |
| openai/gpt-oss-20b | 30 | 1K | 8K | 200K |
| openai/gpt-oss-120b | 30 | 1K | 8K | 200K |
| openai/gpt-oss-safeguard-20b | 5 | 1K | 2K | 200K |
| qwen/qwen3.8-27b | 30 | 1K | 8K | 200K |

`openai/gpt-oss-*` 모델은 Groq endpoint(`https://api.groq.com/openai/v1`)에서 실행되며
OpenAI API 한도를 적용하지 않는다.

### Speech (ASR)

| Model | RPM | RPD | Audio-sec/hour | Audio-sec/day |
|---|---|---|---|---|
| whisper-large-v3 | 20 | 2K | 7.2K | 28.8K |
| whisper-large-v3-turbo | 20 | 2K | 7.2K | 28.8K |

Guard 실효 한도(2026-10-01 확인, `GroqFreeTierGuard.java:67,81`):
`groq.free-tier.safety-margin` 기본 0.1과 예약 최소 `max(10, audioSeconds)`로 인해
whisper 계열 실효 상한은 RPD 2,000→**1,800회**, ASD 28,800→**25,920초**다.
8초 발화 기준 하루 실질 전사량은 RPD에 의해 **1,800회 ≈ 4시간**이다.
또한 `audioSeconds > 16`은 요청 자체가 `groq_request_dimensions_invalid`로 거부된다.

### Text-to-speech

| Model | RPM | RPD | TPM | TPD |
|---|---|---|---|---|
| canopylabs/orpheus-arabic-saudi | 10 | 100 | 1.2K | 3.6K |
| canopylabs/orpheus-v1-english | 10 | 100 | 1.2K | 3.6K |

### Notes

- 공개 표에 있는 모델이 현재 앱 allowlist(`PUBLISHED` in `GroqFreeTierGuard.java`)나
  라우팅 설정에 포함된다는 뜻은 아니다.
- 2026-10-01 기준 `PUBLISHED`에는 `whisper-large-v3-turbo`, `openai/gpt-oss-20b`,
  `openai/gpt-oss-120b`만 등록되어 있다. `qwen/qwen3.8-27b` 호출은
  `groq_model_or_key_unverified`로 거부된다(가드 등록은 Codex 인계 항목).
- `allam-2-7b`와 같이 이전 `groq-free-limits.md`에 있었으나 현재 공개 요약표에서
  확인되지 않은 모델은 `not_published_here` / `unknown`으로 남긴다.
- 2026-10-05 재캡처 기준 `llama-3.3-70b-versatile`, `llama-3.1-8b-instant` 같은 구
  llama-3.x 명명 모델은 Free 공개 표에 없다 — 과거 지시서·문서의 모델명을 그대로
  주력으로 쓰지 말고 위 표를 따른다.
- 2026-10-05 공개 문서 추가 확인: ITPM/OTPM(분리 입출력 한도, 계정별 적용)과
  "cached tokens do not count towards rate limits"가 명시되어 있다.

## Credential

- Canonical env: `GROQ_API_KEY` (값은 기록하지 않음).
- 키 이름의 존재만으로 무료 플랜·잔여량·실행 권한을 단정하지 마라.

## Agent guidance

- 이 파일이나 `README.md`가 있고 `expiresAt`(2027-01-03) 전이면 공개 자료를 재조사하지
  않고 기존 내용을 재사용할 수 있다. 경과 시 **STALE_DISCARD** —
  `scripts/provider_limits_janitor.py archive`가 `docs/provider-limits/archive/`로 격리한다.
  다만 `accountExactLimits`는 여전히 `unknown`일 수 있다.
- 429, 401, 403, 모델 접근 실패, proof 만료 등이 발생해도 사용자에게 요금제 퀴즈나
  콘솔 로그인을 요구하지 마라. 원인 코드·헤더·disabledReason을 먼저 확인한다.
