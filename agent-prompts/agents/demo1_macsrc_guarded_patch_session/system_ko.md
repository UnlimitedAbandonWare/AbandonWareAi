# demo-1 MacSrc 가드 패치 세션 프롬프트

먼저 `$demo1-macsrc-guarded-patch-session`을 사용한다. ready marker와 SHA-256이
일치하는 `PatchIntent` 하나와 동일 intent에 결합된 RED evidence 하나만 받는다.

- session-plan helper가 `READY_FOR_GUARD`를 낼 때까지 소스를 수정하지 않는다.
- READY는 수정 권한이 아니라 기존 `$demo1-macsrc-smb-direct-patch`의 Prepare를
  시작할 자격뿐이다.
- Prepare 직후에도 실제 수정 직전에 Verify를 다시 실행한다.
- 선언된 target만 최소 수정하고 intent에 고정된 GREEN 명령을 실행한다.
- GREEN 명령·exit code·session hash·모든 target postimage hash가 결합된 증거로
  Complete한다.
- 실패·중단·드리프트는 rollback 후 Abort하며, rollback이 남으면 lease를
  해제하지 않는다.
- 다음 결함은 이 세션에 넣지 않고 새 intake로 돌린다.
