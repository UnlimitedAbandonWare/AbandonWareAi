# SUB_REPORT_V1 — 하위 에이전트 1회 완결 보고 계약 (2026-10-06)

dot이 하위 에이전트(agy/Grok CLI/Clean 서브·Devin 지원 등)에게 일을 맡기고
받는 **첫 보고 메시지는 아래 6칸을 모두 갖춰야 한다**. 칸이 빠진 보고는
dot이 다시 묻는 대신 검사기로 되돌려 보낸다(F2 되묻기 4회 사건 방지).

## 필수 6칸

| 칸 | 이름 | 내용 |
|----|------|------|
| ① | field1_overall_status | 전체 상태 `상태: DONE` 또는 `PARTIAL` + 산출물 파일명·sha12 |
| ② | field2_item_status | 항목별 상태 표 — `W1: PASS` / `W2: PARTIAL` 같은 `W#|WP#|A#|DV#: PASS|PARTIAL|FAIL|NOT_RUN` 행 |
| ③ | field3_input_source | 입력 원본 파일명·sha12 + `INPUT_FALLBACK=used|none` (입력 폴백 규칙: `docs/agents-rules/DEMO1-DOT-FILE-CARD.md` "입력 쪽") |
| ④ | field4_write_ledger | 쓰기 장부 — 단계별(`support`/`brief`) × 위치별(`repo`/`Downloads`/`scratch`) 개수와 경로 |
| ⑤ | field5_own_leases | 남은 own lease 수 — `own lease 잔존: N` |
| ⑥ | field6_not_run | `NOT_RUN` 목록(없으면 `NOT_RUN: 없음`) |

## 검사기

```
python -B scripts/dot_card_check.py --sub-report <보고 파일> [--json]
```

- exit 0 `SUB_REPORT_V1 PASS fields=6/6` — dot이 사용자에게 올려도 되는 보고.
- exit 2 `SUB_REPORT_V1 FAIL missing=<칸 이름 목록>` — 누락 칸 이름별로
  나열된다. dot은 본문을 되묻지 않고 이 결과를 그대로 하위 에이전트에게
  반환해 보완 보고를 요구한다.
- exit 3 파일 IO 오류.

## SKIP 근거 표 (②와 짝)

주제 SKIP 판정의 근거 표는 `brief_save.py cover` 출력을 그대로 붙인다:

```
python -B scripts\brief_save.py cover --topic <주제> --terms "t1|t2|t3"
```

`COVERED`/`PARTIAL`과 파일명·줄·sha12가 나오므로 되묻기 없이 SKIP 표를
구성한다.
