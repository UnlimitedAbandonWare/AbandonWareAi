# 02 · C2 — DORMANT 패키지 인덱스

Evidence: `scan-report.json` → `c2_packages[]` (52 groups, `main/java` 2,162 files).
판정 기준: `scanned` = `LmsApplication.scanBasePackages` 내부 / `live` = 활성 코드 importer 수 / `test` = 테스트 핀 수.
`path`는 그룹의 대표 루트(`main/java/<pkg-path>`).

## 핵심 교정 (시드 vs 실측)

- 시드: "`abandonware*` 수백 파일 = dormant" → **부분 오류**. `com.abandonware.ai`(364)는 활성 코드가 40개 파일에서 import하고 `AgentToolOpsConfig`가 ~30개 빈을 배선. `ai.abandonware.nova`(146)는 5개 autoconfig 등록 + 53개 라이브 importer.
- 시드: "루트 패키지(config/web/…) = dormant" → **혼재**. `guard`(5)는 `AnswerSanitizer`가 import. `service`(39)·`strategy`(12) 등은 TEST-PINNED 후보.
- 활성 경계 밖 파일 총합 ≈ 726이지만 그 중 **와이어드/테스트 핀이 643**, 순수 후보는 **~78 파일** 뿐이다.

## A. SCANNED(LIVE) — 건드리지 않음

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| D-L1 | `main/java/com/example/lms` (1,412) | KEEP | — | `scanBasePackages` 내부, test=542 | — | active |
| D-L2 | `main/java/com/nova/protocol` (24) | KEEP | `com/nova` 루트를 통째로 dormant로 오인 | `scanBasePackages` 내부, live=3 test=7 | — | active |

## B. WIRED-NOT-SCANNED — "스캔 밖"이라고 삭제 금지

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| D-W1 | `main/java/com/abandonware/ai` (364) | KEEP(혼재) | "abandonware" 이름 → 죽은 코드로 일괄 오인 | live importers=40 (`AgentToolOpsConfig`, `ChatWorkflow` 등), test=55 | INDEX_ONLY — 서브패키지별 3증명 분리는 P2 | active+unowned |
| D-W2 | `main/java/ai/abandonware/nova` (146) | KEEP | `main/java/ai` 루트라 스캔 밖으로 보임 | `AutoConfiguration.imports` 5개 등록, live=53, test=94 | — | active |
| D-W3 | `main/java/com/acme/aicore` (31) | KEEP(부분) | "acme=샘플/장난 코드"로 오인 | live=5, test=7 | INDEX_ONLY | unowned |
| D-W4 | `main/java/guard` (5) | KEEP | 루트 패키지라 orphan으로 오인 | `com.example.lms.guard.AnswerSanitizer`가 `guard.FinalQualityGate` import | — | active |
| D-W5 | `main/java/ai/abandonware/subagent` (2) | KEEP | D-W2와 동일 | `LmsApplication`이 import | — | active |

## C. TEST-PINNED-DORMANT? — 삭제 시 계약 테스트 파손

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| D-T1 | `main/java/service` (39) | KEEP | 루트 `service` = `lms.service`의 구버전 잔재로 오인 | test=6 (`service/rag` 라인 포함) | INDEX_ONLY | unowned |
| D-T2 | `main/java/com/abandonware/patch` (16) | KEEP | "patch" = 임시 패치 파일 묶음으로 오인 | test=2 — 핀 있음 | INDEX_ONLY | unowned |
| D-T3 | `main/java/strategy` (12) | KEEP | 루트 `strategy` = dormant로 오인 | test=1 | INDEX_ONLY | unowned |
| D-T4 | `main/java/com/example/risk` (6) | KEEP | `com.example` 접두라 활성으로 오인 | test=1, live=0, scanned=False | INDEX_ONLY | unowned |
| D-T5 | `main/java/com/example/moe` (5) | KEEP | 동일 | test=1 | INDEX_ONLY | unowned |
| D-T6 | `main/java/com/example/patch` (4) | KEEP | 동일 | test=4 | INDEX_ONLY | unowned |
| D-T7 | `main/java/com/abandonwareai/planner` (3) | KEEP | `abandonwareai` = 전체 dormant로 오인 | test=1 | INDEX_ONLY | unowned |
| D-T8 | `main/java/planner` + `main/java/tools` (3+3) | KEEP | 루트 util 패키지로 오인 | test=1 each | INDEX_ONLY | unowned |
| D-T9 | `main/java/config` + `main/java/scheduler` (2+2) | KEEP | `config` 루트 = 설정 디렉터리로 오인 | test=1 each | INDEX_ONLY | unowned |

## D. DORMANT_CANDIDATE — 3중 증명 완료 전 INDEX_ONLY

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| D-C1 | `(default package)` (5) | → C1 이관 | 선언 패키지 없는 파일들 | J01·J02·J03·J07·J12와 동일 | 01 문서 참조 | unowned |
| D-C2 | `main/java/com/abandonwareai/*` (planner 제외 19그룹, 52) | QUARANTINE_CANDIDATE | `abandonwareai` 전체를 "확실히 죽은 것"으로 성급 삭제 유혹 | live=0 test=0 전원 — zerobreak 8, fusion 7, context/guard/resilience 5 each 등 | 3증명(미호출·리소스 로딩 포함) 후 격리 제안 | unowned |
| D-C3 | `main/java/com/example/rag` (5) | QUARANTINE_CANDIDATE | `com.example` 접두로 활성처럼 보임 — 실제 활성 RAG는 `lms.service.rag` | scanned=False live=0 test=0 | 동일 | unowned |
| D-C4 | `main/java/{infra,integrations,router,trace,web}` (4+2+2+2+2) | QUARANTINE_CANDIDATE | 루트 패키지군 — `web`은 `RuleBreak*` 잔재 포함 | live=0 test=0 | 동일 | unowned |
| D-C5 | `main/java/{app,otel,telemetry}` (1+1+1) | QUARANTINE_CANDIDATE | 루트 잔재 | live=0 test=0 (`app.prompt.PromptManifestLoader` 등) | 동일 | unowned |
| D-C6 | `main/java/com/example/{guard,infra,rerank,retrieval,search}` (2+1+1+1+1) | QUARANTINE_CANDIDATE | `com.example` 접두 = 활성 오인 | live=0 test=0 | 동일 | unowned |

## RuleBreak 계보 (이름 함정 — 6+ 사본)

동명/유사 `RuleBreak*` 구현이 `lms.guard.rulebreak`(활성, 스캔됨) / `com.nova.protocol.*`(스캔됨) / `main/java/web`(D-C4) / `main/java/com/abandonwareai/zerobreak`(D-C2) / `main/java/com/abandonware/patch`(D-T2)에 공존. 패치할 때 **반드시 스캔 경계 먼저 확인** — `web/RuleBreakInterceptor`는 스캔 밖이다.

## 읽는 법

- `verdictHint`는 스캐너 힌트: `SCANNED(LIVE)` > `WIRED-NOT-SCANNED` > `TEST-PINNED-DORMANT?` > `DORMANT_CANDIDATE(3증명 필요)` 순으로 증거 강도가 낮다.
- D-C 전부: 스캔 밖 + 미import는 확인됨. **미호출/리소스 로딩**(reflection, `Class.forName`, YAML/JSON 경로 참조, SPI) 검증이 남아 있어 최종 삭제 판정 보류.
- 합계: LIVE 1,436 / WIRED 548 / TEST-PINNED 95 / 순수 후보 ~78 (총 2,162, 그룹핑 끝자리 ±5).
