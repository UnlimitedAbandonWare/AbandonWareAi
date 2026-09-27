# MIRROR MANIFEST — madasin Codex design recovery 2026-09-26

생성: 2026-09-27 (Asia/Seoul), Codex 세션(`madasin-design-recovery-64850b22`) 외부 어시스트.
이 폴더는 읽기 전용 참조본이며 **수정 대상이 아닙니다.** 코드 수정은 canonical root
`C:\AbandonWare\demo-1\demo-1\src` 의 활성 sourceSet에서만 한다.

## 1. 이 미러에 채워진 파일 (원본과 SHA-256 동일 확인)

원본: `C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\evidence\`
복사: `evidence/EVIDENCE.md`, `evidence/browser_probe_results.json`, `evidence/source_inventory.json`

복사 시점에 미러에는 `Abandon.txt`, `ANALYSIS_REPORT.md`, `CODEX_KICKOFF.md`만 있었고
`evidence/` 폴더 자체가 없었다. 킥오프 지시문이 요구하는 `evidence/EVIDENCE.md` 경로가
미러에서 해소되지 않아 이곳에 추가했다. 원본은 그대로 두었다(이동 아님).

## 2. 어느 곳에도 존재하지 않는 산출물 (수색 완료, 찾지 말 것)

`Abandon.txt`의 [함께 읽을 파일]과 `EVIDENCE.md` 본문이 참조하지만,
`Downloads` 패키지와 canonical root 전체를 재귀 수색해도 없는 항목:

| 참조된 이름 | 참조 위치 | 상태 |
|---|---|---|
| `plan_contract_probe.txt` | Abandon.txt 함께 읽을 파일 5번, EVIDENCE.md E16 언급 | 없음 |
| `SOURCES.md` (W01–W06) | Abandon.txt 함께 읽을 파일 6번, ANALYSIS_REPORT 참고자료 | 없음 |
| `js_syntax_results.json` | EVIDENCE.md 머리말 (node --check 31/31 근거) | 없음 |

판단: 이 3종은 **evidence_needed**로 남긴다. 없음을 근거로 다른 파일을 대체 재료가처럼
사용하거나, 값 만들어 넣지 말 것. 순수 Java 계약 9개 assertion과 `node --check` 31/31 주장의
원본 증빙은 이 패키지에 없으므로, 필요하면 canonical root에서 새로 실행해 다시 만들어야 한다.
`PlanExecutionSpec`의 등가 검증은 기존 `src/test/java/com/example/lms/plan/PlanExecutionSpecTest.java`
와 `PlanStageProjectionBoundaryTest.java`가 이미 담당한다.

## 3. 지시서 경로 기호 대응 (do00 SourceMap)

| 기호 | 실제 값 (확인됨) |
|---|---|
| `${SRC}` | `C:\AbandonWare\demo-1\demo-1\src` (즉 ZIP의 `main/`이 그대로 루트에 매핑) |
| `${BUILD}` | 동일 경로 — `gradlew.bat` + `settings.gradle`(rootProject `src111_merge15`) + `build.gradle.kts`, Gradle 8.7, Java 17.0.13 |
| `${TEST_JAVA}` | `src/test/java` (1,431개), 추가 전용 sourceSet `src/chatUiTest/java` |
| JS 회귀 | `src/test/js/*.cjs` (29개) — Gradle sourceSet 아님, `node --test <파일...>` 로 실행 |

`C:\AbandonWare\demo-1\demo-1` 에도 **별도의** Gradle 프로젝트(rootProject `demo-1`)가 있다.
madasin 작업의 build root가 아니므로 거기서 `gradlew`를 실행하지 말 것.
