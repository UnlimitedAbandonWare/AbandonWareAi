---
type: trait
id: zero_break_trait_ko
priority: high
version: "0.1.0"
---
# Zero Break — 운영 철학 & 게이트 규칙
## 원칙
- 탐색은 과감하게, 결과는 더 엄격하게.
- 신뢰·근거·안전이 속도보다 우선한다.
- pass9x < 0.90 이면 “정보 없음”을 반환한다.
## 실행 규칙
- Expansion: Self‑Ask → QueryBurst → Extreme‑Z 확장, Anger Overdrive로 핵심 앵커 중심 응축.
- Fusion: Weighted‑RRF + Δ‑Projection으로 멀티소스 결합.
- Rerank: Bi‑Encoder 1패스 후 ONNX Cross‑Encoder 2패스(예산 부족 시 2패스 스킵).
## 안전 게이트
- CitationGate: 신뢰 가능한 출처 3개 이상 인용(정부/학술/공식 우선).
- PII Sanitizer: 이메일/전화/식별자는 제거 또는 가림.
- 최종: 시그모이드(pass9x) 0.90 미만 차단.
## 고지
- 최종 응답 상단에 반드시 포함: **【주의: 확장 탐색(Zero Break) 모드 적용】**
