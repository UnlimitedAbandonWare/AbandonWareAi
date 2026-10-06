# SECURITY_DEBT — VIBE_OPEN 동안 보류된 보안 항목 인벤토리

정책: `docs/security/VIBE_OPEN.md` · 스위치: `configs/vibe-open.yaml`.
보안 일괄 상향 지시 시 이 목록을 위에서부터 한 지시서로 처리한다.
각 항목: 파일:줄 · 관측된 로컬 런타임 상태(2026-10-06, 127.0.0.1:18180 GET) ·
개발 접근 차단 여부 · 잠글 때 할 일 1줄.

## 0. 최우선 (보안 상향 시 1순위)
- **공개 도메인 abandonwareai.kro.kr에서도 관리자 URL이 열려 있을 수 있음**
  (로컬 관측: `/admin`→404, `/debug/studio`→200, Codex 이전 관측: 자격 증명
  없이 관리자 URL 200). cloudflared 터널이 로컬과 같은 백엔드를 서빙하므로
  proto-open이 켜진 배포면 공개 인터넷에서도 동일하게 열림. 공개 쪽 실제
  요청은 본 세션에서 보내지 않음(정책). 잠글 때: 터널/배포 프로필에서
  `DEMO_AUTH_PROTO_OPEN=false` + 공개 도메인 admin 경로 1회 확인.

## 1. proto-open 스위치 본체
| 위치 | 현재 | 개발 접근 차단? | 잠글 때 할 일 |
|---|---|---|---|
| `main/java/com/example/lms/security/ChatOpenSecurityConfig.java:60` | `@Value("${demo.auth.proto-open:${DEMO_AUTH_PROTO_OPEN:false}}")` — Java 기본 false | 아니오 (실행 프로필에서 true) | 기본값 또는 실행 env를 false로 → `/api/chat/**` 외 permitAll 유지 범위 재검토 |
| `main/resources/application-meta-display.yml:161` | `proto-open: ${DEMO_AUTH_PROTO_OPEN:true}` — meta-display 프로필 기본 ON | 아니오 (현재 기동 프로필) | 프로필 yml 기본값을 false로 변경 |
| `main/java/com/example/lms/security/AdminTokenGuardInterceptor.java:87` | 동일 proto-open 주입, 기본 false | 아니오 | proto-open off 시 admin graph 경로 세션·토큰 검증 활성 확인 |
| `main/java/com/example/lms/config/AdminTokenGuardWebMvcConfig.java:15` | 인터셉터 WebMvc 등록 | 아니오 | proto-open off 시 등록 경로 재확인 |

## 2. admin·내부 경로 hasRole("ADMIN") 관문
| 위치 | 현재 | 개발 접근 차단? | 잠글 때 할 일 |
|---|---|---|---|
| `main/java/com/example/lms/config/AppSecurityConfig.java:151-177+` | `/admin/**`·`/api/admin/**`·`/api/router`·`/api/agent/report`·`/agent/db-context`·`/internal/{agent,soak,autoevolve,nn}`·`/flows`·`/v1/tasks`·`/api/learning/gemini`·`/api/internal/**`·POST `/api/train`·POST `/webhooks/channel` 등 hasRole("ADMIN") | 아니오 (proto-open 하에 AdminTokenGuardFilter 통과) — 로컬 `/admin`→404(라우트 부재), `/agent/db-context`→404 | proto-open off 후 X-Admin-Token 없이 401/403 확인·matcher 목록 최신화 |
| `main/java/com/example/lms/config/AppSecurityConfig.java:152-153` | `/api/settings` GET + PATCH `/api/settings/preferences` permitAll | 아니오 | 개인 설정 쓰기 경로 — owner 스코프·토큰 정책 결정 |
| `main/java/com/example/lms/api/internal/InternalAgentToolController.java:190-196` | `ensureEnabledAndAuthorized`: `apiEnabled` + 토큰 미제시→404, 토큰 불일치→403 `admin_token_required` | **예** (내부 도구 API만 — 화면 아님) | VIBE_OPEN 개방 제안 → `FOR_CODEX.md` F1; 잠글 때 유지할 소수 관문 후보 |
| `main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java:211` | `proto-open`/`admin-token` 인증 이름을 생성 admission 통과로 인정 | 아니오 | proto-open 제거 시 채팅 생성도 admin-token 요구로 전환 |
| `main/java/com/example/lms/api/SearchFeedbackController.java:83` | 인증 이름 blank/`proto-open`/`admin-token` 허용 | 아니오 | proto-open 제거 시 익명 피드백 차단 여부 결정 |
| `main/java/com/example/lms/api/ChatApiController.java:3281` | `"proto-open"`/`"prototype"` 인증 이름 판정 | 아니오 | proto-open off 시 판정 경로 재확인 |

## 3. CSRF·세션
| 위치 | 현재 | 개발 접근 차단? | 잠글 때 할 일 |
|---|---|---|---|
| `main/java/com/example/lms/config/AppSecurityConfig.java:108-110` | 별도 체인 `csrf.disable()` + `anyRequest().permitAll()` | 아니오 | 해당 체인 범위 확인 후 CSRF 활성 |
| `main/java/com/example/lms/security/ChatOpenSecurityConfig.java:201-227` | `CookieCsrfTokenRepository`(HttpOnly false) + `CsrfTokenRequestAttributeHandler`, `/api/chat/**` permitAll + `anyRequest().permitAll()` | 아니오 | `anyRequest`를 authenticated로, CSRF 검증 대상 확정 |
| `main/java/com/example/lms/config/ChatUiViewConfig.java:171-210` | 뷰에서 `_csrf` 메타 주입/치환 | 아니오 | CSRF 활성 시 메타 주입 경로 유지 |
| `main/resources/static/js/settings-routing.js:32-53` | 설정 POST가 CSRF 메타 헤더 사용 | 아니오 | CSRF 활성 시 토큰 갱신 동선 확인 |
| `main/resources/templates/{chat-ui,settings,index}.html` + `static/js/chat.js:226-227` | `_csrf` 메타 존재, chat.js 헤더 전송 준비됨 | 아니오 | `csrf_invalid` 실패 경로(chat.js:360,476) 검증 |

## 4. 디버그·학습 표면
| 위치 | 현재 | 개발 접근 차단? | 잠글 때 할 일 |
|---|---|---|---|
| `main/java/com/example/lms/plugin/image/debug/ImageJobDebugController.java:31-42` | `/api/diagnostics/image/**`, AdminTokenGuardInterceptor 주입됨 | 아니오 (proto-open 통과) | proto-open off 시 진단 API 토큰 필요 여부 결정 |
| `main/java/com/example/lms/learning/gemini/LearningController.java:24-34` | `/api/learning/gemini` — `requireAdmin(request)` + AppSecurityConfig hasRole | 아니오 | proto-open off 시 ADMIN 토큰 요구 확인 |
| `main/java/com/example/lms/graphdb/GraphDbManualLearningService.java:22` | 학습 서비스 (수동) | 아니오 | 호출 경로 인증 확인 |
| `main/java/com/example/lms/api/SelectionReplayRequestResolver.java:20` | replay 요청 해석기 | 아니오 | 호출 경로 인증 확인 |
| `main/java/com/example/lms/config/CustomSecurityConfig.java:41` | 추가 보안 설정 체인 | 아니오 | 체인 범위·matcher 재확인 |

## 화면·관측 요약 (2026-10-06 로컬 18180 GET 6/6)
`/chat` 200 · `/admin` 404(라우트 없음, 관문만 존재) · `/assets/display/index.html` 200 · `/assets/interview/index.html` 200 · `/debug/studio` 200 · `/agent/db-context` 404.
개발 접근을 막는 화면은 발견되지 않았다(관측 범위 기준); 내부 도구 API는
`admin_token_required` 잠금 1건(FOR_CODEX F1 참조).