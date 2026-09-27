# 선정과 교차 검토

현재 스킬의 설계 이유를 확인할 때만 읽는다. 매 디버깅에서 이 비교를 다시 수행하지 않는다. 아래 우선순위는 로그·증상 재현·가설 비교·예외·복구·소스 검증 작업에 대한 예상 실용성이다. 실제 호출 빈도나 측정된 생산성 순위가 아니다.

## 우선순위 10개

| 순위 | 스킬 | 선택 근거 |
|---:|---|---|
| 1 | superpowers:systematic-debugging | 재현·정상 비교·원인 검사에서 최소 수정으로 이어지는 기본 순서 |
| 2 | demo1-invisible-eye | 숨은 조건, 평가 시점, 실행과 인과의 구별, 경쟁·복합 가설 |
| 3 | demo1-debugging-with-two-tools | 최초 실패 경계와 집중된 관찰·검증, source/log identity |
| 4 | superpowers:test-driven-development | 의미적 RED와 수정 후 회귀 검증 |
| 5 | superpowers:verification-before-completion | 완료 주장과 현재 증거의 일치 |
| 6 | scoped-blocker-recovery | 실제 의존 작업만 보류하고 독립 작업 계속 |
| 7 | demo1-generating-falsifiable-hypotheses | 주장·제약 출처와 반증 가능한 후보 |
| 8 | demo1-source-edit-three-way-preflight | 기존 단일 소스 수정 진입점 |
| 9 | demo1-verifying-evidence-coherence | 모순·미결정·일관성 판정 |
| 10 | demo1-triangulating-counter-evidence | 필요한 단계의 실행·재사용·생략·보류 |

상위 세 개는 원인 조사, 작동 조건 구별, 집중 주기를 담당한다. TDD·완료 검증은 후속 의무이며 기존 소스 심사는 단일 진입점이다. 원본 스킬을 병렬 또는 순차로 전부 실행하는 설계가 아니다.

## 양방향 질문과 해결

| 방향 | 질문 | 채택한 규칙 |
|---|---|---|
| 원인 조사 → 숨은 조건 | 언제 탐색을 멈추는가? | 현재 결정을 구별할 최소 검사 또는 정확한 누락 증거 하나 |
| 숨은 조건 → 원인 조사 | supported이면 수리 가능한가? | 의미적 재현·인과·권한은 각각 확인 |
| 원인 조사 → 두 도구 | 두 슬롯 이후 필수 검증은? | acceptance를 진단과 분리하고 pending으로 유지; 새 실패를 가정하지 않음 |
| 두 도구 → 원인 조사 | 매번 전체 검사하는가? | 명시된 완료 조건과 영향받은 경계만 확장 |
| 숨은 조건 → 두 도구 | 복수·복합 가설을 잃지 않는가? | 후보들을 보존하고 활성 검사 하나로 모든 후보 상태 갱신 |
| 두 도구 → 숨은 조건 | 단계별로 검사가 늘어나는가? | 기존 증거를 분류하고 가장 이른 결정 관련 경계만 검사 |

원본 Two Tools의 성공 패턴은 영구 메모리 저장 권한이 아니다. 외부 진단 helper도 필수 의존성이 아니다. 원본에서 가져온 유용한 원칙을 위 결합 규칙과 현재 저장소 권한 아래에서 사용한다. 원본 파일을 변경하지 않고 각 역할의 출력·입력 경계만 연결했다.

## 근거와 한계

독립적인 기존 스킬 조합 사례에서 필수 UI 검증을 새 실패 계층으로 표현한 판단이 관찰됐다. 통합 계약은 `acceptance=pending`을 명시해 이 잘못된 분류를 방지한다. 이 관찰은 단일 합성 평가이며 전체 실사용 성능을 측정한 결과가 아니다.

전체 후보별 한계, 실제 비교 파일, 파일 해시, 여섯 합성 사례와 외부 연구 출처는 [조사 보고서](../../../../data/agent-handoff/skill-research/20260914-evidence-debugging/research.md)에 있다. 이 보고서는 설계 출처이며 스킬 실행에 필요한 의존성이 아니다.

- [Anthropic: Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents), 2025-11-26: 맥락 전달·성급한 완료·실제 사용 흐름 검증에 관한 경험. 특정 하니스의 커밋·재시작 절차를 이 저장소의 권한으로 옮기지 않는다.
- [SWE-Bench+: Enhanced Coding Benchmark for LLMs](https://arxiv.org/abs/2410.06992), 2024-10-10 수정: 통과한 테스트가 패치의 의미적 정확성을 충분히 검증하는지 점검할 근거. 논문의 실패율을 이 스킬이나 현재 모델 성능으로 일반화하지 않는다.
