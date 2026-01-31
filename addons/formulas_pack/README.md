# Orchestration Formula Pack (curated)

이 묶음은 `main.zip`에 바로 합쳐 넣어 사용할 수 있도록, 오케스트레이션 품질에 핵심적인 **수식/알고리즘 구현 Java 파일**만 추려서 `addons/formulas/java/` 아래에 정리했습니다.

## 포함 항목 (요지)
- **WeightedRrfFuser** — 다중 리트리버 결과 융합(보수적 안정화).
- **WeightedPowerMean** — 점수 융합의 일반화 평균(WPM) 기반 강화.
- **SoftmaxUtil** — 소프트맥스/온도 기반 확률화·K-할당.
- **FinalSigmoidGate** — '피니쉬·핑거' 시그모이드 품질 게이트(pass9x).
- **MpLawNormalizer** — MP-Law(마르첸코–파스투르) 스펙트럼 정규화.
- **IsotonicCalibrator** — 이종 스코어 확률 스케일 보정.
- **LowRankZcaWhitening + WhiteningStats** — 저랭크 ZCA 정규화.
- **CvarAggregator, RiskKAllocator** — 꼬리-집중(CVaR) 융합과 위험 조정 K-분배.
- **BodeClamp** — 과민 신호 감쇠/안정화 클램프.
- **DppDiversityReranker** — DPP 기반 다양성 강화 재랭킹(1→2 패스 사이).

## 추천 배선 (Resume Alignment)
Hybrid(BM25+Dense) → **WeightedRRF** → **WPM** → **MP‑Law** → **Isotonic** → (1‑pass 출력)
→ **DPP 다양성** → (2‑pass ONNX Cross‑Encoder) → **CVaR/Tail WPM** → **BodeClamp** 
→ **Final Sigmoid Gate**. K‑할당은 **SoftmaxUtil**/RiskKAllocator.

> 원본 위치·ZIP은 각 파일 상단 헤더에 기록되어 있습니다.
