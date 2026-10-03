# 로컬 채팅 실행 계약

확인일: 2026-10-02 (Asia/Seoul). 정적 ZIP 설명보다 현재 체크아웃과 새 실행 증거를 우선한다.

| 항목 | 현재 계약 / 근거 |
| --- | --- |
| canonical root | `C:\AbandonWare\demo-1\demo-1\src` |
| Boot 진입점 | `com.example.lms.LmsApplication` (`build.gradle.kts`의 `springBoot.mainClass`) |
| 다른 Boot 클래스 | `com.abandonware.ai.agent.AgentApplication`: 루트 Boot 실행에서 선택되지 않음. 삭제하지 않음 |
| 활성 sourceSets | 루트 `main/java`, `main/resources`; 테스트 `src/test/java`. `:app`는 `app/src/main/java_clean`, `app/src/main/resources`를 선언하지만 이번 focused 실행에서는 NO-SOURCE로 보고됨 |
| Gradle settings | `settings.gradle`: `src111_merge15`, `:app`; legacy modules는 opt-in |
| 버전 | Java 17, 기존 Spring Boot 3.3 라인, LangChain4j 1.0.1 고정 |
| 개발 기동 | `Start-RAG.bat` → `start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser` |
| 개발 profile | `local,meta-display`; 이 데모는 interview OFF의 메인 `/chat` |
| 포트 / health | HTTP 18180, management 18181 (`/actuator/health`), Netty 18182 |
| 주력 화면 | `http://127.0.0.1:18180/chat`: 200, title `AbandonWare AI`, `/js/chat.js`, `MAIN_OK` 관측 |
| opt-in 기능 | `meta-display`는 위 런처가 선택. `graph-rag`는 별도 선택 기능이며 이 데모의 필수 의존성 아님 |
| 실행 증거 | `Verify-RAG.bat`: compile + resources + 소유권/HTTP/freshness/DevWatch. `Status-RAG.bat`는 alive-only |
| 모델 카탈로그 | `GET /api/chat/models`: id/provider/status/selectable/reason. installed는 실제 생성 성공과 별개 |

런처가 사용하는 환경변수 이름: `LOCAL_LLM_ENABLED`, `LOCAL_LLM_AUTOSTART`, `OLLAMA_HOST`, `LOCAL_LLM_HEALTH_CHECK_URL`, `LOCAL_LLM_FAIL_FAST`, `SERVER_PORT`. 셸 에이전트는 `AWX_RAG_NO_PAUSE=1`. 병렬 검증은 `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=codex-chatrag` 및 별도 Gradle project cache를 사용한다. 키 값은 기록하지 않는다.

브라우저 기본 모델은 이 브라우저의 새 대화에만 적용된다. 서버 전역 설정과 configured/effective/observed 상태는 별도로 판단한다. 실행 중 JVM은 편집한 Java를 자동으로 읽지 않는다. DevWatch 또는 소유권 확인 후 Close/Start와 새 Verify-RAG 증거가 필요하다.
