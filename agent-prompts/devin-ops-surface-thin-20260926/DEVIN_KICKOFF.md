# Devin 지시서 — Ops Surface Thinning (약점만 상쇄, 장점 보존)
날짜: 2026-09-26 KST  
수신: **Devin**  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
Prototype Light / 저가드레일. secrets 출력·push/`add -A` 금지. remote=AbandonWareAi only.

## Self-Ask
1. **요청:** 레포 약점(지시서·targets·운영 잔여물 비대)을 개선하되, 장점(멀티에이전트 레일)은 훼손하지 말 것. 방법까지 설계.
2. **증거:** skills≈124, scripts py+ps1≈340+, agent-prompts≈38, root `targets*.json`=12, `var/`≈390MB, `__patch_drop__`≈11MB/1855 files, 여분 `.gradle-*` 2개. 기존 레일: `$demo1-safe-cleanup`, `$demo1-completed-directive-cleanup`, `archive-*`.
3. **모호:** 스킬 대량 삭제는 장점 훼손 → **금지**. Codex home(`~\.codex`)은 Clean 지시서 담당 → **이번 Devin 범위 밖**.
4. **금지:** `.agents/skills` 삭제, Start-RAG/AGENTS 코어 레일 제거, proto-open fail-close, main/app/configs/frontend 소스 리팩터를 “정리”로 포장, secrets, AbandonWare3.
5. **THE ONE:** **Ops Surface Thinning Pack** — 닫힌 지시서/낡은 targets만 archive 인덱스로 옮기고, AGENTS에 “얇은 운영 표면” 규칙 한 블록 추가. 기존 safe-cleanup에 빈 leftover만 선택 연결.

## 장점 (절대 건드리지 말 것)
- `AGENTS.md` + `.agents/skills` + `scripts/` + Start/Verify/Debug-RAG·Meta bat
- Prototype Light / proto-open DX
- 조건부 git / patch_drop / work_journal / peer-signal 등 **동시 바이브 레일**
- API routing / Ollama / Meta Display 제품 경로

## 약점 (이것만 상쇄)
운영 표면이 제품보다 빨리 두꺼워짐: root `targets-*.json` 산재, `agent-prompts/` 누적, 빈 디버그/여분 gradle 홈, AGENTS의 구식 “Current repair” 포인터.

---

## 방법명: Ops Surface Thinning Pack (OSTP)

원칙 한 줄: **레일은 두고, 닫힌 종이만 서랍으로.**

### A. 스크립트 (신규, WhatIf 기본) — THE ONE 구현체
경로: `scripts/demo1_ops_surface_thin.ps1` (+ 가능하면 `Ops-Surface-Thin.bat` 1줄 래퍼)

동작:
1. **기본 WhatIf.** `-Apply` 있을 때만 이동.
2. 분류만 하고, 삭제가 아니라 `agent-prompts/_archive/<yyyyMMdd>/` 로 **Move**.
3. 매니페스트: `var/debug/ops-surface-thin-<ts>.json` (from, to, reason, sha256 optional).
4. 규칙(보수적):
   - **targets:** root의 `targets-*.json` 중 `targets.json` 및 **최근 2개(mtime)** 만 root 유지. 나머지 → `agent-prompts/_archive/targets/`.
   - **agent-prompts dirs:** 다음 중 하나면 archive 후보  
     (a) 이름에 `202609` 이전이거나 mtime &gt; 14일 **그리고** `DONE.md`/`STATUS=done`/`ROOM_BLURB`에 DONE·완료 표기  
     (b) 또는 AGENTS/다른 live 포인터에서 **참조되지 않음**(Select-String Project Root 한정, `.git` 제외)  
     → 애매하면 **KEEP** (삭제/이동 안 함).
   - **빈 leftover dirs** (옵션 플래그 `-IncludeEmptyLeftovers`):  
     `ui-debug-*`, `autoevolve_debug`, `_patch_artifacts` 등 **파일 0개**만.  
     여분 `.gradle-chat-video-*`, `.gradle-desktop-nova-focus`는 WhatIf 목록만 하고 Apply는 **별도 `-ApplyGradleHomes`** 필요할 때만(기본 off — 빌드 캐시라 장점/속도 훼손 가능).
5. **절대 이동 금지 allowlist:** `.agents/`, `scripts/`, `main/`, `app/`, `configs/`, `docs/`(인덱스 추가 제외), `data/`, `var/meta-display-db/`, `__patch_drop__/`, `.secrets/`, `frontend/`, live `AGENTS.md`.

테스트: `scripts/test_demo1_ops_surface_thin.ps1` 또는 python unittest — WhatIf가 금지 경로를 건드리지 않는지, targets 최근 2개 보존, 참조 있는 prompt dir KEEP.

### B. 인덱스 보강
- `agent-prompts/INDEX.md` 생성/갱신: **Live** vs **Archived** 표.  
  Live = 최근 14일 또는 AGENTS/다른 스킬이 가리키는 것.
- `_archive/README.md`: “복원은 Move back + INDEX 갱신. 제품 빌드와 무관.”

### C. AGENTS.md 최소 블록 (보강, 장점 보존)
`<!-- BEGIN DEMO1-OPS-SURFACE-THIN -->` … `<!-- END -->` 추가:

- 바이브 레일(skills/scripts/Start-*)은 SSOT로 유지.
- 닫힌 지시서·낡은 `targets-*.json`은 `$demo1-ops-surface-thin`(또는 스크립트 경로) WhatIf→Apply로 archive.
- `agent-prompts/`·`data/agent-handoff/` 전량 grep 금지(기존 문구와 합치기).
- **삭제:** `Current repair brief ... gpu-lane-repair-20260924` 포인터(이미 RESOLVED 블록과 충돌). ARCHIVE 한 줄로 강등 가능.

새 스킬(선택, 얇게): `.agents/skills/demo1-ops-surface-thin/SKILL.md` → 위 스크립트 호출만. 기존 `demo1-safe-cleanup` / `demo1-completed-directive-cleanup`을 **대체하지 말고** “주기·백로그 얇게” 역할로 연결.

### D. safe-cleanup 연결 (개선, 비파괴)
`demo1_safe_cleanup.ps1`에 OSTP를 넣지 말고, 스킬/`Safe-Cleanup` 문서에  
“지시서·targets 비대 → Ops-Surface-Thin.bat (별도)” 한 줄만 추가.  
디스크 pressure의 build/logs는 계속 safe-cleanup.

### E. 범위 밖 (Devin이 하면 안 됨)
- `~\.codex` quarantine → Clean SSOT (`clean-codex-paths-hygiene-20260926`)
- 스킬 TopN 삭제, 스크립트 대량 병합
- var/ 통삭제, patch_drop 통삭제

---

## Done when
1. `demo1_ops_surface_thin.ps1` WhatIf JSON 생성 + 테스트 통과(또는 NOT_RUN 명시).
2. `-Apply`로 targets 정리 **또는** WhatIf만 하고 사유(사용자 승인 대기) — Apply는 위험 낮으면 진행.
3. `agent-prompts/INDEX.md` 존재.
4. AGENTS OSTP 블록 + 구식 gpu-lane “Current repair” 포인터 제거/강등.
5. `.agents/skills` 개수 감소 없음(또는 스킬 **+1** thin wrapper만).
6. Start-RAG/컴파일 회귀 없음(건드렸으면 focused verify; 안 건드렸으면 NOT_RUN).

## 보고 형식
```text
DONE|PARTIAL
OSTP script: ...
WhatIf/Apply: moved=N kept=N
INDEX: yes/no
AGENTS block: yes + gpu pointer: removed|archived
skills_count_before→after:
NOT_RUN:
```

## 참조
- `.agents/skills/demo1-safe-cleanup/SKILL.md`
- `.agents/skills/demo1-completed-directive-cleanup/SKILL.md`
- `scripts/demo1_safe_cleanup.ps1` / `Safe-Cleanup.bat`
- `docs/PROTOTYPE_LIGHT.md`