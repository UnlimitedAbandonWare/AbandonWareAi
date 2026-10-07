# DEMO1 Agent BAT Toolkit (SSOT)

에이전트(Devin/Codex/agy/Grok CLI)가 demo-1 체크아웃에서 긴 파이썬/파워셸 명령을
직접 타이핑하는 대신 준비된 BAT를 단계별로 호출하기 위한 안내다. 모든 BAT는
프로젝트 루트(`C:\AbandonWare\demo-1\demo-1\src`)에서 실행한다.

## 비대화형 실행 규칙 (Hang 방지)

- 핵심 BAT는 상단에서 `DEVIN`/`AGENT_SESSION`/`AWX_AGENT_WORKER`/`CI`/
  `CONTINUOUS_INTEGRATION` 환경변수를 감지해 `AWX_RAG_NO_PAUSE=1`을 자동 설정한다.
- 수동으로 끄려면 `--no-pause` 또는 `-NoPause` 인자를 넘기면 된다(전달 인자에서
  자동 제거되므로 하위 스크립트로 넘어가지 않는다).
- PowerShell에서 호출: `cmd.exe /c ".\<BAT명>"` 또는
  `$env:AWX_RAG_NO_PAUSE="1"; .\<BAT명>`.
- 에이전트 환경이 아니면(인터랙티브 콘솔) 기존처럼 마지막에 `pause`가 걸린다.

## 단계별 정답 타점

### 진입 / 상태 확인

| BAT | 용도 |
|---|---|
| `Signal-Digest.bat` | 세션 진입 시 필수 플릿 현황 1초 출력 (lease, journal, handoff, git dirty) |
| `Agent-Scope.bat who` | 현재 점유자 확인 |

### 충돌 해제 / 정리

| BAT | 용도 |
|---|---|
| `Reclaim-Lease.bat` | stale/expired lease 자동 회수 + 점유자 who (live lease는 건드리지 않음) |
| `Reclaim-Lease.bat <taskId>` | 완료 태스크 점유 즉시 반환 (`agent_scope_lease.py done --task`) |
| `Safe-Cleanup.bat` | 안전 디스크 정리 (WhatIf 미리보기 기본) |

### 서버 / 런타임 제어

| BAT | 용도 |
|---|---|
| `ForceRestart-RAG.bat` | JVM 템플릿 파일락 해제 + 클린 백엔드/DevWatch 재기동 (브라우저 팝업 없음) |
| `Start-RAG.bat` | dev 런타임 시작 (브라우저 열기 포함) |
| `Close-RAG.bat` | dev 런타임 + 재시작 워처 정지 |

서버 수명주기 상세는 `docs/agents-rules/DEMO1-SERVER-LIFECYCLE-VERIFY.md`.

### 오프라인 / 경계 검증

| BAT | 용도 |
|---|---|
| `Verify-Chat-Fast.bat` | node 스트림 경계 24개 테스트 즉시 검증 (~400ms) |
| `Verify-RAG.bat` | Spring 빌드 + 런타임 종합 검증 (exit 0/3/6) |

### 로컬 Git 정리

| BAT | 용도 |
|---|---|
| `Git-Ship-Easy.bat` / `Git-Ship.bat` | 조건부 로컬 git 레인 (status/scan/commit/push 게이트) |

## 연계

- 도구 추천 스캐너: `python -B scripts/demo1_tool_placement_scan.py scan "<ask>"`
  — 위 BAT들이 `lease-conflict`/`dev-reload`/`signal-digest`/`verify-chat-fast`/
  `bat-toolkit` 트리거의 1순위 call로 등록되어 있다.
