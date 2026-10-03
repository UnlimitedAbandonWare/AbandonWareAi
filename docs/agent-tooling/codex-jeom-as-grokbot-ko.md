# 점(UnlimitedAbandon)을 Grok Bot처럼 쓰기 — 사용자 카드

2026-10-02 · Devin 작성 · 대상: 개인 Pro 계정의 ChatGPT 데스크톱 앱

## 1. 권한 켜는 법 (공식)

1. ChatGPT 데스크톱 앱에서 점 프로필을 연다.
2. **Computers → Your computer(DESKTOP-M5NOV6K) → Allow access** → 확인에서 Allow access.
   - Codex Remote/Work Sync 연결과는 **별도 스위치**다. 둘 다 켜져 있어도 이게 없으면 점은
     "연결됐지만 작업 권한 없음" 상태다.
3. (선택·권장) **Settings → Personalization → Custom rules**에서 "실행 전 확인"으로 둘 것:
   파일 삭제, git 변경, 프로그램 설치, 메일·메시지 발송, 결제.
4. 점에게 `agent-prompts\codex-jeom-grokbot-primer.md` 내용을 붙여 넣는다.
5. 점이 `scripts\jeom_access_selftest.ps1` 결과 표를 보여 주면 범위가 맞는지 확인한다.

해제는 같은 자리에서 **Revoke access**. 폰에서 불러도 같은 권한이 적용된다.

## 2. 무엇을 언제 쓰나

| 도구 | 한도 | 강점 | 언제 |
|---|---|---|---|
| **점 (dot)** | ChatGPT/Codex 한도(아래 §4) | 메일·앱 연결, 폰 원격, 클라우드 상주 | 외출 중 지시·판정, 메일/문서 소재 작업 |
| **agy (Antigravity CLI)** | Google 한도 | 이 PC 로컬 실행, 지시서 작성(작업 중) | PC 앞에서 빠른 대타 |
| **Grok Bot** | X 한도 | 첨부 파일 읽기·장기 기억 | 첨부 많은 판정, 과거 맥락 질문 |
| **Codex 채팅** | Codex 한도 | 제품 소스 실제 수정 | 코드 변경 작업 |

점에게 맡기는 게 좋은 것: 지시서 작성, 보고서 판정("이대로내도 돼?"), 상황 요약, Top10.
점에게 맡기면 안 되는 것: 제품 소스 수정, git 조작, 비밀 파일 접근, 발송·결제.

## 3. 자주 쓰는 말 5개

1. "지금부터 demo-1에서는 Grok Bot 대타로 일해. `…\handover\HANDOVER.md`부터 읽어."
2. "`agent-prompts\<폴더>\BRIEF.txt` 보고 Codex에 넣어도 되는지 판정해 줘."
3. "데빈한테 이어서 하라는 지시서 써서 저장해 줘."
4. "지금 레인 상황 한 줄로 말해. 내가 나서야 하는 거 있어?"
5. "Top10 뽑아 줘."

## 4. 한도 주의 (2026-10-30부터)

- **Pro 200**: Codex·Work 포함량이 Plus의 **20x → 10x**, GPT-6 Pro 채팅 **200 → 100/주**.
- 점과 대화 자체는 한도 미차감이지만, 점이 **Codex/Work 작업을 만들면 한도를 쓴다**.
  점의 모델(GPT-6 Astra)은 가격 인하 상쇄가 없어 체감 감소가 크다는 보도가 있다.
- 점에게 무거운 로컬 Codex 작업을 많이 시키면 Codex 한도가 빨리 닳는다 —
  지시서·판정 위주로 쓰고, 실제 코드 수정은 기존 Codex 채팅에 두는 게 안전하다.
- 출처: OpenAI 공지 인용(developer community), jutsu.ai / xenospectrum 보도
  (2026-10-02 확인). 정확한 개인 한도는 ChatGPT 앱의 플랜 안내가 최종 기준.

## 5. 문제 생기면

- 점이 파일을 못 읽는다 → 2번 절차의 Allow access가 됐는지, 컴퓨터가 온라인+앱 열림인지.
- `jeom_access_selftest.ps1` 표에서 FAIL이 나오면 메모 열의 구분을 본다:
  "권한 꺼짐 모양"이면 권한 문제, "범위 밖 의도 차단"이면 설계대로 막힌 것.
- 점이 이상한 일을 시도하면 즉시 멈추라고 하고, 반복되면 Revoke access.
