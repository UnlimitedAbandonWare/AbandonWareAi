---
name: demo1-rtx3090-health-watch
description: >-
  Use when the user mentions RTX 3090 / GPU / fan noise / driver resets on
  DESKTOP-M5NOV6K, or a watch alert exists - read latest/alert JSON and report
  hypotheses only
---

# demo1-rtx3090-health-watch

DESKTOP-M5NOV6K RTX 3090 상태 감시. 원인 **미확정** - 사용자가 Afterburner
Power Limit ~90% 실험 중 (`power_limit_first_then_consider_psu`).
스크립트는 읽기 전용 수집기다: GPU 부하를 막지 않고, PL/클럭/PSU를 바꾸지 않는다.

## Entry

```powershell
Watch-Rtx3090.bat                                  # 1회 수집 (= scripts\rtx3090_health_watch.ps1)
Watch-Rtx3090.bat -IntervalSeconds 120             # 선택: 주기 감시 루프
powershell -NoProfile -File scripts\rtx3090_health_watch.ps1   # 동일, 인자 전달 가능
```

읽기 진입점 (에이전트가 먼저 볼 것):
- `var/debug/rtx3090-watch/latest.json` - 매 실행 전체 스냅샷
- `var/debug/rtx3090-watch/alert-<timestamp>.json` - 이상 시만 생성
- `data/agent-handoff/rtx3090-watch/LATEST.md` - 최신 alert 짧은 요약

## 수집/이상 신호

- nvidia-smi: temp, power.draw/limit/max, pstate, clocks, util, fan,
  `clocks_event_reasons.*` (hw_slowdown / hw_power_brake / thermal이 `Active`면 이상;
  `sw_power_cap`은 PL90 의도 상태라 이상 아님). ecc는 미지원 시 `not_observed`.
- Ollama `http://127.0.0.1:11434|11435/api/version`: 지연/성공, 연속 실패
  >= `-OllamaFailThreshold`(기본 2) 시 `ollama_timeout_streak`.
- System 이벤트(24h): nvlddmkm, Display(4101 TDR 포함, Warning도 이상 취급),
  Kernel-Power 41. 이전 실행 이후 새 이벤트 = `new_error_events` (첫 실행은 lookback 전체 1회 기선 보고).
- 그 외: `smi_missing`/`smi_failed`, smi 출력의 lost/reset/Xid 문구 = `smi_error_text`.
- alert 중복 억제: 동일 시그니처는 `AlertCooldownMinutes`(기본 60) 내 재생성 안 함
  (latest.json은 매번 갱신). 시그니처 변화 시 새 alert + LATEST.md 갱신.

## 보고 규칙

- 가설만 제시, `confidence=low` 유지: `power_peak_or_limit` / `psu_or_wiring` /
  `driver` / `unknown`. 단정 금지. LATEST.md/latest.json의 `hypotheses`를 그대로 인용.
- 종료 코드: 0 ok, 3 anomaly(alert 기록 또는 suppressed), 1 script error, 2 usage.
- `var/debug`는 휘발성 보관 - 인수인계 근거는 `data/agent-handoff/rtx3090-watch/`.
- 스케줄링(선택): 사용자 승인 시에만 Task Scheduler 등록 (예: 1-5분). 기본은 수동 BAT.

## Don't

- PL/클럭/PSU 자동 변경, 파워 교체 자동화, 원인 단정, 평소 LLM/벤치 부하 차단 - 전부 금지.
- nvidia-smi/이벤트/포트 값을 임의 추정으로 채우지 말 것 - `not_observed` 그대로 둘 것.
- alert를 "해결됨"으로 닫지 말 것 - 이 파일들은 관측 기록이며 판정은 사용자 몫.

## Related

- AGENTS.md `DEMO1-RTX3090-WATCH` - 모델 락은 `DEMO1-OLLAMA-MODEL-LOCK`과 별개.
- Ollama 포트/모델 배선 변경은 이 스킬 범위 밖 -> `$demo1-api-routing-inventory`.
