# Codex fast-fix helpers — MCP 끊김 우회와 빈발 실패 예방 (2026-10-06)

코덱스 세션 분석에서 확인된 빈발 실패 3종 + 데스크톱 앱 MCP 연결 끊김에 대한
경량 우회 경로 SSOT. 복잡한 아키텍처 변경 없이 스크립트/호출 순서로 해결한다.
주의: 지시 초안의 `scripts/build_error_miner.py`는 실제 경로가
`tools/build_error_miner.py`다 — 아래 경로가 라이브 기준 정답.

## 실패 지문 → 즉시 처방

| 지문 | 원인 | 처방 |
|---|---|---|
| `SyntaxError: Unexpected token 'W'` (JSON.parse) | exec 출력 앞 `Warning: truncated output ...` 등 노이즈 | `python -B scripts/safe_json_parse.py`(stdin/파일/문자열)로 첫 유효 JSON만 추출 |
| `cannot create a new goal because this thread has an unfinished goal` | 동일 스레드 미완료 goal 존재 | `python -B scripts/codex_goal_autoclose.py --status` → `--suggest-close`로 `update_goal` 페이로드 확인 후 기존 goal을 닫고 `create_goal` 재시도 |
| `apply_patch verification failed: Failed to find expected lines` | 패치 컨텍스트가 최신 파일과 불일치 | `python -B scripts/fast_patch_preflight.py --file <대상> --needle <기준문자열>` — exit 0 확인 후 패치; 불일치 시 출력된 유사 라인 ±3줄로 재기준 |
| `asdk_app_* is not connected` / `USER_NOT_LOGGED_IN` | `mcp__codex_apps__*`가 데스크톱 앱 로그인 세션에 의존 | 재로그인 시도 금지. 아래 우선순위로 즉시 폴백 |

## MCP 도구 호출 우선순위 (폴백 매트릭스)

| 순위 | 경로 | 상태 | 비고 |
|---|---|---|---|
| 1 | 로컬 stdio MCP `mcp__awx_control_tower__*` / `mcp__awx-control-tower__*` | 안정(성공률 높음) | 서버 = `scripts/awx_mcp_stdio_server.py`(SERVER_NAME `awx-control-tower`), 선언 = `~/.codex/config.toml`, Grok = `src/.grok/config.toml`(shared-read). Devin 표면은 `codex-review`(shared-read) |
| 2 | 직결 CLI `python -B tools/build_error_miner.py scan --in <log[,zip...]> --out <pfx>` | 안정, 세션 무관 | MCP 없이 동일 광산 로직. 기타 `scripts/awx_*` 계열 동일 |
| 3 | 데스크톱 앱 번들 `mcp__codex_apps__awx_control_tower_build_error_mine` | 불안정 | `asdk_app_*`/로그인 세션 의존 → 끊기면 재시도 루프 금지, 1·2순위로 이동 |

규칙: `codex_apps` 계열 실패는 **한 번 보고 즉시 폴백**. 스킬 존재 ≠ 연결됨
(`docs/operations/mcp-agent-ownership.md` — 한 번의 읽기 전용 호출로 확인).
참조: `docs/codex/mcp-call-templates.md`(검증된 호출 형태),
`docs/operations/mcp-agent-ownership.md`(소유/설정 SSOT).

## 헬퍼 요약

| 스크립트 | 용도 | 자체점검 |
|---|---|---|
| `scripts/safe_json_parse.py` | 노이즈 속 첫 유효 JSON 추출(브레이스 스캔+문자열 인식) | `--test` |
| `scripts/codex_goal_autoclose.py` | goals_1.sqlite ro 조회 → 미완료 goal 현황/닫기 페이로드 | `--test` |
| `scripts/fast_patch_preflight.py` | apply_patch 전 needle 존재·개행·유사 라인 확인 | `--test` |

전부 Python 3.10+ 표준 라이브러리, 외부 패키지·유료 호출 0.
