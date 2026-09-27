# Demo1 Desktop Y-to-C Directive Rebinding Design

## 목적

Notebook에서 생성된 `Y:\` 표기 SourceDirective를 Desktop이 받을 때, Notebook 경로를 실행 권한으로 오인하거나 `Y:\`와 Desktop 로컬 checkout을 동시에 수정하는 충돌을 막는다. 기존 `demo1-desktop-canonical-goal-intake`를 확장하며 새 source-edit, lease, PatchDrop 프로토콜은 만들지 않는다.

## 고정 경계

```text
originEvidenceRoot=Y:\
yEvidenceAuthority=supporting_only
canonicalExecutionRoot=C:\AbandonWare\demo-1\demo-1\src
readRoot=C:\AbandonWare\demo-1\demo-1\src
writeRoot=C:\AbandonWare\demo-1\demo-1\src
finalProofRoot=C:\AbandonWare\demo-1\demo-1\src
fallbackWriteRoot=null
sourceOwner=desktop
```

`Y:\`의 파일, hash, test, branch, sourceSet, runtime 주장은 입력 증거로만 보존한다. Desktop에서 live root, revision, active sourceSet, target preimage를 다시 증명하기 전에는 mutation 권한이나 최종 증명이 아니다.

## 선택한 구조

기존 repo-local 스킬을 얇은 intake router로 유지하고 세 자산만 확장한다.

1. `.agents/skills/demo1-desktop-canonical-goal-intake/SKILL.md`
   - 트리거를 Y/SMB-originated Desktop execution 상황에 맞게 정리한다.
   - root selection, evidence demotion, lane selection만 소유한다.
   - 상세 rebinding과 단계 계약은 reference와 기존 하위 스킬로 위임한다.
2. `references/directive-rebinding-contract.md`
   - Y 절대경로에서 repo-relative target을 얻는 계약, C-root 결합 조건, 두 단계 실행 계약, 실패 분류와 단일 예시를 둔다.
3. `scripts/resolve_desktop_directive_target.ps1`
   - read-only 경로 검증기다. 입력 Y 경로를 문자열 치환하지 않고 정규화된 상대경로로 검증한 뒤 C-root 후보를 계산한다.
   - source write, lease, Git trust 변경, 파일 생성·삭제를 하지 않는다.

`agents/openai.yaml`은 SKILL.md의 트리거와 기본 프롬프트에 맞춰 동기화한다. 애플리케이션 소스, `AGENTS.md`, `agent-prompts`, Gradle 설정은 수정하지 않는다.

## 경로 재결속 흐름

1. 입력 path를 repo-relative, 정확한 `Y:\` 하위 절대경로, 정확한 canonical C-root 하위 절대경로 중 하나로 분류한다.
2. Y/C 절대경로는 prefix 문자열 치환 대신 root-relative 계산으로 상대경로를 얻는다. 이미 상대경로인 입력도 같은 검사를 거친다.
3. 다른 drive, UNC, ADS, 명시적 `..` segment, 빈 상대경로를 거부한다.
4. 상대경로를 고정 C-root에 결합하고 canonical C-root 밖으로 나가는지 확인한다. 같은 C 입력을 다시 처리해도 결과가 바뀌지 않아야 한다.
5. Desktop 실행 시 C-root 존재, local non-UNC Git top-level 일치, target 존재, reparse traversal 부재를 확인한다.
6. 원본 Y 경로와 C 후보의 동일성을 추정하지 않는다. revision/sourceSet/preimage가 C-side에서 확인될 때만 `preimageState=verified`가 된다.

검사기 출력은 `status`, `reason`, 고정 C-root, `targetRel`, C 후보, `withinRoot`, `exists`, `reparseRisk`만 포함한다. 예상 밖 backing/UNC 경로는 출력하지 않는다. lexical 검증 결과만으로 mutation을 승인하지 않는다.

## SourceDirective 재작성 계약

Desktop intake는 다음처럼 authority 필드를 재작성한다.

| Notebook 입력 | Desktop 결과 |
| --- | --- |
| `provenRoot: Y:\` | `originEvidenceRoot: Y:\`, `provenRoot: evidence_needed` |
| Y 절대 target | 검증된 `targetRel`과 C-root 후보 |
| Notebook branch/revision/hash | supporting evidence |
| Notebook verification command | C-root와 Desktop-local cache를 쓰는 명령으로 재생성 |
| Notebook final proof | `desktopFinalProof=evidence_needed` |

Desktop live proof가 끝나면 `provenRoot`만 고정 C-root로 승격한다. 다른 절대 root, UNC, OneDrive, backup, local clone으로 fallback하지 않는다.

## 두 단계 실행 계약

이 스킬은 단계 순서만 검증하고 source mutation은 기존 owner에게 위임한다.

### Stage 1

- Desktop C-root에서 fresh EvidenceSnapshot과 정확히 세 query preflight를 수행한다.
- 지시서가 선언한 production target과 두 RED 테스트를 고정한다.
- stable `APPLY` 뒤 기존 Desktop source lease를 취득하고 preimage를 즉시 재확인한다.
- 최소 패치와 host-local Gradle cache를 사용한 focused GREEN을 수행한다.
- postimage, changed-path subset, count-only secret scan을 기록한 뒤 lease를 해제한다.

### Stage 2

- Stage 1 GREEN과 lease 해제 뒤에만 탐색한다.
- 같은 인과·호출 경계에 있고 active sourceSet이며 독립 RED로 반증 가능한 production 후보만 인정한다.
- formatting이나 일반 정리는 `clean candidate`가 아니다.
- 새 EvidenceSnapshot, 새 three-way preflight, 새 lease를 사용한다.
- 추가 production target은 최대 한 개다. 후보가 없으면 `no_additional_candidate`로 정상 종료한다.
- Stage 1 회귀 테스트와 Stage 2 focused test가 모두 GREEN인 경우에만 lease를 해제한다.

`AnswerQualityEvaluator`와 `EvidenceRepairHandler`는 이 계약을 설명하는 한 가지 사용 예시일 뿐, 일반 스킬의 고정 target이 아니다.

## 기존 스킬 위임

- application source 판단: `demo1-source-edit-three-way-preflight`
- Desktop source lease와 preimage/rollback: 기존 repository-owned source-edit guard
- Desktop 최종 증명: `demo1-desktop-only-proof-loop`
- 스킬 구조 검증: `demo1-skill-family-postprocessor`
- Supabase 등 외부 보조 증거: `demo1-demand-driven-external-proof`

새 lease, CAS, PatchDrop, verification framework를 만들지 않는다.

## 실패 처리

다음은 `HOLD`이며 fallback write 권한을 만들지 않는다.

- `c-canonical-unavailable`
- `directive-path-not-y-rooted`
- `directive-path-escape`
- `directive-path-unsupported`
- `reparse-traversal-risk`
- `revision-mismatch`
- `active-sourceset-uncertain`
- `dirty-target-overlap`
- `index-lock-conflict`
- `source-lease-conflict`
- `preimage-changed`
- `patch-drop-pending`
- `stage-one-not-green`
- `stage-two-red-missing`
- `verification-unproven`

오류 출력은 reason code와 필요한 단일 증거만 포함한다.

## 보안과 권한

- Supabase는 project scope와 필요성이 입증된 경우에만 read-only 보조 증거다.
- DB, public API, credential, secret, 환경 변수 이름을 변경하지 않는다.
- raw key, token, cookie, authorization header, 환경 dump를 출력하지 않는다.
- commit, push, deploy, 외부 메시지는 수행하지 않는다.
- rollback은 단계별로 검증된 preimage만 복원하며 `git reset --hard`나 광범위 checkout을 사용하지 않는다.

## 검증 설계

### RED

기존 스킬만 제공한 fresh-agent 압박 시나리오에서 다음 실패를 찾는다.

- `Y:\..\outside` 또는 mixed-drive target을 C-root로 단순 치환한다.
- Y-side hash를 C-side preimage로 간주한다.
- Stage 1 lease를 유지한 채 Stage 2 범위를 넓힌다.
- GREEN만으로 독립 RED 없는 clean 후보를 수정한다.

기준 agent가 이미 모두 거부하면 중복 지침을 추가하지 않고 deterministic validator의 구조적 공백만 검증한다.

### GREEN

- 정상 Y target, repo-relative target, 이미 canonical C-root 아래인 target은 동일 repo-relative C 후보로 수렴한다.
- `..`, UNC, 다른 drive, ADS, root 자체, reparse 위험은 명시적 HOLD다.
- C-root가 없는 Notebook에서는 `c-canonical-unavailable`이며 성공으로 위장하지 않는다.
- Stage 2는 fresh preflight와 lease 재취득을 요구하고 후보 수를 1로 제한한다.
- `quick_validate.py`, `agents/openai.yaml` 검사, repo skill-family validator가 통과한다.
- 변경된 skill artifact에 대해 count-only secret scan 결과가 0이다.

## 완료 조건

- 기존 intake 스킬이 Y-originated Desktop execution 요청에 안정적으로 trigger된다.
- 경로 검사기가 read-only이며 정상·이탈·혼합 경로 테스트를 통과한다.
- SKILL.md는 기존 audit budget 안에 머문다.
- Desktop C-root의 실제 존재와 Git 상태는 Notebook에서 PASS로 주장하지 않고 `desktopFinalProof=evidence_needed`로 남긴다.
- 애플리케이션 소스 변경 수는 0이다.
