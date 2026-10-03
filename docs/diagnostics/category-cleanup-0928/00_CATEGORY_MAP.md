# 00 · Category Map — C1~C5 (와전 방지 카테고리 지도)

- Contract: `DEMO1-DEVIN-CATEGORY-CLEANUP-TARGETS-20260928`
- Journal: `category-cleanup-0928-4b879da3` (agent: devin)
- Generated: 2026-09-28 (실측 재검증 완료 — 시드 수치는 전부 라이브 트리와 대조됨)
- Evidence: `scan-report.json` (same dir) — regenerate read-only via
  `python -B scripts/category_cleanup_scan.py --root . --out docs/diagnostics/category-cleanup-0928/scan-report.json`

## Scope

이 지도는 **분류/인덱스 산출물**이다. 파일을 삭제·이동·리팩터하지 않는다.
Codex MAX-PUSH / Clean Kit / 외부 리스 침범 금지. "정리"는 아래 표의
`action_now` 열이 허용하는 범위(인덱싱·배너·README)에서만 수행한다.

## Categories

| id | category | 정의 | 에이전트 혼란 메커니즘 |
|----|----------|------|------------------------|
| C1 | JUNK / NAME-TRAP | 컴파일은 되지만 의미 없는 스텁·주석 전용 파일, 패키지≠디렉터리 불일치, `_old`/`_copy`/`test_mod` 스타일 이름 | "서비스 파일"로 보여 패치하거나, "패치 파일"로 보여 무시하거나, 빈 파일을 "구현 대상"으로 오인 |
| C2 | DORMANT | 스캔 범위 밖·미import·미호출 후보 (3중 증명 필요) | `abandonware*`/`acme*`/루트 패키지를 "죽은 코드"로 일괄 판정 → 실제로는 autoconfig·import·test-pin으로 연결된 것이 다수 |
| C3 | RUNTIME CONTRACT | 엔트리포인트·설정·plan 리소스의 이중 표면 | `LmsApplication` vs `AgentApplication`, `application.yml` vs `.properties`, `plans/*` v1/비-v1 별칭 — 어느 것이 "진짜"인지 에이전트가 잘못 고름 |
| C4 | AGENT SURFACE | 스킬·프롬프트·규칙·진단 문서 스프롤 | 130개 스킬 중 82개가 라우터 인덱스 밖, 64개 프롬프트 디렉터리, 5개 규칙 계층 — 에이전트가 자기 SSOT를 못 찾거나 낡은 지시를 재사용 |
| C5 | GHOST TOOLING | 문서/스킬이 참조하지만 존재하지 않는 스크립트, 스템 쌍 중복 | SKILL.md가 존재하지 않는 `scripts/foo.ps1`을 가리킴 → 에이전트가 "도구 고장"으로 오진하거나 대체 구현을 새로 만듦 |

## Verdict legend

| verdict | 의미 |
|---------|------|
| KEEP | 활성 코드/문서. 건드리지 않는다. |
| INDEX_ONLY | 유지하되 지도/배너/인덱스로만 표시. 삭제·이동 없음. |
| QUARANTINE_CANDIDATE | 격리 후보. `quarantine/` 이동은 **승인 필요** + 리스 확인. |
| DELETE_CANDIDATE | 삭제 후보. 3중 증명 + 승인 후에만 실행. 절대 즉시 삭제 금지. |
| OWNER_OTHER | 다른 세션/소유자의 작업 레인. 보고만 하고 손대지 않는다. |

## Agent reading rules (에이전트 읽기 규칙)

1. **패키지≠활성**. `main/java` 아래 있다고 라이브가 아니다. `LmsApplication`의 `scanBasePackages`(`com.example.lms`, `com.nova.protocol`)와 `META-INF/spring/*AutoConfiguration.imports`, `spring.factories`가 활성 경계다.
2. **3중 증명 전 삭제 금지**: ① 스캔 밖 ② 미import ③ 미호출/미참조 — 셋 다 확인돼야 DELETE_CANDIDATE. 하나라도 걸리면 INDEX_ONLY.
3. **테스트 핀 = 잠금**. `src/test`·계약 테스트가 경로/내용을 기대하면 DELETE 불가 (`ZombiePurgeContractTest` 등).
4. **이름으로 판단 금지**. `_old`/`copy`/`patch` 이름과 실제 어노테이션/참조가 다를 수 있다 — 실측 우선.
5. **owner 확인**. 리스/저널이 잡힌 경로는 OWNER_OTHER로 표기하고 건드리지 않는다.
6. 스캐너의 `verdict_hint`는 힌트일 뿐이며 최종 판정은 본 지도의 `verdict` 열이다.

## Deliverable index

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| M0 | `docs/diagnostics/category-cleanup-0928/00_CATEGORY_MAP.md` | KEEP | — | 본 문서 | — | devin |
| M1 | `docs/diagnostics/category-cleanup-0928/01_JUNK_AND_NAME_TRAPS.md` | KEEP | C1 파일을 이름으로만 판정하면 오인 | 12행 재검증 테이블 | — | devin |
| M2 | `docs/diagnostics/category-cleanup-0928/02_DORMANT_INDEX.md` | KEEP | 패키지 경로만 보고 dormant 판정 | 52그룹 분류 + 와이어드 증거 | — | devin |
| M3 | `docs/diagnostics/category-cleanup-0928/03_RUNTIME_CONTRACT_CARD.md` | KEEP | 이중 엔트리·이중 설정·플랜 별칭 | launcher/config/plans 카드 | — | devin |
| M4 | `docs/diagnostics/category-cleanup-0928/04_AGENT_SURFACE_SPRAWL.md` | KEEP | 스킬/프롬프트/규칙 표면 과다 | 130/64/5계층 실측 | — | devin |
| M5 | `docs/diagnostics/category-cleanup-0928/05_ACTION_QUEUE.md` | KEEP | — | P0/P1/P2 게이트 큐 | — | devin |
| M6 | `docs/diagnostics/category-cleanup-0928/scan-report.json` | KEEP | — | `awx.category-cleanup-scan.v1`, readOnly | 스캐너로 재생성 | devin |

## Summary counts (2026-09-28 실측)

| 항목 | 실측 | 시드 추정 | 비고 |
|------|------|-----------|------|
| `main/java` Java 파일 | 2,162 | — | 스캐너 기준 |
| 패키지 그룹 | 52 | — | 3-seg 그룹핑 |
| C1 후보 | 12 | ~5 | 아래 01 문서 |
| 유령 스크립트 참조 | 79 / 402 | — | 05/C5 |
| `.agents/skills` 디렉터리 | 130 | ~128 | 전부 `SKILL.md` 보유 |
| 스킬 인텐트 인덱스 등록 | 48 | — | 82개 미등록 |
| `agent-prompts` 디렉터리 | 64 | ~64 | |
| `scripts/` 파일 | 417 | — | py 208 · ps1 160 · cjs 17 · js 16 · sh 8 … |

> 이후 문서: 01=C1 상세, 02=C2 패키지 인덱스, 03=C3 런타임 카드, 04=C4 표면, 05=액션 큐.
