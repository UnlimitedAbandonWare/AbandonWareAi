# UAW ↔ live harmony map (uaw-vibe-longrun)

- Contract: `DEMO1-UAW-VIBE-LONGRUN-SKILL-20260928-R1`
- Date: 2026-09-28 (Asia/Seoul) · taskId `uaw-vibe-longrun-skill-0928-109c1fe1`
- Status legend: **LIVE** = this checkout에서 실재 확인(이번 세션 명령 출력),
  **STALE** = UAW 표기와 live 상태가 다름, **NO_MATCH** = 주장 경로/이름이 없음.
- UAW.txt(Downloads, 316 KB)는 의도 참고만. 클래스명·DONE/LIVE 표기·수치는
  live 재검증 없이 패치 근거로 쓰지 않는다.

## 1. 재사용 부품 (brief §1 표 → live 확인)

| 역할 | UAW/brief 지목 | live 확인 (2026-09-28) | verdict |
| --- | --- | --- | --- |
| 바이브 맥스/에이전시 | `demo1-vibe-max-agency` + `Vibe-Max-Agency.bat` | skill dir + BAT 존재; AGENTS `DEMO1-VIBE-MAX-AGENCY`가 계약 SSOT | LIVE |
| 의도→스킬 1개 | `demo1-vibe-skill-router` + `scripts/demo1_vibe_skill_router.py` + `.agents/skills-intent-index.yaml` | resolve 스모크 2건이 `demo1-uaw-vibe-longrun` 반환(score 3) | LIVE |
| 요청 분류 | `demo1-core-request-router` | skill dir 존재; AGENTS CORE-REQUEST-ROUTER 블록 유지 | LIVE |
| 장기 Safe Patch | `demo1-autonomous-patch-conductor` | skill + `references/autonomous-patch-conductor-reference.md` 존재 | LIVE |
| 다봉합 brief 위상 | `demo1-devin-source-orchestrator` + `scripts/devin_task_orchestrate.py` | plan 실행됨 — 본 스킬 작성 brief를 nova-focus playbook으로 매칭(키워드 매칭 한계; 위상 지시에는 부분 적합) | LIVE (misroute 관찰됨) |
| 장시간 목표 분해 | `demo1-long-think-goal-composer` | skill + reference 존재 | LIVE |
| 툴체인 선택 | `demo1-toolchain-auto-select` | skill dir 존재 | LIVE |
| Self-Ask 최소 패치 | `self-ask-query-rewrite-safe-patch` | skill dir 존재 | LIVE |
| lease/ledger/barrier | `agent-scope-lease`, `demo1-work-ledger`, `demo1-goal-switch-barrier`, `demo1-goal-complete-stop` | 이번 세션 check(allowedOpen)·journal open·checkpoint begin·lease begin 모두 live 동작 | LIVE |
| reload/verify | `demo1-dev-reload`, `Verify-RAG.bat`, `scripts/debug_rag_stack.ps1` | 파일 존재 (status doc §2 진입점 표와 일치) | LIVE |
| 쿼리 DX | `scripts/db_agent.py`, `scripts/meta_display_db_export.py` | 파일 존재; status doc에 2026-09-28 검증 행 | LIVE |
| (진행 중) query-agent-auto | journal `query-agent-auto-0928-c82fb26f` | in_progress — 완료 주장 아님 | LIVE(in-progress) |
| spend | `demo1-agent-api-spend-guard` + `docs/AGENT_API_SPEND_GUARD.md` | 5min cap 정합은 status doc 2026-09-28 verified | LIVE |
| Display/Nova | `demo1-meta-display-simple-caption`, `demo1-nova-focus` | skill dirs 존재 | LIVE |
| Git soft-auto | `demo1-vibe-git-auto-continue`, `scripts/conditional_local_git.py` | 존재; 조건부 로컬 Git 범위(`DEMO1-GIT-LOCAL-FIRST`) 내부에서만 | LIVE |

## 2. UAW 테마 → live (brief §1 하단)

| UAW 테마 | live 확인 | verdict |
| --- | --- | --- |
| LmsApplication canonical vs AgentApplication 레거시 | `main/java/com/example/lms/LmsApplication.java` + `main/java/com/abandonware/ai/agent/AgentApplication.java` 둘 다 존재 — canonical/legacy 구분은 사실 | LIVE |
| AutoConfiguration.imports / Nova* autoconfig | `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 존재; `ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java` live | LIVE |
| planDsl.status=not_used 계약 | `service/rag/plan/PlanDslLoader.java` + `nova/PlanDslLoader.java` + `UnifiedRagOrchestrator` 참조 존재 — 계약 존중, 임의 활성화 금지 | LIVE |
| `tools/probe_*.py` before/after 패턴 | 루트 `tools/`에 probe_*.py 없음. 유사 패턴은 `.agents/skills/mainfw-safe-repair/scripts/probe_mainfw.py`, `agent-prompts/*/probe_*.py`에 산재 — 위치가 다르므로 주장 경로는 불일치 | STALE(경로) / 패턴은 존재 |
| `uaw.autolearn.*` 제품 기능 | `main/java/com/example/lms/uaw/autolearn/` 패키지(UawAutolearnService·Orchestrator·Properties·Scheduler 등 ~20 클래스) + `application.yml` 키 존재 — 이미 제품 기능. 메타 스킬이 새로 켜지 않음 | LIVE (활성화 금지 유지) |

## 3. 라우팅 관찰 (이번 세션)

- `devin_task_orchestrate.py plan --brief-file <PASTE>` 가 본 skill-authoring brief를
  `nova-focus` playbook으로 매칭. playbook 키워드가 스킬 작성 의도와 무관하게
  "Nova" 단어에 반응한 misroute — phase를 그대로 따르지 않고 brief 본문의
  산출물(스킬+index+HARMONY_MAP)을 기준으로 작업했다. 개선 후보: playbook 매칭에
  "skill-authoring" 전용 playbook 또는 negative keyword.
- router 신규 intent `uaw-vibe-longrun`: `resolve "UAW 첨부 읽고 장기 오토 바이브…"` 와
  `resolve "longrun autonomous vibe multi-hour"` → primary `demo1-uaw-vibe-longrun`.
  비대응 ask("렌즈 힌트 버그 디버그해줘")는 기존 `debug-symptom` 유지(탈취 없음).

## 4. 델타 — uaw-harmony-foundation (2026-09-28, devin-uaw-harmony)

- 신규 진입 스킬 `demo1-uaw-harmony-foundation` + intent `uaw-harmony-foundation`
  (optional `demo1-ablation-harmony-tracker`). 밑밥 산출물:
  `docs/diagnostics/uaw-harmony-foundation-0928/` + `scripts/uaw_spine_probe.py` (S1~S10).
- **drift (사실, Abandon_X 대비)**:
  - P0-A RuleBreak "미등록" → 해소됨. `WebMvcConfig.addInterceptors`가 canonical
    `guard.rulebreak.RuleBreakInterceptor`(@Component)를 조건부 `/**` 등록.
    evaluator는 admin-token `MessageDigest.isEqual` 게이트 — 토큰 미설정 시 전 요청 inactive.
  - `retrieval.order.mode` 미설정(기본 `fixed`) → `decideOrder` 조기 DEFAULT.
    RuleBreak/Guard/FailurePattern/CFVM 분기는 `adjustFromCfvm` mode 플립 후에만 도달.
  - 설정 이중선언 지속: `ocr.enabled`, `ocr.min-confidence`, `local-llm.base-url`,
    `retrieval.vector.enabled` properties↔yml 양쪽 (probe S8).
  - plans/ 비-v1 별칭 지속: `brave.yaml`, `safe_autorun.yaml`, `zero_break.yaml` (probe S7).
- 밑밥 신설 없이 재사용 확인된 축은 §1 표 그대로 (라우터/lease/ledger/spend/DB DX 전부 LIVE).
