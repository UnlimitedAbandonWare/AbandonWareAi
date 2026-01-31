# Kakao 동의(Consent) 카드 템플릿 가이드

이 폴더의 JSON은 **카카오 i 오픈빌더 스킬 응답 포맷**(v2.0)을 따릅니다.

## 변수(런타임 치환 필요)
- `${sessionId}` : 세션 식별자
- `${roomId}`    : Kakao room/thread id
- `${scopes_csv}` : 쉼표 구분 스코프 문자열 (예: `kakao.push,web.get`)
- `${scopes_array}` : 배열 문자열 (예: `["kakao.push","web.get"]`)
- `${ttl_minutes}` : 권한 유효 분
- `${ttl_seconds}` : 권한 유효 초
- `${CONSENT_GRANT_BLOCK_ID}` : 허용 처리 블록 ID
- `${CONSENT_DENY_BLOCK_ID}`  : 거부 처리 블록 ID

## 사용법
1) 동의가 필요한 툴 호출 직전, 서버에서 템플릿 파일(JSON 문자열)을 로드합니다.
2) 위 변수들을 실제 값으로 문자열 치환합니다.
3) 오픈빌더 스킬 웹훅 응답으로 그대로 반환하면, 사용자는 버튼/빠른응답으로 동의/거부를 선택할 수 있습니다.
4) 허용(Grant) 클릭 시 `extra.grants`, `extra.ttl` 등 매개변수가 스킬 서버로 전달되도록 **block 액션**을 권장합니다.

## 템플릿 종류
- `kakao_consent_card.basic.json`: basicCard + 버튼 + quickReplies (권장)
- `kakao_consent_card.quickReplies.json`: simpleText + quickReplies만 사용 (경량)
