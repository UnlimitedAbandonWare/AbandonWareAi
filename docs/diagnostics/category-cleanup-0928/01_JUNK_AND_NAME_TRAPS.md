# 01 · C1 — JUNK & NAME-TRAPS 재검증 목록

Evidence: `scan-report.json` → `c1_junk[]` (12 rows). 모든 행은 라이브 트리에서 재확인됨.
`verdict`는 지도 판정, `proof`는 실측 근거(파일 내용·참조 검색·테스트 핀).

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| J01 | `main/java/com/example/lms/service/ChatService_old.java` | QUARANTINE_CANDIDATE | 이름이 "레거시 ChatService"라 패치 대상으로 오인 | 주석 전용 shim — 클래스·import 없음, 선언 패키지 없음. 참조 0. | 인덱스 등록 + 상단 배너 주석(승인 시) | unowned |
| J02 | `main/java/com/example/lms/service/ChatService_copy.java` | QUARANTINE_CANDIDATE | "_copy" 백업본처럼 보이나 실제로는 빈 shim | 주석 전용 shim — J01과 동형 | 동일 | unowned |
| J03 | `main/java/com/example/lms/service/rag/test_mod.java` | DELETE_CANDIDATE | 파이썬식 이름(`test_mod`)의 더미 클래스 — 테스트/모듈로 오인 | `class A { int x; }`만 존재, 기본 패키지, 참조·테스트 핀 0 | 승인 시 삭제(3중 증명 충족) | unowned |
| J04 | `main/java/com/example/lms/service/rag/EnhancedSearchService.java` | QUARANTINE_CANDIDATE | `service/rag/` 경로인데 선언 패키지 `com.example.lms.service` — 위치≠패키지 | 파일 헤드 + 선언 불일치; 활성 코드가 `service.rag.*`를 참조하지 않음 | 인덱스; 이동/삭제 승인 필요 | unowned |
| J05 | `main/java/com/example/lms/service/patch/ChatOrchestratorPatch.java` | KEEP | "patch" 디렉터리라 정크로 오인 — 실제는 조건부 서비스 | `@Service` + `@ConditionalOnProperty(legacy.chat-orchestrator-patch.enabled, matchIfMissing=false)`; 선언 `com.example.lms.service` vs 경로 `service/patch` 불일치; **testPinned** | INDEX_ONLY — "조건부 빈, 기본 OFF" 배너 | unowned |
| J06 | `main/java/com/example/lms/service/config/JpaBatchConfig.java` | KEEP | `service/config` 경로 + 선언 `com.example.lms.config` 불일치 → "방치 설정"으로 오인 | `@Configuration` 활성 빈 — 스캔 패키지 내부라 로드됨 | 경로↔패키지 주석 보강(승인) | unowned |
| J07 | `main/java/com/example/lms/config/MatrixConfig.java` | DELETE_CANDIDATE | `config` 패키지 + Config 이름이라 설정 클래스로 오인 | 파일 0바이트/내용 없음 — 컴파일만 되는 유령 | 승인 시 삭제 | unowned |
| J08 | `main/java/com/example/lms/service/LangChainChatService.java` | QUARANTINE_CANDIDATE | `ChatService` 계열 이름 — 활성 체인 서비스로 오인 | `package` 선언 1줄만, 클래스 없음 | 인덱스; 삭제 승인 필요 | unowned |
| J09 | `main/java/com/example/lms/service/RagConfig.java` | QUARANTINE_CANDIDATE | `RagConfig` 이름 = 설정 파일로 오인 | `package` 선언 1줄만 | 동일 | unowned |
| J10 | `main/java/com/example/lms/service/RagRetrievalService.java` | QUARANTINE_CANDIDATE | RAG 검색 서비스로 오인 (실제 활성 RAG는 다른 패키지) | `package` 선언 1줄만 | 동일 | unowned |
| J11 | `main/java/com/example/lms/service/trace/TraceHtmlSoakWebKpiCopyCalloutRenderer.java` | KEEP | 이름에 "Copy" 포함 → 복사본/중복으로 오인 | 실제 package-private 렌더러 본체 — testPinned | 이름 주석 또는 인덱스 등록 | unowned |
| J12 | `main/java/service/rag/rerank/DppDiversityReranker.java` | DELETE_CANDIDATE | `main/java/service` 루트 패키지, "moved to backup" 주석 | 파일 자체가 "backup으로 이동, 경로 유지를 위해 남김" 주석 + 실제 클래스 없음 | 승인 시 삭제 (자기 서술 있음) | unowned |

## ChatService 패밀리 이름 함정 (보조 지도)

| path | verdict | 실태 |
|------|---------|------|
| `service/ChatService.java` | KEEP | 활성 `@Service` |
| `service/ChatServiceImpl.java` | KEEP | 활성 구현 `@Service` |
| `service/AbandonWareAi_ChatService.java` | INDEX_ONLY | package-private 별칭 마커 — 참조 추적 후 판정 |
| `service/legacy/ChatServiceLegacy.java` | KEEP | testPinned — `LegacyRetrySleepBreadcrumbContractTest`, `SafeChatMessageLogTest`가 경로·내용 기대 |
| `service/patch/ChatServiceLegacyPatch.java` | KEEP | testPinned — `ZombiePurgeContractTest`가 "active/deprecated 파일 위치" 계약 검증 |

> 에이전트 주의: `ChatServiceLegacy*`·`ChatOrchestratorPatch`는 "레거시/패치" 이름이지만 계약 테스트가 잠근 파일이다. 삭제하면 테스트가 깨진다.

## 3중 증명 상태 요약

- DELETE_CANDIDATE(3증명 충족): J03, J07, J12
- QUARANTINE_CANDIDATE(증명 진행/패키지 불일치): J01, J02, J04, J08, J09, J10
- KEEP(활성 또는 testPinned): J05, J06, J11 + ChatService 패밀리 전원
