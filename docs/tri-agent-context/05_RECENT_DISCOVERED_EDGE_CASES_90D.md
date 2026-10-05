---
doc_id: TRI-CTX-05
title: Recently Discovered Edge Cases — 90-Day Volatile
created_at: "2026-10-05T09:00:00+09:00"
expires_at: "2027-01-03T09:00:00+09:00"
ttl_days: 90
lifecycle: VOLATILE
validity_basis: "docs/api-spec-catalog 실증 카탈로그(사실/실증/관측 등급 태그) + live tree 대조 2026-10-05 KST; 벤더 규격은 인용 시점 기준이며 공식 문서 재확인 의무"
---

# 05. 최근 발견 엣지케이스 (90일 VOLATILE)

> 근거 등급: `사실`=규격/소스 명시, `실증`=로컬 재현, `관측`=필드 관찰, `추정`=미실증.
> 각 항목의 완전판은 [docs/api-spec-catalog](../api-spec-catalog/README.md) 트랙 문서에 있다.

## DPoP (RFC 9449)

- **`htu`는 query/fragment를 제외한** `scheme://host[:port]/path` — 서명 전 strip 필수.
  프록시가 경로를 재작성하면 검증자는 **외부 URI 기준**으로 재구성하고 `X-Forwarded-*`는
  신뢰 프록시 목록 내에서만 소비한다 (`사실`, T2-E1/E2).
- **`jti`는 proof마다 유일** — 네트워크 타임아웃 재시도에도 새 `jti`+`iat`로 **재서명**.
  같은 proof 재전송은 replay로 거절 (`사실`, T2-E8).
- nonce 챌지(`use_dpop_nonce`)는 재서명 후 재시도, 상한 2회. 미래 `iat` 허용치는 ≤60s로 조인다
  (`사실`+`실증`, T2-E7/E9).
- RS 호출 시 `ath`(access_token의 base64url(SHA-256)) 필수; `typ`은 정확히 `dpop+jwt`;
  대칭/none alg 거절 (`사실`, T2-E4~E6).

## OpenAI Realtime (WebRTC)

- **Ephemeral token(`client_secret.value`)은 수 분 내 만료(공식 ~1분대) 설계의 단기 자격증명** —
  just-in-time 발급, 캐시 금지, 만료 30초 전 재발급. SDP offer 지연 = 핸드셰이크 401
  (`사실`+`추정`, T1-E6).
- ICE 재연결은 대화 상태를 옮겨주지 않는다 — 로컬 미러링 후 `conversation.item.create` 재주입
  또는 `session.update` 압축 (`관측`, T1-E7).
- barge-in: `input_audio_buffer.speech_started` 수신 즉시 재생 큐 플러시 + `response.cancel` +
  `conversation.item.truncate` (`사실`, T1-E8).

## SSE 버퍼링 (Streaming Transport)

- "연결됐는데 조용하다" = 프록시 버퍼링. 방어: `X-Accel-Buffering: no` + `Content-Type:
  text/event-stream` + `Cache-Control: no-cache` + `: keepalive` 코멘트 ≤25s 주기 (`사실`,
  T3 카탈로그 §프록시 버퍼링 체크리스트 — Nginx/CF/ALB).
- `Last-Event-ID` 재연결은 링버퍼 범위 내만 유효 — 폴리필 클라이언트는 헤더 수동 부착.
- HTTP/2 유휴 타임아웃 대비 코멘트 핑 간격을 프록시 idle 제한(흔히 60s) 아래로 둔다.
- 근거: [03_STREAMING_TRANSPORT.md](../api-spec-catalog/03_STREAMING_TRANSPORT.md).

## Self-Ask rewrite bypass (에이전트 질의재작성)

- 목적: 에이전트가 사용자 승인 퀴즈(1/2/3 선택 카드)를 띄우기 전 POSITIVE/NEGATIVE/
  COUNTEREXAMPLE 자기질의 + NEUTRAL JUDGE로 `AUTO | ASK_ONCE | HOLD` 하나만 출력 —
  가역 로컬은 AUTO, 비가역만 사용자에게.
- 구현 경로: `scripts/codex_question_classifier.py --options` + `$demo1-vibe-selfask-judge-auto`
  스킬. 제품 `selfask.*`(RAG 질의분해)와 **별개 계약** — `selfask.enabled` 기본 false와 혼동 금지.

## 영수증 신선도 (Receipt freshness)

- 검증 영수증(run.json·checkpoint)은 소스/테스트 해시 핀과 묶여야 유효하다.
  관련 소스가 바뀌면 과거 PASS는 `stale/재검증 필요`이지 현재 PASS가 아니다.
- 실예: `graph-hybrid-reuse-1699f1c7/green-refresh/run.json` 41/41,
  `codex-temperature-selfask-c239331f/green-sampling/run.json` 195/195 — 둘 다 현재 해시와의
  대조 시각(2026-10-04 밤 UTC)이 기록돼 있다.
- 다른 writer의 미완 스위트를 대신 돌리거나, `prepared`/`sealed`/`DEFERRED` 상태를
  완료로 승격하지 않는다 (DEMO1-LOCAL-FIRST-RAG §parallel patch adoption).

## 관련 문서

- [04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md](04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md) — 도구 진입
- [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md) — 라이브 모델 레지스트리
- [README.md](README.md)
