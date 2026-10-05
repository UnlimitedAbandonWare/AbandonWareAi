# DEMO1-AGY-ACCOUNT-SWITCH-RESILIENCE

agy-cli 계정 전환(A↔B, `/logout`→`/login`, `Agy-Auth.bat use <name>`) 중
agy가 일시 부재해도 플릿(devin·orchestra)이 무한 대기하지 않고 자율
폴백하며, 재진입 시 직전 맥락을 이어받는 계약. 구현 근거:
`PASTE_DEVIN_AGY_ACCOUNT_SWITCH_RESILIENCE_20261005`.

## 상태 SSOT

`data/agent-handoff/agy-presence.json` — 단일 writer/reader 계약:

```json
{"status": "ONLINE|SWITCHING|OFFLINE", "account": "<alias>",
 "updatedAtKst": "<iso8601 KST>"}
```

- CLI: `python -B scripts/agy_presence.py set --status S [--account A]`
  · `get` · `is-available`(exit 0 = ONLINE).
- `account`는 별칭만(`A`/`B`/`UNSAVED`). 이메일·토큰 형태는 `MASKED`.
  자격 증명 본문·OAuth 토큰·이메일 평문 기록 금지.

## 수명주기

| 시점 | writer | 상태 |
|---|---|---|
| agy 기동 직전 | `Start-Agy-CLI.bat` | `ONLINE` |
| agy 종료 직후 | `Start-Agy-CLI.bat` | `OFFLINE` + `agy_session_seed.py --on-exit` |
| `use <name>` 전환 시작 | `agy_auth_switch.ps1` | `SWITCHING` |
| `use` 완료/실패 종료 | `agy_auth_switch.ps1` | `OFFLINE` |

- 파일 부재·손상 = `UNKNOWN`(읽는 쪽 fail-safe: 사용 불가 판정).
- stale 규칙(AUTO 2026-10-05, 옵션 a): `SWITCHING`이 30초(`AWX_AGY_PRESENCE_TTL_S`)
  동안 갱신 없으면 유효 상태 `OFFLINE` — 전환 도중 크래시가 플릿을
  '대기'에 고정하지 못하게 한다. `ONLINE`은 런처 명시 상태라 OFFLINE이
  기록될 때까지 유효하나, TTL 초과 시 `stale:true`로 표시되어 엄격한
  소비자가 강등 판정할 수 있다.

## 폴백 레일

`scripts/fixtures/orchestra/route-rules.json` → `lanes.AGY_RESEARCH.fallback`
= `DEVIN_AUTONOMOUS_OR_CACHED_SEED`: `effectiveStatus!=ONLINE` 또는 agy
응답 타임아웃 시 데빈 자율 레인으로 계속하거나 `var/agy-seed/latest.md`
캐시 시드를 즉시 활용한다. agy 부재를 기다리는 busy-wait 금지.

## 세션 스냅샷/재진입

- 종료: `Start-Agy-CLI.bat`가 agy.exe 종료 직후 `python -B scripts/agy_session_seed.py --on-exit`
  → `var/agy-seed/last_exit_context.json`(최근 작업 1줄·인계 경로·리스 주제, scrub 적용).
- 재진입: `agy_session_seed.py --launcher`가 스냅샷이 있으면
  `[Start-Agy-CLI] resume: <직전 작업 1줄>` 출력(옵션 a, non-blocking).

## MCP 격리

모델 OAuth(Windows Credential Manager `gemini:antigravity`)와 MCP 연결은
분리 관리된다 — 계정 전환 중에도 MCP 세션은 유지. 프레즌스는 agy **프로세스
가용성**만 표현하며 MCP·플릿 다른 레인의 상태를 덮지 않는다.

## 검증

`python -B scripts/test_agy_presence.py` → `ALL PASS`(exit 0). 오프라인
전용, 실제 OAuth/API 호출 0.
