---
name: demo1-gpu-power-fallback
description: Use when local Ollama/embedding/LLM calls time out, go silent, or RTX 3090 power-limit is suspected on DESKTOP-M5NOV6K — classify once, cap local retry <=1, fall back to the api-routing.yaml API order
---

# demo1 GPU Power Fallback (RTX 3090)

DESKTOP-M5NOV6K의 RTX 3090은 전력 피크(위이잉) 시 power limit / driver 이벤트로
로컬 Ollama·임베딩 호출이 timeout·무응답이 된다. 해법은 "GPU가 살 때까지
재시도"가 아니라 **실패 분류 + API 폴백**이다. 이 스킬은 판정 절차만 얇게
얹는다 — 새 오케스트레이터/HTTP 스택을 만들지 않는다.

## When

- 로컬 Ollama / embed / LLM 호출이 timeout·무응답·빈 응답을 내거나, 호스트가
  전력 제한 상태로 의심될 때. `scripts/rtx3090_health_watch.ps1` alert id
  (`ollama_timeout_streak`, `hw_power_brake_slowdown_active`,
  `hw_slowdown_active`, `sw_power_cap_active`, `new_error_events`)도 입력이다.
- Codex Focus 개인기억 의미검색(`FocusMemoryService` →
  `OllamaEmbeddingModel.embedPrivate`) 등 로컬 임베딩 경로가 이렇게 죽을 때.

## Procedure

1. **Classify once** —
   `python -B scripts/gpu_power_fallback.py decide --purpose <search|asr|embed|llm> --text "<error or watch signal>" [--attempts N]`
   → `reason`(`timeout|no_response|power_limit_suspect|driver_reset|oom_suspect|model_missing|unknown`)
   + `action` + `nextRoute`를 한 번에 받는다. 분류만 `classify`, 후보만 `route`.
2. **Never hammer** — 같은 로컬 모델 총 재시도 ≤ 1, 그리고
   `power_limit_suspect`/`driver_reset`/`oom_suspect`이면 **0** (재시도가
   다음 스파이크를 유발한다). `localRetryBudget`이 이 상한을 그대로 준다.
3. **Fallback order** — `configs/api-routing.yaml` `policy.order`
   (free_local → low_cost → paid_quality). 실패한 free_local 레인은 건너뛰고,
   `paid_quality`는 `AWX_AGENT_ALLOW_PAID_MODELS` 게이트 유지
   (`$demo1-agent-api-spend-guard`).
4. **Log why only** — `[AWX][api-spend]` 필드에 `why=local_failover`,
   `errorClass=<reason>`, provider/model/env **이름**만. 키·토큰 값 금지
   (`docs/AGENT_API_SPEND_GUARD.md`).
5. **Report one line** — 사용자/에이전트에게
   `로컬 GPU 불안정(<reason>) → API 폴백: <route>` 한 줄 (`decide`의
   `report` 필드 그대로).

## Soft-auto (질문 카드 금지)

- 첫 번째로 **분류된** 로컬 실패 ⇒ API 폴백은 AUTO 진행. "로컬 재시도할까요?"
  류 질문 카드를 띄우지 않는다. 허용된 재시도 1회는 transient
  (`timeout`/`no_response`) 한정 선택지이지 확인 질문이 아니다.
- Hard stops (여전히 멈춤/거부): 시크릿 값 출력·커밋,
  `AWX_AGENT_ALLOW_PAID_MODELS` 없는 paid-tier 호출, `git add -A`/`push`,
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

## Don't

- Ollama/GPU 연속 재시도로 전력 피크 유발 금지. 새 HTTP 클라이언트·프로바이더
  스택·오케스트레이터 금지. GPU 클럭/PL/PSU 변경 금지 (watch는 read-only 유지,
  `$demo1-rtx3090-health-watch`). 로그/커밋에 시크릿 값 금지.
- `conditional_local_git` / secret guard / hooks / Meta Display 캡션·Focus UI는
  이 스킬 범위 밖.

## Related

- `$demo1-rtx3090-health-watch` (관측·가설 전용), `$demo1-api-routing-inventory`,
  `$demo1-agent-api-spend-guard`, `docs/API_ROUTING_SPEC.md`,
  `configs/api-routing.yaml`, `configs/agent-api-spend-guard.yaml`.
