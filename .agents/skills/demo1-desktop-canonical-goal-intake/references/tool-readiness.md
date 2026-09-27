# Notebook 지시서 적용 시 도구 준비

사용자가 "노트북 지시서 패치해", "노트북에서 만든 지시서 적용해", "도구 풀로딩 후 패치해"라고 하면 이 절차를 Desktop 접수의 일부로 수행한다. 사용자가 선택한 지시서, 현재 작업서의 명시적 인계 포인터, 현재 대화의 구현 권한을 사용한다. 후보가 모호하면 그 선택만 확인하고 독립적인 도구 가용성 조사는 계속한다. 분석 전용·dry-run은 실행 권한으로 바꾸지 않는다.

## 한 번 준비하고 재사용

1. 기존 canonical intake로 실제 Desktop local root를 확인한다. Notebook의 Y 접근이나 서브에이전트를 Desktop 연결 증거로 쓰지 않는다.
2. 현재 세션에 노출된 도구 registry를 한 번 확인한다. 지연 도구 검색이 있으면 현재 요청의 shell/file, app/task, Browser/Computer, 프로젝트 connector 등을 검색하고 호출 schema를 확인한다. 없으면 실제 노출된 registry와 기존 CLI를 사용한다. 도구별 available, callable, 필요한 connected, 미확인 이유를 기존 작업 보고서에 기록한다. 플러그인 이름, 파일 존재, API key 존재만으로 연결 성공이라 하지 않는다.
3. main/resources/mcp/awx-control-tower-tools.json의 **전체 tools/resources/prompts 카탈로그**와 해당 작업에 사용할 input/output schema를 읽는다. 현재 조사 기준 24 tools / 11 resources / 3 prompts지만 개수를 상수로 판정하지 않고 현재 manifest와 실제 catalog의 일치를 사용한다. 도구 이름 출력만 필요하면 기존 명령을 쓴다.

   ~~~powershell
   .\scripts\awx_mcp_toolbox.ps1 -Tool schema -InputJson '{}'
   ~~~

   schema는 파일의 이름 목록을 반환할 뿐 연결 검사가 아니다. runtime-session의 start가 protocol 초기화, 실제 전체 pagination, schema 비교, 대표 read-only 호출, 취소·timeout·자원 종료 검사를 수행한다. 모든 tool을 무조건 실행하는 방식으로 준비하지 않는다.
4. 적용 경계의 기존 스킬을 읽고 같은 근거를 재사용한다. source는 three-way preflight/기존 owner guard, Display는 현재 역할/startup doctor, 내부 원인 조사는 invisible-eye 또는 기존 debugger, 최종 검증은 Desktop proof 경로를 사용한다. 선택된 primary route를 유지하고 중복 심사·별도 lease를 만들지 않는다. 외부 도구는 사용자 선택과 필요한 증거에 맞춰 연결을 확인한다. 연결/인증 부재는 그 의존 단계의 미확인으로 남긴다.
5. 로컬 runtime capability가 필요한 경우 아래 기존 세션 절차를 시작한다. 현재 Display 요청에는 앞서 승인된 서버 시작 범위가 유지된다. 일반 소스 수정에서는 해당 runtime 시작 권한과 필요성을 판단한다. 서버가 컴파일되지 않아 기동할 수 없다는 이유로 그 서버의 소스 복구까지 막지 않는다.

## 기존 RuntimeToolkit 세션

구현 경계는 scripts/awx_mcp_toolbox.ps1, scripts/awx_mcp_toolbox.py의 RuntimeToolkit, scripts/awx_mcp_stdio_server.py다. 새 로더·서버·watcher·MCP 설정 파일을 만들지 않는다.

현재 runtime 증거에서 실제 http://127.0.0.1:<port>를 확인한 뒤 $verifiedSpringBaseUrl에 넣는다. 예전 8080/18166 값을 확인 없이 사용하지 않는다. 다음 명령은 stdin이 열린 재사용 가능한 terminal/process 세션에서 실행한다.

~~~powershell
$toolkitConfig = @{
    baseUrl = $verifiedSpringBaseUrl
    startSpring = $false
} | ConvertTo-Json -Compress
.\scripts\awx_mcp_toolbox.ps1 -Tool status -RuntimeSession -InputJson $toolkitConfig
~~~

동일 프로세스 stdin에 아래 명령을 **각 결과를 확인하며** 차례로 보낸다. shell 세션 도구가 있다면 실행 session ID와 stdin-write를 재사용한다. Computer Use로 terminal을 조작하지 않는다.

~~~json
{"command":"doctor"}
{"command":"start"}
{"command":"status"}
~~~

- -Tool status -RuntimeSession은 command reader를 열며 첫 doctor 명령까지 기다린다. -InputJson은 초기 설정이고, stdin은 계속할 명령용이다. --input-json -로 두 입력을 섞거나 유한 명령 목록을 pipe해 EOF로 닫지 않는다.
- startSpring=false는 기존 Spring에 붙으며 Spring을 시작하지 않는다. start는 STDIO 소유 pipe를 시작하고 smoke를 포함하므로 성공 직후 smoke를 중복 실행하지 않는다. 준비가 완료됐는지는 실제 결과에서 판단한다.
- Spring이 없고 임시 runtime 시작이 승인됐다면 실제 Desktop build/port/owner 조건을 확인한 뒤 초기 config의 startSpring=true를 선택할 수 있다. 기존 launcher가 사용되며 호출 한도는 240초다. 도구 호출의 최초 대기는 짧게 반환하고 진행 세션으로 재개해 사용자에게 상태를 알린다. Java17, host-local Gradle user/project cache, split output을 유지한다.
- 사용자가 Display 서버를 작업 후에도 계속 사용하려는 경우, 기존 Display startup 계약의 launcher로 그 런타임을 별도 소유한 뒤 toolkit은 startSpring=false로 연결한다. toolkit의 EOF/stop은 **자신이 시작한 Spring까지 종료**한다. 종료 후에도 현재 invocation이 풀로딩됐다고 보고하지 않는다.
- 작업 중 이 pipe가 필요한 동안 세션을 유지한다. 동일 PID를 여러 번 시작하거나 다른 owner의 toolkit-current.json을 덮어쓰지 않는다. 이미 살아 있는 owner 때문에 publication이 거부되면 소유권을 확인하고 필요한 검증만 보류한다.
- 실제 proof가 60초 이상 지났거나 manifest/process/필수 상태가 바뀐 뒤 준비를 주장해야 하면 같은 세션에 {"command":"smoke"}를 한 번 보낸다. 주기적으로 무조건 실행하지 않고 실제 검증이 필요한 시점에 갱신한다. status만 호출해 실제 smoke 검증 시각을 연장하지 않는다.
- 검증을 마친 뒤 {"command":"stop"} 또는 정상 EOF로 자신이 소유한 자원을 정리한다. 재사용한 외부 Spring/Ollama를 종료하지 않는다. one-shot start/status/smoke/doctor는 반환 전에 소유 자원을 닫으므로 지속 풀로딩 용도로 사용하지 않는다.

## 풀로딩과 소스 완료의 판정

| 관측 | 의미 |
|---|---|
| catalog/schema 확인 | 정의된 도구를 찾고 호출 형식을 준비함 |
| stdio.protocolProbe/catalogValidated/smokeVerified=true | 실제 프로토콜·전체 카탈로그·대표 동작 검사 통과 |
| FULL_LOAD_READY=true | 기존 Java 진단의 local-toolkit 필수 서비스 상태가 준비됨 |
| invocationFullLoadReady=true | 위 조건과 현재 호출자의 살아 있는 pipe·증거 publication까지 준비됨 |
| source focused GREEN + 현재 postimage | 해당 소스 변경 검증; runtime full load와 별도 |

현재 runtime manifest의 서비스는 Spring, local-llm.enabled일 때의 Ollama, local-toolkit profile의 STDIO다. 이 판정은 모든 설치 플러그인·외부 계정·클라우드 provider를 포함하지 않는다. 다른 세션의 FULL=true를 현재 세션에 복사하지 않는다. 기존 var/codex-runtime/toolkit-current.json과 heartbeat proof를 재사용하고 별도 ready marker를 만들지 않는다.

doctor의 SPRING_UNAVAILABLE, REQUIRED_SET_UNKNOWN, CLIENT_PROOF_UNVERIFIED, CLIENT_PROOF_PUBLICATION_REJECTED, RESTART_RATE_LIMITED 등 실제 reason을 보고한다. 동일 원인의 무의미한 재시도를 피하고 기존 300초/3회 시작 예산을 보존한다. 필수 runtime이 미준비여도 독립 소스 수정·fixture 테스트는 현재 source gate로 진행하며, 필요한 live acceptance만 evidence_needed로 남긴다.

도구 준비 뒤에는 원래 지시서로 즉시 돌아가 기존 3질의·lease/preimage·RED/GREEN·rollback 경계를 수행한다. catalog 조회, STDIO smoke의 boot_verify 명령 목록 반환, HTTP200, 인계 수신은 실제 소스 패치·Gradle·provider 검증을 대신하지 않는다.

## 변경·검증 계약

- trigger/non-trigger: 승인된 Notebook 지시서 적용과 도구 풀로딩 준비 / 단순 인계 파일 발견·분석 전용·무관한 작업에는 자동 소스 실행 없음.
- owner/mutation: 이 문서는 기존 Desktop intake의 도구 선택과 runtime 세션 사용법만 소유한다. 설정 파일·앱 소스·계정·DB를 수정하는 새 권한을 만들지 않는다.
- input/output: 현재 선택 지시서·세션 registry·repo manifest·실제 Desktop runtime → 기존 보고서의 tool inventory, 실제 ready flags, 적용 결과, 다음 단일 미충족 증거. 이름/count/reason 위주 30행 이내; secrets·raw prompt/cookie/환경 덤프는 제외한다.
- budget: 초기 도구 조사 5분 이내. actual runtime start/검증은 기존 지시서 deadline과 해당 스크립트 timeout을 재사용한다. runtime 실패는 해당 의존 단계만 fail-closed.
- reuse: 이미 존재하는 5개 runtime action과 PowerShell wrapper를 연결한다. 별도 loader, lease, scheduler 또는 plugin cache 사본 없음.
- falsifiers: one-shot 종료 뒤 준비됐다고 주장, finite pipe 뒤 세션 유지 주장, 다른 owner proof를 현재 것으로 승격, FULL=false를 이유로 컴파일 결함 수정까지 중단, 무관한 모든 API 실행 중 하나라도 발생하면 절차 실패다.
- validation: 기존 scripts/test_awx_runtime_toolkit.py의 pipe 재사용·one-shot 종료·current invocation·freshness 경계 검사를 사용한다. Markdown-only 변경은 실제 Desktop 실행 성공을 만들지 않는다.
- rollback: 작업 보고서의 backup/preimage/postimage와 비교해 이번 AGENTS/스킬/프롬프트 문구만 복구한다. 기존 toolkit/runtime/source guards는 보존한다. 이 reference는 현재 postimage가 이번 변경과 일치할 때만 제거한다.
