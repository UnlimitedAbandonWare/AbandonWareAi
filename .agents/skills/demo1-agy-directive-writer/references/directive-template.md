# Directive template (skeleton)

Korean, paste-ready. Fill every section; delete a section only by writing
"해당 없음" — never silently drop one. This is a skeleton, not boilerplate:
everything you assert must come from a live re-read (SKILL.md §2).

---

```
<!-- Devin 지시서일 때 첫 줄 필수 스킬 프리셋 (누락 금지): -->
@objective-executor @demo1-devin-source-orchestrator @demo1-vibe-max-agency @demo1-core-request-router @meta-rayban-display @demo1-meta-display-simple-caption @demo1-meta-display-resume @frontend-display-debug @demo1-conversate-hint-context @demo1-evidence-debugging @demo1-repairing-from-live-evidence @rag-search-diagnosis @search-zero-result-recovery @safe-source-edit @compile-verify-smoke @start-rag-reload @positive-negative-neutral-judge @SKILL.md

[ANTI-STOP] 이 지시서는 읽고 끝내는 것이 아니라, 아래 명시된 작업을 실제로 수행하고 검증하는 실행 지시서입니다.
[<TARGET> 지시서] <주제 한 줄> — <YYYY-MM-DD HH:mm KST>
파일 ID: PASTE_<TARGET>_<TOPIC>_<YYYYMMDD>
Project Root: C:\AbandonWare\demo-1\demo-1\src
금지 (이 작업 전체): <한 줄 — 예: 제품 소스 수정 0 · commit/push/add -A 0 · 비밀값 출력 0 · 유료 호출 0>

0. 목표 (한 문장)
<무엇을 끝내면 완료인지>

1. 라이브 사실 (읽기 전용 확인, <YYYY-MM-DD HH:mm~HH:mm KST>)
- <관찰 사실 + 시각. 추정과 사실을 섞지 말 것>

2. 기준 해시 표 (지시서가 이름을 붙이는 모든 파일)
| 경로 | sha12 | git 상태(대상 에이전트가 Git을 허용할 때만 — Devin 지시서는 `-`) | 권한 (읽기/수정/생성/금지) |

3. 입력 판정표 (첨부/주장 재대조 결과)
| 주장 | 판정 (RIGHT/WRONG/STALE/UNVERIFIED) | 근거 (file:line 또는 명령+출력) |

4. WP (최대 5개, 각 항목 한 오너 — 오너는 작업별 명시 권한과 대상 파일 종류가 정하고, 역할 분업표는 기본 관행일 뿐이다)
- WP<n> [<오너>] <제목>
  - 증거: file:line
  - RED 검사: 대상 파일 종류에 맞는 실제 focused 명령 — JVM이면 `.\gradlew.bat :test --tests <FQCN> --no-daemon --console=plain; "exit=$LASTEXITCODE"`, 스크립트/검증기면 기존 `python -B scripts/test_*.py`, 문서 단독이면 해당 validator·링크 검사 (무조건 Gradle 금지)
  - 최소 수정: <무엇을>
  - GREEN: <명령 + 기대 exit>
  - 완료 조건: <관측 가능한 것>

4-1. SKILL_PACK (WP가 스킬 생성·개편을 포함할 때 필수 — 아니면 "해당 없음")
| WP | 등급(S/M/L) | 스킬 경로 | companion 경로 | intent | 검사 명령 |
|----|----|----|----|----|----|
| WP<n> | <등급> | .agents/skills/<id>/SKILL.md | <references/ · scripts/x.py+test_x.py · docs/agents-rules/DEMO1-*.md 중 ≥1> | skills-intent-index intent명 | python -B scripts/agy_skill_pack_check.py --skill <폴더> --grade <등급> |
- 기준: docs/agents-rules/DEMO1-AGY-SKILL-PACK.md. M/L인데 SKILL.md 단독 = FAIL. @skill 5개+ 나열 = SKILL_SCATTER.

5. 검증 명령 블록 (명령마다 exit 기록)
cd <repo>
<각 명령은 ; "exit=$LASTEXITCODE" 로 끝낸다>

6. lease · journal 절차
- lease는 Source/실행 파일 대상에만 — repo Markdown(`.agents/skills/**.md`, `docs/` 등)은 lease 없이 journal+checkpoint만 (demo1-work-ledger §2)
- journal은 repo 안 파일 변경 작업에만 연다 — 읽기 전용이나 프로젝트 밖 산출(예: Downloads 단독 지시서)에는 강제하지 않는다
- python -B scripts/agent_scope_lease.py check --path <대상> → claim … → done
- python -B scripts/work_journal.py open … note … close
- lease 걸린 파일은 BLOCKED_LEASE로 표기하고 우회 금지
- lease 대기는 세션 종료가 아니다 — $demo1-parallel-auto-resume(lease-wait→resume-check→exit별 자동 재개)

7. 루프 · 이관
- 자가 수정 ≤ 3회/원인 → 넘으면 TOSS Codex / FOR_GROK / FOR_CLEAN / FOR_DEVIN / ASK
- AUTO: 로컬·가역·계약 범위 안 작업은 묻지 않고 진행

8. 종료 상태 (하나만)
DONE_OFFLINE | DONE_LIVE | ALREADY_COVERED | BLOCKED_LEASE | NOT_RUN_ENV | APPROVAL_REQUIRED

9. 보고 표
| 항목 | 결과 | 증거 경로 |

10. 사용자 결정 (최대 6개, 권장값 먼저)
- U-1 <질문> — (a) 권장: … (b) … (c) …

근거 SSOT: AGENTS.md#<섹션명들>, .agents/skills/<스킬명>, docs/<경로>
```

---

Multi-session build note for any WP that compiles: `AWX_SPLIT_BUILD_OUTPUTS=1`
+ `AWX_BUILD_HOST_ID=<host>` → outputs land in `build/<hostId>/`; focused tests
only — never `clean`, never a whole `:test` sweep as a per-item gate.
