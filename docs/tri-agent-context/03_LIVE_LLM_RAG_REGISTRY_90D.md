---
doc_id: TRI-CTX-03
title: Live LLM/RAG Registry — 90-Day Volatile Snapshot
created_at: "2026-10-05T09:00:00+09:00"
expires_at: "2027-01-03T09:00:00+09:00"
ttl_days: 90
lifecycle: VOLATILE
validity_basis: "live tree 대조(application-llm.yaml, api-routing.yaml, 활성 저널/리스) 2026-10-05 KST; 스냅샷 시점의 가변 스펙이므로 적용 전 SSOT 재독 필수"
---

# 03. 라이브 LLM/RAG 레지스트리 (90일 VOLATILE)

> 스냅샷 기준: **2026-10-05 09:00 KST**. 여기 적힌 모델·순위·잔액은 전부 가변 스펙이다 —
> 배선 전 `configs/api-routing.yaml`, `application-llm.yaml`, `ollama ls`를 다시 읽는다
> (mutable-spec 정책). 코덱스 in-progress 심의 내용은 "진행 중인 보존 계약"이지 완료 선언이 아니다.

## `application-llm.yaml` 핵심 기본값 (실측)

| 키 | 기본값 | 비고 |
|---|---|---|
| `llm.provider` | `local` | Mac mini 안전 기본 |
| `llm.chat-model` | `gemma4:26b` (`LLM_CHAT_MODEL`) | 로컬 폴백 체인용 기본 — 채팅 기본은 auth-first 규칙 참조 |
| `llm.fast.model` | `qwen3.5:9b` @ fast base-url (3060 `LLM_3060_BASE_URL`) | 5s 타임아웃, temp 0.0 |
| `llm.high.model` | `gemma4:26b` (`LLM_3090_BASE_URL`, 11434) | 30s |
| `llm.judge.model` / `llm.coder.model` | `smtek/Qwen3.8-27B:Q3_K_XL` | VRAM 타이트용 Q3_K_XL |
| `llm.vision.model` | `qwen3-vl:8b` | fast 베이스 |
| `embedding.model` | `qwen3-embedding:4b` @ `127.0.0.1:11435/api/embed` | raw 2560 → `SLICE_TO_CONFIGURED_DIM` 1536 |
| `llm.ollama.enabled` | `false` (`LLM_OLLAMA_ENABLED`) | 네이티브 Ollama 경로 기본 OFF |
| `llmrouter.api-first.route-order` | `api3,gemini-pro,mistral-medium,openai-balanced,openai-premium` | `LLMROUTER_API_FIRST` 기본 false |
| `selfask.enabled` | `false` (`SELFASK_ENABLED`) | Self-Ask 생성 자체가 기본 OFF |
| `retrieval.mode` / `reranker` | `RETRIEVAL_ON` / `cross` | |

- 인터뷰 표준 기본값 심(`codex-interview-standard-6f27f6ab`)의 스코프는
  `application-llm.yaml` + `templates/chat-ui.html` + `static/js/chat-settings-bridge.js` + 관련 테스트 —
  `application-llm.yaml` 안에 `interview` 키는 없다 (면접은 `application-interview.properties`와
  `static/assets/interview/*`의 별도 디버그 표면).

## 활성 Codex 심 (2026-10-05 관측 — 진행 중, 완료 아님)

| taskId | 리스/저널 대상 | 보존 계약 (근거: DEMO1-LOCAL-FIRST-RAG) |
|---|---|---|
| `codex-temperature-selfask-c239331f` | `SelfAskPlanner.java` + 테스트 | 샘플링 선호도가 최종 엔드포인트까지 보존: `SelfAskPlanner` → `OpenAiSamplingContract.defersMergerClamp` → factory 최종 결정. focused 195/195 (green-sampling run.json). 옛 early prefix clamp 복원 금지 — live capability 판정은 별개 |
| `graph-hybrid-reuse` (`graph-hybrid-reuse-1699f1c7`) | `WeightedReciprocalRankFuser.java` + 테스트 | 하이브리드 RRF 그래프 identity/revision 보존, focused 41/41 (green-refresh run.json). fuser 수정이 같은 코퍼스 BM25 배선·그래프 리콜 향상·답변 그래프 동작을 증명하지 않는다 |
| `codex-interview-standard-6f27f6ab` | llm.yaml·chat-ui·settings-bridge·테스트 | 인터뷰 표준 기본값: 명시적 OFF/hydrate/session·init-save-zero 제어 보존 |
| `codex-rag-adoption-support-3bc2fdb6` | 스킬/규칙/라우터 도구 | 채택 규칙 정렬 — 외국 writer 보존 |
| `codex-timeout-split-ae8f6e83` | 챗 런 수명 분리 | 수용 8/12 상태 (미완 HOLD 잔여) |

## SelfAskPlanner 계약 (소스 실측)

- `@Component`, `@Qualifier("localChatModel")` ChatModel 주입 + `DynamicChatModelFactory`/
  `SelfAskProperties` ObjectProvider.
- 내부 모델 핀: `llmrouter.gemma` (BQ), `llmrouter.light` (ER), `llmrouter.api3` (RC).
- `HONEST_REWRITE_CONTRACT`: 출처 경계·화자 귀속·부정 표현 보존, 의도/행위자/피해 날조 금지 —
  재작성은 검색 쿼리만 출력.
- `selfask.use-llm-seeds`/`use-llm-followups` 기본 false.

## WeightedReciprocalRankFuser 계약 (소스 실측)

- `score(d) = Σ weight_i / (k + rank_i)`, `k` 기본 60, `retrieval.fusion.rrf.weights`로 재지정.
- 기본 가중치 키 `w_ce`(cross-encoder) / `w_bm25` / `w_sem`, 초과 소스는 1.0 폴백.
- 가중치는 **논리명** 조회 — 소스 리스트 순서와 설정을 분리하는 계약이다.
- 답변 출처 UI 연결은 TraceStore/`SafeRedactor` 경유 흔적 — 활성 심에서 identity/revision 보존 중.

## 모델 기본값 정책 — auth-first

- 채팅·에이전트 기본 = **`chatgpt-oauth:gpt-5.5`** (route id). 탐색 순서: auth(ChatGPT OAuth)
  → 외부 유료 API → 무료 → 로컬 Ollama. `gemma4:26b`는 마지막 폴백 전용.
- `chatgpt-oauth:` id는 remote-looking으로 분류되지 않아 `allow-remote-model-selection=false`에서도 선택 가능.
- auth 실패(`reasonCode=chatgpt_oauth_*`)는 같은 요청 재시도 없이 다음 순위로 1회 이동.
  SSOT: `docs/agents-rules/DEMO1-MODEL-DEFAULT-AUTH-FIRST.md`.

## Vercel AI Gateway / Jev 크레딧 (관측 2026-09-24)

- 잔액 ≈ **USD 22.32** (팀 Billing의 AI Credit — 키 문자열에 잔액 없음). 인증 `AI_GATEWAY_API_KEY` 또는 OIDC.
- Jev는 Gateway **evaluation 전용** — 채팅 LLM 교체 금지, 계획 선택 보조만.
- 프로모 Free는 2026-09-25 종료 — 이후 종량제. `demo.jev.free-only=true`/`allow-paid=false`면 무료 확인
  만료·가격 불명 시 호출 스킵. 자동 유료 전환 금지, Pro 업그레이드 유도 금지.
- 분류: 401 `auth_invalid` / 403+plan·Pro·ZDR 문구 `plan_gate` / 기타 403 `permission_denied` / 429 `rate_limited`.
  SSOT: `docs/agents-rules/DEMO1-VERCEL-AI-GATEWAY-CREDIT.md`, `docs/provider-limits/vercel-ai-gateway-limits.md`.

## Ollama 모델 잠금 (DESKTOP-M5NOV6K, RTX 3060+3090)

- SSOT: `configs/api-routing.yaml` `ollama.installed_models` + `docs/API_ROUTING_SPEC.md`; 라이브 진실 `ollama ls`.
- 역할 맵: chat `gemma4:26b·31b·12b·qwen3.6:27b·Qwen3.8-27B:Q3_K_XL·qwen3.5:9b·qwen2.5:32b·14b` /
  fast `qwen3.5:9b·gemma4:12b·qwen2.5:14b` / vision `qwen3-vl:8b` /
  judge·coder `Qwen3.8-27B:Q3_K_XL` 우선 / embed `qwen3-embedding:4b·latest·nomic-embed-text·bge-m3`.
- GPU 레인: 11434 = 3090 chat/judge/coder, 11435 = 3060 fast/embed/vision (yaml 주석 기준).
- `banned_as_defaults`(dead tag: `qwen3:8b`, `qwen3:30b`, `gemma3:27b` 등)은 Spring 기본값 배선 금지 —
  `alias_to_installed` 매핑으로만 해석. 점검: `scripts/check-model-lock.ps1`.

## 관련 문서

- [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md) — 불변 상수
- [05_RECENT_DISCOVERED_EDGE_CASES_90D.md](05_RECENT_DISCOVERED_EDGE_CASES_90D.md) — 프로토콜 엣지
- [README.md](README.md) — TTL/갱신 정책
