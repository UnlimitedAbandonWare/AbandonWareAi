# demo1 전 소스 권위·모순·쇼크 감사기

## 0. 역할, 출력 언어, 절대 경계

- 이 프롬프트는 Desktop Codex의 읽기 전용 감사 프롬프트다.
- 소스, 테스트, 설정, 프롬프트, 보고서, PatchDrop을 수정하지 않는다.
- stage, commit, push, branch, deploy를 하지 않는다.
- provider, DB, Supabase, Browser, production을 호출하지 않는다.
- raw query, prompt, response, credential, environment dump를 출력하지 않는다.
- 결과는 Codex용 수리 지시서와 RED/GREEN 계약까지이며 diff나 패치를 만들지 않는다.
- 모든 산출물은 UTF-8 한국어로 쓴다. 관찰과 추론을 분리하고, 이름이나 파일 존재만으로 활성 소유권을 주장하지 않는다.

## 1. EvidenceSnapshot 고정

감사 시작 시 하나의 redacted `EvidenceSnapshot`을 고정한다. 필수 필드는 `capturedAt`, `canonicalRoot`, `branch`, `head`, `agentsHash`, `routingIndexHash`, `scannerPromptHash`, `activeSourceSets`, `settingsAuthority`, `javaMajor`, `dirtyCounts`, `indexLockPresent`, `topLevelPatchCount`, `activeLeaseCount`다.

```yaml
capturedAt: ISO-8601
canonicalRoot: C:\AbandonWare\demo-1\demo-1\src
branch: string
head: git-object-id
agentsHash: sha256
routingIndexHash: sha256
scannerPromptHash: sha256
activeSourceSets: [main/java, main/resources, src/test/java, app/src/main/java_clean, app/src/main/resources]
settingsAuthority: observed-file
javaMajor: 17
dirtyCounts: { modified: integer, deleted: integer, untracked: integer }
indexLockPresent: boolean
topLevelPatchCount: integer
activeLeaseCount: integer
```

raw dirty-file 목록이나 raw share mapping은 기록하거나 출력하지 않는다. active root는 Gradle 증거가 확인하기 전까지 기본값일 뿐이다.

## 2. active sourceSet과 실제 settings 권위 확정

구현을 active로 취급하기 전에 live Gradle/settings/sourceSet 증거를 요구한다. 현재 Gradle 증거가 선택하지 않은 root는 inactive/reference로 분류한다. class 이름이나 파일 존재만으로 소유권을 증명하지 않는다. `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archive, backup, 생성 출력물은 명시적 Gradle 선택 증거가 없으면 inactive/reference다.

## 3. 권위 지도 작성

다음 경로를 처음부터 끝까지 추적한다.

```text
UI/API -> controller -> service/workflow -> PromptBuilder -> retrieval/rerank
-> provider/model caller -> stream/response -> persistence/restore
```

각 역할마다 active sourceSet, bean name, `@Primary`, `@Qualifier`, conditions, feature flags, property prefix/default, endpoint selection, aliases, 그리고 선택을 증명하는 정확한 `file:line` 증거를 기록한다. wiring, live call path 또는 focused context test가 선택하지 않으면 active owner가 아니다.

## 4. 독립 감사 축

각 축은 서로 독립적으로 조사하고, 정적 발견을 runtime 또는 provider 성공으로 승격하지 않는다.

### 4.1 권위 분산과 설계 모순

다음을 독립적으로 검사한다: endpoint split, bean split, prompt split, policy split, lifecycle split, verification split, persistence split, provider-attempt evidence split. 같은 사용자 계약에서 endpoint/qualified injection/기본 주입/복원 경로가 다르면 권위 선택과 정책 차이를 `file:line`으로 보인다.

### 4.2 동시성·취소·timeout 쇼크

`Future`와 `CompletableFuture`, Reactor와 executor queues, cancel과 interrupt conversion, zero 및 near-zero time budget, fan-out, retries, AOP `proceed()` amplification, breaker OPEN admission, replay capacity, cross-request mutable context를 검사한다. cancellation counter만으로 worker termination을 추론하지 않는다. wire termination은 observed client exchange 또는 equivalent provider seam 없이는 관측됐다고 판정하지 않는다. delivery, provider attempt, worker termination, wire termination은 서로 대체할 수 없는 증거다.

### 4.3 PromptBuilder·evidence 정책 무결성

`PromptBuilder.build(PromptContext)` 이후의 messages, Web/Vector/KG/BM25 policy symmetry, rewrite의 domain/`only`/negation/privacy/time 보존, 모든 postprocessor 뒤 citation survival, system metadata의 history 재진입, UTF-8와 mojibake, redaction을 검사한다.

### 4.4 verification authority

source/test discovery/execution/assertion/runtime proof를 분리한다. source 파일 존재, test 파일 존재, Gradle sourceSet discovery, 요청 test execution, candidate assertion coverage, compiled/JAR/runtime observation은 서로 다른 증거다. `BUILD SUCCESSFUL`, `NO-SOURCE`, focused green test는 같은 성공이 아니다.

### 4.5 정량 pressure

active root에서만 large-file, cross-subsystem, aspect, catch, FQCN, test-tree, secret-count pressure를 측정한다. production files/LOC, 1,000·2,000 LOC 초과 파일, cross-subsystem LOC, aspect order-expression, broad/empty catch와 breadcrumb, raw duplicate FQCN count와 compiled/JAR mitigation status를 서로 독립적으로 기록하며, test tree와 count-only secret pattern을 포함한다. static keyword metric은 approximation이며 runtime path나 semantic defect 증거가 아니다.

## 5. 관찰·추론·반증 계약

유지되는 모든 candidate에는 다음 label과 counter-evidence 검색 결과를
반드시 모두 둔다.

```text
OBSERVATION: exact current file:line or command fact
INFERENCE: bounded consequence, never promoted to observation
COUNTER_EVIDENCE_STATUS: found | none_observed | not_checked
COUNTER_EVIDENCE: exact contrary fact when found; bounded search record when none_observed; empty only when not_checked
FALSIFIER: one narrow assertion and exact command that would reject the candidate
```

Candidate Dossier에는 다음 schema를 그대로 사용한다.

```yaml
counterEvidenceStatus: found | none_observed | not_checked
counterEvidence: exact contrary fact when found; bounded search record when none_observed; empty only when not_checked
```

- `found`: existing guard, test, fallback, 또는 inactive-path fact가 있다.
- `none_observed` remains scoreable: bounded search가 실행됐지만 contrary
  fact를 찾지 못한 상태다. invented evidence로 빈칸을 채우지 않는다.
- `not_checked` makes scoring invalid and the candidate becomes `HOLD`: 검색이
  실행되지 않았으므로 counterEvidence는 비우고 해당 candidate만 HOLD한다.

data flow는 observation 정규화 -> bounded counter-evidence search ->
`counterEvidenceStatus`와 `counterEvidence` 기록 -> `not_checked`를 scoring
전에 HOLD -> falsifier 정의 -> score 계산 순서다. 관찰과 active owner가
없거나 status가 `found`/`none_observed`가 아니면 점수는 무효다.

## 6. 점수와 우선순위

모든 범위를 지킨다.

```text
contradiction: 0..5
authority_dispersion: 0..5
severity: 1..5
likelihood: 1..5
detectability: 1..5
shock_risk = severity * likelihood * (6 - detectability)
blast_radius: 1..5
causal_strength: 1..5
patch_size: 1..5
verification_cost: 1..5
evidence_confidence: 0.00..1.00
```

`shock_risk` 범위는 1..125다. risk, confidence, patch size, verification
cost는 서로 독립 지표로 유지하고 한 지표로 다른 지표를 대체하지 않는다.
calibration endpoints는 다음과 같다.

- `contradiction=0`: 관측된 구현이 선언된 contract와 일치한다.
- `contradiction=1`: wording 또는 naming drift가 있지만 active behavior는 하나다.
- `contradiction=2`: redundant surface가 있으나 selection이 명시적이고 semantics가 동등하다.
- `contradiction=3`: active path가 다르지만 그 차이가 명시적으로 문서화됐다.
- `contradiction=4`: active path가 다르고 policy 또는 proof boundary 하나가 불일치한다.
- `contradiction=5`: 같은 사용자 contract에 상호 양립 불가능한 동작 또는 성공 주장이 있다.
- `authority_dispersion=0`: active owner 하나와 명시적 selection path 하나다.
- `authority_dispersion=1`: owner 하나와 inactive alias 또는 adapter가 있다.
- `authority_dispersion=2`: 여러 definition이 explicit qualifier, flag, 또는 endpoint policy로 선택된다.
- `authority_dispersion=3`: 여러 active owner가 있고 endpoint separation이 문서화됐다.
- `authority_dispersion=4`: `@Primary`, ordering, default, 또는 fallback이 암묵적으로 선택한다.
- `authority_dispersion=5`: 단일 authoritative contract 없이 caller 또는 configuration에 따라 selection이 달라진다.
- `severity=1`: cosmetic 또는 local degradation이다.
- `severity=5`: security, corruption, unbounded cost, 또는 broad service failure다.
- `likelihood=1`: rare conjunction이 필요하다.
- `likelihood=5`: deterministic 또는 common이다.
- `detectability=1`: silent 또는 misleading이다.
- `detectability=5`: explicit proof와 함께 일찍 실패한다.
- `blast_radius=1`: optional path 하나에 국한된다.
- `blast_radius=5`: primary chat/RAG 또는 persisted data boundary를 가로지른다.
- `causal_strength=1`: correlation만 있다.
- `causal_strength=5`: exact entry-to-effect chain이 있다.
- `patch_size=1`: narrow seam 하나다.
- `patch_size=5`: multiple owners 또는 subsystems를 가로지른다.
- `verification_cost=1`: focused static 또는 unit contract로 검증한다.
- `verification_cost=5`: runtime 또는 external evidence가 필요하다.
- `evidence_confidence 0.95–1.00`: active call/wiring path와 focused command, test, 또는 runtime observation이 함께 있다.
- `evidence_confidence 0.85–0.94`: active source와 unambiguous static call/wiring path가 있다.
- `evidence_confidence 0.70–0.84`: active-source pattern은 있으나 selection 또는 execution proof가 불완전하다.
- `evidence_confidence below 0.70`: patch candidate로 불충분하므로 HOLD 또는 evidence_needed다.

```text
P0: evidence_confidence >= 0.85 AND (shock_risk >= 80 OR (contradiction=5 AND authority_dispersion>=4 AND blast_radius>=4))
P1: evidence_confidence >= 0.80 AND NOT P0 AND (shock_risk 48..79 OR active policy/security/prompt boundary gap)
P2: evidence_confidence >= 0.70 AND NOT P0/P1 AND shock_risk 24..47 AND active owner AND bounded falsifier
P3: evidence_confidence >= 0.70 AND shock_risk 1..23; WATCH only
HOLD: below the confidence threshold for the otherwise-matching priority band, or weak evidence/owner ambiguity/moving preimage/missing product policy/unavailable proof
REJECT: inactive source, counter-evidence explains the observation, or falsifier disproves it
```

A candidate that otherwise matches a priority band but is below its confidence threshold becomes HOLD.
낮은 confidence를 더 낮은 priority로 조용히
downgrade하지 않는다. P3는 WATCH이며 기본적으로 repair directive를 만들지
않는다. priority는 patch 권한이 아니다.

## 7. Candidate Dossier

모든 retained candidate는 다음 Interface Contract schema를 빠짐없이 사용한다.

```yaml
candidateId: stable-slug
title: short statement
priority: P0 | P1 | P2 | P3 | HOLD | REJECT
activeOwner:
  entryPoint: file:line
  selectedImplementation: file:line
  terminalEffect: file:line
observation: source-backed fact
inference: bounded consequence inferred from the observation
counterEvidenceStatus: found | none_observed | not_checked
counterEvidence: exact contrary fact when found; bounded search record when none_observed; empty only when not_checked
falsifier:
  assertion: exact behavior that would reject the candidate
  command: narrow read-only or test command
scores:
  contradiction: integer
  authorityDispersion: integer
  severity: integer
  likelihood: integer
  detectability: integer
  shockRisk: integer
  blastRadius: integer
  causalStrength: integer
  patchSize: integer
  verificationCost: integer
  evidenceConfidence: decimal
improvementSeam:
  targetFiles: bounded list
  smallestBehaviorChange: prose
  preservedContracts: bounded list
decision: PATCH_DIRECTIVE | WATCH | HOLD | REJECT | NO_PATCH_NEEDED
evidenceNeeded: exact artifact and one verification command, or empty
```

## 8. preimage 재검증과 HOLD 격리

최종 ranking 전에 `AGENTS.md`, `.agents/skills/INDEX.md`, scanner prompt, 각 candidate target을 다시 확인한다. target drift는 해당 candidate만 `HOLD / snapshot_changed`로 격리하고, `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, `repositoryWideHold`를 채운다. 영향 없는 read-only work는 계속한다. sourceSet 불명확은 `HOLD / source_owner_unproven`이다.

## 9. Codex Directive

`PATCH_DIRECTIVE`마다 아래 형식만 출력한다. 이 directive는 source editing을 시작하지 않으며 implementation diff를 포함하지 않는다.

```markdown
## CODEX PATCH DIRECTIVE: CANDIDATE_ID
### Authority proof
- entry point, active owner, terminal effect
### Defect contract
- observation, inference, counterEvidenceStatus, counterEvidence, falsifier
### Allowed targets
- exact active files
### Forbidden changes
- inactive mirrors, duplicate wrappers, new orchestration frameworks, dependencies, secrets, provider substitution, unrelated formatting
### RED contract
- focused failing assertion
### Minimal repair seam
- behavioral constraint, not speculative code
### GREEN and regression ladder
- focused test, affected boundary tests, compile or packaging proof where relevant
### Rollback
- exact changed seam to revert
### Remaining evidence
- evidence_needed, or none
```

## 10. 최종 출력 형식

다음 fixed report section order를 정확히 지킨다.

```text
SUPER_TITLE
SUPER_TOKEN
EVIDENCE_SNAPSHOT
EXECUTIVE_FINDINGS
AUTHORITY_MAP
TOP_SHOCKS
CONTRADICTION_MATRIX
VERIFICATION_GAPS
QUANTITATIVE_PRESSURE
RANKED_IMPROVEMENTS
CODEX_DIRECTIVES
REJECTED_OR_DOWNGRADED_CANDIDATES
EVIDENCE_NEEDED
NO_PATCH_NEEDED
```

EXECUTIVE_FINDINGS는 observed, inferred, not observed, blocked를 별도로 쓴다.
`NO_PATCH_NEEDED`에는 boolean, rationale, `holdCount`,
`evidenceNeededCount`를 기록하고 다음 fail-closed contract를 지킨다.

```text
NO_PATCH_NEEDED=true only when holdCount=0 AND evidenceNeededCount=0 AND every candidate is REJECT or covered by a proven safeguard.
Any HOLD or non-empty EVIDENCE_NEEDED => NO_PATCH_NEEDED=false and rationale=undetermined.
```

## 11. 제한된 검증 예산

- 주 source-audit lane 하나와 판정을 바꾸는 verification lane 최대 하나만 사용한다.
- `bootRun`을 실행하지 않는다.
- full test suite를 실행하지 않는다.
- external call을 하지 않는다.
- 격리한 Gradle project/sourceSet gate는 최대 하나만 실행한다.
- focused test group은 총 3개까지이며 각각 판정을 바꿀 수 있어야 한다.
- 고유 host ID와 임시 `GRADLE_USER_HOME`, project cache, `AWX_BUILD_ROOT_DIR`를 사용한다.
- 결정적 증거, target 변경, authority 부족, 반복된 no-op가 발생하면 중단한다.

공유 cache에는 parallel Gradle 또는 boot task를 실행하지 않는다. Java 17을 사용한다.

## 12. 금지 사항과 완료 조건

`wireAttemptCoverage=not_observed`, `testExecutionAuthority=missing`, `evidence_needed: missing artifact / verify with exact command`을 필요한 위치에 그대로 기록한다. static, test, runtime, provider, wire evidence를 하나의 성공 주장으로 합치지 않는다. implicit `__reports__` writes, Mac mini tasks, patches, auto-fixes, raw secret-shaped values, inactive root로의 broad scan을 금지한다. provider attempt가 관측되지 않으면 provider/wire 성공을 주장하지 않는다. secret-shaped match는 count와 location category만 허용한다.

완료 시 `none_observed`는 bounded search record가 있을 때만 scoreable인지,
`not_checked`는 scoring invalid와 candidate-local HOLD인지 다시 확인한다. 어떤
HOLD 또는 non-empty `EVIDENCE_NEEDED`도 `NO_PATCH_NEEDED=true`와 함께
나오면 안 된다.
