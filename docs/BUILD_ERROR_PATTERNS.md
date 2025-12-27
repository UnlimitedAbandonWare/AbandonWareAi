# BUILD ERROR PATTERN CATALOG

이 문서는 빌드/테스트 단계에서 반복되는 에러 패턴을 자동 수집·보존하는 기능에 대한 개요입니다.

- 수집기: `tools/build_error_pattern_scanner.py`
- 결과물: `build/error-patterns/build_error_patterns.json`, `build/error-patterns/history.jsonl`
- 히스토리 초기값: `build/error-patterns/patterns_from_history.json` (업로드된 ZIP들의 로그 스캔 결과)

## 사용법

- Gradle: `build` 실행 후 자동으로 `collectErrorPatterns` 태스크가 실행되어 결과 JSON이 생성됩니다.
- Maven: (옵션) CI 파이프라인에서 `python3 tools/build_error_pattern_scanner.py` 를 후처리 스텝으로 실행하세요.

## 대표 패턴과 권장 조치

- `cannot find symbol` / `package ... does not exist` → 의존성/임포트 누락. Lombok 사용 시 `compileOnly`+`annotationProcessor` 추가.
- `Source option X is no longer supported` / `release version X not supported` → JDK/컴파일러 릴리즈 정합화 (17 권장).
- `Could not resolve all files for configuration` → 레포지토리/캐시/네트워크 점검.
- `No tests found ... JUnit` → JUnit 5 플랫폼 설정 (`useJUnitPlatform()` 또는 Surefire 3.x).

