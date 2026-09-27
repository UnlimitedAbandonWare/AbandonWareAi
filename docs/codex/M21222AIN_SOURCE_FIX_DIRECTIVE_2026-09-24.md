# Codex 소스 수정 지시서 — 3060 보조 AI 유지 + 단계별 적응형 우회 + Graph RAG 답변 완주

작성·공식 문서 확인일: **2026-09-24 KST**

> 이 문서는 단순 조사 요청이 아니라, 아래 동작 계약을 현재 소스에 구현하고 검증하라는 수정 지시다. 다만 이 문서를 작성한 분석 세션에서는 운영 소스를 수정하거나 Windows/GPU/브라우저 테스트를 실행하지 않았다. 기존에 해결한 부분은 보존하고 남은 경계만 수정한다.

**Goal:** 3060의 디스플레이·보조 AI 역할과 3090의 주력 생성 역할을 유지하면서, 혼잡한 단계에서 제한된 우회·축소 실행을 하고 실제 답변·근거·종료 사유를 브라우저까지 일관되게 전달한다.

**Architecture:** 기존 GPU 진단, 모델 gateway/health tracker, 취소 컨텍스트, retrieval handler, evidence attribution, 최종 공개 경계를 연결한다. 새로운 거대 스케줄러·라우터·검증 체계를 병렬로 만들지 않는다. 실행 결과와 근거 승인 결과는 별도 계약으로 전달한다.

**Tech stack:** ZIP에 들어 있는 Java/Spring/정적 HTML·JavaScript/Ollama 구현을 기준으로 한다. 두 번째 첨부는 Java 17·LangChain4j 1.0.1을 언급하지만 이번 ZIP에는 build 파일이 없으므로, 실제 C-root의 dependency resolution으로 버전을 확인한다. 임의 업그레이드는 하지 않는다.

**Spec:** 사용자가 이번에 붙인 `{스터프2}`의 운영 의도와 본 문서의 동작 표. 과거 첨부와 충돌하는 정책은 본 문서로 정정한다. 현재 실행 파일·테스트·로그는 사실 판단의 기준이며 과거 에이전트의 완료 보고는 검증할 주장이다.

**Execution:** systematic-debugging → 실패 회귀 테스트 → 원인 단위 최소 패치 → 독립 검토 → verification-before-completion. 실제 구현은 Codex의 현재 C-root 세션에서 수행한다. 스킬 이름을 나열하거나 도구를 전부 실행하는 것으로 작업을 대신하지 않는다.

---

## 0. 이번 요청으로 결정된 정책 — 같은 선택 질문을 다시 하지 말 것

### 0.1 사용자가 원하는 기본 동작

1. **3060에서 AI를 제거하지 않는다.** 질문 임베딩, 가벼운 재작성·보조 모델·재랭킹 등 실제 연결된 보조 작업에 계속 사용한다. 다만 감당하기 어려운 새 작업을 무한히 쌓지 않는다.
2. **3090은 무거운 주력 답변 경로로 유지한다.** 주력 모델이 상주해 있으면 보조 모델을 넣겠다고 무작정 내리지 않는다. 반대로 사용자가 3060에 배치된 경량 모델을 명시적으로 선택했다면 그 실행 자체를 오류로 취급하지 않는다.
3. **모델 선택은 기존 preferred/strict/auto 계약을 따른다.** preferred는 선택 모델을 먼저 시도하고 허용된 우회가 가능하다. strict는 최종 답변 모델을 몰래 바꾸지 않는다. auto는 현재 capability·권한·예산을 통과한 후보에서 선택한다. 3090 기본 역할이 사용자 선택을 덮어쓰는 구실이 되어서는 안 된다.
4. **부하 판단은 VRAM 용량 하나로 끝내지 않는다.** 실제 장치별 실행 수, 대기열, 대기 시간, 최근 처리 지연, 신선한 부하 관측을 사용한다. 높은 사용률로 정상 생성 중인 요청을 자동 중단하지 않는다.
5. **폴백은 막힌 단계에 붙인다.** 임베딩 실패는 호환 임베딩 또는 접근 가능한 lexical 검색, 선택적 재랭킹 실패는 후보 보존, 최종 생성 실패는 승인된 생성 API 경로로 처리한다. 마지막 모델에만 fallback을 추가하고 앞단 실패를 방치하지 않는다.
6. **일반 지식 답변과 근거 필수 답변을 구분한다.** 일반 개념 질문은 허용 조건에서 근거 없는 일반 답변을 명시적으로 제공할 수 있다. 지정 문서·첨부·출처가 필수인 질문과 필수 검증 실패에는 근거를 지어내지 않는다.
7. **Graph RAG를 재현 가능한 상태로 먼저 만든다.** 이번 변경에 안경 카메라·음성·디스플레이 확장, 관리자 계정 변경, 별도 클라우드 DB 도입을 섞지 않는다.

### 0.2 이번 요청이 허용하지 않는 것

GPU 전체를 3090으로 강제 고정, 모든 3060 보조 기능 비활성화, 모든 HOLD 해제, strict 우회, 외부 전송·유료 호출 무제한 허용, 새 API 가입/로그인/결제, 모델 자동 다운로드, 전역 CUDA 환경변수 변경, 전체 Ollama kill/unload, DB/인덱스 삭제, Git commit/push/merge는 이 요청의 승인이 아니다.

승인된 API 설정·비용 한도·전송 범위가 없으면 **해당 외부 경로만 실행하지 않고 사유를 표시**한다. 그것을 이유로 로컬 버그 수정·mock 검증·독립 작업까지 멈추지 않는다. 유효한 타 세션 파일 예약은 존중하되, 예약 없는 다른 단위를 진행하고 충돌 파일만 별도로 보고한다. 다른 계층에 우회 코드를 끼워 예약을 피해 가지 않는다.

---

## 1. 입력 자료·현재 소스·이미 반영된 수정

### 1.1 스냅샷 경계

- 입력 ZIP: `m21222ain.zip`
- SHA-256: `dbd2a4fa7b65ce6e38d7cb4ec25751167d2c4c06c570b310e613f29d2a129c3d`
- ZIP 엔트리 3,014개, 파일 2,371개, 최상위는 `main/`이다.
- 이번 ZIP에는 Gradle wrapper/build 설정/테스트 트리/.git이 없다. 여기 적은 기존 테스트 이름 중 일부는 이전 보고서에 나온 이름이며, 현재 작업 공간에서 존재 여부부터 확인한다.
- 이번에 실제 첨부된 자료는 ZIP과 마크다운이다. 영상 관련 설명·실기 성능·155/155 등의 수치는 사용자가 붙인 이전 보고서의 주장이지 이번 분석의 신규 실측이 아니다.
- 근거 파일 `m21222ain_source_evidence_2026-09-24.md`의 **S01–S29**, 해시 파일 `m21222ain_source_manifest_2026-09-24.json`을 함께 읽는다. 줄 번호는 ZIP 전용이며 현재 소스에서는 메서드·필드 앵커로 다시 찾는다.

작업 루트는 사용자가 지정한 `C:\AbandonWare\demo-1\demo-1\src`를 먼저 확인한다. `main/java`의 실제 경로와 Gradle root를 별도로 확인하고, `src/src`를 추측해 만들지 않는다. 현재 목표 파일을 읽되 과거 attachment UUID 경로를 새 세션에 그대로 하드코딩하지 않는다.

### 1.2 이미 고쳐진 부분 — 유지 및 회귀 확인

| 현재 확인 사항 | ZIP 앵커 | 처리 |
|---|---|---|
| chat warmup 모델과 embedding warmup 모델 분리 | `main/resources/application-local-llm.yml:22–28` | 과거 잘못된 YAML 패치를 다시 적용하지 않는다. |
| embedding warmup이 embedding.base-url을 사용 | `config/LocalLlmProcessManager.java:914–920` | 별도 임베딩 장애가 주력 chat 준비 상태를 불필요하게 막지 않는 성질을 보존한다. |
| configuredModelId 메서드 복구 | `llm/DynamicChatModelFactory.java:471` | wrapper의 모델 식별자 보존을 회귀 검증한다. |
| 최종 release와 knowledgeWriteAllowed 분리 | `service/ChatWorkflow.java`의 `FinalVerificationReleaseDecision` | 공개 가능한 축소 답변이라고 장기 지식 저장까지 허용하지 않는다. |
| 동일 embedding-space 검사 | `service/embedding/OllamaEmbeddingModel.java:636–652` | OpenAI 등 다른 embedding을 무작정 연결하기 위해 이 guard를 제거하지 않는다. |
| preferred/strict/auto 설정 UI 존재 | `main/resources/templates/chat-ui.html:272–277` | 똑같은 선택기를 새로 만들지 않는다. |

위 Java 경로 중 축약 경로의 기준은 `main/java/com/example/lms/`이다. S01–S29에는 전체 경로가 들어 있다.

### 1.3 현재 코드에서 확인한 실제 수정 타점

**A. 공개 정책이 RAG 요청의 실제 결과를 충분히 구별하지 못한다.**

`ChatWorkflow.deriveEvidenceReleaseState():7412–7463`는 RAG가 요청됐는데 `vectorCitableLocatorCount <= 0`이면 먼저 `METADATA_INCOMPLETE`를 반환한다. `applyEvidenceReleasePolicy():7516–7549`는 이 상태에서 `evidenceReleaseRequired=false`여도 HOLD한다. 따라서 일반 질문이 보류됐다는 보고와 대응되는 정적 경로가 남아 있다. 그렇다고 모든 `Evidence 0`이 언제나 HOLD라고 일반화하지 않는다.

**B. 단순히 CONFIRMED_EMPTY 분기를 위로 옮기는 것도 잘못이다.**

`RagEvidenceAttributionService.PromotionResult`는 `CONFIRMED_EMPTY`의 reason으로 `NO_CITABLE_LOCATOR` 외에 `EVIDENCE_GATE_BLOCKED`, `CITATION_GATE_BLOCKED`도 허용한다. 이는 검색 성공 0건만을 뜻하지 않는다. 검색 단계의 실행 결과, attribution 결과, verifier 결과를 분리하지 않고 if 순서만 바꾸면 검증에서 탈락한 근거를 일반적인 빈 검색처럼 처리하게 된다. [S06–S08]

**C. evidenceReleaseRequired의 이름에 속지 않는다.**

현재 `ChatWorkflow:1352–1353`은 `EvidenceNeededDirectivePolicy.requiresEvidenceNeeded(userQuery)`로 값을 만든다. 해당 클래스는 “근거가 없으면 evidence_needed로 출력하라” 같은 **명시적 출력 지시 파서**다. 이것을 문서 질의·필수 출처·권한·검증 필요성의 전체 판정기로 오해하지 않는다. `false`라는 이유만으로 아무 질문이나 일반 지식 모드로 보내면 안 된다. [S04–S05]

**D. 정상적인 짧은 답을 선택적 후처리가 바꿀 수 있다.**

`ChatWorkflow:4013–4018`은 conversation frame이 expansion을 허용하고 길이가 짧으면 `expandWithLc(out, vp, model)`을 호출한다. 이 호출부에는 explicit polish/정확한 출력 형식에 대한 직접 조건이 없다. `AnswerExpanderService:272–294`는 새로 추가된 숫자를 검사하지만 원래 숫자가 사라지는 것은 검출하지 않는다. `draft="7"`, `result="편집할 원문이 부족합니다."`는 결과가 빈 문자열이 아니고 더 길며 새 숫자도 없어서 해당 검사들을 통과할 수 있다. **이는 확장기가 호출된다는 조건 아래의 정적 반례이지 이번 세션의 실기 재현 보고가 아니다.** [S09–S10]

**E. 장치 관측은 있지만 요청별·장치별 admission을 완성한 상태는 아니다.**

`GpuHardwareDiagnostics`에는 측정 캐시가 있지만 admission은 전체 GPU의 `maxMemoryUsedRatio`에 의존한다. `RerankGate`는 이를 일부 사용한다. `SemaphoreGateAspect`는 ONNX reranker pointcut에 한정된다. 이 세 가지를 두고 모든 embedding·fast 모델·재랭킹이 실제 같은 3060 용량 제한을 공유한다고 주장하지 않는다. [S11–S13]

**F. gateway는 없는 기능이 아니다.**

`application-llm.yaml:22–46`은 gateway/local-device/cloud 경로를 기본 활성화하는 값을 갖고 있다. Java properties 기본값만 보고 “모두 observe/off라 안 됐다”고 결론내리지 않는다. 실제 활성 profile·override·키 유무·허용 범위와 라우트 선택을 본다. fast timeout 5초는 `:51–56`의 보조 경로 값이며 주 모델의 시간 제한이라고 일반화하지 않는다. [S16–S17]

**G. lexical fallback은 이름이 있다고 완성된 것이 아니다.**

`LocalBm25Retriever`는 작은 in-memory BM25-like scorer다. `com.example.lms` 아래 이름 참조는 Conversate 경로에서 확인되며, 이것만으로 브라우저 `/api/chat`의 문서 저장소·ACL·locator까지 연결됐다고 할 수 없다. `service/RagRetrievalService.java`는 패키지 선언 한 줄짜리이므로 그 파일 이름만 보고 본체를 수정하지 않는다. 실제 `HybridRetriever`, handler chain, backing service의 Bean을 추적한다. [S27–S29]

---

## 2. 설정창과 서버 계약 — 저장만 되는 가짜 옵션을 만들지 말 것

다음 중 `modelSelectionMode`, `strictModelSelection`, `useRag`, `searchMode`, `polish`는 현재 구현에 있다. 나머지 필드 이름은 **이번 구현을 위한 제안 이름**이며, 기존에 같은 의미의 authoritative 필드가 발견되면 그 하나를 확장한다. 이름을 바꾸더라도 아래 의미는 보존한다.

### 2.1 UI의 최소 옵션

| 화면 항목 | 값/기본 정책 | 실제 의미 |
|---|---|---|
| 모델 사용 방식 | 기존 preferred / strict / auto 그대로 | 신규 사용자 기본 preferred를 유지한다. 저장된 strict를 몰래 바꾸지 않는다. |
| 로컬 자원 자동 조절 | 제안 `localResourcePolicy=adaptive/observe`, 목표 기본 adaptive | adaptive는 새 작업 admission·혼잡 우회를 적용한다. observe는 새 혼잡 판단만 관측하며 기존 OOM·보안·권한 보호까지 끄지 않는다. |
| RAG 답변 방식 | 제안 `ragAnswerPolicy=adaptive/evidence_only`, 목표 기본 adaptive | adaptive는 일반 질문의 안전한 일반 답변을 허용한다. evidence_only는 검색이 필요한 해당 질의를 근거 없는 사실 답변으로 대체하지 않는다. 인사까지 출처를 요구하는 모드는 아니다. |
| 허용된 API로 우회 | 제안 nullable `allowCloudFallback` | false는 명시 금지. null은 이미 승인된 서버 정책 상속. true도 서버 허용·키·예산·전송 범위를 넘지 못한다. 설정되지 않은 권한을 새로 만드는 토글이 아니다. |
| 그래프 검색 | 제안 `graphRagMode=auto/off/required` | auto는 RAG가 켜진 요청 중 관계 검색이 유익하고 가능한 경우 사용. off는 그래프 실행 안 함. required는 그래프가 실패하면 사용하지 않고 성공한 척하지 않는다. |
| 답변 다듬기 | 기존 `polish` 사용 | false이면 선택적 expansion을 하지 않는다. true여도 “숫자만”, JSON 등 더 강한 출력 계약을 깨지 않는다. |

RAG OFF는 벡터/문서/KG 실행을 억제하며 웹 OFF는 웹을 다시 켜지 못하게 한다. `RAG OFF + graph required` 같은 충돌은 화면에서 선택을 막고 API에서는 명확한 설정 오류로 종료한다. 단순히 required를 auto로 바꿔 성공 처리하지 않는다. 웹 OFF는 “외부 생성 API도 반드시 OFF”와 동일한 뜻은 아니므로 별도 API 허용 정책을 함께 표시한다.

기존 UI는 모델 선택 모드를 `strictModelSelection`과 `model=llmrouter.auto` 등으로 직렬화한다(`chat.js:6426–6451`). 백엔드에 이미 `modelSelectionMode` 문자열이 있다고 가정하지 않는다. 기존 wire contract를 유지하거나 명시적으로 호환 migration을 수행한다.

### 2.2 설정 전달·우선순위

기존 `ChatRequestDto` → `ChatRequestSettingsMerger` → 실행 계획 → 각 stage → release boundary까지 추적한다. `useAdaptive`라는 기존 boolean을 의미 확인 없이 GPU 조절 플래그로 재활용하지 않는다.

서버 권한/전송 제한/필수 검증 → 요청의 explicit 선택 → 승인된 사용자 기본값 → 서버 기본값의 순서로 충돌을 해소한다. 요청 시작 시 `requested / effective / source / disabledReason / policyVersion`을 불변 스냅샷으로 만든다. 도중에 다른 탭에서 설정을 바꿔 이미 실행 중인 요청의 정책이 바뀌면 안 된다.

외부 경로 허용은 다음 조건을 모두 만족해야 한다.

```text
serverRouteEnabled
AND configuredCredentialPresent
AND routeAllowlisted
AND dataScopeAllowsThisPayload
AND authorizedCostBudgetCanBeReserved
AND requestAllowsCloudFallback
AND finalModelSelectionAllowsThisChange
```

이 식은 구현 계약이지 현재 존재하는 메서드 이름이 아니다. existing gateway/usage ledger의 실제 타입에 연결한다. 키 유무만으로 private 문서 전송을 허용하지 않는다. YAML의 cloud=true 하나만으로 미설정 비용/권한을 무한 허용하지 않는다. 사용자가 요청 JSON으로 `effectivePolicy`·`serverMandatedEvidence` 같은 서버 권한 필드를 주입하지 못하도록 별도 서버 계산 타입 또는 ignore/reject 처리한다.

`SettingsController`는 공개 GET allowlist가 비어 있고 POST는 범용 map 저장이다. 새 정책을 붙이려고 전체 설정·키를 공개하거나 인증을 느슨하게 하지 않는다. 요청별 옵션은 요청 DTO에서 처리하고, 운영자 전용 용량/한도 변경은 기존 보호된 경계에 typed validation을 추가한다. 저장된 DB 값이 `@Value` Bean에 자동 반영된다고 가정하지 말고 런타임 적용 시점을 테스트한다.

### 2.3 캐시와 재시도

`ChatService.cacheKey()`에는 여러 실행 선택이 있지만 `strictModelSelection`은 열거돼 있지 않다. 현재 캐시는 stateless·ephemeral·retrieval off 등으로 적용 범위를 제한하므로 전체 채팅이 잘못 캐시된다고 단정하지 말고 **캐시가 실제 적용되는 요청 fixture**로 확인한다.

새 정책의 effective fingerprint/policyVersion, strict 의미, 권한 범위가 캐시 재사용을 바꾸면 key 또는 캐시 eligibility에 반영한다. 무근거 degraded 답을 evidence_only 요청에, cloud fallback 답을 strict 요청에 재사용하지 않는다. held/failed/cancelled 결과는 정상 답변 cache를 오염시키지 않는다. 설정을 바꾼 “명시적 재시도”는 새 요청 스냅샷으로 시작한다.

---

## 3. P0 — 실제 라우트·소스·실행 기준선 확정

수정 전 다음을 하나의 정제된 baseline에 기록한다. 이 단계만 반복하고 구현을 미루지 않는다.

- canonical source root, Gradle root, 활성 profile, 소스 식별 해시, 현재 서버 PID/build identity, 작업 파일 예약.
- 각 역할의 **요청 모델 → effective endpoint → endpoint 소유 PID → runner → GPU UUID**와 관측 시각. 비밀 없는 역할별 alias와 내부 진단 식별자를 사용한다.
- main generation, fast helper, embedding, reranker, KG 추출/인덱싱의 실제 backend. 역할 주석과 실제 호출 대상이 다르면 둘을 분리 기록한다.
- 세대가 다른 포트/프로세스, 동일 endpoint의 `/v1`·`/api/chat`·`/api/embed` alias, 동일 GPU에 붙은 서로 다른 endpoint를 구분한다.

GPU UUID 미확정은 `placement_unverified`이지 GPU 없음이나 장애 확정이 아니다. shared endpoint가 허용된 현재 경로를 무조건 중지하지 않는다. 독립 GPU 역할을 보장하려면 이미 있는 managed process/lease/UUID 기능으로 실제 배치를 검증해야 한다. 확인되지 않은 GPU 번호 0/1이나 11434/11435를 일괄 치환하지 않는다.

주력 direct 호출은 **앱이 실제 사용하는 주소·모델·transport**를 기준으로 한다. 기본 `ollama ps` 하나만 보고 다른 포트의 상태를 추정하지 않는다. 큰 모델의 cold/warm을 구분하고 모델/runner/완료 응답을 연결한다. 이전의 2토큰 산술 응답 속도를 일반 RAG 성능으로 사용하지 않는다.

공식 근거: 작업 관리자 엔진 이름만으로 부하 소유자를 확정할 수 없고[W1], `ollama ps`의 GPU 표시는 적재 위치를 뜻한다[W2]. WDDM에서는 프로세스별 메모리가 N/A일 수 있다[W3]. 이 사실들은 원인 단정을 피하기 위한 근거이지 관측만 하고 패치를 하지 말라는 뜻이 아니다.

**P0 완료:** 현재 작업 대상과 실제 요청 경로를 식별하고, 아래 실패 fixture를 실행 가능한 테스트 위치에 배치한다. 실기 접근이 제한된 항목은 미검증으로 남기고 독립적인 정적 결함과 테스트는 계속 진행한다.

---

## 4. P1 — 검색 실행 결과·근거 승인·답변 공개를 분리

### 4.1 구현 위치

기존 `service/ChatWorkflow.java`, `service/rag/RagEvidenceAttributionService.java`, 실제 retrieval handler chain 및 final presentation boundary를 사용한다. 새 공개 정책을 frontend에 만들지 않는다. 기존 `FinalVerificationReleaseDecision.knowledgeWriteAllowed`와 `base.releaseAllowed()` 우선 보호는 유지한다.

필요한 최소 계약은 다음과 같다. 아래 enum/필드명은 제안이며 이미 같은 의미의 타입이 있으면 확장한다.

```text
Retrieval execution:
  NOT_REQUESTED | SUCCESS_WITH_HITS | SUCCESS_EMPTY |
  FAILED | SKIPPED_RESOURCE | CANCELLED

Evidence attribution:
  NOT_APPLICABLE | ACCEPTED | NO_CITABLE_LOCATOR |
  REJECTED_BY_GATE | FAILED | UNAVAILABLE

Separate fields:
  requestedScope, executedScope, sourceKinds, candidateCount,
  acceptedEvidenceCount, coverage, failureClass, reasonCode,
  retryable, fallbackFrom, fallbackTo, deadlineRemainingMs
```

검색 예외를 빈 List로 바꾸더라도 위 실행 결과를 잃지 않아야 한다. KG/embedding/web 각각 실패를 실행 context에 남기고, 합산 결과의 coverage를 보존한다. `SUCCESS_EMPTY`는 실제 실행을 성공적으로 끝낸 해당 범위의 0건일 때만 생성한다. 데이터베이스 미연결·차단·timeout·취소·검색하지 않음은 여기에 들어가지 않는다.

`PromotionResult.CONFIRMED_EMPTY`는 위 execution.SUCCESS_EMPTY와 동일하지 않다. positive candidate인데 인용 gate에서 탈락한 결과를 no hits로 낮추지 않는다. 현재 웹/벡터 개별 locator 개수를 모든 RAG 성공의 필수조건으로 쓰는 곳을 요청의 실제 source scope와 대조한다.

### 4.2 공개 정책 결정표

| 상황 | 최종 동작 | 지식 저장 |
|---|---|---|
| 인사/정확한 산술 출력, 검색 불필요, 독립적인 차단 없음 | 본문 전달. 선택적 후처리 생략 가능 | 기존 사용자 memory 정책을 따름 |
| 실제 문서 근거 있음, 출처·권한·검증 통과 | 근거를 붙여 답변 | 기존 검증·동의 조건 충족 때만 |
| 일반 개념 + adaptive + 실제 검색 성공 0건 | clean general-knowledge 경로 허용, 무근거 상태는 별도 metadata/UI로 표시 | 이번 축소 fallback의 지식 승격 금지 |
| 일반 개념 + adaptive + 검색 실행 실패/혼잡/attribution 불완전 | 아래 clean fallback 조건을 모두 만족할 때만 일반 지식 경로로 전환. 원래 실패 사유 보존 | 금지 |
| 문서·첨부·출처 필수 + 실제 검색 성공 0건 | 해당 범위에서 자료 부족 안내, 사실 답변 조작 금지 | 금지 |
| 문서·첨부·출처 필수 + 검색 실패 | 검색을 완료하지 못했다는 실행 오류 또는 허용된 대체 검색. 자료가 없다고 단정 금지 | 금지 |
| verifier REJECT/필수 검증 실패/권한 차단/취소 | 기존 차단 의미 보존. adaptive만으로 해제 금지 | 금지 |
| 일부 검색 경로 실패, 다른 경로에서 충분한 승인 근거 확보 | 요구된 범위·coverage를 충족한 부분만 답하고 축소 실행 표시 | 자동 완전 검증으로 승격 금지 |

**Clean fallback 조건:** 서버가 문서 한정/최신 검증/필수 출처 질문이 아니라고 판정했고, 독립적인 safety/auth/verifier 차단이 없고, 일반 지식 답변이 허용되며, 남은 예산이 있고, 허용된 모델 경로가 있어야 한다. `evidenceReleaseRequired=false` 하나는 충분조건이 아니다.

근거 오염 가능성이 있는 기존 draft를 그대로 unhold하지 않는다. 검증되지 않은 retrieved context·미확정 출처·실패 도구 결과를 제거하고 일반 지식용 입력으로 제한된 새 generation을 수행한다. 이미 근거와 독립적으로 생성됐음을 증명할 수 있는 clean draft만 재사용한다. 기존 문서 의존 질문을 “일반 질문”으로 재해석해서 차단을 우회하지 않는다. 새 generation에 남은 예산이 없으면 분류된 종료 사유를 반환한다.

재랭킹을 생략했다는 이유만으로 approved evidence를 지우거나, 반대로 출처 미상 내용에 VECTOR 라벨을 붙여 승인하지 않는다. sourceKind(문서/웹/그래프 근거)와 retrievalMethod(vector/lexical/graph)를 구별한다. KG edge는 원본 doc/chunk locator·권한 범위와 연결된 경우만 근거로 쓴다. 식별자 없는 edge나 모델이 추측한 relation을 출처로 올리지 않는다.

### 4.3 먼저 실패를 보여 줄 최소 테스트

실제 테스트 package는 `com.example.lms.service`로 두어 현행 package-private 경계를 사용할 수 있다. 아래는 **현행 분류 경로를 드러내는 characterization 테스트 예시**이며, 통과 자체가 정책 수정 완료라는 뜻은 아니다.

```java
@Test
void recordsCurrentZeroLocatorClassification() {
    var promotion = new RagEvidenceAttributionService.PromotionResult(
        RagEvidenceAttributionService.PromotionStatus.CONFIRMED_EMPTY,
        RagEvidenceAttributionService.PromotionReason.NO_CITABLE_LOCATOR,
        java.util.List.of(), 0, 0, 0, 0, 0, 0);
    var contract = new ChatWorkflow.RetrievalReleaseContract(
        true, false, true, false, true, false);
    var actual = ChatWorkflow.deriveEvidenceReleaseState(
        promotion, java.util.List.of(), false, contract);
    // 현행 동작을 확인하는 관측용 assertion. 완성 정책의 기대값이 아니다.
    org.junit.jupiter.api.Assertions.assertEquals(
        ChatWorkflow.EvidenceReleaseState.METADATA_INCOMPLETE, actual);
}
```

이후 **실제 실행 SUCCESS_EMPTY임을 가진 fixture**와 **FAILED인데 비어 있는 fixture**를 새 계약에 각각 전달하는 RED 테스트를 작성한다. 두 결과가 서로 다른 reason으로 분리되고 결정표대로 공개되는 것을 GREEN으로 만든다. zero locator만 보고 실행 성공을 추정하는 패치로 위 테스트의 expected만 바꾸지 않는다.

검증 필수 반례는 `candidate > 0 + EVIDENCE_GATE_BLOCKED`, `CITATION_GATE_BLOCKED`, `lateUnattributedEvidenceAdded`, `verifier REJECT`, `일반 설명을 요청한 evidence_needed 문자열 인용문`이다. 명시적 지시 파서의 부정·인용문 처리를 새 GPU 정책 때문에 훼손하지 않는다.

---

## 5. P2 — 정상 단답 보호와 선택적 보조 LLM 호출 축소

### 5.1 변경 위치와 우선순위

`ChatWorkflow`의 `allowsConversationExpansion` 및 expansion 호출부, 기존 conversation frame/출력 계약, `AnswerExpanderService`, `ChatRequestDto.polish` 연결을 수정한다.

- `polish=false`는 선택적 expansion 0회로 이어진다. 단순 metadata 기록이나 deterministic formatting은 별도이며 본문 의미를 바꾸면 안 된다.
- 숫자 하나/한 단어/예·아니오/JSON/코드/사용자가 지정한 정확한 출력 형식에는 길이 부족을 이유로 LLM을 더 호출하지 않는다. greeting도 불필요한 “초안 재구성” 경로에 보내지 않는다.
- `polish=true`여도 exact-output 계약이 우선한다. 사용자가 실제로 설명을 확장해 달라고 한 경우와 숫자만 답하라고 한 경우는 다르게 처리한다.
- optional expansion이 혼잡/예산 부족/timeout/무효 응답이면 원문 유지. 취소이면 원문을 뒤늦게 보내지 말고 요청 자체를 종료한다.
- 확장 과정에서 답의 핵심 수치·단위가 없어지거나 편집 거절 안내로 바뀌면 결과를 채택하지 않는다. 숫자 검사 하나로 완전한 의미 동등성을 증명했다고 주장하지 않는다. 가장 중요한 방어는 **불필요한 확장 자체를 호출하지 않는 것**이다.

기존 generation 실패가 아닌 후처리 품질 실패를 GPU 오류나 provider health 악화로 기록하지 않는다. 원문→확장→final body의 내용 해시와 전환 사유를 남기되 원문을 공개 trace에 저장하지 않는다.

### 5.2 RED/GREEN 사례

| 입력·mock | 기대 |
|---|---|
| `3 + 4의 답을 숫자 하나로만`, 주 모델=`7`, polish=false | 본문 정확히 `7`, expansion 호출 0, HOLD 없음 |
| 같은 요청, polish=true | exact-output 때문에 expansion 0, 본문 `7` |
| `안녕?`, 주 모델=`안녕하세요!` | 정상 인사. 편집 안내·근거 부족으로 교체하지 않음 |
| 확장기를 직접 시험: draft=`7`, 후보=`편집할 원문이 부족합니다.` | 후보 거부, caller는 draft 보존 |
| JSON 계약, 주 모델=`{"answer":7}` | 재편집·코드펜스·설명 자동 추가 없음 |
| 설명 확장 요청 + 충분한 승인 근거 + 예산 | 정상 확장은 유지, 단답 보호를 전체 expansion 영구 OFF로 구현하지 않음 |
| 확장 중 Stop | fallback/메모리 저장/늦은 본문 방출 모두 없음 |

본문 밖의 상태 badge로 “일반 지식 답변”을 표시할 수 있지만, 정확한 출력 계약의 본문에 자동 주석을 붙여 `7`을 다시 훼손하지 않는다.

---

## 6. P3 — 3060을 살려 두는 장치별 admission과 제한된 대기열

### 6.1 재사용할 구현

`health/GpuHardwareDiagnostics`, `llm/ModelRuntimeHealthTracker`, 기존 gateway endpoint lease, `service/rag/rerank/RerankGate`, `resilience/SemaphoreGateAspect`, request time budget과 `ChatRunExecutionContext`를 연결한다.

현재 health tracker의 장애 격리·복구는 혼잡 admission과 같은 개념이 아니다. circuit breaker를 하나 더 만들지 않는다. 기존 코드에 같은 기능이 없다면 **작은 역할 하나짜리 장치 admission 컴포넌트**를 추가할 수 있지만, GPU 관측·모델 라우팅·비용 장부를 다시 구현하지 않는다.

### 6.2 admission key와 수명

물리 GPU UUID 기준으로 보조 작업 permit을 공유한다. 동일 GPU의 여러 모델·여러 포트·embedding/reranker/fast helper가 서로 별도의 무제한 semaphore로 빠져나가면 안 된다. 반대로 3060의 메모리가 높은 것만으로 건강한 3090의 주력 요청까지 일괄 차단하지 않는다.

멀티 GPU runner가 실제 여러 장치를 사용하면 그 집합에 대한 용량을 고려한다. `/v1`/`/api/embed` alias는 동일 endpoint identity로 정규화하되, 다른 프로세스/호스트를 URL 문자열 일부만 같다는 이유로 합치지 않는다.

장치 매핑이 미확정이면 conservatively bounded endpoint/unknown pool을 사용하고 `placement_unverified`를 기록한다. 이를 전용 분리 보장으로 표시하지 않는다. 새 장치에 학습/모델 로딩을 무리하게 늘리지 말고 확인 가능한 경로·축소 검색으로 처리한다.

permit은 GPU 호출의 실제 실행 수명에 맞춰 잡고 해제한다. 호출 실패·예외·timeout·취소·stream 종료의 모든 경로를 검사한다. 이미 종료된 permit은 중복 해제하지 않는다. 취소 요청을 보냈지만 upstream 종료가 미확인인 시도는 종료 완료와 별도로 관리하여 동일 자원을 즉시 무제한 재사용하지 않는다.

### 6.3 판단 입력과 초기 실험값

다음 숫자는 **프로젝트에 적용할 보수적인 초기 실험 제안값**이다. 공식 제조사 권장값도 현재 구현값도 아니며, 운영자의 기존 명시 설정을 몰래 덮어쓰지 않는다.

| 항목 | 초기 실험 제안 | 설명 |
|---|---|---|
| 3060 보조 GPU 호출 동시 실행 | 1 | embedding/rerank/fast 호출에 공유. 사용자가 3060 경량 모델로 대화하면 해당 interactive 호출도 용량에 포함 |
| 새 interactive 보조 작업 대기열 | 최대 2개 | semaphore 앞에 무제한 executor queue를 두지 않음 |
| 보조 대기열 체류 한도 | `min(2,000ms, 남은 요청 예산에서 필수 단계 예약을 뺀 값)` | 모델 실행 timeout과 다른 값. 만료 시 대체 검색/선택적 단계 생략 |
| 혼잡 관측 표본 | 서로 다른 신선한 3개 표본 | 동일한 캐시 결과를 3번 읽고 지속 부하라고 계산하지 않음 |
| utilization 높은/회복 구간 | 85% 이상 / 65% 이하 | 보조 신호. 높은 사용률 단독으로 실행 중인 생성 취소 금지 |
| 회복 확인 | 낮은 부하·대기열 해소가 연속 3표본 | 오락가락하는 라우트 전환 방지 |
| background 인덱싱·재학습 | foreground 대기 중 새 batch 투입 보류 | 이미 실행 중인 작업은 안전한 batch 경계에서 양보, 데이터 중간 삭제 없음 |

메모리 평가는 기존 모델 상주분과 **새 요청의 추가 작업 공간/KV/새 모델 로딩 비용**을 구분한다. 전체 VRAM이 높다고 상주 모델의 모든 후속 요청을 영구 금지하면 안 된다. 3090으로 보조 작업을 우회할 때에는 주력 요청의 용량과 모델 상주를 우선 보호한다. 보조를 넣기 위해 주력 모델을 강제 unload하는 것은 기본 경로가 아니다.

`GpuHardwareDiagnostics`의 캐시를 재사용하며 매 stage마다 외부 `nvidia-smi` 프로세스를 실행하지 않는다. N/A/오래된 표본은 unknown이다. 0%/0MB/장치 없음으로 치환하지 않는다. 관측이 없으면 queue·inflight·latency 기반의 보수적 동작으로 남고, 명시적인 장치 미존재와 구별한다.

### 6.4 상태와 분류

운영 상태는 예를 들어 AVAILABLE → PRESSURED → DRAINING → RECOVERING으로 표현할 수 있다. 명칭보다 다음 불변식이 중요하다.

- PRESSURED는 보통 **새 작업 제한**이다. 이미 잘 생성하는 요청을 죽이지 않는다.
- overload/queue timeout은 GPU_DEVICE_LOST와 다르다. 일시 혼잡 하나가 기존 긴 장치 격리를 유발하지 않게 분류한다.
- AUTH/BAD_REQUEST/embedding-space 불일치는 같은 요청을 재시도해서 풀 문제가 아니다.
- CANCELLED는 neutral이다. 다른 모델 호출과 health penalty를 만들지 않는다.
- 회복 probe는 기존 health tracker의 half-open 제어와 공유해 동시에 여러 모델을 적재하지 않는다.

이 정책은 앱이 보내는 일을 통제하는 것이지 Chrome·녹화 앱의 GPU 사용을 강제로 낮추는 기능이 아니다. 화면 반응성 개선은 별도 실측 지표로 확인하며 보장되지 않은 효과를 완료 보고에 넣지 않는다. 과부하 시 제한된 대기·축소 응답·재시도 폭주 억제의 근거는 [W4]를 참고한다.

---

## 7. P4 — 앞단부터 작동하는 단계별 fallback

### 7.1 우회 표

| 단계 | 정상 경로 | 혼잡/실패 시 허용 행동 | 금지 행동 |
|---|---|---|---|
| 선택적 query rewrite/self-ask | 3060 보조 모델 | 원 질문 보존, 선택적 분해 질의 수 축소, coverage 기록 | 원 질문 소실, 무한 보조 LLM 재호출 |
| 필수 질의 embedding | configured local embedding | 검증된 동일 공간의 명시 endpoint, 아니면 접근 가능한 lexical 경로 | 차원만 같은 다른 모델로 기존 index 검색 |
| vector retrieval | 해당 index 검색 | 실제 연결된 lexical/local/graph 경로, 또는 typed retrieval failure | 예외를 진짜 no-hit으로 표시 |
| 선택적 rerank | 기존 embedding/ONNX backend | 확보된 후보·locator·순서 기준을 보존하고 rerank skipped 표시 | no-op인데 rerank succeeded 표시 |
| graph 조회 | bounded relation lookup + 출처 | auto는 지원되는 다른 검색으로 축소, required는 graph unavailable 의미 보존 | 연결 실패를 관계가 없다는 증거로 사용 |
| 필수 verifier | 정책상 요구된 검증 | 허용된 검증 대체 경로, 불가하면 verification_unavailable | 과부하라는 이유로 통째로 PASS |
| 최종 생성 | 선택 모델의 검증된 경로 | preferred/auto 및 예산·전송 허용 조건에서 기존 gateway fallback | strict 모델 변경, 근거 유실, 이중 답변 출력 |
| 선택적 답변 expansion | 허용된 경우만 | 원문 보존 | 숫자·JSON·정상 답을 편집 실패 문구로 교체 |

보조 모델의 5초 실패가 주력 모델의 장치 고장으로 기록되지 않게 **stage/role/route/model/attempt**를 모두 연결한다. helper 재시도를 줄였다는 이유로 필수 검증까지 생략하지 않는다.

### 7.2 embedding과 index의 동일성

`OllamaEmbeddingModel.backupEmbeddingSpaceCompatible()`와 `requireCompatibleBackupEmbeddingSpace()`, `EmbeddingFingerprint`, `FingerprintAwareEmbeddingStore`를 유지한다. 동일 dimension만으로 compatibility를 승인하지 않는다. 모델 identity/사용 가능한 revision 또는 digest, 차원, normalization/slicing, prefix/task 형식, zero-pad 정책, index namespace를 실제 embedding 생성 설정과 대조한다.

같은 모델 태그라도 실제 버전이 다름이 확인되면 별도 공간으로 취급한다. revision을 관측할 수 없을 때는 검증 수준을 표시하고 미검증 대체 모델을 호환으로 선언하지 않는다. fingerprint를 원 모델 값으로 강제 stamping하여 다른 모델 벡터가 통과하도록 만들지 않는다.

현재 `buildCandidateUrls():1583–1612`에는 옵션이 켜지면 alternative port를 explicit secondary보다 먼저 추가하는 경로가 있다. 이번 adaptive 경로에서 fallback을 켜겠다고 `cross-gpu-fallback=true`만 변경하지 않는다. **명시 등록·capability 검증·공간 호환성이 확인된 후보만** 사용하고 순서를 추적한다. 런타임에 임의 포트를 전수 탐색하거나 사용자 모르게 embedding 모델을 pull하지 않는다.

`EmbeddingFallbackConfig`가 외부 모델 Bean을 만들 수 있어도 local wrapper의 호환성 guard를 통과했다는 뜻은 아니다. 다른 모델을 쓰려면 별도 index 구축·승인된 migration이 필요하다. 이번 작업에서 기존 production index를 지우거나 무단 재임베딩하지 않는다. [W5]

배치 embedding 실패 뒤 단건으로 재시도하는 경로도 같은 admission·attempt budget을 사용하게 한다. 100건 batch 실패가 100개의 병렬 보조 요청으로 증폭되면 안 된다.

### 7.3 lexical 검색의 실제 연결

현재 활성 저장소/검색 backend에서 접근 권한과 문서 locator를 보존하는 lexical 경로가 있는지 먼저 확인한다. 있으면 재사용한다. 없다면 기존 scorer 또는 backend의 기능에 **작은 adapter**를 붙여 browser RAG에서 접근 가능한 문서 범위와 연결한다. 새 검색 엔진 도입부터 시작하지 않는다.

`LocalBm25Retriever` 자체에는 사용자 ACL·지속 저장·source revision 계약이 완성돼 있지 않다. 이를 단순 Bean 등록하고 “문서 fallback 완료”라고 하지 않는다. 인덱스의 범위/갱신/삭제/권한·docId→chunk locator 대응을 테스트한다. 매 요청마다 전체 파일을 재스캔·재색인하지 않는다. lexical 경로가 준비되지 않았으면 `lexical_unavailable`이지 성공한 빈 검색이 아니다.

### 7.4 생성 fallback·대기·취소

`llm/gateway/FallbackAwareChatModel`, factory/router, `ModelRuntimeHealthTracker`, `ChatRunExecutionContext`, 기존 usage/time budget을 확장한다. 새로운 retry loop를 감싸 기존 내부 retry와 곱셈으로 늘리지 않는다. 같은 request의 단계별 child attempt와 총 inference attempt 상한을 공유한다.

대기열 지연, 모델 로딩, 첫 토큰 전 대기, 토큰 간 정체, 전체 요청 deadline을 구분한다. 작은 fast helper의 timeout을 주력 cold load에 복사하지 않는다. 반대로 토큰이 진행 중이라는 이유로 총 예산을 무한 연장하지 않는다. 단계 전환 때 deadline을 처음부터 다시 시작하지 않는다.

로컬 실패 후 cloud로 넘어갈 때도 원래 승인된 evidence·history·system/output contract를 보존하고, 전송 정책에서 금지된 문서/개인정보는 보내지 않는다. 비용 예약은 동시 요청에도 원자적으로 집계한다. 현재 키/한도가 불분명한 실제 외부 경로는 mock으로 계약을 검증하고 실기 미검증으로 표시한다.

이미 일부 본문을 stream으로 공개했다면 다른 모델의 답을 뒤에 이어 붙이지 않는다. 기존 시도를 멈추고 attempt가 바뀐다는 이벤트를 보낸 뒤 UI가 이전 임시 본문을 교체하도록 하는 명시적 계약이 없다면, 해당 응답을 partial failure로 끝내고 사용자의 재시도를 받는다. 안정화 첫 버전은 **첫 본문 전 자동 fallback**을 우선한다.

Stop은 active stage뿐 아니라 queued work/외부 HTTP/후처리까지 연결한다. `Future.cancel(true)`만으로 remote GPU 종료를 증명하지 않는다[W7]. 실제 transport 취소와 upstream 상태를 관측하고, 미확인 취소는 별도 reason으로 남긴다. 전역 process kill이나 unrelated 모델 unload로 종료를 대신하지 않는다. old run의 token/final event가 새 요청에 섞이지 않도록 run/attempt ownership을 검사한다.

---

## 8. P5 — 화면·진단·설정 왕복을 실제 실행과 일치시킨다

### 8.1 화면 상태

`chat.js`에 단계별 서버 상태를 연결한다. “client wait 60초”만 늘리지 않는다. 다음과 같은 의미를 실제 event에서 표시한다.

```text
대기열에서 순서 대기 → 모델 불러오는 중 → 문서 검색 중
→ 재랭킹 생략(혼잡) → 답변 생성 중 → 근거 확인 중
→ 답변 완료 / 축소 경로로 완료 / 자료 부족 / 실행 실패 / 취소됨
```

이벤트를 받지 못했으면 “서버 단계 확인 중”처럼 관측 한계를 표시한다. 클라이언트 시계만으로 생성 중/검증 중을 지어내지 않는다. 실제 남은 시간 추정이 없으면 가짜 진행률·완료 예정 시간을 만들지 않는다.

선택 모델과 실제 생성 모델이 다르면 별도로 보여 준다. 정상 body, status badge, debug detail을 분리한다. 내부 HOLD 문자열을 frontend에서 지우고 이전 draft를 보여 주는 방식은 금지한다. HTTP 200이라도 release held/failed면 완료 성공으로 기록하지 않는다. SSE가 이미 200으로 열린 경우 final semantic status를 authoritative 결과로 기록한다.

### 8.2 최소 진단 필드

기존 TraceStore/ChatRun/usage ledger의 필드를 먼저 재사용한다. 아래 이름은 필요한 의미를 보여 주는 제안이며 기존 키에 매핑해도 된다.

```text
runId / requestId / parentAttemptId / attemptId / sourceBuildIdentity
policy.requested / policy.effective / policyVersion / source / disabledReason
stage / role / routeAlias / requestedModel / actualModel / endpointIdentity
placementStatus / observedAt / sampleAge / capacityKeyHash
queueWaitMs / inFlight / queueDepth / admissionDecision / reasonCode
retrievalExecution / candidateCount / acceptedEvidenceCount / coverage
attributionStatus / verifierStatus / releaseAllowed / releaseReason
fallbackFrom / fallbackTo / fallbackCount / fallbackBlockedReason
loadDurationMs / firstTokenMs / promptEvalMs / evalCount / evalDurationMs
cancellationRequested / transportCancelStatus / upstreamTerminationConfirmed
knowledgeWriteAllowed / cachePolicyFingerprint / finalSemanticStatus
```

Ollama native 응답에서 제공하는 loading/evaluation 정보를 보존하되[W6], OpenAI 호환 transport에서 관측되지 않는 필드는 null/unsupported로 남긴다. 없는 값을 0ms로 기록하지 않는다. 단위와 요청 범위를 명시한다. public 진단에는 raw endpoint·PID·GPU UUID·원문·벡터·쿠키·토큰·API 키를 내보내지 않는다. 상세 진단은 기존 보호된 operator 경계로 제한한다.

성공/실패 카운터는 제한된 stage/role/status label을 사용한다. requestId·모델 임의 문자열을 metrics label로 무제한 쌓지 않는다. 요청 상세는 bounded trace에 둔다.

### 8.3 설정 검증

새 옵션 각각에 대해 다음을 확인한다.

```text
UI 변경 → 저장/직렬화 → 서버 수신 → effective policy
→ 실행 계획 변경 → 실제 downstream 호출 변화 → final metadata
→ 새로고침 후 값 유지 → 새 요청에서 동일 의미
```

ON/OFF 양쪽에서 **실제 호출 횟수**가 바뀌는지를 검사한다. localStorage에 저장됐다는 것, DTO에 필드가 생겼다는 것, badge가 켜졌다는 것만으로 구현 완료를 선언하지 않는다. UI에서 false를 명시한 값과 null/default를 병합 과정에서 섞지 않는다.

---

## 9. P6 — Graph RAG·문서 검색의 재현 가능한 시연 데이터

사용자가 허용한 테스트 namespace/owner 범위에만 아래 데이터를 만든다. production corpus를 덮어쓰지 않는다. 테스트 데이터와 그래프 edge 모두 원본 문서 locator를 유지한다.

| ID | owner | 문서 내용 |
|---|---|---|
| D1 | TEST_A | 오로라 프로젝트의 책임 팀은 루멘팀이다. |
| D2 | TEST_A | 루멘팀이 책임지는 모든 프로젝트의 문서는 17일 보관한다. |
| D3 | TEST_B | 하버팀이 책임지는 모든 프로젝트의 문서는 29일 보관한다. |

Q1: `루멘팀이 책임지는 프로젝트 문서의 보관 기간은?` → 17일과 D2 근거.

Q2: `오로라 프로젝트 문서는 며칠 보관하는가?` → D1의 책임 관계와 D2의 보관 규칙을 연결하여 17일. 답변에는 두 문서의 관계가 드러나야 한다.

Q3: `오로라 프로젝트의 예산은? 첨부 문서만 근거로 답해.` → 성공한 검색에서 예산 정보가 없으면 자료 부족. 임의 예산 금액 금지.

Q4: TEST_A로 TEST_B 문서를 유도하는 질의 → D3의 본문·edge·cache·locator가 노출되지 않음.

동일 Q2로 graph off/auto/required를 비교한다. 단순 벡터 검색도 D1·D2를 잘 찾으면 그래프가 반드시 더 좋았다고 꾸미지 않는다. deterministic 테스트에서는 vector stub이 D1, graph stub이 출처 있는 D2 관계를 추가하도록 해 **연결 기능**을 증명하고, 실제 검색 품질 비교는 별도 실험으로 보고한다.

Graph requested/used/returned/added/cited를 구분한다. DB 연결 성공 또는 graphEnabled=true가 graph를 활용한 답변 증거는 아니다. edge가 없다는 것과 graph 조회가 실패한 것을 구분한다. `KnowledgeGraphRetrievalHandler`가 exception 후 계속 진행하는 현재 경로에서 cancellation이 다음 단계로 이어지지 않도록 별도로 검사한다.

검색 품질, 근거 일치, 답변 완전성, 지연·비용은 별도 평가 항목이다[W8]. Microsoft GraphRAG도 여러 검색 모드를 제공하지만[W9], 이를 이유로 현재 Java 구현을 통째로 그 프레임워크로 교체하지 않는다.

---

## 10. 테스트 실행 계약 — 통과 개수보다 관측한 범위

### 10.1 테스트 파일 배치

현재 프로젝트의 실제 test sourceSets를 확인한다. 다음 이름은 **이번에 추가하거나 기존 동등 테스트에 통합할 후보**이며 ZIP 안에 존재한다고 주장하는 목록이 아니다.

| 테스트 그룹 | 후보 파일/기존 연결 | 소유 패치 |
|---|---|---|
| 실행 결과·공개 정책 | `src/test/java/com/example/lms/service/ChatWorkflowEvidencePolicyContractTest.java` 또는 기존 final release gate 테스트 확장 | P1 |
| 단답·다듬기 | 기존 `AnswerExpanderServiceTest` 및 output contract 테스트 | P2 |
| 장치별 admission·fake clock | `src/test/java/com/example/lms/health/GpuLaneAdmissionContractTest.java` | P3 |
| 임베딩/lexical/rerank/graph 우회 | `src/test/java/com/example/lms/service/rag/RagStageFallbackContractTest.java` | P4 |
| 설정 직렬화·cache | `src/test/java/com/example/lms/api/ChatAdaptivePolicyContractTest.java` 및 실제 chat.js harness | P5 |
| 취소·재시도·이중 방출 | 기존 ChatRun 테스트 또는 `src/test/java/com/example/lms/service/chat/AdaptiveCancellationContractTest.java` | P3–P5 |

먼저 실패를 재현한다. 실제 구현을 호출하지 않는 가짜 stub에 원하는 값을 적고 PASS를 받아내지 않는다. fake clock·mock transport·bounded executor로 실패/회복/취소 경계를 결정적으로 실행한다. 하드웨어가 없는 CI에서도 계약 테스트가 가능해야 하며 실기 GPU 검증과 분리한다.

### 10.2 필수 acceptance matrix

같이 제공한 `m21222ain_acceptance_cases_2026-09-24.json`의 case들을 테스트 ID로 사용한다. 최소 다음 반례를 빠뜨리지 않는다.

- 실제 검색 SUCCESS_EMPTY와 transport FAILED가 서로 다른 final reason을 만든다.
- attribution gate 거부와 locator 미상은 성공한 0건 검색으로 둔갑하지 않는다.
- 일반 개념 adaptive는 clean fallback으로 답할 수 있고, 문서 한정/required verifier는 우회 공개되지 않는다.
- `7`과 JSON 출력 보존, polish false 시 expansion 0회.
- 3060 혼잡이어도 독립 3090 요청은 계속 가능하고, 정상 생성 중 100% utilization만으로 취소하지 않는다.
- 동일 GPU의 서로 다른 endpoint가 permit을 공유하며 unknown telemetry는 장치 없음으로 오판하지 않는다.
- 대기열은 bounded, deadline은 단조 감소, 해제는 정확히 1회, 회복 probe는 제한됨.
- 같은 차원·다른 모델 embedding은 거부; 명시된 동일 공간 대체만 허용; batch→단건 재시도 폭주 없음.
- lexical fallback이 실제 문서·ACL·locator를 지킴; unavailable과 empty 구분.
- rerank skipped가 이미 얻은 근거를 잃게 하지 않음; 필수 verifier는 skip→PASS 금지.
- cloud false/키 없음/예산 소진/전송 불가/strict 각각에서 외부 호출 0회와 사유 확인.
- 허용된 generation fallback은 근거·history·output 계약 유지, 실제 모델 표시, 시도 수 상한 준수.
- Stop 뒤 늦은 body·knowledge write·새 fallback 없음. 새 run은 정상 완료.
- cache가 effective policy·strict·권한 차이를 무시하지 않음.
- graph off/auto/required, graph outage/no-edge, 타 owner 격리, 관계 근거 연결 확인.

### 10.3 실행 명령과 결과 분리

실제 Gradle root에서 현재 wrapper와 sourceSets를 확인한 뒤 실행한다. 아래는 경로를 확인한 **후** 사용할 PowerShell 명령 형태다. 실행하지 않은 명령에 성공 코드를 적지 않는다.

```powershell
# 확인된 Gradle root에서 실행. Windows PowerShell 문법 사용.
& .\gradlew.bat :compileJava :compileTestJava :processResources
if ($LASTEXITCODE -ne 0) { throw 'compile or test compilation failed' }

# 위 후보 이름을 실제 생성/통합한 테스트 이름으로 확인한 뒤 선택 실행한다.
& .\gradlew.bat test --tests '*ChatWorkflow*Release*' --tests '*AnswerExpander*'
if ($LASTEXITCODE -ne 0) { throw 'release/expander regression failed' }

# 실제 도입한 contract 테스트들과 기존 cancellation/embedding/factory 테스트를 각각 실행한다.
# 마지막에는 제외 목록을 추가하지 않은 저장소 기본 전체 test를 실행한다.
& .\gradlew.bat test
```

테스트가 기존에 깨져 있으면 baseline/new/환경 부족을 분리하고 실패 이름·assertion·재현 명령을 적는다. 이전의 native 잘못된 URL 테스트, reflection 시그니처 변경, concurrent factory 변경을 그대로 가져와 이번 패치 실패로 단정하지 않는다. 반대로 “외부 변경 같다” 한 줄로 실제 새 회귀를 빼지 않는다. 문자열 grep/probe PASS와 JUnit/브라우저/실기 PASS를 분리한다.

### 10.4 실기 비교의 세 경로

**A. 직접 호출:** 앱의 실제 endpoint·모델과 일치하는 Ollama 호출. 전체 body, done, loading·prompt·generation 시간, 생성 토큰 수와 runner GPU를 연결한다.

**B. 앱 / RAG OFF·웹 OFF:** 같은 모델로 인사, 산술 단답, 일반 설명. 모델→raw answer→postprocess→release→browser가 연결되는지 확인한다.

**C. 앱 / RAG ON:** D1–D3 fixture로 정답/관계/없는 정보/장애 주입을 비교한다. 임베딩·lexical·rerank·graph·generation·release 중 어디서 어떤 상태로 끝났는지 같은 runId로 확인한다.

각 경로의 cold/warm 결과를 분리한다. 네이티브 direct 성공이 다른 transport의 앱 성공을 대신하지 않는다. 신규 JVM 또는 검증된 hot reload의 source identity를 확인한다. 테스트가 mock이면 mock, remote API를 호출하지 않았으면 미호출, GPU 접근이 없으면 실기 미검증이라고 명시한다.

---

## 11. 패치 단위·복구·보고 형식

### 11.1 변경 순서

P0 기준선 → P1 실행/근거 상태와 공개 정책 → P2 단답 보존 → P3 장치 admission → P4 stage fallback → P5 설정/화면/cache 연결 → P6 Graph RAG 검증 순서로 진행한다. 정책 DTO·불변 snapshot처럼 P1/P3에 필요한 최소 기반은 해당 패치에 함께 넣고, UI 연결은 P5에서 완료한다. 부분 구현 옵션은 작동하는 것처럼 노출하지 않는다.

각 단위는 가설 1개, RED 증거, 변경 파일, GREEN 증거, 남은 반례, 복구 방법을 가진다. 파일 하나만 수정하라는 뜻이 아니다. 한 계약을 고치는 관련 파일은 함께 바꾸되, GPU 라우팅·인덱스 변경·UI 전면 개편을 한 거대 패치로 묶지 않는다.

### 11.2 동시 작업·복구

읽은 뒤 쓰기 직전에 해당 파일의 실제 내용과 예약을 재확인한다. 행 번호 기반 전역 byte replace나 ZIP 전체 덮어쓰기로 동시 변경을 지우지 않는다. 같은 파일의 다른 세션 변경이 있으면 현재 base에서 reconciliation하고, 결과 diff에서 타 세션의 수정이 사라지지 않았는지 확인한다.

운영 파일·설정의 복구 지점을 남기고 각 패치의 base/after hash와 수정 범위를 기록한다. DB/index를 바꾸는 패치는 소스 rollback만으로 복구되는지 별도로 검토한다. 이번 기본 경로에서는 index schema 변경/재색인을 피한다. 기능 flag를 되돌리는 것이 auth/verification/embedding-space guard를 비활성화하는 동작이면 안 된다.

무거운 신규 ledger·스킬 군·상위 orchestration 시스템을 만들지 않는다. 이미 있는 journal/lease/PatchDrop을 사용한다. 외부 reviewer가 미지원이면 “실행 안 됨”으로 기록하며 review PASS로 계산하지 않는다.

### 11.3 Codex 최종 보고 형식

```text
1. 적용한 동작: 실제 옵션 값과 실제 실행 변화
2. 수정 파일: 현재 경로, 핵심 메서드, base/after hash
3. 재현 → 수정 → 재검증: runId / 테스트 ID / 실패와 성공 근거
4. 요청별 실행: 선택 모델/실제 모델, embedding/검색/graph/rerank,
   fallback 이유, evidence/verification/release, 화면 최종 결과
5. 테스트 결과: focused / 전체 / browser / GPU / 외부 API를 각각 분리
6. 남은 문제: blocked 파일, 실패 테스트, 실기 미검증, 권한/비용으로 미호출
7. 복구: 패치별 되돌릴 파일과 설정, 데이터 변경 유무
```

“GPU가 보인다”, “VRAM이 올랐다”, “테스트 수백 개가 통과했다”, “HTTP 200이다”, “모델이 7을 만들었다” 중 어느 하나만으로 전체 해결을 선언하지 않는다. **사용자가 선택한 정책으로 실제 답변이 끝까지 전달되고, 근거가 맞으며, 실패 시 왜 실패했는지 구분되고, Stop·재시도가 복구되는 것**이 완료 기준이다.

---

## 12. 공식 자료 — 적용 범위를 제한해서 사용

각 링크는 2026-09-24에 확인한 공개 문서다. 현재 설치 버전과 다를 수 있으므로 API/설정 적용 전 로컬 버전과 대조한다. 아래 요지는 설계 판단의 외부 근거이며 이 프로젝트의 실행 성공을 증명하지 않는다.

- **[W1] Microsoft, GPUs in the task manager** — 엔진 표시와 실제 실행 API·프로세스 원인 단정의 한계.
  `https://devblogs.microsoft.com/directx/gpus-in-the-task-manager/`
- **[W2] Ollama FAQ** — 모델 적재 위치 표시, 상주, 동시 실행/대기열, 여러 GPU에 모델을 배치하는 기본 설명. 공식 문서가 “항상 CUDA index 0부터 쓴다”를 보장하지 않으므로 과거 첨부의 해당 추정은 정책 근거로 쓰지 않는다.
  `https://docs.ollama.com/faq`
- **[W3] NVIDIA nvidia-smi** — UUID 식별과 WDDM의 프로세스별 GPU 메모리 관측 제한. N/A는 0이 아니다.
  `https://docs.nvidia.com/deploy/nvidia-smi/index.html`
- **[W4] Google SRE, Handling Overload** — 과부하에 대한 제한된 작업 수용·축소 응답·재시도 증폭 방지.
  `https://sre.google/sre-book/handling-overload/`
- **[W5] Microsoft Learn, Generate embeddings for search queries and documents** — indexing과 querying에 같은 embedding 모델을 사용해야 한다는 요구.
  `https://learn.microsoft.com/en-us/azure/search/vector-search-how-to-generate-embeddings`
- **[W6] Ollama Generate API** — done/load_duration/prompt_eval_duration/eval_count/eval_duration 등 응답 필드.
  `https://docs.ollama.com/api/generate`
- **[W7] Oracle Java 17 Future** — cancel은 취소를 시도하는 계약이다. 원격 서버의 GPU 종료까지 증명하는 API가 아니다.
  `https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Future.html`
- **[W8] Microsoft Foundry, RAG evaluators** — retrieval 품질, groundedness, response completeness를 구분한다. 새 유료 evaluator 도입 요구가 아니라 평가 항목 분리의 근거다.
  `https://learn.microsoft.com/en-us/azure/foundry/concepts/evaluation-evaluators/rag-evaluators`
- **[W9] Microsoft GraphRAG query overview** — local/global/basic 등 질의 방식의 구분. 모든 질문에 graph를 강제하거나 현재 구현을 교체하라는 근거가 아니다.
  `https://microsoft.github.io/graphrag/query/overview/`

**최종 한 문장:** GPU 미인식을 전제로 작업을 되돌리지 말고, 3060의 보조 역할을 유지한 채 혼잡한 단계에서 제한된 우회가 작동하며, 3090 또는 승인된 대체 생성 경로의 답변과 정직한 근거 상태가 사용자 화면까지 도달하도록 기존 소스를 완성하라.
