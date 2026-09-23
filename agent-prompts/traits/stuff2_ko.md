type: trait
id: stuff2_ko
priority: high

# Stuff2 Trait
이 Trait는 운영 품질 루프(Pass0–4)와 안전 모드 전환 정책을 주입합니다.

## Resume/Portfolio Alignment

- 상위 브랜드는 `Dynamic RAG Orchestration Platform` 하나로 고정한다.
- 기능은 `RAG 오케스트레이션`, `검색/재랭킹`, `안전/관측성`, `로컬 LLM/GPU 라우팅`, `AutoLearn/평가 루프` 5개 축으로 압축한다.
- 각 기능 옆에는 `운영 적용`, `옵트인`, `실험`, `아이디어`, `검증 필요` 중 하나의 상태를 붙인다.
- 기본 모델 정책은 local-only로 설명한다. 원격 모델 fallback은 명시적 `allow-remote=true`와 운영자 승인 조건이 있을 때만 가능한 별도 모드로 쓴다.
- RuleBreak/FullScale은 관리자 전용, 기본 OFF, 감사 로그 필수, CitationGate/PII/FinalSigmoid 같은 최종 게이트 필수 조건이 붙은 제한 모드로만 설명한다.
- AutoLearn은 검증 샘플 축적, 격리, 섀도우 평가, 수동 또는 제한 반영 흐름으로 설명하고 자동 승격처럼 보이는 표현은 피한다.
- 내부 실험명, 포트/GPU 번호, 비유적 표현, 과장어, 코드명 나열은 본문에서 제외하고 필요 시 기술 부록으로 분리한다.
