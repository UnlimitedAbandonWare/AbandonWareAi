# Grokbot 메모리·세션 개량 — 2026-09-28

계약: `DEMO1-DEVIN-GROKBOT-MEMORY-SESSION-20260928`
태스크 저널: `grokbot-memory-session-0928-a6c85cd0`
역할 한계: 스킬/규칙/agent-prompts/스크립트 도움말만 — **제품 Java·JS diff 0**
(ChatSessionMetaMerger·memoryMode·`pipelineSnapshot.agentWebSearch`·chat.js는
Codex R3/M5 lease 영역).

## 1. 관측된 현재 레일 (사실 — 이 체크아웃에서 확인)

| 레일 | 경로 | 관측 |
|---|---|---|
| Project Root SSOT | `AGENTS.md` DEMO1-PROJECT-ROOT + `.agents/skills/demo1-project-root` + `agent-prompts/grokbot-default-project-root.md` | 존재. auto-session 고지 문구 있음 |
| Grok 프로젝트 룰 | `.grok/rules/` (demo1-bridge, conditional-local-git, assist 카드들) | 존재 — 메모리/세션 계약은 없었음 |
| Chat session debug | `.agents/skills/demo1-chat-session-debug` + `scripts/chat_session_debug_export.py` (`status|list|show|export`) | 존재 — Grok standing에 진입 고지가 없었음 |
| Session watchdog | `.agents/skills/agent-session-watchdog` + `scripts/agent_session_watch.py` | 존재 — 스토어 표에 grok은 있으나 `--agent grok` 기본 예시가 없었음 (scan 기본 codex) |
| Grok 앱 메모리 | `~/.grok/memory-v2/` | global MEMORY.md 비어 있음; workspaces = `abandonwareai-b3bbd780`(현재 루트용, pending observations 다수), 옛 저장소(폐기) 워크스페이스(구 루트 버킷 — topics + 구시대 pending 잔존), `bin-a56b1fc2` |
| Grok 세션 스토어 | `~/.grok/sessions/C%3A%5CAbandonWare%5Cdemo-1%5Cdemo-1%5Csrc/` | 존재 — 세션 디렉터리 35개 (이름만 확인) |

개량 입력(사용자 관측): 폐기 사실(3090 PL80/전력 완화, 옛 저장소(폐기) remote,
전력 사유 API 임베딩 기본)이 episode에 남아 새 세션을 헷갈리게 함; episode
폭주; Codex handoff/goal MD를 "내 메모리"로 착각; "이 세션 뭐였지?" 진입 부족;
watchdog Grok 예시 부족.

## 2. 변경 (After)

| 항목 | 경로 | 내용 |
|---|---|---|
| Grok standing 계약 (신규) | `.grok/rules/demo1-memory-session.md` | 활성 profile 사실 + 폐기(무시) 목록 + profile/log/note 쓰기 계약 + "내 세션" 조회·세션 헬스 명령 + Codex 경계. Grok CLI 자동 로드 |
| 앱 붙여넣기 체크리스트 (신규) | `agent-prompts/grokbot-memory-session.md` | durable profile 블록 + 세션 시작 체크리스트(루트 고정, chat-session-debug, watchdog grok, 다중 세션, Codex 경계) |
| 기존 프롬프트 보강 | `agent-prompts/grokbot-default-project-root.md` | 참고에 새 체크리스트·룰 포인터 1줄 |
| watchdog 예시 | `.agents/skills/agent-session-watchdog/SKILL.md` Entry + `scripts/agent_session_watch.py` docstring | `scan --agent grok --since-hours 72` 예시 — 코드 동작 변경 없음(도움말 텍스트만) |

신규 `.agents/skills/` 스킬은 만들지 않음 — 중복 방지 원칙상 포인터로 연결
(demo1-project-root / demo1-chat-session-debug / agent-session-watchdog 재사용).

## 3. 메모리 쓰기 계약 (SSOT = `.grok/rules/demo1-memory-session.md`)

- **profile(활성, durable):** 활성 사실 8개만 — Root, AbandonWareAi 단일 remote,
  Prototype Light/proto-open, Self-Ask verified, 3090 RESOLVED→로컬 우선,
  conditional_local_git, 비밀 미출력, 다중 세션 헤더. 같은 사실 두 번 금지.
- **폐기(무시) 목록:** 3090 PL80/전력 완화 필요설(RESOLVED 2026-09-24),
  옛 저장소(폐기) remote, "전력 사유 API 임베딩 기본"(구 워크어라운드 →
  라우팅 SSOT 순서로 환원). episode와 충돌 시 이 표가 우선, 수리 태스크로
  승격하지 않고 사용자에게 보고.
- **log(episode):** 태스크/하루 ≤5 bullet — 경로·계약 ID·verdict만. 비밀·토큰·
  PASTE 본문 저장 금지.
- **note:** 당일 휘발 힌트만; 사용자 확인 없이 profile 승격 금지.
- **비소유 구분:** `data/agent-handoff/codex-autonomy/*`, PROJECT_STATUS.md,
  `~/.codex/attachments/goal-objective.md` = 입력 아티팩트(읽기 전용),
  Grok 메모리로 흡수 금지.

## 4. Before/After

| 상황 | Before | After |
|---|---|---|
| 새 Grok 세션이 폐기 사실을 다시 제안 | 방지 수단 없음 (episode 잔존) | 룰의 폐기 표가 profile 우선순위 명시 → "이 표가 이긴다" 규칙 |
| "이 채팅 세션이 뭐였지?" | `chat_session_debug_export` 존재하나 Grok standing에 미연결 | 룰+체크리스트에 status/list/export 3명령 + "export 경로만 공유" 고지 |
| 세션 헬스 스캔 | `--agent grok` 플래그는 있으나 기본 예시는 codex만 | SKILL.md Entry + 스크립트 docstring에 grok 예시 병기 |
| 다중 세션 | 직전 PASTE가 Codex/Devin 중심 | standing 계약에 한 줄 링크 (`DEMO1-DEVIN-MULTI-SESSION-BUILD-20260928`) |
| Codex R3 경계 | 문서화만 | 룰에 명시 금지 목록 (ChatSessionMetaMerger/memoryMode/D1/chat.js) |

## 5. 검증

- `python -B scripts/agent_session_watch.py --help` → docstring에 grok 예시 표시, exit 0.
- `python -B scripts/agent_session_watch.py scan --agent grok --since-hours 72 --max-files 5` → Grok 스토어 읽기 전용 스캔 실행(읽기만; 결과는 warn/info 분류).
- `python -B scripts/chat_session_debug_export.py status` → exit 0 (레일 존재 확인).
- `python -B scripts/test_agent_session_watch.py` → 도움말 변경 회귀 없음 확인.
- 제품 diff 0: `main/java`, `main/resources/static/js` 미터치 (체크포인트 대상 외).

## 6. 한계·미포함

- Grok **앱** 메모리 내부(이미 쌓인 episode/observation 파일) 정리는 범위 밖 —
  룰은 "앞으로 쓰는 법"과 "폐기 사실 재적용 금지"만 고정. 과거 episode 삭제는
  Clean 위생 범위이며 이 작업에서 수행하지 않음.
- `docs/diagnostics/multi-session-build-coexist-0928.md`는 병렬 저널
  (`multi-session-build-coexist-0928-1034101d`)이 작성 중 — 이 문서는 계약 ID만
  링크하고 파일 존재를 전제하지 않음 (추정→존재 시 유효).
- Codex M5/R3 제품 패치·live RAG 재시작·commit/push: 전부 범위 밖, 미수행.
