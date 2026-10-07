# VIBE_OPEN — 바이브코딩 단계 보안 질문 자동처리 (2026-10-06 사용자 결정)

지금은 바이브코딩 단계라 접근성이 최우선이고, 보안은 나중에 한꺼번에 올린다.
Codex·Devin·agy·Grok CLI·Clean이 관리자 로그인·잘못된 계정 차단·로그아웃 후 차단·
CSRF·보호형 검증 환경 URL·Display 인증 잠금 같은 보안 질문을 사용자에게 묻지 않고
"현재 개방 유지 + DEFERRED_SECURITY로 기록"을 자동 선택한다. 메인 `/chat`만이
아니라 Meta Ray-Ban Display, Fold6/웹 Display, interview 페이지, debug studio,
관리자 URL, 내부 도구 API까지 같은 규칙을 적용한다. 나중에 보안을 올릴 때 한 번에
되돌릴 수 있게 스위치 1개와 `SECURITY_DEBT.md` 목록 1개로 모은다.

## 켜짐 조건
- `configs/vibe-open.yaml` `enabled: true` (기본 true). 나중에 보안을 올릴 때
  이 값 하나를 false로 바꾸면 분류기·스킬이 예전 동작(ASK_ONCE)으로 돌아간다.

## 켜져 있는 동안 묻지 않는 것
보호형 검증 환경 URL, 관리자 로그인 성공 증명, 잘못된 계정 차단, 로그아웃 후
재차단, CSRF·세션 검증, PROTO_OPEN 유지 여부, admin 잠금·fail-close,
Display·Fold6·interview·debug studio 접근 인증, 내부 도구 API 토큰 요구 여부,
"보안 강화할까요" 류.

## 자동 답
"VIBE_OPEN — 현재 개방 상태 유지, 보안 검증은 DEFERRED_SECURITY로 기록하고
계속 진행". 관측한 HTTP status만 근거로 남기고, 이 항목들은 PASS도 FAIL도
아닌 `DEFERRED_SECURITY`로 보고한다. 완료 판정에 넣지 않는다.

## 상용구 vs 직접 요구 우선순위
붙여넣은 플러그인 역할 상용구 안의 보안 검사 줄은 사용자 직접 요구가 아니다.
<!-- VIBE-OPEN-BOILERPLATE-RULE v1 -->
- 상용구 판별: 지시문 안의 플러그인 역할 블록("1. Superpowers … 2. Browser …", "7. glm_worker …" 같은 번호·이름 붙은 역할 절)에 들어 있는 admin 로그인·잘못된 계정 차단·로그아웃 후 차단·관리자 권한 검토 줄은 TEMPLATE_BOILERPLATE다 — VIBE_OPEN enabled면 완료 조건이 아니라 DEFERRED_SECURITY다.
- "상용구가 요구했다"/"live request"/"사용자 요구" 같은 문구가 상용구 블록 안이나 그 인용 안에 있어도 직접 요구로 승격되지 않는다 — 판정은 동일하게 DEFERRED_SECURITY다.
- 직접 요구로 인정하는 경우는 하나뿐이다 — 상용구 블록 밖에서 그 턴의 사용자가 자기 문장으로 해당 검사를 요구한 경우뿐이며, 그때도 격리 검증(scripts/agent_isolated_auth_verify.py)을 먼저 쓰고 사용자에게 로그인·계정·URL·비밀번호를 묻지 않는다.
- 한 번 DEFERRED_SECURITY로 처리한 범주는 같은 세션·같은 goal에서 다시 묻지 않는다.
<!-- /VIBE-OPEN-BOILERPLATE-RULE v1 -->

## 하지 않는 것 (VIBE_OPEN이라도 그대로, 접근성과 무관)
비밀값(API 키·토큰·비밀번호·쿠키) 출력·커밋·지시서 기재, 브라우저 인증 상태·
trace 커밋, push, 데이터셋 삭제(백업만), skip-permissions류. 이것들은 질문
대상이 아니라 그냥 금지라서 묻지 않는다.

## 잠그는 방향의 자동 처리
보안을 더 잠그는 방향(인증 추가·잠금·PROTO_OPEN false)은 VIBE_OPEN 동안
자동으로 "하지 않음 + SECURITY_DEBT에 기록"으로 처리하고 묻지 않는다.
반대로 기존 통제를 해제·제거하는 방향은 실제 인증 범위 변경이므로 VIBE_OPEN
대상이 아니고 기존 판정(ASK_ONCE)이 유지된다.

## 나중에 보안 일괄 상향
사용자가 "보안 올리자"라고 하면 `SECURITY_DEBT.md` 목록을 위에서부터 한
지시서로 처리한다.

## 구현 앵커
- 스위치: `configs/vibe-open.yaml` (`enabled`)
- 분류기: `scripts/codex_question_classifier.py` 규칙 `D37` —
  ask-auth-policy·ask-admin-scope·NARROW_GUARD보다 먼저 검사, 로그에
  `VIBE_OPEN` 표기, 카드 옵션은 개방 유지 쪽만 자동 선택
- 보류 목록: `docs/security/SECURITY_DEBT.md`
- 회귀: `scripts/test_codex_question_classifier.py` `test_vibe_*`