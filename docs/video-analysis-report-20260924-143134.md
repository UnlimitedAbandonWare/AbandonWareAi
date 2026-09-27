# 영상 분석 보고서: /chat qwen3.5:9b GPU 준비 지연 → model_unavailable → 근거 부족 보류

- 영상: `F:\CAM\2026-09-24 14-31-34.mkv` (5분 55초, 1280×720)
- 분석 일시: 2026-09-24
- 작업 ID: `video-analysis-20260924-143134-5c6e700e`
- 범위: `main/java` chat/model/router/rag, `frontend` chat, `configs`, `docs`

---

## 1. 영상에서 관측된 주요 증상

### 1.1 모델 선택 후 장기 "Assistant is preparing"
- `/chat` UI에서 모델 `qwen3.5:9b`를 선택하고 `"안녕?"` 입력.
- 오른쪽 작업 관리자 GPU 패널에서 **NVIDIA RTX 3090 전용 GPU 메모리가 19.3/24.0 GB(약 80%) 점유**된 상태로 오랫동안 **GPU 사용률 0%** 유지.
- 이후 GPU 사용률이 급등(92~100%)하면서도 답변 생성에 실패.
- 디버그 패널에 `Model warn GPU / API Failover warn` 표시.

### 1.2 `backend_unavailable` / `model_unavailable` 오류
- 영상 전반에 디버그 상태에 다음이 반복됨:
  - `Intake running Plan / MoE done Query Rewrite done Anchor queued Retrieve queued DPP / Rerank warn Context queued Model running Chat Harmony warn CFVM Failure queued Resilience warn Supabase queued`
  - `Agent DB DISABLED`, `Trace Memory unavailable`, `chroma:unavailable`, `pattern:unavailable`
- 한 시점에 `"모델 서버에 연결할 수 없거나 실행이 실패했습니다. 서비스 상태를 확인해 주세요."` 메시지.

### 1.3 RAG 검색은 동작하나 인용 실패
- `qwen3.5:9b` 재선택 후 `"안녕?"`에 대해:
  - 검색 결과 `Evidence 3` 표시: `namu.wiki`, `korean.go.kr`, `krdict.korean.go.kr`
  - 상태는 `searched, not cited` — 검색은 되었지만 답변에 인용되지 않음.
  - 최종 응답: `"검증된 근거가 추가로 필요해 응답 본문을 보류했습니다."`
  - 실제 본문(answer text) 없음.

### 1.4 디스크 I/O 병목
- 작업 관리자 "디스크 1(E:)" 사용률이 **100%**에 도달.
- E: 드라이브에서 Ollama 모델 파일 로드로 인한 I/O 병목으로 추정.

---

## 2. 코드 근거 및 원인 분석

### 2.1 서버 총 타임아웃이 로컬 모델 cold load를 커버하지 못함
- `ConversateApiCueService.answer()` (`main/java/com/example/lms/assist/ConversateApiCueService.java:66`):
  ```java
  long budget=prior==null?limit("total-timeout-ms",12000,2000,20000):prior.capWaitMillis(...);
  ```
  - **기본 총 예산 12초.** 질문 → 검색 → 압축 → LLM 생성 전체를 12초 안에 끝내야 함.
- `DynamicChatModelFactory.lc()` (`main/java/com/example/lms/llm/DynamicChatModelFactory.java:149`):
  ```java
  return lcWithTimeout(modelName, temperature, topP, frequencyPenalty, presencePenalty, maxTokens, 120);
  ```
  - 단일 HTTP 타임아웃은 120초로 설정되어 있으나, **ConversateAnswerPipeline의 상위 `TimeBudget`이 12초에서 먼저 만료**.
- 영상에서 RTX 3090 메모리는 이미 다른 모델(아마도 이전 `smitec/Qwen3.8-27B-Q3_K_XL` 시도)에 의해 19 GB 점유. `qwen3.5:9b`를 새로 로드하려면 **Ollama가 메모리/디스크에서 기존 모델을 스왑/언로드 + 9B 모델 cold load** 필요. 이 과정이 12초를 초과.
- 결과적으로 `TimeoutException` → `LlmGatewayFailureClassifier.TIMEOUT_SOFT` → `model_unavailable`.

### 2.2 exact local 선택 시 cloud/자동 폴백이 차단됨
- `LlmRouterAspect.aroundLcWithTimeout()` (`main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java:162`):
  ```java
  if (com.example.lms.llm.RequestedModelSelection.matches(ca.requestedModelId)) {
      if (!ca.requestedModelId.startsWith("llmrouter.")) return pjp.proceed();
  }
  ```
  - 사용자가 UI에서 직접 `qwen3.5:9b`를 고른 경우 **exact selection**으로 처리.
  - `routeWithGateway()` 내에서 exact selection일 때 eligibility 실패 시:
    ```java
    if (com.example.lms.llm.RequestedModelSelection.matches(ca.requestedModelId)
            && (eligibility == null || !eligibility.eligible()))
        throw new com.example.lms.llm.ModelSelectionException("model_unavailable");
    ```
  - **즉, 사용자가 명시적으로 선택한 로컬 모델이 불능이면 자동으로 다른 모델/클라우드로 넘어가지 않고 바로 실패.**
- 이는 `ExactModelGatewayTest.exactLocalSelectionNeverArmsCloudFallbackViaRouteLocalInference()` 테스트에서 의도적으로 검증된 동작.

### 2.3 GPU gateway 진단이 recovery에 충분한 시간을 주지 않음
- `HybridLlmGatewayProbeService.guardLocalModel()` (`main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java:204`):
  - `activeLocalDispatches` 맵으로 **같은 엔드포인트에 동시 요청 1개**만 허용.
  - cold load 중 첫 요청이 실패하면 해당 worker가 `finally`에서 제거되지만, timeout이 소프트하게 발생하면 **GPU recovery 검증이 2초(`gpuRecoveryVerified` allowed budget)로 제한**.
- `HybridLlmGatewayProbeService.evaluate()` (`:66`):
  - `healthTracker.snapshot()`에서 마지막 성공이 없고 local device failover가 enable이 아니면 `failures`에 이유 추가 → **과거 실패가 다음 요청 eligibility를 계속 깎을 수 있음.**

### 2.4 RAG_CUE 인용 실패 → 빈 응답
- `ConversateApiCueService.answer()` (`:136`):
  ```java
  debug.put("evidenceStatus",!cueDecision.equals("RAG_CUE")?"NOT_REQUIRED":evidence.isEmpty()?"EMPTY":usableEvidence(evidence).isEmpty()?"FRAGMENTED":"AVAILABLE");
  ```
  - `RAG_CUE`인데 `usableEvidence`가 0이면 `FRAGMENTED`.
- `:186`:
  ```java
  if(value.insufficient()){
      debug.put("hintPath","GENERAL_HINT");debug.put("fallback",true);
      if("AVAILABLE".equals(debug.get("evidenceStatus")))debug.put("evidenceStatus","INSUFFICIENT");
      debug.putIfAbsent("fallbackReason","EVIDENCE_INSUFFICIENT");
  }
  ```
  - `ConversateCardPrompt`에서 생성한 응답이 `insufficient=true`이면 `"검증된 근거가 추가로 필요해 응답 본문을 보류했습니다."` 출력.
- `canSupplement()` (`:227`):
  ```java
  return !trace.containsKey("conversate.search.BRAVE")
          && trace.get("conversate.search.NAVER") instanceof Map<?,?> naver
          && "NONE".equals(naver.get("failureReason"))
          && naver.get("resultCount") instanceof Number count&&count.intValue()>0;
  ```
  - **Brave 보강 검색이 아직 예약 상태**이고, Naver 결과만 있을 때 보강 가능. 영상의 `namu.wiki/korean.go.kr/krdict` 결과는 아마도 Naver/KoreanGov 웹 검색 결과로 보이나, 압축/인용 단계에서 `usableEvidence`로 인정받지 못함.

---

## 3. 개선 제안 (소스 수정 고려사항)

### 3.1 [높음] exact local 모델 cold-load용 별도 타임아웃/예산 분리
- 현재 `ConversateApiCueService`의 `total-timeout-ms=12000`은 **이미 메모리에 올라간 모델**의 생성 시간을 가정함.
- **로컬 모델 cold load(디스크 → 메모리)는 별도 budget**을 두거나, 모델 상태에 따라 timeout을 동적으로 늘려야 함.
- 수정 후보:
  - `DynamicChatModelFactory.lcWithTimeout()`에서 `timeoutSeconds` 인자를 받지만, 상위 `TimeBudget`이 더 짧으면 의미 없음.
  - `ConversateApiCueService`에서 모델이 메모리에 없을 경우(cold) `total-timeout-ms`를 늘리거나, `ModelRuntimeHealthTracker`에 모델 로드 상태를 노출하여 예산 조정.
  - 또는 `localLlmProcessManager`/`HybridLlmGatewayProbeService`에 모델 warm-up API를 추가.

### 3.2 [높음] exact selection 실패 시 사용자에게 폴백 옵션 명시
- 현재 exact selection은 strict 실패 → `model_unavailable`.
- **개선 방향:**
  - `LlmRouterAspect`에서 exact selection 실패 시, 같은 capability의 다른 사용 가능한 로컬 모델로 자동 폴백 **대신** UI에 `"선택한 모델이 준비되지 않았습니다. 대체 모델 X로 시도할까요?"` 확인 노출.
  - 폴백은 `llmrouter.auto`나 cloud route를 사용하되, **사용자 동의 후**에만 exact 마커를 해제.
- 이는 `ExactModelGatewayTest`의 `exactLocalSelectionNeverArmsCloudFallbackViaRouteLocalInference()` 기존 계약을 해치지 않음(자동 폴백 금지 유지).

### 3.3 [중간] 모델 피커에 "cold / loaded / busy" 상태 추가
- `chat-model-picker.js` (`main/resources/static/js/chat-model-picker.js`)에서 모델 카탈로그는 `selectable=true/false`만 표시.
- **Ollama `ps` API 또는 `/api/tags` + `size` + 메모리 사용량**을 바탕으로:
  - `loaded`: 메모리에 상주 중 → 빠른 응답
  - `cold`: 설치됨 but 메모리에 없음 → cold load 예상
  - `busy`: 동일 엔드포인트에서 다른 요청 처리 중
- 이 상태를 UI에 노출하면 사용자가 cold load 모델 선택을 피할 수 있음.

### 3.4 [중간] 디스크 I/O 병목 완화
- 영상에서 E: 드라이브 사용률 100% 관측.
- **Ollama 모델 디렉터리를 더 빠른 드라이브(C: NVMe 등)로 이동**하거나, `OLLAMA_MODELS` 환경 변수 조정.
- 코드 변경보다는 운영 설정 개선. 다만 `configs/api-routing.yaml` 또는 `application-llm.yaml`에 모델 경로/드라이브 관련 힌트 문서 추가 가능.

### 3.5 [중간] RAG_CUE 인용 실패 시에도 검색 결과 요약 표시
- 현재 `insufficient=true`이면 본문을 완전히 보류.
- **개선 방향:**
  - `ConversateApiCueService`에서 `evidenceStatus=FRAGMENTED`일 때, 생성된 답변 대신 **검색 결과 3건의 제목/URL/1줄 요약**을 카드로 표시.
  - 사용자에게 `"검색 결과는 찾았으나 인용할 만한 본문이 충분하지 않습니다"`라고 투명하게 공개.
  - 이는 `frontend/src/app/api/chat/sync/route.js` 또는 `chat-answer-renderer.js`에서 `CUE_EVIDENCE_INSUFFICIENT` 모드에 대한 렌더링 분기 추가로 구현.

### 3.6 [낮음] GPU readiness 사전 체크
- `HybridLlmGatewayProbeService.evaluate()`에서 local 모델 eligibility 판정 시, **VRAM 사용량 + 현재 로드된 모델**을 추가 메타로 노출.
- `GpuGatewayDiagnostics` (`main/java/com/example/lms/health/GpuGatewayDiagnostics.java`)의 스냅샷을 `/api/chat/models` 응답에 포함하면 모델 피커가 더 정확한 상태 표시 가능.

---

## 4. 우선순위 제안

| 우선순위 | 항목 | 예상 영향 |
|---|---|---|
| P1 | exact local 모델 cold-load 타임아웃 분리 | `model_unavailable` 직접 실패 감소 |
| P2 | 폴백 동의 UI + exact 마커 해제 | 사용자 경험 개선, 자동 폴백 계약 유지 |
| P3 | 모델 피커에 loaded/cold/busy 상태 | 사용자가 cold 모델 자발 회피 |
| P4 | RAG_CUE 인용 실패 시 검색 요약 표시 | 빈 응답 대신 유용한 정보 제공 |
| P5 | Ollama 모델 디스크 이전 | I/O 병목 완화(운영) |

---

## 5. 검증 제안

- 단위 테스트:
  - `ExactModelGatewayTest`에 "exact local 모델 cold load 시 상위 TimeBudget 초과로 timeout_soft → 사용자 동의 후 폴백" 시나리오 추가.
  - `ConversateApiCueServiceTest`에 `evidenceStatus=FRAGMENTED`일 때 카드에 검색 결과 요약 포함하는지 검증.
- 통합/수동 검증:
  - RTX 3090에 다른 대형 모델을 먼저 로드한 상태에서 `qwen3.5:9b` exact 선택.
  - `Debug-RAG.bat -Action tail -Pattern timeout_soft`로 타임아웃 흐름 확인.
  - `/api/chat/models` 응답에 loaded/cold 상태 필드 추가 후 `chat-model-picker.js` UI 확인.

---

## 6. 다음 단계

- 사용자 확인 후 P1~P2 소스 수정 진행.
- 수정 시 `DynamicChatModelFactory`, `LlmRouterAspect`, `ConversateApiCueService`, `chat-model-picker.js`, `chat-answer-renderer.js` 대상.
- 각 수정 전 `scripts/codex_work_checkpoint.py begin`으로 preimage 보존.
