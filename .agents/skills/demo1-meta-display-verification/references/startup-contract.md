# Display 서버와 의존 서비스 시작

## 실제 시작 경계

Display는 Spring의 `/assets/display/index.html`에서 제공된다. 현재 실행 근거는 `data/agent-handoff/codex/report/meta-display-sync-v1/SETUP.md`이고 기존 실행기는 `scripts/chat_ui_vibe_listener.ps1`이다. 이 실행기는 환경을 상속한 `gradlew.bat bootRun`을 실행한다. 단순 HTML 미리보기나 `frontend`의 Next.js 실행은 이 Spring 시작을 대체하지 않는다.

Spring `main/java/com/example/lms/config/LocalLlmProcessManager.java`가 Ollama를 소유한다. 현재 `main/resources/application.yml`은 `LOCAL_LLM_ENABLED`와 `LOCAL_LLM_AUTOSTART`의 기본값을 true로 선언한다. 매니저는 정상 listener를 재사용하고, 없을 때 `ollama serve`를 실행하며, 자신이 시작한 프로세스만 종료한다. 새 Ollama 실행 래퍼나 Windows 상주 작업을 추가하지 않는다.

## 시작 요청을 받았을 때

1. **실행 호스트를 확인한다.** Notebook의 `Y:\`는 Desktop 소스 접근 경로다. Notebook loopback 검사, 설치, 환경변수 설정은 Desktop 서버에 적용되지 않는다. 기존 프로세스의 소유권 확인 없이 재시작하지 않는다. 상태 파일을 다른 호스트에서 재사용하거나 삭제하지 않는다.
2. 그 실행 호스트의 저장소 루트에서 아래 진단을 한 번 실행한다. 출력은 supporting evidence다. `--probe`를 생략하면 네트워크 요청도 하지 않는다. `java -version`은 최대 3초, loopback HTTP는 최대 2초이며 호출자 전체 한도는 15초다.

   ```powershell
   python -B .\.agents\skills\demo1-meta-display-verification\scripts\startup_doctor.py --probe
   ```

3. `java17=false`면 현재 호스트의 기존 Java 17 설치/`JAVA_HOME`을 확인한다. 다른 버전으로 빌드하거나 Notebook 검증으로 Desktop 결과를 만들지 않는다. `LOCAL_LLM_ENABLED=false`, `LOCAL_LLM_AUTOSTART=false`, 명시적 router 해제는 존중한다. null은 환경변수 미지정이며 실제 Spring ConfigData·프로필·실행 인수의 값이 미확인이라는 뜻이다.
4. 현재 설정에서 Ollama host와 health URL이 일치하는지 확인한다. 기본은 `127.0.0.1:11435`와 `http://127.0.0.1:11435/api/version`이다. 다른 endpoint를 의도적으로 사용하면 기존 `OLLAMA_HOST`와 `LOCAL_LLM_HEALTH_CHECK_URL`을 **동일한 대상**으로 현재 시작 셸에 설정한다. 다른 포트의 응답을 근거로 성공이라 하지 않는다. 진단은 remote, redirect, URL의 credential/query를 요청하지 않는다.
5. Ollama가 정상 응답하면 재사용한다. 미실행이고 실행 파일이 있으면 Spring의 기존 자동 시작에 맡긴다. 실행 파일 부재나 점유된 비정상 포트는 구체적인 blocker로 남긴다. 모델 설치·교체·다운로드, GPU 선택, embedding 변경은 자동 부수 작업이 아니다. `/api/version`만으로 모델 준비나 추론 성공을 주장하지 않는다.
6. **필수 외부 연결을 분리한다.** 현재 admission은 `UpstashRedisClient`의 `upstash.redis.rest-url`, `upstash.redis.rest-token`을 사용한다. 기존 `UPSTASH_REDIS_REST_URL`/`UPSTASH_REDIS_REST_TOKEN`의 존재만 검사한다. 환경변수 부재가 다른 ConfigData의 부재까지 증명하지는 않는다. User/Machine에만 있으면 기존 승인된 값을 안전하게 상속받는 시작 셸을 사용하고 원문을 출력하지 않는다. 이 진단은 URL/token을 HTTP로 보내거나 DB 명령을 실행하지 않는다. 로컬 Redis TCP 서비스 실행, admission 해제, 새 계정/credential 발급으로 이를 대체하지 않는다. JDBC 등은 실제 실패 경계가 확인됐을 때만 해당 기존 서비스 시작 수단을 사용한다.
7. 전제 조건이 준비됐고 서버 시작이 승인돼 있으면 **현재 호스트의 기존 실행기**를 실행한다. Desktop은 현재 canonical C 루트, Notebook supporting 명령은 `Y:\`를 유지한다. 예시의 state path는 이 사용자의 기존 Display 런타임 기록이다. 소유권이 다른 경우 새 값을 추측하지 않는다.

   ```powershell
   # 현재 확인한 서버 호스트의 저장소 루트에서 실행
   $env:SERVER_ADDRESS = '127.0.0.1'
   $env:MANAGEMENT_SERVER_ADDRESS = '127.0.0.1'
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chat_ui_vibe_listener.ps1 `
     -Port 18166 -UiSurface meta-display -StatePath var\codex-runtime\meta-display-01a094de.json `
     -BuildHostId desktop-display `
     -ProjectCacheDir (Join-Path $env:USERPROFILE '.awx-gradle-project-cache\desktop-display') `
     -GradleUserHome (Join-Path $env:USERPROFILE '.gradle-awx-desktop') `
     -OutDir build\codex-smoke\meta-display-01a094de -ReadyTimeoutSeconds 180
   ```

   **위 실행 예시와 `chat_ui_vibe_listener.ps1`은 확인된 Desktop 서버 호스트 전용이다.** 기존 실행기는 `AWX_AGENT_HOST=desktop`을 지정한다. Notebook은 이 진단과 지시서 작성까지 수행하며, 별도로 입증된 Notebook 실행 경로 없이 이 launcher나 Desktop state path를 사용하지 않는다. 시작 전에 실제 build/runtime owner gate와 cache 격리를 확인한다. 기존 실행기는 검증 후 소유 runtime을 재시작하므로 단순 health 검사로 실행하지 않는다. `SkipVerification`이나 충돌 종료 옵션을 자동으로 추가하지 않는다. listener가 반환한 실제 selected server port로 Display URL을 만든다.
8. 진단을 다시 실행해 Ollama 준비를 확인하고 E3 절차로 실제 sync와 세션 연속성을 관측한다. `localLlm.startup.*`의 allowlisted reason/status와 모델 준비를 따로 남긴다. launcher의 화면 HTTP 응답은 Upstash admission, 모델 준비, provider 호출의 증거가 아니다. 한 조건이 막히면 그 의존 단계만 보류한다.

## 진단 계약과 복구

- trigger: 서버 시작·Ollama 자동 실행·시작 실패 진단. non-trigger: 일반 UI 편집, 문구 수정, 자동 배포, OS 부팅 서비스 설치.
- owner/mutation: 현재 실행 호스트; 진단은 파일·환경·서비스·DB를 변경하지 않는다. Java version 명령과 단일 HTTP 진단용 자식만 실행한다. HTTP 자식은 전체 2초 timeout으로 종료하므로 느린 header/body도 계속 대기하지 않는다. 자식에 URL은 stdin으로 전달하고 응답은 고정 status/reason만 수용한다. 매니저/실행기를 바꿀 필요가 생기면 기존 source gate를 사용한다.
- input: 현재 프로세스 환경, Windows User/Machine의 지정된 두 Upstash 변수 존재, Java/Ollama 실행 파일 발견, 선택적 loopback `/api/version`. CLI는 `--probe`만 허용한다.
- output: schemaVersion, executionOwner, Java17 여부, 실행 파일 존재, env flag의 bool/null, endpoint coherence/scope/reason, localHealth, Upstash 존재 boolean, evidence_needed. 내부 URL·실행 경로·값·예외·응답 본문은 출력하지 않는다. 출력은 8 KiB 미만.
- failures: java-unavailable/java-not-17, endpoint-invalid/endpoint-mismatch/external-endpoint, ollama-unavailable/invalid-version-response, autostart-explicitly-disabled, upstash-environment-evidence-missing. launch와 live-sync의 증거 부족은 각각 fail-closed; 이미 가능한 독립 검사는 계속한다.
- exit 0: 진단 실행 완료. **서비스 준비 성공이 아니다.** 비정상 진단은 값/traceback 없이 고정 reason과 exit 2를 반환한다.
- 재사용 근거: 일반 smoke는 runtime을 띄우거나 테스트용 flag를 바꾼다. 이 helper는 Display 시작 전의 무변경·값 비공개 진단만 담당하며 기존 매니저·listener를 복제하지 않는다.
- falsifier: remote URL로 요청하거나 HTTP200 HTML을 Ollama 정상으로 판정하거나 진단 중 `serve`/DB 명령을 실행하면 실패다. `python -B -m unittest discover -s .agents/skills/demo1-meta-display-verification/scripts -p test_startup_doctor.py`로 검증한다.
- rollback: 이번 skill 세 파일은 작업 보고서의 backup과 postimage를 비교해 이번 변경만 되돌린다. 새 reference/helper/test는 현재 postimage가 이번 것과 같을 때만 제거한다. 기존 앱 소스·런타임·E0–E4 기록은 보존한다. 스킬 변경으로 taskBinding이 stale해지면 현재 계약에 따라 Desktop이 재검토하고, 과거 PASS 시각/해시를 기계적으로 갱신하지 않는다.
