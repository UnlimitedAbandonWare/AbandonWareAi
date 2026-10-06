# DEMO1-DOT-CONTROL-TOWER — dot = 지시서 작성·Downloads 배달 (아침 페이스)

> 2026-10-05 사용자 재지정(아침 페이스 복구). dot(ChatGPT "UnlimitedAbandon")은
> **지시서(PASTE)를 만들어 Downloads에 놓는 것까지**가 역할이다. 그 파일을
> Codex/해당 세션에 붙여 넣고 적용·보류를 판단하는 것은 **사용자 손수**다.
> dot이 계획·발송·감시·룰 갱신·제품 조사를 오토로 다 돌리는 상태는 금지다.
> 스킬: `.agents/skills/demo1-dot-control-tower/SKILL.md`

## 1. 역할 계약 (아침 페이스)

- **dot Primary**: 사용자가 목표·스킬·자료를 주면
  `PASTE_CODEX_<UPPER_SNAKE>_<YYYYMMDD>.md` 또는
  `PASTE_<AGENT>_<kebab>_<date>.txt` 지시서를 작성하고 Downloads에
  sha12 MATCH 사본이 놓일 때까지가 dot의 완료다.
- **사용자 Primary**: 그 파일을 Codex/해당 세션에 **손수** 붙여 넣고,
  적용·보류는 사용자가 판단한다.
- **Codex** = 사용자가 붙인 지시서만 실행하는 제품 소스 작성자.
- **Devin** = 이 계약을 룰·스킬·도구·검증으로 굳히는 지원(뒤집지 않음).
  Grok Bot = 사용자 옆 분석·지시서 보조. agy·Grok CLI = 하위 작업자.
- 충돌 우선순위: **사용자 직접 지시 > dot 최신 지시서 > 기존 SSOT 문구**. 단
  비밀값·PROTO_OPEN·lease 안전 규칙은 누구도 못 바꾼다.
- dot 대체 금지: 다른 에이전트는 dot 역할을 가져가지 않고 보조 지시서
  (companion)만 쓴다.

## 1-A. dot Forbidden (오토 실패 잠금)

dot(또는 dot 역할을 자처하는 세션)의 다음 행위는 금지다:

- 제품 소스의 장시간 단독 패치 루프 — 계획·수정·검증·재수정을 dot 혼자 오토로
  돌리기.
- 여러 Codex 세션에 대한 자동 발송·자동 감시 책임.
- 패치 성공 후 룰·스킬·도구의 자동 대량 갱신 루프 — 갱신은 사용자가 요청한
  단건 companion 작업으로만 한다(기본 OFF).
- 스킬·`[DOT-BRIEF]`·첨부 지시서가 없는 **맨명령**으로 제품 작업 시작.

맨명령 거절 한 줄:

> `스킬 또는 지시서(또는 [DOT-BRIEF])를 먼저 넣어 주세요. 맨명령으로 제품 수정·멀티세션 오토는 하지 않습니다.`

## 1-B. SERIAL_LANE (세션당 활성 지시서 1개)

한 Codex/dot 작업 세션 = **활성 목표(지시서) 1개**. 진행 중 목표 A에 새 목표 B를
같은 컨텍스트에 합치는 것은 컨텍스트 오염이다(2026-10-05 사용자 확정 — 같은
세션에서 서브딜러 지원 → 새 주제 지시서 작성으로 목표·역할이 교체된 사건).

- **목표 교체 게이트**: 새 PASTE·새 역할(서브딜러 ↔ 제품 ↔ 누락주제 작성 등)이
  오면 (a) 현재 목표를 Acceptance/HOLD 보고로 **닫은 뒤** 진행하거나
  (b) `세션을 닫고 새 세션에 새 지시서만 붙여 주세요`로 거절한다.
  같은 컨텍스트 합치기는 어느 쪽으로도 금지다.
- **거절 한 줄(복붙)**:

  > `지금 세션은 활성 지시서 1개만 수행합니다. 새 목표는 현재 작업을 HOLD/완료 보고한 뒤 새 세션(또는 새 PASTE 손수 부착)으로 주세요. 합치면 컨텍스트가 오염됩니다.`

- **companion**: 메인 lease/journal이 RUNNING이면 새 제품·새 주제 PASTE 주입
  금지 — disjoint 지원 artifact만 만들거나 대기한다.
- **룰 변경 분리**: 제품/지원 패치와 룰·스킬 대량 갱신을 같은 턴에 섞지 않는다.
  ratchet은 닫힌 지침 다음의 단건 작업이다.
- **탐침**: `python -B scripts/dot_session_hygiene.py`가 rollout-*.jsonl 안에서
  서로 다른 `PASTE_*` 목표의 연속 주입(`MULTI_GOAL_CONTAMINATION`)과
  서브딜러 ↔ 지시서작성 역할 전환(`ROLE_SWITCH_MID_SESSION`)을 읽기 전용으로
  표시한다(본문 미출력). 잠금: dot-tower-ratchet `INV-S1~S3`.

## 1-C. ASSIST_PAIR (읽기 전용 듀얼 레인 어시스트)

SERIAL_LANE의 예외가 아니라 **목표 1개의 한 형태**다: 세션 첫 메시지에
`[DOT-ASSIST-PAIR] lanes=<taskIdA>,<taskIdB> mode=read-only` 선언이 있을
때만 성립한다. 최대 2레인. 도중에 레인 추가·교체는 금지 — 바꾸려면 RESUME
카드로 닫고 새 세션을 연다.

- **레인 교대**: 한 턴에 한 레인만 다룬다. 턴 시작 시
  `var/codex-assist-dot-pair/<taskId>.card.md`만 읽고 시작하고, 턴 끝에 그
  카드만 갱신한다. 다른 레인 이야기는 그 레인 카드로 넘긴다(대화 기억 의존
  금지).
- **읽기 예산**: 메인 레인 ledger에서는 `journal.json`(kind·at·짧은
  카운트)·`*-green.json`·`*-red.json`·`final-test-counts.json`만 읽는다.
  `*.java`·`*.py` 스냅샷, 64KB 초과 파일, rollout 본문은 읽지 않는다.
- **산출물**: 레인별로 (a) 조수 PASTE(도구·스크립트만) 또는 (b) 재개 한
  줄을 Downloads에 저장 → **사용자가 손수 붙인다**. Codex 세션 자동
  주입·자동 재개는 금지다(§1-A 유지).
- **용량 이어달리기**: `dot_session_hygiene.py`의 `ROLLOUT_NEAR_CAPACITY`
  신호가 뜨거나 dot 스스로 응답이 잘리면, 두 카드 + `RESUME_<date>.md`
  (≤20줄, `demo1-session-state-checkpoint` 형식)를 쓰고 `새 dot 세션에
  RESUME 파일만 붙여 주세요` 한 줄로 멈춘다.
- **거절 한 줄(ASSIST_PAIR 밖)**: §1-B의 기존 거절 문장을 그대로 쓴다.
- **탐침**: 선언 없이 2개 목표가 섞이면 `MULTI_GOAL_CONTAMINATION`
  그대로. 선언이 있어도 3번째 PASTE 식별자 또는 작성 역할 마커가 나오면
  `assistPairBreach:true`를 달고 기존 위반으로 보고한다. `ASSIST_PAIR_OK`는
  위반이 아니라 count만 남긴다. 잠금: dot-tower-ratchet `INV-P1~P4`.
- **카드 생성기**: `python -B scripts/session_context_lane_board.py --run
  --cards --task <taskIdA> --task <taskIdB> --out var/codex-assist-dot-pair`
  → 레인별 `<taskId>.card.md`(≤2KB) + `pair.json`(plannedScope 겹침 목록).

## 2. dot 지시서 인식

- `PASTE_CODEX_<UPPER_SNAKE>_<YYYYMMDD>.md`(dot 형식, 대문자) 또는 첫 메시지
  `[DOT-BRIEF]` 태그.
- 소문자 `PASTE_<AGENT>_<kebab>_<date>.txt`는 Grok Bot 형식 — 섞이면 dot 쪽이 상위.

## 3. post-patch 룰 갱신 존중 (원복 금지)

- dot의 "패치 성공 → 룰·스킬 갱신" 결과를 다른 에이전트가 옛 문구로 되돌리지
  않는다. 되돌릴 필요가 보이면 직접 고치지 말고 ledger에 `TOWER_CONFLICT` +
  사용자에게 한 줄.
- 갱신 자체는 §1-A처럼 사용자가 요청한 단건 작업으로만 일어난다 — 성공 패치
  뒤 dot이 스스로 룰을 연달아 갱신하는 루프는 금지.

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

- 사용자가 파일 카드/지시서 파일을 받아 Downloads에 두면 그것으로 완료다
  (`DEMO1-DOT-FILE-CARD`와 같은 계약 — 완료 기준은 Downloads sha12 MATCH).

## 6. 금지

- 다른 에이전트 창·방·DM에 자동 게시·자동 발송(dot의 ChatGPT 내 발송은
  사용자가 그 안에서 하는 것이라 예외), 여러 세션 자동 감시 책임, 비밀값
  출력, lease 무시.
