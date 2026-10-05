# EVIDENCE snapshot — primitive Debug-AI (2026-09-26, read-only probe)

Project Root: C:\AbandonWare\demo-1\demo-1\src

## Counts
- Root bats (Debug|RAG|Watch|Status|Verify): 11 (0 Trace*)
- scripts matching debug|rag|evidence|trace|timeline|journal|selfask|probe: ~81
- skills matching evidence|debug|rag|self-ask|timeline: 21
- Java name hits Debug*|Trace*|Evidence*|SelfAsk*: 165 (RagLauncher*=0)
- rag-launcher run dirs: ~157; LATEST.json present (sample runId 20260926-230555-7e83e1d4 status ready)

## Key paths
- Read-RAG-Debug.bat → scripts/read_rag_debug_trail.ps1
- LATEST writer: scripts/start_rag_stack.ps1 Write-RagRunResult
- Offline assist: tools/ai_debug_assist.py + tools/build_error_miner.py
- Skills: demo1-evidence-debugging, demo1-debugging-with-two-tools, demo1-observed-debugging-meta
- Java islands: DebugCopilotService, EvidenceGroundedTriadicDebugAdjudicator (default off)
- AGENTS: DEMO1-DEBUG-ENTRYPOINTS, DEMO1-RAG-DEBUG-TRAIL
- docs/debug-ui/README.md: not second SSOT

## Gap
nextCommand ignores failurePoint/stage/excerpt classes; no unified offline trail→hypothesis ranker in skill-free path; auto-exec must stay forbidden.

## Concurrent briefs (do not collide)
- clean-agent-loadout-harmony-20260926
- clean-codex-paths-hygiene / quarantine / OSTP — out of scope