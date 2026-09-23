# 장비별 실행 절차

[시작 화면](../../../../EXTERNAL_SKILLS.md) · [외부 스킬 선택](external-skills.md) · [기존 명령집](control-tower-reference.md)

**연결 확인 → 작업 범위 확정 → 전달 → 결과 수신 → 요구한 결과 검증**

이 문서의 명령은 실행 예시입니다. 실행 이력이 아니며, 로컬 검증으로 외부 장비의 현재 접속을 증명하지 않습니다.
명령은 저장소 루트에서 실행합니다. 실제 작업에 필요한 단계만 선택합니다.

## 공용 정의와 장비별 로컬 설정 (2026-09-13)

공용 정의는 이 저장소의 `.agents/skills`, `.codex/shared-runtime.json`, `.codex/hooks.json`입니다.
각 장비는 Python 3.11 이상과 자기 장비의 Codex 인증을 사용합니다. 개인 `.codex` 폴더 전체나
토큰·세션·플러그인 캐시를 SMB에 복사하지 않습니다. 설치된 외부 플러그인은 각 장비에서 관리합니다.

Desktop 또는 독립된 Windows Notebook 작업본:

```powershell
python -B scripts/awx_mcp_node_setup.py --register-codex --dry-run
python -B scripts/awx_mcp_node_setup.py --register-codex
python -B scripts/awx_host_runtime.py status
```

Notebook에서 공용 원본을 읽는 설정은 canonical `Y:\`에서 만듭니다.

```powershell
Set-Location -LiteralPath 'Y:\'
python -B scripts/awx_mcp_node_setup.py --register-codex --shared-read
```

이 명령은 기존 backing identity 검증기를 호출합니다. 설정 파일만 이 Notebook의 로컬에 쓰며
`sourceWriteRoot=null`, `authorizedMutation=false`를 유지합니다. MCP의 변경 도구도 이 모드에서는
거절됩니다. 애플리케이션 소스 직접 수정은 기존 Y-drive guarded-direct 절차로 별도 수행합니다.

MacBook의 독립 작업본에서는:

```sh
python3 -B scripts/awx_mcp_node_setup.py --register-codex
python3 -B scripts/awx_host_runtime.py status
```

실제 SMB 마운트의 저장소 루트에서 읽기만 연동할 때는 `--shared-read`를 추가합니다.
macOS 마운트의 backing identity는 이 Windows 세션에서 증명되지 않았으므로 쓰기 권한으로
승격하지 않습니다. MacBook의 수정은 기존 독립 작업본/PatchDrop 경계를 따릅니다.

설정은 기존 개인 MCP 항목을 유지하며 `awx-shared` 이름으로 추가됩니다. 프로젝트의 같은 이름
설정이 다르면 `project-mcp-override-conflict`로 멈춥니다. `--with-glm`은 장비 로컬 Java와 JAR이
준비됐을 때만 사용합니다. Java는 `AWX_JAVA`, `JAVA_HOME`, PATH 순으로 찾고 JAR은
`AWX_GLM_JAR` 또는 로컬 state의 `artifacts/glm-agent-mcp*.jar` 한 개를 사용합니다.
공용 저장소에 있는 JAR을 직접 실행 경로로 설정하면 거절됩니다.

| 항목 | 저장 위치 또는 규칙 |
| --- | --- |
| 공용 스킬 | 저장소 `.agents/skills`; 개인 스킬을 복사하거나 수정하지 않음 |
| 개인 스킬 충돌 | 이름 정규화 및 SHA 검사 후 개인 우선; 해당 공용 `SKILL.md` 경로만 개인 `[[skills.config]]`에서 비활성화 |
| 같은 이름의 여러 개인/공용 후보 | 모호한 경우 자동 선택 없이 충돌 처리 |
| Windows 상태 | `%LOCALAPPDATA%/AWX/workspaces/<source-hash>/<host-id>` |
| macOS 상태 | `~/Library/Application Support/AWX/workspaces/<source-hash>/<host-id>` |
| 인증·Codex 세션 | 각 장비의 로컬 `CODEX_HOME` 또는 `~/.codex`; 설정 생성기가 인증을 이동하지 않음 |
| Gradle 캐시 | 로컬 `gradle-user`, `gradle-project`; 소스의 빌드 결과는 기존 host-id 분리 사용 |
| 백업 | 로컬 transaction의 `.bak_<UTC timestamp>` 및 journal; 새 파일은 원래 부재했다는 기록 |

설정 등록 뒤 Codex를 다시 시작합니다. 공식 설정 방식은
[OpenAI 스킬 문서](https://learn.chatgpt.com/docs/build-skills)의 경로별 비활성화 규칙입니다.

빌드와 기존 런타임 도구는 공용 루트에서 아래 진입점을 사용합니다. POSIX에서는 `python3`를 씁니다.

```powershell
python -B scripts/awx_host_runtime.py run --runtime gradle :classes
python -B scripts/awx_host_runtime.py run --runtime toolkit
```

관리되는 런타임은 상태·로그·캐시와 `RUNTIME_TOOLKIT_EVIDENCE_PATH`를 같은 로컬 state로 설정합니다.
이미 실행 중인 JVM은 새 환경을 물려받지 않으므로 소유권을 확인한 별도 재시작이 필요합니다.
서로 다른 장비에서 같은 소스 파일을 직접 수정할 때는 기존 대상별 source-edit lease를 사용합니다.
아무 편집기에서나 동시 덮어쓰기를 해도 안전하다는 보장은 하지 않습니다.

키트 갱신은 전체 manifest·해시·경로 검사를 통과한 뒤 적용됩니다. 처음 설치는 기존 파일과
다르면 보존/충돌 처리하고, 다음 설치는 로컬 receipt의 기준 해시로 독립 수정분을 확인합니다.
JSON은 기준값·현재값·새 값으로 병합하며 배열의 경쟁 수정은 추측해서 합치지 않습니다.
TOML은 기존 주석과 키를 유지할 수 있는 추가만 적용하고 그 외에는 충돌로 반환합니다.
실패 시 우리 postimage가 유지된 파일만 되돌립니다. 다른 작성자의 수정은 보존하고
`recovery-required` journal을 남기며, 미완료 journal이나 알 수 없는 잠금을 자동 삭제하지 않습니다.
단일 파일 게시에는 원자적 rename을 사용하고 여러 파일은 journal/rollback으로 관리합니다.
프로세스 강제 종료나 SMB 단절 시 전체 묶음의 동시 가시성까지 보장하는 분산 트랜잭션은 아닙니다.

실제 장비 확인 시 설정 생성 결과, `status`, stdio initialize/tools-list와 source hash를 해당 장비에서
수집합니다. 현재 Desktop 테스트와 OS 분기 테스트를 Notebook/MacBook의 실제 실행 증거로 대신하지 않습니다.

## 1. Desktop에서 시작

### 공유 작업 큐와 장치 배정

공유 지시서·분석·독립 작업을 배정할 때는 기존 Control Tower의 `device_work` 또는
`python -B scripts/awx_device_work.py`를 사용합니다. JSON의 `action`은
`probe`, `enqueue`, `route`, `claim`, `next`, `start`, `complete`, `status`입니다.
현재 승인된 작업을 큐로 처리할 때 장비가 `probe → next → start → 실행 → complete`로
진행하고 같은 작업 ID를 재실행하지 않습니다. 범위 없는 자동 실행/상시 감시는 시작하지 않습니다.

Desktop은 통합·실행, Notebook은 지시·설계·가벼운 수정, Mac mini는 ARM 보조 후보가 기본입니다.
CPU·GPU·메모리·OS·현재 부하와 비교 가능한 완료시간으로 배정을 조정합니다.
Mac 자동 전환에는 같은 비교 단위의 장비당 3회 이상 기록과 최소 10% 시간 이점이 필요합니다.
장비 식별자는 역할 변경과 분리되어 같은 장비의 동시 처리 한도가 중복 생성되지 않습니다.

큐 인수는 소스 변경 권한이 아닙니다. `light-edit`은 기존 source lease/Notebook Verify를 통과하며,
독립 작업은 대상 파일이 겹치지 않으면 계속합니다. Notebook shared-read MCP 설정은 그대로
유지하고 승인된 지시서/큐 메타데이터 처리는 검증한 `Y:\`의 로컬 CLI를 사용합니다.
원격 mount·명령 실행·성능은 해당 장비에서 나온 증거로만 확인합니다.

상세 JSON 예시와 인수/복구 절차: [장치별 공유 작업 안내](../../../../docs/device-work-routing.md).
기존 프로젝트 자원 이벤트/비밀값 레지스트리가 있다면 그 소유권을 유지합니다. 이 큐는
작업의 인수·완료·자원 배정만 담당하며 인증이나 개인 설정을 동기화하지 않습니다.

```powershell
Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'
git --no-optional-locks branch --show-current
git --no-optional-locks rev-parse HEAD
```

대상 파일, 현재 지침, 변경분, 작업본·잠금·겹치는 작업자를 확인합니다.
스킬·Markdown 작업에는 애플리케이션 소스 변경용 세 갈래 사전 검토를 적용하지 않습니다.
애플리케이션 소스를 수정할 때는 [소스 편집 사전 검토](../../demo1-source-edit-three-way-preflight/SKILL.md)와
기존 소유권·임대·대상 해시 절차를 따릅니다.

기존 제어 루프가 필요한 경우 아래 설정은 지시 파일·producer kit 작성을 끄고 중첩 완료 감사를 생략합니다.
그래도 로컬 소스·증거를 조사하므로 가벼운 연결 확인 대신 매번 실행하지 않습니다.

```powershell
$taskPayload = '{"nodeRole":"desktop","topic":"node-readiness","patchdrop_root":"__patch_drop__","write_dispatch":false,"write_producer_kit":false,"require_producer_bundles":false,"require_supabase_live_proof":false,"run_completion_audit":false}'
$taskPayload | python .\scripts\awx_mcp_toolbox.py desktop_control_loop --input-json -
```

위의 `require_*:false`는 로컬 단계에 해당 외부 증거를 요구하지 않는 설정입니다.
원래 목표가 외부 연결·producer 결과·DB 증거를 요구한다면 그 완료 조건은 별도로 유지합니다.

## 2. 외부 Codex 작업

| 순서 | 앱에서 사용할 도구 | 판단 |
| --- | --- | --- |
| 찾기 | `list_projects`, `list_threads` | 실제 호스트·프로젝트·작업을 확정 |
| 읽기 | `read_thread`, 또는 `wait_threads`의 즉시 상태 조회 | 현재 진행과 필요한 입력 확인 |
| 계속하기 | 사용자 지시가 있을 때 `send_message_to_thread` | 확인된 작업에 명확한 범위 전달 |
| 옮기기 | 사용자 지시가 있을 때 `handoff_thread` | 연결된 목적지와 같은 저장소의 저장된 프로젝트 확인 |
| 확인 | `get_handoff_status`, 이후 `wait_threads` | 인계 완료와 후속 작업 완료를 각각 확인 |

새 작업은 사용자가 새 작업 생성을 요청했을 때만 만듭니다. 기존 작업을 조회하는 요청을 새 작업 생성으로
바꾸지 않습니다. 호스트가 목록에 없으면 `evidence_needed: 대상 호스트 연결`로 기록하고,
앱의 Connections에서 해당 호스트를 연결한 뒤 `list_projects`로 다시 확인합니다.

SSH 연결이 필요한 경우 현재 공식 절차에 따라 대상 호스트의 SSH 접근과 Codex 설치·인증을 먼저
확인합니다. 호스트명·계정·키를 추측해서 설정하지 않습니다.
상세: [공식 원격 연결 안내](https://learn.chatgpt.com/docs/remote-connections).

## 3. Notebook: 목적에 따라 경로 선택

| 요청 | 담당 스킬 | 완료 증거 |
| --- | --- | --- |
| 조사 내용을 Desktop에 전달 | [notebook-smb-handoff](../../notebook-smb-handoff/SKILL.md) | 지정된 인계 파일의 존재와 내용 조건 |
| Desktop에서 인계 수신·처리 | [desktop-smb-ack](../../desktop-smb-ack/SKILL.md) | 수신·처리 기록; 소스 적용은 해당 작업의 별도 증거 |
| 명시적으로 요청된 Y드라이브 직접 소스 수정 | [demo1-macsrc-smb-direct-patch](../../demo1-macsrc-smb-direct-patch/SKILL.md) | backing identity, 대상 임대·해시, 집중 검증 |
| 독립 작업본의 패치 제작 | [patchdrop-safe-patch-orchestrator](../../patchdrop-safe-patch-orchestrator/SKILL.md) | 분리된 작업본, 누적 v3 패치와 동반 파일 |

Notebook의 `Y:\` 권한을 판단하기 전에 해당 Notebook의 확인된 저장소에서
`scripts\verify_ydrive_backing_identity.ps1`을 실행합니다. 출력은 canonical workspace,
동일성 확인 여부, 이유 코드만 유지합니다. 원격 공유 매핑을 출력하지 않습니다.

명시적으로 승인된 직접 소스 수정 모드는 `YDRIVE_SMB_GUARDED_DIRECT`입니다.
동일성 불일치·겹치는 임대·바뀐 대상 해시가 있으면 해당 쓰기를 보류합니다.
조사만 하는 `SMB_ACCESS`는 `sourceWriteRoot=null`, `authorizedMutation=false`입니다.
단순히 Y드라이브가 존재하거나 파일이 읽힌다는 사실은 쓰기 권한을 증명하지 않습니다.

## 4. Mac mini: 독립 작업본에서 제작

[macmini-safe-patch-assistant](../../macmini-safe-patch-assistant/SKILL.md)를 사용해 실제 독립 작업본을
확인하고, 그 작업본의 지침을 읽습니다. Desktop 저장소의 현재 디렉터리나 공유 소스를
Mac mini 작업본으로 추정하지 않습니다.

확인된 producer 작업본에서 노드 실행 증거가 필요한 경우:

```bash
python scripts/awx_mcp_node_smoke.py --root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --node-role macmini
```

위 명령은 Mac mini의 독립 작업본에서 실행할 예시입니다. 인자의 Desktop 경로는 격리 비교용 기준이며
Mac mini의 로컬 쓰기 위치가 아닙니다. 실제 호스트의 결과를 수집해야 외부 노드 증거로 평가할 수 있습니다.

필요한 스크립트가 producer 작업본에 없고 설치 키트 준비가 요청된 경우, **Desktop에서** 아래 명령을 사용합니다.
이 명령은 `__patch_drop__/producer-kit/` 아래에 파일을 쓰는 작업입니다.

```powershell
$taskPayload = '{"nodeRole":"desktop","topic":"node-bootstrap","patchdrop_root":"__patch_drop__","output_dir":"__patch_drop__/producer-kit/node-bootstrap-producer-kit"}'
$taskPayload | python .\scripts\awx_mcp_toolbox.py producer_kit_export --input-json -
```

생성된 kit manifest와 실제 포함 파일을 확인합니다. 키트가 모든 외부 플러그인이나 이 안내의 새 참조까지
자동 포함한다고 가정하지 않습니다. 누락된 안내가 필요하면 승인된 독립 작업본에 이 문서·진입 스킬·참조를
함께 전달하고 링크를 확인합니다. 외부 플러그인 원본은 해당 호스트에 설치된 버전을 사용합니다.

## 5. 결과를 Desktop에서 확인

먼저 실제 작업 주제와 증거 위치를 확정합니다. 아래 `node-work`는 예시 주제이므로 실제 값으로 바꿉니다.
이미 전달된 증거를 읽는 `external_evidence_audit`와 증거를 복사하는 `external_evidence_intake`를 구분합니다.

```powershell
$taskPayload = '{"nodeRole":"desktop","topic":"node-work","evidence_dir":"data/agent-handoff/mcp-control-tower","patchdrop_root":"__patch_drop__","required_roles":["macmini","notebook"],"require_producer_bundles":true}'
$taskPayload | python .\scripts\awx_mcp_toolbox.py external_evidence_audit --input-json -
```

`required_roles`는 실제로 작업을 맡긴 장비만 포함합니다. 패치를 요구하지 않은 연결 점검이라면
`require_producer_bundles`를 그 목적에 맞게 설정하고, 완료 범위를 명시합니다.
누락 결과의 `nextActions`를 읽고 필요한 자료만 수집합니다.

패치를 적용할 때는 [PatchDrop 절차](../../patchdrop-safe-patch-orchestrator/SKILL.md)가 기준입니다.
단일 manifest가 지정하는 누적 v3 패치와 `.report.md`, `.verify.log`, `.sha256.txt`, `.manifest.json`을 확인합니다.
중첩 producer 패치는 기존 승격 절차를 거쳐야 하며, 가장 최근 파일을 임의로 선택하지 않습니다.
실제 적용 전에 inventory·검토·비밀값 검사·체크섬·적용 가능성·소스 임대를 확인하고,
Desktop 검증이 끝난 뒤에만 적용 완료로 기록합니다.

## 6. 결과 기록과 재개

| 항목 | 기록 방법 |
| --- | --- |
| 대상 | 확인된 장비 역할·작업·파일 범위 |
| 관측 | 명령 종료 코드, 검증 결과, 필요한 개수·해시 |
| 전달 | 파일·응답 수신 여부; 의미상 성공과 분리 |
| 실행 | 요구한 작업을 충족한 현재 증거 |
| 누락 | `evidence_needed`와 판단을 바꿀 한 가지 확인 행동 |
| 공급자 시도 | 관측되지 않았으면 `not_observed` |

보류 시 `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`,
`repositoryWideHold`를 기록합니다. 한 장비의 연결 부족은 그 장비에 의존하는 작업만 보류합니다.
같은 결과를 반복 조회하기보다 새로운 대상 상태·파일 해시·검증 결과가 생겼을 때 재평가합니다.

`verify_control_plane_topology.ps1`의 로컬 테스트·시뮬레이션 결과도 실제 외부 장비 연결 성공과 구분합니다.
이 문서의 작성·링크·스킬 형식 검증은 문서 산출물의 완료 증거입니다.
