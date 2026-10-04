> 폐기(2026-10-04): dot은 `DEMO1-DOT-FILE-CARD.md`를 따른다 — 자동 Downloads 복사·이동 없음.
> 아래 본문은 폐기 전 이력으로만 보존한다.

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
4. `.codex/hooks.json` 의 Stop hook(`--scan --since-minutes 240 --quiet`)이
   안전망이다 — "까먹어도 복사"용이지 1번의 대체가 아니다.

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
