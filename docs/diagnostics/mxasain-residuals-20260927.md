# mxasain residuals — 2026-09-27

작업: `mxasain-residuals-0927-566cac4c` · Root: `C:/AbandonWare/demo-1/demo-1/src`.

**허용된 잔여 정리를 근거 있는 API-03 재HOLD로 마감한다. cleanup 검사43/43을 정상 실행했고, snapshot 계약과 runtime partial 원인을 확인했다. 최종 제품 소스 변경은 없다. API03 목적 격리와 기존 timeout2실패는 해결 완료가 아니다.**

## 단위별 결과

| 단위 | 판정 | 근거·한계 |
|---|---|---|
| R0 | DONE | 이전 보고서/completion-r2/24 cycle43 path 대조. API01/02·WP0–8 DONE 및 QUERY/VECTOR conditional SKIP 보존 |
| R1 API03 | evidenced HOLD | SelfAsk RC와 별도 ensemble 직접 api3 사용, 공통 AOP main/auxiliary 목적 격리 없음. 관련4 suites32/32 PASS. LIVE catalog api3=true, OpenAI economy=false, Mistral=false |
| R2 timeout2 | KNOWN_BASELINE, repair deferred | 최종53개 중51 PASS/2 FAIL. 이전 baseline과 실패 이름/메시지 동일. 단순 catch 수정은 native Ollama 중복 집계 위험으로 폐기·정확히 원복 |
| R3 Verify-RAG | partial 분류 완료 | exit0, targetpartial,9 checks/1 warning/0 not-run.64개 already-exists 원인과 Hibernate wrapper가128 match로 집계 |
| R4 snapshot404 | 계약 명문화·검증 | 전역 링은 휘발성, 소유 세션의 safe typed projection/bundle은 durable. Java11/11, UI24/24 PASS; 과거 global snapshot404 LIVE 확인 |
| R5 cleanup | PASS with invocation workaround | 자식 PSModulePath 상속 제거 후 task17/17, directive26/26, Python hook8/8 PASS. 저장소 스크립트 변경은 없음 |
| R6 | DONE | 보고서·R0/R1/R3·검증/원복 자료·PROJECT_STATUS 갱신 |

## API03 정확한 HOLD 사유

1. `SelfAskPlanner.java:34,319,457`, `SelfAskProperties.java:26,33`에서 RC 보조 모델이 api3다. Bandit direct lookup은 auto 필터보다 먼저 반환한다.
2. `DiverseSamplingOrchestrator.java:121,247`의 support_alternative도 api3이므로 SelfAsk 한 곳 수정으로 전체 격리를 증명할 수 없다.
3. `LlmRouterAspect.java:140,176-178`는 factory 호출을 같은 route:primary로 처리한다. main/auxiliary 입장 구분과 보조 호출 api3 미사용 LIVE 증거가 없으므로 auto weight0을 유지한다.

[상세 R1](../../data/agent-handoff/codex-autonomy/mxasain-residuals-0927-566cac4c/R1-api03.md), [소스 hash/줄 근거](../../data/agent-handoff/codex-autonomy/mxasain-residuals-0927-566cac4c/r1-source-evidence.json). 다음 경계는 공통 입장 지점의 호출 목적 전달과 direct/auto/fallback 보조 사용 차단이다. 그 뒤 main auto 승격을 판단한다. 현재 직접 api3 선택은 카탈로그에서 유지된다. API01 실제 HTTP200/렌더 증거는 이전 full-force 보고서의 과거 증거로만 인용하며 재생성하지 않았다.

## R2 baseline과 폐기한 수정

실패 FQCN: `com.example.lms.service.ChatWorkflowStrictSingleAttemptHttpIntegrationTest`.

- `providerOwnedTimeoutBeforeGenerousRequestDeadlineStillRecordsFailure`
- `providerThrownTimeoutWhileRequestCapStillHasTimeRecordsProviderFailure`

실제 Mockito 기록은 pending phase1회이며 기대한 recordCurrentRequestRouteFailure가 없다. 원인은 early no-replay timeout bailout이 기존 health 기록보다 먼저 실행되는 것이다. [baseline 비교](../../data/agent-handoff/codex-autonomy/mxasain-residuals-0927-566cac4c/r2-baseline-comparison.json)는 두 실패의 이름/메시지가 줄 번호 외 동일함을 확인했다.

2줄 기록 추가는 일시적으로42/42를 통과했지만, OllamaNativeChatModel도 같은 실패를 기록하고 tracker는 이 지점에서 중복을 막지 않는다. 따라서 catch-only 수정은 채택하지 않았다. 기존 기대값을 낮추거나 테스트를 skip하지 않았다. 소스·테스트2개는 preimage SHA256과 정확히 일치하도록 guarded recovery했고 원복 후 같은2실패를 다시 확인했다. **42/42는 폐기된 실험 결과이며 최종 성공 수치가 아니다.**

다음 검증은 generic/native 양쪽의 한 physical attempt·한 route failure 계약이다. tracker/native 및 병행 misclassify 소유 경계를 확인한 뒤 once-only attribution을 다뤄야 한다.

첫 실험 cycle은 finish 전에 리스를 해제해 source-lease-drift HOLD를 남겼다. receipt를 조작하지 않고 새 리스·현재 postimage 검사·checkpoint apply로 원복한 `r2-revert`를 검증했다. [원복 증거](../../data/agent-handoff/codex-autonomy/mxasain-residuals-0927-566cac4c/r2-recovery-proof.json)와 두 cycle의 preimage/diff는 보존한다.

## 최종 검증과 runtime

| 실행 | 실제 결과 | handoff 증거 |
|---|---|---|
| R1 관련4 suites + timeout2 baseline |34 실행,32 PASS/2 FAIL |verify-r1-r2-baseline/junit-summary.json |
| 최종 StrictSingleAttempt + durable2 suites |53 실행,51 PASS/2 FAIL/0 skipped |verify-final-r2-r4/run.json |
| Trace UI/restore Node |24/24 PASS |verify-r4-ui/command.log |
| Cleanup task / directive |17/17 및26/26 PASS |verify-r5-task, verify-r5-directive/command.log |
| Cleanup hook Python |8/8 PASS |verify-cleanup-hook/command.log |
| 최종 Verify-RAG equivalent |exit0, verified/targetpartial |verify-r3-runtime/run.json |
| SAFE GET |catalog200: api3만 허용, 과거 global snapshot404 |live-safe-evidence.json |

최종 dev run `20260927-160549-684cdb57`, PID20924, springReused=false. compileJava/processResources, HTTP/ports/ownership, sourcesNewer=false, DevWatch armed/failStreak0을 관측했다. 서비스는 원복된 최종 소스로 실행 중이다. 첫 실험 기동은 최종 근거에서 제외한다.

H2는 테이블31/인덱스25/constraint8의 already-exists64건이다. 이전 실행과 같은 분포이며,128 regex match는 wrapper와 cause를 포함한다. DB 무결함을 증명한 것이 아니므로 partial은 유지한다. [R3 상세](../../data/agent-handoff/codex-autonomy/mxasain-residuals-0927-566cac4c/R3-verify-rag.md).

R4 계약: 전역 TraceSnapshotStore는 메모리 링이다. 소유 세션은 안전한 typed projection을 보관하며 전체 HTML/원래 이벤트 이력을 영속 보관하는 계약이 아니다. 링이 없으면 bundle events는 ring_expired_or_restarted, logs는 raw_application_logs_not_collected를 반환한다. Java11/11과 UI24/24는 이 계약을 검증한다. 이번 LIVE에서는 global404만 재확인했고 실제 소유 세션 본문/cookie는 가져오지 않았다.

R5 기본 Python→PowerShell 실행에서 fixture1건 실패를 재현했다. 실행 자식의 PSModulePath 상속만 제거하고 종료 시 원래 process 값을 복구하자17/17·26/26이 통과했다. 사용자/시스템 환경 변경 없음. 이 단일 변수 변경의 fail→pass는 관측했지만 underlying cmdlet exception은 not_observed이다. 추가 계측의0 tests/parse 실패 결과는 성공 근거에서 제외했다. 기본 상속 실행의 문제는 여전히 남으므로 같은 실행 방식을 사용한다.

수치는 겹치므로 합산하지 않는다. 최초 Java runner는 --suite 누락으로 run.json totals0이나 fresh isolated XML34개 결과를 보존/검증했다. 이후 Java 실행은 suite를 명시했다. Node/PowerShell/Python totals0은 JUnit 비사용이며 실제 command.log를 따른다. 전체 무필터 :test는 실행하지 않았다.

## 범위와 미실행

- LIVE auxiliary no-api3 증명 NOT_RUN: 목적 격리가 구현되지 않아 재HOLD. 실제 모델 생성 요청0회.
- 반복된 로그인/영상/전체 플러그인 템플릿은 이번 잔여 및 PROTO_OPEN 계약과 상충하여 실행 범위에서 제외했다.
- GLM은 계정의 모델 미지원으로 실패하여 재시도 없이 native read-only explorer로 검토했다. AWX는 정제된 실제 실패 로그에서 other를 반환했으며 원인 증명으로 쓰지 않았다.
- ApiFailureRecorder/classifier/UI 병행 파일, API01/02/WP0–8, 벡터/DB, provider guard, PROTO_OPEN을 변경하지 않았다. commit/push/remote 변경 없음.
- 최종 변경은 보고서·상태 기록뿐이다. 모든 소스 실험은 원복했으며 증거·복구 bytes를 보존한다. 삭제 후보0개.

Handoff: `data/agent-handoff/codex-autonomy/mxasain-residuals-0927-566cac4c/`. API03 목적 격리와 R2 once-only health attribution은 별개 미해결 항목이다.
