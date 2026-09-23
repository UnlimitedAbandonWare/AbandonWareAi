# 경량 모델·멀티모달 띵킹 모델 대화 조화 소스수정 지시서

상태: 권장안 2 설계 승인 반영 · source-modification directive 검토본

작성일: 2026-08-23

선택안: 권장안 2 — 타입화된 `ConversationFrameV1`과 비대칭 모델 역할 분담

지시서 ID: `demo1.lightweight-multimodal-conversation-harmony.v1`

현재 세션 범위: 문서 작성과 검증만 수행하며 애플리케이션 소스·테스트·리소스는 수정하지 않음

## 1. 목표

다른 구현 에이전트가 이 문서만으로 안전하게 작업할 수 있도록, 대화 중 사용자가 중단·관계 회복·정서적 지원·즉각적 안전 신호를 보냈을 때 경량 보조 모델과 최종 멀티모달 모델이 서로 권한을 침범하지 않게 하는 소스수정 계약을 정의한다.

완료된 구현은 다음 결과를 보장해야 한다.

1. 결정론적 정책이 현재 요청의 대화 자세를 `STANDARD`, `REPAIR`, `SUPPORTIVE_CHECK_IN`, `SAFETY_FIRST` 중 하나로 분류한다.
2. 경량 모델은 허용된 경우에도 관찰용 가설만 제공하며 사실 확정, 최종 답변, 메모리 쓰기, 이미지 원본 접근 권한을 갖지 않는다.
3. `REPAIR`, `SUPPORTIVE_CHECK_IN`, `SAFETY_FIRST`에서는 경량 후보 생성과 선택적 확장·재정제를 중단하고, 주 모델이 짧고 상황에 맞는 최종 답변을 한 번 작성한다.
4. 이미지가 있으면 검증된 비전 모델 라우트의 주 모델에만 실제 `ImageContent`를 전달한다. 비전 경로를 증명할 수 없으면 이미지를 무시하거나 텍스트 모델로 조용히 대체하지 않고 `evidence_needed`를 반환한다.
5. 기존 증거 정책, 검색·인용 판단, 사실 신뢰도, `PromptBuilder.build(PromptContext)` 경계, 모델 버전과 비밀 흐름은 대화 자세 때문에 변하지 않는다.
6. 기능은 `OFF -> SHADOW -> ENFORCE`로 단계적으로 활성화하며, 원문 대신 열거형·카운트·해시·사유 코드만 기록한다.

이 지시서 작성 세션에서는 커밋, 스테이징, 브랜치 생성, 푸시, 런타임 실행, 제공자 호출을 하지 않는다.

## 2. 입력 자료와 개인정보 경계

사용자가 지정한 첨부 대화는 UTF-8로 읽었으며 다음 증거 앵커로만 참조한다.

- 경로: `C:\Users\nninn\.codex\attachments\20e36199-f7ad-49f8-927b-9a45b529f512\pasted-text-1.txt`
- 바이트 수: `57925`
- SHA-256: `CE591E553DB5791BA9E72DB754B074DD5261A7D123FE11A99135D45FAA4E3396`

첨부에서 필요한 것은 사적 대화 원문이 아니라 다음 행동 패턴뿐이다.

- 사용자가 불편함, 상처, 중단 또는 안전 신호를 보낸 뒤에도 보조 관점과 후처리가 원인 분석·관계 평가·설득을 계속할 수 있다.
- 여러 에이전트의 말이 많아질수록 최종 응답 권한과 중단 책임이 불분명해진다.
- 경량 모델의 빠른 관찰은 유용하지만, 그것이 독립 증거나 최종 판단처럼 승격되면 대화 품질이 나빠진다.

첨부 원문, 인물명, 연락처, 구체적 사건, 원문 메시지, 이미지 데이터는 소스, 테스트 fixture, 로그, TraceStore, 문서의 예제로 복사하지 않는다. 테스트는 합성 문장과 합성 1x1 이미지 바이트만 사용한다.

## 3. 현재 라이브 증거 스냅샷

- canonical root: `C:\AbandonWare\demo-1\demo-1\src`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- `.git/index.lock`: 없음
- root main sourceSet: `main/java`, `main/resources`
- root test sourceSet: `src/test/java`, `src/test/resources`
- `:app` sourceSet: `app/src/main/java_clean`, `app/src/main/resources`
- 실제 경량·비전 제공자 가용성과 생성 성공: `not_observed`

현재 관련 파일에는 사용자 소유 변경이 이미 존재한다. 아래 해시는 설계 시점의 읽기 전용 증거 앵커일 뿐 편집 권한이나 복원 기준이 아니다.

| 파일 | Git 상태 | SHA-256 |
| --- | --- | --- |
| `main/java/com/example/lms/guard/InteractionEvidencePolicy.java` | untracked | `E3A7B53549B774536E924E75FAAA6E6578A491310EECC0620F3C97BB2CCBB026` |
| `main/java/com/example/lms/service/guard/SensitiveTopicDetector.java` | modified | `AD89E620EEB480117D3FEBA550371CA1658657D1CEB0923DC1336A7C5DE3922F` |
| `main/java/com/example/lms/prompt/PromptContext.java` | modified | `F6C0BBEA6E6F20AE586562178CBE277B0AF91CB53C17D1151385DDBFA65A7F68` |
| `main/java/com/example/lms/prompt/StandardPromptBuilder.java` | modified | `6BB58C1EE06B4395E006A4748F83C6B84BE9E7510CF56AA416A4FF022B41F83A` |
| `main/java/com/example/lms/service/ChatWorkflow.java` | modified | `6840497F315B594241334AC3AF3425A142095339B18C7142A4016CE4E0F7EE41` |
| `main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java` | modified | `4D95ABF3D08F0D0C2989A25DDB6B944E0A3EB94EA6485A229558F486896E0919` |
| `main/java/com/example/lms/dto/ChatRequestDto.java` | modified | `BBECE1E3FC13A49248D3841906B30C8CD42BF84F2296ECEDFAF6B296D6FED329` |
| `main/java/com/example/lms/api/PublicRequestBudgetGuard.java` | untracked | `0252B232C8C3F9587F2025B51F88BEC259C551AB1500291C8D4D27EAA35E2F49` |
| `main/java/com/example/lms/telemetry/MlaBreadcrumb.java` | modified | `BDB1AFBDFD8C5884575FEFEAD7C1C93A688AAD885CA73500EB168ADB860934FD` |
| `main/java/com/example/lms/service/rag/plan/PlanModelResolver.java` | clean | `9ABF08C1E42B293D7B5A2C70CB47FFEEA329FE0785FEC685F757E03B89CBD2D5` |
| `main/resources/application-llm.yaml` | modified | `943AB31CFFEAA8A166087A27F3E4905E608A26BB16164356E59D350418B0F832` |
| `main/resources/configs/models.manifest.yaml` | clean | `EBCB5A039BE27893C3401DE2DB331C0AC34A93F8B15AB1E96B7D1B28B4E48570` |

미래 구현 세션은 모든 활성 대상의 현재 상태와 SHA-256을 편집 직전에 다시 계산해야 한다. 해시가 다르면 새 본문을 권위로 재탐침하고 패치를 재조정한다. 위 해시나 Git의 과거 본문으로 사용자 변경을 되돌리지 않는다.

## 4. 소스에서 확인된 핵심 결함

### 4.1 증거 정책에는 대화 자세가 없다

`InteractionEvidencePolicy`는 현재 다음 타입 축을 갖는다.

- `ResponseStyle`: `STANDARD`, `COLLABORATIVE`
- `SecurityStance`: `NEUTRAL`, `DEFENSIVE`
- `EvidenceMode`: `BASELINE`, `STRICT`
- `MemoryWriteMode`: `NORMAL`, `SUPPRESS`
- `FailureMode`: `LEGACY_FAIL_SOFT`, `FAIL_CLOSED`
- `FeatureMode`: `OFF`, `SHADOW`, `ENFORCE`

이 정책은 증거 조작과 보안 방어에 적합하지만, 사용자가 “중단”, “관계 회복”, “정서적 확인”, “즉각적 안전”을 요구하는지를 나타내는 별도 타입이 없다. 감정 신호를 이 증거 정책에 직접 넣으면 감정 표현이 사실 신뢰도나 증거 격리를 바꾸는 잘못된 결합이 생긴다.

따라서 `InteractionEvidencePolicy`의 기존 축은 그대로 두고 독립된 `ConversationFrameV1`을 추가한다.

### 4.2 민감 주제 탐지는 개인정보 경계만 바꾼다

`SensitiveTopicDetector`는 자해·자살·트라우마·학대 등 민감 신호를 탐지한 뒤 메모리 비활성, 답변 온도 제한, 웹 질의 마스킹과 선택적 웹 차단을 설정한다. 그러나 이 결과는 최종 답변이 분석을 멈추고 안전을 먼저 확인해야 한다는 대화 자세로 전달되지 않는다.

민감 주제와 즉각적 안전 위험은 동일하지 않다. 교육·번역·제3자 인용처럼 위험하지 않은 메타 언급도 있을 수 있으므로, `SensitiveTopicDetector.isSensitiveTopic()`만으로 `SAFETY_FIRST`를 선택하지 않는다. 기존 detector는 개인정보 경계 소유자로 유지한다.

### 4.3 최종 프롬프트는 일반 답변 흐름을 유지한다

`StandardPromptBuilder`는 기본적으로 `AnswerMode.ALL_ROUNDER`와 일반 시스템 역할을 렌더링한다. `InteractionEvidencePolicy`의 협업·방어 블록은 존재하지만, 중단 요청 후 원인 분석 금지, 관계 회복 우선, 정서적 체크인, 안전 우선 답변 형태를 강제하는 블록은 없다.

최종 프롬프트 조립은 계속 `PromptBuilder.build(PromptContext)`에서만 수행해야 한다. `ChatWorkflow` 안에 최종 대화 지시 문자열을 새로 조립하지 않는다.

### 4.4 경량 후보는 자세와 무관하게 선택적 정제에 참여할 수 있다

`DiverseSamplingOrchestrator`의 후보는 이미 “가설이며 verdict가 아님”이라는 계약을 갖고, `EnsembleFinalAnswerService`는 후보를 `mutationAllowed=false`와 `decisionAuthority=primary_model|probe_only`로 다룬다. 이 경계는 유지한다.

그러나 `ChatWorkflow`의 `prompt.context.refiner` 활성화 여부는 증거 오염도와 제공자 상태를 중심으로 판단하며, 대화 자세가 `REPAIR` 또는 `SAFETY_FIRST`인지에 따른 금지 조건이 없다. 따라서 사용자가 멈추라고 한 상황에도 SUPPORT/FALSIFY 후보가 추가 분석 재료로 붙을 수 있다.

### 4.5 이미지 필드는 수신되지만 최종 모델 메시지에는 전달되지 않는다

현재 확인된 경로는 다음과 같다.

- `ChatRequestDto.imageBase64`는 공개 요청 필드다.
- `PublicRequestBudgetGuard`는 최대 길이 `8388608`자를 검사한다.
- `ChatService`는 요청 fingerprint와 단순 경로 판정에 이미지 존재를 반영한다.
- `ChatWorkflow`의 최종 사용자 메시지는 `UserMessage.from(finalQuery)`로 텍스트만 만든다.
- 활성 비전 라우트는 `llmrouter.vision -> llm.vision.model -> qwen3-vl:8b`로 이미 존재한다.
- `DynamicChatModelFactory`는 `qwen3-vl`을 전용 vision base URL로 보낸다.
- `models.manifest.yaml`은 `qwen3-vl:8b`에 `[chat, vision]` capability를 선언한다.
- `ModelRuntimeRequestTimelineTest`에는 `UserMessage.from(TextContent, ImageContent)`가 요청 타임라인 래퍼를 통과하는 테스트가 있다.

즉, 새 SDK나 새 모델을 도입할 필요가 없다. 누락된 것은 요청의 이미지 데이터를 검증된 비전 주 모델 메시지로 연결하는 활성 호출 경계다.

## 5. 근본 원인과 선택한 구조

가장 영향이 큰 한 가지 원인은 **현재 요청을 위한 타입화된 대화 프레임이 없어서, 경량 후보·직접 응답·선택적 후처리·최종 주 모델이 같은 중단 및 권한 규칙을 공유하지 못하는 것**이다.

현재 흐름은 다음과 같다.

```text
사용자 신호
  -> 개인정보/증거 플래그
  -> 대화 자세 없음
  -> 경량 후보와 직접 fallback이 계속 가능
  -> 일반 ALL_ROUNDER 프롬프트
  -> 선택적 확장/재정제가 분석을 다시 늘릴 수 있음
```

목표 흐름은 다음과 같다.

```text
현재 요청 + 이미지 존재 여부
  -> 결정론적 ConversationFrameResolver
  -> ConversationFrameV1
       ├─ 경량 모델: OBSERVE_ONLY 또는 ABSTAIN
       ├─ 직접 fallback/정제/확장: 자세별 허용 또는 억제
       ├─ PromptContext -> PromptBuilder: 자세별 응답 계약
       └─ 주 모델: 텍스트 또는 검증된 멀티모달 입력의 유일한 최종 권위
  -> sanitizer/guard
  -> 최종 답변
```

새 프레임은 기존 `InteractionEvidencePolicy`의 내부 축이 아니다. 두 정책은 `PromptContext`에서 나란히 이동하되 서로의 사실·증거 결정을 변경하지 않는다.

## 6. `ConversationFrameV1` 타입 계약

다음 두 파일을 새로 만든다.

- `main/java/com/example/lms/guard/ConversationFrameV1.java`
- `main/java/com/example/lms/guard/ConversationFrameResolver.java`

`ConversationFrameV1`은 요청 범위의 불변 record로 구현하며 다음 타입을 소유한다.

```java
public record ConversationFrameV1(
        Mode mode,
        Stance stance,
        LightweightRole lightweightRole,
        boolean multimodalInputPresent,
        boolean suppressOptionalRefinement,
        boolean suppressMemoryWrites,
        ReasonCode reasonCode) {

    public enum Mode { OFF, SHADOW, ENFORCE }

    public enum Stance {
        STANDARD,
        REPAIR,
        SUPPORTIVE_CHECK_IN,
        SAFETY_FIRST
    }

    public enum LightweightRole {
        OBSERVE_ONLY,
        ABSTAIN
    }

    public enum ReasonCode {
        DEFAULT,
        EXPLICIT_STOP,
        ASSISTANT_BOUNDARY_COMPLAINT,
        DISTRESS_CHECK_IN,
        IMMEDIATE_SAFETY_SIGNAL
    }
}
```

record compact constructor와 정적 factory는 다음 불변식을 강제한다.

- null enum은 안전한 기본값으로 정규화한다.
- `OFF`는 동작상 항상 기존 경로와 동일하다.
- `SHADOW`는 감지 결과와 “would suppress” 값만 계산하고 실제 모델 호출, 프롬프트, 메모리, 라우팅을 바꾸지 않는다.
- `ENFORCE + STANDARD`만 기존 선택적 경량 후보를 허용한다.
- `ENFORCE + REPAIR|SUPPORTIVE_CHECK_IN|SAFETY_FIRST`는 `LightweightRole.ABSTAIN`, `suppressOptionalRefinement=true`, `suppressMemoryWrites=true`다.
- 모든 자세에서 최종 답변 권한은 주 모델 한 곳에만 있다. record에 최종 후보 텍스트나 모델 응답을 저장하지 않는다.
- `multimodalInputPresent`는 입력 형태를 나타낼 뿐 이미지 데이터나 MIME 문자열을 저장하지 않는다.

다음 파생 메서드를 제공해 호출부의 조건 중복을 막는다.

```java
boolean enforcementActive();
boolean allowsDirectShortCircuit();
boolean allowsOptionalRefinement();
boolean allowsOptionalExpansion();
boolean suppressesMemoryWrites();
boolean shouldTrace();
```

`allowsDirectShortCircuit()`와 두 optional 허용 메서드는 `OFF`와 `SHADOW`에서 기존 동작을 유지하고, `ENFORCE`의 비표준 자세에서만 `false`를 반환한다.

## 7. 결정론적 자세 판정 계약

`ConversationFrameResolver`는 모델, 검색, DB, 세션 저장소, 시계, 외부 provider를 호출하지 않는다. 입력은 현재 요청의 사용자 텍스트, 이미지 존재 boolean, 설정 mode뿐이다. 대화 원문이나 과거 assistant 답변을 record 또는 TraceStore에 넣지 않는다.

판정 우선순위는 고정한다.

```text
SAFETY_FIRST > REPAIR > SUPPORTIVE_CHECK_IN > STANDARD
```

판정 규칙은 다음과 같다.

1. 입력을 Unicode NFKC로 정규화하고 format character를 제거하며 연속 공백을 하나로 줄인다.
2. 분류용 문자열은 최대 4096자로 제한한다. 앞부분과 끝부분을 보존해 끝에 위치한 중단 신호를 잃지 않는다.
3. 번역, 인용, 예시, 정책 설명, 제3자 전달을 나타내는 메타 문맥은 직접 신호로 승격하지 않는다.
4. “멈추지 말고 계속”처럼 명시적 부정이 붙은 표현은 `EXPLICIT_STOP`으로 오인하지 않는다.
5. `SAFETY_FIRST`는 현재 화자의 직접적이고 즉각적인 자기 위해·자살 위험 표현에만 사용한다. 민감 단어의 교육적·번역적·제3자 언급은 이 자세를 선택하지 않는다.
6. `REPAIR`는 현재 assistant에게 멈춤·거리두기·사과·경계 존중을 요구하거나, assistant가 계속 분석해 상처를 줬다는 직접 신호에 사용한다.
7. `SUPPORTIVE_CHECK_IN`은 즉각적 자기 위해 신호는 없지만 현재 화자가 심한 지침·외로움·상처·압도감을 직접 표현한 경우 사용한다.
8. 어느 규칙도 확실하지 않으면 `STANDARD`다. 모델 추론으로 분류를 보강하지 않는다.

`SAFETY_FIRST` 응답 계약은 진단이나 상담가 역할 연기가 아니다. 공식 WHO 자료의 일반 원칙에 맞춰 비판단적 표현, 명확한 현재 안전 확인, 즉각적 위험 시 지역 응급·위기 지원 또는 신뢰할 수 있는 사람에게 연결하도록 한다. 국가별 번호는 런타임에서 검증된 locale-aware 자원이 있을 때만 사용하며 이 소스에 하드코딩하지 않는다.

참고: [WHO Suicide Q&A](https://www.who.int/news-room/questions-and-answers/item/suicide)

## 8. 모델 역할 행렬

| 자세 | 경량 모델 | 선택적 refiner/확장 | 주 모델 | 메모리 쓰기 |
| --- | --- | --- | --- | --- |
| `STANDARD` | 기존 가설 후보를 `OBSERVE_ONLY`로 제공 가능 | 기존 feature flag와 증거 gate에 따름 | 유일한 최종 답변 권위 | 기존 정책에 따름 |
| `REPAIR` | `ABSTAIN`, 호출 수 0 | 전부 억제 | 짧은 인정·경계 존중·다음 선택 제시 | 억제 |
| `SUPPORTIVE_CHECK_IN` | `ABSTAIN`, 호출 수 0 | 전부 억제 | 비진단적 정서 확인·한 가지 체크인 | 억제 |
| `SAFETY_FIRST` | `ABSTAIN`, 호출 수 0 | 전부 억제 | 직접적 안전 확인·즉각적 지원 연결 | 억제 |

경량 후보가 허용되는 `STANDARD`에서도 다음 계약은 변하지 않는다.

- 후보는 독립 증거가 아니다.
- 후보 수는 투표 수가 아니다.
- 후보는 사실을 확정하거나 사용자의 의도·관계·감정을 단정하지 않는다.
- 후보는 최종 답변으로 직접 반환되지 않는다.
- 후보 텍스트와 경량 모델 응답은 메모리나 공개 telemetry에 저장하지 않는다.
- 이미지 원본, base64, attachment 본문은 경량 모델에 전달하지 않는다.
- 후보 생성 실패나 비활성화는 주 모델 답변을 막지 않는다.

여기서 조화는 모든 모델이 항상 발언하는 것이 아니라, 보조 모델이 관찰자로 머물고 상황에 따라 침묵하며 주 모델의 단일 최종 권한을 보존하는 것을 뜻한다.

## 9. 자세별 최종 프롬프트 계약

`PromptContext`에 `ConversationFrameV1 conversationFrame`을 추가한다. builder 기본값과 null fallback은 `ConversationFrameV1.off(false)`로 두고, copy/toBuilder 경로에서도 손실되지 않게 한다.

`StandardPromptBuilder`는 기존 `appendInteractionPolicyBlocks`와 별개의 `appendConversationFrameBlock`을 호출한다. 이 블록은 최종 `PromptBuilder.build(PromptContext)` 경계 안에서 system role 뒤, evidence 앞에 들어간다. `ENFORCE`가 아니면 아무 문자열도 추가하지 않는다.

### `REPAIR`

- 사용자의 경계 또는 불편을 짧게 인정한다.
- 원인 분석, 상대 의도 추측, 관계 가치 평가, 설득, 변명, 게임·점수 비유를 중단한다.
- 사용자가 요청하지 않은 해결책 목록을 늘리지 않는다.
- 가능한 경우 “지금 멈출지” 또는 “한 가지만 도울지”처럼 한 번의 선택만 제시한다.
- 사과가 필요한 경우 한 번만 구체적으로 하고 자기방어 문장을 붙이지 않는다.

### `SUPPORTIVE_CHECK_IN`

- 감정을 사실처럼 진단하지 않고 사용자가 표현한 부담을 짧게 반영한다.
- 조언보다 현재 원하는 도움 형태를 한 번 확인한다.
- 원치 않는 관계 분석·심리 분석·행동 평가를 추가하지 않는다.
- 응답 길이는 기본 `ALL_ROUNDER`보다 짧게 제한한다.

### `SAFETY_FIRST`

- 비판단적이고 명확하게 현재의 즉각적 안전을 한 번 확인한다.
- 즉각적 위험이 있으면 지역 응급 서비스, 위기 지원, 가까운 신뢰 가능한 사람에게 지금 연결하도록 안내한다.
- 의료 진단, 위험 확률, 과도한 질문 목록, 장황한 원인 분석을 출력하지 않는다.
- 사용자가 이미 답한 안전 정보를 반복 질문하지 않는다.
- 지역이 검증되지 않았으면 특정 국가 번호를 추측하지 않는다.

### `STANDARD`

기존 `AnswerMode`, 증거, citation, guard, verbosity 계약을 그대로 사용한다.

어떤 자세에서도 chain-of-thought, 내부 후보 전문, raw prompt, hidden scoring을 출력하지 않는다.

## 10. 멀티모달 입력 계약

### 요청 DTO와 입력 검증

`ChatRequestDto`의 기존 `imageBase64`를 유지하고 선택 필드 `imageMediaType`을 추가한다.

- 허용값: `image/png`, `image/jpeg`, `image/webp`
- null 또는 blank: 기존 raw-base64 호출 호환을 위해 `image/png`
- `data:` URI 전체가 `imageBase64`에 들어온 경우: 중복 prefix를 조용히 허용하지 않고 고정된 `chat_image_data_uri_not_allowed`로 거부한다.
- Base64 decode 실패: `chat_image_base64_invalid`
- 허용되지 않은 MIME: `chat_image_media_type_unsupported`
- 빈 decode 결과: `chat_image_empty`
- 기존 최대 문자 수 초과: `chat_image_too_large`

`PublicRequestBudgetGuard`에서 위 검증을 수행해 모델 호출 전에 4xx로 종료한다. 로그에는 reason code, 입력 문자 수, decode 바이트 수만 허용하며 base64와 이미지 본문은 남기지 않는다.

### 주 모델 라우팅

`ChatWorkflow`는 `conversation.harmony.mode=ENFORCE`이고 유효한 이미지가 있을 때 다음 순서를 지킨다.

1. `PlanModelResolver.resolveRequestedModel("llmrouter.vision")`으로 기존 비전 alias를 구체 모델로 해석한다.
2. 해석된 모델이 blank, unresolved `${...}`, `llmrouter.*` 잔여 alias, disabled route이면 provider 호출 전에 `evidence_needed: vision_model_unavailable / verify with PlanModelResolverTest and active llm profile`로 종료한다.
3. 현재 `main/resources/application-llm.yaml`의 `llm.vision.model=${LLM_VISION_MODEL:qwen3-vl:8b}`와 기존 manifest를 재사용한다. 새 모델 ID나 새 endpoint property를 만들지 않는다.
4. 최종 사용자 메시지를 다음 모양으로 만든다.

```java
UserMessage.from(
        TextContent.from(finalQuery),
        ImageContent.from(imageBase64, imageMediaType))
```

활성 LangChain4j `1.0.1`에서 단일 문자열 overload는 URI 입력으로 해석된다. 따라서 검증된 raw base64와 allowlisted MIME을 두 인자 overload로 전달해 `Image.base64Data()`와 `Image.mimeType()`을 채우고 `Image.url()`은 비워 둔다.

5. 동일 이미지나 base64를 `DiverseSamplingOrchestrator`, `EnsembleFinalAnswerService`, verifier, memory, TraceStore, DebugEventStore에 전달하지 않는다.
6. 텍스트만 있는 요청은 기존 `UserMessage.from(finalQuery)` 경로를 유지한다.
7. 주 모델이 비전 요청을 실제로 받았는지는 request-attempt timeline의 동일 요청 ledger로 증명한다. route 이름, HTTP 200, 응답 문자열만으로 이미지 wire 전달을 주장하지 않는다.

`SHADOW`에서는 `wouldSelectVisionRoute=true|false`만 기록하고 기존 모델·메시지 호출을 바꾸지 않는다. `OFF`에서는 새 멀티모달 분기와 새 trace를 모두 비활성화한다.

## 11. `ChatWorkflow` 적용 계약

`ChatWorkflow.continueChat`의 현재 입력 단계에서 interaction policy와 별개로 frame을 한 번만 계산한다.

```text
ConversationFrameV1 frame = resolver.resolve(
    currentUserText,
    hasNonBlankImage,
    ConversationFrameV1.Mode.parse(conversationHarmonyMode)
)
```

frame은 동일 요청 전체에서 불변이어야 하며 다음 경계에 전달한다.

1. `PromptContext.builder().conversationFrame(frame)`
2. 직접 short-circuit 허용 여부
3. prompt-context refiner 호출 여부
4. optional free-idea, creative projection, answer expansion, final polish 호출 여부
5. memory-write deny 여부
6. 최종 텍스트/멀티모달 `UserMessage` 구성
7. bounded telemetry

현재 `interactionShortCircuitAllowed`가 보호하는 remembered-value, literal, external-proof, Supabase, mode-status, agent-debug direct answer 경로는 다음과 같이 합성한다.

```text
shortCircuitAllowed = interactionShortCircuitAllowed
    && frame.allowsDirectShortCircuit()
```

비표준 자세에서는 직접 fallback이 자세별 prompt를 우회하지 못해야 한다.

현재 `finalAnswerMemoryDeniedByPolicy`는 다음 의미를 갖게 한다.

```text
memoryWriteDenied = interactionPolicyDecision.suppressMemoryWrites()
    || frame.suppressesMemoryWrites()
```

기존 interaction evidence 정책이 `OFF`여도 conversation frame이 `ENFORCE`이면 대화 자세의 메모리 억제는 작동한다. 반대로 conversation frame이 `OFF|SHADOW`이면 기존 memory 동작을 바꾸지 않는다.

## 12. 경량 후보와 선택적 후처리 적용 계약

`EnsembleFinalAnswerService.sampleCandidatesForRefinement`의 모델 호출보다 앞에 frame gate를 둔다.

- `ctx == null` 또는 frame 누락: 기존 동작과 안전한 off 기본값을 유지한다.
- `frame.mode != ENFORCE`: 기존 feature flag와 citation gate를 유지한다.
- `frame.mode == ENFORCE && !frame.allowsOptionalRefinement()`: 빈 목록을 반환하고 모델 호출 수를 0으로 유지한다.
- 이 경우 bounded trace는 `disabledReason=conversation_stance`, `stance`, `candidateCount=0`, `modelCallCount=0`, `decisionAuthority=primary_model`, `mutationAllowed=false`만 기록한다.

`ChatWorkflow`도 호출부에서 동일 frame을 확인해 불필요한 서비스 진입을 피하되, 중앙 서비스 gate가 최종 방어선이어야 한다.

다음 optional 경로는 `frame.allowsOptionalExpansion()`으로 함께 억제한다.

- prompt-context refiner 후보 부착
- free-idea draft
- dual-view creative merge
- answer expander
- projection final polish처럼 원 응답 의미를 다시 늘리는 후처리

sanitizer, redaction, cancellation, request budget, evidence fail-closed guard는 optional 확장이 아니므로 계속 실행한다.

## 13. 활성 작업 단위

미래 구현 세션의 production 대상은 다음으로 제한한다.

### 새 파일

1. `main/java/com/example/lms/guard/ConversationFrameV1.java`
2. `main/java/com/example/lms/guard/ConversationFrameResolver.java`

### 수정 파일

3. `main/java/com/example/lms/prompt/PromptContext.java`
4. `main/java/com/example/lms/prompt/StandardPromptBuilder.java`
5. `main/java/com/example/lms/service/ChatWorkflow.java`
6. `main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java`
7. `main/java/com/example/lms/dto/ChatRequestDto.java`
8. `main/java/com/example/lms/api/PublicRequestBudgetGuard.java`
9. `main/java/com/example/lms/telemetry/MlaBreadcrumb.java`

### 테스트 대상

1. 새 `src/test/java/com/example/lms/guard/ConversationFrameResolverTest.java`
2. 새 `src/test/java/com/example/lms/service/ChatWorkflowConversationHarmonyContractTest.java`
3. 새 `src/test/java/com/example/lms/service/ChatWorkflowMultimodalPrimaryMessageTest.java`
4. 기존 `src/test/java/com/example/lms/prompt/StandardPromptBuilderInteractionEvidencePolicyTest.java`
5. 기존 `src/test/java/com/example/lms/ensemble/EnsembleFinalAnswerServiceTest.java`
6. 기존 `src/test/java/com/example/lms/api/PublicRequestBudgetGuardTest.java`
7. 기존 `src/test/java/com/example/lms/telemetry/MlaBreadcrumbTest.java`
8. 기존 `src/test/java/com/example/lms/service/rag/plan/PlanModelResolverTest.java`
9. 기존 `src/test/java/com/example/lms/llm/ModelRuntimeRequestTimelineTest.java`
10. 기존 `src/test/java/com/example/lms/manifest/LocalModelConfigYamlTest.java`

테스트 파일의 정확한 현재 소유권과 preimage도 편집 직전에 다시 확인한다. 이미 동등한 테스트가 있으면 새 클래스를 중복 생성하지 않고 기존 클래스에 최소 fixture를 추가한다.

## 14. 명시적 비대상

다음은 이 지시서로 수정하지 않는다.

- `InteractionEvidencePolicy`의 기존 증거·보안 축과 판단 의미
- `SensitiveTopicDetector`의 개인정보·온도·웹 마스킹 계약
- `DiverseSamplingOrchestrator`의 SUPPORT/FALSIFY 또는 3관점 스키마
- `EnsembleJudgeService`의 심판 모델과 디버그 판정 계약
- `DynamicChatModelFactory`, `PlanModelResolver`, `models.manifest.yaml`, `application-llm.yaml`의 기존 비전 모델 ID·endpoint 구조
- Spring Boot 또는 Gradle 버전
- 모든 `dev.langchain4j` 버전 `1.0.1`
- DB, Supabase, DDL, credentials, owner token, ACL, 배포
- `sessionId`, `ctx.memory`, 애플리케이션 장기 메모리의 기존 데이터
- Chat UI 레이아웃, CSS, 인증, 세션 복원
- 기존 Stuff3 또는 독립 3관점 프롬프트 팩
- 새 production dependency, 새 judge 모델, 새 provider, 새 orchestration framework

라이브 증거가 위 비대상 중 하나의 수정 없이는 acceptance를 만족할 수 없음을 보이면 해당 lane만 `HOLD scope_expansion_required`로 반환하고 별도 승인을 받는다.

## 15. RED 계약

소스 패치 전에 다음 테스트가 현재 구현의 실제 결함 때문에 실패하는 것을 확인한다. 이미 동등 계약이 GREEN이면 `verified_no_patch_needed` 또는 `fixture_only_required`로 재분류하고 정상 소스를 일부러 취약하게 만들지 않는다.

### 15.1 frame 분류

합성 입력으로 다음을 단정한다.

- 직접 중단 요청 -> `REPAIR`, `EXPLICIT_STOP`
- assistant가 경계를 무시했다는 직접 불만 -> `REPAIR`, `ASSISTANT_BOUNDARY_COMPLAINT`
- 직접적인 심한 지침·상처 표현이지만 즉각적 위해 신호 없음 -> `SUPPORTIVE_CHECK_IN`
- 현재 화자의 직접적 자기 위해·자살 위험 표현 -> `SAFETY_FIRST`
- 일반 기술 질문 -> `STANDARD`
- “멈추지 말고 계속 설명” -> `STANDARD`
- 중단 표현을 번역하거나 제3자의 말을 인용하는 메타 요청 -> `STANDARD`
- 자기 위해 표현의 정책·번역·제3자 보고 문맥 -> `STANDARD`
- null, blank, format characters, 과도한 길이 -> 예외 없이 bounded 결과
- 우선순위가 겹치면 `SAFETY_FIRST > REPAIR > SUPPORTIVE_CHECK_IN`

### 15.2 mode 의미

- `OFF`: 기존 prompt, short-circuit, refiner, memory, model route와 동일
- `SHADOW`: detected stance trace만 추가되고 모델 호출 수·prompt·결과는 기존과 동일
- `ENFORCE + STANDARD`: 기존 선택적 refiner 허용
- `ENFORCE + REPAIR|SUPPORTIVE_CHECK_IN|SAFETY_FIRST`: auxiliary call 0, optional expansion 0, memory write 0

### 15.3 prompt 의미

- `REPAIR` block은 경계 인정과 분석 중단을 포함하고 관계 가치 평가·설득을 금지한다.
- `SUPPORTIVE_CHECK_IN` block은 비진단적 반영과 한 번의 체크인을 포함한다.
- `SAFETY_FIRST` block은 직접 안전 확인과 즉각적 지역 지원 연결을 포함하고 특정 국가 번호를 하드코딩하지 않는다.
- `OFF|SHADOW|STANDARD`의 기존 prompt snapshot은 의도하지 않게 변하지 않는다.
- conversation block은 `PromptBuilder.build(PromptContext)` 밖에서 조립되지 않는다.

### 15.4 refiner와 최종 권한

- 비표준 자세에서 `sampleCandidatesForRefinement`는 빈 목록을 반환한다.
- underlying `DiverseSamplingOrchestrator` mock 호출 수는 0이다.
- trace는 `decisionAuthority=primary_model`, `mutationAllowed=false`, `candidateCount=0`이다.
- `STANDARD`에서는 기존 citation preflight와 후보 순서가 유지된다.
- 후보 수나 score gap이 conversation stance를 변경하지 않는다.

### 15.5 멀티모달 입력

- raw PNG base64 + null media type -> `base64Data` 한 개 + `image/png`, URI 없음
- JPEG와 WebP allowlist -> 각각 올바른 `base64Data` + MIME, URI 없음
- malformed base64, empty decode, unsupported media type, full data URI 입력 -> 4xx reason code, provider call 0
- 유효 이미지 + `ENFORCE` -> `llmrouter.vision`의 구체 모델 선택, 주 `UserMessage`에 `TextContent` 1개와 `ImageContent` 1개
- 경량 후보·verifier message에는 `ImageContent` 0개
- 비전 모델/route 불가 -> `evidence_needed`, 텍스트 모델 fallback 0
- request-attempt ledger는 원문·base64 없이 content type count와 request hash만 기록

## 16. 최소 GREEN 구현 순서

### Work Unit 0 — 실행 전 분류와 안전 게이트

1. `java -version`으로 Java 17을 확인한다.
2. 가장 가까운 `AGENTS.md`, `build.gradle.kts` sourceSets, 현재 branch와 HEAD를 읽는다.
3. `git worktree list`, `git status --short`, `.git/index.lock`, PatchDrop pending, source-edit lease, overlapping writer를 확인한다.
4. 활성 production·test 대상의 preimage SHA-256을 계산한다.
5. 현재 소스와 동등 계약을 대조해 `verified_no_patch_needed`, `fixture_only_required`, `patch_required`, `HOLD` 중 하나를 선택한다.
6. `patch_required`일 때만 `$demo1-source-edit-three-way-preflight`로 하나의 redacted EvidenceSnapshot을 고정하고 정확히 `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`를 실행한다.
7. NEUTRAL을 A-B와 B-A 순서로 평가했을 때 모두 `APPLY`인 경우에만 source-owner guard, lease, 즉시 preimage 확인 후 `apply_patch`로 이동한다. 판정이 달라지거나 APPLY가 아니면 HOLD한다.

### Work Unit 1 — `ConversationFrameV1`과 resolver

1. frame RED fixture를 추가한다.
2. RED가 미구현 타입 또는 잘못된 기본 동작으로 실패함을 확인한다.
3. 두 새 production 파일만 추가한다.
4. null-safe record, mode parser, 파생 허용 메서드, deterministic resolver를 구현한다.
5. positive, negative, neutral, negation, meta/reporting, precedence fixture를 GREEN으로 만든다.

### Work Unit 2 — `PromptContext`와 prompt boundary

1. builder 기본값, 명시 frame, copy/toBuilder 보존 RED를 추가한다.
2. `PromptContext`에 frame을 추가하되 기존 interaction policy 필드를 변경하지 않는다.
3. `StandardPromptBuilder`에 자세별 block을 추가한다.
4. `OFF`, `SHADOW`, `STANDARD` prompt가 기존 의미를 유지하고 세 비표준 자세만 정확한 block을 렌더링하는지 GREEN으로 만든다.

### Work Unit 3 — workflow 권한과 optional gate

1. short-circuit, refiner, expansion, memory-write mock call-count RED를 추가한다.
2. `ChatWorkflow`에서 frame을 한 번 계산해 전체 요청에 전달한다.
3. 비표준 자세에서 direct fallback을 우회하고 주 모델 prompt 경로로 보낸다.
4. `EnsembleFinalAnswerService` 중앙 gate와 workflow 호출부 gate를 추가한다.
5. sanitizer, cancellation, request budget, evidence guard가 계속 작동함을 확인한다.
6. `STANDARD` 회귀와 비표준 자세의 `auxiliary=0`, `primary=1` 계약을 GREEN으로 만든다.

### Work Unit 4 — 검증된 멀티모달 주 모델 입력

1. DTO/media/base64 validation RED를 추가한다.
2. `imageMediaType`과 fixed reason code 검증을 추가한다.
3. valid image 요청에서 기존 vision alias를 해석하도록 한다.
4. 주 사용자 메시지 생성 로직을 package-private 또는 작은 단일-purpose helper로 추출해 content 개수, `base64Data`, MIME, URI 부재를 직접 테스트할 수 있게 한다.
5. 경량·verifier·memory 경로에 image가 전달되지 않는 negative assertion을 추가한다.
6. provider가 없을 때 silent text fallback 없이 `evidence_needed`가 반환되는지 GREEN으로 만든다.

### Work Unit 5 — bounded telemetry

1. 원문이 trace에 들어가지 않는 RED fixture를 추가한다.
2. `MlaBreadcrumb`에 frame 전용 bounded append 경로를 추가한다.
3. 허용된 키·열거형·boolean·count만 남기는지 GREEN으로 만든다.
4. 기존 interaction-policy breadcrumb의 형식과 count를 회귀시키지 않는다.

각 Work Unit은 그 단위의 focused GREEN과 diff 검토가 끝난 뒤 다음 단위로 이동한다. 별도 권한이 없으면 commit 또는 staging을 만들지 않는다.

## 17. Telemetry 계약

허용 키는 다음으로 제한한다.

```text
conversation.frame.version=v1
conversation.frame.mode=off|shadow|enforce
conversation.frame.stance=standard|repair|supportive_check_in|safety_first
conversation.frame.reasonCode=<allowlisted enum>
conversation.frame.lightweightRole=observe_only|abstain
conversation.frame.multimodalInputPresent=true|false
conversation.frame.refinerSuppressed=true|false
conversation.frame.optionalExpansionSuppressed=true|false
conversation.frame.memoryWriteSuppressed=true|false
conversation.frame.primaryAuthority=primary_model
conversation.frame.wouldSelectVisionRoute=true|false
conversation.frame.visionRouteSelected=true|false
conversation.frame.auxiliaryModelCallCount=<non-negative integer>
conversation.frame.primaryModelCallCount=<non-negative integer>
conversation.frame.wireAttemptCoverage=observed|not_observed
```

금지 항목은 다음과 같다.

- raw user/assistant text
- raw prompt, candidate, final response
- image base64, decoded image, MIME 외 임의 metadata
- session ID, 사용자 ID, 인물명, 연락처
- provider request/response body
- endpoint 전체 URL, credential, token, cookie, header
- 모델 chain-of-thought 또는 hidden score

`wireAttemptCoverage=observed`는 동일 요청의 request-attempt ledger에서 실제 provider attempt가 확인될 때만 기록한다. 모델 metadata 조회, HTTP 500 캡처, route 선택, 응답 문자열만 있으면 `not_observed`다.

## 18. 검증 사다리

### 18.1 focused RED/GREEN

Desktop 전용 cache 격리를 먼저 설정한다.

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$projectCacheDir = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$projectCacheDir | Out-Null
```

focused test 명령은 다음과 같다.

```powershell
.\gradlew.bat test `
  --tests "com.example.lms.guard.ConversationFrameResolverTest" `
  --tests "com.example.lms.prompt.StandardPromptBuilderInteractionEvidencePolicyTest" `
  --tests "com.example.lms.service.ChatWorkflowConversationHarmonyContractTest" `
  --tests "com.example.lms.service.ChatWorkflowMultimodalPrimaryMessageTest" `
  --tests "com.example.lms.ensemble.EnsembleFinalAnswerServiceTest" `
  --tests "com.example.lms.api.PublicRequestBudgetGuardTest" `
  --tests "com.example.lms.telemetry.MlaBreadcrumbTest" `
  --tests "com.example.lms.service.rag.plan.PlanModelResolverTest" `
  --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" `
  --tests "com.example.lms.manifest.LocalModelConfigYamlTest" `
  --no-daemon --project-cache-dir $projectCacheDir
```

### 18.2 구조·버전·컴파일

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava `
  --no-daemon --project-cache-dir $projectCacheDir

.\gradlew.bat :app:classes bootJar `
  --no-daemon --project-cache-dir $projectCacheDir
```

LangChain4j가 전부 `1.0.1`이 아니거나 active sourceSet이 달라지면 HOLD한다. 빌드가 기존 광범위 baseline 오류로 실패하면 첫 오류와 변경 파일 연관성을 분리하고 이 패치의 성공으로 포장하지 않는다.

### 18.3 diff·비밀·무결성

```powershell
git diff --check -- `
  main/java/com/example/lms/guard/ConversationFrameV1.java `
  main/java/com/example/lms/guard/ConversationFrameResolver.java `
  main/java/com/example/lms/prompt/PromptContext.java `
  main/java/com/example/lms/prompt/StandardPromptBuilder.java `
  main/java/com/example/lms/service/ChatWorkflow.java `
  main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java `
  main/java/com/example/lms/dto/ChatRequestDto.java `
  main/java/com/example/lms/api/PublicRequestBudgetGuard.java `
  main/java/com/example/lms/telemetry/MlaBreadcrumb.java
```

secret scan은 대상 diff와 생성 증거에 한정하고 `secretPatternHits=<count>`만 보고한다. 전체 secret 후보 본문을 출력하지 않는다. 마지막으로 postimage SHA-256, 대상별 Git 상태, HEAD, staged count, `.git/index.lock`, lease, PatchDrop 상태를 다시 기록한다.

## 19. 런타임 의미 검증

provider/runtime 권한과 가용성이 실제로 증명된 경우에만 다음 합성 시나리오를 실행한다. 민감한 첨부 원문을 사용하지 않는다.

1. `STANDARD`, text-only
   - 기존 답변 의미가 유지된다.
   - refiner가 활성 조건을 만족하면 후보는 참고로만 붙고 최종 권한은 primary다.
2. `REPAIR`, text-only
   - auxiliary model call 0, optional expansion 0, primary call 1이다.
   - 최종 답변은 중단 신호를 존중하며 원인·관계 평가를 다시 시작하지 않는다.
3. `SUPPORTIVE_CHECK_IN`, text-only
   - auxiliary model call 0, optional expansion 0, primary call 1이다.
   - 최종 답변은 비진단적 반영과 한 번의 도움 형태 확인으로 끝난다.
4. `SAFETY_FIRST`, text-only
   - auxiliary model call 0, memory write 0, primary call 1이다.
   - 최종 답변은 현재 안전을 명확히 확인하고 즉각적 위험 시 지역 지원 연결을 안내한다.
5. `STANDARD`, synthetic image
   - 주 모델은 실제 `ImageContent`가 포함된 한 요청을 받는다.
   - resolved model과 endpoint lane은 vision 경로다.
   - 경량 모델 request에는 image content가 없다.
6. malformed image
   - 4xx reason code로 끝나고 모든 model call count는 0이다.

각 시나리오는 입력별 독립 request identity를 사용한다. HTTP 200이나 화면에 텍스트가 보이는 것만으로 semantic success 또는 multimodal wire success를 주장하지 않는다.

Browser/UI 증거가 요구되는 별도 실행에서는 실제 `/chat-ui`가 image 전송을 지원하는지 먼저 확인한다. 현재 소스에 해당 UI 계약이 없으면 backend API proof까지만 수행하고 `evidence_needed: browser image submission control / verify with live DOM and request payload`를 남긴다. 이 지시서만으로 새 UI 컨트롤을 추가하지 않는다.

## 20. HOLD 조건과 롤백 경계

다음 중 하나가 발생하면 영향받은 lane만 HOLD한다.

- 대상 preimage 변경을 현재 본문과 재대조하지 못함
- `.git/index.lock`, source lease, overlapping writer, branch ownership 충돌
- top-level PatchDrop pending 또는 ambiguous producer bundle
- source-edit three-way preflight의 NEUTRAL이 안정적인 `APPLY`가 아님
- RED가 기존 결함으로 재현되지 않음
- interaction evidence 정책의 사실·증거 의미를 변경해야만 테스트가 통과함
- `PromptBuilder.build(PromptContext)` 밖에서 최종 stance prompt를 만들어야 함
- 이미지 전송에 새 production dependency 또는 새 모델 ID가 필요함
- 비전 route가 unavailable인데 text-only fallback만으로 성공을 주장하려 함
- raw 대화, base64, credential 또는 provider body가 trace/log/UI에 노출됨
- focused 테스트, LangChain4j purity, sourceSet hygiene, compile, bootJar 중 변경과 관련된 실패
- provider/wire proof가 `not_observed`인데 멀티모달 성공을 주장하려 함

HOLD 보고는 다음 필드를 포함한다.

```text
holdScope=<affected lane>
firstBlockingRule=<first rule>
blockingEvidence=<path, hash, first error, or bounded reason code>
independentWorkCompleted=<verified work>
repositoryWideHold=false
evidence_needed=<missing artifact> / verify with <one exact command>
```

이 작업의 blocker가 모든 승인 lane을 불안전하게 만들지 않는 한 `repositoryWideHold=true`를 사용하지 않는다.

검증 실패 시 이번 Work Unit이 만든 정확한 hunk와 새 파일만 되돌린다. `git reset --hard`, `git checkout --`, 저장소 전체 정리, 사용자 변경 삭제, 과거 해시 본문 덮어쓰기를 사용하지 않는다.

## 21. 완료 경로

실행 세션은 정확히 다음 중 하나로 종료한다.

### `verified_no_patch_needed`

- 현재 소스에 동등한 `ConversationFrameV1`, stance gate, actual multimodal message 전달, negative/meta fixture가 이미 있다.
- focused·구조·컴파일·runtime 의미 검증이 모두 통과한다.
- 새 source/test diff를 만들지 않는다.

### `fixture_only_required`

- production 동작은 이미 정확하지만 동등한 회귀 fixture 또는 count-only telemetry assertion만 없다.
- 승인된 테스트 대상의 현재 preimage를 확인하고 characterization fixture만 추가한다.
- production diff가 없고 전체 검증이 통과한다.

### `patched_and_verified`

- 현재 구현에서 RED가 실제로 재현된다.
- 승인된 production·test 대상만 최소 패치한다.
- `OFF`·`SHADOW` 무변화, `ENFORCE` 자세별 호출 수, prompt 경계, memory 억제, multimodal content, negative/meta case, redaction이 모두 GREEN이다.
- sourceSet·LangChain4j·compile·`:app:classes`·`bootJar`, count-only secret, postimage, Git 무결성이 통과한다.
- provider를 실제로 실행했다면 동일 요청의 wire-attempt evidence가 있다. provider를 실행하지 못했다면 local source 완료와 runtime `evidence_needed`를 분리한다.

### `HOLD`

- 첫 차단 규칙과 한 개의 정확한 검증 명령을 보고한다.
- 안전하게 끝낸 독립 작업을 보존한다.
- 다른 lane의 미확인 증거를 source success나 repository-wide failure로 확대하지 않는다.

## 22. 완료 보고 형식

```text
directiveId=demo1.lightweight-multimodal-conversation-harmony.v1
completionPath=verified_no_patch_needed|fixture_only_required|patched_and_verified|HOLD
canonicalRoot=C:\AbandonWare\demo-1\demo-1\src
branch=<current branch>
headBefore=<hash>
headAfter=<hash>
sourceSet=root main/java + main/resources
conversationFrameMode=off|shadow|enforce
changedPaths=<exact paths>
focusedTests=<PASS|FAIL plus count>
sourceSetHygiene=<PASS|FAIL>
langchain4jPurity=<PASS|FAIL; expected 1.0.1>
compileJava=<PASS|FAIL>
appClasses=<PASS|FAIL>
bootJar=<PASS|FAIL>
auxiliaryCallCount=<count per scenario>
primaryCallCount=<count per scenario>
multimodalContentObserved=<true|false|not_observed>
wireAttemptCoverage=<observed|not_observed>
secretPatternHits=<count>
stagedCount=<count>
indexLock=<true|false>
preimageStable=<true|false>
postimageHashes=<path=sha256 list>
evidence_needed=<none or missing artifact / exact verification command>
```

## 23. 이 지시서의 자체 완료 조건

- 사용자 승인안 2의 타입화된 frame, 비대칭 모델 권한, deterministic gate, primary final authority를 보존한다.
- 첨부 사적 대화를 원문 없이 행동 패턴과 hash로만 다룬다.
- 가장 큰 결함을 “공유된 대화 자세와 중단 책임의 부재”로 고정한다.
- 기존 증거 정책에 감정 축을 섞지 않는다.
- 새 경량 모델 호출을 만들지 않고 기존 optional hypothesis seam을 역할 제한한다.
- 이미지가 주 비전 모델에만 전달되는 exact source boundary와 negative contract를 포함한다.
- positive, negative, neutral, negation, meta/reporting, malformed-input fixture를 포함한다.
- 활성 파일, 비대상, RED/GREEN, rollout, telemetry, runtime proof, HOLD, rollback, 완료 보고가 서로 모순되지 않는다.
- Java 소스, 테스트, 리소스, DB, credentials를 이 문서 작성 세션에서 수정하지 않는다.
- 별도 권한 없이 commit, staging, push, deploy를 요구하거나 수행하지 않는다.

이 문서를 사용자가 검토한 뒤 실제 source implementation 또는 별도 implementation plan을 요청할 때만 다음 단계로 이동한다.
