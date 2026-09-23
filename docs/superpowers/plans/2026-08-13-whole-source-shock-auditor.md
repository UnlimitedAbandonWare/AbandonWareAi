# Whole-Source Shock Auditor Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the registered `demo1_orch_patch_scanner` system prompt with the approved read-only, evidence-first whole-source shock auditor and prove the generated prompt matches its registered source.

**Architecture:** Preserve the existing prompt-pack identity and manifest entry. One Markdown source prompt owns the audit contract; the existing manifest builder produces a byte-equivalent UTF-8 output because the route has no traits and merges only `system`. Verification is prompt-only and checks structure, semantic canaries, route uniqueness, output equality, redaction, and diff scope.

**Tech Stack:** UTF-8 Markdown, PowerShell 5.1+, Python 3 with PyYAML, Git read-only inspection, existing `agent-prompts/build.py`.

## Global Constraints

- Work only in `C:\AbandonWare\demo-1\demo-1\src`.
- Modify only `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md` during
  the original implementation. The approved final-review correction may also
  update this plan, its approved design, and its scoped SDD report, but no route
  catalog, builder, application, test, Gradle, script, skill, or PatchDrop file.
- Preserve `canonicalId=demo1_orch_patch_scanner` and `agent-prompts/out/demo1_orch_patch_scanner.prompt`.
- Read but do not edit the already-dirty `agent-prompts/prompts.manifest.yaml` because its route is valid and unique.
- Do not modify application Java, resources, tests, Gradle files, scripts, skills, PatchDrop, or existing reports.
- Do not stage, commit, push, branch, deploy, or clean unrelated changes without separate operation-level authority.
- Do not call providers, databases, Supabase, Browser, production, or start runtime processes.
- Do not run Gradle for this prompt-only change.
- Do not print raw prompts from private sessions, raw queries, provider responses, credentials, authorization headers, cookies, environment dumps, or complete error bodies.
- Keep human-facing instructions and report prose in Korean; keep repository identifiers, commands, reason codes, and schema keys exact.
- Active roots default to `main/java`, `main/resources`, `src/test/java`, `app/src/main/java_clean`, `app/src/main/resources` and must be reconfirmed by a later audit run.
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives, backups, and generated outputs remain inactive/reference unless current Gradle evidence selects them.
- The future scanner is read-only: it emits repair directives and RED/GREEN contracts, never code diffs or source edits.
- If the source prompt preimage changes from `705233DFC1D594FE6C972FDCCAA1670268648AC2C2F6A3E73BC77B0EDE62CD17` before the edit, return `HOLD / snapshot_changed` for the prompt lane with `repositoryWideHold=false` and preserve independent evidence.

---

## File Map

- Reference: `docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md` — approved design authority.
- Modify: `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md` — sole authored system prompt.
- Read: `agent-prompts/prompts.manifest.yaml` — registered route and output contract; no edits.
- Read: `agent-prompts/build.py` — existing deterministic prompt builder.
- Generate for verification: `agent-prompts/out/demo1_orch_patch_scanner.prompt` — build output; do not hand-edit.
- Create in this planning phase only: `docs/superpowers/plans/2026-08-13-whole-source-shock-auditor.md` — this plan.

## Interface Contract

The authored prompt consumes a live repository checkout and produces one Korean report with these fixed top-level sections:

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

Every retained candidate produces this logical record:

```yaml
candidateId: stable-slug
priority: P0 | P1 | P2 | P3 | HOLD | REJECT
activeOwner:
  entryPoint: file:line
  selectedImplementation: file:line
  terminalEffect: file:line
observation: source-backed fact
inference: bounded consequence
counterEvidenceStatus: found | none_observed | not_checked
counterEvidence: exact contrary fact when found; bounded search record when none_observed; empty only when not_checked
falsifier:
  assertion: exact rejecting behavior
  command: one narrow command
scores:
  contradiction: 0..5
  authorityDispersion: 0..5
  severity: 1..5
  likelihood: 1..5
  detectability: 1..5
  shockRisk: severity * likelihood * (6 - detectability)
  blastRadius: 1..5
  causalStrength: 1..5
  patchSize: 1..5
  verificationCost: 1..5
  evidenceConfidence: 0.00..1.00
decision: PATCH_DIRECTIVE | WATCH | HOLD | REJECT | NO_PATCH_NEEDED
evidenceNeeded: exact artifact and one verification command, or none
```

The counter-evidence search result is explicit: `found` means a guard, test,
fallback, or inactive-path fact exists; `none_observed` means a bounded search
ran without finding a contrary fact and remains scoreable without invented
evidence; `not_checked` means the search did not run, invalidates scoring, and
makes the candidate `HOLD`.

Use these calibration endpoints and confidence bands without deriving one
dimension from another:

```text
contradiction=0: observed implementation matches the declared contract
contradiction=1: wording or naming drift with one active behavior
contradiction=2: redundant surface with explicit selection and equivalent semantics
contradiction=3: active paths differ but the difference is explicitly documented
contradiction=4: active paths differ and one policy or proof boundary is inconsistent
contradiction=5: mutually incompatible behavior or success claims exist on the same user contract
authority_dispersion=0: one active owner and one explicit selection path
authority_dispersion=1: one owner plus inactive alias or adapter
authority_dispersion=2: multiple definitions with explicit qualifier, flag, or endpoint policy
authority_dispersion=3: multiple active owners with documented endpoint separation
authority_dispersion=4: implicit selection through @Primary, ordering, defaults, or fallback
authority_dispersion=5: selection changes by caller or configuration without a single authoritative contract
severity=1: cosmetic or local degradation
severity=5: security, corruption, unbounded cost, or broad service failure
likelihood=1: requires a rare conjunction
likelihood=5: deterministic or common
detectability=1: silent or misleading
detectability=5: fails early with explicit proof
blast_radius=1: one optional path
blast_radius=5: crosses primary chat/RAG or persisted data boundaries
causal_strength=1: correlation
causal_strength=5: exact entry-to-effect chain
patch_size=1: one narrow seam
patch_size=5: crosses multiple owners or subsystems
verification_cost=1: focused static or unit contract
verification_cost=5: requires runtime or external evidence
evidence_confidence 0.95–1.00: active call/wiring path plus focused command, test, or runtime observation
evidence_confidence 0.85–0.94: active source and unambiguous static call/wiring path
evidence_confidence 0.70–0.84: active-source pattern with incomplete selection or execution proof
evidence_confidence below 0.70: insufficient for a patch candidate; HOLD or evidence_needed
```

Priority classification is confidence-first and non-overlapping:

```text
P0: evidence_confidence >= 0.85 AND (shock_risk >= 80 OR (contradiction=5 AND authority_dispersion>=4 AND blast_radius>=4))
P1: evidence_confidence >= 0.80 AND NOT P0 AND (shock_risk 48..79 OR active policy/security/prompt boundary gap)
P2: evidence_confidence >= 0.70 AND NOT P0/P1 AND shock_risk 24..47 AND active owner AND bounded falsifier
P3: evidence_confidence >= 0.70 AND shock_risk 1..23; WATCH only
HOLD: below the confidence threshold for the otherwise-matching priority band, or weak evidence/owner ambiguity/moving preimage/missing product policy/unavailable proof
```

A candidate below the confidence threshold for its otherwise-matching band
becomes `HOLD`; it is not silently downgraded.

---

### Task 1: Replace the Registered Scanner with the Approved Audit Contract

**Files:**

- Modify: `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md`
- Reference: `docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md`

**Interfaces:**

- Consumes: the approved design, active repository instructions, and the current scanner preimage.
- Produces: one UTF-8 Korean system prompt with the fixed report schema and candidate dossier contract above.

- [ ] **Step 1: Run the structural RED contract against the current prompt**

Run:

```powershell
$env:PYTHONUTF8 = '1'
@'
from pathlib import Path

path = Path('agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md')
text = path.read_text(encoding='utf-8')

required = [
    'EVIDENCE_SNAPSHOT',
    'AUTHORITY_MAP',
    'TOP_SHOCKS',
    'CONTRADICTION_MATRIX',
    'VERIFICATION_GAPS',
    'QUANTITATIVE_PRESSURE',
    'RANKED_IMPROVEMENTS',
    'CODEX_DIRECTIVES',
    'REJECTED_OR_DOWNGRADED_CANDIDATES',
    'NO_PATCH_NEEDED',
    'authority_dispersion',
    'shock_risk',
    'COUNTER_EVIDENCE',
    'FALSIFIER',
    'wireAttemptCoverage=not_observed',
    'testExecutionAuthority=missing',
]
forbidden = [
    'Mac mini PatchDrop Task',
    '__reports__/orch-scan-',
]
missing = [token for token in required if token not in text]
present_forbidden = [token for token in forbidden if token in text]
assert not missing and not present_forbidden, {
    'missing': missing,
    'forbidden': present_forbidden,
}
print('STRUCTURE_CONTRACT_OK')
'@ | python -X utf8 -
```

Expected before implementation: FAIL with an `AssertionError` that lists missing evidence-contract tokens and the legacy Mac mini/report-write tokens. This establishes that the old prompt does not satisfy the approved design.

- [ ] **Step 2: Recheck the exact prompt preimage and lane safety**

Run:

```powershell
$prompt = 'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md'
$expected = '705233DFC1D594FE6C972FDCCAA1670268648AC2C2F6A3E73BC77B0EDE62CD17'
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $prompt).Hash
$indexLock = Test-Path '.git\index.lock'
$status = git status --short -- $prompt

[pscustomobject]@{
    preimageMatch = ($actual -eq $expected)
    indexLockPresent = [bool]$indexLock
    targetStatus = @($status)
}
```

Expected: `preimageMatch=True`, `indexLockPresent=False`, and no pre-existing target status. If the preimage differs, do not overwrite it; report:

```text
decision: HOLD
holdScope: prompt-lane
firstBlockingRule: snapshot_changed
blockingEvidence: scanner prompt SHA-256 differs from approved preimage
independentWorkCompleted: plan and read-only audit evidence preserved
repositoryWideHold: false
```

- [ ] **Step 3: Replace the prompt with the exact approved section structure**

Use `apply_patch` to replace the old scanner body. The new prompt must contain these sections in this order and implement the stated behavior literally:

```markdown
# demo1 전 소스 권위·모순·쇼크 감사기

## 0. 역할, 출력 언어, 절대 경계
## 1. EvidenceSnapshot 고정
## 2. active sourceSet과 실제 settings 권위 확정
## 3. 권위 지도 작성
## 4. 독립 감사 축
### 4.1 권위 분산과 설계 모순
### 4.2 동시성·취소·timeout 쇼크
### 4.3 PromptBuilder·evidence 정책 무결성
### 4.4 verification authority
### 4.5 정량 pressure
## 5. 관찰·추론·반증 계약
## 6. 점수와 우선순위
## 7. Candidate Dossier
## 8. preimage 재검증과 HOLD 격리
## 9. Codex Directive
## 10. 최종 출력 형식
## 11. 제한된 검증 예산
## 12. 금지 사항과 완료 조건
```

Section 0 must say:

```text
- 이 프롬프트는 Desktop Codex의 읽기 전용 감사 프롬프트다.
- 소스, 테스트, 설정, 프롬프트, 보고서, PatchDrop을 수정하지 않는다.
- stage, commit, push, branch, deploy를 하지 않는다.
- provider, DB, Supabase, Browser, production을 호출하지 않는다.
- raw query, prompt, response, credential, environment dump를 출력하지 않는다.
- 결과는 Codex용 수리 지시서와 RED/GREEN 계약까지이며 diff나 패치를 만들지 않는다.
```

Section 1 must freeze the exact `EvidenceSnapshot` fields defined in the Interface Contract plus `capturedAt`, `canonicalRoot`, `branch`, `head`, `agentsHash`, `routingIndexHash`, `scannerPromptHash`, `settingsAuthority`, `javaMajor`, `dirtyCounts`, `indexLockPresent`, `topLevelPatchCount`, and `activeLeaseCount`. It must prohibit raw dirty-file lists and raw share mappings.

Section 2 must require live Gradle/sourceSet proof before treating an implementation as active and must classify unselected roots as inactive/reference. A class name or file presence alone never proves ownership.

Section 3 must trace:

```text
UI/API -> controller -> service/workflow -> PromptBuilder -> retrieval/rerank
-> provider/model caller -> stream/response -> persistence/restore
```

For every role it records active sourceSet, bean name, `@Primary`, `@Qualifier`, conditions, feature flags, property prefix/default, endpoint selection, aliases, and exact `file:line` evidence.

Section 4 must independently inspect:

```text
endpoint split
bean split
prompt split
policy split
lifecycle split
verification split
persistence split
provider-attempt evidence split
Future and CompletableFuture
Reactor and executor queues
cancel and interrupt conversion
zero and near-zero time budget
fan-out, retries, AOP proceed amplification
breaker OPEN admission
replay capacity
cross-request mutable context
PromptBuilder post-build messages
Web/Vector/KG/BM25 policy symmetry
rewrite preservation of domain, only, negation, privacy, time
citation survival after every postprocessor
history re-entry of system metadata
UTF-8 and mojibake
redaction
source/test discovery/execution/assertion/runtime proof separation
large-file, cross-subsystem, aspect, catch, FQCN, test-tree, secret-count pressure
```

Section 5 must require these labelled fields and the explicit search-result
schema for every retained candidate:

```text
OBSERVATION: exact current file:line or command fact
INFERENCE: bounded consequence, never promoted to observation
COUNTER_EVIDENCE_STATUS: found | none_observed | not_checked
COUNTER_EVIDENCE: exact contrary fact when found; bounded search record when none_observed; empty only when not_checked
FALSIFIER: one narrow assertion and exact command that would reject the candidate
```

`none_observed` remains scoreable only after a bounded search and must never be
filled with invented evidence. `not_checked` invalidates scoring and makes the
candidate `HOLD`.

Section 6 must define all numeric ranges and this exact formula:

```text
shock_risk = severity * likelihood * (6 - detectability)
```

It must keep risk, confidence, patch size, and verification cost independent.
Priority rules are confidence-first and exact:

```text
P0: evidence_confidence >= 0.85 AND (shock_risk >= 80 OR (contradiction=5 AND authority_dispersion>=4 AND blast_radius>=4))
P1: evidence_confidence >= 0.80 AND NOT P0 AND (shock_risk 48..79 OR active policy/security/prompt boundary gap)
P2: evidence_confidence >= 0.70 AND NOT P0/P1 AND shock_risk 24..47 AND active owner AND bounded falsifier
P3: evidence_confidence >= 0.70 AND shock_risk 1..23; WATCH only
HOLD: below the confidence threshold for the otherwise-matching priority band, or weak evidence/owner ambiguity/moving preimage/missing product policy/unavailable proof
REJECT: inactive source, counter-evidence explains the observation, or falsifier disproves it
```

Section 6 must also reproduce the contradiction, authority-dispersion,
evidence-confidence, and remaining metric endpoint calibration from the
Interface Contract. A candidate below the confidence threshold for its
otherwise-matching band becomes `HOLD`; it is never silently downgraded.

Section 7 must reproduce the Candidate Dossier schema from the Interface Contract without dropping fields.

Section 8 must recheck `AGENTS.md`, `.agents/skills/INDEX.md`, the scanner prompt, and each candidate target. It must isolate target drift to `HOLD / snapshot_changed`, populate `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, and `repositoryWideHold`, and continue unaffected work.

Section 9 must emit `CODEX PATCH DIRECTIVE: CANDIDATE_ID` with Authority proof,
Defect contract, Allowed targets, Forbidden changes, RED contract, Minimal
repair seam, GREEN/regression ladder, Rollback, and Remaining evidence. The
Defect contract must include observation, inference, `counterEvidenceStatus`,
the truthful `counterEvidence` payload, and falsifier. It must state that the
directive does not start source editing.

Section 10 must emit the fixed report section order from the Interface Contract
and include `holdCount` and `evidenceNeededCount` with this exact contract:

```text
NO_PATCH_NEEDED=true only when holdCount=0 AND evidenceNeededCount=0 AND every candidate is REJECT or covered by a proven safeguard.
Any HOLD or non-empty EVIDENCE_NEEDED => NO_PATCH_NEEDED=false and rationale=undetermined.
```

Section 11 must enforce:

```text
- one primary source-audit lane and at most one decision-changing verification lane
- no bootRun
- no full test suite
- no external call
- at most one isolated Gradle project/sourceSet gate
- at most three focused test groups total, each verdict-changing
- unique host ID and temporary GRADLE_USER_HOME, project cache, and AWX_BUILD_ROOT_DIR
- stop on decisive proof, target change, missing authority, or repeated no-op
```

Section 12 must include:

```text
wireAttemptCoverage=not_observed
testExecutionAuthority=missing
evidence_needed: missing artifact / verify with exact command
```

It must prohibit success claims that collapse static, test, runtime, provider, and wire evidence. It must prohibit implicit `__reports__` writes, Mac mini tasks, patches, auto-fixes, raw secret-shaped values, and broad scans into inactive roots.

- [ ] **Step 4: Run the structural contract again and require GREEN**

Run the same Python contract from Step 1.

Expected after implementation:

```text
STRUCTURE_CONTRACT_OK
```

Its appearance means the previously failing structural contract is green.

- [ ] **Step 5: Inspect the authored prompt diff without staging or committing**

Run:

```powershell
git diff --check -- agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md
git diff --stat -- agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md
git diff -- agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md
```

Expected:

- `git diff --check` exits 0;
- exactly one authored prompt is changed by this task;
- no application source or manifest hunk appears;
- the old Mac mini directive and implicit report write are removed.

Checkpoint: record the new prompt SHA-256 and diff summary. Do not stage or commit.

---

### Task 2: Build and Verify the Registered Prompt Artifact

**Files:**

- Read: `agent-prompts/prompts.manifest.yaml`
- Read: `agent-prompts/build.py`
- Read: `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md`
- Generate: `agent-prompts/out/demo1_orch_patch_scanner.prompt`

**Interfaces:**

- Consumes: the authored UTF-8 system prompt and the unique manifest route.
- Produces: a generated prompt whose text and SHA-256 match the source prompt, plus bounded verification results.

- [ ] **Step 1: Verify manifest uniqueness and unchanged route fields**

Run:

```powershell
$env:PYTHONUTF8 = '1'
@'
from pathlib import Path
import yaml

manifest_path = Path('agent-prompts/prompts.manifest.yaml')
manifest = yaml.safe_load(manifest_path.read_text(encoding='utf-8'))
matches = [a for a in manifest.get('agents', []) if a.get('id') == 'demo1_orch_patch_scanner']
assert len(matches) == 1, {'matchingRouteCount': len(matches)}
agent = matches[0]
assert agent['system'] == 'agents/demo1_orch_patch_scanner/system_ko.md', agent
assert agent.get('traits') == [], agent
assert agent.get('merge', {}).get('order') == ['system'], agent
assert agent['output']['path'] == 'out/demo1_orch_patch_scanner.prompt', agent
assert agent['output']['encoding'].lower() == 'utf-8', agent
print('MANIFEST_ROUTE_OK count=1 merge=system encoding=utf-8')
'@ | python -X utf8 -
```

Expected:

```text
MANIFEST_ROUTE_OK count=1 merge=system encoding=utf-8
```

- [ ] **Step 2: Build the registered prompt with the existing builder**

Run:

```powershell
python -X utf8 agent-prompts\build.py `
  --manifest agent-prompts\prompts.manifest.yaml `
  --agent demo1_orch_patch_scanner
```

Expected:

```text
Wrote agent-prompts\out\demo1_orch_patch_scanner.prompt
```

- [ ] **Step 3: Prove source/output equality and UTF-8 readability**

Run:

```powershell
$env:PYTHONUTF8 = '1'
@'
from hashlib import sha256
from pathlib import Path

source = Path('agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md').read_bytes()
output = Path('agent-prompts/out/demo1_orch_patch_scanner.prompt').read_bytes()
source.decode('utf-8')
output.decode('utf-8')
assert source == output, {
    'sourceSha256': sha256(source).hexdigest(),
    'outputSha256': sha256(output).hexdigest(),
}
print('PROMPT_OUTPUT_EQUAL sha256=' + sha256(source).hexdigest().upper())
'@ | python -X utf8 -
```

Expected: `PROMPT_OUTPUT_EQUAL` followed by one SHA-256 shared by source and output.

- [ ] **Step 4: Run semantic canaries for the six required defect classes**

Run:

```powershell
$env:PYTHONUTF8 = '1'
@'
from pathlib import Path

text = Path('agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md').read_text(encoding='utf-8')
canaries = {
    'endpointOwnerSplit': ['endpoint split', '@Primary', '@Qualifier'],
    'cancelWithoutWireProof': ['cancel', 'worker', 'wireAttemptCoverage=not_observed'],
    'testNoSource': ['NO-SOURCE', 'testExecutionAuthority=missing'],
    'duplicateFqcnMitigation': ['FQCN', 'JAR', 'mitigation'],
    'providerAttemptGap': ['provider-attempt', 'wire'],
    'evidencePolicyAsymmetry': ['Web', 'Vector', 'policy'],
}
missing = {
    name: [token for token in tokens if token not in text]
    for name, tokens in canaries.items()
}
missing = {name: tokens for name, tokens in missing.items() if tokens}
assert not missing, missing
print('SEMANTIC_CANARIES_OK count=6')
'@ | python -X utf8 -
```

Expected:

```text
SEMANTIC_CANARIES_OK count=6
```

These checks prove the prompt can express the classes. They do not claim that a future checkout contains each defect.

- [ ] **Step 5: Run placeholder, forbidden-language, and count-only secret checks**

Run:

```powershell
$env:PYTHONUTF8 = '1'
@'
from pathlib import Path
import re

paths = [
    Path('agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md'),
    Path('docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md'),
    Path('docs/superpowers/plans/2026-08-13-whole-source-shock-auditor.md'),
]
texts = {str(path): path.read_text(encoding='utf-8') for path in paths}
prompt_text = texts[str(paths[0])]

placeholder_words = ['T' + 'BD', 'T' + 'ODO', 'FIX' + 'ME', 'X' + 'XX']
placeholder_pattern = re.compile(
    r'\b(?:' + '|'.join(placeholder_words) + r')\b|\$\{[^}]+\}'
)
forbidden = [
    'Mac mini PatchDrop Task',
    '__reports__/orch-scan-',
]
secret_pattern = re.compile(
    r'(?i)(?:api[_-]?key|client[_-]?secret|authorization|owner[_-]?token)'
    r'\s*[:=]\s*["\']?[A-Za-z0-9_./+\-=]{16,}'
)

placeholder_counts = {name: len(placeholder_pattern.findall(text)) for name, text in texts.items()}
forbidden_counts = {
    token: prompt_text.count(token)
    for token in forbidden
}
secret_counts = {name: len(secret_pattern.findall(text)) for name, text in texts.items()}

assert sum(placeholder_counts.values()) == 0, placeholder_counts
assert sum(forbidden_counts.values()) == 0, forbidden_counts
assert sum(secret_counts.values()) == 0, secret_counts
print('TEXT_GATES_OK placeholders=0 forbidden=0 secretPatternHits=0')
'@ | python -X utf8 -
```

Expected:

```text
TEXT_GATES_OK placeholders=0 forbidden=0 secretPatternHits=0
```

Only counts are printed; never print a matching value.

- [ ] **Step 6: Verify final scope and preserve unrelated work**

Run:

```powershell
git diff --check -- agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md
git status --short -- `
  agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md `
  agent-prompts/out/demo1_orch_patch_scanner.prompt `
  agent-prompts/prompts.manifest.yaml `
  docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md `
  docs/superpowers/plans/2026-08-13-whole-source-shock-auditor.md

Get-FileHash -Algorithm SHA256 -LiteralPath `
  'agent-prompts\agents\demo1_orch_patch_scanner\system_ko.md', `
  'agent-prompts\out\demo1_orch_patch_scanner.prompt', `
  'docs\superpowers\specs\2026-08-13-whole-source-shock-auditor-design.md', `
  'docs\superpowers\plans\2026-08-13-whole-source-shock-auditor.md' |
  Select-Object Path, Hash
```

Expected:

- prompt source and generated output share one SHA-256;
- manifest remains an unrelated pre-existing modification and has no task-authored diff;
- application source remains untouched by this implementation;
- spec and plan are present as approved process artifacts;
- no staging or commit occurs.

Checkpoint: report exact paths, hashes, command results, and any remaining `evidence_needed`. Do not describe prompt-only success as Java build, runtime, provider, or wire success.

---

## Plan Completion Conditions

The plan is complete only when all of the following are freshly proven:

1. The authored prompt contains the approved authority, contradiction, shock, prompt-integrity, verification-authority, and quantitative-pressure lanes.
2. Every retained candidate requires `OBSERVATION`, `INFERENCE`,
   `counterEvidenceStatus`, its truthful `COUNTER_EVIDENCE` payload,
   `FALSIFIER`, active-owner proof, and all numeric fields; `none_observed`
   remains scoreable and `not_checked` is invalid and becomes `HOLD`.
3. Risk, confidence, patch size, and verification cost remain independent.
4. `NO-SOURCE` cannot count as executed coverage.
5. Cancel, timeout, delivery, provider attempt, worker termination, and wire termination are separate claims.
6. The output is a Codex directive, not a Mac mini task, report write, source diff, or auto-patch.
7. The manifest route is unique and unchanged.
8. The generated UTF-8 prompt equals the authored source byte-for-byte.
9. Structure, semantic canary, placeholder, forbidden-language, and count-only secret checks pass.
10. `NO_PATCH_NEEDED=true` requires zero HOLDs and zero evidence-needed items;
    any HOLD or non-empty `EVIDENCE_NEEDED` forces false with
    `rationale=undetermined`.
11. The final task-authored scope contains only the approved prompt plus the approved spec and this plan; unrelated dirty work remains preserved.

## Spec Coverage Map

| Approved spec unit | Implementing plan step | Verification |
| --- | --- | --- |
| Purpose, Current Problem, Chosen Approach | Task 1 Steps 1–3 | structural RED then GREEN |
| Authority Boundary | Task 1 Step 3, Section 0 | forbidden-language gate |
| Evidence Snapshot | Task 1 Step 3, Sections 1 and 8 | required field inspection |
| Authority Mapper | Task 1 Step 3, Sections 2 and 3 | authority schema tokens |
| Contradiction Analyzer | Task 1 Step 3, Section 4.1 | endpoint/bean/prompt/policy/lifecycle canaries |
| Shock Analyzer | Task 1 Step 3, Section 4.2 | cancel/worker/wire and budget tokens |
| Prompt and Evidence Integrity Analyzer | Task 1 Step 3, Section 4.3 | Web/Vector policy canary |
| Verification Authority Analyzer | Task 1 Step 3, Section 4.4 | `NO-SOURCE` canary |
| Quantitative Pressure Analyzer | Task 1 Step 3, Section 4.5 | FQCN/JAR mitigation canary |
| Measurement Model and Priority | Task 1 Step 3, Section 6 | structural formula and P0–P3 tokens |
| Candidate Dossier | Interface Contract and Task 1 Section 7 | schema-field completeness check |
| Data Flow | Task 1 Sections 1–10 | ordered section and report checks |
| Codex Directive | Task 1 Step 3, Section 9 | `CODEX_DIRECTIVES` structural check |
| Final Report Shape | Interface Contract and Task 1 Section 10 | fixed output-section check |
| Error and HOLD Handling | Task 1 Step 3, Sections 8 and 12 | HOLD field and reason-code checks |
| Verification Budget | Task 1 Step 3, Section 11 | no boot/full/external and bounded-test inspection |
| Implementation Scope | File Map and Global Constraints | Git scope check |
| Prompt-Only Verification | Task 2 Steps 1–6 | manifest, build, equality, canaries, text gates |
| Acceptance Criteria | Plan Completion Conditions | final requirement-by-requirement audit |

## Execution Authority Note

This plan intentionally omits `git add` and `git commit` steps. The repository requires separate operation-level authority for staging and committing, and the user approved the design and scoped prompt change only.
