# Codex 단독 디버깅 가이드 (데빈 종료 대비)

2026-10-13 Devin 지원 종료 이후 **코덱스가 혼자** 이 체크아웃을 진단·수리하기 위한
원터치 커맨드북. 모든 명령은 Project Root `C:\AbandonWare\demo-1\demo-1\src` 에서
PowerShell 5.1로 실행한다. 셸 연산자는 `;` 와 `if ($LASTEXITCODE -eq 0) { }` 만
쓴다(`&&`/`||` 없음).

원칙: 여기 적힌 명령은 전부 **읽기 전용 또는 var/ 아래 산출물 기록뿐**이다.
서버 시작·재시작·중지, 제공자/모델 호출, 유료 API, Git 쓰기는 이 가이드 범위 밖.

## 1. 원터치 진단 명령 (1줄씩)

| 목적 | 명령 | 정상 종료 코드 |
|---|---|---|
| RAG 스택 원인 요약(카드) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/read_rag_debug_trail.ps1 -Compact` | 0 ready / 3 없음 / 4 실패 |
| RAG 스택 전체 요약(JSON) | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/read_rag_debug_trail.ps1 -JsonStdout` | 위와 동일 |
| 라이브 RAG 상태 | `Debug-RAG.bat -Action status` | 0 ready, 3 not-running |
| 라이브 RAG 상태(JSON) | `$env:AWX_RAG_JSON='1'; Debug-RAG.bat -Action status` | 동일, stdout=JSON 1건 |
| 포트·런타임 소유자 | `Status-RAG.bat` | 0 |
| 세션 트레이스 요약 카드 | `python -B scripts/chat_session_debug_export.py list --summary --tail 50` | 0 |
| 세션 트레이스 JSON 카드 | `python -B scripts/chat_session_debug_export.py list --compact-json --tail 50` | 0 |
| 특정 세션/런 카드 | `python -B scripts/chat_session_debug_export.py show <id> --compact-json` | 0 / 4 미발견 |
| 트레이스 디렉터리 상태 | `python -B scripts/chat_session_debug_export.py status` | 0 / 2 디렉터리 없음 |
| 로컬 DB 상태·H2 락 | `python -B scripts/db_agent.py status --pretty` | 0 / 3 잠금(아래 §4) |
| Meta Display DB 상태 | `python -B scripts/meta_display_db_export.py status` | 0 (locked=true는 잠금 표시) |
| 머신·env·도구 위치 | `python -B scripts/agent_machine_context.py --pretty` | 0 |
| 활성 저널 목록 | `python -B scripts/work_journal.py list --active` | 0 |
| lease/신호 프리플라이트 | `python -B scripts/agent_preflight.py --root . --agent codex` | 0 |

## 2. 토큰 절약 옵션 (코덱스 예산 ~2-3KB)

| 옵션 | 대상 | 효과 |
|---|---|---|
| `--summary` | export.py `list`/`show` | 레코드당 ~7줄 텍스트 카드 |
| `--compact-json` | export.py `list`/`show` | `awx.chat-session-trace-card.v1` 카드 JSON; `source_cutoffs`는 파일 수로 축약 |
| `--tail N` | export.py `list`/`show` | 각 트레이스 파일의 마지막 N줄만 역방향 읽기(빠름). `coverage.tail_truncated_files>0`이면 부분 스캔 |
| `-Compact` | read_rag_debug_trail.ps1 | `[TRAIL CARD]` 4줄: verdict·class·error·nextCommand |
| `-JsonStdout` | read_rag_debug_trail.ps1 / debug_rag_stack.ps1(`$env:AWX_RAG_JSON='1'`) | stdout에 JSON 1건 |
| `--pretty` | db_agent.py / agent_machine_context.py | 사람 읽기용 들여쓰기(기본은 1줄 JSON) |

카드 필드: `ts, sessionId(hash:), runId(hash:), surface, requestedModel,
effectiveModel, baseUrlClass, outcome, errorClass, harmonyWarn, cfvmQueued,
ragEnabled, fallbackCount, recommendedAction, source{file,line|offset,sha12}`.
`recommendedAction`은 결정적 매핑(모델 호출 없음): cancelled→재현+cancel 경로,
error+local→Ollama/Status-RAG, error+remote→제공자/quota+`export <id>`,
fallback>0→기본 제공자 점검, harmonyWarn→harmonyDecision 검토, 이상 outcome→전체 레코드.

## 3. 종료 코드 요약

- `read_rag_debug_trail.ps1` / `Debug-RAG.bat`: 0 ready, 3 not-running/요약 없음,
  4 degraded/failed, 1 도구 오류 (`Debug-RAG.bat` 추가: 2 blocked, 5 verbose,
  6 verify 실패).
- `chat_session_debug_export.py`: 0 성공, 2 trace dir 없음(status), 3 export 거부,
  4 id 미발견.
- `db_agent.py`: 0 정상, 3 H2 파일 잠금, 5 verify-admin 행 없음.
- `read_rag_debug_trail_tests.ps1`: 총괄 `[TRAIL-TESTS] passed=N failed=0`, exit 0.

## 4. H2 락 읽는 법

`db_agent.py status` 가 exit 3이면 lmsdb 파일을 **실행 중 JVM이 잡고 있다**는
뜻이다. 고장이 아니라 "서버 살아 있음" 증거다. 잠금 중 라이브 H2를 직접 열지
말고 Display 대화 DB는 `scripts/meta_display_db_export.py` 익스포트 레인만 쓴다
(SSOT: `docs/agents-rules/DEMO1-DB-AGENT-SSOT.md`).

## 5. 세션 시작 Self-check 블록 (순서대로)

```powershell
Set-Location C:\AbandonWare\demo-1\demo-1\src
python -B scripts/agent_preflight.py --root . --agent codex        # 저널/lease/신호
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/read_rag_debug_trail.ps1 -Compact
python -B scripts/chat_session_debug_export.py status              # 트레이스 디렉터리
python -B scripts/db_agent.py status                               # DB/잠금 (3=running)
python -B scripts/work_journal.py list --active                    # 진행 중 작업
```

판정: trail `exit=0` + `verdict=ready`면 런타임 정상. `exit=3/4`이면 카드의
`next=` 힌트 그대로 다음 읽기 전용 단계를 고른다(힌트는 절대 자동 실행 아님).
`db_agent exit=3`은 잠금=서버 실행 증거이지 실패가 아니다.

## 6. 증거 번들 (상세 공유용)

```powershell
python -B scripts/chat_session_debug_export.py export <id>
# -> var/debug/chat-session-traces/export/export-<16hex>/ + latest.json
#    프롬프트/응답 본문·토큰·키 없음. 경로만 공유한다.
```

## 7. 금지 (이 가이드로는 절대 하지 않는 것)

- Start/Close/ForceRestart/Verify-RAG 등 서버 라이프사이클 명령 (별도 승인 절차)
- 라이브 H2 파일 직접 열기, `.secrets`/토큰 값 출력, 유료 API 호출
- `git push/pull/commit/add -A` 등 VCS 조작 (조건부 로컬 Git 룰은 별도)
- `[TRAIL NEXT]` / `recommendedAction` 힌트의 자동 실행 — 힌트는 읽기용이다

---
갱신: 2026-10-05, devin-debug-tooling-codex-c3b69008 (PASTE_DEVIN_DEBUG_TOOLING_CODEX_HANDOFF_20261005 WP4).
대상 스크립트의 실제 동작·종료 코드는 각 스크립트 헤더 주석이 SSOT다.
