
# src111_merge125 — Patch Notes (점수 급상승 5개 과제: 미니멈 구현)

본 패치는 `src111_merge15 (56).zip` 코드베이스에 다음 변경을 적용했습니다.

## 0) 공통
- `application.yml` 두 위치(`app/`, `src/`)에 신규 토글/파라미터 추가:
  - `selfask.enabled`, `selfask.biTopN`, `selfask.crossTopN`, `selfask.temperature`
  - `retriever.safe.staleOnError`, `retriever.safe.staleMaxAgeSeconds`
  - `gate.preflight.enabled`, `gate.preflight.minCitations`, `gate.preflight.enforceWhitelist`
  - `singleflight.enabled`, `singleflight.maxWaitMs`
  - `metrics.ndcg.enabled`
  - `virtualPoint.enabled`
  - `retrieval.k.dynamic.enabled`

## 1) Self‑Ask 3‑Way (경량)
- 패키지 `com.example.lms.service.rag.selfask` 추가:
  - `SubQuestion.java` / `SubQuestionType.java` / `SubQuestionGenerator.java` / `HeuristicSubQuestionGenerator.java`
  - `SelfAskPreprocessorHandler.java` (`@Component`, `selfask.enabled=true`일 때만 활성)
- 역할: 원질의에서 정의·별칭·관계 3축 하위질의를 생성하는 프리프로세서(핸들러).  
  (체인 결합은 AOP/체인 훅이 있는 환경에서 간단히 끼워 넣을 수 있게 순수 기능 클래스로 설계)

## 2) 폴백 라인 (안전 래퍼)
- 패키지 `com.example.lms.service.rag.safety` 추가:
  - `SafeRetrieveDecorator.java`: stale 캐시 제공자 주입 시 예외 시 빈 리스트 또는 stale 반환
  - `RetrieveResult.java`: 상태/오류코드 포함 결과 컨테이너

## 3) Gate/Policy (Preflight)
- 패키지 `com.example.lms.guard` 추가:
  - `AutorunPreflightGate.java`, `PreflightReport.java`, `GateViolationException.java`
- 역할: 화이트리스트 및 최소 근거 개수 검증 결과를 리포트로 반환(소프트/하드 실패 연계 가능).

## 4) Single‑Flight (정합성 수정)
- **패키지-경로 정합성 오류 2건 수정** (빌드 오류 예방):
  - `SingleFlightExecutor.java` → `app/src/main/java/com/example/lms/service/infra/cache/`
  - `MultiQueryMergeAdapter.java` → `app/src/main/java/com/example/lms/service/service/rag/fusion/`
- 주: 기존 코드에 Single‑Flight 레지스트리가 이미 존재하여(Aspect 훅) 충돌 없이 사용 가능.

## 5) 관측/프로브 (nDCG·근거율·K‑할당)
- 엔드포인트 추가:
  - `GET /internal/soak/run` → `com.example.lms.api.internal.SoakApiController`
  - `POST /api/probe/search` → `com.example.lms.probe.SearchProbeController`
- 공통 반환 JSON에 다음 필드 포함:
  - `metrics.ndcg@10`, `metrics.evidence_ratio`, `metrics.latency_ms`, `metrics.onnx_used`, `metrics.k_alloc`

## 6) 전략 메모리(가상 포인트)
- 패키지 `com.example.lms.learning.virtualpoint` 추가:
  - `VirtualPoint.java`, `VirtualPointService.java`(LRU 256), `KAllocationPolicy.java`(간이 K 추천)

---

### 변경 파일 일람(요약)
- **추가(새 파일)**: 14개 (Self‑Ask 5, Safety 2, Gate 3, Soak/Probe 2, VirtualPoint 3, Config 1)
- **이동(경로 수정)**: 2개 (`SingleFlightExecutor.java`, `MultiQueryMergeAdapter.java`)
- **설정 변경**: `application.yml` 2개 파일에 신규 토글 추가(새 문서 섹션으로 append)

### 호환성/롤백
- 모든 신규 기능은 토글 Off 시 경로를 타지 않음.
- 기존 체인/훅 부재 환경에서도 컴파일/부팅에 영향 없도록 순수 빈/컨트롤러로 격리.

### 후속 결합 가이드(운영 환경에 맞춰)
- Self‑Ask 단계는 `RagPipelineHooks` 또는 오케스트레이션 체인 앞단에서
  `SelfAskPreprocessorHandler.preprocess(..)` 호출로 쉽게 접합.
- Preflight는 도메인 풀과 계획된 도메인 목록을 넘겨 `AutorunPreflightGate.check(..)` 호출.
- Single‑Flight는 캐시 미스 분기에서 기존 호출을 `SingleFlightExecutor`로 감싸면 충분.
- `VirtualPointService`/`KAllocationPolicy`는 질의 키 기준으로 저장/조회 후 K 값 튜닝에 사용.

