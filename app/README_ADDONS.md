# Addons Pack (Complexity Gate · Z-System · FlowJoiner · MatrixTransformer · OCR-RAG)

이 폴더는 다음 기능을 **침투 0줄** 또는 **선택 패치 1~2줄**로 통합합니다.

- Query Complexity Classifier + 글로벌 게이팅
- Z‑System: Time‑Budget·Cancellation·2‑Pass CE Gate·Single‑Flight
- FlowJoiner(P,R,C,Y,K) 헬스 스코어 + 폴백
- MatrixTransformer: Authority/Novelty/Augmentation 동적 합성 + MoE Hybrid Gate
- DBVM‑X‑RAG: OCR→인덱싱→리트리벌→융합

자동 구성: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 에
`com.abandonware.ai.addons.config.AddonsAutoConfiguration`가 선언되어 있어 Spring Boot 3에서 자동 로드됩니다.

AOP 연동: `com.abandonware.ai.addons.config.AddonsHookAspect`는 다음 지점에 비침투형 훅을 겁니다(대상 메서드가 없으면 no-op).
- `*..OnnxCrossEncoderReranker.rerank(..)` → OnnxSemaphoreGate 적용
- `*..AnalyzeWebSearchRetriever.fetch(..)` → SingleFlightRegistry 적용
- `*..RetrievalOrderService.decide*(..)` → Complexity Gate 힌트 반영(리플렉션)

환경설정 예시는 `app/src/main/resources/application-addons-example.yml` 참고.
