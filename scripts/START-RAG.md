# RAG 한 번에 실행하기

저장소 루트의 **Start-RAG.bat**를 더블클릭합니다. Meta Display 전용 Spring을 고정 포트 `18180`에서 실행하고 Display 화면을 엽니다. 콘솔 창을 닫아도 서버는 유지됩니다.

전용 프로파일은 `local,meta-display`입니다. 기존 로컬 설정을 기반으로 `application-meta-display.yml`의 서버 `18180`, 관리 `18181`, Netty `18182`를 사용하고, 실행 인수로도 같은 포트를 고정해 개발용 환경 변수에 영향을 받지 않습니다. 기존 `application.yml`, `application-local.yml` 및 환경 변수의 영구 설정은 변경하지 않습니다. Netty가 비활성화된 설정에서는 해당 포트가 실제로 열리지 않을 수 있습니다.

고정 로컬 주소는 **http://127.0.0.1:18180/assets/display/index.html**입니다. 안경에는 기존 공개 HTTPS 주소를 등록해야 하며, HTTPS 게이트웨이의 Display 전달 대상은 `127.0.0.1:18180`으로 한 번 맞춰야 합니다. 콘솔은 기존 `APP_PUBLIC_BASE_URL` 또는 `application.yml`의 기본 공개 주소를 사용해 HTTPS 후보 주소도 출력합니다. 공개 경로의 동작과 실기기 접속은 로컬 기동 검증과 별개이며, 이 실행기는 공유 HTTPS 게이트웨이를 변경하거나 재시작하지 않습니다.

Meta Display 프로파일은 `demo.interview.fixed-public-origin`에 같은 공개 HTTPS 출처를 명시합니다. `APP_PUBLIC_BASE_URL`을 지정할 경우 `https://호스트` 형식을 사용하며 사용자 정보·경로·쿼리·fragment를 포함하지 않습니다. 기존 인터뷰 보안 필터는 루프백 프록시, 요청 호스트와 정확한 HTTPS 출처가 일치하는 경우에만 브라우저 요청을 허용합니다. 이 프로파일을 사용하지 않으면 고정 출처 설정은 비어 있고 기존 임시 터널 검증 동작을 유지합니다.

실행 순서는 환경·프로세스·포트 확인 → Ollama 준비 → Spring 시작 또는 재사용 → RAG 화면 및 API 입력 검증 → 접속 주소 출력입니다. RAG 엔진은 Spring 내부 서비스이므로 별도 RAG 서버를 중복 실행하지 않습니다.

- 기존 `chat_ui_vibe_listener.ps1`와 `chat_ui_vibe_lifecycle.ps1`의 Gradle 검증, 시작, 소유권 및 실패 정리를 재사용합니다. 기존 스크립트는 그대로 사용할 수 있습니다.
- Ollama는 지정한 포트의 `ollama.exe`, `/api/version`, `/api/tags`를 확인해 재사용합니다. 없을 때만 설치된 `ollama.exe serve`를 숨김 실행하고 준비 완료 후 Spring으로 넘어갑니다. 실행기가 시작하는 Spring 자식에만 `LOCAL_LLM_AUTOSTART=false`를 전달하여 기존 `LocalLlmProcessManager`가 GPU 자동 탐색으로 추가 Ollama를 시작하지 않도록 합니다. 원래 환경은 즉시 복원하며 기존 스크립트를 직접 실행할 때의 자동 시작 동작은 유지됩니다.
- Meta Display 모드는 같은 저장소·전용 프로파일·고정 포트가 일치하고 Display/RAG 화면과 API가 준비된 서버만 재사용합니다. 다른 포트의 개발 Spring은 계속 실행할 수 있습니다. 두 번 클릭해도 시작 뮤텍스와 실행 전 재확인으로 중복 기동을 막습니다.
- 외부 프로세스의 포트를 빼앗거나 기존 서버를 종료하지 않습니다. 실행 중인 전용 Spring이 준비되지 않았거나 전용 서버가 여러 개이면 원인을 표시하고 중단합니다. 일반 RAG 모드에서는 기존대로 명시한 포트 또는 준비된 개발 서버 중 가장 낮은 포트를 재사용하며, 명시한 포트에 해당하는 기존 서버가 없으면 오류로 표시합니다.
- 전용 포트 세 개 중 하나라도 다른 프로세스가 점유하면 포트·PID·프로세스 이름을 로그로 출력하고 중단합니다. 랜덤 포트 전환이나 점유 프로세스 종료는 하지 않습니다. 전용 빌드 출력과 프로젝트 캐시는 `desktop-meta-display` / `AWX/meta-display`로 분리합니다. 첫 빌드는 시간이 걸릴 수 있으며 기본 Spring 대기 상한은 600초입니다.
- 단계 로그와 결과 JSON은 실행할 때마다 `var/rag-launcher/<실행시각-ID>/`에 보관합니다. 실패하면 `PREFLIGHT`, `OLLAMA`, `SPRING`, `RAG` 중 실패 단계, 이유 코드와 로그 경로를 출력하고 1로 종료합니다. Spring 빌드/실행 로그도 같은 폴더에 생성됩니다.
- Java 17과 Ollama가 설치되어 있어야 합니다. 모델 자동 다운로드, GPU 재설정, 환경 변수 영구 저장은 하지 않습니다. 기존 모델·DB·프로바이더 설정을 상속합니다. 서버 준비 검사는 모델 답변 생성 성공을 의미하지 않습니다.
- 기존 Python MCP 서버와 HTTPS 터널은 핵심 RAG의 선행 의존성이 아닙니다. 각자의 기존 시작 파일과 설정을 유지하며 이 실행기로 계정 설정이나 외부 공개를 자동 변경하지 않습니다.

PowerShell에서 상태만 확인할 때:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack.ps1 -MetaDisplay -CheckOnly
```

Ollama 포트나 시작 대기 시간을 지정할 때:

```powershell
.\Start-RAG.bat -OllamaPort 11435 -TimeoutSeconds 900
```

Meta Display 모드에서 `-Port`는 생략하거나 `18180`만 지정할 수 있습니다. `SERVER_PORT`는 이 모드의 포트를 바꾸지 않습니다. `OLLAMA_HOST`는 기존대로 `127.0.0.1:<포트>` 또는 `localhost:<포트>`를 지원합니다. 콘솔에서 자동화할 때 `AWX_RAG_NO_PAUSE=1`을 설정하면 마지막 키 입력 대기가 생략됩니다.

기존 일반 RAG 실행 동작은 PowerShell 스크립트를 `-MetaDisplay` 없이 실행하면 사용할 수 있습니다. 이 모드는 개발 서버 재사용, `SERVER_PORT`, 기본 `8080` 및 기존 프로파일 동작을 유지합니다.

## 두 진입점과 wear 런타임 역할

- **Start-RAG.bat**: 기존 개발/canonical 실행입니다. `-MetaDisplay -ForceRestart -DevWatch -OpenBrowser`를 그대로 전달하며 개발용 Meta Display(dev 역할)를 대상으로 합니다.
- **Start-Meta-Display.bat**: 실착용용 얇은 진입점입니다. 같은 `start_rag_stack.ps1`을 `-MetaDisplay -Wear -OpenBrowser`로만 호출하며 `-ForceRestart`/`-DevWatch` 없이 기존 SSOT를 재사용합니다.
- `-Wear`는 실행되는 Spring에 `--awx.runtime.role=wear`를 부여하고 ownership manifest에 `runtimeRole`을 기록합니다. 빌드 출력과 프로젝트 캐시는 `desktop-meta-display-wear` / `AWX/meta-display-wear`로 분리합니다.
- 일반 `-ForceRestart`(일반 모드·개발용 Meta Display 모드 모두)는 `wear` 프로세스를 건너뛰며 종료하지 않습니다. `wear`가 전용 포트를 점유한 상태에서 개발용 Meta Display 재시작을 요청하면 `meta-display-wear-runtime-protected`로 중단합니다. DevWatch도 `wear` 보유 시 재시작을 건너뜁니다.
- 착용 런타임 재시작은 명시적으로만: `Start-Meta-Display.bat -ForceRestart` 또는 `start_rag_stack.ps1 -MetaDisplay -Wear -ForceRestart`. 이 경우 `wear` 프로세스만 교체합니다. `-Wear`와 `-DevWatch`는 함께 사용할 수 없습니다.

### 정적 자산 제공 경로와 provenance

- 렌즈가 읽는 자산은 `main/resources/static/assets/display/meta/`(`index.html`, `receiver.js`)입니다. 실행 서버가 `build/desktop-meta-display`(dev) 또는 `build/desktop-meta-display-wear`(wear)의 복사본을 서비스하므로, 원본 수정 후에는 해당 빌드 출력에도 반영되어야 하며 `receiver.js` 변경 시 `index.html`의 `?v=` 값을 올려 렌즈가 새 자산을 받게 합니다.
- 기동 provenance(`Test-AwxFreshRuntimeProvenance`)는 실제 서빙된 페이지가 로드하는 자산을 검증합니다. `demo.interview.enabled=true`에서는 `/chat-ui`가 `/assets/interview/index.html`로 전달되고 `/js/chat.js`는 의도적으로 차단되므로, 페이지 본문에 `/assets/interview/`가 보이면 `/assets/interview/app.js`를 대신 검증합니다. meta-display 표면은 `/assets/display/app.js`, rag-studio 표면은 `/assets/interview/studio.js`를 검증합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack.ps1 -Port 8080
```

동작 회귀 검증:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\meta_display_launcher_tests.ps1
```

## DevWatch (vibe coding auto-reload)

Start-RAG.bat now passes -ForceRestart -DevWatch.

- **ForceRestart**: each bat launch stops meta-display Spring on 18180-18182 then starts fresh (Java changes apply).
- **DevWatch**: after READY, starts a single-instance watcher (scripts/dev_reload_watch.ps1) that:
  - watches main/java, main/resources, pp/src/main/java, pp/src/main/resources
  - **static** (static/** js/css/html): no Spring restart; client poll/reconnect
  - **Java / yml / properties / other**: debounce ??compileJava + processResources ??start_rag_stack -MetaDisplay -ForceRestart ??wait until Display HTTP ready
  - logs [DEV-RELOAD] ... to console and ar/dev-reload/dev-reload.log
  - single-instance mutex + restart cooldown (default 45s) to avoid duplicate/infinite restarts

Disable watcher-only by running without -DevWatch, or stop the watcher PowerShell process. Alive check still:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack.ps1 -MetaDisplay -CheckOnly
```

Codex/AGENTS: see `DEMO1-SPRING-VIBE-RELOAD` in `AGENTS.md` and skill `demo1-dev-reload` (compile before claiming Java edits are live).
