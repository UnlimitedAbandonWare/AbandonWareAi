# 05 · ACTION QUEUE — P0/P1/P2

원칙: 지도는 인덱스다. 삭제·이동·리팩터는 승인 항목에서만. Codex MAX-PUSH/Clean Kit 레인 침범 금지, `add -A`·push·대량 삭제 금지.

## C5 — GHOST TOOLING 요약 (큐의 근거)

Evidence: `scan-report.json` → `c5_ghosts` — 참조 402건 중 **79건 unresolved**.

| id | path / ref | verdict | why_confuses_agents | proof | action_now | owner |
|----|-----------|---------|---------------------|-------|------------|-------|
| G-L1 | 활성 `SKILL.md`에서 참조하는 없는 스크립트 ~17건 (예: `scripts/advance_autograder_probe.ps1` ← demo1-macsrc-defect-intake, `scripts/catalog.py` ← adaptive-rule-lab, `scripts/startup_doctor.py` ← meta-display-verification, `scripts/probe_mainfw.py` ← mainfw-safe-repair, `scripts/zombie_candidate_audit.py` ← zombie-purge, `scripts/changed.py`·`cleanup_completed_directives.ps1`·`test_cleanup_completed_*.ps1` ← completed-directive-cleanup, `scripts/new_docker_autograder_job.ps1` ← docker-autograder, `scripts/macsrc_smb_patch_guard.ps1` ← macsrc-smb, `scripts/prepare_patch_tri_query.ps1` ← patch-postprocessor, `scripts/tri_query_directive_postprocess.ps1` ← memory-integrity-autopatch, `scripts/validate_counter_evidence_skill_family.py` ← triangulating-counter-evidence, `scripts/evaluate_failure_routes.py` ← verifier-escalation) | INDEX_ONLY | SKILL.md는 에이전트가 실행 명령으로 신뢰 — 없는 파일을 실행하려 시도 | `missing[]` docs 필드가 `.agents/skills/*/SKILL.md`를 가리킴 | 스킬별로 "stale-script" 태그 또는 경로 수정 — P1 | per-skill owner |
| G-L2 | `docs/PROJECT_STATUS.md` → `scripts/mgain_trace_verify_matrix.py` | INDEX_ONLY | **상태 SSOT**가 없는 검증 스크립트를 가리킴 — 신뢰 문서의 참조 무결성 문제 | missing 목록 1건 | 상태 문서 행 수정 — P1 (status_doc 절차) | unowned |
| G-L3 | `agent-prompts/*`·`docs/superpowers/plans|specs`의 유령 ~60건 | INDEX_ONLY | 과거 계획/설계 문서 — 기록용이라 참조 유효해도 당시 트리 기준 | missing 목록 다수 | 재작성 금지(역사 문서), 표지 인덱스만 | unowned |
| G-L4 | stem 쌍 9종 (`agent_*_context/.ps1+.py`, `fix_build.ps1+.sh`, `start-https-tunnel.bat+.ps1` 등) | KEEP | ps1/py 쌍을 중복으로 오인 | 양쪽 파일 모두 존재 — 의도된 래퍼 패턴 | — | active |
| G-L5 | `scripts/DesktopMariaDbMetadataSnapshot.java` (49KB) | INDEX_ONLY | scripts/ 안의 유일한 .java — 빌드 소스셋 밖 고아 | 확장자 분포 실측 | 스크립트 분류 표기 | unowned |

## P0 — 오늘 OK (인덱스/배너/포인터만)

| # | action | 대상 | 상태 |
|---|--------|------|------|
| P0-1 | 카테고리 지도 00~05 + scan-report.json 작성 | `docs/diagnostics/category-cleanup-0928/` | ✅ done (cycle-06) |
| P0-2 | 읽기 전용 스캐너 `scripts/category_cleanup_scan.py` | 재생성 명령 보존 | ✅ done (cycle-02~04, exit 0) |
| P0-3 | AGENTS.md에 지도 포인터 1줄 추가 | `AGENTS.md` | ready — 별도 리스 필요 |
| P0-4 | `agent-prompts/README_ACTIVE.md` (활성 3~5개 + dated=historical 규칙) | `agent-prompts/` | ready — 활성 목록 판단 필요 |
| P0-5 | skills-intent-index에 `archive`/`negative` 키워드 태그(유령 참조 스킬 등) | `.agents/skills-intent-index.yaml` | ready — 태그 목록은 G-L1 참조 |

## P1 — 승인 필요 (격리/삭제/정정)

| # | action | 대상 | gate |
|---|--------|------|------|
| P1-1 | DELETE_CANDIDATE 삭제: `test_mod.java`, `MatrixConfig.java`(0바이트), `service/rag/rerank/DppDiversityReranker.java`(자기 서술 있음) | C1 J03/J07/J12 | 3증명 충족 + 리스 + diff ≤3 |
| P1-2 | `ChatService_old.java`/`ChatService_copy.java` 삭제 또는 shim 유지 결정 | J01/J02 | 현재 shim 자체가 배너 역할 — 삭제 시 함정도 제거됨. 승인 후 진행 |
| P1-3 | QUARANTINE_CANDIDATE 패키지 이동/빌드 exclude: `com.abandonwareai.*`(52), `com.example.rag` 등 루트 pkg 잔재 (~78파일 총) | 02 문서 D-C 전원 | 소스셋 exclude 또는 `quarantine/` 이동 — 컴파일 영향 검증 필수 |
| P1-4 | `plans/` 비-v1 별칭 3개(`brave.yaml`,`safe_autorun.yaml`,`zero_break.yaml`) 제거 또는 `_archive` | R-P2 | PlanHintApplier v1 정규화 확인됨 — dormant 로더 활성화 계획 유무 확인 |
| P1-5 | `AgentApplication.java` 격리/삭제 | R-L2 | 비활성 런처 — 실행 진입점 정리 승인 |
| P1-6 | SKILL.md 유령 스크립트 참조 정정 (~17건, G-L1) | 해당 스킬들 | 스킬별 소유 레인 확인 — reference 갱신 또는 스크립트 복원 판정 |
| P1-7 | `PROJECT_STATUS.md`의 `mgain_trace_verify_matrix.py` 참조 정정 | G-L2 | status_doc 절차로 행 수정 |

## P2 — 대형/후속 (별도 계획)

| # | action | 비고 |
|---|--------|------|
| P2-1 | `com.abandonware.ai`(364) 서브패키지별 와이어드/도먼트 분리 감사 | 부분 활성 — 패키지 단위 일괄 판정 불가 |
| P2-2 | 루트 패키지(`service`/`strategy`/`web`/`infra`/`router`/`trace`/`otel`/`app`/`config`/`planner`/`scheduler`/`tools`/`telemetry`/`integrations`) 통합 방안 | `com.example.lms` 흡수 vs quarantine 분리 |
| P2-3 | 규칙 5계층 통합 제안 (.clinerules/.grok/.codex/.devin/.windsurf) | 에이전트별 SSOT 분열 해소는 정책 결정 |
| P2-4 | 스킬 인텐트 인덱스 백필 (미등록 82개) | 라우터 커버리지 |
| P2-5 | `scripts/` 417파일 인벤토리 SSOT (정본/래퍼/아카이브 표기) | C5 재발 방지 |

## 금지 사항 재확인

- 대량 삭제 / 제품 리팩터 / Codex MAX-PUSH 핫패스 동시 편집 / Clean Kit 레인 침범 / foreign 리스 강제 해제 — **전부 금지**.
- 깨우기(wake)·스캔 범위 확장 금지 — dormant 후보는 "exclude/아카이브/표시"까지만.
- 이 큐의 P1 전부는 승인 키워드 + 대상 리스 확보 후에만 착수한다.
