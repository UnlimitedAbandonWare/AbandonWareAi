# docs/build_error_memory__neutralized_block_closer.md

분류: **NeutralizedBlockCloser → UnclosedComment**

패턴
- 빌드 전 파이프라인에서 안전 목적으로 `*/`를 `* /`로 중화한 흔적이 남아, 실제 Java 파서 입장에선 **블록 주석이 닫히지 않음**.
- 전형적 로그:
  - `error: unclosed comment` (여러 파일)
  - 직후 `reached end of file while parsing`, `illegal start of expression`, `'var' is not allowed here` 등 **연쇄 오류**.

시그니처
- 소스 라인 예: `/* ... * /` , `/* ───── * /` , `/* no-op * /`

해결 규칙
1) **상태 머신**으로 라인/문자열/문자 리터럴을 배제하고, **블록 주석 내부에서만** `* /` → `*/`로 정상화.
2) 파일 EOF까지 블록 주석이 열려 있으면 `*/`를 자동 보강.
3) 코드/문자열 컨텍스트의 `* /`는 그대로 둠(기능적 의미 없음, 단 토큰 영향은 극히 제한적).

연관 카테고리
- DocBlockCorruption (주석 누출) 과 상호 보완.
- 매핑: `UnclosedComment`, `ReachedEOF`, `IllegalStartOfExpression`, `VarNotAllowedHere`.

테스트
- 스모크: `./gradlew :compileJava -x test`
- 리그레션: 랜덤 50파일 샘플 파싱(커스텀 AST 체커) → 잔여 `* /` 미검출 확인.