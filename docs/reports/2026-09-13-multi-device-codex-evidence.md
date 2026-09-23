# 멀티기기 Codex 공유 환경 조사

> 이 문서는 설계 승인 전 조사 기록이다. 이후 사용자가 설계를 승인했고 구현에 착수했다.
> 최신 변경과 검증은 [구현 보고서](2026-09-13-multi-device-codex-implementation.md)를 따른다.

2026-09-13 Desktop 세션에서 확인한 현재 파일과 명령 결과다. 구현 완료 보고나 다른 장비의 실행 증거가 아니다. 조사 범위는 기존 공유 저장소의 Codex 설정, 로컬 스킬 목록, 장비 설정 생성기, producer 설치 키트다.

## 현재 기준

| 항목 | 현재 관측 |
| --- | --- |
| Desktop 저장소 | `C:\AbandonWare\demo-1\demo-1\src` |
| 브랜치 | `codex/owned-runtime-browser-restart` |
| HEAD | `0796a3c5b29bbb08c3314bd40649d856d4a7bce6` |
| 최초 조사 시 Git 변경 항목 | 3032개; 기존 변경을 보존 |
| Git index 잠금 | 최초 조사 시 존재; 제거하거나 Git 쓰기를 수행하지 않음 |
| 최상위 대기 `.patch` | 0개 |
| source-edit-locks 폴더의 파일 | 0개; 전체 임대 유효성 검증을 대신하지 않음 |
| 저장소 스킬 | `.agents/skills/*/SKILL.md` 64개 |
| 저장소 스킬 이름 중복 | front matter의 `name` 기준 0개 |
| Windows 경로가 포함된 저장소 스킬 | 22개; 경로 예시도 포함하므로 결함 개수가 아님 |
| 개인 폴더에만 있는 일반 스킬 | `.agents/skills` 3개와 `.codex/skills` 9개; 시스템 및 플러그인 스킬 제외 |
| 외부 장비 | 앱 프로젝트 목록에서 로컬 호스트만 관측; Notebook/Mac 원격 실행은 미확인 |

## 확인한 연동 차이

1. `.codex/config.toml:4`의 `mcp_servers.glm_agent`에 Desktop Java 실행 파일, Desktop 빌드 JAR, Desktop 작업 디렉터리가 고정되어 있다. 세 필드의 Windows 절대 경로는 Notebook의 `Y:\`나 macOS 경로로 자동 변환되지 않는다. 다른 장비에서 실제 실행 실패를 재현한 것은 아니다.
2. `.codex/hooks.json:9`의 기본 `command`와 `:10`의 `commandWindows`가 모두 Windows PowerShell 명령이다. OS별 명령을 지정할 자리는 있지만 현재 파일의 기본 실행 경로도 Windows에 맞춰져 있다.
3. `token-efficient-agents`, `glm-offload`, `awx-source-surgeon` 및 개인 `.codex/skills`의 9개 스킬은 현재 저장소의 스킬 목록에 없다. 해당 개인 스킬을 필요로 하는 작업은 저장소만 공유해서 동일해지지 않는다. 설치된 플러그인의 캐시나 인증 파일을 복사하는 것은 이 차이의 해결책에 포함하지 않는다.
4. `scripts/awx_mcp_node_setup.py:106`은 수신 작업본의 `cwd`와 장비 역할을 넣는 설정 생성 지점이다. 현재 출력은 `mcpServers` JSON이며, 이 파일이 있다는 사실만으로 Codex의 TOML 설정 등록이나 stdio 연결을 증명하지 않는다. `:89`는 기존 출력 파일의 변경 여부 비교 없이 설정을 쓴다.
5. `scripts/awx_mcp_toolbox.py:5835`의 producer-kit manifest에는 파일 해시와 소스 격리 정책이 있다. 설치 키트의 소스 리비전, 도구 목록 해시, 대상 설치 이력을 묶는 정보는 이 manifest에 없다. Notebook 설치기의 `:6011`은 키트 검증 후 `Copy-Item -Force`를 수행한다. 대상 파일의 독립적인 수정분을 비교하는 갱신 절차가 필요하다.
6. 기존 `scripts/awx_mcp_node_smoke.py`의 직접 toolbox 검사는 생성한 stdio 설정으로 서버를 실행해 연결한 증거와 다르다. 실제 도구 목록과 스킬 의존성의 일치 여부는 추가 검증 대상이다.

## 재사용할 기존 경계

- 저장소 `AGENTS.md`와 `.agents/skills`가 공통 작업 규칙의 기준이다.
- Notebook의 공유 소스 직접 수정은 검증한 `Y:\`와 기존 대상별 임대·preimage 절차를 따른다. 공유 경로가 읽힌다는 사실만으로 수정 권한을 판단하지 않는다.
- Mac 생산 작업은 기존 독립 작업본과 PatchDrop 전달 경로를 따른다. MacBook에서 Desktop 공유 소스의 직접 수정을 새로 허용하는 정책 변경은 현재 조사에서 하지 않았다.
- `producer_kit_export`와 `awx_mcp_node_setup.py`가 기존 배포·설정 생성 지점이다. 별도의 동기화 데몬이나 SMB 서비스를 추가할 필요성은 확인되지 않았다.
- Gradle 파일에서 `awx.splitBuildOutputs`, `awx.buildHostId`, root `main` 및 `app/src/main/java_clean` 경계를 확인했다. 실제 다중 호스트 빌드·캐시·포트 격리를 실행으로 검증한 것은 아니다.

## 공식 문서와 대조

- Codex는 저장소의 `.agents/skills`를 검색한다. 같은 이름의 스킬을 자동 병합하지 않으므로 공용 스킬과 개인 복사본의 이름·내용 차이를 확인해야 한다. [OpenAI, Build skills](https://learn.chatgpt.com/docs/build-skills)
- 신뢰한 프로젝트의 `.codex/config.toml`은 사용자 설정보다 우선한다. 장비별 경로를 공용 프로젝트 설정에 고정하면 개인 설정만 바꾸어 해결되지 않을 수 있다. [OpenAI, Config basics](https://learn.chatgpt.com/docs/config-file/config-basic)
- 여러 위치의 hook은 합쳐져 로드되며, `commandWindows`는 Windows용 별도 명령이다. 공용 hook과 개인 hook의 중복 등록도 갱신 검사에 포함해야 한다. [OpenAI, Hooks](https://learn.chatgpt.com/docs/hooks)

문서는 조사일에 조회했다. 설치된 CLI의 현재 동작은 별도의 실제 실행으로 확인해야 한다.

## 이번 조사에서 실행한 검증

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -m unittest scripts.test_awx_mcp_node_setup -q
```

결과: 1개 테스트 통과. 해당 테스트는 POSIX 형태 UNC 경로 분류만 검증한다. 키트 업그레이드, 설정 덮어쓰기 방지, macOS hook 실행, 실제 SMB 동시 수정 또는 외부 장비 연결의 통과를 의미하지 않는다.

자동 재개 시 현재 설정 생성기와 공용 설정·hook의 해시가 위 조사 시점과 일치함을 확인한 뒤, 기존 설정 보존 여부를 임시 파일에서 추가 검증했다. 실제 사용자 설정과 공유 소스는 수정하지 않았다. 다른 가상 MCP 항목이 있는 임시 JSON을 `--output`으로 지정해 현재 `awx_mcp_node_setup.py`를 실행하면 종료 코드 0과 `ok=true`를 반환하지만, 기존 가상 항목은 사라지고 AWX 항목으로 교체된다. 이 결과는 지정된 출력 파일의 덮어쓰기를 재현한 것이며, 기본 실행이 사용자 `config.toml`을 수정한다는 뜻은 아니다. 갱신 설계에는 기존 출력의 용도·설치 이력을 확인하고 다른 항목이나 독립 수정분이 있으면 보존 또는 충돌 처리하는 조건이 필요하다. 원시 출력은 보관하지 않았으며 검증용 임시 경로의 범위를 확인하고 정리했다.

설정 파일은 TOML 파서로 구조를 확인했다. 개인 설정은 키 이름, 경로 유형, 개수와 해시만 조사했고 인증값을 보고서에 남기지 않았다. 실제 설치, 서버 시작, 외부 모델 호출, commit, push, 배포는 수행하지 않았다.

## 조사 시점 대상 해시

| 상대 경로 | SHA-256 |
| --- | --- |
| `.codex/config.toml` | `93cd38f3c6c1a8c9285cc54a910825da9dc6ed69b2b491c929187566ea78fbed` |
| `.codex/hooks.json` | `68490d373586a60c9ca0ff5c31319602c13fe87e5afc277dd111a4fbb182a357` |
| `.codex/hooks/source_edit_triage.ps1` | `0b9a89cc126571cf48b97b39d2217e8249f6b36cf6dcdc1461cf546bc5dd33ed` |
| `scripts/awx_mcp_node_setup.py` | `752c2cdf4f3aeaff5707b2d0254069f606e894ad929ef76367ada68510c1fcdf` |
| `scripts/awx_mcp_toolbox.py` | `1da7dc4eb25a4819e7ae81fced73e95214e607c937a588ac8f9224b6279de208` |
| `scripts/awx_mcp_toolbox_tests.ps1` | `90847021386c3c2163044c77dd68b5a2c290479f691e6d952e823670fec3435d` |

이 해시는 이후 수정을 허가하는 증거가 아니다. 실제 변경 직전에 대상 파일과 겹치는 작업자를 다시 확인한다.

## 진행 상태와 남은 증거

- `goalStatus=blocked`
- `implementationStatus=awaiting-design-approval`
- `holdScope=implementation`
- `firstBlockingRule=superpowers-brainstorming-design-approval`
- `blockingEvidence=architectural design has not yet been approved`
- `blockedAudit=3 consecutive goal turns`: 최초 요청과 두 번의 자동 재개에 걸쳐 같은 설계 확인 조건이 유지됐다. 마지막 점검에서 새 사용자 답변이 없고 설정 생성기·공용 설정·hook 해시가 동일함을 확인했다. 이전 재개는 임시 파일에서 덮어쓰기 동작을 재현한 진행이었으며, 추가 구현은 설계 확인에 의존한다. 목표 도구를 통해 `blocked` 상태로 전환했다.
- `independentWorkCompleted=local configuration inventory, bounded explorer evidence, official documentation comparison, focused baseline test, evidence report`
- `repositoryWideHold=false`
- `externalDeviceProof=evidence_needed`: 확인된 Notebook와 Mac에서 같은 기준 목록, 로컬 설정, 장비별 실행 및 충돌 감지 결과를 수집해야 한다.
- `glmWorker=SESSION_UNAVAILABLE`: 키 존재 여부만 확인했다. 현재 `codex-cli 0.144.1` 및 `gpt-5.6-sol multi_agent_version=v2`가 사용자 지침의 전송 HOLD 조건과 일치하여 GLM 호출은 0회다. 기본 탐색 에이전트 1개를 사용했다.

기존 `/goal`의 전체 구현·검증 목표를 유지한다. 이 문서는 조사 결과이며 패치 완료나 멀티기기 무충돌을 선언하지 않는다.
