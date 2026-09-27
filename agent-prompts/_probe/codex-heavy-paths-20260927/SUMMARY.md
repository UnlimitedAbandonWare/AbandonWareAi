# Codex heavy-path probe — 2026-09-27

Generated: 2026-09-27 10:03:05 +09:00 (Asia/Seoul)
CODEX_HOME total: 25.45 GB decimal (25,454,719,912 bytes); exists=True

## Top 8 heaviest paths
- C:\Users\nninn\.codex\sessions: 9.03 GB; 2681 files
- C:\Users\nninn\.codex\visualizations: 7.53 GB; 12843 files
- C:\Users\nninn\.codex\cache: 679.98 MB; 2104 files
- C:\Users\nninn\.codex\tmp: 603.19 MB; 2057 files
- C:\Users\nninn\.codex\integrations: 602.22 MB; 4324 files
- C:\Users\nninn\.codex\plugins: 491.70 MB; 3408 files
- C:\Users\nninn\.codex\.sandbox-bin: 310.26 MB; 24 files
- C:\Users\nninn\.codex\.tmp: 209.46 MB; 6576 files
- Heaviest direct files: thread_history_1.sqlite 2.82 GB; logs_2.sqlite 1.57 GB.

## Age probes
- sessions >7d: 2,588 files / 9.13 GB
- sessions >30d: 2,400 files / 7.31 GB
- rollouts >7d: path absent / 0
- attachments >7d: 301 files / 16.17 MB
- session dirs: 182 total; empty/no-file: 0; index-orphan count not reliable because legacy JSONL parsing had malformed/encoding-corrupted lines.

## Existing quarantine
- codex-quarantine-20260926: 618.94 MB
- codex-quarantine-9only-20260919: 161.57 MB
- codex-quarantine-20260919: 1.22 MB
- combined: 781.73 MB; prior 20260926 folder still exists.

## Project Root heavy candidates
- C:\AbandonWare\demo-1\demo-1\src\data\agent-handoff\codex-autonomy: 1.67 GB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-madasin-proof-27: 82.65 MB [PROTECTED/excluded]
- C:\AbandonWare\demo-1\demo-1\src\.gradle: 47.94 MB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-chat-repair-0868f566: 45.63 MB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-nova-focus: 44.74 MB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-meta-display: 42.56 MB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-madasin-resume: 35.69 MB [PROTECTED/excluded]
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-chat-resilience: 30.48 MB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-meta-display-wear: 25.42 MB
- C:\AbandonWare\demo-1\demo-1\src\build\desktop-devwatch-verify: 25.08 MB
- agent-prompts ZIPs: none (0 bytes).

## TIER_A_NOW — candidate only, not executed
- No confirmed stale rollouts (rollouts path absent).
- Old attachments >7d: 16.17 MB / 301 files, only after stale-use and lock check.
- tmp/demo1-audit50-gradle-home: 629.74 MB, only after active-use check.
- Duplicate rescue leftovers only after restore/lease verification; no deletion performed.
- Empty session dirs: none found.

## TIER_B_AFTER_CONFIRM
- plugins: 515.59 MB (.plugin-appserver 416.48 MB); user-facing/reinstallable.
- integrations: 631.48 MB (gemini-agy 617.27 MB); user-facing/reinstallable.
- visualizations: 8.09 GB; confirm retention/ownership before action.
- sessions >7d: 9.13 GB; never bulk-delete without active-session/lease/index verification.

## NEVER_DELETE
- auth.toml/auth.json, config.toml, active session locks, secrets/OAuth/browser cookies/credentials.
- Project Root AGENTS.md and .codex skills SSOT.
- build\desktop-madasin-* and build\verify-test-*; do not kill Java/Gradle.

## Script and notes
- Exists: 
- Invoke (dry-run first): python -B scripts/codex_home_quarantine.py [--classes a,b,c] [--limit N] [--dry-run] [--rescue=<rescue-dir>]. py/python unavailable on target, so --help was not run.
- CLEAN_KICKOFF exists; first 80 lines expose P0 and P1/P1b/P7/P8 headings.
- Root AGENTS notes: keep auth/session material device-local and unchanged; never print .secrets; use hash-bound quarantine; enable only needed plugins.

## NOT_RUN
- No quarantine/delete/move; no Java/Gradle termination; no remotes mutation; no auth/config/secret contents read or printed.

Evidence JSON: C:\AbandonWare\demo-1\demo-1\src\agent-prompts\_probe\codex-heavy-paths-20260927\SIZES.json
Summary MD: C:\AbandonWare\demo-1\demo-1\src\agent-prompts\_probe\codex-heavy-paths-20260927\SUMMARY.md
