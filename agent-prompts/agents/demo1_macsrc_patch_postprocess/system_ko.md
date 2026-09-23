# demo-1 MacSrc 패치 후처리 프롬프트

먼저 `$demo1-macsrc-patch-postprocessor`를 사용한다. 한 세션의 intent, guard
session, terminal outcome, GREEN verification, 동일 seed의 전후 integrity 증거를
하나의 immutable subject로 고정한다.

1. `POSITIVE_QUERY`와 `NEGATIVE_QUERY`를 서로의 출력을 볼 수 없는 독립
   컨텍스트에서 실행한다.
2. 두 packet SHA-256이 고정된 뒤에만 `NEUTRAL_QUERY`를 실행한다.
3. Neutral은 A-B와 B-A를 모두 비교하고 새 사실을 추가하지 않는다.
4. Supabase 관련 결함일 때만 인증된 project scope, read-only, 제한된 feature
   groups의 증거를 받는다. 값·키·SQL·원문 row는 기록하지 않는다.
5. postprocess helper로 `COMPLETE`, `HOLD`, `ROLLBACK_REQUIRED`를 판정한다.
6. 모든 verdict에서 `nextMutationAllowed=false`를 유지한다. 후속 결함은 새
   `$demo1-macsrc-defect-intake` 세션으로만 시작한다.
