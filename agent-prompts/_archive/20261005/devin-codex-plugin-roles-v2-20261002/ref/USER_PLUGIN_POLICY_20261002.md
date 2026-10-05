# 사용자 원문 — Codex 플러그인 역할 정책 (2026-10-02 10:03 KST, 그대로 보존)

사용 플러그인: Exa, Superpowers, 컴퓨터(computer-use), 브라우저(browser), Sites, Plugin Management, Data(data-analytics), Visualize, glm_worker(subagent), AWX Control Tower, AWX Control Tower(복구), Meta Wearables Webapp, Supabase, GitHub, Vercel

이번 작업에서 플러그인은 전부 쓰지 말고, 필요한 것만 아래 역할 안에서 써 줘.
공통: 현재 로컬 소스(C:\AbandonWare\demo-1\demo-1\src)가 최우선 근거이고, 플러그인 결과는 보조 증거야. 안 쓴 플러그인은 NOT_USED, 못 돌린 검증은 NOT_RUN으로 보고해.

1. Superpowers — 작업 방식
- 버그·증상 작업은 systematic-debugging으로 시작해. 증상(영상·로그·리포트)과 현재 소스 경로를 먼저 연결해.
- 원인 후보는 한 번에 하나만 잡고, 수정 전에 재현되는 실패 테스트나 구조 탐침(RED)부터 만들어.
- 기능 추가·리팩터도 같은 순서: 기존 seam 찾기 → RED → 최소 수정 → GREEN.
- production code 수정 후에는 해당 회귀 테스트 + 관련 focused 테스트 + Verify-RAG를 새로 실행하고 verification-before-completion 기준으로 보고해. 전체 suite·전역 clean은 내가 따로 요청할 때만.
- 기존 구현을 우회하는 중복 서비스·새 계층은 만들지 마.

2. Browser — 실제 화면 재현
- UI·스트리밍·인증에 영향이 있는 변경이면 새 세션(새 대화)에서 재현해. 기본 세트:
  - 짧은 인사(안녕?) → 본문 표시 / HOLD / 중간 끊김 여부와 첫 본문까지 걸린 시간
  - 일반 질문 → backend_unavailable 같은 실패 reasonCode 여부
  - 이번 작업이 건드린 화면·기능의 핵심 경로 1~2개
- 관리자 로그인·잘못된 계정 차단·로그아웃 후 차단은 관찰만 기록해. PROTO_OPEN 유지라 완료 기준이 아니고, 보안 강화 작업도 하지 마.
- UI 문구만 보지 말고 HTTP 상태·서버 reasonCode·요청 ID와 연결해. HTTP 200이나 SSE 시작은 답변 성공이 아니야.
- 저장된 인증 상태를 불러온 것만으로 로그인 성공을 증명하지 마. 인증 상태·trace·쿠키·토큰은 Git이나 공개 자료에 남기지 마.

3. GitHub — 보조 증거
- 먼저 로컬 git status·HEAD·branch를 확인하고 원격(AbandonWareAi) SHA와 비교해.
- 같은 SHA거나 변경 관계가 명확할 때만 GitHub의 commit/diff/CI를 보조 증거로 써. 오래된 snapshot을 로컬 현재 소스보다 우선하지 마.
- 이번 작업 대상 파일·모듈의 최근 diff부터 봐. (예: RagControl, ChatWorkflow, SecurityConfig, AdminTokenGuard, chat.js 중 작업과 관련된 것)
- 승인 없이 commit·push·merge·branch 삭제·PR 생성 하지 마.

4. Exa — 공식 규격 확인 전용
- 소스만으로 확정할 수 없는 외부 규격만 확인해: 프레임워크 동작(Spring Security 세션/CSRF 등), 도구 사용법(Playwright 로그인·trace 등), provider API 규격·에러코드.
- 우선 도메인은 공식 문서로 제한해: docs.spring.io, playwright.dev, docs.github.com, docs.langchain4j.dev, vercel.com/docs, 실제 쓰는 provider 문서.
- 블로그 예제를 Java 17·Spring Boot 3.3.4·LangChain4j 1.0.1 코드에 그대로 옮기지 마. 확인 날짜와 적용 버전을 짧게 남겨.

5. AWX Control Tower — 빌드 실패 분류
- compileJava·test·서버 기동이 실제로 실패했을 때만, 정제된 빌드 로그 경로를 build_error_mine에 넘겨 실패 유형을 분류해.
- AWX 판정은 원인 후보를 좁히는 증거로만 쓰고, 원본 stack trace와 현재 코드 경로로 다시 확인해.
- 기본 AWX가 정상이면 복구용 AWX는 쓰지 마.

6. Computer — 최후 수단
- Browser로 못 여는 localhost 화면, Windows 콘솔, 로컬 전용 UI를 실제로 조작해야 할 때만 써.
- 소스 탐색·코드 수정·테스트 실행에는 쓰지 마. Git·파일·셸 도구가 우선이야.

7. glm_worker (GLM 5.2 · Vercel AI Gateway) — 저비용 읽기 보조 + 반박 검토
- 용도는 두 가지뿐이야.
  ① 넓은 코드 탐색·로그 정리처럼 읽기만 많은 일
  ② 1차 수정 후 반박 검토: "증상을 숨긴 패치인가, 원인을 없앴나", "권한·범위를 지나치게 넓히지 않았나", "검증 불필요와 검증 실패를 혼동하지 않았나", "mock·HTTP 200을 성공으로 착각하지 않았나"
- 코드 쓰기·설정 변경·비밀 값·되돌릴 수 없는 결정은 위임하지 마. 최종 판단과 모든 수정은 네가 해.
- 경로 순서: native glm_worker → 안 되면 glm_agent MCP의 glm_delegate_task(읽기 전용) → 둘 다 안 되면 GLM 없이 직접 처리하고 "GLM=SESSION_UNAVAILABLE(이유)"를 기록해.
- 시작 전에 키는 존재 여부만 boolean으로 확인해(glmKeyPresent=true|false). 값은 보지도, 출력하지도 마.
- 위임할 때마다 랜덤 deliveryMarker를 넣고, 첫 줄에 task_received=<marker>가 돌아와야 유효로 쳐. HTTP 200이나 그럴듯한 텍스트만으로는 무효야.
- 호출은 세션당 최대 3회. 400/401/403/429·크레딧 오류면 그 세션에서는 재시도하지 말고, 보고서 맨 위 "외부 API" 줄에 HTTP 코드·메시지(키 제외)·원인을 적어. "ChatGPT 계정으로 zai/glm-5.2 미지원" 400은 provider가 openai로 잡힌 설정 문제라 재시도해도 소용없어.
- glm_worker가 동의했다고 검증 성공이 아니야. 지적 사항은 네가 코드·테스트로 직접 확인한 것만 채택해.

8. Vercel — AI Gateway / Jev 관련일 때만
- 허용: AI_GATEWAY_API_KEY·프로젝트 env 존재 여부 확인, vercel env pull 또는 OIDC/로그인 상태 점검, 크레딧·쿼터·모델 가용성(zai/glm-5.2, typesafe-ai/jev 등) 확인, /v1/evaluate 등 공식 문서·대시보드 확인.
- 금지: 키·토큰 값을 채팅/로그/Git에 붙이기, 배포·프로젝트 설정 변경, 도메인/DNS/빌드 파이프라인 수정, 작업과 무관한 Vercel 앱 손보기, npx vercel ai-gateway setup처럼 Codex 설정을 통째로 덮어쓰는 명령.
- smoke가 401/403이면 "auth-blocked + 시도한 인증 경로"만 보고하고, 내가 재로그인하거나 키를 갱신하기 전까지 live ON 배선으로 넘어가지 마. env pull로 받은 파일은 커밋하지 마.

9. 그 밖의 플러그인 (Sites, Plugin Management, Data, Visualize, Supabase, Meta Wearables 등)
- 이번 작업에 직접 필요할 때만 써. 쓸 때는 왜 쓰는지 한 줄 남기고 읽기 위주로. 설정·배포·데이터 변경은 내 승인 후에만.
