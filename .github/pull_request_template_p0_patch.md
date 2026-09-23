# feat(rag): P0 패치 5종 — DPP, FinalGate, K-Alloc, TWPM/CVaR, Planner-DSL

## 요약
- 다양성 재랭킹(DPP) → CE 호출량 절감
- Final Sigmoid Gate + Citation Gate → 환각 억제
- 동적 K-할당(소스별 Softmax) → 동일 예산 품질↑
- Tail‑Weighted Power‑Mean + CVaR → 롱테일 회수 강화
- Plan‑DSL & Planner‑Nexus(경량) → 실행계층 파라미터 주입

## 변경사항
- 신규 클래스 / 테스트 스텁 추가 (app/src/main/java, app/src/test/java)
- SearchProbeController의 잘못된 Boolean placeholder 및 필드 누락 수정

## 설정 키
```yaml
probe:
  search:
    enabled: true
    admin-token: ${PROBE_ADMIN_TOKEN:}
```

## 리스크/롤백
토글로 즉시 꺼짐 지원, planner 기본 plan-id 교체 가능
