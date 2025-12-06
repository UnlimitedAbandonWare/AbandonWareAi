# Build Error Pattern Memory — src111_merge15

패치 목적: Javadoc/블록 주석 내부에 예시 표기용 `'/* ... */'`가 포함되어 조기 `*/`이 발생, 주석이 비정상 종료되며 파서가 붕괴되는 문제 해결.

해결 방법: 문제 시퀀스의 말미 `*/`를 `*&#47;`(슬래시 HTML 엔티티)로 치환하여, 주석은 안전하게 유지되고 문서 렌더링은 동일하게 보이도록 처리.

대상 패턴:

- `/* ... */` (공백 유무 모두)
- `/ * ... */` (간헐적 공백 포함)
- `/* … */` (유니코드 줄임표)

요약:

- 스캔 파일 수: 2452

- Java 파일 수: 1989

- 치환된 플레이스홀더 총 수: 197

- 수정된 파일 수: 66


상위 일부 수정 파일:

  - app/src/main/java/com/abandonware/ai/addons/synthesis/MatrixTransformer.java  (치환 2건)

  - app/src/main/java/com/abandonware/ai/agent/consent/BasicConsentService.java  (치환 2건)

  - app/src/main/java/com/abandonware/ai/agent/consent/ConsentService.java  (치환 2건)

  - app/src/main/java/com/abandonware/ai/agent/consent/RedisConsentService.java  (치환 2건)

  - app/src/main/java/com/abandonware/ai/agent/web/IdentityController.java  (치환 1건)

  - app/src/main/java/com/abandonware/ai/agent/web/RoomController.java  (치환 1건)

  - src/main/java/com/abandonware/ai/addons/synthesis/MatrixTransformer.java  (치환 2건)

  - src/main/java/com/abandonware/ai/agent/consent/BasicConsentService.java  (치환 2건)

  - src/main/java/com/abandonware/ai/agent/consent/ConsentService.java  (치환 2건)

  - src/main/java/com/abandonware/ai/agent/integrations/RrfWeightTuner.java  (치환 7건)

  - src/main/java/com/abandonware/ai/agent/integrations/TextUtils.java  (치환 4건)

  - src/main/java/com/abandonware/ai/agent/integrations/service/rag/retriever/Bm25LocalRetriever.java  (치환 2건)

  - src/main/java/com/abandonware/ai/agent/web/IdentityController.java  (치환 1건)

  - src/main/java/com/abandonware/ai/agent/web/RoomController.java  (치환 1건)

  - src/main/java/com/abandonware/ai/service/rag/fusion/WeightedRRF.java  (치환 1건)

  - src/main/java/com/acme/aicore/app/ConversationService.java  (치환 2건)

  - src/main/java/com/example/lms/agent/CuriosityTriggerService.java  (치환 8건)

  - src/main/java/com/example/lms/agent/SynthesisService.java  (치환 4건)

  - src/main/java/com/example/lms/api/ChatApiController.java  (치환 35건)

  - src/main/java/com/example/lms/config/AppSecurityConfig.java  (치환 1건)
