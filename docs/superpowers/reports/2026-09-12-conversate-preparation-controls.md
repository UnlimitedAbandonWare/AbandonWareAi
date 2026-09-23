# Conversate 자료 적용·제어 경합 후처리 — A6

전체 목표는 ACTIVE다. 이 단계는 승인된 자체 Conversate의 자료 적용과 제어 화면을 보완한다. 실제 휴대폰 입력, 안경 출력, 실제 모델 품질·지연, 공유 인프라 수용 시험을 완료했다고 판정하지 않는다.

## 변경

- 애플리케이션: `main/resources/static/conversate/app.js` 한 파일. 기존 자료 선택 요청을 하나로 제한하고 적용 중 텍스트·마이크·중복 선택을 차단한다. 입력문은 선택 성공/실패 동안 보존하고, 이미 시작한 마이크 캡처는 닫는다. 자료를 바꾼 뒤 마이크는 명시적으로 다시 시작한다.
- 일시정지·중지는 자료 선택 응답을 기다리지 않는다. `stale_epoch`에만 소유 세션 상태를 다시 읽고 제어 요청을 최대 한 번 재시도한다. 권한 실패나 반복 충돌은 재시도하지 않는다. 중지는 대기 중인 일시정지보다 우선할 수 있다.
- 자료 응답·제어 응답·재접속 상태 조회가 늦어도 새 assist나 변경된 epoch를 덮지 않는다. 오래된 조회 실패도 새 출력 연결을 닫지 않는다. 서버 epoch/버전 검사를 그대로 사용한다.
- 로컬 중지 요청 즉시, 또는 원격 STOPPED 수신 시 전송 전 입력문도 폐기한다. 선택 실패 때 입력을 보존하는 것과 명시적 중지 때 폐기하는 것을 구분한다.
- 테스트: `src/test/js/conversate-ui.test.cjs`에 실제 비동기 순서를 제어하는 회귀를 추가했다. `ConversateGenerationHttpTest.java`의 테스트 전용 실행기에 0–10초 자료 읽기 지연 옵션을 추가했다. 기본값은 0이며 애플리케이션 설정·의존성은 추가하지 않았다.

## 새 검증

| 명령/검증 | 결과 | 보존 증거 |
|---|---|---|
| 초기 Node 재현 | exit1, 기존3 pass / 새7 fail | `a6-red.log` |
| 중지 입력 폐기 재현 | exit1 | `a6-stop-red.log` |
| 이전 재접속 응답/실패 재현 | exit1,12 pass /2 fail | `a6-reconnect-red.log` |
| `node --test src/test/js/conversate-ui.test.cjs src/test/js/conversate-pcm.test.cjs` | exit0,15 pass,0 fail/skip | `a6-ui-final.log` |
| `gradlew.bat test --tests com.example.lms.assist.ConversateGenerationHttpTest checkLangchain4jVersionPurity checkSourceSetHygiene :app:classes bootJar` | exit0,3 HTTP tests pass,0 fail/error/skip; source owners2, duplicateGroups0 | `a6-build.log`, `a6-test-results` |
| 최종 UI 수정 뒤 `gradlew.bat processResources bootJar` | exit0 | `a6-reconnect-build.log` |
| 인증된 로컬 브라우저 | 5초 자료 적용 대기 중 새 입력 disabled, pause/stop enabled, Stop 실행 및 늦은 응답 뒤 STOPPED 유지 | `a6-verification.json` |
| 600px 화면 | viewport600 / documentScrollWidth600, 대기 상태와 제어 표시 확인 | `a6-verification.json` |
| 중지 개인정보 정리 | 재빌드·재로드 뒤 입력문0자, 입력 전달 disabled | `a6-verification.json` |

Gradle 공통 옵션은 `--offline --no-daemon --console=plain --project-cache-dir C:\Users\nninn\AppData\Local\awx-gradle-project-cache\desktop-api-reliability`다. 프로세스 범위의 `AWX_AGENT_HOST=desktop`, `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop-api-reliability`, `GRADLE_USER_HOME=C:\Users\nninn\.gradle-awx-desktop`를 사용했다. Java17/LC4j1.0.1 유지. 이전 A4/A5 테스트 수는 이 결과에 합산하지 않는다.

브라우저에서 허용된 합성 환불 자료를 선택한 후 보존된 질문으로 120자 이내 한국어 카드와 `fixture-refund` 근거를 확인했다. 검색1회, 생성 경로1회, 처리431ms/생성416ms, 표본1이다. 모델은 합성 HTTP 응답이며 p95 또는 실제 LLM 성능이 아니다. 자료 읽기5초는 테스트에서 의도적으로 넣은 지연이다. 실제 마이크는 이 fixture에서 비활성이다.

## 소유권·diff·남은 경계

단일 3역할 preflight의 stable APPLY 후 기존 source lease와 즉시 preimage verify를 사용했다. 일시적인 `git-operation-active`는 해당 쓰기만 보류했고 실제 허용 응답 이후 수정했다. 다른 HOLD와 index.lock을 해제하지 않았다. 테스트 서버 PID/시작 시각/포트 소유자를 확인하고 이 작업의 이전 fixture만 교체했다. 현재 fixture는 `a6-fixture-process.json`에 식별된 loopback18087이며 테스트 세션은 STOPPED다.

산출물은 `data/agent-handoff/conversate/20260912-01a09365/` 아래에 있다. `a6-ui-source.patch`는 이 단계 UI diff, `conversate-approved-source.patch`와 `change-manifest.json`은 승인 이후 누적34개 애플리케이션/sidecar 경로의 diff와 사전/사후 SHA다. 테스트 파일 SHA는 `a6-verification.json`에 있다. 이전 A5와 재접속 수정 전 산출물도 보존했다. 역방향 적용 가능성은 `git apply --reverse --check`로 확인하며 실제 rollback/commit은 수행하지 않는다.

기존 모델 준비 진단은 시간·상태와 endpoint를 제공하지만 warmup 모델 identity를 제공하지 않는다. `/api/chat/ui-heartbeat` 조회는 warmup이나 restart를 실행하지 않는다. `autostart=false`일 때 기존 manager의 warmup/recovery 경로도 실행되지 않는다. 따라서 이전 실제 `gemma3:4b` 요청의4201ms `GENERATION_TIMEOUT`을 해결된 것으로 보지 않는다. GLM transport HOLD도 유지한다.

다음 독립 구현/검증은 정확한 chat-target 준비 상태와 단일 bounded warmup/probe 경계를 정하고 실제 카드 품질·지연을 측정하는 것이다. 살아 있는 endpoint만으로 model-ready를 추론하거나 4초 카드 예산을 늘려 통과시키지 않는다. 실제 휴대폰/안경/허용 HTTPS, 공유 Redis와 별도 프로세스 DB 경합·재시작, 제공자 실제 연산 중단1초 측정, 기존 활성 semantic retrieval 연결은 각자의 수용 증거가 남아 있다. T01–T22 원문은 아직 확보되지 않아 읽은 것으로 취급하지 않는다.
