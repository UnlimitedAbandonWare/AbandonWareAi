# Clean(Cline) 실행 지시서 — Codex 홈/경로 전면 정리
날짜: 2026-09-26 KST  
수신: **Clean (Cline)**  
작성: Grok Bot (검증 패킷 기준)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
CODEX_HOME: `C:\Users\nninn\.codex`

## 한 줄 목표
죽은 경로 고치고, 세션/캐시 비대를 격리·정리하고, 구식 GPU/repair 지침을 보강·삭제한 뒤, 다시 안 생기게 최소 가드를 남긴다.

## 자세 (필수)
- Prototype Light / 저가드레일 유지.
- **비밀 금지:** `auth.json`, API 키, `.sandbox-secrets`, env 값 **출력·커밋·채팅 붙여넣기 금지**. 존재 여부만 보고.
- **git:** push / `add -A` / history rewrite 금지. 문서·스크립트만 손대면 `scripts/conditional_local_git.py` selective commit.
- **삭제 원칙:** 가능하면 **이동(quarantine/rescue)** 먼저. 즉시 `rd /s` 금지. sqlite live DB 수동 DELETE 금지.
- **건드리지 말 것:** `approval_policy`, `sandbox_mode`, windows `sandbox=elevated` (바이브용으로 열어둠 — 보고만).
- memories: `generate_memories=false`, `use_memories=false` **유지** (켜지 말 것).
- remote: AbandonWareAi only. AbandonWare3 언급해도 fetch/push 금지.

## 검증된 현황 (건드리는 이유)
| 항목 | 수치/사실 |
|---|---|
| 죽은 경로 | `AbandonWareX` / `C:\AbandonWareX` → **존재하지 않음** |
| sessions | ≈2772 rollout / **~9.52–9.75 GB** |
| session_index | 1548줄 vs 2772파일 (**orphan +1224**) |
| mtime &gt;7일 | ≈2710 / **~9.08 GB** |
| visualizations | ≈**7.5–7.7 GB** (gradle caches ≈1.66 GB) |
| plugins staging | `.plugin-appserver` ≈397MB+ |
| integrations/gemini-agy | ≈589MB |
| CODEX_HOME 주요 합 | ≈**19.7 GB** |
| 지침 충돌 | 전역 AGENTS: 3090 PL~90% vs 프로젝트 AGENTS: RESOLVED/ACTIVE + L26이 구식 `gpu-lane-repair-20260924` |
| OK | origin AbandonWareAi, hermes/codex.exe/awx 스크립트 존재, F:\git 존재 |

---

## 작업 순서 (이 순서대로)

### 0) 사전
1. Codex Desktop/CLI가 무거운 작업을 안 하는지 확인(가능하면 종료 또는 idle).
2. 백업 디렉터리 생성:
   - `C:\AbandonWare\_rescue\codex-paths-fix-20260926\`
   - `C:\AbandonWare\_rescue\codex-quarantine-20260926\`
3. `Copy-Item $env:USERPROFILE\.codex\config.toml C:\AbandonWare\_rescue\codex-paths-fix-20260926\config.toml.bak`
4. `Copy-Item $env:USERPROFILE\.codex\AGENTS.md C:\AbandonWare\_rescue\codex-paths-fix-20260926\AGENTS.md.bak` (있으면)

### 1) 해결 — P0 죽은 AbandonWareX 경로 (**필수**)
**대상:** `C:\Users\nninn\.codex\config.toml`  
**행동: 개선(교정) + 유령 블록 삭제**

1. MCP filesystem `args` / `cwd` 의  
   `C:/Users/nninn/AbandonWareX`, `C:\AbandonWareX` →  
   **`C:/AbandonWare/demo-1/demo-1/src`** (기존 따옴표/슬래시 스타일 유지).
2. `[projects.'c:\abandonwarex']` 블록 **삭제**.  
   이미 `[projects.'c:\abandonware\demo-1\demo-1\src']` 가 있으면 그걸로 충분.
3. `enabled = false` 인 MCP라도 **문자열은 고친다**(나중에 켤 때 재발 방지).
4. 다른 살아 있는 projects (`f:\macnas`, `c:\web_x`, `c:\web\_x`, Documents 등)는 **삭제하지 말 것**.

**검증:**
```powershell
Select-String -Path "$env:USERPROFILE\.codex\config.toml" -Pattern 'AbandonWareX|abandonwarex' -CaseSensitive:$false
# 기대: 매치 0
Test-Path 'C:\AbandonWare\demo-1\demo-1\src'  # True
```

### 2) 해결 — P1/P1b/P7/P8 세션·viz 비대 (**필수**, 삭제=격리 이동)
**도구:** `C:\AbandonWare\demo-1\demo-1\src\scripts\codex_home_quarantine.py`  
**행동: 삭제하지 말고 rescue로 이동**

```text
cd C:\AbandonWare\demo-1\demo-1\src
python scripts\codex_home_quarantine.py candidates --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926
python scripts\codex_home_quarantine.py preview --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926
python scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926 --dry-run
python scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926 --classes=child-stale-no-evidence
python scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926 --classes=viz-gradle-cache
# 안전하면 이어서 한 클래스씩:
# tmp-globalstate / stale-db-copy / automation-stale
python scripts\codex_home_quarantine.py status --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926
```

**금지:**
- `codex-quarantine-9only-20260919` 롤아웃을 sessions로 **재주입** 금지.
- visualizations 전체 `rd /s` 금지 (gradle-cache 클래스만).
- `thread_history_1.sqlite` / `logs_2.sqlite` 직접 DELETE 금지.

**검증:** sessions GB 전후, preview/apply moved count, `status` 기록.

**선택 보강:** `codex_home_quarantine.py` 기본 `RESCUE=...\20260919` → `...\20260926` 주석 또는 기본값 1줄 변경 + dry-run으로 확인.

### 3) 해결 — P3/P9 지침 충돌 (**필수**)
#### 3a) 전역 — 개선
**대상:** `C:\Users\nninn\.codex\AGENTS.md` Hardware note  
**행동: 개선(교체)**  
“3090 abnormal / power limit ~90%” 문단을 아래로 **교체**:

> RTX 3090 is the primary local GPU on DESKTOP-M5NOV6K (aux/dedicated power resolved 2026-09-24). Prefer active 3090 for local Ollama/embedding/GPU work. Do not skip 3090 for presumed power-peak. Do not change GPU/system settings from agent initiative.

#### 3b) 프로젝트 — 삭제/보강
**대상:** `C:\AbandonWare\demo-1\demo-1\src\AGENTS.md`  
**행동:**
- L26 근처 `Current repair brief for Codex: agent-prompts/gpu-lane-repair-20260924/...` **삭제**하거나  
  `ARCHIVED — power issue resolved; see RTX 3090 ACTIVE block` 한 줄로 강등.
- `<!-- BEGIN DEMO1-RTX3090-WATCH -->` RESOLVED/ACTIVE 블록은 **유지·보강**(이미 맞으면 손대지 말 것).

### 4) 개선 — P10 trust 과다 (선택, 하지만 권장)
**대상:** `C:\Users\nninn\.codex\ollama-launch.config.toml`  
**행동: 개선**  
`[projects.'c:\users\nninn'] trust_level = "trusted"` 를 제거하고,  
필요하면 `[projects.'c:\abandonware\demo-1\demo-1\src'] trust_level = "trusted"` 만 남김.  
(홈 전체 trusted는 반경이 과함.)

### 5) 삭제(이동) — P11/P12 디스크 잔여 (권장)
Codex 종료 후:

1. **P11 plugins staging**  
   `~\.codex\plugins\.plugin-appserver*` 및 `..plugin-appserver.staging-*` 를  
   `C:\AbandonWare\_rescue\codex-paths-fix-20260926\plugins-staging\` 로 **이동**.  
   살아 있는 `plugins\cache` 정상 사용분은 유지.

2. **P12 integrations/gemini-agy**  
   최근 7일 내 사용 흔적(mtime) 있으면 **유지**.  
   미사용이면 같은 rescue 아래로 **이동**(삭제 아님). 확실치 않으면 이동 보류 + 보고.

### 6) 보강 — P13 features 플래그 (선택)
**대상:** `config.toml`  
`[features] memories = true` 인데 generate/use는 false.  
**권장:** `memories = false` 로 정렬 **또는** 주석으로 “feature flag on / runtime off intentional” 명시.  
generate/use를 true로 켜지 말 것.

### 7) 보강 — 재발 방지 (짧게)
1. Project Root / CODEX 경로 SSOT를 `docs/ai-memory/AGENT_MEMORY_ROUTING.md` 또는 `AGENTS.md`에 한 줄 포인터:  
   “Codex MCP filesystem root = `C:\AbandonWare\demo-1\demo-1\src` only.”
2. 기존 메모리 지시서와 중복 실행 말고 이 지시서의 quarantine 명령을 **한 번만** 실행.  
   참조: `agent-prompts/clean-codex-devin-memory-optimize-20260926` (도구 동일, 경로는 본 SSOT 우선).
3. `thread-writer-locks`에 stale lock만 있으면: **writer PID 없는 0바이트/오래된 lock**만 rescue로 이동. live lock은 건드리지 말 것.

### 8) 하지 말 것 (명시)
- secrets 출력
- AbandonWare3 remote 추가/push
- approval/sandbox harden
- memories 자동생성 켜기
- 9only → sessions 복원
- live sqlite VACUUM/DELETE로 “청소”
- 사용자 다른 projects trust 일괄 삭제

---

## Done when (체크리스트)
- [ ] `config.toml`에 AbandonWareX 문자열 **0**
- [ ] quarantine preview + 최소 `child-stale-no-evidence` apply(또는 dry-run+차단 사유) + sessions GB 전후
- [ ] `viz-gradle-cache` apply 시도 또는 사유
- [ ] 전역 AGENTS GPU 노트 갱신
- [ ] 프로젝트 AGENTS L26 구식 repair 포인터 삭제/강등
- [ ] (권장) ollama-launch 홈 trust 축소
- [ ] (권장) plugin staging rescue 이동
- [ ] secrets 미출력, 결과 요약만 사용자에게

## 사용자에게 보고할 형식
```text
DONE / PARTIAL
P0: ...
P1/P8 quarantine: moved=N, sessions_GB before→after
P3/P9 AGENTS: ...
P10/P11/P12: done|skipped reason
NOT_RUN: ...
```

## 참조
- `scripts/codex_home_quarantine.py`
- `docs/codex-session-cleanup-execution-20260919.md`
- `docs/ai-memory/SESSION_MEMORY_HYGIENE_AUDIT_20260919.md`
- `docs/ai-memory/AGENT_MEMORY_ROUTING.md`
- `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919\apply-9only.jsonl` (클래스 템플릿만)