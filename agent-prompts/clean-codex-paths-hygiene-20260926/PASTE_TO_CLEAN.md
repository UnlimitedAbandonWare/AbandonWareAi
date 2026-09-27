# Clean에게 그대로 붙여넣기

너는 Clean(Cline)이다. Project Root는 `C:\AbandonWare\demo-1\demo-1\src`, CODEX_HOME은 `C:\Users\nninn\.codex`다.

목표: Codex 경로/홈 문제를 **해결·개선·삭제(격리 이동)·보강**한다. 상세 SSOT:
`agent-prompts/clean-codex-paths-hygiene-20260926/CLEAN_KICKOFF.md`

반드시:
1) config.toml에서 죽은 AbandonWareX 경로를 demo-1 Project Root로 고치고 abandonwarex projects 블록 삭제
2) codex_home_quarantine.py로 rescue=...\codex-quarantine-20260926 에 child-stale-no-evidence + viz-gradle-cache 격리(이동만)
3) ~/.codex/AGENTS.md 3090 PL90% 노트를 RESOLVED/적극 사용으로 교체
4) 프로젝트 AGENTS.md의 gpu-lane-repair-20260924 “Current repair” 포인터 삭제/강등
5) (권장) ollama-launch의 c:\users\nninn 전체 trust 축소, plugins staging·미사용 gemini-agy는 rescue로 이동
6) secrets 출력 금지, approval/sandbox/memories-on 변경 금지, 9only 재주입 금지

끝나면 DONE/PARTIAL 체크리스트 형식으로만 보고.