# CODEX_HANDOFF — seam 1개

공유 파일의 writer는 Codex다. Devin 조수는 `ChatWorkflow`, `PromptBuilder`, `Attachment*`, `FileIngestion`, GraphRAG, Ensemble을 고치지 않는다.

## 지금 열 seam

ATT-1 내용 digest. `main/java/com/example/lms/service/AttachmentInspectionService.java` 160행.

```text
out.put("sha256", SafeRedactor.hash12(name + ":" + file.getSize() + ":" + contentType));
```

148행에서 이미 바이트를 읽는다. 그 바이트의 SHA-256이 아니다. 이름·크기·MIME이 같고 내용이 다른 파일이 같은 `sha256`을 가진다. T01이 먼저 실패해야 한다.

이 파일은 이번 preflight에서 live lease에 걸려 있지 않았다. `sourceLeaseActiveCount=0`, 체크포인트 `leaseOverlapWarnings=[]`. 만료된 lease 하나는 `clean-primitive-debug-ai-impl-0926`이며 대상은 `read_rag_debug_trail`이라 이 seam과 겹치지 않는다. 패치 직전에 lease를 다시 본다. live lease가 생기면 그 파일은 건너뛴다.

이전 조수 핸드오프의 `devin-timeout-5min-cap-0928`은 이번 preflight의 active lease 목록에 없었다. PROJECT_STATUS에 그 작업이 verified라고 적힌 것은 과거 보고다. NW를 GREEN으로 옮기지 않는다.

## 다음 focused 테스트

1. T01만. 같은 이름·크기·MIME, 다른 바이트, digest가 다름. 명령과 FQCN은 Codex가 비어 있는 A2 칸에 적는다.
2. T05, T08, T13, T17, T18은 그 다음 각각 한 패치다. 여섯 개를 한 diff에 넣지 않는다.
3. CTX 표의 다섯 카운트는 ATT-6 다음이다. `EnsembleFinalAnswerService` 324행과 343행의 2개/3개 검사를 느슨하게 만들지 않는다.

## evidence_needed

- T17 복원기가 `ChatWorkflow` 밖 다른 클래스에 이미 있는지. 이 세션은 첨부 주입 구간만 읽었다.
- `/chat`의 SCOPED_RAG·RECENT_ONLY가 focus 경로 밖에 있는지. focus의 `web=false`는 `/chat` 증거가 아니다.
- `attachments.archive.maxEntries`의 현재 설정값. 코드 기본값 300은 읽었다.
- `prompt.context.refiner`의 런타임 값. 테스트 기대값만 보았고 서버 로그는 열지 않았다.
- `application-llm.yaml`의 `max-time-budget-ms` 현재 값. 이번 세션에서 다시 열지 않았다.
- LangChain4j 1.0.1 jar 안에 현재 RAG 튜토리얼의 Easy RAG API가 있는지. 튜토리얼 예제는 `1.20.1-beta30`이었다. jar 대조는 NOT_RUN.

## NOT_RUN

Gradle, Node, Verify-RAG, live web.search ON, HOLD/admin Browser, Gemini 실호출, Neo4j, commit, push, AWX mine.

## 트랙 표

추측으로 GREEN을 쓰지 않는다. 이번 세션은 정적 읽기만 했다.

| 트랙 | 이번 세션 |
|---|---|
| NW | not_observed. 과거 보고를 현재 동작으로 재사용하지 않음 |
| API | not_observed |
| WS | not_observed. 웹 ON 강제 없음 |
| ATT | ATT-1 digest 간극을 160행에서 읽음. 테스트 0. PARTIAL이 아니라 아직 미착수 |
| CTX | 2/3 가설 검사와 SystemMessage 조립을 읽음. 다섯 카운트는 not_observed. 미착수 |

## Codex에 이미 준 경로

- `C:\Users\nninn\Downloads\PASTE_CODEX_ATT_GRAPHRAG_SOURCE_MOD.txt`
- `C:\Users\nninn\Downloads\00_START_ATTACHMENT_GRAPHRAG_2026-09-28.txt`
- `C:\Users\nninn\Downloads\PASTE_CODEX_CTX_PREP.txt`
- `C:\Users\nninn\Downloads\00_START_CONTEXT_PREPARATION_2026-09-28.txt`

## Git 읽기

HEAD `2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6`. 브랜치 `codex/owned-runtime-browser-restart`.
스테이지된 외래 파일 `SelfAskPlannerOwnershipContractTest.java`는 유지한다. writer를 죽이지 않고, 스테이지를 비우지 않고, push하지 않는다.

## 반박 질문

A1의 세 질문(증상 은폐, CUE 확대, System 승격)은 T01이 녹색이 된 뒤에만 쓴다.
