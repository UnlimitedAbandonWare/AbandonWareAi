# Living API Spec & Edge-Case Catalog

> 실무에서 널리 쓰이지만 웹에 실전 정보가 거의 없는 4대 API 규격군의
> 비공개 엣지케이스 카탈로그. 90일 마일스톤으로 실증·확장한다.
> 생성일: 2026-10-05 (KST) · 리뷰 주기: 30일 · 만료: 문서별 작성일 +90일
> 검증기: `python -B scripts/validate_api_catalog.py` (exit 0 = 구조 유효)

## 범위 및 아키텍처 격리 선언 (Architecture Reference)

본 카탈로그의 모든 명세(DPoP/RFC 9449·8693, Zero-Trust 게이트, SSE/WS 전송 규격
등)는 **외부 연동·미래 규격 참조용 Architecture Reference**다. 현재 로컬
프로토타입 런타임의 `PROTO_OPEN` 계약(`demo.auth.proto-open` — 모든 요청에
`ROLE_ADMIN` 부여, 무로그인 데모; SSOT
`docs/tri-agent-context/01_ARCHITECTURAL_INVARIANTS.md` §PROTO_OPEN 보안 경계)을
침범·변경·대체하지 않는다. 이 문서군을 근거로 런타임에 인증 게이트·DPoP
검증·역할 제한을 추가하는 것은 사용자의 명시적 "harden" 지시 전까지 금지다.

## 문서 내비게이션

| 문서 | 내용 | 트랙 |
|---|---|---|
| [00_ROADMAP_AND_GAP_MATRIX.md](00_ROADMAP_AND_GAP_MATRIX.md) | 90일 Phase 1–4 로드맵, The Gap Matrix (G01–G18) | 공통 |
| [01_AI_AGENT_PROTOCOLS.md](01_AI_AGENT_PROTOCOLS.md) | MCP stdio/Streamable HTTP, OpenAI Realtime WebRTC, Anthropic tool_use 스트리밍 | AI Agent |
| [02_ZERO_TRUST_AUTH.md](02_ZERO_TRUST_AUTH.md) | RFC 9449 DPoP (htu/nonce/jti/ath), RFC 8693 토큰 교환 바인딩 | Zero-Trust Auth |
| [03_STREAMING_TRANSPORT.md](03_STREAMING_TRANSPORT.md) | SSE 프록시 버퍼링·Last-Event-ID, WebSocket 하트비트, gRPC-Web trailers | Streaming |
| [04_RATELIMIT_IDEMPOTENCY.md](04_RATELIMIT_IDEMPOTENCY.md) | RateLimit 헤더 3계열, Retry-After 파싱, Full Jitter, Idempotency-Key | Resilience |

## 핵심 엣지케이스 치트시트

처음 읽는 사람을 위한 "이것만은 기억" 10선:

1. **MCP stdio는 개행 프레이밍이다.** `Content-Length` 붙이면 교착; stdout에 로그 섞지 마라.
2. **MCP 404 = 세션 만료.** `Mcp-Session-Id`가 죽으면 `initialize`부터 재협상한다.
3. **Realtime ephemeral token은 just-in-time.** 발급 후 SDP offer까지 지연시키면 만료 실패.
4. **ICE 재연결은 대화 상태를 옮겨주지 않는다.** 로컬 미러링 → `conversation.item.create` 재주입.
5. **barge-in 수신 즉시 재생 버퍼 플러시 + `response.cancel`.** 아니면 에코·유령 문맥.
6. **DPoP `htu`에는 query/fragment가 없다.** 프록시가 URI를 재작성하면 외부 URI 기준으로 비교.
7. **재시도할 때 proof는 재서명.** 같은 jti 재전송은 replay 거절이다.
8. **SSE가 "연결됐는데 조용하면" 버퍼링이다.** `X-Accel-Buffering: no` + `: keepalive` ≤25s.
9. **`RateLimit-Reset`(draft)은 delta-초, `X-RateLimit-Reset`은 epoch.** 헤더명으로 단위 확정.
10. **Idempotency-Key 409 = "처리 중이니 기다려".** 종료가 아니라 지연 재시도 신호.

## 역할별 가이드

### 클라이언트/SDK 개발자

1. [04_RATELIMIT_IDEMPOTENCY.md](04_RATELIMIT_IDEMPOTENCY.md) §정규화 파서 + Full Jitter — 재시도 계층 필수 구성.
2. [03_STREAMING_TRANSPORT.md](03_STREAMING_TRANSPORT.md) §Last-Event-ID — 폴리필 쓰면 수동 헤더.
3. [02_ZERO_TRUST_AUTH.md](02_ZERO_TRUST_AUTH.md) §재시도 = 재서명 — proof 재사용 버그 방지.
4. [01_AI_AGENT_PROTOCOLS.md](01_AI_AGENT_PROTOCOLS.md) §partial_json — 완성 전 도구 실행 금지.

### 게이트웨이/인프라 운영자

1. [03_STREAMING_TRANSPORT.md](03_STREAMING_TRANSPORT.md) §프록시 버퍼링 체크리스트 (Nginx/CF/ALB).
2. [03_STREAMING_TRANSPORT.md](03_STREAMING_TRANSPORT.md) §WebSocket 하트비트 — 유휴 상한 설계.
3. [02_ZERO_TRUST_AUTH.md](02_ZERO_TRUST_AUTH.md) §X-Forwarded-* 신뢰 경계 — htu 재구성 정책.
4. [04_RATELIMIT_IDEMPOTENCY.md](04_RATELIMIT_IDEMPOTENCY.md) §헤더 계열 통일 — 429 응답 규격 결정.

### 리뷰어/아키텍트

1. [00_ROADMAP_AND_GAP_MATRIX.md](00_ROADMAP_AND_GAP_MATRIX.md) §The Gap Matrix — 위험도 우선순위.
2. 각 트랙 문서 §엣지케이스 카탈로그 — 근거 등급(사실/실증/관측/추정)별 검토.
3. §미실증 항목 — Phase 2 승격 과제와 실측 필요 항목.

## 검증

구조 유효성 검증기 (로컬, stdlib 전용, 네트워크 0회):

```powershell
python -B scripts/validate_api_catalog.py
```

검사 항목: 필수 파일 실존 → UTF-8 디코딩 → 필수 섹션 헤딩 → Mermaid 블록 존재 →
문서 간 상대 링크(파일+앵커) 무결 → 종합 exit code (0=PASS, 1=FAIL, 2=실행 오류).

## 운영 정책

- **Living 문서**: 30일 리뷰, 90일 자동 만료. 만료 항목은 `stale` 표기 후 재실증/아카이브.
- **근거 등급**: 모든 주장에 `사실/실증/관측/추정` 중 하나. `추정`은 Phase 2까지 승격 의무
  (세부: [00_ROADMAP_AND_GAP_MATRIX.md](00_ROADMAP_AND_GAP_MATRIX.md) §운영 규칙).
- **비용 원칙**: 전 과정 로컬 $0. 라이브 유료 API 실측이 필요하면 별도 승인 ledger.
- **부록 후보**: Webhook HMAC 서명 검증 등 추가 규격군은 핵심 4트랙 완성 후 부록으로 편입.

## 관련 문서

- 카탈로그 외 참조: `docs/API_ROUTING_SPEC.md` (프로젝트 API 라우팅 SSOT — 본 카탈로그와 독립)
- 검증 스크립트: `scripts/validate_api_catalog.py`
