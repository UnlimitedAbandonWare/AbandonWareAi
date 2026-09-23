# Device Probe Resume ExecPlan

## Objective

기존 `device_probe_gpt_*` 구현을 이어서 디버깅하고, 같은 물리 노드의 이중 역할 오인식을 차단하며, SMB 요청 왕복을 `PUBLISHED -> DISCOVERED -> PROBED -> REQUESTED -> RESPONDED -> ACKNOWLEDGED -> RESOLVED`까지 검증한다. 실제 별도 노드에 접근할 수 없으면 합성 2노드 검증을 완료하고 실제 2노드 실행만 `HOLD`로 남긴다.

## Constraints

- 기존 CLI와 JSON 필드는 additive 변경만 허용한다.
- 원시 MachineGuid, MAC 주소, 비밀값은 파일이나 콘솔에 출력하지 않는다.
- Java, Spring Boot, LangChain4j, OpenSSL/opnessl 설정은 변경하지 않는다.
- Desktop의 현재 checkout만 최종 검증 소스로 사용한다.
- 실제 2노드 성공은 서로 다른 호스트명과 서로 다른 해시 지문이 관찰된 경우에만 선언한다.

## Milestones

- [x] 현재 branch/HEAD, sourceSets, dirty tree, lock/lease/PatchDrop 상태 확인
- [x] 비교 스크립트 parser RED 재현 및 최소 복구
- [x] 동일 호스트명 또는 동일 지문의 `INVALID_SAME_NODE` 차단
- [x] 요청/응답/ACK/해결 상태와 pending 0/0/0 복구
- [x] 타임라인 NDJSON, 메타데이터, 순서, 재실행 불변성 검증
- [x] 수집기의 비밀값/원시 식별자 비노출 및 최신 파일 원자적 교체
- [x] `collect -> publish -> compare -> loop -> summary` run-all 래퍼 추가
- [x] 임시 디렉터리 기반 회귀 테스트 추가
- [x] 실제 별도 노트북 접근성 확인; 접근 근거가 없어 실제 SMB 왕복만 `HOLD`
- [x] 최종 상태/해시/비밀 스캔/lease 종료 기록

## Progress Log

- 2026-08-20: 기존 두 역할 산출물이 동일 호스트명과 동일 지문을 가진 같은 물리 장비임을 확인했다. 과거 결과는 실제 2노드 증거로 채택하지 않았다.
- 2026-08-20: 비교 스크립트의 닫는 중괄호 누락, null/빈 컬렉션 처리, 빈 배열 JSON 직렬화, NDJSON 압축 직렬화 문제를 수정했다.
- 2026-08-20: ACK 대상 조건과 in-memory `RESOLVED` 반영을 수정해 request/response/ACK pending을 모두 0으로 만들었다.
- 2026-08-20: 동일노드 차단, stale, malformed JSON 격리, 전체 왕복, 두 번째 실행 불변성, run-all과 비정상 공유경로를 영구 회귀 테스트로 고정했다.
- 2026-08-20: UTC로 파싱한 수집 시각을 로컬 시각과 빼서 나이를 9시간 부풀리던 결함을 RED로 재현하고 `DateTime.UtcNow` 기준으로 수정했다.

## Surprises & Discoveries

- 이전 `desktop`/`notebook` JSON은 역할명만 달랐고 실제 호스트와 지문은 같았다.
- PowerShell의 `return $array` 열거와 빈 배열 직렬화가 메시지 정규화 및 `events.ndjson` 유효성에 직접 영향을 주었다.
- UTC 정규화된 시각과 로컬 `Get-Date`의 뺄셈은 시간대 오프셋만큼 stale 나이를 부풀렸다.

## Decision Log

- 동일노드는 호스트명 일치 또는 해시 지문 일치 중 하나만 성립해도 차단한다. 이유: 역할 라벨은 물리 장비 정체성을 증명하지 않는다.
- 기존 `machineGuid` 필드는 호환성을 위해 남기되 새 수집 결과에는 `null`만 기록한다. 비교기는 구형 입력을 메모리에서 해시할 수 있으나 재출력하지 않는다.
- 실제 SMB가 없어도 같은 실행 로직을 쓰는 합성 2노드 fixture로 결정론적 검증을 수행한다. 이는 실제 2노드 증거를 대체하지 않는다.
- run-all은 기존 collector/comparer를 호출하는 얇은 조정층으로 두고 새 수집·비교 구현을 만들지 않는다.

## Verification Commands

```powershell
pwsh -NoProfile -File .\scripts\device_probe_gpt_tests.ps1

$files = @(
  '.\scripts\device_probe_gpt_tool.ps1',
  '.\scripts\device_probe_gpt_compare.ps1',
  '.\scripts\device_probe_gpt_run_all.ps1',
  '.\scripts\device_probe_gpt_tests.ps1'
)
foreach ($file in $files) {
  $tokens = $null; $errors = $null
  [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $file), [ref]$tokens, [ref]$errors) | Out-Null
  "${file}: parserErrors=$(@($errors).Count)"
}
```

## Outcomes & Retrospective

- 합성 2노드 E2E: `PASS`. run-all의 다섯 단계 종료 코드는 모두 0이고, 타임라인은 유효한 NDJSON 10행이며 필수 상태 순서를 포함한다. request/response/ACK pending은 대시보드와 pending-summary에서 모두 `0/0/0`이다. 같은 `TaskId`의 두 번째 실행은 메시지 수와 타임라인 수를 늘리지 않았다.
- 동일노드 방지: `PASS`. 호스트명만 같은 경우 `same_hostname`, 지문만 같은 경우 `same_fingerprint`로 각각 `INVALID_SAME_NODE`이며 정상 비교 행은 0개다.
- 장애 경계: `PASS`. 75분 probe와 60분 기준은 `stale`, 부분 JSON은 quarantine, 디렉터리가 아닌 공유경로는 run-all 전체 종료 코드 1과 실패 단계를 남겼다.
- 개인정보: `PASS`. 새 실수집 산출물에서 원시 MachineGuid, 원시 MAC, 민감 환경값 적중 수가 각각 0이며 `.tmp` 잔류도 0이다.
- 저장소 경계: `PASS`. Java 17.0.13, `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`를 격리된 Gradle 캐시로 검증했다. Java/Gradle/OpenSSL/opnessl 파일은 수정하지 않았다.
- 실제 2노드 E2E: `HOLD`. `canonicalWorkspace=Y:\`, `backingShareIdentityVerified=false`, `backingShareIdentityReason=evidence-needed`이고 이 세션에는 Y drive가 없다. 열린 Parsec 창의 접근성 트리에는 연결 장비가 없었으며 화면 캡처도 Windows `0x80004002`로 두 번 실패했다. 새 원격 연결이나 인증을 추측해 만들지 않았다.
- Browser: 적용할 localhost/UI 표면이 없는 PowerShell/JSON/Markdown 도구이므로 실행하지 않았다. 파일·프로세스 증거가 이 작업의 권위 있는 표면이다.

실제 별도 노트북이 공유경로에 접근 가능한 상태에서 다음 명령을 노트북과 데스크톱에 차례로 실행하면 `HOLD`를 해소할 수 있다.

```powershell
# 실제 노트북
pwsh .\scripts\device_probe_gpt_run_all.ps1 -Role notebook -ShareRoot "<verified-share>" -TaskId "probe-handoff"

# 실제 데스크톱
pwsh .\scripts\device_probe_gpt_run_all.ps1 -Role desktop -ShareRoot "<verified-share>" -TaskId "probe-handoff"
```
