# demo1_legacy_300_metadata_9h_patch

> 대상: Codex / Antigravity / Claude 계열 장기 소스수정 에이전트
>
> 목적: 첨부 설계도면을 claim map으로 삼고, Desktop canonical root의 파일 수정일 메타데이터를 기준으로 오래된 레거시 타점을 300개 안팎의 큐로 뽑아 9시간 동안 안전하게 개선, 흡수, 검증한다.
>
> 모드: Desktop Autonomous Safe Patch. 최신 파일을 더 건드리려 하지 말고, 오래된 수정일과 현재 active sourceSet 증거가 만나는 지점부터 처리한다.

## 0. Current Evidence Snapshot

이 지시서는 2026-06-19 Asia/Seoul 기준 Desktop root에서 생성되었다.

```text
canonical_root: C:\AbandonWare\demo-1\demo-1\src
git_metadata: unavailable in this mirror (Test-Path .git = false, git rev-parse exit=128)
index_lock: false
PatchDrop source leases: activeCount=0
PatchDrop top-level producer pending: 0
PatchDrop nested report-only: tailscale-smb-load-shed notebook evidence only, not apply candidate
Supabase MCP config: read_only=true, project_ref missing
Computer Use: available only for explicit Windows UI proof; not needed for source metadata pass
```

Authoritative Gradle/source evidence from this root:

```text
sourceScoreReport: BUILD SUCCESSFUL, Total Score 100 / 100
checkLangchain4jVersionPurity: PASS
checkSourceSetHygiene: PASS
active roots: main/java, main/resources, app/src/main/java_clean, app/src/main/resources
inactive unless Gradle proves otherwise: demo-1, lms-core, app/src/main/java, project/src/main/java, archives, backups
duplicateFqcn=0
packagePathMismatch=0
activeLegacySignals=184
largeActiveSourceFiles=12
largeActiveSourceMethods=990
broadCatches=2724
exactEmptyCatchMatches=0
configResourceFiles=47
resourceBackupArtifacts=0
sensitiveResourceStores=0
```

The repo is already green. The 9-hour goal is not to chase the score. The goal is to reduce old legacy concentration without breaking the green hard gates.

## 1. Attachment Design Baseline

Treat `C:\Users\nninn\.codex\attachments\14532d42-b068-4856-a5ec-85875800597c\pasted-text.txt` as a design claim map, not as truth. It was read as UTF-8.

High-signal design pillars found in the attachment:

```text
Anchor-Based Context Compression: 28
Failure Pattern: 20
CFVM: 12
MoE / Mixture-of-Experts: 25 total
DPP: 27
Plan DSL: 33
CitationGate: 26
PromptBuilder: 15
ONNX: 57
Self-Ask: 23
UAW: 19
OpenAI / LangChain4j: 28 total
Brave / Naver: 23 total
HYPERNOVA / CVaR / TailWeighted: 15 total
Matryoshka: 4
ExtremeZ: 2
GraphRAG / KG: 16 total
```

Design-to-source interpretation:

- S01 Overdrive / Anchor compression should stay on canonical active `OverdriveGuard`, `DynamicContextCompressor`, and PromptBuilder-safe context injection.
- S02 CFVM / Failure Pattern should stay on active `main/java/com/example/lms/cfvm/**` and `RetrievalOrder` proof, not on old `demo-1` or `lms-core` shadows.
- S03 MoE / ArtPlate should preserve current ArtPlate evolver/selector ownership and not create new strategy frameworks.
- S04 Matryoshka / embedding / ZCA should preserve dimension and normalization contracts and disable missing/placeholder model paths fail-soft.
- S05 ExtremeZ should preserve CancelShield, TimeBudget, and interrupt hygiene.
- S06 HYPERNOVA / DPP / CVaR should preserve existing `com/nova/protocol/fusion` ownership and avoid parallel score-fusion implementations.
- S07 CIH-RAG / Citation / Breadcrumb should preserve redacted trace keys and citation gates.
- S08 OpenAI adapter / version purity should keep every `dev.langchain4j:*` at `1.0.1` and keep responses/chat mismatch as expected failure, not blank output.

## 1.1 Attached Runtime Patch Directive Overlay

Treat `C:\Users\nninn\Downloads\codex_patch_directive_20260618.md` as the latest runtime backlog overlay. It is not permission for broad edits; it is a priority map to apply only after active sourceSet proof and only when the named seam still lacks the required guard, trace key, or regression test.

Overlay priority order:

| priority | seam | active target files | required proof before DONE |
|---|---|---|---|
| P0-A | UAW AutoLearn / translation fail-soft | `main/java/com/example/lms/service/TranslationService.java`, `main/java/com/example/lms/uaw/autolearn/UawAutolearnService.java`, `main/java/ai/abandonware/nova/orch/aop/UawAutolearnStrictRequestAspect.java`, `main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java` | `*UawAutolearn*` or `*Translation*` focused test, no blank-result training sample, redacted TraceStore breadcrumbs |
| P0-B | ExtremeZ cancellation and executor fallback | `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`, `main/java/com/example/lms/service/rag/RagChainConfig.java`, optional `ExtremeZTrigger.java` if present | `*ExtremeZ*` or `*RagChain*` focused test, replace timeout cleanup with `future.cancel(false)` where safe, no `future.cancel(true)` toxicity, collected partial results preserved |
| P0-C | ChatWorkflow / PromptBuilder boundary | `main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/prompt/StandardPromptBuilder.java` | `*ChatWorkflow*` or `*PromptBuilder*` focused test, `PromptBuilder.build(PromptContext)` used before final LLM calls |
| P0-D | CFVM failure-pattern recording | `main/java/com/example/lms/cfvm/CfvmFailureRecorder.java`, `RawSlotExtractor.java`, `CfvmJbCbCalculator.java`, `RawMatrixBuffer.java` | `*Cfvm*` or `*RawSlot*` focused test, optional provider absence recorded, divide-by-zero guarded |
| P0-E | MoE ArtPlate selector and evolver safety | `main/java/com/example/lms/artplate/NineArtPlateGate.java`, `ArtPlateRegistry.java`, optional `ArtPlateEvolver.java` if present | `*ArtPlate*` or `*NineArtPlate*` focused test, base plate fallback on evolver error, selected plate traced |
| P0-F | Overdrive anchor compression safety | `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`, optional `DynamicContextCompressor.java` if present | `*Overdrive*` or `*ContextCompress*` focused test, no raw query trace, compression returns previous stage on empty final stage |
| P1-A | RetrievalOrder CFVM feedback | `main/java/com/example/lms/strategy/RetrievalOrderService.java`, `main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java` | `*RetrievalOrder*` or `*FailurePattern*` focused test, last-set-by trace identifies CFVM or DEFAULT_FIXED |
| P1-B | Web starvation fail-soft ladder | `WebFailSoftSearchAspect.java`, `HybridWebSearchEmptyFallbackAspect.java`, `HybridWebSearchEmptyFallbackSupport.java` | `*WebFailSoft*` or `*HybridWebSearch*` focused test, ladder trace keys present, `proceed()` called once |
| P1-C | StrategyConflictResolver priority | `main/java/com/example/lms/orchestration/StrategyConflictResolver.java` | `*StrategyConflict*` focused test, ExtremeZ wins over Overdrive, suppressed modes traced |
| P2-A | TraceSuppressions comment/key cleanup | eight `*TraceSuppressions.java` files listed in the directive | logic unchanged, suppress keys checked against actual TraceStore keys, only stale comments or unmatched key notes changed |

Required overlay trace keys and behaviors:

```text
translation.failReason
translation.disabled
translation.disabledReason=api-key-missing
uaw.autolearn.cancelled
uaw.autolearn.sampleSkipped.reason=empty-response
uaw.handoffWriter.absent
uaw.strictRequest.blocked
extremeZ.interrupted
extremeZ.outCount
extremeZ.executor.source=fallback
chatWorkflow.promptBuilderUsed
promptBuilder.nullCtx
promptBuilder.evidenceEmpty
cfvm.buffer.absent
cfvm.memory.absent
cfvm.jbcb.absent
cfvm.jbcb.error
cfvm.buffered
cfvm.memoryRecorded
cfvm.patternHex
cfvm.signature.empty
cfvm.patternId.zero
cfvm.jb
cfvm.cb
moe.evolver.error
moe.selectedPlate
moe.rawBuffer.absent
moe.registry.empty
moe.evolver.candidateNull
moe.evolver.plateRegistered
moe.evolver.plateId
overdrive.blackbox.absent
overdrive.activated
overdrive.score
overdrive.reason
overdrive.compress.stage.<stage>
overdrive.compress.error
starvationFallback.trigger
outCount
poolSafeEmpty
cacheOnly.merged.count
rescueMerge.used
web.<provider>.skipped.reason
specialMode.conflict.suppressed
```

Overlay execution rule:

1. When a 300-queue candidate intersects one of the overlay seams, use the overlay priority and trace contract first.
2. If the active source already satisfies the overlay contract, record `no_patch_needed` with the exact evidence instead of changing code.
3. If the overlay asks for a file that does not exist in the active sourceSet, record `evidence_needed` or `not-present-in-active-root`; do not create a duplicate owner.
4. If a P0 overlay patch fails its focused test, stop that candidate, revert only that patch, classify the failure, and move to the next safe candidate.

## 2. Decomposition Decision

```text
Decomposition decision:
- mode: N-way
- reason: The task combines attachment design claims, file metadata age, active sourceSet proof, legacy absorption, Supabase read-only DB evidence, and Windows/Desktop verification.
- branches:
  A. active sourceSet and hard gates
  B. metadata-old candidate queue
  C. design pillar mapping
  D. inactive legacy absorption / delete proof
  E. resource/test/tooling cleanup
  F. Supabase and Computer Use constraints
- skipped_axes: none; direct patching is skipped until each candidate has proof.
```

## 3. Non-Negotiables

- Do not edit `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, real credential values, keystores, or secret setup.
- Do not rename env vars, `openssl`, or `opnessl` keys.
- Keep Java 17 / Spring Boot 3 and the current Gradle ownership.
- Keep `dev.langchain4j:*` exactly `1.0.1`.
- Keep final prompt assembly on `PromptBuilder.build(PromptContext)` or the current equivalent boundary.
- Do not patch inactive mirrors just because their files are old.
- Do not delete by simple class name. Use package + FQCN + active sourceSet + external usage proof.
- Do not fabricate build, boot, provider, browser, Supabase, or score success.
- For optional external credentials, fail soft with redacted `disabledReason` and no outbound call.
- For Supabase, keep MCP/database tasks read-only unless the user explicitly approves mutation and a concrete `project_ref` is selected.
- For Computer Use, do not automate Windows apps unless a visible GUI proof is explicitly needed; source patching uses shell/Gradle evidence.

## 4. Start Commands

Run from Desktop root before editing:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root

Get-Location
Write-Host "[AWX][git] gitPath=$(Test-Path '.git')"
git rev-parse --show-toplevel 2>$null
Write-Host "[AWX][git] revparseExit=$LASTEXITCODE"
git branch --show-current 2>$null
Write-Host "[AWX][git] branchExit=$LASTEXITCODE"
git status --short 2>$null
Write-Host "[AWX][git] statusExit=$LASTEXITCODE"
if (Test-Path ".git\index.lock") { Write-Error "[AWX] index-lock-conflict"; exit 1 }

if (Test-Path "__patch_drop__\janitor_inventory.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass -File "__patch_drop__\janitor_inventory.ps1"
}

$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null

.\gradlew.bat sourceScoreReport checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

If Git metadata is unavailable, continue with filesystem, Gradle, janitor, and report evidence. Do not invent branch state.

## 5. Metadata Candidate Queue

The current metadata pass found 3,019 source/resource/test/tool candidates, then selected a balanced 300-item queue:

```text
A_ACTIVE_HARDEN: 120
B_ACTIVE_OLD_REVIEW: 35
C_RESOURCE: 35
D_INACTIVE_ABSORB: 75
E_TEST_BACKFILL: 25
F_TOOLING: 10
```

Lane distribution in the selected 300:

```text
SPRING_WIRING_GUARD: 87
ALIAS_ABSORB_PROVE_DELETE: 13
FAILSOFT_TRACE_HARDEN: 3
DESIGN_PILLAR_HARDEN: 16
HOLD_CANONICAL_HARDEN: 3
OLD_ACTIVE_REVIEW: 43
RESOURCE_MERGE_OR_DISABLE: 35
ABSORB_OR_PROVE_DELETE: 75
TEST_BACKFILL_OR_DELETE_STALE: 25
```

Selected queue ID ranges:

```text
L001-L120: old active runtime hardening candidates
L121-L155: old active review candidates with no immediate design/catch/spring signal
L156-L190: old resource/config merge or disable candidates
L191-L265: inactive legacy absorption or prove-delete candidates
L266-L290: old tests to backfill, repair, or remove if stale
L291-L300: old tooling scripts to reconcile with current Desktop proof chain
```

Representative seed rows from the actual metadata pass:

```csv
id,bucket,date,lane,subsystem,path
L001,A_ACTIVE_HARDEN,2026-02-01,SPRING_WIRING_GUARD,LEGACY_MISC,main/java/com/abandonware/ai/agent/config/Bm25Props.java
L010,A_ACTIVE_HARDEN,2026-02-01,FAILSOFT_TRACE_HARDEN,LEGACY_MISC,main/java/com/example/lms/aop/RetrievalDiagAspect.java
L035,A_ACTIVE_HARDEN,2026-02-01,SPRING_WIRING_GUARD,LEGACY_MISC,main/java/com/example/lms/config/aop/AliasCorrectionPreResolveAspect.java
L082,A_ACTIVE_HARDEN,2026-02-01,FAILSOFT_TRACE_HARDEN,LEGACY_MISC,main/java/com/example/lms/service/routing/plan/RouterDecisionCache.java
L094,A_ACTIVE_HARDEN,2026-02-01,DESIGN_PILLAR_HARDEN,PROMPT,main/java/com/acme/aicore/domain/model/Prompt.java
L105,A_ACTIVE_HARDEN,2026-02-01,HOLD_CANONICAL_HARDEN,PROMPT,main/java/com/example/lms/learning/gemini/GeminiCurationPromptBuilder.java
L156,C_RESOURCE,oldest-first,RESOURCE_MERGE_OR_DISABLE,resource,main/resources and app/src/main/resources queue
L191,D_INACTIVE_ABSORB,2026-01-31,ABSORB_OR_PROVE_DELETE,legacy,demo-1/src/main/java/** queue
L214,D_INACTIVE_ABSORB,2026-01-31,ABSORB_OR_PROVE_DELETE,legacy,lms-core/src/main/java/** queue
L266,E_TEST_BACKFILL,2026-01-31,TEST_BACKFILL_OR_DELETE_STALE,S08_OPENAI_ADAPTER,src/test/java/com/example/lms/resilience/AuxDegradedBlocksAuxStagesTest.java
L291,F_TOOLING,2026-01-31,OLD_ACTIVE_REVIEW,LEGACY_MISC,scripts/apply-configs.sh
L300,F_TOOLING,2026-01-31,ALIAS_ABSORB_PROVE_DELETE,LEGACY_MISC,tools/error_pattern_extractor.py
```

Regenerate the exact 300 queue at run time with this command. Save its stdout to a report, not to source, unless the user asks for an artifact.

```powershell
@'
from pathlib import Path
from datetime import datetime
import re
root=Path('.').resolve()
root_defs=[
 ('ACTIVE_MAIN_JAVA', Path('main/java'), 'active'),
 ('ACTIVE_MAIN_RES', Path('main/resources'), 'active-resource'),
 ('ACTIVE_APP_CLEAN', Path('app/src/main/java_clean'), 'active'),
 ('ACTIVE_APP_RES', Path('app/src/main/resources'), 'active-resource'),
 ('TEST_JAVA', Path('src/test/java'), 'test'),
 ('TEST_RES', Path('src/test/resources'), 'test-resource'),
 ('INACTIVE_APP_JAVA', Path('app/src/main/java'), 'inactive-mirror'),
 ('OPTIN_DEMO1_JAVA', Path('demo-1/src/main/java'), 'opt-in-legacy'),
 ('OPTIN_LMS_CORE_JAVA', Path('lms-core/src/main/java'), 'opt-in-legacy'),
 ('ROOT_LEGACY_COM', Path('com'), 'inactive-root'),
 ('ROOT_LEGACY_SERVICE', Path('service'), 'inactive-root'),
 ('ROOT_LEGACY_GUARD', Path('guard'), 'inactive-root'),
 ('ADDONS', Path('addons'), 'support'),
 ('CONFIGS', Path('config'), 'support-resource'),
 ('CONFIGS2', Path('configs'), 'support-resource'),
 ('TOOLS', Path('tools'), 'tooling'),
 ('SCRIPTS', Path('scripts'), 'tooling'),
]
allowed={'.java','.kt','.kts','.gradle','.yml','.yaml','.properties','.xml','.json','.ps1','.py','.sh'}
subsystems=[
 ('S01_OVERDRIVE','overdrive|anchor|compress|contextcompressor|dynamiccontext'),
 ('S02_CFVM','cfvm|rawmatrix|rawslot|rawtile|retrievalorder|failurepattern|failure_pattern'),
 ('S03_MOE','moe|artplate|rgbstrategy|strategyselector|plate'),
 ('S04_MATRYOSHKA','matryoshka|embedding|whitening|zca|ollama'),
 ('S05_EXTREMEZ','extremez|extreme|burst|cancelshield|timebudget|parallelquery'),
 ('S06_HYPERNOVA','hypernova|cvar|dpp|diversity|twpm|tailweighted|rerank|fuser|rrf'),
 ('S07_CIH_RAG','cih|iqr|mla|breadcrumb|citationgate|citation'),
 ('S08_OPENAI_ADAPTER','langchain4j|openai|responses|modelguard|versionpurity|version|llm'),
 ('WEB_PROVIDER','naver|brave|serpapi|websearch|hybridweb|searchprovider|domainwhitelist'),
 ('PROMPT','promptbuilder|promptcontext|prompt'),
 ('UAW_AUTOLEARN','uaw|autolearn|trainrag|idletrain|learning'),
 ('SECURITY_REDACTION','pii|redact|sanitiz|secret|auth|token|security'),
 ('PATCHDROP_JANITOR','patchdrop|janitor|producer_bundle|source_edit'),
]
canon_holds=['PromptBuilder.java','ExtremeZSystemHandler.java','OverdriveGuard.java','DppDiversityReranker.java','CvarAggregator.java','NovaNextFusionService.java','NaverSearchService.java','BraveSearchService.java','SerpApiProvider.java','HybridWebSearchProvider.java','NightmareBreaker.java','QueryTransformer.java','TraceStore.java','DebugEventStore.java']
def classify(rel, rootkind, ext, text, subsystem):
    deprecated=bool(re.search(r'@Deprecated|deprecated|legacy|alias|compatib|bridge', text, re.I))
    spring=bool(re.search(r'@(Component|Service|Repository|Controller|RestController|Configuration|AutoConfiguration|Aspect|Bean|SpringBootApplication)\b', text))
    canonical=any(rel.endswith(x) for x in canon_holds)
    broad=len(re.findall(r'catch\s*\(\s*(?:Exception|Throwable|RuntimeException)\b', text))
    empty=len(re.findall(r'catch\s*\([^)]*\)\s*\{\s*\}', text, re.S))
    if canonical: lane='HOLD_CANONICAL_HARDEN'
    elif rootkind.startswith('inactive') or rootkind.startswith('opt-in'): lane='ABSORB_OR_PROVE_DELETE'
    elif rootkind.startswith('test'): lane='TEST_BACKFILL_OR_DELETE_STALE'
    elif rootkind.endswith('resource') or ext in {'.yml','.yaml','.properties','.xml','.json'}: lane='RESOURCE_MERGE_OR_DISABLE'
    elif deprecated and not spring: lane='ALIAS_ABSORB_PROVE_DELETE'
    elif broad or empty: lane='FAILSOFT_TRACE_HARDEN'
    elif spring: lane='SPRING_WIRING_GUARD'
    elif subsystem!='LEGACY_MISC': lane='DESIGN_PILLAR_HARDEN'
    else: lane='OLD_ACTIVE_REVIEW'
    return lane, deprecated, spring, broad, empty
rows=[]
for label, relroot, rootkind in root_defs:
    base=root/relroot
    if not base.exists(): continue
    for f in base.rglob('*'):
        if not f.is_file() or f.suffix.lower() not in allowed: continue
        rel=f.relative_to(root).as_posix()
        try:
            raw=f.read_text(encoding='utf-8', errors='ignore') if f.stat().st_size < 2_000_000 else ''
        except Exception:
            raw=''
        low=(rel+'\n'+raw[:40000]).lower()
        subsystem='LEGACY_MISC'
        for name,pat in subsystems:
            if re.search(pat, low): subsystem=name; break
        lane, deprecated, spring, broad, empty=classify(rel, rootkind, f.suffix.lower(), raw, subsystem)
        dt=datetime.fromtimestamp(f.stat().st_mtime)
        loc=raw.count('\n')+1 if raw else 0
        rows.append({'date':dt.strftime('%Y-%m-%d'),'label':label,'rootkind':rootkind,'lane':lane,'subsystem':subsystem,'loc':loc,'broad':broad,'empty':empty,'deprecated':int(deprecated),'spring':int(spring),'path':rel})
buckets=[
 ('A_ACTIVE_HARDEN', 120, lambda r: r['rootkind']=='active' and r['lane']!='OLD_ACTIVE_REVIEW'),
 ('B_ACTIVE_OLD_REVIEW', 35, lambda r: r['rootkind']=='active' and r['lane']=='OLD_ACTIVE_REVIEW'),
 ('C_RESOURCE', 35, lambda r: 'resource' in r['rootkind'] or r['lane']=='RESOURCE_MERGE_OR_DISABLE'),
 ('D_INACTIVE_ABSORB', 75, lambda r: r['lane']=='ABSORB_OR_PROVE_DELETE'),
 ('E_TEST_BACKFILL', 25, lambda r: r['lane']=='TEST_BACKFILL_OR_DELETE_STALE'),
 ('F_TOOLING', 10, lambda r: r['rootkind']=='tooling' or r['subsystem']=='PATCHDROP_JANITOR'),
]
selected=[]; seen=set()
for bucket, quota, pred in buckets:
    cand=[r for r in rows if pred(r) and r['path'] not in seen]
    cand.sort(key=lambda r:(r['date'], r['subsystem'], r['path']))
    for r in cand[:quota]:
        r=r.copy(); r['bucket']=bucket; selected.append(r); seen.add(r['path'])
if len(selected)<300:
    extra=[r for r in rows if r['path'] not in seen]
    extra.sort(key=lambda r:(r['date'], 0 if r['subsystem']!='LEGACY_MISC' else 1, -r['broad']-r['empty'], r['path']))
    for r in extra[:300-len(selected)]:
        r=r.copy(); r['bucket']='G_FILLER_OLD_DESIGN'; selected.append(r); seen.add(r['path'])
print('id,bucket,date,lane,subsystem,root,loc,broadCatch,emptyCatch,deprecated,spring,path')
for i,r in enumerate(selected,1):
    vals=[f'L{i:03d}',r['bucket'],r['date'],r['lane'],r['subsystem'],r['label'],str(r['loc']),str(r['broad']),str(r['empty']),str(r['deprecated']),str(r['spring']),r['path']]
    print(','.join('"'+v.replace('"','""')+'"' for v in vals))
'@ | python -
```

## 6. Lane Rules

### A_ACTIVE_HARDEN

Purpose: old active runtime files. Improve observability, fail-soft behavior, condition guards, or focused tests.

Allowed:

- Add missing redacted TraceStore/debug breadcrumbs.
- Guard optional beans with existing Spring conditional patterns.
- Replace broad catch swallowing with a breadcrumb and existing fallback.
- Add focused tests before touching behavior.

Forbidden:

- Delete active Spring components.
- Split large classes unless a focused test proves a narrow extract is safe.
- Create new framework wrappers.

### B_ACTIVE_OLD_REVIEW

Purpose: old active files with weak signal. Review first; patch only when a concrete blocker or missing regression is found.

Default outcome: `verify_only` or `no_patch_needed`.

### C_RESOURCE

Purpose: old YAML/properties/resources.

Allowed:

- Merge duplicate YAML keys.
- Disable placeholder ONNX/model paths with explicit reason.
- Normalize provider-disabled flags without renaming env vars.
- Remove stale `.bak` resource artifacts only if sourceSet hygiene or package resource proof says they are inactive.

Forbidden:

- Edit real secrets or secret setup.
- Rename env vars, `openssl`, or `opnessl`.
- Enable providers without real credentials.

### D_INACTIVE_ABSORB

Purpose: old `demo-1`, `lms-core`, root `com`, root `service`, root `guard`, or inactive app mirror files.

Allowed:

- Treat as idea/reference only.
- If a unique behavior is still valuable, absorb a tiny tested behavior into the canonical active class.
- If dead, delete only after zombie candidate audit proves no active references.

Required before any delete:

```powershell
python C:\Users\nninn\.codex\skills\abandonware-desktop-zombie-purge-safe-patch\scripts\zombie_candidate_audit.py --root . --candidates candidates.md --format markdown
python C:\Users\nninn\.codex\skills\abandonware-desktop-zombie-purge-safe-patch\scripts\zombie_candidate_audit.py --root . --candidates candidates.md --format json
```

Forbidden:

- Copy inactive class bodies into active source wholesale.
- Patch `demo-1` or `lms-core` to make them compile unless `-PincludeLegacyModules=true` is explicitly part of this patch pass.

### E_TEST_BACKFILL

Purpose: old tests that can protect active legacy cleanup.

Allowed:

- Repair stale path assumptions.
- Convert broad old tests into focused regression tests for the active seam.
- Delete stale tests only if they target inactive modules and a replacement active test exists.

### F_TOOLING

Purpose: old scripts/tools.

Allowed:

- Align old scripts with current Desktop cache isolation and sourceSet paths.
- Add count-only secret scans.
- Remove or quarantine obsolete scripts only after no prompt/manifest/script references remain.

## 7. 9-Hour Loop

This is a timebox for persistence, not permission for broad rewrites.

### Phase 0: 0-30 min, intake and queue

- Run Start Commands.
- Regenerate the 300 queue.
- Write `analysis/legacy-300-run-notes.md` only if the user wants an artifact; otherwise keep notes in the final report.
- Pick the first candidate whose lane has safe proof and whose failure class is not already green.

### Phase 1: 30-150 min, P0 active hardening

Process `A_ACTIVE_HARDEN` candidates first.

Patch rule:

1. Write or identify a focused test.
2. Patch the smallest active source seam.
3. Run the focused test.
4. Run `compileJava -x test`.
5. Run `checkSourceSetHygiene` and active secret count.

Stop phase if the failure class changes to compile, duplicate FQCN, LangChain4j purity, PromptBuilder bypass, or secret risk.

### Phase 2: 150-270 min, design pillar hardening

Prioritize old active candidates that map to attachment pillars:

```text
S01_OVERDRIVE -> OverdriveGuard / DynamicContextCompressor / anchor trace
S02_CFVM -> RawMatrixBuffer / RawSlotExtractor / RetrievalOrder / cfvm.tempSource
S03_MOE -> ArtPlate / strategy selector / evolver registration
S04_MATRYOSHKA -> embedding slice method / ZCA / dimension proof
S05_EXTREMEZ -> CancelShield / TimeBudget / interrupt cleanup
S06_HYPERNOVA -> DPP / CVaR / TWPM / Risk-K / score scale proof
S07_CIH_RAG -> breadcrumb / CitationGate / IQR / MLA trace
S08_OPENAI_ADAPTER -> model guard / expected failure / version purity
```

Every patch touching two or more S01-S08 groups must run the cross-subsystem test matrix from `demo1-cross-subsystem-guard`.

### Phase 3: 270-390 min, inactive absorption

Process `D_INACTIVE_ABSORB`.

For each candidate:

- Find canonical active owner.
- Compare behavior, not file name.
- If the inactive code is better, absorb the smallest behavior into active source with a regression test.
- If dead, run zombie audit and delete only `REVIEW_DELETE_CANDIDATE`.
- If ambiguous, mark `HOLD_REFERENCED` or `evidence_needed`.

### Phase 4: 390-480 min, resources and tests

Process `C_RESOURCE` and `E_TEST_BACKFILL`.

Resource focus:

- duplicate YAML keys,
- placeholder ONNX/model disable reasons,
- config files included in `checkSourceSetHygiene`,
- no secret values.

Test focus:

- tests should pin the active canonical class, not inactive mirror classes.
- add tests for every active behavior absorbed from inactive code.

### Phase 5: 480-540 min, tooling and final proof

Process `F_TOOLING` only if broad gates remain green.

Final proof:

```powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop'
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle-awx-desktop"
$pcd="$env:USERPROFILE\.awx-gradle-project-cache\desktop"

.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $pcd
.\gradlew.bat sourceScoreReport --no-daemon --project-cache-dir $pcd

$hits = Get-ChildItem "main\java","main\resources","app\src\main\java_clean","app\src\main\resources" -Recurse -File -ErrorAction SilentlyContinue |
  Where-Object { $_.Extension -match '\.(java|yml|yaml|properties)$' } |
  Select-String -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" -ErrorAction SilentlyContinue
Write-Host "[AWX][security] activeSecretPatternHits=$(@($hits).Count)"
```

Do not claim boot success unless a boot command proves it. `bootJar` is not boot success.

## 8. Supabase Lane

Supabase is in scope only as read-only evidence unless the user explicitly authorizes schema mutation.

Current local state:

```text
.mcp.json: https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs
project_ref: missing
mutation_allowed: false
```

If a patch touches Supabase DDL, vector schema, or public table exposure:

- first produce `evidence_needed: Supabase MCP project_ref missing` unless `project_ref` is present;
- do not use real tokens in files or logs;
- run read-only snapshot/import tools before any DDL claim;
- for public schema tables exposed through Data API, include explicit `GRANT` statements and RLS policy checks.

Current Supabase changelog contract to keep in mind:

```text
2026-05-30: new projects require explicit grants for new public tables to be reachable through Data API by default.
2026-10-30: this behavior is planned to apply to existing projects.
RLS does not replace grants; grants decide whether the role can access the table at all.
```

Source: https://supabase.com/changelog

## 9. Patch Acceptance Gates

Accept a patch only when all relevant gates pass:

- active sourceSet proven,
- candidate lane recorded,
- before/after behavior or deletion evidence recorded,
- focused test added or existing focused test named,
- no duplicate FQCN,
- no LangChain4j version impurity,
- no secret pattern growth,
- no PromptBuilder bypass,
- no raw prompt/query/secret trace,
- sourceScoreReport does not regress,
- `checkSourceSetHygiene` does not grow `activeLegacySignals`, `largeActiveSourceGrowth`, `largeActiveSourceMethodGrowth`, `broadCatchGrowth`, or resource backup artifacts.

If a gate fails after a patch, revert that patch only, classify the failure, and move to the next safe candidate.

## 10. Patch Backlog Row Format

Use this row format in reports:

```markdown
| id | lane | file | action | canonical owner | test | verification | result |
|---|---|---|---|---|---|---|---|
| L001 | A_ACTIVE_HARDEN | `path` | failsoft_trace_harden | `owner` | `*FocusedTest*` | `compileJava + hygiene` | DONE/SKIP/HOLD |
```

Action taxonomy:

```text
failsoft_trace_harden
spring_wiring_guard
prompt_boundary_guard
resource_duplicate_merge
placeholder_disable_reason
alias_absorb
prove_delete
test_backfill
tooling_cache_isolation
verify_only
no_patch_needed
evidence_needed
```

## 11. Final Report Format

Return exactly this structure:

```markdown
## 요약
- 2~5줄. 실제 수정 범위, 300 큐 처리량, 검증 상태, 남은 blocker만.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / Git availability / active sourceSets.
- 첨부 설계 pillar 사용 방식.
- metadata-old candidate queue summary.
- Decomposition decision.
- Supabase / Computer Use evidence state.
- missing tools/files는 `evidence_needed`로 명시.

## do02 / Patch Blocks
파일별:
- Observation
- Before snippet, 최대 20줄
- After snippet, 최대 20줄
- Minimal unified diff
- Why this file only
- Lane and candidate ID
- Checkpoint log added, if any
- Secret masking method
- Rollback note

## do03 / Setup Commands
- repo root에서 실행할 정확한 명령.
- Desktop cache isolation 포함.
- network/cache-dependent 명령 분리.
- real external API keys 또는 Supabase project_ref가 필요한 명령 분리.

## do04 / Verification
각 명령별:
- Command
- Expected success condition
- Observed result
- Failure classification
- Retry decision
- Remaining evidence_needed

## do05 / Risks & Next Steps
- 최대 5개.
- SMB/PatchDrop 충돌 위험: H/M/L.
- counterexample/limitation 1개.
- decision factors 최대 3개.
- confidence: L/M/H.
- next single most urgent patch.
```

## 12. One-Shot Instruction

You are Codex on the Desktop canonical root. Use this file as a 9-hour Safe Patch rail. Start by proving the root and sourceSets, read `C:\Users\nninn\Downloads\codex_patch_directive_20260618.md` as the current runtime backlog overlay, regenerate the 300 candidate queue from modified-date metadata, and patch only the first candidate whose lane has active proof and a narrow verification path. Prefer overlay P0 seams when they intersect old active runtime hardening; absorb inactive legacy only through canonical owners; delete only after zombie audit. Keep sourceScoreReport 100/100, LangChain4j 1.0.1, PromptBuilder boundary, secret safety, and Desktop verification intact.
