# OpenRouter Desktop routing policy (SSOT)

```yaml
capturedAt: "2026-09-24"
timezone: "Asia/Seoul"
reviewedAt: "2026-09-24"
sourceType: "official_public_documentation_and_user_directive"
reviewAfterDays: 90
runtimeEnforcement: "unchanged"
disclaimer: >
  OpenRouter limits, free-model caps, pricing, and model IDs change without notice.
  This page is a routing policy snapshot, not an entitlement or a runtime admission
  proof. If a model 429s persistently, 404s, or disappears from the catalog,
  re-check https://openrouter.ai before relying on the values below.
sourceUrls:
  - "https://openrouter.ai/stealth/space-bunny-alpha"
  - "https://openrouter.ai/docs"
```

## What this file is / is not

- OpenRouter 사용 경로는 **Desktop 앱**이다 (Settings → Providers → OpenRouter →
  API key). opencode/Cline류 **새 CLI 에이전트 설치는 이 정책의 목표가 아니다.**
- 이 문서는 에이전트/모델 라우팅 정책 SSOT다. Java 라우터·LangChain4j·
  `configs/api-routing.yaml` 프로덕션 경로를 변경하지 않는다.
- Space Bunny는 Codex/Devin/Grok/Kimi를 **대체하지 않는다.**

## Roles

| slot | owner | note |
|---|---|---|
| primary coding/fix | Codex / Devin | OpenRouter Desktop은 이 슬롯에 두지 않음 |
| research/adversarial | Grok / Kimi | 기존 역할 유지 |
| `stealth/space-bunny-alpha` | secondary investigator | pre-screen 전용 — 아래 정책 |

### `stealth/space-bunny-alpha` policy

```yaml
model: "stealth/space-bunny-alpha"
role: "experimental | long-context | multimodal | pre-screen"
priority: "secondary"
cost: 0                      # $0 preview as of capturedAt; may change
productionCritical: false
allowSecrets: false
fallbackEnabled: true
use_for: "ZIP/대형 소스/로그 1차 후보 추출, 구조 감사, 수정 타점 초안 ONLY"
not_for: "최종 패치, Gradle/빌드 확정, 비밀 포함 파일, 주력 코딩"
```

- 모델 페이지 기준: ~1M context, multimodal, tool calling, 현재 $0 preview.
- **익명 3rd-party provider**: prompts/completions may be retained by the provider
  (not for training) → 비밀·키·개인정보 포함 파일/로그는 절대 보내지 않는다.
- 업스트림 제공자는 비공개다. "MiniMax일 것" 같은 커뮤니티 추정은 **미확인
  추정**으로만 취급하고 사실로 기록하지 않는다.

## Pipeline

```text
User ask → (light) existing fast model
         → (large repo/log/video) Space Bunny pre-screen
         → Codex/Devin actual edit+test
         → (optional) Grok/Kimi counter-check
```

Space Bunny 출력은 후보·초안이다. 최종 판단·패치·테스트·커밋은 항상 Codex/Devin
쪽에서 수행한다.

## Free-model platform caps (OpenRouter)

| account state | published cap |
|---|---|
| < $10 lifetime credits purchased | ~20 RPM, ~50 free-model requests/day (UTC reset) |
| ≥ $10 lifetime credits purchased | higher daily free quota (~1000/day class) |

- 429 → exponential backoff. Failed attempts can still count toward the cap.
- 일일 무료 한도(~50회) 근처까지 소진됐으면 Bunny 남용 금지 → 바로 Codex/Devin 직행.

## Desktop 찍먹 checklist (~10 min, read-only)

1. OpenRouter Desktop → Settings → Providers → OpenRouter → API key 입력 후
   `Space Bunny Alpha` / `stealth/space-bunny-alpha` 선택
   (모델이 안 보이면 앱 완전 재시작).
2. 읽기전용 프롬프트만 사용:
   "문제 후보 5개 + 근거 경로 + 반례 + 최소수정방향 (수정/커밋 금지)".
3. `.env`, `*API_KEY*`, credentials, `.secrets/` 경로는 첨부·질의에서 명시 제외.
4. 결과는 `use_for` 범위 안에서만 소비하고, 실제 수정은 Codex/Devin으로 넘긴다.

## Agent guidance

- Bunny 출력을 그대로 패치로 채택하지 마라. 후보 → 소스 직접 확인 → 최소 패치 →
  실제 검증의 기존 체인을 따른다.
- `allowSecrets=false`, `productionCritical=false`를 임의로 바꾸지 마라.
- 모델 ID 사라짐·지속 429·가격 변경이 보이면 `disclaimer`에 따라 OpenRouter를
  재확인하고, 확인 불가면 이 페이지를 `stale`로 표시한다.
- 계정 크레딧 잔량·플랜 상세는 `unknown`이 정상 상태다. 문서 완성을 위해 콘솔
  로그인·Billing 스크레이핑·요금제 퀴즈를 요구하지 마라.
