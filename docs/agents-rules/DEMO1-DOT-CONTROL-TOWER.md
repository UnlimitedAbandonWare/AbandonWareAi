# DEMO1-DOT-CONTROL-TOWER — dot = 전체 컨트롤 타워

> 2026-10-05 사용자 지정. dot(ChatGPT "UnlimitedAbandon")의 계획 → Codex 지시서
> → 발송·감시 → 패치 후 룰 갱신 흐름을 add-only로 고정. 새 구조가 아니다.
> 스킬: `.agents/skills/demo1-dot-control-tower/SKILL.md`

## 1. 위계

- dot = 지휘(무엇을·누가·언제). Codex = 제품 소스 작성자. Devin = 룰·도구·검증
  지원(dot 결정을 도구로 굳힘, 뒤집지 않음). Grok Bot = 사용자 옆 분석·지시서
  보조. agy·Grok CLI = 하위 작업자(조사·단건 위임).
- 충돌 우선순위: **사용자 직접 지시 > dot 최신 지시서 > 기존 SSOT 문구**. 단
  비밀값·PROTO_OPEN·lease 안전 규칙은 누구도 못 바꾼다.
- dot 대체 금지: 다른 에이전트는 컨트롤 타워를 가져가지 않고 보조 지시서
  (companion)만 쓴다.

## 2. dot 지시서 인식

- `PASTE_CODEX_<UPPER_SNAKE>_<YYYYMMDD>.md`(dot 형식, 대문자) 또는 첫 메시지
  `[DOT-BRIEF]` 태그.
- 소문자 `PASTE_<AGENT>_<kebab>_<date>.txt`는 Grok Bot 형식 — 섞이면 dot 쪽이 상위.

## 3. post-patch 룰 갱신 존중 (원복 금지)

- dot의 "패치 성공 → 룰·스킬 갱신" 결과를 다른 에이전트가 옛 문구로 되돌리지 않는다.
- 되돌릴 필요가 보이면 직접 고치지 말고 ledger에 `TOWER_CONFLICT` + 사용자에게 한 줄.

## 4. 고정된 dot 정책 (반대로 쓰지 않음)

a. **OAuth 지속** — 12월 31일 **날짜만으로 차단·강제 전환 금지**. 순서: 구독
   포함(롤링) 사용량 → (리셋권: 사용자 직접) → 62,500 크레딧. 차감 순서는
   공급자 관리라 앱에서 흉내 내지 않음. 기존 폴백 유지. 구독을 "무제한"으로
   단정하지 않음.
b. **안경 기본 후보** = 빠른 Luna, 복잡한 질문 Astra. OpenAI 웹검색은
   **지원·연결 확인된 경우만** 우선, 네이버·브레이브는 보강 경로. Fast는 옵션
   (기본 ON 아님). 영구 1등 모델 하드코딩 금지.
c. **하위 에이전트 연동** — 공식 기능 우선(Codex 내장 서브에이전트, Devin 공식
   ChatGPT 구독 연결, Grok Build는 `codex exec` 위임). **토큰·쿠키 복사로 다른
   CLI에 붙이는 방식 금지**. 짧은 읽기 전용 작업부터 비교 후 확대.

이 3개의 **소스·설정 반영은 dot→Codex lane**. 이 문서는 "정책이 이렇다"만 적는다.

## 5. dot이 PC에 못 붙을 때

- 카드 파일을 사용자가 받아 쓰면 끝(`DEMO1-DOT-FILE-CARD`와 같은 계약).
  Downloads 미존재를 "실패"로 보고하지 않는다.

## 6. 금지

- 자동 게시·다른 에이전트 창 자동 발송(dot의 ChatGPT 내 Codex 발송은 사용자가
  그 안에서 하는 것이라 예외), 비밀값 출력, lease 무시.
