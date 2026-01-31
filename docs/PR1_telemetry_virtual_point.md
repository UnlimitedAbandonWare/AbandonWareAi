# PR 1 — Telemetry: 9‑Matrix + VirtualPoint (NDJSON 저장)

**What**  
- `MatrixTelemetryExtractor`로 실행 요약에서 9개 코어 매트릭스(M1~M9) 추출  
- `VirtualPointService`가 고정 길이 벡터화 후 NDJSON 1줄 append (Fail‑soft)

**Why**  
- 실행별 관측 피처 축적 → A/B 튜닝·전략 추천 기반 확보

**How**  
- 순수 Java, 외부 의존성 0, 예외 상류 전파 금지  
- 샘플 사용:
```java
var mtx = new MatrixTelemetryExtractor();
var vps = new VirtualPointService();
var snap = mtx.extract(runSummaryMap);
vps.appendNdjson(requestId, snap, new File("/var/log/app/virt/virt_points.ndjson"));
```

**Flags**  
- `features.telemetry.virtual-point.enabled` (기본 false)

**Tests**  
- 벡터 길이·순서 고정, 결손 0 보정, 라인 append 확인

**Risk**  
- 파일 권한/공간 → 로테이션 디렉터리 사용 권고, 실패 시 무시(Fail‑soft)