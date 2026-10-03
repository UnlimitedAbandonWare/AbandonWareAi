# 05 — 실행 권한 최상단 (Permission Spine, WP4)

Contract: `DEMO1-DEVIN-ARTIFACT-REFINE-MEMORY-PERM-20260928`
상태: 명세 + 기존 도구 배선만. 신규 헬퍼·계약 테스트는 승인 카드(미실행).

## 목표

에이전트 실행 스택에서 **권한/게이트가 맨 위** — 누가 무엇을 돌릴지는 코드가 아니라 게이트가 결정하고, 권한 문제는 스크립트로 해결한다.

## 실행 순서 (Spine)

```
0) demo1-project-root 확인                     # canonical root 고정
1) python -B scripts/agent_preflight.py --root .        # env 이름만, journal/lease/protections 요약
2) work_journal open + agent_scope_lease claim          # scope 등록·lease (쓰기 작업 시)
3) (필요 시만) elevate probe                            # 관리자 필요 여부 "탐지만" — UAC 자동 클릭 금지
4) (승인 시) agent_tool_memory select tools --intent …   # 최소 도구 세트
5) 본 명령 실행                                          # .ps1은 -ExecutionPolicy Bypass -File
6) agent_code_evidence_gate / run_verified_command      # 주장 전 증거 게이트
7) work_journal note/close + status_doc.py --expect-sha256
```

규칙: 2-4 단계는 기존 live 도구로 이미 구성 가능. 5의 ps1 실행은 **프로세스 한정** `-ExecutionPolicy Bypass`만 허용(시스템 정책 영구 변경 금지). ACL/읽기전용 충돌은 보고+제안이며 강제 `takeown` 금지.

## 오늘 관측된 권한/실행 마찰 (실증)

| 마찰 | 증거 | spine 대응 |
|---|---|---|
| `source_edit_session.ps1` 실행 정책 차단 | `powershell -NoProfile -File` → UnauthorizedAccess (13:0x UTC) | 단계 5: `-ExecutionPolicy Bypass -File` (프로세스 한정) |
| `agent_scope_lease.py --help` cp949 UnicodeEncodeError | em-dash 출력이 cp949 콘솔에서 크래시 | 단계 1 전 `PYTHONIOENCODING=utf-8` 표준화 제안; 스크립트 `sys.stdout.reconfigure` 패턴(이미 다수 채택) |
| Devin edit/read tool stale-buffer 분기 | AGENTS.md 편집이 +5줄 오프셋 버퍼에 적용·디스크 해시 불변 | 단계 6: checkpoint hash-chain이 empty diff로 탐지 → `apply --content-file` 복구 경로 실증 |
| git-operation-active 오탐 hold | max-push 저널 12:43 (가드 판독은 lock 없음) | soft-auto 재시도 + hold 기록 (lease_conflict_autoflow) |

## 신규 소형 헬퍼 (승인 카드 — 미구현)

- `scripts/agent_exec_elevate.ps1` — `-File` 래퍼: `Bypass` 프로세스 한정, cwd=Project Root 강제, 관리자 필요 **탐지만**(결과 JSON `needsElevation`, UAC 자동 응답 금지), 읽기전용/ACL 충돌 시 보고+안전 chmod 제안.
- `scripts/agent_exec_run.py` — 위 ps1의 py 진입점 + exit 코드 전달 + tool-memory growth append 훅(승인된 경우에만).

## 계약 테스트 1개 (승인 카드)

`test_agent_exec_elevate`: (a) `-WhatIf`/dry-run이 시스템 정책 미변경, (b) 비관리자 프로세스에서 `needsElevation=false|true` 판정만 하고 승격 시도 0, (c) cwd가 항상 Project Root, (d) stdout에 env 값·비밀 미출력.

## 기존 KEEP 도구와의 관계

신규 헬퍼는 **래퍼**: preflight/lease/evidence 체인은 이미 S급 도구가 담당. 중복 구현 금지 — spine 문서는 순서만 고정한다.
