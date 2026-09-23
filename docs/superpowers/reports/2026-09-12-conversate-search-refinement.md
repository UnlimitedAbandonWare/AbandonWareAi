# Conversate A5 — 제한된 검색 보완과 읽기 오류 구분

전체 목표는 ACTIVE·미완료다. A4 로컬 생성 연결에 이어 기존 prepared-material 검색을 보완했다. 실제 휴대폰·Meta Display, 의미 기반 벡터 검색, 실제 모델 응답 품질을 완료했다고 판정하지 않는다.

## 변경 경계

- `ConversateAnswerPipeline.java`: 최초 BM25가 정상 0건인 경우만, 선택한 허용 자료의 어휘로 유일하게 나눌 수 있는 한국어 토큰 하나를 두 단어로 나눠 한 번 재검색한다. 예: `보증기간 → 보증 기간`. 새 사실·일반 facet·숫자 삭제·문장 축약·추가 모델 요청은 없다. 기존 검색이 맞으면1회, 보완하면 최대2회다. 분할이 모호하거나 허용 어휘에 없으면 기존 NO_MATCH를 유지한다.
- 같은 파일: 새 부정 반례가 기존 조사 제거 오류를 재현했다. `수리불가`의 마지막 가를 지우던 정규화를 고쳐 불가로 끝나는 토큰을 보존한다. 일반 한국어 형태소 분석 전체를 해결했다는 의미는 아니다.
- `ConversatePreparedMaterials.java`: DataAccessException은 원문 없는 `503 material_index_unavailable`, 빈 문서 본문은 `422 material_parse_failed`. 기존 owner/session/attachment ACL과 읽기 전용 트랜잭션을 유지한다.
- `ConversateController.java`: 읽기 트랜잭션 진입 전에 실패하는 경우도 DataAccessException/TransactionException 경계에서 동일한 no-store503으로 반환한다. 드라이버 오류 원문은 응답에 넣지 않는다.
- `ConversateSessionService.java`, `static/conversate/app.js`: 세션당 누적 실제 로컬 검색·보완 횟수를 기존 진단에 추가한다. 쿼리 문자열이나 식별자를 metrics label로 저장하지 않는다.

검토한 `StochasticExpander`는 로컬 순수 함수지만 일반 facet을 붙이거나 앞부분만 남기는 후보를 만들 수 있었다. 정상 0건 이후 관련 없는 가이드 문서가 선택될 수 있어 이 경로에 연결하지 않았다. `AdaptiveSearchQueryVariants`의 외부 제공자용 후보 체인도 재사용하지 않았다. 새 서비스·의존성·영속 인덱스는 추가하지 않았고 기존 BM25/lexical owner를 유지했다. 이 보완은 의미 동의어 검색이 아니다.

## 검증 증거

| 시험 | 명령/로그 | 결과 |
|---|---|---|
| 최초 RED | pipeline/material/disabled 선택자 / `a5-red.log` | exit1. 정상0건 보완 부재2건, DataAccess 분류 부재1건. 빈 Document fixture 자체도 LC4j 검증에서 실패하여 mock으로 바로잡음 |
| 확대 RED | 전체 assist / `a5-regression-red.log` | exit1. 수리불가 정규화 오류1건 |
| 최종 Java | `test --tests 'com.example.lms.assist.*Test'` / `a5-regression.log` | **exit0,43 pass / 0 fail / 0 error / 0 skip** |
| UI/PCM | `node --test src/test/js/conversate-ui.test.cjs src/test/js/conversate-pcm.test.cjs` / `a5-ui.log` | **exit0,4 pass / 0 fail** |
| 최종 빌드 | `checkLangchain4jVersionPurity checkSourceSetHygiene :app:classes bootJar` / `a5-build.log` | **exit0** |
| 누적 diff | `git --no-optional-locks apply --reverse --check -- data/agent-handoff/conversate/20260912-01a09365/conversate-approved-source.patch` | **exit0**, 원복 실행 없음 |

Gradle은 기존 process 설정 `AWX_AGENT_HOST=desktop`, `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop-api-reliability`, `GRADLE_USER_HOME=C:\Users\nninn\.gradle-awx-desktop`과 `--offline --no-daemon --console=plain --project-cache-dir C:\Users\nninn\AppData\Local\awx-gradle-project-cache\desktop-api-reliability`를 사용했다. ASR 테스트 Python은 task-owned venv다. Java17/LC4j1.0.1/sourceSets를 유지한다. A4의90개와 이번43개는 겹치는 시험이므로 합쳐 고유133개로 보고하지 않는다.

현재 frozen XML은 `a5-test-results/`, 통계와 source SHA는 `a5-verification.json`이다. Pipeline5·준비 자료3·인증 HTTP3개 시험에서 검색 횟수, 모호한 분할 거절, 숫자·부정어 유지, DB 장애 후 attachment 호출 없음, 파싱 실패 구분과 no-store503을 검증했다. `conversate.enabled=false`일 때 하위 generation 옵션이true여도 생성기 bean이 생기지 않는 시험도 추가했다.

## 실제 브라우저

task-owned loopback18087 fixture를 PID/startTime 대조 후 최신 클래스로 재시작했다. 현재 PID42956, identity는 `a5-fixture-process.json`이다. A4의42932는 이 작업이 시작한 fixture이며 종료했고 기존 A3 fixture18086/PID26748과 모델 서버는 종료하지 않았다.

인증 후 테스트 준비 자료를 적용한 다음 브라우저에서 다음을 확인했다.

1. `보증기간은 얼마인가요?` → `보증 기간은 2년입니다.`. 검색2회/보완1회/생성0회, 처리14ms.
2. `보증기간은 3년인가요?` → 수치 불일치 ASK. 누적 검색4회/보완2회/생성0회, 이번 처리1ms.
3. `아, 네` → 억제1회만 증가. 검색·생성 횟수 유지.
4. 중지 → STOPPED 확인. 실제 안경이나 휴대폰 캡처 증거가 아니다.

합성 자료·로컬 브라우저 표본이며 지연 p95나 LLM 성능을 뜻하지 않는다. A4 실제 gemma3:4b 요청은4201ms TIMEOUT 실패로 그대로 보존한다. 추가 실제 모델 요청은 없었다.

## 보존과 다음 실행

34개 누적 승인 source/sidecar 경로의 최신 patch SHA256은 `ab4335c6085dc60123b44d12364f0e01cb53a70f718bcfdec97e3faa7f2a2ff3`이다. high-confidence secretPatternHits0, preimage와 관련 없는 기존 변경분 보존, owned lease 종료. 이전 A4 누적본은 `a4-conversate-approved-source.patch`, `a4-change-manifest.json`으로 보존했다. 기존 index.lock과 다른 HOLD를 제거하지 않았다. commit/push/배포/운영 마이그레이션/전역 설정 변경은 없다.

다음 독립 작업은 두 가지다. 먼저 준비 자료 선택 요청이 진행 중인 동안 새 텍스트/음성 입력이 이전 epoch로 처리되지 않도록 UI 경합을 재현하고 최소 수정한다. A4 브라우저에서 적용 완료 전에 질문을 보낸 첫 작업이 선택 이전 자료로 처리된 관찰을 유지한다. 이 경합을 전체 기능 성공으로 덮지 않는다.

다음으로 기존 `LocalLlmProcessManager`의 준비 상태 경계를 재사용할 수 있는지 확인한다. 정적 소스에는 명시적 opt-in warmup이 있고, `embed=false`면 한 토큰 chat warmup을 지원한다. 기본 embedding warmup이나 listener 응답은 선택한 chat 모델 준비 완료 증거가 아니다. `diagnostics()`의 state/modelReady/warmup reason/age와 **정확한 모델·endpoint 일치**를 확인해야 한다. warmup은 별도 시작 단계이며 카드의4초 제한을 늘리는 수단이 아니다. 기존 manager에는 재시도와 소유 프로세스 복구 정책도 있어 실제 호출 전에 그 범위를 고정해야 한다. 아직 warmup을 실행하거나 공유 모델 설정을 바꾸지 않았다.

마지막 GPU 읽기 전용 표본은3060(총12288MiB/여유12115MiB/사용률0%),3090(24576/23008/21%)였다. 이는 각 endpoint의 GPU 배치, 과거 OOM, 시간 초과 원인 또는 GPU 취소를 증명하지 않는다.

의미 검색의 실제 모델·차원·ACL/보관 계약, 정상 NO_MATCH와 외부 키 누락/429/timeout/파싱 실패의 종단 구분, 휴대폰 잠금·통화·Bluetooth·한국어 입력, 실제 Meta Display/Neural Band/20분 사용, HTTPS·공유 MariaDB/Redis 수용 시험은 계속 남아 있다. 원래 Conversate 문서의 T01–T22는 파일을 실제로 찾기 전까지 읽었다고 주장하지 않는다. 다음 실행은 승인 범위를 유지하고 이 체크포인트부터 이어간다.
