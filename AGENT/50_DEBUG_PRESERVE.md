# 50_DEBUG_PRESERVE.md — 디버깅·진단·트레이스 보존 정책(Agent‑Safe)

본 문서는 에이전트 모드가 **디버깅/진단/트레이스 코드를 '소음 감소' 명목으로 삭제·축소**하지 못하도록 하는 보존 규칙과 가드 예시를 제공합니다.  
원칙: **게이팅(gating=branch‑selection)으로 끄고 켜되, 코드는 지우지 않는다.**

## 1) 불가침(Do‑Not‑Remove)

- 패키지/클래스(삭제·인라인·정적 제거 금지)
- `com.example.lms.debug` : PromptDebugLogger, PromptMasker
- `com.example.lms.diagnostics` : RetrievalDiagAspect
- `com.example.lms.service.diagnostic` : DiagnosticsDumpService
- `com.example.lms.service.trace` : TraceHtmlBuilder
- `com.example.lms.telemetry` : ConfigKeysLogger, DefaultSseEventPublisher, LoggingSseEventPublisher, SseEventPublisher, TelemetryService
- `com.example.lms.trace` : EbnaDetector, LlmTraceAspect, PromptTraceAspect, SafeRedactor, SearchTraceAspect, TraceContext, TraceEvent, TraceLogger
- 설정 파일
  - `src/main/resources/logback-spring.xml` 내 `TRACE_FILE` appender 및 `TRACE_JSON` 로거

- 로그 호출
  - `log.debug(..)`, `log.trace(..)` 삭제 금지. 레벨/프로퍼티로 제어.

## 2) 게이팅 정책(끄고 켜는 법, 지우지 않기)

- 프로퍼티
  - `lms.trace.enabled` (기본 true)
  - `lms.trace.http.enabled` (기본 false; 켜면 WebClient 에러 응답 비소모형 로깅 활성)
  - `lms.trace.sample` (0.0–1.0 샘플링 비율)
  - `lms.debug.enabled` (기본 false)
  - `lms.debug.dump-prompts`, `lms.debug.mask-secrets`, `lms.debug.max-bytes`
- 프로파일: 필요 시 `@Profile("diag")` 등으로 추가 게이팅 허용
- 로깅 레벨: `logging.level.com.example.lms=INFO`(기본). 문제 재현 시 일시적으로 `DEBUG/TRACE`.

## 3) AGENT_KEEP 마커

- 파일/클래스/메서드 상단 주석: `// AGENT_KEEP: debug`
- 또는 어노테이션: `@com.example.lms.annotations.AgentKeep`

## 4) CI/훅 가드(권장)

- GitHub Actions: 보존 패키지 내 클래스 수>0, `logback-spring.xml` 존재 확인
- pre‑commit: 보존 경로의 삭제/이동(D/R) 차단

## 5) 스모크 테스트(권장)

- `DebugPreserveSmokeTest`가 다음 빈 존재를 확인:
  - `com.example.lms.web.TraceFilter`
  - `com.example.lms.trace.RequestIdHeaderFilter`
  - `com.example.lms.debug.PromptDebugLogger`

> 위 정책은 코드를 **기능 플래그로 게이팅**하여 운용 소음을 낮추면서도, 추후 문제 분석을 위한 자산을 항상 보존하는 것을 목표로 합니다.
