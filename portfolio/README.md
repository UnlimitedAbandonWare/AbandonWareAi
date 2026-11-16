# AbandonWareAI 엔지니어링 포트폴리오

## 1. 채팅 전송 계층에서 취소와 타임아웃 다루기

브라우저의 요청을 Next.js 서버가 백엔드에 전달합니다. 서버는 브라우저의 취소와 업스트림 응답 헤더 대기 기한을 별도 원인으로 처리합니다. 응답 헤더를 받은 뒤에는 연결 타이머를 해제해 SSE 본문이 계속 전달될 수 있게 합니다.

| 확인할 동작 | 코드와 테스트 |
| --- | --- |
| 취소가 이미 요청된 경우 | 업스트림 호출 0회와 `499 user_abort`를 확인합니다. |
| 헤더를 기다리는 동안 발생한 문제 | 브라우저 취소는 `499 user_abort`, 전송 타임아웃은 `504 transport_timeout`으로 구분합니다. |
| SSE 응답이 이미 시작된 경우 | 헤더 대기 한도보다 늦게 도착한 마지막 이벤트를 계속 읽을 수 있는지 확인합니다. |
| 요청 전달의 공개 범위 | 상관관계 헤더를 선택적으로 전달하고 브라우저의 Authorization 헤더를 제외하는지 확인합니다. |

[스트리밍 라우트](../frontend/src/app/api/chat/stream/route.js) → [BFF 구현](../frontend/src/lib/bff.js) → [회귀 테스트](../frontend/test/bff.test.mjs)

저장소 루트에서 Node.js 24로 실행합니다.

```sh
node --test frontend/test/bff.test.mjs
```

이 테스트는 합성 `fetch`와 스트림을 사용합니다. 헤더, 오류 분류, 취소 시점, 스트림 본문 수명을 재현합니다.

## 2. RAG 검색과 프롬프트 구성 읽기

Spring 백엔드에서 [채팅 워크플로](../main/java/com/example/lms/service/ChatWorkflow.java)의 검색 호출과 프롬프트 호출을 따라갑니다. [하이브리드 검색기](../main/java/com/example/lms/service/rag/HybridRetriever.java)와 [검색 보조 로직 테스트](../src/test/java/com/example/lms/service/rag/HybridRetrieverSupportTest.java)를 함께 읽으면 검색 결과를 처리하는 경계를 볼 수 있습니다. [프롬프트 인터페이스](../main/java/com/example/lms/prompt/PromptBuilder.java)에서 컨텍스트가 모델 입력으로 넘어가는 지점을 확인할 수 있습니다.

## 변경 이력과 복구

[공개 파일 및 복구 기준](RECOVERY.md) · [에이전트 검수와 커밋 운영서](https://github.com/UnlimitedAbandonWare/AbandonWareAi/blob/0dd0b184dc8f277db984978fbf32cb9a19369ac1/docs/kb/2026-11-16-agent-git-checkpoint-roles.md)
