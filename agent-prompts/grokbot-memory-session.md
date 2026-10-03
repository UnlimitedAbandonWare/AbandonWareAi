# Grokbot memory/session — copy blocks

새/자동 Grok 세션이 메모리를 읽고 쓰는 규칙을 고정하는 복붙용 문구 모음.
프로젝트 룰 SSOT는 `.grok/rules/demo1-memory-session.md` — Grok CLI가 이
루트에서 열리면 자동 로드되므로 이 파일은 Grok **앱 프로필·메모리**와
다른 cwd 자동 세션용 fallback이다. Grok 앱 UI/계정 설정은 직접 손대지 않는다.
계약: DEMO1-DEVIN-GROKBOT-MEMORY-SESSION-20260928.

## Grok profile / durable memory block (KR, 붙여넣기)

```
Project Root = C:\AbandonWare\demo-1\demo-1\src (자동 세션 포함). remote=AbandonWareAi만 (AbandonWare3 폐기). Prototype Light/proto-open, Self-Ask verified. RTX3090 전력 이슈 RESOLVED 2026-09-24 → 로컬 GPU 우선. conditional_local_git만, 비밀값 출력 금지. 메모리 쓰기: profile=활성 사실만(중복 금지), log=경로·ID 요약 ≤5줄, note=당일 휘발. 폐기 사실 — 3090 PL80 완화, AbandonWare3 remote, "전력 사유 API 임베딩 기본" — 재적용 금지. Codex handoff/goal-objective.md는 입력 아티팩트이지 내 메모리가 아님.
```

## Session-start checklist (새/자동 세션 첫 메시지에 붙여넣기)

```
1. cwd와 무관하게 Root = C:\AbandonWare\demo-1\demo-1\src (attachments/Downloads/ZIP은 입력 전용, 루트 아님).
2. "이 채팅/세션 뭐였지?" → python -B scripts/chat_session_debug_export.py status | list --since-hours 24 | export <id> — 핸드오프는 export 경로만, 레코드 본문 붙여넣기 금지.
3. 세션 헬스 → python -B scripts/agent_session_watch.py scan --agent grok --since-hours 72 — auto findings만 read-only diagnose; 세션 파일 삭제/이동 금지.
4. 다중 세션 공존 → live RAG/ForceRestart 소유는 한 세션 (계약 DEMO1-DEVIN-MULTI-SESSION-BUILD-20260928; docs/diagnostics/multi-session-build-coexist-0928.md 예정).
5. 제품 ChatSessionMetaMerger/memoryMode/pipelineSnapshot.agentWebSearch/chat.js 검색 상태는 Codex 영역 — Grok은 메모리 읽기·쓰기 계약만.
```

## 참고

- Grok 메모리 실제 경로: `~/.grok/memory-v2/workspaces/<ws>/` (topics +
  observations) — 위 profile 블록이 durable에 들어가면 episode에 폐기 사실이
  남아 있어도 새 세션이 다시 적용하지 않는다.
- Meta Display 대화 행이 필요하면 `scripts/meta_display_db_export.py`
  related export만 — live H2는 JVM이 잡고 있으면 열지 않는다.
- 프로젝트 루트 고지 문구는 기존 `agent-prompts/grokbot-default-project-root.md`
  그대로 사용 — 이 파일은 그 보완이지 대체가 아니다.
