# User brief (pasted 2026-09-26) — low guardrail DX cleanup

taskId: proto-open-spendguard-routing-dx-2e7edc49

목표 (유연·저가드레일): demo-1 로컬/착용 개발 DX를 깨지 않는 선에서만 정리.
fail-closed·관리자 잠금 강화가 목표가 아님. proto-open / 익명 chat / Meta Display
로컬 편의 유지·우선. 로컬 Ollama/RTX 우선, 유료 fanout은 플래그 없으면 자제,
시크릿 로그 금지. 새 프로젝트 루트 금지.

## 가드레일 방침 (전역)
- 낮음: PROTO_OPEN·로컬 admin 편의·`/chat` anonymous-first 유지.
- 하지 말 것: `demo.auth.proto-open` 기본값 false로 뒤집기, admin 토큰 강제화로
  Meta Display/Start-RAG 막기, CSRF/permitAll 한꺼번에 조이기.
- 해도 됨(선택·최소): 문서에 "로컬은 proto-open=true가 정상" 한 줄 명시,
  진짜 위험한 경로만 옵션 플래그로 막되 기본 열림.
- 보안 hardening PR은 별도 목표. 이번엔 DX·비용·라우팅 정합이 먼저.

## P1 — proto-open (잠그지 말고 문서·옵션화)
- 읽기만 재확인: `application-meta-display.yml` proto-open, AdminTokenGuard*,
  Start-RAG profile.
- A. 기본값 true 유지 (`DEMO_AUTH_PROTO_OPEN` 미설정 = open). false로 뒤집지 말 것.
- B. 닫고 싶을 때만 Start-RAG/env `DEMO_AUTH_PROTO_OPEN=false` (문서 한 줄).
- C. high-risk 경로 토큰 강제는 하지 않거나 `DEMO_AUTH_STRICT_INTERNAL=1` 같은
  opt-in일 때만. 기본 동작 early-return(proto-open) 유지.
- D. `MetaDisplayDbQueryController` / CSRF ignore: 동작 유지.
- 검증(느슨함): 기본 부트에서 Meta Display/`/chat` sync/익명 경로 안 깨짐;
  STRICT 미사용이면 NOT_RUN 허용.
- docs/AGENTS: "로컬 meta-display = proto-open 기본 on, 잠금은 opt-in" 한두 줄.

## P2 — AgentApiSpendGuard 실배선 (권장, soft)
- hotspot에 beforeCall 연결: `LlmRouterAspect`, `DynamicChatModelFactory`,
  (가능하면) Brave 유료 반복.
- `ApiSpendAttribution.agentModeActive()`일 때만: allow=false면 클라우드/유료
  중단, 로컬 ollama 통과. `AWX_AGENT_ALLOW_PAID_MODELS=1` 없으면
  multi-model/stale gpt-* fanout 자제.
- 일반 사용자/비-agent 트래픽은 기존처럼. spend guard로 proto/익명 UX 막지 말 것.
- 시크릿 로그 금지. 검증: agent 모드에서 paid 차단 why_code + ollama 통과.
  전체 bootSoak 불필요.

## P3 — api-routing SSOT + 임베딩 (권장, soft)
- `configs/api-routing.yaml` ↔ `main/resources/configs/api-routing.yaml`
  동기화(또는 복사 단일화). chat_wait 누락 해소.
- EmbeddingFallback: `matchIfMissing=false` 권장(명시 true만 backup).
- GPU healthy면 embedding fallback 기본 닫힘 권장하되, 기존 장애/압력 플래그
  있으면 존중 (RTX 3090 우선 정책과 맞춤).
- 검증: yaml 정합 + `check-model-lock.ps1` 가능 범위. OPENAI_API_KEY 있어도
  fallback false면 backup bean 미등록.

## 참고 (이번 필수 아님 — 손대지 말거나 메모만)
- SecurityFilterChain Order, `/internal/dataset/**` permitAll 정렬,
  llmrouter enabled 기본값, README Nova 안내 축소
- `__patch_drop__` / zip / apikey.txt 근접: 패치 타깃 제외, 삭제 강제 금지

## 제약
- 시크릿 값 출력/커밋 금지. Ollama/로컬 GPU 우선; 유료 fanout은 플래그 있을 때만.
- 루트 발명 금지. AGENTS/skills SSOT. 스킬 장문 신설 금지.
- Display 카피 건드리면 minimal caption만.
- destructive git/대량 삭제 금지. 읽기→최소 패치→포커스 테스트.

## 완료 기준 (낮춘 가드레일)
- proto-open 기본 열림 유지 + (있으면) STRICT opt-in 문서화.
  fail-closed 기본값 변경 ❌를 성공으로 치지 말 것.
- `/chat`·Meta Display 로컬 경로 리그레션 없음.
- (권장) SpendGuard가 agent 모드 hotspot에서 호출·paid 차단 증거. 없으면 NOT_RUN.
- (권장) api-routing 동기화 + embedding fallback 명시화.
- check-model-lock / focused test 그린(가능 범위).
- 시크릿 미출력, Display 장문 추가 없음, DX 파괴 없음.
