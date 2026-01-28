# src111_merge15r21 — 패치 적용 내역 (요약)

## 빌드 오류 패턴 기반 조치
- **IllegalStartOfType / stray token**: `WeightedRRF.java` 내부 다중 정의/토큰 파손 → **완전 재작성(정상화)**.
- **typo/null**: `OnnxCrossEncoderReranker.java` 의 `nul` → `null`, 전체 정리 + 동시성 게이트.
- **분할 토큰**: `RerankCanonicalizer.java` 의 `pa rts` → `parts` 등 → **동일 기능을 `com.abandonware.ai.*` 패키지로 신규 작성**.
- **ConcurrentHash Map 분할**: `UpstashBackedWebCache.java` 파손 → **미니멀 2계층 캐시 + single-flight** 재작성.
- **모델 DTO 파손**: `ContextSlice.java` getter/setter/equals/hashCode 파손 → **완전 재작성**.

빌드 로그 패턴 추출기는 `cfvm-raw/.../BuildLogSlotExtractor.java` 에 존재(패턴 코드: MissingSymbol / DuplicateClass / IllegalStartOfType / OverrideMismatch 등). (운영 시 Soak/Probe로 수집한 로그에 자동 적용). 

## 수식(LEGACY) 반영
- **Calibrated‑RRF**: RRF 점수에 *ScoreCalibrator* 훅 적용(등온회귀/로그리스틱 폴백) + **MP‑Law 소프트 클램프** 보강.
- **ONNX 2‑Pass 가드**: `OnnxCrossEncoderReranker` 동시성 세마포어(`rerank.guard.max-concurrency`) 및 topK 컷 적용.
- **Canonical URL**: UTM/gclid/fbclid 제거, 정렬 쿼리 키 생성.

## 변경 파일
- `src/main/java/com/abandonware/ai/service/rag/model/ContextSlice.java` (신규·정상화)
- `src/main/java/com/abandonware/ai/service/rag/fusion/WeightedRRF.java` (신규·정상화)
- `src/main/java/com/abandonware/ai/service/rag/fusion/RerankCanonicalizer.java` (신규)
- `src/main/java/com/abandonware/ai/service/rag/fusion/MarchenkoPasturNormalizer.java` (신규)
- `src/main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java` (정상화)
- `src/main/java/com/abandonware/ai/infra/upstash/UpstashBackedWebCache.java` (정상화)

## 설정 키 (예)
```yaml
onnx:
  enabled: true
rerank:
  guard:
    max-concurrency: 3
upstash:
  cache:
    ttl-seconds: 900
  singleflight:
    enabled: true
    wait-timeout-ms: 800
```

## 예상 효과
- **중복/파손 클래스 제거**로 컴파일 오류군(IllegalStartOfType, DuplicateClass) 다수 해소
- **Calibrated‑RRF + Canonical URL**로 중복억제·정확도 개선(오프라인 기준 추정)
- **ONNX 가드**로 폭주 방지 및 안정성 확보

