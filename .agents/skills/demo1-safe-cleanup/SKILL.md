---
name: demo1-safe-cleanup
description: >-
  Use when demo-1 vibe-coding hits disk pressure or stale Gradle/log/pycache
  residue is suspected; allowlisted WhatIf-first cleanup only
---

# demo1-safe-cleanup

## Why
`build/`, `logs/`, `__pycache__`, report/cache 잔여물이 디스크를 먹지만, 손으로
지우다 `main`/`app`/`.secrets`/`var/meta-display-db/lmsdb*`/handoff를 건드리는
사고가 최악이다. allowlist + WhatIf 기본 스크립트로 좁게만 정리한다.

## Do
1. Project root: `C:\AbandonWare\demo-1\demo-1\src`
2. 먼저 보고만:
```powershell
Safe-Cleanup.bat                    # = scripts\demo1_safe_cleanup.ps1 (WhatIf 기본)
```
3. JSON 확인: `var/debug/safe-cleanup-<timestamp>.json` —
   `would-delete` 목록·바이트·`skip/denied` 이유.
4. 사용자/오너 승인 문구가 있을 때만:
```powershell
Safe-Cleanup.bat -Apply
```
5. 허용 대상(이번 스프린트 범위): `build/`, `logs/`, `__reports__/`,
   모든 `__pycache__/`, 빈 잔여 디렉터리(`_patch_artifacts`, `autoevolve_debug`,
   `output`, `verification`, `pki-validation` — 파일이 하나라도 있으면 skip).
6. `build/`를 지웠으면 다음 Start-RAG/compile에서 재컴파일이 필요하다고 알린다
   (재기동 자체는 이 스킬 범위 밖).

## Don't
- 승인 없이 `-Apply` 금지. WhatIf 결과를 먼저 보여준다.
- `main/` `app/` `configs/` `src/` `data/` `var/` `.secrets/` `frontend/`
  `__patch_drop__/` `.agents/` — 스크립트 거부 목록; 우회 편집 금지.
- 살아있는 H2(`var/meta-display-db/lmsdb*`), export 스냅샷, 리스
  (`__patch_drop__/source-edit-locks`), 활성 handoff 원본 — 절대 비대상.
- lease 해제·handoff 대량 삭제·`.next`/`var/*` 정리는 2차 범위 — 이 스킬로 하지 않는다.
- 실행 중 서버가 잡은 로그 파일은 삭제 실패로 기록된다(`failed`) — 서버를 끄지 말고
  다음 정리 때 다시 보고한다.

## Related
- 엔진: `scripts/demo1_safe_cleanup.ps1` · 래퍼: `Safe-Cleanup.bat`
- 라이브 DB 컨텍스트: `$demo1-meta-display-db-export`
- 작업 등록/복구: `$demo1-work-ledger`
