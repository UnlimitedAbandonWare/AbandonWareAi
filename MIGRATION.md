# Migration Notes
본 노트는 기존 RAG 파이프라인에 Zero Break를 **비침투적**으로 연결하는 절차입니다.

## 1) Plan DSL 배치
- `ops/zerobreak/plans/*.yaml` 을 리소스로 패키징하거나 런타임 경로로 마운트합니다.
- `PlannerNexus`가 `X-Plan-Id` 또는 헤더/정책에 따라 해당 YAML을 로드합니다.

## 2) 인터셉터 추가
- Spring MVC: `RuleBreakInterceptor` 를 `WebMvcConfigurer#addInterceptors`에 등록.
- 등록 순서: RequestIdHeaderFilter(있다면) → RuleBreakInterceptor → 기타.

## 3) 게이트 연결 지점
- **Preflight**: 리트리벌/행동 직전 (권한·화이트리스트·예산 확인)
- **CitationGate**: 합성 직전(출처 수·신뢰도 점검)
- **PIISanitizer**: 최종 출력 직전
- **FinalSigmoidGate**: 오케스트레이션 품질 승인 전 마지막 단계

## 4) 토글/프로필
- 실행: `--spring.profiles.active=zerobreak` 또는 앱 설정에서 `zerobreak.enabled=true`

## 5) 실패-허용(Graceful) 설계
- 게이트/인터셉터는 항상 **소거적**이어야 합니다. 조건 불충족 시 '안전 중지'·'정보 없음'으로 종료.
