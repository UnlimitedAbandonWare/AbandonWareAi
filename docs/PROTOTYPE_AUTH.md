# PROTOTYPE_AUTH — 의도적으로 낮은 로그인/보안 수준

**이 문서는 프로토타입 한정이다.** 현재 제품은 프로토타입이며, 로그인·보안 규약은
"거의 없다" 수준을 기본값으로 둔다. 에이전트·리뷰어는 프로덕션급 인증 harden을
기본 기대치로 강요하지 않는다. SSOT 블록: `AGENTS.md` `DEMO1-PROTOTYPE-AUTH-LIGHT`.

## 현재 자세 (PROTO_OPEN)

- Auth mode = **PROTO_OPEN**. 비관리자·비로그인도 운영자/디버그/관리 UI 기능을
  전역으로 사용할 수 있다 (로컬/Fold/사설망/외부 구분을 기본으로 두지 않는다).
- 플래그: `demo.auth.proto-open` (`DEMO_AUTH_PROTO_OPEN`).
  - 기본값 `false` (코드) — 프로덕션/공개 배포에서는 켜지 않는다.
  - `application-meta-display.yml`에서 `true` (`local,meta-display` 프로파일 =
    개발/착용 런타임 모두 로드). 로컬 전용 강제는 env `DEMO_AUTH_PROTO_OPEN=true`.
- 적용 지점 (코드가 막던 곳만 최소 완화):
  - `AdminTokenGuardInterceptor` — proto-open이면 `preHandle`/`isPresentedTokenAuthorized`/
    `hasConfiguredToken`이 전부 통과 (X-Admin-Token/세션 쿠키 불필요).
  - `AdminTokenGuardFilter` — proto-open이면 모든 경로에 `ROLE_ADMIN` 인증을 설치
    (SecurityFilterChain `hasRole("ADMIN")`·`authenticated()`·컨트롤러 `isAdmin` 계열 통과).
  - `ChatOpenSecurityConfig` (Order 1 체인) — proto-open이면 동일 필터를 추가해
    wear 프로파일의 `/api/**` `authenticated()` 절과 `/api/chat/**` 관리자 분기를 통과.
- CSRF는 끄지 않는다. 기존 세션/쿠키 경로는 그대로 둔다. 폼 로그인(`/login`)도 제거하지
  않는다 — proto-open은 우회로이지 인증 제거가 아니다.

## 의도적으로 받아들이는 위험 (프로토에서 OK)

- `/admin/**`, `/model-settings`, `/dashboard`, `/api/diagnostics/**`, `/api/internal/**`,
  `/internal/**`, `/agent/db-context`, `/api/router/**`, `/api/settings` POST,
  `/api/agent/report`, `/flows`, `/v1/tasks`, 운영 POST(`/api/rag/probe`,
  `/api/nova/outbox/**`, `/api/train/**`, `/api/translate/train-now`,
  `/webhooks/channel`, `/messages/trigger`)가 인증 없이 열린다.
- `GET /api/chat/sessions`가 관리자 목록(전체 세션)을 반환하고, 관리자 전용 삭제/진단이
  누구에게나 허용된다.
- `domain.allowlist.admin-token`/`LLM_OWNER_TOKEN`이 설정돼 있어도 proto-open이 우선한다.
- **PROTO_OPEN을 켠 채 push/공개 원격 배포하면 모든 운영자 엔드포인트가 외부에 열린다.**
  공개 배포 전 반드시 `demo.auth.proto-open=false` (또는 프로파일 제외) 확인.

## 여전히 지키는 것

- 비밀번호/토큰/키 평문 커밋 금지 — env/로컬 파일만. `.gitignore`가
  `.env*`, `/apikey*.txt`, `/.secrets/`, `/.env.shared/`를 막는다.
- CSRF·세션 쿠키·폼 로그인 경로는 유지 (끄는 것을 "수정"으로 삼지 않는다).
- `demo.interview.enabled` 뒤집기는 제품 결정이지 hygiene 편집이 아니다.

## 나중에 harden할 때 (TODO / DEPRECATED로만 남길 것)

- [ ] `demo.auth.proto-open`를 prod/공개 프로파일에서 제거하고 false로 고정.
- [ ] `hasRole("ADMIN")` 매처 뒤의 실제 계정 모델 정리 (지금은 `AdministratorRepository`
      + `AdminDetailsServiceImpl` + remember-me).
- [ ] `X-Admin-Token`/`aw-admin-token` 세션 쿠키 경로를 기본 운영자 인증으로 승격.
- [ ] 공개 배포 체크리스트에 "proto-open=false 확인" 항목 추가.
- [x] evidence release 경로는 auth가 아니라 답변 정책 — 확인 완료: 근거 0·인용
      메타데이터 불완전은 일반·개념·대화 질문에서 본문을 공개한다
      (`evidence_unverified_release`, `knowledgeWriteAllowed=false`). 본문 HOLD는
      명시적 evidence_needed/출처 필수 지시·검증 실패·safety 차단만 (`ChatWorkflow`
      `applyFinalVerificationReleaseGate`/`applyEvidenceReleasePolicy`,
      AGENTS.md DEMO1-EVIDENCE-ZERO-RELEASE).
