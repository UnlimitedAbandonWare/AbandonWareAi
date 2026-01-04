# GPT Pro 에이전트 지시서 (src111_merge15 v83)

## 목적
- 하이브리드 검색/재랭크 파이프라인을 안전하고 예산 내에서 실행.
- 증거 우선(Evidence-first) 생성: 인용 수(min 2) 충족 및 PII 제거.
- 실패/혼잡 시 그레이스풀 디그레이드(Time-Budget, Single-Flight, 1-패스 폴백).

## 런타임 규칙
1) **플랜 선택(Plan-DSL)**  
   - 입력 헤더 `X-Plan`이 있으면 해당 플랜(`recency_first.v1`/`kg_first.v1`/`brave.v1`/`zero_break.v1`)을 로드.  
   - 없으면 `recency_first.v1` 기본값.

2) **질의 복잡도 게이트**  
   - `QueryComplexityClassifier.classify(q)` → `HIGH`면 CE(교차 인코더) 경로 허용, `LOW`면 1‑패스 유지.

3) **Self‑Ask(3‑Way)**  
   - `SelfAskPlanner.plan(q)` → BQ/ER/RC 3개 하위질의 생성. 웹/벡터/KG 회수에 분배.

4) **하이브리드 회수 & 융합**  
   - (옵션) `Bm25Retriever.search` 결과를 기존 Dense/웹 결과와 **보수적 RRF**로 결합.
   - `ScoreCalibrationService.calibrate`로 점수 보정 후 상위‑K 결정.

5) **재랭킹(2‑패스 보호)**  
   - `OnnxRerankerGuard.maybeRerank(cands, timeBudget, fn)` 사용.  
   - 남은 시간이 300ms 미만 또는 동시성 초과면 **CE 스킵**.

6) **증거/품질 게이트**  
   - `CitationGate(min=2)` 통과 못 하면 **생성 차단** 또는 요약 전환.  
   - `FinalQualityGate(k=6, x0=0.5, τ=0.9)`에서 거부되면 “정보 부족” 텍스트 반환.  
   - 응답 텍스트는 `PIISanitizer.sanitize` 적용.

7) **오버드라이브(선택)**  
   - `OverdriveGuard.shouldOverdrive`가 true면 → `ExtremeZSystemHandler.burst`/`AngerOverdriveNarrower.narrow`로 후보군을 단계 축소(48→24→12→6).

8) **스탬피드 방지**  
   - 동일 키 요청은 `SingleFlightExecutor.run(key, task)`로 병합.

## 출력 규칙
- 모든 주장에는 출처(도메인/링크/타임스탬프)를 2개 이상 포함.
- 표/리스트는 상위‑K(3~10)만 노출, 나머지는 “추가 근거 있음”으로 축약.
- 사람/전화/이메일 등 PII는 마스킹.

## 구성 토글(예시)
- `guard.citation.min-sources=2`
- `runtime.time-budget.ms=4000`
- `onnx.guard.max-concurrency=4`
- `retrieval.bm25.enabled=true`
- `fusion.score.calibrate.enabled=true`

> 본 지시서는 Jammini 메모리의 운영 항목(Soak/Probe, 페더레이티드 벡터, Upstash 캐시/레이트리미트, 동적 리트리벌 체인, RRF/CE, 도메인 화이트리스트, SSE 등)과 결합하여 사용하도록 설계되었습니다.
