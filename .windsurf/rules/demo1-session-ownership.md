---
trigger: glob
globs: "**/ChatApiController.java,**/*OwnerKey*.java,**/*Session*Controller*.java,**/ChatHistoryService*.java"
---

# demo1-session-ownership

Main `/chat` session/run ownership boundary (anonymous-first, proto-open aware).

- O1. 익명 사용자의 소유 기준은 `ownerKey` 쿠키 하나다. IP는 소유 기준이 아니다
  (cloudflared 터널 뒤에서는 모든 익명이 같은 IP로 묶인다). `X-Owner-Key` 헤더는
  신뢰하지 않는다.
- O2. 로그인 일반 사용자는 `administrator.username` 일치만 허용한다.
- O3. 관리자 예외(타인 세션·run 열람, `GET /sessions` 전체 목록)는 메인 모드
  (`demo.interview.enabled=false`)에서 **실제 관리자에게만** 유지한다.
  실제 관리자 = `isAdmin(authentication)` 이고 principal 이름이 `proto-open`이
  아닌 호출자 — 제시된 관리자 토큰(principal `admin-token`) 또는 실제 관리자
  계정 로그인. 면접 모드에서는 실제 관리자에게도 예외가 없다.
- O3a. PROTO_OPEN이 모든 요청에 심는 ambient ROLE_ADMIN(principal `proto-open`)은
  소유권 판정에서 일반 사용자 취급이다 — ownerKey/username 범위로만 허용하고
  `getAllSessionsForAdmin` 같은 관리자 전용 경로에 쓰지 않는다. 소유권·관리자
  예외 판정은 `isRealAdmin`(위 정의)으로 하고 벌어진 `isAdmin`에 걸지 않는다.
- O4. 목록 API는 "가져온 뒤 필터"가 아니라 "필터한 결과"만 반환한다. 남의 것은
  존재 여부도 흘리지 않는다(403/404 문구 통일은 기존 관례를 따른다). 공유
  대체키(`ipua:`/`system:`/부재) 호출자에게는 빈 목록을 반환한다.
- O5. 소유권을 바꾸는 PR에는 2클라이언트 테스트(A·B 쿠키 분리)가 반드시 있어야 한다.

Caveat: `demo.auth.proto-open=true`(meta-display 기본)에서는 `AdminTokenGuardFilter`가
모든 요청에 name=`proto-open`의 ROLE_ADMIN 인증을 심는다. 소유권 경로는 이 ambient
관리자를 실 관리자로 인정하지 않는다 — `isRealAdmin = isAdmin && principal != "proto-open"`
(`AdminTokenGuardFilter`가 심는 이름: `proto-open` vs `admin-token`).

References: `ClientOwnerKeyResolver` (쿠키→gid→ipua→UUID), `OwnerKeyBootstrapFilter`,
`ChatSessionAccessGuard`, `ChatApiControllerMainModeOwnershipTest`,
`ChatApiControllerInterviewOwnershipTest`, `AdminTokenGuardFilter`.
