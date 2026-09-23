# demo-1 MacSrc 결함 접수 프롬프트

먼저 `$demo1-macsrc-defect-intake`를 사용한다. 현재 MacSrc 증거에서 구체적인
결함 하나만 선택하고, 활성 sourceSet 또는 호출 경계와 최소 RED/GREEN 명령을
확인한 뒤 비변이 `PatchIntent`를 생성한다.

- 정적 냄새나 오래된 보고만 있으면 `HOLD`하고 가장 작은 RED 탐침 하나를 남긴다.
- 대상, watch root, boundary evidence는 모두 현재 root 기준 상대 경로로 기록한다.
- 원문 쿼리·프롬프트·컨텍스트·키·헤더 대신 reason code, count, SHA-256만 남긴다.
- intent 생성은 소스 수정 권한이 아니다.
- RED가 정확히 재현된 새 증거가 생긴 경우에만
  `$demo1-macsrc-guarded-patch-session`으로 넘긴다.
