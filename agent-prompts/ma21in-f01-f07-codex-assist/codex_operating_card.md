# ma21in F01–F07 Codex 작업 카드

기준: 2026-09-23 라이브 트리. 감사 ZIP과 지시서는 스냅샷이다. 이 카드는 애플리케이션 소스를 고치지 않는다.
Grok 역할: 셋업, 충돌 지도, 읽기 전용 프로브. Codex가 소스를 수정한다.

코덱스에 붙일 한 줄:

> `agent-prompts/ma21in-f01-f07-codex-assist/codex_operating_card.md`와 `probe_f01_f07.py`를 읽고, 패치 전에 `python -B agent-prompts/ma21in-f01-f07-codex-assist/probe_f01_f07.py`를 실행하라. `status=open`인 항목만 do01→do07 순서로 최소 수정하라. `leaseBlocks`가 있는 파일은 건너뛰고, `closed` 항목은 재수정하지 마라.

## 프로브

프로젝트 루트 `C:\AbandonWare\demo-1\demo-1\src`에서 실행한다.

```powershell
python -B agent-prompts/ma21in-f01-f07-codex-assist/probe_f01_f07.py
python -B agent-prompts/ma21in-f01-f07-codex-assist/probe_f01_f07.py --fail-on-open
```

stdout JSON 하나다. `gradleProof`는 항상 `not-run`이다. 프로브 통과는 Gradle 통과가 아니다.
각 블록을 고친 뒤 같은 프로브와 그 블록의 Gradle 테스트를 다시 실행한다.

## do00 라이브 빌드 경계

| 항목 | 2026-09-23 확인 |
|---|---|
| 빌드 루트 | 이 디렉터리. `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts` |
| production sourceSet | `main/java`, `main/resources` |
| test sourceSet | `src/test/java`, `src/test/resources` |
| boot mainClass | `com.example.lms.LmsApplication` |
| LangChain4j | `build.gradle.kts`에 `langchain4j:1.0.1`, `langchain4j-open-ai:1.0.1`, resolutionStrategy가 1.0.1로 고정 |
| dependency insight | 이 카드 작성 시 미실행. JAR API가 필요하면 Codex가 한 번만 실행 |

`bin/main/application-llm.yaml`은 빌드 복사본이다. 수정 대상은 `main/resources/application-llm.yaml`뿐이다.
새 빌드 파일을 만들지 않는다. `src/main/java`로 소스를 복사하지 않는다.

## 라이브 판정 (프로브가 재확인)

순서는 지시서와 같다. 같은 파일은 한 작성자만 수정한다. do02–do05는 `OpenAiResponsesChatModel.java`를 공유하므로 직렬로 한다.

| 블록 | 감사 | 10:10Z 프로브 | Codex가 할 일 |
|---|---|---|---|
| do01 | F01 | closed, Gradle 미실행 | 다시 고치지 말고 기존 테스트만 실행해 회귀를 고정한다 |
| do02 | F03 | open | Responses 경로에서 completions 문자열 변환을 끊는다 |
| do03 | F04 | open | cap이 없는 생성 호출만 보완한다. ChatWorkflow fallback은 이미 cap을 넘긴다 |
| do04 | F05 | open | 텍스트 유무와 incomplete_details를 분리한다 |
| do05 | F02 | open | OpenAiCompatBaseUrl의 공용 잘라내기를 고친다. 호출자가 많다 |
| do06 | F06 | open | embedding.provider=hf가 Ollama로 조용히 떨어지지 않게 한다 |
| do07 | F07 | open | EmbeddingMatch에 질의 벡터를 넣지 않는다 |

### F01 — 소스 앵커는 이미 닫혀 있다

2026-09-23 10:10Z 프로브는 F01을 `closed`로 봤다. 그 전에 작업 트리가 바뀌어 있었다. 감사 ZIP의 광역 접두어와 부분 문자열 host 판정은 현재 소스에 없다.

현재 `ModelGuardSupport`는 목록과 정확히 같은 이름만 전용으로 보고, host는 `https` + `api.openai.com`만 참이다.
현재 YAML `responses-only-prefixes`는 `gpt-5.5-pro`, `gpt-5-pro`, `gpt-5.1-codex`, `gpt-5-codex`, `o3-deep-research`, `o4-mini-deep-research`다. `gpt-4.1`, `gpt-4o`, `o1`, `o3`, `o4` 광역 항목은 없다.
Java 기본 목록에는 `gpt-5.5-pro`가 없다. `application-llm.yaml`이 바인딩되면 Spring이 Java 기본 목록을 YAML 목록으로 통째로 바꾼다. 이 차이만으로 F01을 다시 열지 마라.

이미 있는 파일: `src/test/java/ai/abandonware/nova/orch/llm/ModelGuardEndpointContractTest.java`.
새로 같은 이름의 테스트를 만들지 마라. 프로브가 다시 `open`이 되거나 이 테스트가 실패할 때만 최소 수정한다. 테스트가 요구하는 계약은 다음이다.

- YAML을 바인딩한 설정과 Java 기본 생성자 둘 다에서 `gpt-4.1`, `gpt-4.1-mini`, `gpt-4o`, `gpt-4o-mini`, `o3`, `o3-mini`, `o4-mini`는 Responses 전용이 아니다.
- `gpt-5-pro`, `o3-deep-research`, `o4-mini-deep-research`는 전용으로 남는다.
- 목록의 `gpt-5-pro`가 `gpt-5-pro-unverified`까지 전용으로 만들지 않는다.
- OpenAI host 판정은 `https://api.openai.com` 권한만 참이다. `http://api.openai.com`은 거짓이다.

`ModelGuardYamlCompatibilityTest`는 광역 접두어를 금지하지 않지만, YAML에 `gpt-5-pro`, `gpt-5.5-pro`, `gpt-5.1-codex`, `gpt-5-codex`, `o3-deep-research`, `o4-mini-deep-research`가 남고 `gpt-5.5`, `gpt-5.6-luna`는 전용이 아니어야 통과한다. 이 테스트 파일은 다른 리스에 있다. 수정하지 말고 실행만 하라.
Spring은 YAML 리스트로 Java 기본 리스트를 통째로 바꾼다. 두 리스트를 합친다고 가정하지 마라.
전용 목록을 비우지 마라. 미확인 모델을 유료 모델로 바꾸지 마라.
모델 태그를 새 Java 카탈로그로 복제하지 마라. 기존 `nova.orch.model-guard.responses-only-prefixes`만 좁혀라. `configs/api-routing.yaml`로 이 목록을 이사하는 작업은 범위 밖이다.

### F03

`OpenAiResponsesChatModel`이 completions 프롬프트 함수로 Responses input을 만든다. 그 호출만 끊는다.
legacy completions 변환 함수는 그 경로에 남긴다.
도구 왕복(function_call, call_id, 두 번째 요청, reasoning item)을 1.0.1에서 보존하지 못하면 평문으로 바꾸지 말고, 외부 호출 전에 미지원을 반환한다. 텍스트 역할 복구와 도구 미완료를 결과에 나눠 적어라.
제안 테스트 ResponsesMessageContractTest, ResponsesToolRoundTripContractTest는 아직 없다.

### F04

10인자 생성자는 이미 출력 cap 정수를 받는다. 새 생성자를 만들지 마라.
`OpenAiChatModelGuardAspect`의 ROUTE_RESPONSES 생성과 `LlmRouterAspect`의 생성은 cap을 넘기지 않는다.
`ChatWorkflow.callResponsesFallback`은 이미 계산된 cap을 넘긴다. 그 호출을 다시 고치지 마라.
timeout, retry 횟수를 cap으로 읽지 마라. cap이 없으면 max_output_tokens를 생략하고, 더 큰 DTO 값으로 되돌리지 마라.
`OpenAiResponsesChatModelTest`는 cap이 있을 때 payload 키를 이미 검사한다. 그 검사를 약화하지 마라.
`LlmRouterAspect.java`는 만료 리스가 겹친다. 그 파일은 이번 턴에 수정하지 말고, guard aspect만 고친 뒤 프로브의 routerAspectPassesCap=false를 미완료로 남겨라.

### F05

성공 판정이 텍스트 추출 후 텍스트 유무다. incomplete_details를 읽지 않는다.
부분 텍스트는 유지하되 완료와 구분한다. 무출력을 공급자 고장으로만 뭉개지 않는다. 거절, 필터, 도구만 있는 응답, 실패, 취소를 구분한다.
부분 응답 때문에 cap을 키우거나 생성 요청을 반복하지 마라.
제안 테스트 ResponsesTerminalStatusContractTest는 아직 없다.

### F02

`OpenAiCompatBaseUrl.sanitize`는 `/v1` 경계 뒤를 자르거나, 없으면 `/v1`을 붙인다.
그래서 `/v1beta/openai/`는 끝에 `/v1`이 더 붙고, 그 결과를 한 번 더 정규화하면 `/v1`이 또 붙는다. `/v1/tenant-a`는 tenant가 사라진다.
고친 뒤에도 다음은 유지한다.

- `https://api.openai.com/v1`는 그대로
- `http://127.0.0.1:11435/v1`는 그대로
- `http://127.0.0.1:11434`처럼 API 경로가 없는 origin만 `/v1`을 보완

같은 함수를 쓰는 다른 파일: `LlmConfig`, `QueryTransformer`, `OllamaNativeChatModel`, `LocalLlmGatewaySecurity`, `ChatWorkflow`, `OpenAiResponsesChatModel`. `DynamicChatModelFactory`도 호출하지만 그 파일은 리스에 있다. 팩토리를 열지 말고 공용 함수와 그 함수의 계약 테스트로 고쳐라.
정규화 두 번이 같아야 한다. `/responses`나 `/v1`을 중복으로 붙이지 마라. 쿼리를 지우거나 허용 목적지 검사를 우회하지 마라.
제안 테스트 OpenAiCompatBaseUrlContractTest는 아직 없다.

### F06

`LangChainConfig`의 Primary 선택은 `embedding.provider`이고 openai / none / ollama / 그 외 Ollama다. hf 분기가 없다.
`HfInferenceEmbeddingModel`은 `embeddings.provider`를 읽는다. 키를 삭제하거나 이름만 바꾸지 마라.
알 수 없는 값을 Ollama로 바꾸지 마라. HF의 URL, 응답 모양, pooling을 이 턴에 증명하지 못하면 HF는 명시적 미지원으로 두고, 조용한 치환만 제거한다.
운영 인덱스를 재생성하거나 차원만 맞춰 섞지 마라.
제안 테스트 EmbeddingProviderSelectionContractTest는 아직 없다.

### F07

includeVectors=false는 유지해도 된다. 잘못된 부분은 EmbeddingMatch 세 번째 인자에 질의 벡터를 넣는 줄이다.
`FederatedEmbeddingStore`가 매치의 embedding을 복사하는 것은 따라가는 동작이다. 질의 벡터나 zero vector로 그 복사를 바꾸지 마라.
1.0.1 EmbeddingMatch가 null을 허용하는지는 로컬 JAR로 확인하고, 최신 온라인 Javadoc을 근거로 쓰지 마라. null이 불가하고 소비자가 벡터를 쓰지 않으면 명시적 미수신을 기존 타입으로 표현한다. 모든 검색에 includeVectors=true를 강제하지 마라.
제안 테스트 UpstashMatchedEmbeddingContractTest는 아직 없다.

## 리스 — 훔치지 마라

만료여도 owner가 죽지 않았으면 겹치는 경로는 수정하지 않는다. 락 삭제, recover, 강제 해제는 하지 않는다.
프로브의 repairPathLeases가 최신이다. 카드 작성 시점에 막혀 있던 경로:

| 리스 | 상태 | 막힌 경로 | 영향 |
|---|---|---|---|
| exact-model-selection-9e8c3beb | 만료, owner-evidence-needed | LlmRouterAspect.java, DynamicChatModelFactory.java, chat.js, ExactModelGatewayTest.java | do03 라우터 호출, do05 팩토리, chat.js |
| report-crosscheck-src | 만료, owner-evidence-needed | ModelGuardYamlCompatibilityTest.java 외 | F01 기존 YAML 테스트는 실행만 |
| mutable-spec-policy | 활성 | docs/PROJECT_STATUS.md, skills index, windsurf/cline/devin 규칙 | 상태 문서와 그 규칙 파일을 수정하지 않음 |
| vibe-skill-router | 활성 | skills-intent-index.yaml, 여러 SKILL.md, demo1_vibe_skill_router.py | 라우터 인덱스에 스킬을 추가하지 않음 |

AGENTS.md는 mutable-spec 저널 plannedScope에 있다. 전역 지침은 이 카드로 대신한다. AGENTS.md에 이 작업을 끼워 넣지 마라.

## 보존

- Java 17, LangChain4j 1.0.1, 기존 프로퍼티와 환경 변수 이름
- owner token, 인증, 로컬/원격 허용, 유료 호출 동의, 시간예산
- Ollama embed의 최상위 dimensions (`OllamaEmbeddingModel.buildEmbedBody`)
- chat.js의 createSseEventParser
- ChatWorkflow Responses fallback의 cap 전달
- 유료 호출, 운영 인덱스 초기화, 서버 재시작, Git 커밋은 이 계약 복구의 수단이 아니다
- 계약 증명은 mock HTTP의 요청 본문, 최종 URI, 선택된 delegate, 반환 상태다. 설정값과 내부 로그만으로 통과시키지 마라

## Gradle

한 번에 하나의 Gradle만 실행한다. 다른 세션이 같은 캐시를 쓰면 기다린다.

```powershell
.\gradlew.bat test --tests ai.abandonware.nova.orch.llm.ModelGuardEndpointContractTest --tests ai.abandonware.nova.orch.aop.ModelGuardYamlCompatibilityTest
```

F01 다음에는 가드를 쓰는 기존 테스트도 돌린다. ModelGuardExpectedFailureContractTest, LlmRouterGatewaySecurityTest.
새 계약 테스트는 `src/test/java`에 두고, 지시서의 클래스명을 재사용한다. 이미 있는 테스트를 통과시키는 쪽이 우선이다.
기록은 `python -B scripts/run_verified_command.py --output <새 디렉터리> -- <명령>`으로 남긴다. wrapper가 tests=0으로 보여도 Gradle 리포터의 실제 건수를 적는다.

## 보고

각 블록을 Observation, Patch Blocks, Setup Commands, Verification으로 적는다.
명령, 종료 코드, 미검증 항목을 구분한다. 리스로 건너뛴 파일은 미완료로 남긴다.
세 역할의 합의만으로 완료하지 않는다. 채택 근거는 프로브 JSON과 실행한 assertion이다.
