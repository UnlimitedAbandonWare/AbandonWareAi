---
name: demo1-ops-surface-thin
description: >-
  Use when demo-1 agent-prompts/targets ops residue bloats the ops surface;
  archives closed directives only via a WhatIf-first Move script. Never a
  replacement for demo1-safe-cleanup or demo1-completed-directive-cleanup.
---

# demo1-ops-surface-thin (OSTP)

레일은 두고, 닫힌 종이만 서랍으로. 운영 표면(지시서·targets 잔여물)만 얇게 —
코드·스킬·스크립트·레일은 건드리지 않는다.

## Do
1. Project root: `C:\AbandonWare\demo-1\demo-1\src`
2. 먼저 보고만:
   ```powershell
   Ops-Surface-Thin.bat            # = scripts\demo1_ops_surface_thin.ps1 (WhatIf 기본)
   ```
3. 매니페스트 확인: `var/debug/ops-surface-thin-<ts>.json` —
   `wouldMove`/`kept`/`reason` 검토 후 사용자 승인 시 `Ops-Surface-Thin.bat -Apply`.
4. Apply는 `agent-prompts/_archive/<yyyyMMdd>/`로 Move + `agent-prompts/INDEX.md`
   (Live vs Archived) 재생성. 복원 = Move back + INDEX 갱신.
5. 옵션: `-IncludeEmptyLeftovers`(빈 ui-debug-*/autoevolve_debug/_patch_artifacts),
   `-ApplyGradleHomes`(여분 `.gradle-*` 홈 — 빌드 캐시라 기본 off, 명시 요청 시만).

## Don't
- 승인 없이 `-Apply` 금지 — WhatIf JSON을 먼저 보여준다.
- KEEP 보존 규칙을 우회하지 않는다: 날짜 없는 구조 디렉토리(agents/data/traits/out),
  `ROOM_BLURB` 배달 대기 카드, live 참조(AGENTS/스킬/문서) 있는 디렉토리,
  in-flight 저널 매치, 닫힘 증거 없는 신선 디렉토리는 절대 비대상.
- `.agents/skills`, `scripts/`, Start-*/Debug-*/Close-* bat, work ledger/lease/
  conditional Git 등 바이브 레일 — thinning 대상 아님.
- 디스크/build/logs 정리는 `$demo1-safe-cleanup`, 대화 아카이브는
  `$demo1-completed-directive-cleanup` — 이 스킬은 대체가 아니라 연결만.

## Related
- 엔진: `scripts/demo1_ops_surface_thin.ps1` · 래퍼: `Ops-Surface-Thin.bat`
- 디스크 정리: `$demo1-safe-cleanup` (`scripts/demo1_safe_cleanup.ps1`)
- 작업 등록/복구: `$demo1-work-ledger`
