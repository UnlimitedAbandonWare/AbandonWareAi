> 정정(2026-10-05, 아침 페이스 복구): 2026-10-04 폐기 결정을 **부분 번복**한다.
> "Downloads sha MATCH = 완료" 규칙은 **dot 지시서(`PASTE_CODEX_*.md` /
> `PASTE_<AGENT>_*.txt` 계열)에 한해** 다시 유효하다 — dot이 지시서를 쓰면
> `dot_brief_save.py save`로 Downloads에 놓고, 사용자가 손수 세션에 붙여
> 넣는다. **전 에이전트 Stop 스캔(`.codex/hooks.json` Stop 훅의 `--scan`) 부활과
> 전역 자동 복사는 여전히 금지** — 다른 에이전트 산출물은 각자의 보고 절차를
> 따른다. 아래 본문은 이력 + 도구 계약 참조용으로 보존한다.

# DEMO1-DELIVERY-DOWNLOADS — 산출물은 Downloads까지 배달해야 완료

Codex(점/dot)·Devin·Grok·agy·Clean 이 사람에게 주는 지시서·보고서·패치 설명
파일은 **만든 경로만 답하면 미완료**다. Downloads(`user.downloads` 키, 현재
`C:\Users\nninn\Downloads`)에 sha 일치 사본이 있어야 완료다.

## 규칙

1. 지시서·보고서·패치 설명 파일을 만들면 바로 실행한다:
   `python -B scripts/deliver_to_downloads.py --file <path>`
2. 답변에는 도구 출력 `DELIVERED <Downloads전체경로> <size>B sha12=<12> MATCH`
   줄을 그대로 붙인다.
3. `Documents\Codex\...\task\` 같은 원본 경로만 주고 끝내면 미완료다.
4. `.codex/hooks.json` 의 Stop hook은 `--hook-stop`(첫 user `[DOT-BRIEF]`가
   있는 dot 세션만 배달 확인)이다 — 전역 `--scan` 안전망은 제거됐고 부활
   금지다.

## 도구 계약 (`scripts/deliver_to_downloads.py`)

- `--file <path>`: 명시 파일 복사(원본 유지). sha256 비교 후
  `DELIVERED ... MATCH` / `SKIP_SAME` 한 줄 출력.
- `--scan [--since-minutes 240] [--roots ...]`: 기본 roots
  `Documents\Codex\**\task`, `var\codex-assist-*`, `agent-prompts\**` 에서
  이름 규칙(`PASTE_*.txt`, `*directive*.md`, `*지시서*`, `*_report_*.md`,
  `*brief*.md`)과 since 창 안 파일만. 같은 이름·같은 sha → `SKIP_SAME`,
  다른 sha → `<이름>_v2.._v9` (기존 Downloads 파일 덮어쓰기 금지).
- 제외: 1MB 초과, `.env`·`*secret*`·`*token*`·`*key*` 이름, 바이너리.
- 쓰기 범위: Downloads + `var/deliver/deliver-log.jsonl` 뿐. 항상 exit 0 —
  hook이 Codex 턴을 막지 않는다.
- 테스트: `python -B -m unittest scripts.test_deliver_to_downloads -v` (T1~T10).
