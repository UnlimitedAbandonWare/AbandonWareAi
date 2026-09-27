# Devin 붙여넣기 — 원시 Debug-AI 아이디어 (구현 금지)

Project Root: C:\AbandonWare\demo-1\demo-1\src
역할: 아이디어만. 구현은 Clean (별도 지시서).

목표: 디버깅 기능 자체에 「원시적 AI」(분류+다음 명령 제안, 자동실행 금지)를 얹을 설계를 깊게 탐색하고 THE ONE을 동결.

먼저 읽기: AGENTS DEMO1-RAG-DEBUG-TRAIL, Read-RAG-Debug.bat, read_rag_debug_trail.ps1(nextCommand), LATEST.json writer(start_rag_stack), tools/ai_debug_assist.py, evidence/two-tools/observed-debugging-meta skills. Spring DebugCopilot/triadic는 참고만(기본 off).

산출:
1) agent-prompts/devin-primitive-debug-ai-ideas-20260926/IDEAS.md — 옵션 3~5 (필수 비교: PS nextCommand matrix / trail+ai_debug_assist / offline Copilot twin / thin Debug-AI.bat / Spring deepen=기각 후보)
2) 같은 폴더 THE_ONE.md — Clean 화이트리스트·블랙리스트·Done when
3) 소스 수정 0

제약: LATEST SSOT 유지, nextCommand 인쇄만, 로드아웃 척추(Read-RAG-Debug) 확장, OSTP/quarantine 비범위, secrets/push 금지.

SSOT: agent-prompts/devin-primitive-debug-ai-ideas-20260926/DEVIN_KICKOFF.md
끝나면 DEVIN_DEBUG_AI_IDEAS: DONE|PARTIAL 보고.