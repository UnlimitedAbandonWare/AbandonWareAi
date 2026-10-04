# DEMO1-CODEX-SCRIPTIZE — 반복 손 명령의 스크립트화 채굴

목적: `~/.codex/sessions` rollout 기록에서 Codex가 손으로 반복하는 무거운 명령
묶음을 찾아 bat/ps1/py 후보로 만든다. 분석기·판정 기준·재실행 주기의 SSOT.

## 분석기

```
python -B scripts/codex_scriptize_mine.py [--days 7] [--max-files 250]
    [--cwd-filter "AbandonWare\demo-1"] [--min-sessions 2] [--min-count 3]
    [--out-dir var\codex-assist-scriptize] [--json-out PATH]
```

- 입력: `rollout-*.jsonl` 한 줄씩 스트리밍(파일명 날짜 창, 최신순 max-files).
- 추출: `custom_tool_call:exec` 입력의 `tools.exec_command({cmd:"..."})` cmd
  (JS 이스케이프 해제, `;`/`&&`/줄바꿈으로 구문 분리), function_call 이름 카운트,
  `call_id`로 짝지은 출력의 잘림/실패/출력길이/경과. 암호화 입력(`gAAAA…`)·
  깨진 줄은 건너뛰고 카운트만.
- 모양 정규화: 경로→`<PATH>.ext`, 숫자→`<N>`, 따옴표 문자열→`<STR>`,
  uuid/해시→`<ID>`, 날짜→`<DATE>`, bare 스크립트명→`<NAME>.ext`,
  `$env:` 비밀값→`<STR>`·env 이름→`$env:<ENV>`.
- 묶음: 같은 턴(task_started 또는 user message 사이) 연속 명령 모양의 2~6-gram.
  서로 다른 세션 ≥2 && 총 발생 ≥3 만 남긴다.

## 점수식

`score = 반복세션수 × 평균경과초(미측정·0이면 1.0) × (1+잘림률) × (1+실패율)`

- 잘림률/실패율은 묶음 구성 명령 출력의 비율. 평균 출력 길이(문자)는 별도 기록.
- 판정: `진행 중`(F5 git-ship/guard-fast/lock-harmony) > `기존 도구 사용 중`
  (묶음이 scripts/*.py·*.ps1·*.bat 호출 포함) > `기존 도구 있음, 안 쓰임`
  (도구 파일명/docstring 키워드 매칭 ≥2) > `새 후보`.
- 위험: git add/commit/push/reset/checkout, Remove/Move/Set-Content/Out-File/
  Add-Content/Rename-Item, apply_patch, Stop-Process, taskkill, gradle* 가
  하나라도 있으면 `state-change`, 아니면 `read-only`.

## 판정 기준(5단계 Self-Ask)

상위 10개 각각 긍정(절감 시간·토큰)/부정(기존 도구 충분? 희소? 상태변경 위험?)/
반례(한 세션 부풀림?) → MAKE / POINTER(기존 도구 안내선만) / DROP.

## 출력

`var/codex-assist-scriptize/mine-<ts>.json|.md` — 상위 20 묶음 표 + 반복 단일
명령 보조 표. 세션 식별은 rollout uuid 앞 8자만. 세션 원문·출력 원문·키 값은
저장 금지(예시는 마스킹 후 ≤160자). 결과는 `git_staged_guard.PATTERNS`로
0건 확인 필수.

## 재실행 주기

주 1회 또는 큰 세션 배치 후: `--days 7`로 돌려 전주 대비 새 패턴·판정 변화만
리뷰한다. `기존 도구 사용 중`·`진행 중` 묶음은 신규 작업에서 제외.

## 2026-10-03 첫 실행 결과 (mine-20261003-1842)

- 181파일/169 demo-1 세션/46,687명령/1,592묶음/37초.
- 지배 패턴: bounded-read 군집 ~8,604회(Get-Content|Skip-First·-Tail·-Raw·
  TotalCount·$p=·for-loop·$s[A..B]·rg|Select-First) → `scripts/out_lines.py`
  (번호 붙은 중간 창)로 신규 제작; out_peek.py(head/tail/grep/json-keys)는
  중간 창 뷰가 없어 상호보완.
- decision 카드 수작업(reasonCode+risk+gates, 20세션, 실패 52%) →
  `scripts/checkpoint_decision_card.py` 신규 제작(codex_work_checkpoint
  스키마 import, --out JSON 또는 stdout).
- $env:<ENV> 체인·tool --help·git identity 쌍 → DROP/POINTER (judgments.md 참조).
