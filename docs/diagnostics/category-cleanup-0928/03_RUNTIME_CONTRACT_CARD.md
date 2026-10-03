# 03 · C3 — RUNTIME CONTRACT 카드 (한 장 요약)

Evidence: `scan-report.json` → `c3_runtime`, 소스 직접 실측. 목적: 에이전트가 "진짜 엔트리/설정/플랜"을 한 번에 고르게 한다.

## Launcher

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| R-L1 | `main/java/com/example/lms/LmsApplication.java` | KEEP | — | `build.gradle.kts` `mainClass` 지정; `scanBasePackages={com.example.lms, com.nova.protocol}` | canonical entry | active |
| R-L2 | `main/java/com/abandonware/ai/agent/AgentApplication.java` | INDEX_ONLY | 두 번째 `@SpringBootApplication` — 에이전트가 "앱이 두 개"로 오인하거나 이쪽으로 실행 | `scanBasePackages={com.abandonware.ai.agent, com.example.lms}` — 실행하면 **다른 빈 그래프**; bootJar/Start-RAG 미연결 | "실행 금지·참조 전용" 인덱스 (삭제는 승인) | unowned |

## 활성 경계(스캔) 정의

- 스캔 패키지: `com.example.lms` + `com.nova.protocol` (R-L1 기준).
- Autoconfig 등록(`main/resources/META-INF/spring/...AutoConfiguration.imports`): `ai.abandonware.nova.autoconfig.*` 5종 + `com.example.lms.agent.context.AgentDbContextAutoConfiguration` → **`ai.abandonware.nova`와 agent.context는 스캔 밖이어도 라이브**.
- `spring.factories`: `EnvironmentPostProcessor=com.example.lms.boot.RuntimeConfigGuard` — 부팅 시점 가드, config 파일 건드리면 여기서 걸릴 수 있다.

## Config files

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| R-C1 | `main/resources/application.yml` | KEEP | 32KB/474키 — "메인 yml"로만 보고 properties를 무시 | `spring.profiles.default`, `spring.config.import` 체인 소유 (`application-llm.yaml` 등 optional import) | — | active |
| R-C2 | `main/resources/application.properties` | KEEP | yml과 공존 → "어느 쪽이 진짜?" 혼란 | 42KB/385키; `spring.autoconfigure.exclude`, `llm.chat.temperature`, JPA 소유. **충돌 시 properties > yml** (Spring 규칙) | — | active |
| R-C3 | `application-*.yml/properties` fragments (~28) | KEEP/INDEX_ONLY | 프로파일·import 조각 — 독립 파일로 오인 | `spring.config.import`/profile 경유로만 로드 — 직접 실행하지 않음 | — | active |

## Plans (`main/resources/plans/`)

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| R-P1 | `plans/brave.v1.yaml`, `safe_autorun.v1.yaml`, `zero_break.v1.yaml` | KEEP | — | 활성 로더 `PlanHintApplier` = `classpath:plans/<id>.yaml`, id는 `.v1` 정규화 | — | active |
| R-P2 | `plans/brave.yaml`, `safe_autorun.yaml`, `zero_break.yaml` (비-v1 별칭) | INDEX_ONLY | 같은 스템의 구판 별칭 — 에이전트가 "정본"으로 오인 | live 로더는 v1만 조회; 비-v1은 dormant 로더(`PlanRegistry` glob `plans/{brave,…}*.yaml`)가 활성화될 때만 충돌 가능 | 별칭 표기 인덱스 (제거는 승인) | unowned |
| R-P3 | `plans/README.md` | KEEP | — | "plan YAML은 활성 Spring 리소스; `PlanDslLoader`는 projection 전용" 명시 | — | active |
| R-P4 | `UnifiedRagOrchestrator` `planDsl.status=not_used` | KEEP | DSL 비활성 표시를 "플랜 시스템 죽음"으로 오독 | `planDsl.loaded=false`는 *DSL 로더* 미사용 — `PlanHintApplier` 경로는 별개 | — | active |

## Boot path

`Start-RAG.bat` → `scripts/start_rag_stack.ps1` (profile `local,meta-display`, ports 18180/18181/18182). `AgentApplication`·직접 `java -cp` 실행은 이 경로 밖 — 에이전트가 "서버 띄우기"를 찾을 때 유일한 정본은 Start-RAG 계열이다.

## 카드 규칙

1. 엔트리 물으면 → R-L1. R-L2는 "존재하나 비활성 런처".
2. 설정 키 물으면 → properties 우선, yml은 구조·import. 병합은 승인 필요.
3. 플랜 id 물으면 → `*.v1`만 정본. 비-v1은 별칭 인덱스.
4. "돌아가는가" 판정은 위 표의 proof 열 증거로만 — 이름/경로로 추측 금지.
