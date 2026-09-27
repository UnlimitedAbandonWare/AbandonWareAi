# Devin 붙여넣기 — Codex 무거운 경로 당장 격리 (2026-09-27)

너는 Devin이다. 제품 Java/JS/yml은 건드리지 말고, Codex home + 검증된 캐시/핸드오프 쓰레기만 치운다.

## Roots
- Project Root: `C:\AbandonWare\demo-1\demo-1\src`
- CODEX_HOME: `C:\Users\nninn\.codex`
- Evidence (읽기): `agent-prompts/_probe/codex-heavy-paths-20260927/SUMMARY.md` + `SIZES.json`
- 기존 Clean 위생(참고): `agent-prompts/clean-codex-paths-hygiene-20260926/`
- Quarantine tool: `scripts/codex_home_quarantine.py` (move only, never delete)
- New rescue root: `C:\AbandonWare\_rescue\codex-quarantine-20260927`
- Prior rescue keep: `...\codex-quarantine-20260926` (~619MB) — 지우지 말 것. 9only 롤백 금지.

## Stance
- Prototype Light / low guardrail.
- Secrets: auth.toml/auth.json, API keys, `.sandbox-secrets`, env — 존재 여부만, 값 출력·커밋·채팅 금지.
- git: push / `add -A` / history rewrite 금지.
- Concurrent: 다른 Codex가 madasin recovery 중일 수 있음. `build\desktop-madasin-*`, `build\verify-test-*`, 해당 lease, Java/Gradle kill/삭제 금지.
- remote: AbandonWareAi only. AbandonWare3 금지.

## LIVE sizes (2026-09-27 Asia/Seoul)
| Path | Size | Action |
|---|---|---|
| CODEX_HOME total | **25.45 GB** | shrink via quarantine |
| sessions | **~9.7 GB** (>7d ~9.13 GB) | script classes only; no bulk rd |
| visualizations | **~8.1 GB** | viz-gradle-cache class only; no whole-tree delete |
| thread_history_1.sqlite | **2.82 GB** | NEVER live DELETE |
| logs_2.sqlite | **1.57 GB** | NEVER live DELETE |
| cache (prompt-boundary-probe) | **~713 MB** (~658 MB probe) | TIER_A after idle → move |
| tmp (demo1-audit50-gradle-home) | **~632 MB** (~630 MB) | TIER_A after idle → move |
| integrations (gemini-agy) | **~631 MB** (~617 MB) | TIER_B confirm |
| plugins (.plugin-appserver) | **~516 MB** (~416 MB) | TIER_B confirm |
| Project `data\agent-handoff\codex-autonomy` | **~1.67 GB** | TIER_B: stale only; keep active madasin |

## 작업 순서

### 0) Safety
1. Codex Desktop/CLI 무거운 작업 중이면 대기. 강제 kill 금지.
2. `New-Item -Force C:\AbandonWare\_rescue\codex-quarantine-20260927`
3. config.toml 백업 → `C:\AbandonWare\_rescue\codex-paths-fix-20260927\config.toml.bak`
4. before GB 기록 (Get-ChildItem Measure Length /1GB).

### 1) THE ONE — script quarantine
```text
cd C:\AbandonWare\demo-1\demo-1\src
<py> -B scripts\codex_home_quarantine.py candidates --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927
<py> -B scripts\codex_home_quarantine.py preview --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927
<py> -B scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927 --dry-run
<py> -B scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927 --classes=child-stale-no-evidence
<py> -B scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927 --classes=viz-gradle-cache
<py> -B scripts\codex_home_quarantine.py apply --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927 --classes=tmp-globalstate
# optional if safe: --classes=automation-stale ; stale-db-copy only after confirming not live codex-dev.*
<py> -B scripts\codex_home_quarantine.py status --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260927
```
Python: `py -3` 또는 `python` 또는 full path. 없으면 NOT_RUN: quarantine script.

### 2) TIER_A 수동 Move-Item → rescue\manual\
| Target | Why |
|---|---|
| `~\.codex\tmp\demo1-audit50-gradle-home` | ~630MB |
| `~\.codex\cache\prompt-boundary-probe` | ~658MB |
| `~\.codex\attachments\*` mtime>7d | ~16MB; skip if locked |
| `.tmp` orphan (not held locks) | age check |

### 3) TIER_B — 확인 전 보류 (목록만 보고)
.plugin-appserver, gemini-agy, visualizations 전체 rd, sessions 통삭제, live sqlite DELETE/VACUUM, active madasin handoff, prior rescue 삭제.

### 4) 금지
rd /s sessions|visualizations root; 9only restore; secrets print; approval/sandbox/memories-on 변경; product main\java|static JS; Java/Gradle kill; shared build wipe.

### 5) Done
```
CODEX_HEAVY_PURGE: DONE|PARTIAL
before_GB / after_GB
script_classes_applied / manual_moved / skipped_locked / TIER_B_pending / NOT_RUN
evidence: rescue path (no secrets)
```