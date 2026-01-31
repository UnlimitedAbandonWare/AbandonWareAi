# PR 4 — Auto Wiring (AOP/Configuration)

본 PR은 운영 환경에 맞춘 **자동 배선**을 추가합니다. 외부 의존성 추가 없이, **Feature Flag(기본 OFF)** 과 **Fail‑soft** 방식을 통해 안전하게 롤아웃할 수 있습니다.

## 포함 내용
- `AutoWiringConfig` — Telemetry/Alias/MPC 빈 자동 등록 (`@ConditionalOnMissingBean`, Feature flag 게이트)
- `RagPipelineHooks` — 단일 진입점 `ChatService.chat(..)` AOP 훅
  - 입력 전: `TileAliasCorrector`로 별칭 교정
  - 입력 전: `MpcPreprocessor`로 voxel/blob 정규화 (기본 No‑op)
  - 종료 시: `MatrixTelemetryExtractor` → `VirtualPointService`로 NDJSON 1줄 append
  - 모든 로직 Fail‑soft (예외 삼킴)
- `tools/pr_auto_wiring.sh` — 원클릭 PR 스크립트
- `src/main/resources/application-features-example.yml` — 플래그 예시

## 플래그 (예시: 기본 OFF)
```yaml
features:
  telemetry:
    virtual-point:
      enabled: false
  alias:
    corrector:
      enabled: false
  mpc:
    enabled: false

telemetry:
  virtual-point:
    path: /var/log/app/virt/virt_points.ndjson
```

## 배선
- 포인트컷: `execution(* com.example.lms.service.ChatService.chat(..))`
- 조건: AOP 의존성 존재 시(`spring-boot-starter-aop`), 훅 활성. 플래그 OFF이면 무동작.

## 롤아웃 제안
- **DEV:** Telemetry만 ON → 파일 권한/경로 확인
- **STAGE:** Alias 10% ON (A/B), 이슈 시 즉시 OFF
- **PROD:** 단계적 확대, MPC는 실구현 PR과 동시

## 리스크 & 완화
- AOP 미활성 프로젝트: 훅 미동작, 빈만 등록 (안전)
- 포인트컷 과대적용: 단일 진입점만 후킹
- 파일 권한/공간: 경로 오버라이드 지원, 실패 시 Fail‑soft
