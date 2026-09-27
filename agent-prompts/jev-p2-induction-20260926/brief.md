# 데빈 유도 지시서 — Codex m312ain 목표의 P2 Jev 신호조절·라우팅 (소스 수정은 Codex 전담)

작성: Devin (codex-assist-runway-0926-35f76294) · 2026-09-26
상위 브리프: `agent-prompts/display-reconnect-transcript-tts-jev-20260926/brief.md` (3순위 Jev)

## 코덱스에게 붙일 유도문 (그대로 전달 — 작업 시작/인계 메시지에 첨부)

[P2 Jev — 필수 인계, P0 음성 hot path와 분리]

목적: Jev를 답변 생성기로 쓰지 말고, 확정 질문 이후의 **빠른 판단 신호 조절기 + 검색/답변 라우팅 전달기**로만 붙인다.
판정 출력(계약): RECENT_ONLY | SCOPED_RAG | WEB | HYBRID | CLARIFY
(필요 시 defer/budget_skip 같은 fail-open 사유 코드만 추가, 새 오케스트레이터 금지)

접점(기존 유지):
- ConversateApiCueService local_rules 전후
- NovaFocusAnswerService 검색 판정 후
생성(LLM 본문)·STT·PCM·재연결·audioEpoch 경로에 Jev 호출 금지.

순서: OFF → mock → SHADOW(로그만, 라우팅 미적용) → 제한 ON(키·예산 있을 때만).
키/예산/배선 없으면 JEV_NOT_CONFIGURED로 기록하고 P0/P1 완료를 막지 말 것. 무료 단정 금지.

완료 게이트(코덱스 보고 필수):
1) 확정 질문 1건에 대해 jevDecision + reasonCode가 trace에 남는지
2) SHADOW에서 기존 결정적 경로와 불일치해도 음성/Focus 성공률이 안 깨지는지(fail-open)
3) PCM chunk / beginVoice / reconnect 경로에 Jev span 0건
4) 선택 모델 requested→actual과 Jev 판정이 섞여 변조되지 않는지

P1-B(모델·API/로컬·fallback) 마무리 직후 착수. E03/E04 lease가 열려 있으면 클라 P0와 파일 충돌 없이 서버 접점만. timeout-only·GraphRAG 자동색인·push 금지.

## Devin이 확인한 현재 관측 상태 (사실 / 추정 분리)

- 사실: product 배선 0건 — `demo.jev.*`는 docs·brief·AGENTS.md에만 존재, application YAML·Java에 설정/빈 없음. Jev 식별자는 `scripts/jev_gateway_smoke.mjs`(env 이름 `AWX_JEV_ENDPOINT|MODEL|TIMEOUT_MS|ALLOW_HOST`, `AI_GATEWAY_API_KEY`)뿐.
- 사실: 마지막 live smoke(2026-09-24, 2회) HTTP 401 `authentication_error` → 분류 `auth-blocked`. `VERCEL_OIDC_TOKEN` 부재, CLI 로그인 없음 → 갱신은 사용자 Vercel 세션 필요.
- 사실: AGENTS.md 비용 메모 — Jev=Gateway evaluation/계획선택 보조만, 채팅 LLM 교체 금지, 프로모 Free 2026-09-25 종료(이후 종량), `demo.jev.free-only=true`/`allow-paid=false`면 무료 만료·가격 불명 시 호출 스킵+기존 경로 유지.
- 사실: preflight 2026-09-26 ~06:4x UTC — `sourceLeaseActiveCount=0`, blocking=0 → E03/E04 미점유. lease 재점유 여부는 Codex 작업 시작 시 자체 재확인.
- 사실: Fold 설정 UI(`index.html` `nf-jev`)는 이미 `JEV_NOT_CONFIGURED` 표시 + SHADOW/ON disabled — P1 phone-policy 쪽에서 관측 가능 상태로 배선됨. 제품 판정 경로 자체는 미배선.
- 추정: 키 존재 여부는 providers.json/env에 이름만 확인됨(값 미출력). 유효성·잔액·entitlement 미검증 — `JEV_NOT_CONFIGURED` 판정은 "배선+auth+예산 증거 부재"에 근거, "키 없음" 단정 아님.

## Devin 게이트 (Codex 패치 후 수신할 증거)

- mock 또는 SHADOW trace만 수집: `jevDecision` + `reasonCode` 필드, hot-path span 0 증거.
- 보고서에 P2 = `done(shadow|mock)` 또는 `JEV_NOT_CONFIGURED` 명시 — P0/P1과 별도 필드. 묶음 실패 금지.
- Devin 역할 한계: 소스 diff·commit·push 0건, 검증 증거 정리·반박만.
