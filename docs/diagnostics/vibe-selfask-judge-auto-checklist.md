# vibe-selfask-judge-auto — acceptance checklist + judge samples

Contract: `DEMO1-DEVIN-SELFASK-JUDGE-AUTO-VIBE-20260929`.
SSOT skill: `.agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md`.

## Acceptance checklist

| 항목 | 상태 | 근거 |
|---|---|---|
| `.agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md` (SSOT) | done | 파일 생성 + `quick_validate.py` + family validator |
| `AGENTS.md` 짧은 포인터 | done | `DEMO1-VIBE-SELFASK-JUDGE-AUTO` 블록 append (전체 개편 없음) |
| `.grok/rules/` 얇은 포인터 | done | `demo1-vibe-selfask-judge-auto.md` — 본문 복제 없음, 이중 미러 없음 |
| `data/agent-handoff/devin-selfask-judge-auto-20260929/FOR_CODEX.md` | done | 핸드오프 문서 |
| router intent (`skills-intent-index.yaml`) | done | `vibe-selfask-judge-auto` intent → primary skill resolve 확인 |
| receipt DDL 가짜 ASK → Judge AUTO 샘플 표 | done | 아래 표 |
| 금지 항목 준수 | done | F01-B/TRACE 제품 Java diff 0, commit/push 없음, 비밀 출력 없음, 기존 스킬 삭제 없음 |

## Judge 샘플 표 (loop 동작 예시 — 실행 아님, 판정 예시)

| 시나리오(가짜 ASK 후보) | POSITIVE | NEGATIVE | COUNTEREXAMPLE | NEUTRAL JUDGE |
|---|---|---|---|---|
| **receipt DDL**: `awx_receipt` 로컬 테이블 1개를 additive로 만들까? (가짜 ASK) | `db_agent.py apply --dry-run` 후 additive CREATE TABLE — 로컬·가역 | 실 서버 DB 아님 확인 필요; lock exit3이면 읽기전용 fallback만 | JVM이 lmsdb 잠금 중이면 apply 실패 → dry-run만 남기고 기록 | **AUTO**: dry-run 통과 시 additive DDL 진행, `SELFASK_JUDGE AUTO` 기록 |
| `git commit` 요청 | own path만 selective add + secret scan + 1 local commit (`agent_git_vibe_commit.py`) | push는 항상 금지 | foreign staged path 섞이면 스캔 실패 | **ASK_ONCE**: staged set이 순수 own-path인지 확인 질문 1개 (아니면 STOP) |
| `task_ask` 활성화 플래그 켜기 | 없음 — 제품 동작 변경 | InMemory/callback 활성화는 계약상 forbidden | 활성화 시 외부 callback fanout | **HOLD**: 명시 contract 대기 (resume=사용자 지시) |
| foreign lease 파일 덮어쓰기 | 없음 | lease는 소유권 증거 — 대기/조정만 | heartbeat 살아있는 lease 강제 해제 = 충돌 | **HOLD**: lease 만료/해제 후 재개 |
| 스킬 docs 한 줄 수정 | 좁은 패치 + checkpoint | 없음 | — | **AUTO** |

## 사용법 (agent)

1. ASK 직전 루프 4단 실행 → verdict 하나만.
2. `python -B scripts/work_journal.py note --root . --task <taskId> --kind plan --text "SELFASK_JUDGE <verdict> | <reason> | <paths>"`.
3. AUTO → 진행. ASK_ONCE → 질문 1개만. HOLD → blocker+재개 조건 보고.
