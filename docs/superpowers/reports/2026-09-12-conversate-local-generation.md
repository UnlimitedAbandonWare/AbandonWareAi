# Conversate A4 — 선택적 로컬 카드 생성 구현 및 검증

전체 목표는 ACTIVE이며 미완료다. 이 보고서는 승인된 자체 Conversate 기능의 A4 하위 구현을 기록한다. 휴대폰 입력부터 Meta Display 실기 출력까지 완료됐다는 판정이 아니다. 기존 승인과 저장 경계는 유지하며 설계 승인을 다시 요구하지 않았다.

## 실제 변경

| 파일 | 변경 내용 |
|---|---|
| `main/java/com/example/lms/llm/OllamaNativeChatModel.java` | `chatStructured(messages, schema)`가 기존 native HTTP 호출에 `format`만 추가한다. 기존 `chat`은 schema 없이 같은 호출 경로를 사용한다. |
| `main/java/com/example/lms/assist/ConversateCardPrompt.java` | Spring prompt advice와 기존 memory 주입을 거치지 않는 독립 prompt 경계. 질문 2,048자, 조회 근거 최대 6개·각 2,048자·합계 8,192자, 실제 evidence ID 열거형. |
| `main/java/com/example/lms/assist/ConversateCardVerifier.java` | JSON 중복 필드·후행 데이터·깊이·출력 길이 제한. 실제 조회 ID, 한국어/120자/3줄, 수치·단위·부정어·조건 및 근거 문구 순서를 검사한다. 반례가 있으면 SHOW를 거부한다. |
| `main/java/com/example/lms/assist/ConversateLocalCardGenerator.java` | 기본 OFF, 명시적으로 설정한 literal loopback native endpoint만 사용. 기존 worker에서 최대 4초, 512 출력 토큰, 호출 1회, CPU/다른 제공자 재시도 없음. 기존 시간 예산 복원, 오류 분류, 원문 없는 결과 진단. |
| `main/java/com/example/lms/assist/ConversateAnswerPipeline.java` | 기존 ACL 자료 → BM25 → lexical 선택 후 긴 근거를 요약할 때만 생성기를 사용한다. 짧은 답·NO_MATCH·수치 충돌은 기존 보수적 경로를 유지한다. |
| `main/java/com/example/lms/assist/ConversateSessionService.java` | 기존 실제 worker 단위 취소·epoch 검사에 생성 결과를 연결한다. 시도 수, 복합 판단 수, 보류 수, 최근 생성 지연만 추가한다. |
| `main/resources/static/conversate/app.js` | 기존 진단 줄에 생성 횟수·지연·보류 횟수를 표시한다. |

추가 의존성은 없다. Java17, Spring Boot3.3.4, LangChain4j1.0.1을 유지했다. 설정을 활성화하거나 기존 property/opnessl/openssl/TLS/전역 환경·모델 프로세스를 변경하지 않았다. 새 설정은 `conversate.generation.enabled=false`, `base-url`, `model`, `timeout-ms=4000`이다. 실제 활성화에는 상위 `conversate.enabled=true`도 필요하다. endpoint/model은 현재 확인한 값을 명시적으로 지정하며 과거 포트나 GPU 번호를 기본값으로 하드코딩하지 않는다.

구조화 응답은 현재 [Ollama chat API](https://docs.ollama.com/api/chat)와 [structured outputs 문서](https://docs.ollama.com/capabilities/structured-outputs)를 확인해 연결했다. 공식 문서 조회일은 2026-09-12다. SDK나 서버 LC4j 버전을 올리지 않았다.

## 개인정보와 판단 한계

실시간 질문·근거 사본·모델 응답·카드는 기존 상한 있는 assist 메모리에서만 처리한다. JobService, RDB/Redis, 채팅 기록, ctx.memory, 벡터 쓰기, 브라우저 저장소를 새로 연결하지 않았다. 기존 Native 진단은 해시·길이·오류 분류를 사용하며 모델 출력 전체를 UI 진단이나 로그에 추가하지 않는다. sourceId는 조회 근거의 부분집합으로 다시 검사한다.

복합 판단은 한 모델 호출 안의 positive/negative/neutral 구조다. 독립 모델 3개를 호출했다는 의미가 아니다. 단순 요약은 후보 1개, 반례 배열 없음이며 복합 판단은 후보/반례 각각 최대 2개다. 현재는 FACT 카드만 생성하고, 근거 부족·검증 실패는 고정된 짧은 ASK/HOLD로 처리한다. 제안·개념 설명의 의미 검증 확장은 남아 있다.

검증기는 수치·부정어·조건 보존과 근거 어순을 검사하는 보수적 추출 검증이다. 일반적인 문장 의미의 완전한 함의 증명이 아니다. 자연스러운 의역도 거절할 수 있으며 자료의 모든 조건을 짧게 보존하지 못하면 표시를 보류한다. 기존 공유 벡터/ONNX/그래프/웹 경로를 연결했다는 주장은 하지 않는다. 제공자 내부 KV/보관 정책과 실제 GPU 연산 중단은 별도 미검증이다.

## 새 검증 결과

| 구분 | 명령/증거 | 결과 |
|---|---|---|
| 최초 RED | `test --tests com.example.lms.assist.ConversateLocalCardGeneratorTest` / `a4-red.log` | exit1, 신규 클래스 부재로 컴파일 실패 |
| 검증기 초기 | 같은 선택자 / `a4-generator.log` | exit0, 당시 5개 테스트 통과 |
| 확대 RED | assist 전체 + native 관련 4개 클래스 / `a4-regression-red.log` | 신규 취소 fixture의 비해시 owner가 기존 admission에서 거절돼 1개 실패. fixture만 기존 64자리 해시 형식으로 수정 |
| 최종 Java | 아래 선택자 / `a4-regression.log`, `a4-test-results/` | exit0, **90 pass / 0 fail / 0 error / 0 skip** |
| UI/PCM | `node --test src/test/js/conversate-ui.test.cjs src/test/js/conversate-pcm.test.cjs` / `a4-ui.log` | exit0, **4 pass / 0 fail / 0 skip** |
| 빌드 | `checkLangchain4jVersionPurity checkSourceSetHygiene :app:classes bootJar` / `a4-build.log` | exit0, owners2, retainedFiles2127, duplicateGroups0 |
| diff | `git --no-optional-locks apply --reverse --check -- data/agent-handoff/conversate/20260912-01a09365/conversate-approved-source.patch` | exit0, 원복 실행 없이 적용 가능성만 검사 |

최종 Gradle 테스트 선택자는 `--tests 'com.example.lms.assist.*Test' --tests com.example.lms.llm.OllamaNativeChatModelTest --tests com.example.lms.llm.OllamaNativeFailurePayloadTest --tests com.example.lms.llm.HttpTransportCancellationTest --tests com.example.lms.llm.LlmInFlightCancellationTest`다. 공통 인자는 `--offline --no-daemon --console=plain --project-cache-dir C:\Users\nninn\AppData\Local\awx-gradle-project-cache\desktop-api-reliability`다. process 환경은 기존 `AWX_AGENT_HOST=desktop`, `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop-api-reliability`, `GRADLE_USER_HOME=C:\Users\nninn\.gradle-awx-desktop`을 사용했다. ASR 테스트 Python은 이 작업의 격리 venv다.

신규 테스트는 생성기 9개와 실제 인증 HTTP 경로 2개다. 잘못된 출처·금액·단위·부정어·조건 삭제·새 사실·JSON 파손·120자 초과, NO_MATCH/짧은 답에서 호출 없음, 단일 native HTTP 요청, 403/404/429/500 분류, 채팅 실행 컨텍스트 없는 소켓 취소, 일시정지 후 늦은 결과 폐기와 실제 worker 종료 전 용량 보존을 포함한다. 기존 42개 기준선이나 B4의169개 결과를 A4 새 검증으로 계산하지 않았다.

## 브라우저와 실제 모델은 별개

인증된 loopback18087 테스트 host에서 허용 자료 선택 → 한국어 질문 → native HTTP 합성 응답 → 검증 카드 → 출력 페이지 경로를 확인했다. 화면에는 `미개봉 제품은 7일 이내 환불이 가능합니다.`와 실제 fixture 근거 ID가 표시됐다. 새 생성 횟수/지연 진단도 갱신됐다. 600×600에서 scrollExtent도600×600이며 제어 버튼5개 높이88px이었다. 종료 후 STOPPED, 임시 viewport 복원 확인. 이 host의 ASR 설정은 OFF이고 실제 안경이 아니다.

브라우저 fixture에서 관찰한 생성 지연은406ms와9ms, 처리 지연은419ms와11ms다. 모델이 합성 응답을 반환하므로 LLM 성능이나 p95가 아니다. 자료 선택 완료 전에 바로 보낸 첫 질문은 준비 자료 없이 처리되고 이후 선택으로 카드가 지워졌다. 자료 선택 완료 뒤 질문에서는 생성 경로를 확인했다. 이를 첫 시도 성공으로 기록하지 않는다.

실제 설치 목록과 실행 포트를 현재 조회한 뒤 loopback11434의 `gemma3:4b`에 합성 자료로 최대3회·각4초의 제한을 두고 시험했다. 모델 로드0 상태의 첫 요청이 **4,201ms, GENERATION_TIMEOUT, ASK, 시도1회**로 끝났다. 실패 후 추가 요청0회, 모델 변경/다운로드/종료0회다. probe 프로세스 exit0은 결과 출력이 정상 종료됐다는 뜻이며 **모델 수용 시험은 FAIL**이다. 이 결과만으로 냉시작, GPU 메모리, prefill 중 어느 부분이 병목인지 단정할 수 없다. 실제 모델 답변 품질·완료율·2.5초/6초 목표는 미검증이다.

소켓 시험에서는 plain worker interrupt 후 상대 서버 EOF/reset을 **1ms, n=1**에 확인했다. 시험 한계는2초이며 GPU 연산1초 중단 목표와 분리한다. 실제 제공자 연산 중단은 `not_observed`다.

## 보존 및 다음 실행

34개 승인 source/sidecar 경로의 누적 패치·전후 SHA·secretPatternHits0은 `data/agent-handoff/conversate/20260912-01a09365/change-manifest.json`과 `conversate-approved-source.patch`에 있다. 패치 SHA256은 `4e29ad846010a9804c16eef8311d0689de02a00800af208facbbb7df27752ff9`다. 이전 B4 누적본은 `b4-conversate-approved-source.patch`와 `b4-change-manifest.json`으로 보존했다. 파일은 검토/롤백 자료이며 pending PatchDrop apply queue에 넣지 않았다. 기존 index.lock, 다른 변경분과 HOLD를 보존하고 소유한 편집 lease는 종료했다.

다음 독립 구현은 A5: 현재 prepared-read 예외·검색 실패 원인을 보존하면서 **정상 NO_MATCH에서만 최대1회**의 제한된 질의 보완을 기존 검색 경계에 연결하고 회귀 시험한다. 벡터/semantic reranker를 사용하려면 실제 활성 모델·차원·ACL·외부 전송/보관 계약을 먼저 확인한다. 비활성 ONNX를 성공한 semantic reranker로 표시하지 않는다. A4 실제 모델 지연은 별도 로컬 실행 증거로 좁혀 진단하며 실패 임계값을 올려 통과시키지 않는다.

현재 task-owned A4 fixture PID42932, loopback18087의 identity는 `a4-fixture-process.json`에 기록했다. 기존 A3 fixture PID26748/18086을 종료하지 않았다. 재시작이 필요하면 해당 PID와 startTime을 먼저 대조한다. 다음 실행은 `java @data/agent-handoff/conversate/20260912-01a09365/a4-fixture.args`이며 해당 포트가 비어 있을 때만 사용한다. 실제 모델 probe args는 실패 재현 자료이고 자동 반복 요청용이 아니다.

실제 휴대폰 마이크·잠금/통화/BT, Meta Display/Neural Band 및20분 사용, 허용 HTTPS, 공유 MariaDB/Redis 배포 수용 시험, 제공자 연산 중단과 기존 T01–T22 원문 매핑은 여전히 별도 남은 항목이다. 이전 Conversate 지시서가 발견되지 않은 사실도 유지한다. 전체 목표 완료·DEVICE_VERIFIED를 선언하지 않는다.
