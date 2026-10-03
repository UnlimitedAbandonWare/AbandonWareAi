# B02 재현 카드 — 검색 실패와 빈 성공

픽: B02. 증상: 검색 장애인데 결과 0건과 `ok`만 보인다.
부모 Codex 저널 `autograde-b02-0928-74c07d0e`가 이미 이 타점을 골랐다. Grok은 그 파일을 고치지 않는다.

대상: P06 `main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java`
live SHA match `aea768e073a0b8fd2eec4ebcecdee8daced3872da090dc0200eb709cebcfbca2`, `execute` 44행.
이 클래스는 도구 gateway다. 일반 채팅 전체 검색 경로와 같지 않다.

## 세 갈래 (live `execute`, 패치 전 읽기)

| 입력 | trace `web.search.tool.status` | 응답 본문 | 분류 키 |
|---|---|---|---|
| query 없음/공백 | `SKIPPED`, `skipped.reason=EMPTY_QUERY` | `results=[]`, `skippedReason=EMPTY_QUERY` | `blank-query-skip` |
| gateway가 빈 목록 | `OK`, `zeroResults=true`, `returnedCount=0` | `results=[]` | `genuine-empty` |
| gateway `RuntimeException` | `FAIL_SOFT`, `skipped.reason=TIMEOUT_OR_EXCEPTION`, `failReason`=예외 클래스 이름, `failMsgHash`만 | `ToolResponse.ok` + `results=[]`. 본문에 `failReason`/`status` 없음 | `fail-soft-body-conflated` |

예외 메시지는 trace에 평문으로 남기지 않는다. 로그 분류도 hash와 reason code만 본다.

`AgentToolInvoker.webExecutionStatus`는 trace의 `FAIL_SOFT`/`SKIPPED`를 `executionStatus`로 올리고, 그 값을 다시 `web.search.tool.status`에 쓴다. `evidenceSink`는 `executionStatus`가 `OK`일 때만 돈다. 그래서 invoker 맵에는 상태가 있고, `results`만 읽는 소비자는 여전히 빈 성공으로 본다.

## 로그 키

- `web.search.tool.status` = `OK` | `SKIPPED` | `FAIL_SOFT`
- `web.search.tool.zeroResults`
- `web.search.tool.returnedCount`
- `web.search.tool.failReason`
- `web.search.tool.failMsgHash`
- `web.search.tool.skipped.reason`
- `web.search.tool.queryHash` (원문 쿼리 금지)
- `agent.acmeGateway.result.reason`
- invoker `executionStatus`

`FAIL_SOFT` + `zeroResults=true` + 응답 키가 `results`뿐이면 실패가 빈 성공과 합쳐진 것이다.
`OK` + `zeroResults=true` + `failReason` 없음은 자료 없음이다.
`SKIPPED` + `EMPTY_QUERY`는 검색 실패가 아니다.

## 이미 있는 테스트

`src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java`
메서드 `webSearchToolFailsSoftAndLeavesReasonOnException`.
지금 단언은 빈 `results`와 trace `FAIL_SOFT`다. 본문 구분 필드가 없어도 통과한다. 이 파일은 Codex 저널 범위 `src/test/java/com/abandonware/ai/agent/tool/impl` 밖이다. 고치기 전에 범위를 넓힌다.

Grok은 Gradle을 실행하지 않았다. Codex focused 후보:

```
.\gradlew.bat test --tests com.abandonware.ai.agent.tool.AgentWebSearchToolConditionalWiringTest
```

## RED 골격 (적용하지 않음)

Codex가 자기 테스트 트리에 추가한다. Grok은 Java를 만들지 않았다.

- gateway가 runtime 예외를 던진다.
- 호출은 예외를 다시 던지지 않는다. fail-soft 유지.
- 응답 본문이 실패와 진짜 빈 목록을 구분한다 (`failReason` 또는 `status`/`outcome`).
- 진짜 빈 목록은 `OK`와 `zeroResults`로 남는다.
- 빈 질의는 `SKIPPED` / `EMPTY_QUERY`로 남는다.
- 예외 메시지 원문은 응답과 trace에 없다.

합격은 장애와 자료 없음이 구분되고, 정상 축소 응답에 오류 문자열이 섞이지 않는 것이다. 새 검색 서비스, 유료 fan-out, 모든 예외의 재throw, 180/240/300초 대기는 넣지 않는다.
