---
name: demo1-bat-run-ledger
description: demo-1 의 .bat/.cmd 실행 전 공용 실행 기록(var/bat-runs)을 확인하고 재실행 낭비를 줄일 때. 실행 후에는 기록 한 줄이 남는지 확인한다.
---

# demo1 bat run ledger

`.bat` 실행 기록 공용 저장소: `var/bat-runs/YYYYMMDD.jsonl` (schema `awx.bat_run.v1`).
훅이 붙은 bat(Start-RAG, ForceRestart-RAG, Verify-RAG, Debug-RAG, Debug-Meta-Display,
Sub-Tool, Verify-Chat-Fast)는 실행 시 자동으로 begin/end 한 줄씩 남는다.
훅이 없는 bat 는 수동으로 `python -B scripts/bat_run_ledger.py append --phase end --bat <name> --exit <code>`.

## 실행 전

```
python -B scripts/bat_run_ledger.py should-run <Bat-Name.bat>
```

- `RUN`(exit 0): 그대로 실행해도 됨.
- `SKIP_RECENT_OK`(exit 3): 직전 실행이 성공이고 HEAD·작업 트리 변경이 없음.
  재실행 대신 직전 결과를 인용한다(`var/rag-launcher/LATEST.json` 또는 ledger tail).
- `FIX_FIRST`(exit 4): 같은 실패 지점으로 2연속 실패. 먼저 실패 지점을 고친다.
  출력된 log/runId 경로부터 본다.

should-run 은 안내만 한다(실행을 막지 않음). 이유를 journal note 에 한 줄 적으면
그대로 실행 가능.

## 실행 후

```
python -B scripts/bat_run_ledger.py tail --days 3 --bat <Bat-Name.bat>
```

- begin 만 있고 end 가 없으면 실행이 중간에 끊긴 것 — 결과 파일 없음의 원인 후보.
- `summary --days 3` 은 bat 별 횟수·성공률·마지막 결과·연속 실패 수.

## 규칙

- caller 는 `AWX_CALLER` > `DEVIN*` > `CODEX_*` > `AGENT_SESSION/AWX_AGENT_WORKER` > `user`.
- args 는 토큰/키 패턴 자동 마스킹. 비밀값을 기록에 넣지 않는다.
- `var/rag-launcher` 의 과거 실행은 `backfill --from var\rag-launcher --days N` 으로
  `caller=unknown` 으로 합산된다(원본 읽기 전용).