# trace983: 취소·보조 응답 비노출·부정 판정 보존 수정, 원사건은 PARTIAL

Root: `C:\AbandonWare\demo-1\demo-1\src`
Task: `codex-verifier-cancellation-d6cad5e8`

## 동일 요청에서 확인한 사실

requestHash `5f8510ef244c`, session263/hash `4be84111a613`, runHash `e368b22948c9`.
제공 epoch1791194463.137은 2026-10-05 10:01:03.137 UTC / 19:01:03.137 KST다.

실행 로그: `var/rag-launcher/20261005-183551-56f05a98/chat-ui-vibe-listener-18180.out.log`.

| 단계 | 실제 기록 |
|---|---|
| 요청 시작 | L12189, 18:59:09.009 KST |
| 검색→프롬프트 | L12261 candidate7/promoted7/distinct locator6, evidence/citation gate 모두 true. L12262 web7/citable7/RAG0 |
| 초안 모델 호출 | L12263–12335, 35,169ms. OAuth HTTP200 및 SSE delta 관측. 모델 식별 hash는 chatgpt-oauth:gpt-5.6-sol과 일치하나 provider 최종 응답 모델·완료를 확정하지 않음 |
| 후속 호출 | logicalCall6–10이 30,016/6,016/12,013/6,001/12,014ms timeout, 합계66,060ms. 모든 역할을 JUDGE로 단정하지 않음 |
| 로컬 호출 | L12450–12451에 같은 요청의 smtek/Qwen3.8-27B:Q3_K_XL timeout 명시. qualifier judge의 설정과 관측 native maxTokens256의 동일 호출 여부 미확정 |
| 최종 | L12481 characters84/evidence2. L12487 existing_release_guard_hold, L12488 runtime_lineage_missing, L12489 verification_outcome_missing. L12492 boundaryStage=llm/catch/reactiveexception |
| 최초 사용자 SSE | L12491, 19:01:03.111 KST, 요청 시작 후114.102초 |

`worker_exited`는 scope=model_call/stage=chat_draft이고 cancellationRequested=false다. 전체 run 종료나 사용자 취소 ACK가 아니다. 해당 run에서 cancel 경로 hash·LLM_TRANSPORT_CANCEL·cancel_accepted는 관측되지 않았다. 이는 사용자가 중지 버튼을 눌렀다는 진술을 부정하지 않으며 서버 도달/수락 시점을 아직 모른다는 뜻이다.

처음 검색이 실패했다거나 OAuth가 전혀 응답하지 않았다는 설명은 위 기록과 맞지 않는다. 최초 근거 승격 이후 검증·후처리 timeout과 release hold가 관측된 손실 경계다. promoted7은 공식 본문 추출·동일 인물·주장별 entailment 검증까지 완료했다는 증거는 아니다.

## 수정한 범위

세 검증기는 취소/종료 예외를 일반 실패로 삼켜 빈 응답·heuristic/unknown으로 바꾸고 후속 검증을 호출할 수 있었다. 합성 RED로 이 결함을 확인했다. 실제 trace983에서 취소 ACK가 발생했는지는 별도 미확인이다.

- `TimedChatModelCaller.java:55`: 기존 공용 호출 클래스에 bounded cause-chain guard 추가. 취소·인터럽트는 원본 cause 없는 CancellationException으로 전파하고 인터럽트 플래그를 복원한다. 보조 terminal은 본문·모델·응답ID·사용량·providerCode·원본 cause 없이 고정 auxiliary_verification_terminated/failed로 종료한다. primary 모델의 terminal 경로는 변경하지 않는다.
- `FactVerifierService.java:157,456,475,479`: 시작/호출/응답 직후 취소 검사 및 내부·외부 catch 전파.
- `verification/ClaimVerifierService.java:86,171,366,405,420`: 추출→판정과 외부 fail-soft가 취소를 재흡수하지 않도록 보존.
- `verification/FactStatusClassifier.java:66,102,180,213,221`: 취소를 heuristic PASS/일반 judge 실패로 바꾸지 않도록 보존.
- 신규 `src/test/java/com/example/lms/service/VerifierCancellationBoundaryTest.java`: 30개 합성 사례. 직접/중첩 취소, 중첩 인터럽트, terminal, 일반 장애 대조, 사전 인터럽트0호출, 정상 응답 직후 인터럽트1호출/후속0.

제품 Java 수정은 기존 네 파일에 한정했다. 추가로 FactVerifierService.VerificationState의 명시적 rejected/insufficient가 앞선 인프라 fail-soft에 의해 unknown으로 덮이지 않게 했다. accept는 unknown→명확한 negative→기존 fail-soft→positive 순서로 처리한다. negative와 unknown 모두 메모리 저장 금지를 유지한다. 기존 줄바꿈을 보존했고 기존 FactVerifierDetailedOutcomeTest를 14개 사례로 갱신했다. 기존 메서드·프로퍼티·모델 경로·출처 규칙은 유지했다. chat.js/Workflow/controller/모바일 HTML·CSS는 수정하지 않았다.

## 검증

- RED receipt `data/agent-handoff/coop-verify/receipts/cv-6235cbbe22da4381.json`: 21개 중 취소/terminal 전파15개 예상 실패, 일반 장애 대조6개 통과. exit1, inputDrift=false, interferingWriters=[] . cycle01은 실패 그대로 기록하고 신규 테스트만 rollback했다.
- 1차 GREEN `cv-16123d7aa356454f`: 10개 suite118/118, 실패/오류/skip0. cycle02 verified. 이 결과는 뒤의 원본 줄바꿈 복원 및 신규6개 테스트 이전 입력에 관한 이력이다.
- 중간 GREEN `cv-170e21784458462a`: 10개 suite124/124, 실패/오류/skip0. 모바일 writer 해제 후 실행했고 cycle03 verified. 이후 발견한 보조 terminal 원문 노출은 이 테스트만으로 잡히지 않았다.
- 공개 경계 RED `cv-2e234425e76d415e`: 27개 중6개 실패. 실제 ChatResponseDto.terminal()에서 합성 auxiliary-only text가 공개 content가 되는 것을 확인했다. cycle04는 이 재현 테스트 변경만 rollback.
- 비노출 GREEN `cv-c310b7ec2a1944da`: 10개 suite125/125. 본문과 모델/responseId/token/providerCode 제거 및 terminal 내부 취소 우선순위를 검증했다.
- 최종 경계 RED `cv-bc2e0303c8f44604`: 44개 중4개 실패, 오류/skip0. 부정 판정→unknown 마스킹3개와 CancellationException의 cause를 통한 auxiliary terminal 재노출1개를 확인했다. InterruptedException 역방향 대조는 기존에도 PASS였으며 실패로 세지 않는다. cycle06은 재현용 두 테스트 변경만 rollback.
- 최종 GREEN `cv-c71150ab6b26447b`: 아래 검증 JSON에 receipt와 XML 개수를 직접 기록했다. 기록 시 VERIFIED_PASS/exit0와 inputDrift=false/interferingWriters=[]를 확인했다. 전체 test suite의 건강 상태로 확대하지 않는다.

공통 명령 접두사:
`cmd /d /c gradlew.bat --offline --no-daemon -Pawx.splitBuildOutputs=true -Pawx.buildHostId=codex-entity-release --project-cache-dir build/codex-entity-release-cache`

RED는 `:test --tests com.example.lms.service.VerifierCancellationBoundaryTest`.
GREEN은 `:compileJava :test`와 다음 클래스 각각의 `--tests`다:

1. `com.example.lms.service.VerifierCancellationBoundaryTest`
2. `com.example.lms.service.FactVerifierDetailedOutcomeTest`
3. `com.example.lms.service.verification.ClaimVerifierOutcomeValidationTest`
4. `com.example.lms.service.verification.FactStatusClassifierBudgetTest`
5. `com.example.lms.service.verification.FactStatusClassifierMalformedLabelTest`
6. `com.example.lms.llm.JudgeCallObservationTest`
7. `com.example.lms.service.verification.JudgeVerificationObservationTest`
8. `com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest`
9. `com.example.lms.service.chat.FinalizedMemoryPersistenceCancellationFenceTest`
10. `com.example.lms.service.SourceCredibilityReleaseRegressionTest`

## 원사건에 남은 경계

일반 judge timeout을 실제 내용 모순이나 사용자 취소로 바꾸지 않았다. 취소 전파 수리가 원사건의 보류 응답을 해결했다고 주장하지 않는다. 해당 실패의 callback/예외 원인과 실제 모델별 역할 계보는 일부 미확인이다.

Devin 보고서 `PASTE_CODEX_verifier-failsoft-release_20261005.txt`는 19,131B/SHA256 `8b6e0b617ddbddeeed7bb121227b0c4eac765df17fe470f1227b7641298e2f17`로 전체 대조했다. unknown이면 초안 전체를 공개하자는 부분은 최신 사용자의 지원된 주장만 부분 공개 조건보다 넓어 적용하지 않았다.

현재 DetailedVerificationResult는 본문/상태/불리언만 제공한다. 앞선 인프라 실패가 뒤의 명시적 부정 판정을 unknown으로 덮던 결함은 이번 수정과 RED/GREEN으로 해결했다. unknown 자체에 지원된 주장별 증거가 추가된 것은 아니다. verifiedAnswer라는 이름도 실패 시 원문 draft를 담으므로 증명이 아니다. 기존 EvidenceAnswerComposer는 키워드·주제 일치 기반이며 동일 인물/기관·상충 문서·주장별 locator 연결을 보증하지 않는다. 따라서 지원된 부분의 typed 증명 없이 release gate를 완화할 수 없다. 기존 unknown unsupported draft 차단, strict evidence, owner, 취소, 장기기억 금지 계약을 유지했다. 새로운 인물 전용 규칙이나 API/DB/schema를 만들지 않았다.

이 cancellation 결함은 수정 전 소스에서 재현됐다. 오늘 refactor가 새로 만든 회귀인지 과거부터 있었는지는 별도 이력 증거가 없어 미확정이다.

## 서버 반영

11:00:29 UTC 재확인에서도 latest run은 `20261005-190353-1393c3a9`, 기록상 Java PID45084, READY10:08:24 UTC였다. descriptor에 JVM classpath/Java source pin/build host가 없어 loaded class는 UNKNOWN이다. 10:38 시점의 별도 비교에서 일반 build의 두 클래스는09:40, isolated build는10:34로 서로 달랐다. 이후 오프라인 컴파일 성공도 실행 중 JVM의 class 교체 증명은 아니다. 이전 SourceAnalyzer 반영 증거는 이번 verifier 반영 증거가 아니다.

재시작·HTTP·외부 모델·유료 API·DB행 조회·Git mutation은 모두0. 이전 CIM/NetTCP 접근 거부 재시도/우회0. 실제 공개 UI 재검증은 NOT_RUN이다.

## 출처 URL 식별자 추가 대조

부모가 전달한 과거 설계의 단서를 현행 소스와 읽기 전용으로 대조했다. `main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java`의 `sanitizePublicUrl`은 L1187–1209에서 모든 query/fragment를 제거하며, `dept`·`doct` 값만 다른 동일 path의 문서 URL은 같은 공개 URL/locatorKey가 될 수 있다. SHA256 `0782ea53be9c83bfab22ae6e8d494c5a822397f3e0118dd8eea5f77ea9399a50`. 출처 본문 자동 삭제는 확인되지 않았다. trace983은 distinct locator6으로 citation gate를 통과했으므로 이 동작을 이번 보류의 원인으로 단정하지 않는다. 이 보조 단서는 대조만 했고 URL 정책을 변경하지 않았다.

## 모바일 인계와 충돌 정리

모바일 담당자는 10:52 UTC에 writer/lease를 해제했다. root가 현재 파일 3개의 SHA256을 독립 대조하여 인계값과 일치함을 확인했다. chat.js는 d55f00a8db2d6ecef23b79ebd95f943d4b76efd687373a21514d4bcf3f8f7827이다. 이 Java 작업에서 모바일 파일을 수정하지 않았다.

인계 경로: data/agent-handoff/codex-autonomy/codex-main-chat-session-refresh-8eb4cda8/handoff.json 및 final-session-report.md, final-protected-hashes.json. 모바일 담당자의 보고는 focused34/34, 필수15시나리오×3뷰포트 각100PASS/0FAIL, layout108/108, keyboard reduced viewport9/9다. 이는 해당 담당자의 검증 결과이며 root가 브라우저를 재실행한 결과가 아니다. 정확한 initial-list+final 수정 후 브라우저 재실행과 추가 held-stream은 NOT_RUN, 추가 active-run fixture timing ERROR는 DEFERRED이며 제품 결함으로 단정하지 않는다. 실제 휴대폰·public UI·서버 재시작·모델 호출은 NOT_RUN이다.

## 최종 소스 pin

| Root 상대 경로 | SHA256 |
|---|---|
| main/java/com/example/lms/llm/TimedChatModelCaller.java | 46e3c0540da70c1141698fb0cb181343d8b3b464c6b0e0b6d4d9a30eb9985b58 |
| main/java/com/example/lms/service/FactVerifierService.java | 8192f31bbe783411537c9e71f13951339554105937c6a384d8a5b0bf5823b273 |
| main/java/com/example/lms/service/verification/ClaimVerifierService.java | 0cb88861f9ad5414e1ec9c44ccec573eabf50a22e1a17c928f2f98e86eb499a8 |
| main/java/com/example/lms/service/verification/FactStatusClassifier.java | 97dc56c28e0f81c7690c2b4b5c5cd6be34859b08320e6dae43b548b1e62a62cf |
| src/test/java/com/example/lms/service/VerifierCancellationBoundaryTest.java | 05a5b60448a7b1ccdf8114d96afc3c1028d44a0f02a3cceffcfd24fca1c8a0bd |
| src/test/java/com/example/lms/service/FactVerifierDetailedOutcomeTest.java | 269a2623004f3dd444a060395402a53e9244f6b1d8776b1e64d05d187c2e7672 |

최종 집중 검증: **10개 suite, 130/130 PASS, 실패/오류/skip 0**. XML/receipt 해시는 `verifier-final-validation.json`에 기록했다. 공개 UI 성공이나 전체 suite PASS를 뜻하지 않는다.

최종 판정: source/offline 수정은 검증됨. 원래 교수 답변의 지원된 부분 공개, 실제 취소 ACK, 새 Java의 서버 반영 및 공개 UI 재현은 아직 미완료다. 이 경계 때문에 전체 사건은 PARTIAL로 인계한다. 추가 비용·재시작 권한을 추정하지 않는다.
