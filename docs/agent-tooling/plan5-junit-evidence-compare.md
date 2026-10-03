# plan5-junit-evidence-compare — JUnit 증거 도구 3종 비교 (2026-10-02)

목적: PLAN5 P1 판정에 쓸 JUnit 증거 도구의 중복을 정리한다. 어느 것도 Gradle을
실행하지 않는다(전부 기존 XML/로그만 읽음). 작성자: devin,
task `devin-plan5-assist-685f4d65`.

| 축 | `scripts/junit_owned_summary.py` | `scripts/test_xml_evidence.py` | `agent-prompts/.../ref/verify_test_evidence.py` |
|---|---|---|---|
| 입력 | `--xml-dir` + `--class`(반복) | `--log`(Gradle 로그) + `--xml-dir`(반복) + `--gradle-exit` + `--since` | `--xml`(파일 단위 반복) + `--started-ns/--finished-ns` + `--command-exit` + `--require-case`(필수) |
| 정확한 케이스 요구 (`classname#name`) | **가능(additive `--require-case`, 이번 확장)** — 없으면 클래스 수준 집계 | 없음(클래스 무관 전체 집계) | **필수** — 미지정 시 INPUT_ERROR |
| 신선도(실행 시간 창) | 없음 | `--since` ISO ts → mtime 미달 파일 stale 제외(`staleXmlCount`) | **강함**: `--started-ns ≤ mtime ≤ --finished-ns`, `REPORT_OUTSIDE_RUN` |
| skip/failure 처리 | class별 `skipped`·`failedMethods` 집계, FAIL/MISSING 분리 | executed>0 + failures/errors 0 판정 | 케이스별 failure/error/skipped 구분, `SKIPPED_TESTS`/`REPORTED_FAILURE` reason |
| 명령 exit 결합 | 없음(코드 0/3/4 = 결과 상태) | `--gradle-exit` + 로그 마커 → `WRAPPER_EXIT_LIE`/`BASELINE_BLOCKED`/`RED`/`GREEN` | `--command-exit != 0` → `COMMAND_NONZERO` reason |
| 방어적 파싱 | XML ParseError skip | ANSI strip + 로그 패턴 | DTD/ENTITY 거절, 링크 거절, 읽는 중 변경 탐지, 선언 카운터 교차검증, 중복 케이스 탐지, 배타적 `--out` |
| 판정 | named[] PASS/FAIL/MISSING + exit 0/3/4 | verdict 필드(RED/GREEN/…) | `EVIDENCE_ACCEPTED/REJECTED` + reasons[] |
| 역할 | 클래스 단위 소유 집계 | Gradle 실행 결과 판정 엔진 | 엄격한 실행 창·케이스 증거 심사 |

## 결정 (AUTO_DECISION)

- 채택: **(a)** `junit_owned_summary.py`에 `--require-case FQCN#METHOD`(반복,
  기본 off) 추가. 근거: Codex 브리프가 클래스명만 고정하고 케이스명은 자유라
  클래스 단위 집계가 기본이지만, 필요 시 정확 케이스 단언이 additive로 가능.
  `verify_test_evidence.py`를 `scripts/`에 복제해 평행 스택을 만들지 않음
  (지시서 금지 준수) — 엄격 심사가 필요하면 원본 경로를 그대로 호출.
- exit 규칙(기존과 동일 축): 필수 케이스 `FAILED` → 4, `MISSING|SKIPPED` → 3.
  클래스 FAIL이 있으면 4가 우선(기존 규칙 유지).
- `payload["requiredCases"]`는 플래그 사용 시에만 추가 — 기존 소비자 무영향.
- 검증: `python -B -m unittest scripts.test_junit_owned_summary` → 10 tests exit 0
  (기존 2 + 신규 8). 기본 동작 불변 확인: 플래그 없으면 `requiredCases` 키 없음.
