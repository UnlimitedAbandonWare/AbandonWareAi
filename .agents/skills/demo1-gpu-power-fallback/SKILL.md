---
name: demo1-gpu-power-fallback
description: Use when a local Ollama/embedding/local-LLM call fails on DESKTOP-M5NOV6K (RTX 3090 power issue resolved) — classify the failure, cap local retry <=1, fall back to the api-routing.yaml API order; product main /chat routing is API/OAuth-first and app-owned
---

# demo1 GPU Power Fallback (RTX 3090)

> **2026-09 RESOLVED:** DESKTOP-M5NOV6K RTX 3090의 전력 피크/불안정 이슈는
> **별도 보조 PSU 전원 투입으로 해결됨**. 3090은 정상 운용 대상이며 로컬
> Ollama·임베딩·로컬 LLM 작업은 **3090 로컬 우선**이다 (제품 main `/chat`
> 생성 라우팅은 별개 — API·OAuth 우선·Ollama 마지막 폴백, 앱 코드 소관).
> "3090이 불안해서" API로 상시 우회하던 구 지침은 폐기 — 이 스킬은 **분류된
> 명시적 로컬 실패 1건**의 폴백 판정만 얇게 얹는다 — 새 오케스트레이터/HTTP
> 스택을 만들지 않는다.

## When

- 로컬 Ollama / embed / LLM 호출이 **실제로** timeout·무응답·빈 응답을 냈을
  때 (전원 사유 선제 회피 금지). 미래 이상 신호인
  `scripts/rtx3090_health_watch.ps1` alert id (`ollama_timeout_streak`,
  `hw_power_brake_slowdown_active`, `hw_slowdown_active`, `sw_power_cap_active`,
  `new_error_events`)도 입력이다 — 상시 우회 사유가 아니라 발생한 실패의 분류 입력.
- Codex Focus 개인기억 의미검색(`FocusMemoryService` →
  `OllamaEmbeddingModel.embedPrivate`) 등 로컬 임베딩 경로가 이렇게 죽을 때.

## Procedure

1. **Classify once** —
   `python -B scripts/gpu_power_fallback.py decide --purpose <search|asr|embed|llm> --text "<error or watch signal>" [--attempts N]`
   → `reason`(`timeout|no_response|power_limit_suspect|driver_reset|oom_suspect|model_missing|unknown`)
   + `action` + `nextRoute`를 한 번에 받는다. 분류만 `classify`, 후보만 `route`.
2. **Never hammer** — 같은 로컬 모델 총 재시도 ≤ 1, 그리고
   `power_limit_suspect`/`driver_reset`/`oom_suspect`이면 **0** (전원/드라이버/
   OOM 신호에서 재시도는 무의미하고 상태를 악화시킬 수 있다).
   `localRetryBudget`이 이 상한을 그대로 준다.
3. **Fallback order** — `configs/api-routing.yaml` `policy.order`
   (free_local → low_cost → paid_quality). 실패한 free_local 레인은 건너뛰고,
   `paid_quality`는 `AWX_AGENT_ALLOW_PAID_MODELS` kill-switch 준수
   (`=0`/`false`/`no`/`off`이면 차단, 미설정·그 외 값은 허용 —
   `$demo1-agent-api-spend-guard` SSOT). 이 순서는 에이전트·도구 계층의 폴백
   판정일 뿐이다 — 제품 main 채팅 생성의 런타임 라우팅은 앱 코드
   (`LlmRouterAspect`) 소관이며 이 스킬이 바꾸지 않는다.
4. **Log why only** — `[AWX][api-spend]` 필드에 `why=local_failover`,
   `errorClass=<reason>`, provider/model/env **이름**만. 키·토큰 값 금지
   (`docs/AGENT_API_SPEND_GUARD.md`).
5. **Report one line** — 사용자/에이전트에게 `decide`의 `report` 필드 한 줄을
   그대로 보고한다.

## Soft-auto (질문 카드 금지)

- 첫 번째로 **분류된** 로컬 실패 ⇒ API 폴백은 AUTO 진행. "로컬 재시도할까요?"
  류 질문 카드를 띄우지 않는다. 허용된 재시도 1회는 transient
  (`timeout`/`no_response`) 한정 선택지이지 확인 질문이 아니다.
- Hard stops (여전히 멈춤/거부): 시크릿 값 출력·커밋,
  `AWX_AGENT_ALLOW_PAID_MODELS=0` kill-switch 하의 paid-tier 호출, `git add -A`/`push`,
  타 세션 staged 해제, 한 건의 장애 때문에 `configs/api-routing.yaml` 개편.

## Existing seams — extend, don't rebuild

- `OllamaEmbeddingModel`: port failover + fast-fail breaker →
  `backupEmbeddingModel` 폴백이 이미 있다. `embedPrivate`는 설계상
  loopback-only — 클라우드로 연결하지 말 것.
- `FocusMemoryService.retrieve`: 임베딩 실패 시 이미
  `SCOPED_LEXICAL_LOCAL_GRAPH`로 fail-soft(`embedding_unavailable_*` reason).
  유지할 것 — 폴백 판정은 에이전트/세션 계층의 몫이다.
- 글로벌 RAG 기본 임베딩을 OpenAI로 통째 바꾸지 말 것 (spec §3 fallback
  순서는 유지, 사고 한 건으로 기본값 변경 금지).

## Incident mode (GPU3090_LOST)

- `python -B scripts/gpu_incident.py status` exit 3이면 사고 중 — 3090 레인(`ollama:11434`)을 건너뛴다.
- `decide`는 활성 플래그를 자동 감지한다(`--from-incident`는 호환 옵션): llm → `chatgpt_oauth`(구독 포함량) 1순위, 그 뒤 기존 API 순서. `gpu lost`/`GPU is lost`/`Unable to determine the device handle` 문구는 플래그 없이도 `gpu_lost`로 판정된다.
- `decide --purpose embed --from-incident` → 3060 레인(`ollama:11435`) 먼저, 그 뒤 기존 API 순서.
- `clear`는 새 probe가 OK일 때만 플래그를 해제한다(거짓 복구 금지). 이건 사고 중 폴백이지 상시 우회·기본값 변경이 아니다.

## Don't

- Ollama/GPU 무한 연속 재시도 금지. 새 HTTP 클라이언트·프로바이더
  스택·오케스트레이터 금지. GPU 클럭/PL/PSU 변경 금지 (watch는 read-only 유지,
  `$demo1-rtx3090-health-watch`). 로그/커밋에 시크릿 값 금지.
- "3090 전원/불안정"을 이유로 한 상시 API 우선·로컬 회피 금지 — 이슈 해결됨.
  API 폴백은 분류된 실패·라우팅 스펙(free→cheap→paid)·쿼터/키 부재·
  `AWX_AGENT_ALLOW_PAID_MODELS` 등 기존 spend-guard 규칙이 있을 때만.
- `conditional_local_git` / secret guard / hooks / Meta Display 캡션·Focus UI는
  이 스킬 범위 밖.

## Related

- `$demo1-rtx3090-health-watch` (관측·가설 전용), `$demo1-api-routing-inventory`,
  `$demo1-agent-api-spend-guard`, `docs/API_ROUTING_SPEC.md`,
  `configs/api-routing.yaml`, `configs/agent-api-spend-guard.yaml`.
- 폐기 문구 점검: `python -B scripts/routing_policy_wording_check.py`
  (인벤토리 `configs/retired-phrases.json`; exit 0=깨끗/3=폐기 문구/4=설정 오류).
