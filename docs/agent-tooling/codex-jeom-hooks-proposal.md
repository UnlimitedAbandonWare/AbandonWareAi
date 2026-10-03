# codex-jeom-hooks-proposal — 점(dot) 시대의 repo hook 보강 제안 (미적용)

Contract: DEMO1-DEVIN-CODEX-JEOM-GROKBOT-ACCESS-20261002 · 2026-10-02 · Devin
**이 파일은 제안이다. `.codex/hooks.json`은 건드리지 않았다.** 적용은 사용자 승인 후에만.

## 현재 상태 (읽기 확인, sha 기준은 변경될 수 있음)

`.codex/hooks.json` 등록 hook:

| 훅 | 매처 | 스크립트 | 하는 일 |
|---|---|---|---|
| UserPromptSubmit | (all) | `.codex\hooks\project_capabilities_hook.ps1` | 리소스 capability 컨텍스트 주입 |
| UserPromptSubmit | (all) | `.codex\hooks\source_edit_root_hook.ps1` | 소스 수정 preflight 루트 triage |
| PreToolUse | `apply_patch\|write\|edit\|notebook_edit` | `scripts\devin_pre_edit_guard.ps1` | live lease 충돌 시 쓰기 차단(exit 2) |
| PreToolUse | `Bash` | `scripts\agent_work_guard.ps1` | 경로 분류·재시도 루프 브레이크 |
| PostToolUse | `Bash` | `scripts\agent_work_guard.ps1` | 동일 가드 기록 |

## 갭 — 점(또는 어떤 새 실행 주체)이 쳐도 현재 못 막는 것

명령 **텍스트**를 보는 deny 규칙이 없다. 지금은 경로 패턴·재시도 횟수만 본다.

| 위험 명령 | 현재 | 제안 matcher 정규식(예시) |
|---|---|---|
| `git push`, `commit`, `reset`, `clean`, `checkout --`, `restore`, `stash`, `add -A`/`add .`, `--no-verify` | 미차단 | `git\s+(push\|commit\|reset\|clean\|stash)\b`, `git\s+add\s+(-A\|\.|--all)`, `--no-verify` |
| `.secrets`, `auth.json`, `.sandbox-secrets`, `.env` 읽기 | 미차단 | `(\.secrets\|auth\.json\|\.sandbox-secrets\|\.env\b)` — 단, 파일명만으로 오탐 주의(`.env` 글롭과 충돌 가능) |
| `Remove-Item`/`rm`/`del` 계열 | 미차단 | `\b(Remove-Item\|rm\b\|del\b)\b.*(-Recurse\|-rf\b)` |
| `gradlew` 전체 suite(테스트 지정 없음) | 미차단 | `gradlew[^\r\n]*\btest\b(?!.*--tests)` |
| 서버 재기동(`Start-RAG`, `ForceRestart`) | 미차단 | `Start-RAG\|ForceRestart\|stop_rag_stack` |
| 외부 유료 API 직접 호출 | 미차단 | `api\.openai\.com\|api\.anthropic\|generativelanguage` (조회만도 결제 연결 가능) |

## 제안 형태

- 새 PreToolUse hook 한 개: `matcher: "^(Bash|exec|shell|run_terminal_command)$"`,
  `commandWindows: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jeom_command_deny_guard.ps1`.
- 가드 스크립트는 위 표의 정규식으로 명령 문자열만 검사하고, 매치 시 `exit 2` + stderr에
  차단 사유(규칙 이름만, 명령 전문 로그는 64자로 자름). fail-open 설계(exit 1) 유지 —
  실제 강제는 여전히 lease·checkpoint·rules 책임.
- 오탐 관리: 프로젝트에서 자주 쓰는 읽기 명령(`git status`, `Get-Content` 일반 파일)은
  allowlist 우선 평가. `.env`는 `\.env($|[^a-z])`로 축소.
- 적용 전 회귀 확인: `python -B -m unittest scripts.test_agent_work_guard -v` 계열이
  통과하는지 + Devin/Codex/Grok hook 래퍼 3곳(.devin/.codex/.grok)에 같은 규칙을 심을지
  결정 — 이 작업 범위 밖이므로 제안으로만 남긴다.

## 왜 이렇게까지 하지 않았나

점의 로컬 작업이 repo `.codex/hooks.json`을 타는지는 (e) `추정` 단계다. hooks가 적용되더라도
fail-open이고, hooks가 안 타도 Codex approval policy가 마지막 방어선이다. 따라서 **당장의
방어는 Codex 측 권한(config 제안) + Custom rules + 역할 스킬**이 맡고, 이 파일은 그 다음
보강이다.
