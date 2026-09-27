# demo-1 미완성 지시서 재대조 및 소스 수정 실행 지시서

**Goal:** 기존 100행 감사와 50행 수정 지시를 현재 활성 소스에 재결합하고, 미적용 결함을 RED/GREEN으로 검증하여 수정한다. 과거 위험 후보나 증거 부족 항목을 확정 결함 100개로 승격하지 않는다.

**Architecture:** 현재 owner 안에서 원인별로 순차 수정한다. 기존 사용자 변경을 보존하고, 각 production edit 직전 독립된 3개 논리 역할과 lease/preimage 검증을 거친다.

**Tech Stack:** Java 17.0.13, Spring Boot 3.3.4, LangChain4j 1.0.1, 기존 Gradle/JUnit/Spring Test.

**Spec:** `demo1_50_findings_live_reconciled_source_patch_directive_20260823.md`의 LIVE-02 및 현재 `AGENTS.md`. 사용자 요청은 이 세션의 소스 수정을 명시적으로 승인한다.

**현재 상태:** 누적52독립 수리 원인(앱50·검증2) / 앱43파일+검증 Python1파일, 총 소스44파일. 55차 비용·속도 부분 일치2 RED를 기존 token-boundary 원인에 통합 보완·신규8사례 포함55 PASS·필수5 build gates와 새 JAR PASS. 새 독립 수리0. 과거100행73개 의미 판정/27개 미판정, BP까지68단위. 전체 check dry-run은 MCP2.0.0 offline cache 부족으로 evidence_needed. 전체100개 목표 ACTIVE; 기존 opt-in/stored-readiness 실패 미해결·전체 GREEN 아님. 상세: `data/agent-handoff/codex/report/source-audit-100-reconcile-20260907/RESULT.md`.

## 현재 실행 기준

- Desktop root: `C:\AbandonWare\demo-1\demo-1\src`
- branch: `codex/owned-runtime-browser-restart`
- HEAD: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- 활성 owner: root `main/java`, `main/resources`, `src/test/java`; app `src/main/java_clean`, `src/main/resources`.
- 현재 baseline: `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava` PASS.
- 실행 증거 디렉터리: `data/agent-handoff/codex/report/source-audit-100-reconcile-20260907/`.
- 외부 provider 요청 상한 0, DB 요청 상한 0. 로컬 focused 테스트로 판정한다.
- 커밋, push, 배포, 설정 비밀값 및 앱 메모리 변경은 실행 단계에 포함하지 않는다.

## 단위 A: LIVE-02 업로드 공개 경로 일치

대상 production: `main/java/com/example/lms/storage/LocalFileStorageService.java`.
대상 test: `src/test/java/com/example/lms/storage/UploadPublicPrefixContractTest.java`.

현재 `WebMvcConfig`는 `lms.upload-public-prefix`를 정규화하여 resource route를 등록하지만 저장 서비스는 `/uploads/`를 반환한다. 과거 지시서 이후 삭제 로직과 real-root 방어가 추가되어 있으므로 save만 바꾸면 delete 계약이 깨진다. **save와 delete가 같은 정규화된 prefix를 소비하도록 현재 지시를 보완한다.**

- [x] 실제 source owner 및 caller 확인: AttachmentService가 LocalFileStorageService.save를 호출한다.
- [x] RED test 작성: files, /files, /files/, 공백 포함 prefix; 기본 prefix; 실제 파일 내용; 발급 URL 삭제; 위조 URL 거부; 경로 탈출 거부.
- [x] focused RED 실행: `gradlew.bat test --tests com.example.lms.storage.UploadPublicPrefixContractTest` — 9개 중 5개 assertion 실패.
- [x] frozen EvidenceSnapshot의 POSITIVE_QUERY, NEGATIVE_QUERY, NEUTRAL_QUERY를 실행하고 순서 안정 APPLY 확인.
- [x] 기존 source-edit lease 및 target manifest 검증 후 `@Value("${lms.upload-public-prefix:/uploads/}")` 필드와 WebMvcConfig와 동일한 정규화 규칙을 현재 클래스에 적용.
- [x] save의 URL 조합과 delete의 prefix 검증/상대경로 추출 교체. root, UUID, 확장자, issued-path claim, symlink/junction 방어 보존. 스트림 해제는 단위 D의 별도 RED에 근거해 적용.
- [x] focused GREEN: 신규 테스트 + LocalFileStorageRealRootTest + 현재 Attachment lifecycle 회귀 테스트. 전체 선택 범위 94 pass / 3 skip / 0 fail / 0 error.
- [x] 결과 및 실행한 검사 기록, task 소유 lease 정상 종료.

## 집계 규칙

기존 100행 감사는 26 confirmed_defect, 67 structural_risk, 7 evidence_needed로 작성된 과거 자료다. 모든 기존 ID를 보존하고 현재 hash/anchor/실행 결과를 별도 필드로 붙인다. 이미 구현된 settings projection, RemoteEmbedder client/interrupt, RRF validation, soak cleanup, ChatWorkflow trace envelope는 다시 패치하지 않는다. 새로운 현재 결함은 별도 ID로 기록하며 같은 원인의 여러 입력을 여러 결함으로 세지 않는다.

## 단위 B: FUSE-01 실제 텍스트 identity 보존

- production owner: `main/java/com/example/lms/service/rag/fusion/ReciprocalRankFuser.java`.
- caller: `LangChainConfig` bean → `HybridRetriever`의 표준 fusion branch. weighted branch 선택 여부를 runtime 성공으로 추정하지 않는다.
- test: `com.example.lms.service.rag.fusion.ReciprocalRankFuserIdentityTest`.
- RED 관찰: `Aa`, `BB`가 동일 Java hash를 가져 2개 문서가 1개로 줄어듦. 4 tests 중 collision assertion 1개 실패.
- 최소 수정: `keyOf`가 32비트 해시 문자열 대신 기존 공백 정규화를 거친 텍스트 자체를 반환한다. Java Map의 키 동등성으로 해시 충돌이 실제 동일 문서로 취급되지 않게 한다. 키는 호출 내 임시 map에만 존재하며 로그, trace, 장기 저장소에 기록하지 않는다.
- 보존: 같은 정규화 텍스트의 중복 제거, 첫 metadata 보존, RRF 산식과 topK. metadata별 identity 정책은 바꾸지 않는다.
- 추가 탐침: 허용된 `Integer.MAX_VALUE` K에서 정수 덧셈이 overflow하면 반복 문서의 점수가 음수가 되어 순위가 뒤집힐 수 있다. 별도 RED가 확인된 경우에만 분모 덧셈을 double로 승격한다.

## 단위 C: RemoteEmbedder 벡터 정규화의 유한성

- production owner: `main/java/com/abandonware/ai/agent/integrations/RemoteEmbedder.java`.
- caller: `AnnIndexer.selectEmbedder()`가 `EMBED_BACKEND=remote`에서 생성하는 기존 선택 경로.
- test: `com.abandonware.ai.agent.integrations.RemoteEmbedderNumericBoundaryTest`.
- RED 관찰: 합성 loopback HTTP fixture 4건 중 3건 실패. 외부 provider 호출 없음.
- `[1e20,0]`는 float 제곱에서 overflow하여 0 벡터가 된다.
- `[3e38,3e38]`는 유효한 float 입력이지만 norm을 float로 다시 줄이는 시점에도 overflow가 발생한다.
- `[1e100,1]`는 double→float 변환 뒤 무한값이 되어 NaN이 반환된다.
- 최소 수정: float 변환 뒤 `Float.isFinite` 확인; 제곱은 `(double)x * x`; norm을 double로 유지한 나눗셈 결과만 float로 변환한다.
- 보존: 정상 `[3,4]→[0.6,0.8]`, 기존 256차원 fallback, threshold, HTTP timeout, client 재사용 및 interrupt 처리를 유지한다.

## 단위 D: 업로드 입력 스트림 소유권

- production owner: 단위 A와 같은 LocalFileStorageService의 `Files.copy(file.getInputStream(), ...)`.
- test: `com.example.lms.storage.LocalFileStorageStreamLifecycleTest`.
- 탐침: 성공 및 IOException 모두 실제 파일 복사에 제공한 InputStream의 close 여부를 확인한다.
- RED일 때 최소 수정: 현재 Files.copy 한 줄을 try-with-resources로 감싼다. 실패 파일의 삭제 정책이나 기존 경로/issued-path 로직은 이 변경에 추가하지 않는다.

## 첫 번째 적용 가드의 해결 기록

단위 A 최초 frozen 3-role 결과는 APPLY였으나, lease 획득 단계에서 다른 활성 작업의 `quota-no-replay-20260907-01a0796d` 임대 때문에 `source-lease-blocked`가 반환됐다. 이 관찰은 production 적용 완료가 아니다. 새로운 lease/preimage 관찰에서 충돌이 사라졌을 때 새 snapshot과 세 논리 역할을 실행하여 현재 write set만 적용한다. 다른 작업의 lock, source, 프로세스는 정리하지 않는다. 그동안 테스트와 감사/지시서 작업은 계속한다.

후속 관찰에서 외부 임대와 Git 작업이 종료된 것을 확인한 뒤 3개 frozen target에 대한 source-owner guard를 통과해 적용했다. RRF의 자동 CRLF 변환은 별도 frozen preimage/3-role/lease 검증 후 원래 LF로 복원했다. 최종 3개 소스의 바이트는 테스트한 candidate와 정확히 같으며, 테스트 후에도 postimage가 유지됐다. 위 과거 HOLD는 해결되었고 현재 source lease는 0개다.

## 단위 E: CALIBRATION-01 = M001 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/service/rag/fusion/FusionCalibrator.java`; caller: HybridRetriever minmax/isotonic 선택 경로.
- RED: `[-Double.MAX_VALUE, 0, Double.MAX_VALUE]`의 결과가 `[0, 0.5, 1]`이 아니다. 비대칭 범위도 실패했다.
- 변경: `mx-mn`이 유한하지 않은 경우에만 양끝과 값을 절반으로 축소해 차/비율을 구한다. 일반 범위는 기존 식을 유지한다.
- 검증: 신규 FusionCalibratorExtremeRangeTest 6개와 기존 FusionCalibratorTest 2개 pass. 인접 극값, subnormal, 비유한 입력 및 입력 배열 비변경 포함.

## 단위 F: RANK-01 = M009 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/service/rag/rerank/DefaultLightWeightRanker.java`; caller: HybridRetriever 경량 재정렬.
- RED: `!!!`가 `relevant`보다 먼저 입력되면 query `relevant`, limit 1에서 문장부호 문서가 선택된다.
- 변경: 위치 기반 점수는 query token이 비었을 때만 적용한다. query에 token이 있으면 token 없는 candidate는 어휘 일치가 없는 candidate로 처리한다.
- 검증: DefaultLightWeightRankerContractTest 3 failure/5 → 5 pass. 빈 query, 일치 없음 fallback, 동점과 limit를 보존한다.

## 단위 G: URI-IDENTITY-01 = M010/M011/M012 — 적용 및 검증 완료

- owner: `RerankCanonicalizer.java`, `WeightedReciprocalRankFuser.java` (모두 `main/java/com/example/lms/service/rag/fusion/`).
- caller: HybridRetriever weighted fusion 선택 경로. 모든 실행에서 이 branch를 선택한다고 주장하지 않는다.
- RED: case-sensitive path/query, 서로 다른 scheme/port, opaque ID, percent-encoded 구분자, 마지막 slash 및 빈 query가 잘못 병합된다.
- 변경: URI를 먼저 파싱하고 scheme/host만 소문자로 정규화한다. port, raw path/query, opaque scheme-specific part를 보존한다. fuser가 파싱 전에 전체 URL을 소문자로 만드는 동작도 제거한다. HTTP(S)의 빈 root path는 `/`와 동일하게 처리한다.
- 보존: tracking parameter/fragment 제거, URL metadata alias, 첫 문서 metadata, 기존 trace 통계. 키는 기존 호출 내 map에서만 사용한다.
- 기존 테스트 3개의 fixture URL은 정확히 같은 경로로 수정한다. 서로 다른 경로를 같은 문서라고 가정한 fixture에 맞추려고 잘못된 병합을 유지하지 않는다. 근거: RFC3986 §6.2.2.1/§6.2.4.
- 검증: RerankCanonicalizerIdentityTest 10 failure/12 → 12 pass, 기존 WeightedReciprocalRankFuserTest 11 pass. 여러 충돌 입력을 1개 원인으로 집계한다.

## 단위 H: INTENT-01 = C002 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/search/NoiseClipper.java`; caller: SmartQueryPlanner.
- RED: `삼성이 뭐 하는지 말고 법인명 알려줘`가 `삼성`만 남긴다.
- 변경: ENTITY_WHAT는 요청 전체가 단순 엔티티 질문일 때만 일치하도록 제한한다. 임의의 실질적 뒷문장을 허용하는 `.*`를 제거한다.
- 검증: NoiseClipperIntentTest 2 failure/4 → 4 pass. 부정과 요청 속성, 장소·위치 질문의 후속 조건, 단독 질문 및 일반 cleanup 포함.

## 단위 I: SLICE-01 = C003 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/search/policy/QuerySlicer.java`; caller: SearchPolicyEngine의 slicingEnabled 경로.
- RED: 공백 없이 이어진 일본어 문장을 여러 문장으로 인식하지 못한다.
- 변경: Latin `.!?` 뒤의 공백 규칙을 유지하고 CJK `。！？` 뒤에는 공백 0개도 허용한다.
- 검증: QuerySlicerSentenceBoundaryTest 2 failure/4 → 4 pass. Latin 소수점, newline, overlap, maxSlices 계약 포함.

## 두 번째 적용 가드 및 검증

6개 frozen production preimage, 15행 evidence snapshot 및 정확히 3개 논리 query를 기록했다. 정방향/역방향 모두 APPLY, 점수 90, 사전 검토 시간 73.1초였다. 이후 기존 source-owner lease와 target manifest를 즉시 검증해 원본 바이트를 보존한 후보를 적용했다. 기존 Weighted 테스트 fixture의 줄바꿈 3곳도 test-only manifest/lease 검증으로 원래 CRLF로 보존했다.

`second-focused-test-summary.json`: 7 suite / 44 pass. `second-desktop-test-summary.json`: 16 suite / 98 pass, failure/error/skip 0. `checkLangchain4jVersionPurity checkSourceSetHygiene :app:classes bootJar` 성공. `second-jar-proof.json`은 누적 source 9개 postimage 및 JAR class 일치를 보존한다. source lease는 정상 종료됐다.

## 단위 J: MEAN-NUMERIC-01 = M005 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/service/rag/fusion/WeightedPowerMeanFuser.java`.
- active caller: NovaNextFusionService → TailWeightedPowerMeanFuser. 유한 [0,1] source score와 [1.05,8] applied p를 사용하는 기존 경로다.
- RED: 작은 양수 `1e-200`, `2e-200`이 p=2/3/8에서 0으로 소실된다. 실제 Nova upper-tail fixture에서도 확인했다.
- 변경: 기여하는 최대 score로 나눈 후 pow하고 결과의 크기를 복원한다. zero-weight의 큰 값이 scale을 결정하지 않게 한다. 유효 비음수 데이터/양수 p 경로에 적용하고 p=0/음수 p, 기본 weights, 입력 비변경은 유지한다.
- 검증: WeightedPowerMeanNumericalStabilityTest 5 failure/11 → 11 pass. 기존 bounded pMax probe 7개는 전후 모두 pass. 파라미터·H03 trace·rollback 증거는 `third-hypernova-report.md`.

## 단위 K: AUTH-IDENTITY-01 = C008 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/service/rag/auth/AuthorityScorer.java`; caller: SourceAnalyzerService.
- RED: 임의 docs/developer host만으로 OFFICIAL을 주며 실제 SourceAnalyzer도 이를 공식 출처로 승격한다.
- 변경: 일반 문서 label 승격 조건만 제거한다. 기존 명시적 override, known vendor/project, 정부/교육/community 분류는 보존한다.
- 검증: AuthorityScorerHostIdentityTest 4 failure/7 → 7 pass, 기존 malformed URL redaction test pass. 전체 authority heuristic을 인증했다는 주장은 하지 않는다.

## 단위 L: RELEVANCE-DIM-01 = C009 — 적용 및 검증 완료

- owner: `main/java/com/example/lms/util/RelevanceScorer.java`; caller: NaverSearchService의 유사도 점수 계산.
- RED: `[1,0]`와 `[1,0,10]`을 앞부분만 비교해 거의 완전 일치로 반환한다.
- 변경: 동일 차원이 아니면 기존 fail-soft 값 0을 반환한다. 양쪽 mismatch 순서를 검증한다.

## 단위 M: RELEVANCE-NUMERIC-01 = C010 — 적용 및 검증 완료

- owner/caller: 단위 L과 동일. 차원 오류와 산술 오류는 독립 원인이므로 별도 ID를 유지한다.
- RED: 큰 float 곱은 double 누적 전에 overflow하고, 작은 float와 고정 EPS는 cosine의 scale invariance를 깨뜨린다. nonfinite 모델 출력도 NaN이 된다.
- 변경: 좌표 finite 검증 후 double에서 곱한다. 기존 zero-norm guard로 나눗셈을 보호하며, 모든 유한 float norm이 double에서 표현 가능하므로 고정 EPS를 제거한다. 결과는 유한 [0,1] 또는 fail-soft0이다.
- 검증: L/M을 함께 검사하는 RelevanceScorerVectorContractTest 10 failure/11 → 11 pass. 기존 opposite/equal/zero/embedding-exception 테스트 3개도 pass.

## 세 번째 적용 가드 및 검증

3개 frozen source preimage, 13행 snapshot, 정확히 3개 query, 양순서 APPLY/score91.35/52초를 기록했다. 기존 source lease와 target manifest를 즉시 검증한 뒤 적용했다. dirty RelevanceScorer의 다른 hunk는 보존했다. 집중 6 suite/40 pass, 확대 16 suite/116 pass, failure/error/skip0. LangChain4j/sourceSet, app classes 및 bootJar 성공. 누적 source12개 postimage와 JAR class12개의 현재 compile output 일치를 확인했고 source lease는 정상 종료했다.

## 단위 N: UI-REASONING-FILTER-01 = U001/U003 — 적용 및 검증 완료

- owner: `main/resources/static/js/chat.js`의 stripAssistantReasoningBlocks/filterAssistantReasoningChunk/renderChatEvent.
- RED: 가시 prefix 뒤 열린 태그의 hidden suffix 노출, opening/closing 태그 분할 노출, whitespace 손실, 중첩 블록 조기 종료.
- 최소 수정: marker 증분 처리, 짧은 pending suffix, 중첩 상태, independent final payload 정리. Unicode 앞부분의 case-fold 길이 변화로 index가 어긋나지 않도록 marker만 비교한다.
- 보존: 일반 텍스트·HTML sanitization, 공백, ordinary incomplete tag EOF 표시. exact-run recovery에서는 아직 열린 블록 상태를 버리지 않는다.
- [x] Node 실행형 회귀와 기존 전체 stream 계약, 집중 Gradle 및 실제 DOM 검증.

## 단위 O: UI-REASONING-SCOPE-01 = U002 — 적용 및 검증 완료

- 원래 탐침의 같은 VM 연속 chunk는 새 stream 경계 증거가 아니었다. 활성 send/resume가 실제 새 assistant 객체를 생성한다는 호출 경로를 확인했다.
- 전역 boolean을 assistant-keyed WeakMap으로 바꿨다. stopped old bubble의 late event 차단, fresh bubble의 다음 답변, same bubble reconnect를 각각 검증했다.
- [x] 별도 소유권 원인으로 집계하고 U001/U003과 중복 계산하지 않는다.

## 단위 P: UI-RESTORE-RETRY-01 = U004 — 적용 및 검증 완료

- RED: 복원 시작 latch가 transient503/invalid JSON/invalid detail 이후에도 true여서 다음 복원 시도를 막았다.
- 동일 session/generation의 미완료 시도만 finally에서 latch를 해제한다. 성공 latch, in-flight exclusion, stale generation/transcript guards,403/404 정리를 보존했다.
- [x] 실패 후 재시도와 한 번만 append, 세대 변경 시 상태 불변, 실제 DOM의 합성503 → 성공을 검증했다.

## 네 번째 적용의 검증 및 열린 계약

- frozen preimages2개, snapshot11행, 세 query와 양순서 APPLY, score91.75,71.6초; 기존 owner guard 적용 및 source lease 종료.
- 신규 Node20/20, 기존 전체 stream 계약 PASS, 집중 Gradle3 suite/29 PASS, 실제 DOM6/6.
- 확대 Gradle133개 중5개 실패. 수정 전 JS 동결본과 같은 CSS/template 입력에서도 동일5개 실패를 재현했다. 전체 검사 성공으로 보고하지 않는다.
- sourceSet/LangChain4j/app classes/bootJar PASS. 누적 Java12 class와 chat.js resource의 현재 패키지 일치.
- browser proof는 loopback 합성 fixture이며 전체 backend/provider 성공은 not_observed.

## 단위 Q: UI-IMAGE-LIFECYCLE-01 = U005 — 적용 및 검증 완료

- POST 접수와 SUCCEEDED 완료를 구분했다. 동일 job id를 검증하며 FAILED, 대기 종료, status 조회 실패를 각각 표시한다.
- etaSeconds, 유한2~30분 대기 상한, 최대90 status GET, 요청10초 상한, 제거되거나 stopped된 대상 정리를 검증했다. 서버 취소 API를 만들지 않았다.
- Node16개, 기존 reasoning/restore20개 및 stream 계약, 집중 Gradle50개와 실제 DOM8개 PASS. 전체 provider 생성/파일 전달은 별도 증거다.

## 단위 R: IMAGE-DEBUG-CONTRACT-01 — 적용 및 검증 완료

- 실제 nested diagnostic `/api/diagnostics/image/jobs/{id}/debug` 응답에서 job.status만 별도 진단 행에 표시한다.
- 진단404·지연된 상태가 authoritative primary 상태를 바꾸지 않도록 하고10초 요청 상한을 적용했다.
- snapshot12행과 정확히 세 query, 양순서 APPLY/score88.45/76.5초, 기존 owner guard 및 postimage/JAR 확인.

## M003/M004 실행 경로 주장 정정

앞선 SelfAskPlanner → RagLightAdapters → RrfFusion 참조는 source 수준이었다. top-level adapter/planner가 root component scan/import로 활성화된다는 증거가 없고 실제 root component planner는 이 RrfFusion을 사용하지 않는다. 적용했던 RRF 수정은 원래 해시로 복구하고 새 테스트를 report의 fifth-proposed-tests로 옮겨 보존했다. 완료 원인 수에0개 추가했다. 다음 적용 전 기본 runtime caller 또는 명시적 지원 utility 계약이 필요하다.

## 단위 S: UI-IMAGE-ARTIFACT-01 — 적용 및 검증 완료

- owner: image-jobs-ui.js의 기존 updateImageJobCard. SUCCEEDED의 publicUrl/manifestUrl을 무시해 생성된 결과에 접근하지 못하는4 RED를 재현했다.
- 서버가 반환한 root-relative same-origin publicUrl만 실제 img/open link로 사용한다. manifest는 반환된 URL이 현재 job endpoint와 정확히 같을 때만 제공한다. opaque artifactRef로 경로를 만들지 않는다.
- metadata replaceChildren으로 stale media 정리, alt label과 responsive max-width/height, noopener noreferrer를 적용했다. storage prefix와 서버 권한 변경 없음.
- 신규8개 및 기존 image16/reasoning20을 합쳐 Node44 PASS; 집중 Gradle50 PASS; 실제 DOM8 PASS. 합성 PNG GET1/manifest GET1과320→240px 표시를 확인했다. 실제 provider 생성은 not_observed.
- snapshot10행/세 query/양순서 APPLY/score92.25/69.2초; owner manifest/preimage 적용 후 lease 종료. current source/class/resource/JAR 일치와 제한적 secret pattern hits0.

## 단위 T: MASKER-STRUCTURED-SECRET-01 — 적용 및 검증 완료

- 실제 source 경로: AttachmentController archive ingest → classifier/build → legacy PiiSanitizer → SafeRedactor → PromptMasker. 허용된 synthetic JSON-key 메시지의 청크 본문에 값이 남는 RED를 확보했다.
- 대상은 공통 PromptMasker 한 파일. 기존 field-name set을 공유하고 quoted/plain 값을 분리하며 quoted key, 공백/escape, 인용부호·구분자·길이를 보존한다.20000자 long-input/normal/malformed fail-soft/idempotence 대조군을 포함했다.
- 최초 신규9개 중7 RED와 기존4 PASS, 최종 후보14 PASS. 실제 원본은 candidate init 없이 관련5 suites/36 PASS. 최종JAR archive probe4 PASS 및 누적 Java13/JS2 패키지 일치.
- snapshot12행/세 query/양순서 APPLY/score89.9/70.4초; scoped owner/preimage guard와 최종 lease0, reverse patch PASS, 제한적 secret pattern hits0.
- 별도 service.guard.PIISanitizer 직접실행 RED2개는 남아 있으며 기본 호출은 미증명이다. 그 클래스를 수정하거나 archive의 정상 Korean-phone 처리를 변경하지 않았다. 이번 root만1개 집계한다.
- AL-07의 source hash만 갱신했다. 학습 데이터 전체 PII/모든 malformed·non-string JSON 또는 default streamed-chat privacy 인증으로 확대하지 않는다.

## 단위 U: UI-SHORT-DESKTOP-ACCESS-01 — 적용 및 검증 완료

- RED: 실제 CSS/template의1280×720에서 Diagnostics closed/open 모두 transcript0px, composer가 fixed hidden wrapper 밖으로 밀림.
- `chat-style.css`에 min-width761/max-height880 조건의 auto height와 기존 viewport 최소 높이를8줄 추가했다. 페이지 재배치로 transcript260px와 composer 접근성을 복원했다.
- 모바일390×667 및 데스크톱1280×900 closed/open 계측은 후보와 원본에서 수정 전과 완전히 같다. 메시지20개 대조에서 chatWindow client260/scroll1866, wrapper client=scroll970을 확인했다.
- matcher2개는 height/min-height substring 충돌 및 obsolete last-child였다. 실제 모바일 Health gridColumn1/-1 확인 뒤 테스트만 정정했고 원인 수에 더하지 않았다.
- 원본 집중4 PASS, 전체 웹 계약105개 중103 PASS와 기존 heartbeat2 FAIL, sourceSet/LangChain4j/app classes/bootJar PASS. 누적 Java13, JS2+CSS1 패키지 일치. source/test owner guard와 reverse check PASS, 최종 lease0.
- fixture 계측6상태를 provider/실제 Spring 요청 성공으로 확대하지 않는다. recovery/local-operator heartbeat2개는 의미 확인이 남았다.

## 단위 V: UI-RECOVERY-OPERATOR-01 — 적용 및 검증 완료

- 현재 전체 chat.js와 실제 startup heartbeat 렌더링에서 READY+미해결 조치가 Model OK/준비 완료를 표시하면서 요약은 local action을 요구했다. WARMING에서도 Model 상세가 조치를 가렸다.
- 실제 controller는 cached modelRuntime과 fresh recovery snapshot을 합친다. operator map에는 recovery와 비교할 시간 근거가 없으므로 READY로 조치를 지우거나 stale/fresh라고 단정하지 않는다.
- Model 상태·상세 두 계산을 좁게 변경했다. 기존 명시적 clear 조건만 사용해 미해결 조치의 WARN과 상세를 유지하고 recovery 설명에 함께 표시한다. 실제 clear/조치 없음 READY는 기존 OK를 보존한다.
- 신규 Node 8개 중 3 RED→8 PASS, 기존44 PASS, 최종 원본 웹 계약105 PASS. 전체 원본 JS 브라우저6상태 PASS, sourceSet/LangChain4j/app classes/bootJar PASS. Java13 및 JS2+CSS1 source/JAR 일치.
- snapshot12행/세 query/양순서 APPLY/92.55점/2.72초 뒤 owner·manifest·preimage guard로 적용했다. 기존 backend evidence/cache/프로세스/memory는 변경하지 않았다. 최종 lease0.
- 테스트의 오래된 문자열 정정2개를 별도 production 원인으로 세지 않는다. process readiness를 native provider 생성 성공으로 승격하지 않는다.

## 단위 W: ARCHIVE-CODE-PROSE-01 — 적용 및 검증 완료

- 696자 일반 기술 대화가 keyword substring3개 조건 때문에 code_or_internal_dump로 분류되어 청크0이 되는 packaged RED를 재현했다. ZIP 수집과 prose·inline code/다중 행 대조 회귀도 실패했다.
- 기존400자 최소/3개 category를 유지하면서 import/package/class/function/binding의 source-shaped declaration을 판별한다. Java/JS·minified·anonymous function/destructuring 코드 대조와 기존 noise 규칙을 유지했다.
- 긴40,064자 qualified declaration에서 첫 후보의 도입 스택 회귀를 찾았다. 원래 소스 control PASS를 확인하고 possessive 반복3곳으로 보완했다. 원래 결함 수에는 추가하지 않고 package/import5000segment 검사를 최종 신규15개 안에 포함했다.
- 실제 원본 archive/masker6 suites51 PASS, 새 JAR 일반·짧은·관사 반복 prose/코드/순수 반복5 PASS 및 긴 선언1 PASS. Java14+resource3와 archive 구성/JAR 일치. 두 후보 단계 각각 fresh three-query/owner/CAS guard를 사용했다.

## 단위 X: ARCHIVE-REPETITION-PROSE-01 — 적용 및 검증 완료

- 첫 단어가 비연속으로4회 이상 등장하면 정상 문장도 repeated_noise가 됐다. 117자 일반 문장과 ZIP 제출 경로의 RED를 재현했다.
- 모든 whitespace-delimited 토큰이 첫 토큰과 같은 경우로 좁혔으며 low-distinct-character guard는 보존했다. 대소문자/개행·tab을 포함한 순수 반복 대조는 계속 격리한다.
- W와 같은 classifier 한 파일의 독립 원인으로 집계한다. 테스트 개수나 도입 회귀를 별도 원인으로 세지 않는다. vector mock의 enqueue는 제출 증거이며 실제 persistence는 not_observed다.

## 단위 Y: API-CHAT-02 — 적용 및 검증 완료

- 실제 absent positive sessionId는 access guard를 통과해 startNewSession으로 복구된다. 이 서비스의5-arg guest fast path 및3-arg delegate는 첫 user message를 이미 저장한다. controller가 원래 non-null ID만 보고 다시 append하는 문제가 normal/shared sync와 non-attach stream에 있었다.
- controller 한 파일의 두 branch에서 request-local firstUserTurnStored를 유지한다. 생성/복구가 저장했으면 추가 user append를 생략하고 기존 owned session은 계속1회 append한다. 요청 ID와 생성 ID의 값 비교는 사용하지 않는다.
- 전체 controller47개 중4 RED를 확보했고 최종47/access1=48 PASS. missing/recovered-ID coincidence/public normal/sync/stream과 기존 null-ID·foreign403·replay/ACK/canonical run 제어를 검증했다. 테스트4개와 기존 stream assertion1개를 보강했다.
- snapshot13행/세 query/양순서 APPLY/93.1점 및 owner·manifest·preimage gate 후 적용했다. 최종 sourceSet/LangChain4j/app classes/bootJar 및 Java15/resource3, controller 본체·중첩5class 일치를 확인했다. 실제 DB/provider 성공으로 확대하지 않는다.

## 기존 적용 및 반증 재대조

- API-ATTACH-01은 기존 owner admission11 PASS로 현재 방어를 확인했다. 새 소스 수정이나 새 원인 집계를 하지 않았다.
- API-SESSION-03의 detached-only 주장은 현재 updateSessionMeta 서비스 호출과 내부 repository.save source에 의해 반증된다. 기존 stream metadata test는 서비스 전달을 확인하며 실제 DB save 성공은 미관측이다.
- API-ARCHIVE-06의 generated-artifact silent-drop 가설은 실제 source_path=conversation_archive 규칙 때문에 성립하지 않는다. 새 JAR에서 일반/generated-looking entry2허용 및 직접generated metadata1거부를 확인했다. 불가능한 stub 반환을 결함 증거로 쓰지 않고 queue API를 유지했다. promotedCount 의미의 관측 계약은 별도 범위다.

## 단위 Z: API-ATTACH-ASSOCIATION-01 — 적용 및 검증 완료

- 생성 전에 owner-bound upload한 파일이 direct sync 신규/복구와 normal/stream missing-ID 복구에서 세션 index에 연결되지 않았다. normal 신규는 generation 뒤에야 연결돼 첫 답변 시점의 session lookup이 비었다.
- controller의 실제 sessionCreated 상태를 사용해 shared handleChat과 stream에서 생성 직후 owner-aware association을 수행한다. 기존 first-user-turn 저장 조건과 request ID, owner·foreign/session-link 방어를 유지한다.
- 실제 controller/AttachmentService를 사용하는 새12사례 중5 RED→PASS. 최종 controller59+attachment admission6+service ownership5+session access1=71 PASS. 첫 테스트 선택 오류는 별도 정확한 package 실행/XML로 보완했다.
- snapshot12행/정확히3query/양순서 APPLY/95점, owner lease·manifest·즉시 preimage 후 적용. sourceSet/LangChain4j/app classes/bootJar 및 Java15/resource3/package identity를 검증했다. 잘못 잡힌 최초 artifact hunk는 적용 전 거부했으며 source 결함 수로 집계하지 않았다.

## 단위 AA: HISTORY-EAGER-READ-01 — 적용 및 검증 완료

- 활성 getFormattedRecentHistory가 요청 N과 무관하게 전체 transcript를 먼저 조회했다. 기존 deterministic newest-page query를 page당 최대200개로 사용하고 Java 메타 필터 후 필요한 수가 모이면 중단한다.
- case-sensitive prefix·null content·최소1개·350개 같은 큰 출력 요청을 유지한다. timestamp/id 동률·null 순서는 기존 bounded window convention으로 명시한다. 새 JPQL/DB predicate/의존성은 없다.
- 신규7개 중6 RED를 확인했고 memory10/window9=19 PASS. 합성 repository10000→3행, meta-tail6 포함10006→8행,350출력/400행/200page를 관측했다. sourceSet/version/app/bootJar와 누적 Java16/resource3 class identity를 확인했다.
- snapshot12행/3query/양순서 APPLY/93.3점 및 기존 owner·preimage guard 후 적용. 전체 API-HISTORY-07을 닫지 않는다. 긴 메타 tail·JPA transaction entity retention·다른 authorization/getSessionWithMessages full reads·live concurrent pagination과 실제 SQL 성능은 남은 범위다.

## 단위 AB: SYNC-RUN-LIFECYCLE-01 — 적용 및 검증 완료

- sync/root 공통 handleChat을 기존 exact run lifetime/context와 terminal gate에 연결했다. 동시 owner는409, generation 중 삭제가 이기면 후속 assistant append를 중단한다. 저장이 먼저 admitted되면 삭제가 terminal block 종료를 기다린다. 성공/취소/오류 cleanup을 유지한다.
- 실제 registry와 두 route의 신규10 latch/owner/error 사례 모두 RED→PASS. 관련8 suites162 + selection replay7 =169 PASS. 첫99검사의 fixture 실패1개는 real registry로 test-only 보정했고 query assertions를 유지했다.
- snapshot12행/정확히3query/양순서 APPLY/92.15점과 기존 owner/preimage guard 후 적용. sourceSet/version/app/bootJar 및 누적 Java16/resource3 일치 확인.
- 초기 user/session 준비 쓰기, live JPA transaction/FK, terminal metadata 처리 latency는 별도 범위다. stream fence는 기존 방어이므로 새 원인 수에 포함하지 않는다.

## 단위 AC: STREAM-FALSE-PERSISTED-01 — 적용 및 검증 완료

- Stream assistantMessageId가 non-null일 때만 기존 markPersisted를 호출하도록 조건 한 곳을 수정했다. 저장 생략/실패의 nullable 계약과 fail-soft final delivery를 유지한다.
- final emit 성공/실패 신규2개 RED→PASS. exact-token persisted=false/count0/no stale restore/attachable=true/final·recovery ACK거부를 확인하고 기존 non-null 저장 복구 대조군을 유지했다.
- controller61 + sync10 + cancel11 + registry9 + trace pointer5 =96 PASS. sourceSet/version/app/bootJar와 누적 Java16/resource3 및 companion 일치 확인. snapshot10행/3query/양순서 APPLY/97.85점 및 owner/preimage guard 후 적용.
- API-TRACE-04는 ring eviction 후 redacted durable fallback 복구라는 현재 반증을5개 기존 테스트로 재검증했다. 새 수정 수0개. 물리 DB commit/restart와 full raw trace 복구는 미입증이다.

## 단위 AD: OFFICIAL-ONLY-FAILOPEN-01 — 적용 및 검증 완료

- explicit officialOnly를 기존 restrictTrust와 구별하여 all-filtered selected 복원을 막았다. 일반/strike-only의 현재 동작, order/dedup/min-limit/filter-after-limit/no-backfill은 유지했다.
- 신규5사례와 기존 assertion1개 중4 RED→PASS. 실제 public cache-only entry를 포함해 검색9 suites101 PASS. provider는 mock이고 실제 외부 요청 증거는 없다.
- snapshot10행/3query/양순서 APPLY/96.4점과 owner/preimage guard 후 source2파일 적용. sourceSet/version/app/bootJar 및 누적 Java18/resource3와 companion 일치 확인.
- 기존 low-trust substring은 완전한 official-host 검증이 아니다. 정상 merge 경로의 after_filter_starvation 분류는 source 증거이며 다른 경로의 telemetry까지 확장하지 않는다.
- SEC-06은 bootstrap attribute 우선/신뢰 proxy 제한이라는 현재 반증을 기존16개 테스트로 확인했다. API-TRACE-04 기존 durable fallback5 PASS와 함께 신규 수정 수0개로 유지한다.

## 단위 AE: 과거 6개 지시서 재대조 — 신규 source 수정0개

- SEC-05 비활성 호환 resolver, SEC-04 활성 official profile, PM-05 blank 응답 차단, PA-01 내부 신호 우선순위 전제, PM-02 정상 memory/summary 실패 방어, PA-08 협조적 취소를 현재 source와 테스트로 한정 판정했다.
- 신규8개/3개 test 파일 및 기존 대조군을 합쳐10 suites42개가 모두 PASS. PA-01의 root task 누락은 crossSubsystemContractTest의 실제7개 XML로 보완했다.
- 각 owner lease/preimage/postimage/reverse-check 통과. production30 hash행과17차 JAR 그대로, 신규 수정 수0개. 31개 독립 원인/21개 production 파일을 유지한다.
- 각 범위 제한과17개 historical 의미 판정은 eighteenth-counterevidence.json 및 historical-100-current-anchors.json에 기록했다. 나머지83행의 hash는 의미 성공 증거가 아니다.

## 단위 AF: CLOUD-FALLBACK-ELIGIBILITY-01 — 적용 및 검증 완료

- process unavailable, primary ENFORCE ineligible, lazy failure에서 cloud fallback을 선택할 때 기존 gateway 적격성을 검사한다. null/차단은 거부하고 정상 fallbackOnly와 OBSERVE를 유지한다. 초기 lazy feasibility는 설정만 확인하고 실제 실패 시 현재 적격성을 확인한다.
- 신규8개 포함 gateway52, 장치/replay/strict-primary/provider/version 대조군까지130 PASS. 원래6 RED 중5개 동작 실패와1개 누락 검사. 첫 GREEN의503 분류 기대값2개를 HEALTH_DOWN으로 test-only 보정하고 원본 출력 및 모든 행동 assertion을 보존했다.
- snapshot10행/3query/양순서 APPLY/96.75점, owner·preimage·postimage·reverse-check 통과. sourceSet/version/app/bootJar와 Java19/resource3/router inner class identity 확인. source1개 추가로 누적32원인/22production 파일.
- PM-06 strict-total-request 전제는 현재 primary-retry 및 한 번의 별도 replay-safe fallback 계약과 구별한다. 관련 기존 테스트 재검증은 신규 수정 수0개이며 전체 strict workflow/router 결합 인증은 아니다.

## 단위 AG: EXPLICIT-WHITELIST-MODE-BYPASS-01 — 적용 및 검증 완료

- UnifiedRagOrchestrator final whitelist에서 aggressive/memory NONE 우회 조건을 제거했다. 기존 predicate·empty result·topK/rank·whitelist=false는 유지한다.
- 실제 seed-only mixed WEB/VECTOR와 all-denied, 정상/무제한 대조군 신규8개 중4 RED→PASS. owner43 포함7 suites143 PASS, failure/error/skip0.
- snapshot10행/3query/양순서 APPLY/96.9점 및 owner/preimage/postimage/reverse-check 후 source1개 적용. sourceSet/version/compile/app/bootJar와 누적 Java20/resource3/inner class 일치 확인. 누적33원인/23production 파일.
- Cross-subsystem guard의 영향표를 확인했다. final admission만 변경하며 S01–S08 알고리즘·공유 중재·prompt/auto-config 변경은 없다. 원래 predicate의 hostname suffix 충돌과 missing-bean/빈 설정 정책은 별도 범위다.

## 단위 AH: FINAL-WHITELIST-HOST-IDENTITY-01 — 적용 및 검증 완료

- Unified의 raw hostname suffix 비교를 기존 DomainWhitelist strict matcher 호출로 교체했다. 기존 pure helper의 public static visibility만 늘리고 본문은 유지했다.
- 신규12개 중5 RED→PASS. suffix impostor2개와 case-equivalent 정상 호스트3개가 실패했으며 exact/subdomain/userinfo/path/malformed/missing controls를 유지했다. owner/domain/public161 PASS; 이름 오기1개는 별도 실제 ContractTest 실행/XML로 보완했다.
- snapshot10행/3query/양순서 APPLY/97.65점 및 owner/preimage/postimage/reverse-check 통과. sourceSet/version/compile/app/bootJar와 누적 Java21/resource3/inner class 일치. 누적34원인/24production 파일.
- PA-06 모드 우회와 구별되는 실제 최종 predicate다. SEC-04 기존 profile 반증은 신규 수정으로 세지 않는다. IDNA/root-dot, missing-bean/빈 설정/general community 정책, 실 공급자/DB/Browser는 별도 범위다.

## 단위 AI: DEBUG-SNAPSHOT-ROUTING-MUTATION-01 — 적용 및 검증 완료

- GuardDebugTraceAspect의 stateful OrchestrationSignals.compute 호출을 제거하고 이미 캡처한 GuardContext mode/reason을 snapshot에 사용한다. 기존 mode 우선순위와 breaker/potential-trigger 관측, query/error/reason 마스킹을 유지한다.
- 신규9개 중8 RED→PASS. caller plan/flag, business 변경 및 원래 예외 identity, 새 routing/noise trace 없음, enabled/disabled와 breaker 대조까지 관련8 suites91 PASS.
- snapshot10행/3query/양순서 APPLY/96.8점 및 owner/preimage/postimage/reverse-check 통과. 첫 writer 관측 시 무적용,0개 재검증 후1.82초에 적용. sourceSet/version/compile/app/bootJar 및 Java22/resource3/debug inner class 일치. 누적35원인/25production 파일.
- PA-04와 동일 원인 한 개이며 호출 단계/테스트 실패8개를 별도 집계하지 않는다. 배포 debug flag/AOP wiring, 실제 provider/DB/Browser는 미관측이다. PM-09 dormant escalate는 source-only 반증이며 새 수정0개다.

## 단위 AJ: R01/R05/FUSE-01 현재 계약 재검증 — 신규 source 수정0개

- 기존 SingleFlightManager Throwable completion5, LlmTraceAspect error/cancel/stop3, 첫 batch RRF collision/metadata/rank5를 현재 실행하여13 PASS. R01/R05는 기존 방어, FUSE-01은 동일 수정의 ledger 연결이며 신규 원인0개다.
- R06의 discarded result는 확인했지만 supplemental event 실패 시 run cancellation 계약은 미입증이다. 임의 정책을 추가하지 않는다. PA-03/PM-09의 범위 제한도 유지한다.
- 누적35원인/25production 파일과22차 JAR identity 유지. 과거100행 판정26개에는 evidence_needed도 포함하며 미판정74개다. 22차 writer 수의 설명 오기는 원본 JSON에 따라1로 정정했고 무적용 guard 결과는 동일하다.

## 단위 AK: FAILURE-MEMORY-PREFIX-IO-01 — 적용 및 검증 완료

- FailurePatternMemoryService에서 마지막1000 physical lines의 시작을8192-byte 역방향 블록으로 찾고 기존 deque/parser/scorer를 사용한다. 빈줄·CR/LF/CRLF·final newline 의미와 strict selected-tail UTF8 오류 처리를 보존한다. 버릴 prefix는 검증/디코딩하지 않는다.
- 신규12개 중2 RED→PASS, 관련6 suites29 PASS. 실제 JFR FileRead 기준 큰 prefix1403890→31384바이트, 작은 prefix15100→30100바이트. 반환tail은 동일하며 큰 파일97.8% 감소는 I/O 수치다. byte-cap/latency/heap 인증이 아니다.
- snapshot9행/3query/양순서 APPLY/94점 및 owner/preimage/postimage/reverse-check 통과. sourceSet/version/compile/app/bootJar와 Java23/resource3/inner class 일치. 누적36원인/26production 파일. 경계 fixture 보정은 새 원인으로 세지 않는다.
- R08 기존 유한 retention10 PASS는 신규 수정0개다. 전체100행 판정28개에는 evidence_needed를 포함하며72개 미판정이다. 실제 memory data/provider/DB/Browser는 미변경/미관측이다.

## 단위 AL: FEDERATED-ID-CAPABILITY-01 — 적용 및 검증 완료

- UnsupportedFeatureException 뒤 non-ID addAll fallback을 제거해 요청/반환 ID가 다른 저장을 성공으로 세지 않는다. 기존 FAILED 및 all-failed 예외, 정상 sibling partial success를 사용한다.
- 신규17개 중9 RED→PASS, 관련5 suites65 PASS. 여섯 공개 입력형태·실제 anonymous support·generic UOE·fan-out과 실제 VectorStoreService flush pending/같은 ID retry를 확인했다.
- snapshot9행/3query/양순서 APPLY/94.5점 및 owner/preimage/postimage/reverse-check PASS. sourceSet/version/compile/app/bootJar 및 Java24/resource3/federated inner class 일치. 누적37원인/27production 파일.
- 최초 fixture 컴파일 오류와 적용 전 newline anchor 실패를 별도 기록했다. 삭제된 fallback 작업의 static log assertion2개만 제거했다. 배포 capability/partial persistence/physical DB/provider/Browser는 미관측이며 원인1개로만 집계한다.

## 단위 AM: ROLLING-SUMMARY-WATERMARK-01 — 적용 및 검증 완료

- 완료 ID보다 앞선24행만 검사한 경우 watermark를 실제 검사한 eligible 최대 ID까지만 기록한다. meta/blank 검사 행도 진행 기준에 포함하고 빈 eligible 결과는 기존 반환을 유지한다.
- 신규6개 중4 RED→PASS, 관련6 suites36 PASS. 실제 RSUM 행이 page를 차지하는30-turn backlog에서34→41 catch-up을 검증하고 exact24·meta-only·upper-bound·중복/과거 완료를 대조했다.
- 최초 GREEN35/36의 본문/메타 중복 assertion을 test-only 보정하여6개만 재실행했다. 나머지 통과5 suites XML을 보존하고 original watermark/query/turn assertions를 유지했다.
- snapshot9행/3query/양순서 APPLY/94.8점 및 owner/preimage/postimage/reverse-check PASS. sourceSet/version/compile/app/bootJar와 Java24/resource3/history inner class 일치. 기존 history paging 함수 밖 byte identity 유지. 누적38원인/27production 파일.
- CITE-01 기존 count/승격11 PASS는 새 원인0개다. Ratio 정책과 실제 생산자부터 필요하다. 후속 호출 없는 완전 catch-up, 초기 newest12 정책, live DB isolation/실제 memory data/provider/Browser는 미인증이다.

## 단위 AN: QTX-HYPHEN-CORRECTION-01 — 적용 및 검증 완료

- correctWithLLM의 greedy label 구분자 목록에서 하이픈만 제거했다. non-empty/read-only·compound·숫자 범위·지명이 잘리는 한 원인이다. 기존 colon/arrow grammar와 cache/cleanup/fail-soft/original-first는 유지한다.
- 신규18개 중14 RED→PASS, 관련8 suites68 PASS. public transform의 완전한 교정문과 원문 우선 대조, 독립 variant 응답, 정상 라벨/숫자 prefix/cache/failure/disabled를 확인했다. fixture 수정 없이 통과했다.
- snapshot9행/3query/양순서 APPLY/95.85점 및 owner/preimage/postimage/reverse-check PASS. source2바이트 외 기존 hunk를 보존했다. sourceSet/version/compile/app/bootJar와 Java25/resource3/transformer inner class 일치. 누적39원인/28production 파일.
- 비활성 test/java Fanout 참조는 실행 증거에서 제외하고 실제 src/test/java suite를 선택했다. EMPTY-01 기존 실패 분류2 PASS는 새 수정0개다. 원문 전체 소실/최종 ranking/실 공급자/Browser를 인증하지 않는다.

## 단위 AO: RC-05 최종 모델 메시지 경계 — 현재 방어 재검증

- 실제 public continueChat에서 synthetic builder 지시/근거와 사용자 문구를 local model에 전달하고 전체 문자열·System/User 역할·순서를 캡처했다. known asset/trait는 실제 asset service로 로드하고 public literal/missing asset은 거부한다.
- 새4 capture + 기존 release1/boundary·public-system5 =10 PASS. 각 모델 호출1회, same PromptContext, 거부값 raw trace 없음, durable writers 미호출을 확인했다.
- test owner/preimage/postimage/reverse-check PASS. 기존 test bytes, production과27차 JAR hash 그대로다. 새 source 원인0개; 누적39원인/28production 파일. 과거100행 의미 판정34개/미판정66개.
- 합성 FACT/FULL non-web/non-RAG final 경계다. 전체 PromptBuilder 의미나 streaming/multimodal/strict-plan/실 공급자를 인증하지 않는다.

## 단위 AP: AL-08/STKG-10 — 결과 탐침 및 기존 저장 방어 재검증

- 실제 temp-file/controller MockMvc에서 physical false/예외 시 retained bytes·metadata 제거·200·DELETE_FAILED를 확인했다. 정상 삭제/이미 없는 파일·기존 admission/retention/storage 대조 포함16개 중15 PASS/Windows symlink1 SKIP. 새4개는 current-behavior characterization이다.
- Public2xx의 physical-completion 계약은 미입증이다. W1 cleanup attempt/failed reason 계약을 보존하고, source handler 노출 추론과 실제 GET proof를 구별한다. Live attachments/ACLs/DB는 사용하지 않았다.
- STKG-10은 기존 staged forced-file/atomic-replace와 shutdown persistence2 PASS. Fallback filesystem 원자성/다중 writer는 미인증이다.
- 총18개 중17 PASS/1 SKIP. owner/preimage/postimage/reverse-check 및 production/27차 JAR identity 유지. 신규 source 원인0개, 누적39원인/28production 파일. 과거100행 의미 판정36개/미판정64개다.

## 단위 AQ: STKG-03 — 도메인별 지식 identity 수리, R02/R03/R04 반증

- 실제 H2 신규 스키마에서 기본 service의 A/X+B/X 저장 RED를 확인하고 entityName 전역 unique 옵션을 제거했다. 기존 domain+entityName 복합 index는 유지한다.
- Legacy overlay 초기 조회/제약 충돌 재조회 모두 scoped finder를 사용한다. 다른 도메인 행을 재사용하거나 이동하지 않는다. Repository는 stale 주석만 수정했다.
- 신규8개 중6 RED→8 PASS, 기존 KB5+SingleFlight12+ExtremeZ2를 합쳐27 PASS. Exact-domain/unique mismatch fallback 보존, ambiguous global empty 처리, same-domain duplicate rejection, 실제 overlay와 mock collision 대조를 포함한다.
- 단일3query9행/93.2점/양방향 APPLY와 owner/preimage/postimage/reverse-check, sourceSet/version/app/bootJar 및 Java28/resource3 JAR identity PASS. 누적40원인/31production 파일이며 source1개는 주석만 변경했다.
- R02는 기존 timeout/owner cancellation12 PASS로 추가 수정0개. R03 취소 제안은 의도된 cache warming/CancelShield 정책과 충돌해 철회했다. R04는 caller interrupt를 소비하는 명시적 경계의 기존 동작2 PASS다. 새 결함으로 세지 않는다.
- 기존 운영 DB migration은 not_observed다. 승인된 read-only constraint inventory 후 검토된 DDL이 필요하며, source 변경만으로 기존 제약이 제거됐다고 주장하지 않는다. 라이브 DB/실제 동시 트랜잭션과 새 JAR Browser/provider proof도 없다.

- 최종 diff의 기존4줄 LF 정규화를 fresh3query/owner guard로 원래 바이트에 복원했다. 신규8개 재실행/bootJar PASS, 전체 추적 class bytes 동일. 초기 보존 주장에는 이 correction을 함께 적용하며, 최종 source hash/patch는 `thirtieth-source-final-postimages.json`과 `thirtieth-overlay-final.patch`를 사용한다.

## 단위 AR: R07/R09/R10/STKG-06/STKG-07/PM-03 — 실제 결과와 정책 반증 대조

- 실제 controller Flux: 두 세션 해시·sessionless 이벤트·hello를 함께 반환한다. ADMIN 경계는 현재 source/static contract로 확인했고 live HTTP auth는 not_observed다. 관리자 공유 ops 진단을 세션 혼합만으로 취약점으로 세지 않는다.
- 실제 BrainStateService: 같은 청크 재수집 시 chunk1 유지, mention/relation count 증가, sparse2→0; supplied ID를 다른 세션으로 교체하면 이전 accumulator가 남는다. Prepared ID와 Neo4j composite identity가 달라 putIfAbsent를 섣불리 적용하지 않는다. Count 단위와 replacement 계약부터 정한다.
- R10은 기존 Spring executor·context propagation·terminal8 PASS. R09 FOUND는 철회하고 실제 concurrent caller/thread-safe 계약을 요구한다. Start-barrier만으로 deterministic RED를 주장하지 않는다.
- PM-03 targetChars/output budget/cost warning은 final input-window 정책이 아니다. STKG-07 snapshot-cache TTL/output count도 저장 eviction 계약이 아니다. 명시적 기준 없이 prompt 삭제나 지식 eviction을 추가하지 않는다.
- 새3 characterization/2테스트 파일, 관련51 PASS. Owner/preimage/postimage/reverse-check와 production/30차 JAR identity 유지. 새 source 원인0개, 누적40원인/31production 파일. 과거100행 의미 판정46개/미판정54개다.
- AutoLearn manifest/cycles/rejected 필수 입력이 지정 경로에 없어 handoff-dependent source 적용만 evidence_needed다. Independent analysis와 다른 source repair를 계속한다.

## 단위 AS: 공개 출처 raw URL path 수리 및 설정 검사 실패 분리

- PUBLIC-CITATION-RAW-PATH-01: URI component constructor가 getRawPath의 percent를 다시 인코딩해 문서 경로를 바꿨다. 기존 authority 검증 뒤 raw path를 그대로 붙이는 두 줄만 수정했다. Query/fragment/user-info 제거 및 기존 거부/길이 정책은 보존한다.
- 새15개 중8 RED→15 PASS, 기존 attribution17/boundary7을 합해 출처39 PASS. 실제 public promotion/appendix 경계를 검사하며 semantic gate는 stub이다. 이전 ranking identity 원인과 구별하여 새 원인1개로 집계한다.
- 전체49개 중48 PASS/1 FAIL: RuntimeConfigShadowGuardTest의 opt-in false 기대와 application-llm.yaml의 enabled/autostart true 기본값이 충돌한다. 이 기존 설정 hunk의 의도와 owner 계약을 확인한다. 검사 완화를 통해 GREEN을 만들지 않는다.
- CFG-02 modern activation은 isolated dev ConfigData context1 PASS로 기존 방어를 재확인했다. 추가 source 수리0개다.
- 단일3query/97.8점/양방향 APPLY, owner/preimage/postimage/reverse-check, sourceSet/version/app/bootJar PASS. Java29/resource3 및 등록된 class entries의 새 JAR identity를 확인했다. 전체 테스트 GREEN 및 새 runtime/Browser/provider proof는 아니다.
- 누적41원인/32production 파일, 과거100행 의미 판정48개/미판정52개. CITE-02 기능성 query/fragment 정책은 계속 evidence_needed다.

## 단위 AT: 출처의 서비스/DTO 길이 경계 보완과 OCR 설정 실측

- 초기 public URL9개가 모두 RED여서 service1000 절단 뒤 DTO512+ellipsis가 있음을 확인했다. DTO 직접 대조4개를 더한13 RED를 만든 뒤 두 owner를 함께 수정했다. 서비스 단독 후보를 먼저 적용하거나 기존999/1000 기대를 낮추지 않았다.
- 서비스는 sanitize 후1000자를 넘는 URL을 제외한다. DTO source는 같은 masker를 통과한 문자열이 원본과 같고1000자 범위이며 URI 문법에 맞을 때만 유지한다. Masking/절단으로 바뀐 주소는 제외하고 다른 DTO 필드의512 제한은 유지한다.
- 새13 RED→PASS, 기존 출처/DTO/triadic caller 포함84 PASS. 실제 OCR ConfigData/빈 주입9개와 기존5개는14 PASS다. 합98 PASS, failure/error/skip0, 새22테스트/2파일이다.
- CFG-01은 generic ocr.enabled와 rag.ocr.enabled를 구분하고 실제 SystemEnvironmentPropertySource의 OCR_ENABLED/RAG_OCR_ENABLED 우선순위를 확인했다. 실제 OCR engine과 전체 retrieval chain 호출은 아니다.
- SEC-10은 일반 equals가 존재하지만 해당 evaluator의 실제 HTTP registrar를 bounded 검색에서 확인하지 못했다. LLM defaults true의 변경 의도도 bounded directive 검색에서 미확정이다. 이전 opt-in 검사 실패를 전체 GREEN으로 덮지 않는다.
- 단일3query9행/96.5점/양방향 APPLY, source2파일 owner/preimage/postimage/reverse-check 및 sourceSet/version/app/bootJar PASS. Java30/resource3과 등록된 entries의 새 JAR identity를 확인했다.
- 같은 PUBLIC-CITATION-RAW-PATH-01의 보완이므로 누적41원인을 유지한다. Production33파일, 과거100행 의미 판정50개/미판정50개다. 기능성 query/fragment, 긴 local filePath, 원격 도달성 및 새 JAR runtime/Browser/provider는 미인증이다.

## 단위 AU: 실제 실행 없는 broad runtime proof 차단과 미확정 정책 보존

- TBL-02에서 all-skipped5 suite가 executed0인데 passed=true인 실제 함수 결과를 확인했다. 실행4 suite에 empty/skipped suite가 더해져 breadth를 충족하는 것도 같은 원인이다. 새 도구 원인 BROAD-RUNTIME-SKIPPED-PROOF-01로 집계하고 앱 결함41개와 구분한다.
- `_broad_runtime_test_proof`의3줄 추가·1줄 교체로 tests > skipped인 suite만 기존 >4 기준에 센다. 기존5개 public field와 총개수는 유지한다. 부분 skip은 충분한 실제 실행을 무효화하지 않는다. 새8개 중4 RED→PASS, scorecard전체91 PASS다.
- 기존 소비자5개 중4 PASS/1 FAIL, 합96개 중95 PASS/1 FAIL이다. 저장된 scorecard JSON의 stale/local interaction/collection/peer evidence 부족을 실패 이유로 보존한다. 소비자는 새 판정 함수를 실행하지 않는다. 이를 통과시키려고 보고서나 assertion을 임의로 바꾸지 않는다. 이전 opt-in 설정 검사 실패도 남는다.
- 테스트 첫 적용의 줄바꿈 정규화는 postimage guard가 탐지했다. 테스트 실행 전 fresh owner/CAS로 원래 bytes를 복원했다. Source helper의 mixed EOL와 나머지 bytes를 보존하고 scoped reverse checks를 수행했다. Audit Python에는 application-source3query를 잘못 적용하지 않으며 owner/preimage guard는 유지한다.
- PM-08의 probe timeout/TTL은 현재 분류 service에 transport/cache 소비자가 없으며 의도된 owner 계약이 필요하다. HYBRID-01은 caller interrupt 복원이 존재하고 deadline 이후 worker lifetime 정책은 미확정이다. 새 source bug로 추가 집계하지 않는다.
- 등록된 application/companion40개 source identity와33차 JAR identity 유지. 이번 Java/resources 수정0, Gradle 재실행0, runtime/Browser/provider not_observed. 과거100행 판정53개/미판정47개, 누적42원인(앱41·도구1)/앱33파일+도구1파일이다.

## 단위 AV: 최신 검사 결과와 신선도 — 과거 성공으로 현재 실패를 가리지 않기

- 동일 Gradle 작업의 과거 PASS가 최신 실패·부분 실행의 누락을 채우는 실제 결과를 재현했다. RUNTIME-PROOF-RESULT-SELECTION-01은34차 executed-count 원인과 구분되는 새 검증 도구 원인이다.
- 두 proof가 공유 selector를 사용해 작업별 최신 XML 루트를 고르고 동률을 보존한다. Focused 클래스도 최신/tie 결과를 따르며 과거 성공으로 새 실패를 rescue하지 않는다. Broad는 task별 breadth를 합산하지 않고 실패/error·malformed·stale·미래 시각 companion의 불리한 증거를 유지한다. 기존24h/future300s artifact 정책과 broad5/focused3 필드를 유지한다.
- Initial candidate는 독립 FALSIFY의3개 companion 반례로 HOLD됐다. 소스에 적용하지 않고 반례3 RED 및 inventory 대조1개를 추가했다. 새22개 중16개 메서드 RED(실패 assertion17)→PASS. 수정 후보 재검토 SUPPORT와 NEUTRAL 양순서 APPLY 뒤 owner/CAS 적용, 전체 scorecard113+isolated consumers4=117 PASS다.
- 실제 저장된87730 XML metadata의 비교 전후 hash가 같다. 과거 판정은 broad1105 suites/7037 tests+focused4/4였고 현재 선택은 broad6 suites/84 tests+focused1/4다. 이 결과는 기존 artifact 선택 증거이며 새 Java 테스트 실행이 아니다. 전체 inventory는 관련 identity 변화 전 재스캔하지 않는다.
- 34차·33차 preimage까지 역구성해 source/test 무관한 bytes와 mixed EOL 보존을 확인했다. 현재 cumulative source/test patch를 역검증에 사용한다. 앱 소스/companion40개와 기존 JAR identity 유지, Java/resources 수정0·Gradle 재실행0이다.
- 이전 저장된 report readiness와 opt-in config 실패는 변경 없는 hash로 미해결 유지한다. XML mtime은 현재 source/branch/run 및 effective build output의 attestation이 아니다. 기존 breadth 기준을 전체 테스트 실행 증거로 과장하지 않으며 이 provenance 계약은 evidence_needed다.
- SEC-01 active Hybrid→Brave query 전달과 normalizer를 추적했다. 실제 no-network request recording과 적용할 privacy 변환 계약을 다음 단계로 남긴다. 아직 wire/provider 성공이나 credential 전송을 실제 관측했다고 주장하지 않는다.
- 누적43원인(앱41·검증2)/앱33파일+검증 Python1파일. 과거100행 판정54개/미판정46개, 전체100개 목표 ACTIVE다.

## 단위 AW: 선택적 Brave 상태의 Ops 부팅 경계와 실제 검색 요청 탐침

- TBL-06에서 orchestration을 끈 상태의 missing BraveRateLimitState 때문에 Ops가 부팅 실패했다. 새 context7개 중3 RED, 기존 대조4 PASS다. 독립 앱 원인 OPS-OPTIONAL-BRAVE-STATE-BOOT-01로 집계한다.
- Ops bean factory의 두 줄만 ObjectProvider/getIfAvailable로 바꾼다. Aspect는 원래 null-safe이며 Naver backoff가 독립적으로 동작하므로 aspect 자체를 조건부 제거하지 않는다. 실제 state는 재사용하고 기존 Ops/backoff/Class 조건을 유지한다.
- 새7개 포함 Ops39 PASS. 검색/마스킹/HTML trace76 PASS와 합115 PASS, error/failure/skip0. 새22개/2테스트 파일 중 source RED3개이며 최초 검색 fixture 실패4개는 결함 수에 넣지 않는다.
- SEC-01은 예산별 HTTP 클라이언트에도 복사되는 terminal interceptor로 외부 통신을 막는다. 실제 요청 생성11개에서 합성 query 보존을 확인하고 privacy block4개는 요청0이다. 공개 trace는 기존 HTML/SSE 경계가 redaction하므로 raw internal field만으로 공개 유출을 주장하지 않는다.
- 로그 마스커의 정상 기술 문구 변형 반례를 보존한다. Outbound redact/block 정책과 SEC-02 vendor endpoint 소유권 정책은 미확정이다. 과거 header-only 지시서의 좁은 범위를 현재 사용자 source authorization의 거부 근거로 쓰지 않는다.
- 단일3query9행/97.25점/양방향 APPLY와 owner/CAS, cross-subsystem shared-wiring context/compile/app 및 sourceSet/version/bootJar PASS다. Registered source41/class entries83/resource3와 새 JAR identity를 확인했다. 실제 전체 앱 boot/Browser/provider는 not_observed다.
- 활성 소스2296개에서 기존 제한 패턴 match17, 변경 source 전후0/최종 patch3개0이다. 값을 출력하지 않으며 match를 실제 credential 수로 단정하지 않는다. 초기 helper metadata parameter 오류는 source gate 전에 재실행으로 정정했다.
- 누적44원인(앱42·검증2)/앱34파일+검증 Python1파일이다. 과거100행 판정56개/미판정44개다. 이전 opt-in config와 stored scorecard readiness 실패 및 TBL-06 전체6개 configuration 조합의 미검증 범위를 보존한다.

## 단위 AX: WebClient MDC를 구독 스레드의 수명에 맞춰 복구하기

- 등록된 DebugPort customizer→Boot builder→naverWebClient→NaverSearchService 경로를 확인했다. 직접 만든 다른 WebClient가 customizer를 건너뛴다는 사실을 이 활성 caller의 반증으로 쓰지 않는다. 실제 customizer와 naverWebClient factory를 사용한 합성 recording transport가 탐침 seam이다.
- 새9개 중4 RED/5 PASS다. 동기 exchange throw는 복구 callback 설치 전 실패하고, 비동기 성공/error/cancel은 완료 worker에서 호출자 MDC를 복원해 두 스레드 상태를 오염시켰다. 독립 원인 WEBCLIENT-MDC-THREAD-SCOPE-01 하나로 집계한다.
- mdcBridgeFilter만 Mono.fromDirect의 구독 범위로 바꾸고 원래 Subscriber를 Mono.defer downstream에 전달한다. 같은 스레드 finally에서 MDC를 복원하고 완료 worker의 doFinally 복원을 제거한다. 조기 복구로 지연 subscription 추적을 잃거나 detached sink로 Reactor Context/cancellation을 바꾸지 않는다.
- 신규 lifecycle9 및 기존 debug/source, WebClient redaction, correlation, context/time-budget/disconnect를 합쳐47 PASS다. 지연 subscription, Reactor Context, 반복 구독, no-id/existing-id 대조를 유지한다. 실제 HTTP/provider 요청은0이다.
- 단일3query9행/94.5점/양방향 APPLY와 owner/CAS, compile/app/sourceSet/version/bootJar PASS. Registered source42/class entries85/resource3/template와 새 JAR identity를 확인했다. 메서드 밖 bytes와 기존 header/trace redaction, scheduler hook은 보존한다.
- Zero100의 선택적 executor와 FailurePattern의 master/feature 결합 조건을 반증으로 기록한다. Bare context에서 정상 필수 bean을 빼는 실험만으로 추가 production defect를 만들지 않는다. 남은 configuration runtime matrix는 미인증이다.
- 누적45원인(앱43·검증2)/앱35파일+검증 Python1파일이다. 과거100행56개 판정/44개 미판정은 유지한다. MDC 복구는 SEC-08 physical wire-attempt lineage나 모든 ThreadLocal 수명 인증이 아니다. 이전 opt-in config/stored scorecard 실패와 privacy/endpoint 정책의 미확정 범위를 보존한다.

## 단위 AY: RC-10 패키지 불일치 가설을 실제 AOP 실행으로 교정하기

- @Around annotation의 실제 expression과 네 구현의 실제 retrieve signature를 비교한다. Nova override의 다른 package만으로 AspectJ exclusion을 단정하지 않는다. 매칭4개 모두 PASS였다.
- 동일 production advice를 JDK/CGLIB proxy에서 실행하여 압축 결과 identity와 applied/beforeDocs/afterDocs trace를 확인한다. 구현4개×proxy2개8 PASS, disabled/ordinary-mode/single-document 대조3 PASS다. Provider 및 retriever body는 mock이며 외부 요청0이다.
- 새15개, 기존 aspect4/compressor44/order28 합91 PASS다. 초기 getOrCreate 컴파일 오류는 fixture 수정이며 source RED가 아니다. 기존 pointcut과 compressor production을 그대로 유지한다.
- RC-10의 확인되지 않은 우회 수정안을 철회하고 실제 다형성 회귀 테스트를 보존한다. 전체 ChatWorkflow 실행/Boot context wiring이나 전 입력 공간의 압축 품질을 이 결과로 인증하지 않는다.
- 과거100행57개 의미 판정/43개 미판정, 누적45개 수리 원인으로 기록한다. 회귀 파일1개 추가는 새 production 결함 수를 늘리지 않는다. Source/companion42개 및 이전 JAR identity 재확인, patch reverse/owner/secret 검사 PASS다.

## 단위 AZ: RC-06 실제 동적 gate 입력과 정책 차이 보존

- RetrieverChainConfig dynamic 분기→실제 chain.handle→repair 결과 추가→recording real gate 경로를 실행한다. 기본 fixed와 전체 Boot/provider 증거는 구분한다.
- Nonempty4개에서 출처 중복률0/.75/0/1과 무관하게 gate score1/risk0, strong은 count 기반임을 관찰했다. Empty4모드와 optional gate/forced BLOCK/forced DEGRADE/이전 trace/질문 변경5대조를 합쳐 새13 PASS다. 기존40개 포함53 PASS, source RED0이다.
- Coverage는 nonblank 비율이며 의미 관련성으로 재명명하지 않는다. 이전 diagnostic 값을 현재 request metric으로 재사용하지 않는다. Forced gate result 대조를 실제 품질 판정 실패로 세지 않는다.
- 미사용 FinalSigmoidAccumulatorPolicy의 다른 score·BLOCK·DEGRADE 방식을 현재 chain에 그대로 연결하지 않는다. 기존 지시 부재는 권한 veto가 아니며, 필요한 signal/retention 계약이 미정이라는 근거다. 해당 source 변경만 evidence_needed, repositoryWideHold=false다.
- RC-06은 actual arguments characterized 상태로 기록하고 과거100행58개 판정/42개 미판정, 누적45수리 원인을 유지한다. RC-11은 직접 import 부재만으로 upstream DPP를 부정하지 않는다. 실제 dynamic→DPP→Hybrid fusion→CE 경로를 다음 member identity 탐침의 근거로 사용한다.

## 단위 BA: 작은 DPP determinant의 절대 동점 오판 수정

- Canonical DppDiversityReranker의 greedy comparator가 절대1e-12 때문에 서로 다른 nonzero determinant를 동점으로 취급하는 반례를 보존한다. 겹치지 않는 텍스트의 대각 kernel, 양수 relevance, lambda0/.7/1로 재현하며 임의 다양성 정책을 기대값으로 넣지 않는다.
- 낮은 점수5개/k4와 보통 점수10개/k8의6 RED, 큰 점수·진짜 동점·입력/객체 보존7 PASS를 확인했다. 두 점수 크기에 비례한 tolerance로 source1파일3줄 추가/2줄 제거했다. 큰 k underflow 또는 전체 수치 안정성까지 인증하지 않는다.
- 단일3query9행/95.25점과 양방향 APPLY, 기존 owner/lease/CAS 후52초 이내 적용했다. Source preimage83E1352B…→postimage6F147CCA…를 exact bytes로 검증하며 관계없는 hunk를 보존한다.
- 새13개 포함64 PASS 및 version/sourceSet/compile/app/bootJar PASS, 새 JAR의43등록 source/companion와97 class/resource3/template 일치를 기록한다. 이전85 class와의 차이는 검증 inventory 교정이며 새 클래스 생성량으로 세지 않는다.
- Source/test patch reverse PASS, 초기 owner hold와 유효하지 않은 Gradle 호출을 기록하고 unknown index.lock은 유지한다. Active source 전체2307파일17 pattern matches는 credential 확정이 아니며 변경 source pre/post·patch2개는0이다.
- 누적46원인(앱44·검증2), 앱36파일+검증 Python1파일이다. RC-11의 후속 fusion/CE membership 변화는 별도 미실행으로 남기며 과거100행58/42를 유지한다. 새 source numerical defect로 RC-11 전체를 완료 처리하지 않는다.

## 단위 BB: DPP 이후 Hybrid/RRF 문서 보존과 제한 구분

- 실제 DPP를 합성 RetrievalHandler에서 실행하고 실제 Hybrid.retrieveAll 및 real RRF를 순차/병렬로 실행한다. Dynamic chain 전체 wiring 또는 weighted fusion을 실행한 것으로 표기하지 않는다.
- 두 branch의 고유2+2/공통1개가 budget3에서 전부 유지되며 첫 Content 객체를 보존함을 확인했다. Budget2 subset, explicit candidate cap1 prefix, keepN2 하한, keepN1의2배 후보 대조를 각 모드에서 실행하여 새10 PASS다.
- 실제 fuser1회/terminal success로 timeout/flatten fallback을 배제한다. 새10개 포함50 PASS, source RED0이다. 의도된 cap 또는 순서 변화만으로 diversity failure를 만들지 않는다.
- CE의 현재 source gate/candidate/fallback 경로를 기록하되, 기존 public continueChat fixture가 web/RAG를 끄므로 실행 증거가 아님을 보존한다. 다음에는 captured fused input과 recording named reranker로 actual continueChat 경계를 실행한다.
- 누적46수리 원인, 과거100행58/42를 유지한다. 43 source/companion와 기존40차 JAR identity는 재확인했고 새 runtime/provider 증거는 없다. Test patch reverse/owner/secret0 PASS, 이전 opt-in/stored readiness 실패는 미해결이다.

## 단위 BC: 실제 ChatWorkflow CE 선택과 프롬프트 전달 확인

- 실제 public continueChat에 AUTO/web+RAG 요청, synthetic Hybrid8개 결과, real PlanHintApplier 적용 및 RerankKnobResolver, recording named CE를 연결한다. Plan load/model/vector/provider/저장소는 mock이며 물리 provider 증거로 확대하지 않는다.
- 후보 전체, explicit cap, keepN 하한, keepN만 지정한2배 후보, CE 비활성, empty/exception fallback의7개를 실행했다. 실제 Hybrid 호출1회와 선택된 원본 Content가 prompt promotion까지 같은 identity로 전달됨을 확인했다. Durable writer interaction0이다.
- 초기 forceLightSearchMode 지역 변수를 field로 오인한 fixture1줄을 수정했으며 source RED가 아니다. 새7개/기존32개 합39 PASS, source 변경0이다. 같은 field 설정 오류를 반복하지 않는다.
- 실제 scorer 품질·source/domain quota·dynamic factory/weighted 전체 wiring은 미인증이다. RC-11의 policy/source 변경만 수용 조건 evidence_needed로 남기고 해당 단계의 characterization을 반복하지 않는다. 41차와 이번 경계를 완전한 단일 production pipeline 실행으로 합치지 않는다.
- 누적46수리 원인, 과거100행59/41을 유지한다. Registered43/JAR identity 재확인, test patch reverse/owner/secret0 PASS다. 다음 TBL-01은 intentional ledger readiness와 실제 gate 성공 계약을 구별한 후 결함 여부를 판정한다.

## 단위 BD: 기본 LLM 빈의 설정 토큰 한도 전달 및 TBL-01 반증

- @Primary LlmConfig.chatModel의 generic builder에서만 양수 maxTokens 전달이 빠졌다. Native는 이미 전달했으며 활성 직접 소비자는 LLMNamedEntityExtractor다. 일반 ChatWorkflow의 요청별 dynamic rebuild 및 Responses 경로는 별도 계약으로 남긴다.
- 실제 모델 defaultRequestParameters를 검사하는 새10개 중 legacy1/512/4096·completion1/512의5 RED를 재현했다. 기존 tokenParamKey를 사용해 legacy/completion/OMIT를 구분하는9줄을 추가했다. null/0/음수/native·모델명·credentials·headers/retries 보존, 새10+기존14 합24 PASS다. Provider/chat 요청0이며 실제 usage/절감을 주장하지 않는다.
- 동일9행 snapshot으로 정확히3논리 query, 동일3scenario, A-B/B-A APPLY95.25, 기존 owner begin/verify/end 및 즉시CAS PASS다. Patch는9추가/0삭제이고 unrelated dirty bytes·EOL을 보존했다. Unknown index.lock을 제거하지 않았다.
- 필수 version/sourceSet/compile/app classes/bootJar5gate PASS와 새 JAR의 registered44개 source/companion class/resource identity를 확인했다. 이번 새 browser/boot/provider 증거는 없다. 기존 opt-in/stored readiness 실패를 PASS로 바꾸지 않는다.
- TBL-01은 artifact-only loop/consumer 계약과 기존7Python PASS로 false-execution-success 추정을 채택하지 않았다. Consumer가6gate 성공으로 승격한다는 구체적 반례가 생기기 전 새 실행 인프라를 추가하거나 같은 조사만 반복하지 않는다.
- 누적47수리 원인, 과거100행61개 의미 판정/39개 미판정이다. PM-04의 다른 cap owner는 미인증이며 Dynamic factory matrix OMIT 후보는 다음 실제 construction RED 이후에만 수정·계수한다.

## 단위 BE: 요청별 모델의 matrix OMIT를 실제 설정과 기록에 일관되게 적용

- DynamicChatModelFactory가 NONE/OMIT와 completion을 legacy boolean false로 합쳐 처리했다. 실제 matrix의 model/prefix/base/default4경로를 사용해 delegate token parameters·returned-model ledger·real decorator envelope를 함께 검사한4 RED를 재현했다. 이것은 한 독립 원인의4개 시나리오다.
- Native 분기 이후 기존3state token key를 사용하고 OMIT일 때 effective cap을 null로 만든다. Ledger는 양수 요청값을 normalizedRequestCap에 보존하면서 configuredCap/state/kind를 null/OMITTED/OMITTED로 기록한다. Envelope는 기존 null→unknown 계약을 따른다. Null/0/음수/native·정상 legacy/completion·headers/retries/modelName을 보존한다.
- 한 소스13추가7삭제, 새13개 포함7suite61 PASS. 새 probe는 실제 모델/실제 wrapper 생성만 수행하며 chat 요청0이다. 기존 integration의 owned loopback fixture는 외부 provider/wire 성공으로 확대하지 않는다.
- Mixed-EOL candidate 준비 실패와 첫 git-operation-active owner HOLD를 보존했다. Fresh writer0에서 재동결3query/3scenario AB/BA APPLY95.25, 두 번째 owner begin/verify/end·즉시CAS PASS, unknown index.lock 보존이다. 필수5gate·새 JAR registered45 source/companion identity·source/test reverse·secret0 PASS다.
- STKG-05 append는 source에서 확인했지만 RSUM meta filtering/latest-only read와 문자수/행수 계약 차이를 보존한다. 임의 retention/delete 정책을 도입하지 않는다. 정책 evidence_needed이며 새 수리 원인으로 세지 않는다.
- 누적48수리 원인, 과거100행62개 의미 판정/38개 미판정, 다음 PA-05 중첩 search advice recording probe다. Source 수정은 실제 중복 실행 RED와 단일3query/owner guard 이후에만 한다.

## 단위 BF: 두 검색 advice의 실제 bounded 프록시 호출 계수와 PA-03 timing 확인

- 선언된 HIGHEST+50/+60 순서로 두 real advice와 실제 Hybrid target을 AspectJProxyFactory에 연결했다. Source-default bounded=true를 fixture에 명시하며 full production aspect stack/런타임 env override를 인증하지 않는다.
- search/searchWithTrace 각각 empty-disabled expansion,1회 expansion-empty, trusted primary hit, privacy block의8개를 실행했다. Provider doubles의 모든 search overload 진입을 합산해1/1,2/2,1/0,0/0 회수를 확인했고 expansion은1,1,0,0회였다. Target body 및 outer/inner bounded skip도 확인했다. 물리 전송 증거로 확대하지 않는다.
- 새8개/기존73개 합81 PASS, source RED0·수정0이다. 기본 bounded 경로의 기존 방어를 증명했다. Nonbounded distinct-query retry를 request-wide cap 위반으로 판단하려면 해당 계약이나 same-query 재실행 반례부터 필요하다.
- PA-03은 producer/consumer 이름 차이와 plan-before-producer 순서를 함께 보존한다. Active post-Overdrive plan application edge 없이 alias만 추가하거나 인위적 producer→consumer 테스트를 실제 결정 RED로 세지 않는다.
- 누적48수리 원인, 과거100행64개 의미 판정/36개 미판정이다. Test owner/reverse/secret0 PASS; source45/JAR identity unchanged. 다음 token matrix의 base/global 충돌은 public configuration contract 및 상충/비상충 대조를 확인한 뒤 판정한다.

## 단위 BG: endpoint token matrix 우선순위 수리 및 shared profile 동시성 후보

- Public base-first 계약과 달리 global exact/prefix가 scoped exact/prefix/default를 덮었다. 지원되는 conflicting configuration6개 RED를 실제 enum 및 tokenParamKey에서 확인했다. Shipped global maps는 비어 있어 현재 배포 충돌은 not_observed다.
- OpenAiModelParamMatrix 한 source를 endpoint rule → global exact/prefix → global default 순서로10행 추가/5행 삭제했다. 문서에 global fallback을 명시했다. NONE 및 official safety, blank/unmatched fallback, exact/longest-prefix와 endpoint matching을 보존했다.
- 새19개와 관련 config/factory/compat 대조7 suites60 PASS. Source three-query APPLY92, owner/CAS/byte reversal/reverse patch/changed secret0 PASS. Version/sourceSet/compile/app/bootJar5 gates PASS; 새 JAR registered46/source·103 class·resource/template 일치. 6실패는 독립 원인1개다.
- EVID-01의 GENERAL count4 Force Pass는 현재 명시된 정책이므로 임의의 semantic threshold로 바꾸지 않는다. 별도로 GuardProfileProps 공유 singleton을 정상 요청들이 쓰는 concurrency 후보가 있다. A:MEMORY → B:FREE → A gate의 실제 요청 overlap을 먼저 재현한다. Single-request profile selection 확인만으로 동시성 안전을 주장하지 않는다.
- 누적49수리 원인, 과거100행65개 의미 판정/35개 미판정. 새 provider/runtime/browser 성공과 전체 저장소 GREEN은 주장하지 않는다.

## 단위 BH: 요청별 가드 프로필 소유권 수리

- 같은 real workflow에 두 요청을 latch로 겹쳐 MEMORY/false↔FREE/true의 실제 EvidenceGate 판단 혼입을 재현했다. Caller context 유무, 설정 오염, copy/executor 전달을 포함 새23개 중10 RED였다. 이는 원인1개다.
- GuardContext에 configured default/header guardLevel과 분리한 enum requestGuardProfile을 저장하고 copy에 전파한다. GuardProfileProps는 현재 request selection을 우선 사용한다. ChatWorkflow는 context 없는 호출에만 holder를 설치하고 finally에서 그 소유분을 정리한다. Caller/worker lifecycle과 explicit mode, projection selection, non-request setter 계약을 보존한다.
- Source3파일42추가/2삭제, 3query APPLY92.5 및 owner/CAS/byte reversal/reverse patch/changed secret0 PASS. 새23개와 기존91개 합17 suites114 PASS, 필수5 build gates PASS, 새 JAR registered49/source·116 exact classes 일치. S01-S08 알고리즘/PromptBuilder/autoconfig body·기존 trace keys는 바꾸지 않는다.
- GENERAL Force Pass 정책을 이번 수리와 혼동하지 않는다. PM-01은 W/V snippets의 SystemMessage 경로와 deliberate grounding 설명, ensemble 전용 방어 및 expander UserMessage 경로를 모두 기록했다. 실제 message capture/지시 추종 결과 없이 새 source defect로 세지 않는다.
- 누적50수리 원인, 과거100행66개 의미 판정/34개 미판정. Registered JAR identities와 수리한 소스 집합, assertion 수와 root 수를 각각 구분한다.

## 단위 BI: 실제 메시지 역할 및 SerpApi 진단 범위 확정

- 실제 normal workflow와 StandardPromptBuilder의 Web-only/vector-only/both3개 최종 모델 호출에서 근거의 SystemMessage 배치와 원래 질문의 UserMessage를 캡처했다. 초기3실패는 preprocessor mock의 null intent로 router 대역이 맞지 않은 fixture 오류이며 source RED가 아니다.
- 실제 expander의 evidence 유무2개는 UserMessage 하나를 사용한다. Role 구성은 확인했지만 합성 응답 모델의 지시 추종을 검증한 것이 아니므로 prompt injection 수리 원인을 추가하지 않는다. 기존 deliberate grounding/ensemble framing을 보존한다.
- SerpApi success/HTTP500/ResourceAccess3개는 query auth URI 존재를 기록하되 provider/base DEBUG 로그와 공개 TraceStore의 합성 key/query/URI·throwable 노출0을 검증했다. 물리 외부 요청0; transport/proxy/upstream logging은 미검증이다. Vendor contract 없이 header auth를 도입하지 않는다.
- 세 test source를 추가해 기존44개 포함52 PASS. Application source 수정0·새 수리0, patch reverse/changed secret0/owner gates PASS. 누적50수리 원인과67개 historical 의미 판정을 구분한다. 47차 JAR identity 보존은 새 runtime 증거가 아니다.

## 단위 BJ: Zero100 활성화 효과 및 별도 RAG endpoint 계약

- Real aspect/registry12개에서 활성9·비활성3, touch/feedback·scheduler projection hints·proceed를 확인했다. Scheduler hint 수를 실제 실행/예약 job 수로 표기하지 않는다. 인용/부정/embedded marker와 false+plan/marker도 현재 활성화되지만, 명령형 전용 또는 false-veto 계약 없이 새 정책으로 바꾸지 않는다.
- Chat과 RAG는 별도 request/response 계약을 제공한다. 기존 endpoint/facade 대조와 합52 PASS, 동일한 owner/계획/검색/근거를 요구하는 계약은 확인되지 않았다. 구조 차이만으로 추가 defect를 계산하지 않는다.
- Test1파일 추가·application source 수정0·새 수리0. 정확한 test patch reverse/secret0/owner gates PASS; 기존47차 JAR 및 source49개 동일성은 새 runtime proof가 아니다. 누적50수리 원인과 historical69개 판정을 구분한다.

## 단위 BK: 실제 caller terminal interruption을 facade까지 보존

- Real async graph worker 진입 뒤 기다리는 caller를 실제 interrupt했다. Direct graph는 terminal cancellation을 보존했지만 PRIMARY facade는 legacy를1회 새로 호출하고 응답으로 바꿨다. 새4개 중1 RED·3대조 PASS 및 기존10 PASS이며 원인1개다.
- Facade catch에서 CancellationException과 현재 caller interrupt flag가 함께 있을 때만 같은 예외를 재전달한다. Source1파일5추가/0삭제다. Bare stage cancellation·일반 예외 fallback, SHADOW/OFF 및 graph cancel(false)를 보존한다. Worker 종료는 fixture release/join으로 별도 확인한다.
- 새4개와 모든 facade/graph executor/controller 대조57 PASS, 3query APPLY96.5·owner/CAS/byte reversal·source/test reverse·changed secret0 PASS. 5 build gates 및 새 JAR registered50/source·118 exact class/resource/template 일치. HTTP cancellation나 provider 성공은 별도 not_observed다.
- 누적51수리 원인, 앱42파일+검증1파일. Historical RC-02의 endpoint 구조 판정은 유지하고 이 source 수리를 독립 companion 증거로 연결한다. Historical69개 의미 판정과 수리51개를 혼동하지 않는다.

## 단위 BL: 지원 계획 힌트 소비와 명시된 DSL 미지원 경계

- 기존2 suites 24 PASS. Real UnifiedRagOrchestrator의 실제 stage별 반환 개수와 request ONNX flag, 별도 PlanHintApplier의 nested/legacy 매핑 및 거절을 확인했다. DSL-only llm/guard/plan.when/plan.pipeline의 unwired 표시를 지원 필드 무시로 오인하지 않는다.
- 모든 runtime consumer·provider 시간 제한·실제 self-ask 생성 또는 full DSL 실행을 검증한 것은 아니다. 새 source/test 수정 및 독립 수리0. RC-03 의미 판정만 갱신해 historical70/30이고 누적 수리는51개다.
- RC-04 controller→workflow의 반복 적용 구조를 확인했지만 잘못 바뀐 지원 값/추가 부작용은 미발견이다. 정확한 실제 호출 count는 미검증으로 유지한다. Fixture를 찾은 사실이나 별도 테스트 통과를 연결된 end-to-end 실행으로 표기하지 않는다.

## 단위 BM: 반복 계획 적용의 실제 범위와 지원 값 확인

- 명시된 plan ID에서 controller 동기 selection/load/guard apply각1회, stream은 사전 budget/worker의 서로 다른 컨텍스트에서 각2회다. 서비스 대역 위임은각1회·controller meta apply0회이며 web/RAG cap 및 ONNX/rerank/minCitations를 유지한다.
- 별도 real workflow는 사전 guard 적용 여부2사례 모두 load/guard/meta apply각1회·retrieval1회다. 실제 retrieval 인자의 지원 값이 일치한다. Controller→workflow 전체 연결이나 auto-selection normalization count는 이4사례의 증거가 아니다.
- 초기2실패/후속1실패는 요청 해석기 반환 및 SSE 등록 대역 누락이었다. 수정 후44 PASS, 실패 XML 보존, source RED/수리0. Test2파일 byte inverse/reverse/secret0/owner PASS. 누적51개 수리와 historical71개 판정을 구분한다.

## 단위 BN: 요청 진입 trace 격리와 계획·integrity checkpoint

- 기존 direct7개 포함27 PASS. Workflow 진입이 이전 outCount를 지우므로 기본 NORMAL, 이전 outCount+caller high-risk 대조는 HYPERNOVA다. Direct compute의 EXTREMEZ 결과를 workflow에 그대로 투영하지 않는다. 최초 oracle1실패를 보존하고 outCount 부재 assertion2개를 보강했다.
- Planner/retrieval 시점에는 plan이 이미 있고 integrity는 없으며, 실제 prompt filter 후 합성 입력1/거절1/사용0이 기록된다. Mode는 checkpoint에서 유지된다. 전체 TraceStore write 로그·provider quality·현재 결과를 통한 의무 재계획 계약은 입증하지 않았다. source RED/수리0이다.
- QueryBurst 소비는 재계산 이전이다. cancelShield.breadcrumb는 plan.knobs를 통해 NORMAL에서 false로 재설정되므로 조건부 true 코드만 보고 고착으로 판단하지 않는다. Test1파일 byte inverse/reverse/secret0/owner PASS, 초기 Git 활동 HOLD는 쓰기 전 해소했다.

## 단위 BO: 자동 금융 계획의 영문 약어 경계 수리

- 실제 GENERAL temperature가 내부 per에 걸려 금융 계획으로 잘못 간1 source RED를 수정했다. weather는 원래 정상 대조다. 금융 판별식만2추가/1제거, 기존 dirty 첨부 처리와 branch priority는 그대로 보존했다.
- ETH/PER/Ethereum/NASDAQ100/ETH가격 및 금융·비용·겹침의 실제 loaded/applied officialOnly/CE/topK 대조를 포함해47 PASS. Consumer package 오기를 XML로 식별하고 빠진7개만 추가 실행했다. 필수5 build gates와 새 JAR source51/class119/resource3+template PASS.
- 일시 Git writer를 실제 count로 확인한 뒤 bounded settle→기존 verify→CAS로 적용했다. Guard/unknown index.lock 변경0, exact inverse/reverse/changed-secret0·lease cleanup PASS. PA-07 우선순위 관측과 raw-only/RC-09 미확인 사항을 독립 수리 수에 추가하지 않는다.

## 단위 BP: 비용·속도 부분 일치의 같은 원인 보완

- 실제 breakfast/costume 오분류2 RED를 고쳤다. fast/cost 및 faster/quickly/cheaper/costs를 보존하는 경계로 predicate1곳만2추가/1제거했다. 이전 금융·첨부 변경의 exact byte inverse와55 PASS를 확인했다.
- 같은 자동 선택기·무경계 Latin token 원인이므로 기존 root에 통합하며 누적52개를 유지한다. Test8개 추가, 필수5 build gates 및 새 JAR source51/class119/resource3+template PASS, changed-secret0·owner/CAS/cleanup PASS.
- TBL-03 source/doc를 읽었지만 check dry-run은 기존 MCP2.0.0 라이브러리2개가 offline cache에 없어 graph 전에 실패했다. Full graph는 evidence_needed이며 앞의 실제 빌드 성공과 섞지 않는다. 의존성 cache가 준비되면 저장된 명령을 재실행한다. 검증 wiring을 구조 차이만으로 바꾸지 않는다.

## 다음 실행 단위

2026-09-08 사용자가 목표를 E2E 우선으로 갱신했다. **앱이 있으면 기존 PID를 재사용하고 재기동하지 않는다. 앱이 없을 때만 같은 SHA-256 JAR을1회 기동한다.** 이 지시가 과거100행 감사의 다음 후보보다 우선한다.

1. 비용·속도 보완은55 PASS·필수5 build gates·JAR `FB9F76A4C055E78CA96CB68CBDBBDFF5C557F6DEAB71EDDCAC427139DBDE828E`까지 완료됐다. DocumentChunkingService 후보는 읽기만 했고 실행 RED/수정0으로 보류한다. 누적52개 수리·과거100행73/27 판정은 역사적 집계이며 새 목표의 완료 판정이 아니다.
2. 기존 PID10064/18172와 PID20888/50549는9월4일 시작의 classpath 실행이다. 앱 재기동/중단/새 기동0으로 PID10064에서 상한5개 요청을 수행했다. 실제 브라우저 정답42 및 명시적 cancel accepted=true, exact-run state3회 running=false를 확인했다. 이 증거를 새 JAR 실행이나 provider 성공으로 확대하지 않는다.
3. 로컬 debug/trace와 PID23524의 Gradle daemon stdout으로 요청별 증거를 복구했다. 검색 후보의 네이버 HTTP200 응답1회·retrieval0, 30초 요청의 네이버 응답6회·retrieval0을 확인했다. 브라우저 정답42 요청은 LLM client HTTP response의 primary health_down 실패→fallback 성공2행으로 연결됐다. 다른30초 요청은 모델 client exchange3회 중 fallback도 실패했다. 네이버 응답 횟수·모델 client 시도·Decision Evidence Reconstruction의 linked receipt count를 서로 대체하지 않는다. provider receipt/wire는 별도 미관측이다.
4. 후속6번은 UI 모델 단계 후 취소,7번은 실제 primary health_down의 client HTTP exchange/response1행(3,192ms) 확인 후 취소했다. 각 회차 상한1요청·Stop1회를 지켜 누적7요청·취소3회다.7번은 요청 시작7,471ms 후 cancel200·cancelled=true, state3회 모두 terminal cancelled였고, 취소260,491ms 후 snapshot까지 후속 완료 행0이다. 그러나 LLM_REQUEST_PROOF는 delegate 반환/실패 뒤 완료 기록이며 ordinal도 그때 부여된다. elapsedMs를 빼 실제 dispatch 시각으로 만들거나 기록 부재를 신규 호출0회로 바꾸지 않는다. 취소 순간의 in-flight 및 물리적 no-new-call은 not_observed다. HTTP302/login은 로컬 로그가 제공하는 범위의 E2E를 막지 않지만 인증된 메모리 snapshot 접근은 별도 미확인이다. raw prompt/response·capability·키는 저장하지 않는다.

   **현재 PID와 완료 로그 경계가 그대로이면 추가 생성 탐침을 반복하지 않는다.** 현재 debug/trace/daemon 및 exact-run state의 plan ID 부재는 그대로 기록한다. TraceSnapshotStore는 memory_only/restartDurable=false이고 디스크 저장 경로가 없다. 다음 행동은 인증된 snapshot summary 접근 상태가 바뀐 경우 기존 requestHash만 대조해 allowlisted 메타데이터를 읽는 것이다. 미변경이면 evidence_needed를 유지하고 동일 스캔·재요청·앱 재기동을 하지 않는다.
5. 새 runtime identity 또는 권한 있는 요청별 증거에서 현재 소스의 재현 RED가 나올 때만 기존3query/owner/lease/preimage/CAS의 최소 수리로 이어간다. opt-in·프로퍼티·키 임의 변경, 기존 앱 재기동, 장식적 provider/DB 호출, 일반 감사 자동 재개는 하지 않는다. TBL-03 offline cache 부족과 기존 opt-in/stored-readiness 실패는 각 lane의 미해결 사항으로 유지한다.

현재 E2E 목표 BLOCKED, repositoryWideHold=false. 기존 브라우저의 GET /api/diagnostics/trace/snapshots?limit=1도302/login으로 확인됐다. 같은 필수 시작·취소 증거 차단이3회 이상 연속되고 가능한 독립 읽기를 마쳤으므로 ADMIN 진단 세션 준비 또는 결정적인 접근 증거 변화가 필요하다. 이번 후속 생성 요청/Stop/source/test 변경0이며 기존55 PASS XML4개를 hash/count로 재검증했다. 기존 PID 재사용은 사용자 조건을 충족하고, 새 JAR 기동은 앱이 없을 때만 적용하는 분기다. 최신 실행 증거는 `verification/chat-debug-events-readback/runtime-e2e-summary.json`과 기존 `RESULT.md`의 현재 실행 절이다. 55차 build/source proof는 `fiftyfifth-integrity-summary.json`의 당시 증거로 별도 보존한다.
