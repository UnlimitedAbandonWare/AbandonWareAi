# demo-1 Quant Harmony 9h Safe Patch Directive

Generated: 2026-06-13 Asia/Seoul  
Updated: 2026-06-20 Asia/Seoul  
Workspace: `C:\AbandonWare\demo-1\demo-1\src`

이 문서는 첨부된 Dynamic RAG HR/포트폴리오 설계를 `claim_map`으로 정량화하고, live source 증거로 잘못된 설계 조합을 교정한 뒤, Codex / Antigravity / Claude 계열 에이전트가 최대 9시간의 작업 예산 안에서 패치 가치가 증명되는 사이클만 안전하게 진행하도록 하는 지시서다.

사용한 판단 프레임:

- `superpowers:using-superpowers`
- `superpowers:brainstorming`
- `superpowers:writing-plans`
- `quantitative-metric-normalizer`
- `demo1-ablation-harmony-tracker`
- `demo1-autonomous-patch-conductor`
- `demo1-rag-platform`
- `supabase:supabase`
- `computer-use:computer-use`

첨부 설계는 사실이 아니라 주장 목록이다. 실제 repo 파일과 현재 명령 출력이 항상 우선한다.

---

## 0. 현재 Desktop 증거 요약

이 baseline은 지시서 생성 시점의 supporting evidence다. 실제 패치 세션은 반드시 다시 실행한다.

```text
root: C:\AbandonWare\demo-1\demo-1\src
git metadata: unavailable in current cwd; git rev-parse/status/worktree returned "not a git repository"
index.lock: absent by local path check
PatchDrop top-level pending .patch: none observed
active source evidence: build.gradle.kts sourceSets prove main/java and main/resources
:app source evidence: app/src/main/java_clean and app/src/main/resources
main_java_files: 1830
app_java_clean_files: 11
main_resource_files: 172
app_resource_files: 67
yaml_scan: checked=100 failures=0
activeSecretPatternHits: 0
sourceScoreReport: Total Score 100 / 100
checkLangchain4jVersionPurity: PASS
checkSourceSetHygiene: PASS
duplicateFqcn: 0
packagePathMismatch: 0
largeActiveSourceFiles: 8
broadCatches: 2691
exactEmptyCatchMatches: 1
focused harmony tests: PASS
PatchDrop janitor CoreGuards: PASS via -ExecutionPolicy Bypass
Supabase lane: CLI unavailable in this Desktop proof; MCP/project_ref/auth evidence_needed
Computer Use lane: no Windows GUI proof required for source/prompt patching
```

Focused harmony tests that passed in this baseline:

```powershell
.\gradlew.bat test --no-daemon --project-cache-dir $pcd `
  --tests "com.example.lms.orchestration.StrategyConflictResolverTest" `
  --tests "com.example.lms.service.rag.burst.ExtremeZTriggerTest" `
  --tests "com.nova.protocol.fusion.NovaNextFusionServiceTest" `
  --tests "com.example.lms.strategy.RetrievalOrderServiceTest" `
  --tests "com.example.lms.telemetry.MlaBreadcrumbTest" `
  --tests "com.example.lms.moe.RgbStrategySelectorTraceTest" `
  --tests "com.example.lms.cfvm.RawMatrixBufferTest"
```

PatchDrop janitor proof:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
```

Observed required probes:

```text
MISSING_META: PASS
filemode-blocked: PASS
SHA_MISMATCH: PASS
real PatchDrop untouched: PASS
```

---

## 1. Authority Order

1. Current Desktop source and real command output.
2. Uploaded attachment, ZIP, log, or PatchDrop evidence for the current task.
3. Root `AGENTS.md`, `.agents/skills`, and `agent-prompts`.
4. Official vendor documentation for external API contracts.
5. Memory, old prompt packs, and older chat text.

If evidence conflicts, follow live source and record the conflict. If evidence is missing, write:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

---

## 2. Non-Negotiables

- Safe Patch only: patch the smallest confirmed blocker.
- Do not patch inactive mirrors just because they contain similar files.
- Default active roots are `main/java`, `main/resources`, `src/test/java`, `src/test/resources`, `app/src/main/java_clean`, and `app/src/main/resources`.
- Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives, generated output, and PatchDrop rollback copies as reference unless Gradle proves otherwise.
- Keep every `dev.langchain4j:*` dependency exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)` when that boundary exists.
- Do not edit `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, real secret setup, `openssl`, or `opnessl`.
- Do not print raw API keys, client secrets, owner tokens, Authorization headers, cookies, raw prompts, raw sensitive queries, or full environment dumps.
- Optional providers with missing, placeholder, or malformed credentials must disable or fail soft with a redacted reason and no outbound call.
- Do not fabricate build, boot, provider, browser, score, trace, or credential success.
- Do not add duplicate providers, wrappers, routes, public APIs, or architecture folders.
- Do not turn a repo-owned `100 / 100` score into a whole-design verdict. Pair it with source-backed structural and runtime evidence.
- Supabase is read-only evidence unless the user explicitly authorizes a concrete project, schema mutation, and verification path. New/exposed API tables must pair explicit `GRANT` decisions with RLS/policies; never put `service_role` or secret keys in public clients.
- Computer Use is for explicit Windows GUI proof only. Do not use Windows UI automation for terminal/source edits, do not automate PowerShell or command prompts, and require action-time confirmation for uploads, submissions, permission changes, installs, deletes, or other external side effects.

---

## 3. Decomposition Decision

Default mode for this directive:

```md
Decomposition decision:
- mode: 3-way
- reason: the attachment spans multiple Dynamic RAG subsystems, metric claims, and runtime interactions
- axes:
  1. Claim axis: what the attachment says exists or improves
  2. Source axis: which active FQCN, caller, bean, sourceSet, or verifier proves it
  3. Failure axis: which design combination can break, starve, leak, or silently degrade
```

Use direct mode only for one confirmed blocker, such as a duplicate YAML key, one missing TraceStore key, one failing focused test, or one PatchDrop janitor probe.

---

## 4. Quantified Baseline

Do not collapse these scores into one truth.

```text
repo_owned_source_score: 100 / 100
source_backed_design_readiness: 83 / 100
confidence_weighted_proof_score: 73 / 100
highest_risk: maintainability concentration + metric integrity + provider/runtime smoke gaps
hard_gate_state: PASS except git metadata evidence_needed in this copied/non-git root
```

Normalization ledger:

| Area | Weight | Raw evidence | Normalized | Confidence | Weighted proof |
|---|---:|---|---:|---:|---:|
| SourceSet/build truth | 0.20 | sourceScoreReport 100/100, sourceSet hygiene PASS, compileJava UP-TO-DATE | 1.00 | 0.95 | 0.190 |
| Runtime wiring/data contract | 0.20 | harmony owners and focused tests PASS | 0.90 | 0.90 | 0.162 |
| Prompt/search/provider safety | 0.15 | PromptBuilder/TraceStore anchors exist, secret hits 0; provider smoke not proven | 0.80 | 0.80 | 0.096 |
| Failure handling/observability | 0.15 | required harmony trace keys exist and tests pass; broad catches remain 2691 | 0.82 | 0.85 | 0.105 |
| Metric integrity | 0.15 | live score/YAML/secret metrics current; attachment improvement claims lack units/verifiers | 0.72 | 0.80 | 0.086 |
| Scope/maintainability | 0.15 | 8 active files over 2000 lines, large active methods 986, broad catches 2691 | 0.65 | 0.90 | 0.088 |

Formula:

```text
source_backed_design_readiness = 100 * sum(weight * normalized)
confidence_weighted_proof_score = 100 * sum(weight * normalized * confidence)
```

Interpretation:

- `100 / 100` means the repo-owned guard checklist is currently green.
- `83 / 100` means the design is largely implemented but still contains proof gaps and maintainability risk.
- `73 / 100` means an autonomous patch agent should continue only through evidence-producing, test-backed passes.

---

## 5. Claim Ledger

| ClaimId | Attachment claim | Live owner evidence | Verdict | Design correction |
|---|---|---|---|---|
| S01 | Anchor-Based Context Compression / Overdrive condenses sparse evidence | `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`, `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java` | PROVEN_ACTIVE | Do not add another compressor. Protect trigger authority and fallback trace. |
| S02 | Failure Pattern Analysis / CFVM records raw failure patterns and Boltzmann state | `RawMatrixBuffer`, `RawSlotExtractor`, `CfvmFailureRecorder`; `cfvm.boltzmannTemp` tests pass | PROVEN_ACTIVE | Patch only redaction/quarantine or lifecycle gaps proven by test. |
| S03 | MoE Strategy Selector chooses and evolves plates | `RgbStrategySelector`, `ArtPlateEvolver`, `RetrievalOrderService`; `moe.evolverPlateRegistered` test passes | PROVEN_ACTIVE | Keep `retrievalOrder.lastSetBy` as authority record. Do not let MoE and Plan DSL both silently own order. |
| S04 | Matryoshka slicing reduces embedding dimensions with low loss | `MatryoshkaEmbeddingNormalizer`, `MatryoshkaEmbeddingModelPostProcessor` | PARTIAL_ACTIVE | Require vector dimension/schema smoke before any new dimension claim. |
| S05 | ExtremeZ expands queries massively under hard cases | `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`; cancel/time budget tests pass | PROVEN_ACTIVE | Do not widen fan-out before proving CancelShield and TimeBudget remain green. |
| S06 | HYPERNOVA uses TWPM/CVaR/Risk-K/DPP style high-risk fusion | `NovaNextFusionService`, `CvarAggregator`, `TailWeightedPowerMeanFuser`; HYPERNOVA trace tests pass | PROVEN_ACTIVE | Normalize ranks/probabilities/scores before fusion claims. |
| S07 | CIH-RAG / MLA breadcrumb improves observability | `MlaBreadcrumb`; `cihRag.breadcrumb.queryRedacted=true` test passes | PROVEN_ACTIVE | Breadcrumbs must stay redacted and low-cardinality. |
| S08 | PromptBuilder and safety gates protect final output | `PromptBuilder`, `CitationGate`, `PIISanitizer`, `OpenAiResponsesChatModel` | PROVEN_ACTIVE | Patch only active bypasses. Never inject raw evidence directly into final prompt strings. |
| S09 | Brave/RuleBreak/FullScale expand search under explicit policy | Plan YAMLs and search providers exist | PARTIAL_ACTIVE | Require user-visible mode reason, bounded relaxation, provider-disabled reason, and no fake results. |
| S10 | AutoLearn/UAW creates verified training data | AutoLearn paths exist, but runtime data freshness is not proven here | EVIDENCE_NEEDED | Use `train_rag.jsonl` as raw truth; quarantine vector rows until verifier promotes them. |
| J01 | PatchDrop janitor needs MISSING_META, filemode-blocked, SHA mismatch probes | `__patch_drop__/janitor_tests.ps1 -Suite CoreGuards` PASS with all three probes | PROVEN_ACTIVE | Do not rewrite janitor tests. Preserve and extend only when a new regression appears. |
| E01 | Supabase can provide DB/vector/schema evidence | Supabase CLI unavailable in Desktop session; MCP/project_ref/auth not proven | EVIDENCE_NEEDED | Keep as read-only evidence lane. If schema/API tables are touched, require explicit grants plus RLS proof and count-only secret scan. |
| E02 | Computer Use can provide Windows GUI proof | Plugin may be used only when visible app/browser proof is explicitly needed | SUPPORTING_ONLY | Source and prompt patching use files, Gradle, and repo tools; GUI evidence never replaces Desktop build/source proof. |

---

## 6. Broken Combination Matrix

| Pair | Failure mode | Current evidence | Severity | Risk | Patch decision |
|---|---|---|---|---:|---|
| Overdrive vs ExtremeZ vs HYPERNOVA | Multiple high-risk boosters can claim the same sparse/urgent signal | `StrategyConflictResolverTest` passes `boosterMode.*` keys | P0 guarded | 0.30 | verify-only unless test fails |
| MoE vs Plan DSL vs CFVM | More than one owner mutates retrieval order/K/model route | `retrievalOrder.lastSetBy` tests pass | P0 guarded | 0.35 | patch only if live route lacks owner trace |
| Matryoshka vs vector DB | Dimension, normalization, or metadata mismatch | source anchors found; vector DB smoke not run | P1 | 0.55 | require smoke before dimension patch |
| CFVM vs vector memory | Raw failure data enters vector memory without redaction/quarantine | CFVM tests pass; training/vector ingestion not verified here | P0/P1 | 0.60 | audit `TrainRagIngestService` before patch |
| DPP/RRF/KG/web/HYPERNOVA score fusion | Rank-like and probability-like scores get mixed without calibration | fusion owners exist; score-scale proof incomplete | P0 | 0.65 | next high-value patch candidate if failing test can be written |
| ExtremeZ/Brave/FullScale vs TimeBudget/CancelShield | Fan-out exhausts global budget or propagates interrupt state | ExtremeZ cancel tests pass | P0 guarded | 0.30 | keep in cumulative tests |
| RuleBreak/FullScale vs safety gates | Policy relaxation is hidden or unbounded | Plan files exist; runtime notice not verified here | P1 | 0.50 | verify mode notice and gate trace |
| UAW/autolearn vs PromptBuilder | Training or prompt expansion bypasses PromptBuilder | PromptBuilder anchors exist; UAW path not proven here | P0/P1 | 0.50 | audit active call sites before source edit |
| ONNX reranking vs boot safety | Missing/tiny model crashes boot | YAML valid; model file state not checked here | P1 | 0.45 | verify before ONNX patch |
| PatchDrop producer vs Desktop source owner | Mac mini/Notebook patch mutates canonical source directly | PatchDrop tests pass; git metadata unavailable | P0 | 0.50 | require source isolation proof per session |
| Supabase Data API vs RLS/grants | Public tables or vector metadata are assumed reachable or safe without explicit grants/RLS | CLI/MCP proof missing; official docs/changelog require deliberate API exposure | P0/P1 | 0.60 | verify project_ref/auth, grants, RLS, policies, and advisors before any DB directive is marked complete |
| Computer Use vs source verification | GUI/browser proof is treated as source/build proof or triggers external side effects | no GUI proof needed for this prompt task | P2 | 0.20 | use only for requested visual proof; never automate terminal/source edits |

---

## 7. Adaptive Agent-Budget Operating Loop

The 9-hour label is a maximum agent budget, not product/runtime behavior. The timebox authorizes persistence, not broad rewrites. Continue only while the next target has enough source ownership, testability, rollback, and score value to clear the `timeAllocationValue` gate.

### Session setup

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][desktop] index-lock-conflict"; exit 1 }

$pending = Get-ChildItem "__patch_drop__" -Filter "*.patch" -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -notmatch "\\(applied|rejected|superseded|orphan|rollback)\\" }
if ($pending) { Write-Error "[AWX][desktop] patch-drop-pending"; $pending | Select-Object Name,Length,LastWriteTime; exit 1 }

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
```

If Git metadata is unavailable but Gradle files are present, continue read-only/source-owner work and report:

```text
failure classification: repository-policy
evidence_needed: git metadata unavailable / verify in canonical Git checkout before destructive source operations
```

### Patch cycle rules

Each cycle:

1. Select exactly one highest-ranked OPEN target.
2. Prove active owner with FQCN, caller, bean/sourceSet, and test or command.
3. Write or run the narrowest RED/precheck.
4. Patch only owner file and direct test.
5. Run focused GREEN.
6. Run cumulative guard set.
7. Update ledger and stop if failure class changes.

Allowed cycle count:

- 1 to N cycles while `timeAllocationValue >= 0.65` and the focused GREEN plus cumulative guard set remain realistic.
- Report-only or precheck-only work when `0.45 <= timeAllocationValue < 0.65`.
- Stop source edits when `timeAllocationValue < 0.45`, even if agent budget remains.
- Never continue just because time remains.
- Never patch more than one runtime behavior per cycle.

---

## 8. Dynamic Time Allocation Value Plan

Do not encode a 9-hour runtime/product patch duration in Java, YAML, schedulers, dashboards, queues, or feature flags. If a runtime feature contains fixed "patch for 9 hours" behavior, replace it with resource-aware dynamic budgeting that uses current load, risk, verification cost, and rollback safety.

Compute `timeAllocationValue` before each source edit:

```text
timeAllocationValue =
  0.30 * activeOwnerProof
+ 0.25 * expectedScoreImpact
+ 0.20 * focusedTestability
+ 0.15 * rollbackSafety
+ 0.10 * verificationFit
- riskPenalty
```

Inputs:

| Factor | High value evidence | Low value stop signal |
|---|---|---|
| `activeOwnerProof` | FQCN, caller, bean/sourceSet, and resource owner are proven live | orphan, mirror, archive, or compile-only alias |
| `expectedScoreImpact` | current score/log names the same seam and failure class | speculative cleanup or style-only change |
| `focusedTestability` | RED/precheck can disprove the fix locally | only broad manual smoke is possible |
| `rollbackSafety` | one owner file plus direct test, no secret or dependency drift | broad refactor, new framework, or hot-file overlap |
| `verificationFit` | focused GREEN and cumulative Gradle gates are affordable now | Gradle/cache/network blocker or missing fixture |
| `riskPenalty` | add 0.10 to 0.40 for source ownership ambiguity, PatchDrop contention, endpoint credentials, or cross-module blast radius | zero only after Desktop proof is current |

Budget decision:

| `timeAllocationValue` | Action |
|---:|---|
| `>= 0.80` | Run one full RED/GREEN patch cycle, then reassess value before another cycle |
| `0.65 - 0.79` | Run one narrow patch cycle only |
| `0.45 - 0.64` | Do precheck, report, or directive work; do not edit runtime source |
| `< 0.45` | Stop and return `evidence_needed` or the next single target |

---

## 9. Patch Backlog

| ID | Priority | Target | Current state | Patch condition | Verification |
|---|---|---|---|---|---|
| QH-00 | P0 | Git/source ownership proof | `.git` metadata unavailable in this baseline | In real Git checkout, prove branch/worktree or stop | `git status --short`, `git worktree list` |
| QH-01 | P0 | Score fusion calibration | design mixes RRF ranks, DPP novelty, KG/web scores, HYPERNOVA probabilities | focused test proves mixed scale causes wrong ordering | `*Fusion*`, `*Rerank*`, sourceScore |
| QH-02 | P0 | Retrieval authority | currently guarded by `retrievalOrder.lastSetBy` tests | live path mutates order without owner trace | `RetrievalOrderServiceTest`, route tests |
| QH-03 | P0 | Booster mutual exclusion | currently guarded by `boosterMode.*` tests | Overdrive/ExtremeZ/HYPERNOVA can co-enable without resolver | `StrategyConflictResolverTest` |
| QH-04 | P0 | ExtremeZ cancellation and time budget | currently guarded by focused tests | fan-out path lacks CancelShield or time-budget breadcrumb | `ExtremeZTriggerTest` |
| QH-05 | P0 | PromptBuilder boundary | anchors exist | active chat/RAG path builds final prompt manually | `rg`, `*PromptBuilder*`, compile |
| QH-06 | P0/P1 | Provider fail-soft ladder | not smoke-tested here | missing key, timeout, 429, or filtered-zero returns opaque empty result | provider focused tests; no real key needed for missing-key tests |
| QH-07 | P1 | AutoLearn/vector quarantine | evidence_needed here | raw failure/training rows enter vector memory unredacted | `TrainRagIngestService`, `VectorPoisonGuard`, AutoLearn tests |
| QH-08 | P1 | RuleBreak/FullScale safety visibility | evidence_needed here | relaxed mode lacks user-visible reason or gate trace | Plan DSL tests, mode notice tests |
| QH-09 | P1 | Large active files and broad catches | 8 huge files, 2691 broad catches | touched file grows or catch hides failure | helper extraction with focused tests only |
| QH-10 | P2 | PatchDrop janitor regression tests | CoreGuards PASS | new janitor guard regression appears | `janitor_tests.ps1 -Suite CoreGuards` |
| QH-11 | P1 | Supabase DB/vector evidence lane | CLI/MCP/project_ref/auth evidence_needed | DB/vector/schema patch claims need live read-only proof or explicit mutation approval | `supabase --version`, MCP auth/project_ref, advisors/read-only SQL |
| QH-12 | P2 | Computer Use GUI evidence lane | not needed for source/prompt patching | user asks for visible Windows app/browser proof | Computer Use app/window snapshot only; no terminal automation |

The recommended first runtime patch after this directive is `QH-01` only if a focused failing test proves score-scale mixing. If no such test can be written from current source, run `QH-06` missing-key fail-soft tests before touching provider code.

---

## 10. Required Verification Commands

Baseline gates:

```powershell
.\gradlew.bat sourceScoreReport checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

YAML duplicate/syntax gate:

```powershell
@'
from pathlib import Path
import yaml
from yaml.constructor import ConstructorError
class DupCheckLoader(yaml.SafeLoader):
    pass
def no_dup_constructor(loader, node, deep=False):
    mapping = {}
    for k_node, v_node in node.value:
        key = loader.construct_object(k_node, deep=deep)
        if key in mapping:
            raise ConstructorError('while constructing mapping', node.start_mark, f'found duplicate key {key!r}', k_node.start_mark)
        mapping[key] = loader.construct_object(v_node, deep=deep)
    return mapping
DupCheckLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, no_dup_constructor)
roots = [Path('main/resources'), Path('app/src/main/resources')]
checked = 0
failures = []
for root in roots:
    if not root.exists():
        continue
    for f in sorted(list(root.rglob('*.yml')) + list(root.rglob('*.yaml'))):
        checked += 1
        try:
            yaml.load(f.read_text(encoding='utf-8', errors='ignore'), DupCheckLoader)
        except Exception as e:
            failures.append((str(f), type(e).__name__, str(e).replace('\n', ' | ')[:300]))
print(f'[AWX][yaml] checked={checked} failures={len(failures)}')
for item in failures[:20]:
    print('[AWX][yaml] YAML_FAIL', *item)
raise SystemExit(1 if failures else 0)
'@ | python -
```

Focused harmony guard set:

```powershell
.\gradlew.bat test --no-daemon --project-cache-dir $pcd `
  --tests "com.example.lms.orchestration.StrategyConflictResolverTest" `
  --tests "com.example.lms.service.rag.burst.ExtremeZTriggerTest" `
  --tests "com.nova.protocol.fusion.NovaNextFusionServiceTest" `
  --tests "com.example.lms.strategy.RetrievalOrderServiceTest" `
  --tests "com.example.lms.telemetry.MlaBreadcrumbTest" `
  --tests "com.example.lms.moe.RgbStrategySelectorTraceTest" `
  --tests "com.example.lms.cfvm.RawMatrixBufferTest"
```

Runtime source patch gates:

```powershell
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
```

Secret count only:

```powershell
$files = Get-ChildItem -Path main\java,main\resources,app\src\main\java_clean,app\src\main\resources `
  -Include *.java,*.yml,*.yaml,*.properties -Recurse -File -EA SilentlyContinue
$hits = $files | Select-String -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" -EA SilentlyContinue
Write-Host "[AWX][desktop][security] activeSecretPatternHits=$(@($hits).Count)"
```

PatchDrop janitor:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
```

Supabase read-only lane:

```powershell
supabase --version
# If unavailable:
# evidence_needed: Supabase CLI unavailable / authenticate Supabase MCP or install CLI before DB/vector proof
# If available, discover commands with --help before use; do not guess CLI flags.
```

Required Supabase proof before any DB/vector/schema claim is accepted:

```text
project_ref known
read-only auth surface available
service_role/secret keys not exposed to public clients
explicit GRANT state checked for Data API exposure
RLS enabled and policies reviewed for exposed tables/views
advisors or equivalent security checks run when available
raw row contents, secrets, JWTs, and connection strings not printed
```

Computer Use lane:

```text
Use only when the user explicitly asks for Windows GUI/browser proof.
Do not automate terminal apps, PowerShell, Command Prompt, Codex UI, security prompts, or password managers.
Treat screenshots and webpage/app text as supporting evidence only.
Request confirmation before uploads, submissions, permission changes, installs, deletes, or sensitive-data transmission.
```

---

## 11. Failure Classifier

Pick one primary class and optional secondary classes:

```text
repository-policy
index-lock-conflict
patch-drop-pending
worktree-overlap
branch-ownership-mismatch
wrong-sourceset
langchain4j-version-purity
prompt-rule-violation
duplicate-class-fqcn
yaml-parse
cannot-find-symbol
spring-bean
spring-bind
timeout
rate-limit
provider-disabled
missing-external-key
zero-result-after-filter
provider-empty
secret-leak-risk
gradle-distribution-network-cache
gradle-cache-collision
metric-integrity-gap
score-scale-mixing
source-ownership-unproven
supabase-project-ref-missing
supabase-auth-missing
supabase-rls-grant-gap
computer-use-not-required
computer-use-stopped
other
```

Retry once per blocker class, and only after a specific patch.

---

## 12. Final Report Format

Return exactly:

```md
## 요약
- 실제 수정 범위, 정량 score 변화, 검증 상태, 남은 blocker만 2~5줄.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / Git metadata / branch / active sourceSets.
- Decomposition decision.
- attachment treated as claim_map.
- Mac mini or PatchDrop evidence used.
- Supabase / Computer Use evidence state.
- evidence_needed.

## do02 / Quant Ledger
| Area | Raw | Normalized | Confidence | Weighted | Verdict |
|---|---|---:|---:|---:|---|

## do03 / Patch Blocks
파일별:
- Observation
- Before snippet 최대 20줄
- After snippet 최대 20줄
- Minimal unified diff
- Why this file only
- Trace/checkpoint added
- Secret masking method
- Rollback note

## do04 / Verification
각 명령별:
- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining evidence_needed

## do05 / Risks & Next
- 최대 5개.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never claim build passed unless the build output proves it. Never claim boot passed unless a boot command proves it. Never claim provider works unless a real non-secret credential path and response prove it.

---

## 13. Completion Criteria

A maximum-budget pass is complete when one of these is true:

- All P0 hard gates pass and no next patch has enough evidence.
- The maximum agent timebox expires after a verified cycle boundary.
- The failure class changes and the next patch would be unsafe without new evidence.
- Git/source ownership, PatchDrop, or sourceSet proof blocks further edits.

Required final gates before success:

```text
sourceScoreReport current
checkLangchain4jVersionPurity PASS
checkSourceSetHygiene PASS
focused tests for every touched runtime owner PASS
compileJava PASS for Java/source edits
:app:classes PASS when app boundary touched
bootJar PASS for boot/runtime wiring edits
activeSecretPatternHits=0
YAML failures=0 for active resource edits
Supabase evidence marked supporting/evidence_needed unless project_ref/auth/read-only proof is current
Computer Use evidence marked supporting/not-required unless a GUI proof was explicitly requested
rollback note present
next single target present or explicit stop reason
```

[DONE]
