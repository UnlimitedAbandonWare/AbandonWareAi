# 외부 스킬 사용 안내

[시작 화면](../../../../EXTERNAL_SKILLS.md) · [장비별 실행 절차](node-playbook.md) · [진입 스킬](../SKILL.md)

이 문서는 설치된 외부 스킬을 이 저장소의 작업에 연결하는 사용 안내입니다.
공급자 스킬·실행 파일·계정 연결은 각 호스트의 설치 상태를 사용합니다.
원본 내용을 중복 설치하는 대신, 여기에서 필요한 절차와 호출 예시를 선택합니다.

## 도구를 선택하는 순서

1. 원하는 결과와 대상 장비·프로젝트·작업을 확정합니다.
2. 현재 세션의 스킬 목록과 호출 가능한 도구를 확인합니다. `tool_search`가 있으면 사용하고,
   없으면 `ALL_TOOLS`의 이름·설명에서 관련 기능만 찾습니다.
3. 아래 표에서 필요한 외부 스킬을 골라 현재 목록에 표시된 `SKILL.md`를 읽습니다.
4. 작업 도구를 호출하기 전 대상 연결과 그 작업에 대한 권한을 확인합니다.
5. 실제 결과로 완료 조건을 검증합니다. 도구가 보이거나 요청이 전달된 사실만으로 완료 처리하지 않습니다.

플러그인 설치는 누락 기능을 해결할 때만 제안합니다. 추천 목록에 있다는 이유로 설치하지 않습니다.
없어진 도구 이름을 임의로 호출하지 말고, 호출 가능한 대체 기능 또는 필요한 연결 조치를 기록합니다.

## 기능별 선택표

| 기능 | 선택할 스킬·도구 | 입력 | 검증할 결과 |
| --- | --- | --- | --- |
| Codex 작업 조정 | 앱의 `list_projects`, `list_threads`, `read_thread`, `wait_threads` | 실제 반환된 대상 ID | 대상 호스트, 작업 상태, 결과와 다음 행동 |
| 기존 작업 계속하기·인계 | 앱의 `send_message_to_thread`, `handoff_thread`, `get_handoff_status` | 사용자 지시와 확인된 대상 | 전송·인계 완료와 후속 작업의 의미상 성공을 각각 확인 |
| 다중 장비 인계 | 이 저장소의 제어 스킬과 toolbox | 장비 역할, 주제, 대상 파일, 완료 조건 | 노드 증거, 패치 구성, Desktop 검증 |
| Superpowers | `brainstorming`, 작업에 맞는 실행·디버깅·검증 스킬 | 요청, 현재 증거, 이미 승인된 설계 | 승인 범위 안의 변경과 검증 결과 |
| 컴퓨터 | `computer-use:computer-use`와 사용 가능한 `node_repl` | 특정 Windows 앱·화면·행동 | 대상 창에서 관측한 UI 결과 |
| 브라우저 | 현재 브라우저 도구의 문서, 필요하면 설치된 `playwright` | 지정된 탭 또는 URL, 확인할 동작 | 페이지·상호작용의 현재 증거 |
| Sites | `sites:sites-building`, 배포가 요청된 경우 `sites:sites-hosting` | 사이트 목적, 로컬/호스팅 범위 | 해당 범위의 빌드·동작 또는 배포 결과 |
| Deep Research | `deep-research-work:deep-research` | 조사 질문, 범위, 출처 기준 | 출처가 있는 답, 불일치·누락 근거 |
| Plugin Management | `plugin-management:plugin-management` | 필요한 기능 또는 정확한 플러그인 | 호출 가능한 기능·권한·의존성·연결 필요 사항 |
| Data Analytics | `data-analytics:index`, 필요한 분석 스킬 | 실제 구조화된 기록과 분석 질문 | 정의된 분모·기간·단위에 맞는 계산 |
| Visualize | `visualize:visualize` | 설명할 관계 또는 상호작용 | 관계가 명확한 Mermaid 또는 대화용 시각화 |
| GLM worker | `token-efficient-agents` → `glm-offload` → 사용 가능한 작업자 | 제한된 읽기 전용 조사 패킷 | 전달 표식과 요구 형식을 충족한 작업별 답 |

### Codex 앱과 장비

- 현재 `list_projects` 결과로 호스트와 저장소를 찾습니다. 폴더 이름이 Mac mini를 연상시켜도
  `hostId`가 로컬이면 원격 컴퓨터가 연결됐다고 보지 않습니다.
- `list_threads`로 반환된 작업 이름을 그대로 사용하고, 요약을 새 작업 이름처럼 표시하지 않습니다.
- 사용자가 새 작업을 요청했을 때만 `create_thread`를 사용합니다. 현재 요청의 일부를 조사할 때는
  제한된 서브에이전트를 사용합니다. 서브에이전트는 별도 물리 장비가 아닙니다.
- 다른 작업으로 메시지를 보내거나 실행 중인 작업을 인계할 때는 그 동작에 대한 사용자 지시가 필요합니다.
  인계는 실행 중인 작업을 중단할 수 있습니다. 현재 호출 중인 작업은 스스로 인계하지 않습니다.
- 상태 확인은 `wait_threads`의 반환 커서를 재사용합니다. 인계의 `operationId`는
  `get_handoff_status`로 확인합니다. 변경 없는 상태를 짧은 간격으로 반복 조회하지 않습니다.

### 컴퓨터와 브라우저

Windows UI가 필요하면 먼저 해당 Computer Use 스킬과 지침을 읽습니다. 설치된 스킬의 초기화는
사용 가능한 `node_repl`에서 `@oai/sky`를 가져오는 방식입니다. API 문서를 읽고 특정 창을 확인한 뒤
조작합니다. 파일·PowerShell만으로 해결되는 작업은 그 경로를 사용합니다.

브라우저 도구는 그 세션의 첫 호출·탭 선택 규약을 따릅니다. 예를 들어 현재 CUA 도구는
전체 표면이 필요할 때 `cua.getState()`를, 알려진 탭에는 `cua.getTab(...)`을 사용합니다.
브라우저 CUA의 네이티브 앱 지원 여부와 별도 Computer Use 런타임의 지원 여부를 혼동하지 않습니다.
UI를 보지 않았으면 확인했다고 기록하지 않습니다.

### 조사·플러그인·분석·시각화

- **Deep Research:** 먼저 결정할 질문과 조사 범위를 정합니다. 외부 제품의 최신 동작은 공식 자료를
  확인하고, 저장소 동작은 현재 코드·명령 결과를 기준으로 합니다. 충분한 근거가 모이면 조사를 종료합니다.
- **Plugin Management:** 우선 현재 제공되는 도구를 사용합니다. `search_plugins` 같은 이름이 안내에
  있어도 현재 도구 목록에 없으면 호출 가능하다고 가정하지 않습니다. 권한 변경·설치·삭제는 해당 요청과
  실제 도구 규약을 따릅니다. 스킬 파일 존재와 계정 연결을 분리해 기록합니다.
- **Data Analytics:** 구조화된 데이터나 수치 판단이 필요한 경우에만 분석 경로를 엽니다.
  단순 문서 편집은 분석 작업으로 확대하지 않습니다. 실행되지 않은 장비의 성공률·시간을 만들지 않습니다.
  토큰 사용량이 미관측이면 `evidence_needed`로 남깁니다.
- **Visualize:** 정적인 연결은 Mermaid로, 값을 바꿔가며 이해해야 하는 경우는 대화형 시각화로 표현합니다.
  그림의 연결선은 실제 접속 증거가 아니므로 관측 상태를 별도로 표시합니다.
- **Sites:** 로컬 문서 정리 요청은 문서로 완료합니다. 사이트 제작이 요청되면 Sites 지침을 적용하고,
  사용자가 로컬 전용으로 정한 경우 외부 사이트를 등록하거나 배포하지 않습니다.

### GLM 전달 조건

현재 사용자 지침의 `token-efficient-agents`와 `glm-offload`가 라우팅 기준입니다.
세션당 허용된 한 번의 프로세스 환경 확인은 아래처럼 **존재 여부만** 출력합니다.

```powershell
Write-Output ("glmKeyPresent={0}" -f ((Test-Path Env:AI_GATEWAY_API_KEY).ToString().ToLowerInvariant()))
```

키가 있어도 사용 가능 판정은 아닙니다. 현재 사용자 지침의 CLI `0.144.1` +
`gpt-5.6-sol`의 `multi_agent_version=v2` 전달 오류 보류 조건이 유지되면 GLM을 호출하지 않습니다.
`codex --version`과 필요한 모델의 필드만 추려 조건을 확인하며 전체 모델 응답·환경을 출력하지 않습니다.
조건 변경 후의 재검증도 해당 지침에 따라 한 번만 수행합니다. 정상 응답은 비민감 전달 표식과
요구 형식을 충족해야 합니다. 실패 시 지침이 허용하는 Codex 탐색 작업자로 전환합니다.
문서에 적힌 버전은 현재 지침의 조건이며 모든 향후 버전의 동작을 보장하지 않습니다.

## 전달할 최소 작업 패킷

원격 작업 또는 서브에이전트에 필요한 사실만 제공합니다.

```text
objective: 요청한 최종 결과
target: 현재 확인된 호스트·프로젝트·작업 또는 로컬 대상
scope: 허용된 파일·경로와 읽기/쓰기 범위
knownFacts: 현재 확인된 사실
constraints: 변경하면 안 되는 조건, 권한, 외부 호출 한도
expectedOutput: 결과 형식과 이를 증명할 자료
stopCondition: 완료·실패·새 증거 필요 시점
```

원시 프롬프트·응답, 비밀값, 인증 헤더, 쿠키, 원격 공유의 실제 매핑을 공개 증거에 넣지 않습니다.
상태·횟수·해시·이유 코드로 충분한 경우 그 정보만 남깁니다.

## 원본 스킬 위치

**2026-09-08 Desktop 파일 존재 확인 기준.** 아래는 이 호스트의 위치 기록입니다.
다른 장비에서는 현재 세션의 스킬 목록을 우선하며, 캐시 버전 경로를 그대로 복사해 사용하지 않습니다.
아래 경로 약칭은 설명용이며 실제 환경 변수나 새 설정이 아닙니다.

| 약칭 | 이 Desktop의 절대 경로 |
| --- | --- |
| `B` | `C:/Users/nninn/.codex/plugins/cache/openai-bundled` |
| `P` | `C:/Users/nninn/.codex/plugins/cache/openai-curated-remote` |
| `S` | `C:/Users/nninn/.codex/skills` |
| `A` | `C:/Users/nninn/.agents/skills` |

| 스킬 | 위 경로에 이어지는 원본 위치 |
| --- | --- |
| Superpowers 설계 | `P/superpowers/6.3.0/skills/brainstorming/SKILL.md` |
| Computer Use | `B/computer-use/26.901.51231/skills/computer-use/SKILL.md` |
| Browser의 터미널 대안 | `S/playwright/SKILL.md` |
| Sites | `B/sites/0.1.57/skills/sites-building/SKILL.md` |
| Deep Research | `P/deep-research-work/0.1.14/skills/deep-research/SKILL.md` |
| Plugin Management | `P/plugin-management/0.1.0/skills/plugin-management/SKILL.md` |
| Data Analytics | `P/data-analytics/0.2.10-13ceeea1f599/skills/index/SKILL.md` |
| Visualize | `B/visualize/1.0.29/skills/visualize/SKILL.md` |
| GLM 라우팅 | `A/glm-offload/SKILL.md` |
| 작업자 비용·범위 라우팅 | `A/token-efficient-agents/SKILL.md` |
| 스킬 작성·형식 검증 | `S/.system/skill-creator/SKILL.md` |

현재 CUA 브라우저 도구는 세션에 제공된 API 문서가 기준입니다. 별도의 Browser 플러그인이
설치됐다고 추정하거나 존재하지 않는 `SKILL.md` 경로를 만들지 않습니다.

## 이 작업에서 확인한 범위

설계 조사 시 앱의 저장된 프로젝트 목록에는 로컬 호스트만 확인됐습니다. 이름만으로 원격 호스트를
추정하지 않았으며, Notebook·Mac mini 실제 연결은 `evidence_needed`입니다.
이 상태는 실시간 모니터가 아니므로 다음 작업에서 `list_projects`를 다시 확인합니다.

스킬 원본, 현재 도구 목록, 기존 repo toolbox 구현은 확인 대상입니다. 외부 노드의 실제 제어,
Sites 배포, Windows UI·브라우저 동작, GLM 실행의 성공을 이 문서 작성으로 주장하지 않습니다.

공식 근거: [스킬 구조·발견·배포](https://learn.chatgpt.com/docs/build-skills) ·
[원격 호스트 연결·작업 인계](https://learn.chatgpt.com/docs/remote-connections)
