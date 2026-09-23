# demo-1 GraphRAG-Only 9h Source Patch Directive

Generated: 2026-06-16 Asia/Seoul

목표: 첨부/붙여넣기 자료에서 Dynamic RAG Orchestration Platform에 실제로 이식할 가치가 있는 GraphRAG/KG 타점만 남기고, 현재 Desktop canonical root의 active sourceSet에 맞춘 9시간 Safe Patch 지시서로 실행한다.

이 문서는 광범위한 제품 기획서가 아니다. Codex / Antigravity / Claude 계열 코딩 에이전트가 `C:\AbandonWare\demo-1\demo-1\src`에서 실제 소스 패치를 고를 때 쓰는 근거 기반 실행 지시서다. 외부 문서의 표현보다 live source, Gradle, 테스트, TraceStore 증거가 우선한다.

---

## 0. Evidence Intake

현재 지시서의 기준:

```text
canonical root: C:\AbandonWare\demo-1\demo-1\src
active backend sourceSet: main/java, main/resources
:app sourceSet: app/src/main/java_clean, app/src/main/resources
prompt source owner: agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md
generated prompt: agent-prompts/out/demo1_graphrag_only_source_patch_9h.prompt
```

`rag_only_patch_directive.md`가 제공되면 이 문서보다 먼저 읽고, 상충하면 live repo 증거와 `rag_only_patch_directive.md`의 GraphRAG-only 범위를 우선한다. 파일이 없으면 다음처럼 보고한다.

```text
evidence_needed: rag_only_patch_directive.md / verify with:
Get-ChildItem -Recurse -File -Filter rag_only_patch_directive.md C:\AbandonWare\demo-1\demo-1\src,C:\Users\nninn\.codex\attachments
```

참고 가능한 기존 repo 자산:

```text
agent-prompts/agents/demo1_graphrag_kg_macmini_patchdrop/system_ko.md
agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md
AGENTS.md GraphRAG / KG / PromptBuilder / PatchDrop rules
```

---

## 1. Scope Gate

In scope:

- GraphRAG/KG runtime quality and diagnostics.
- Neo4j/GraphDB manual learning lane already present in source.
- KG chunk write/read boundaries, graph evidence merge, graph score preservation, graph fusion trace keys.
- Brain State, LangGraph snapshot, query-time anchor map, sparse node inference, and graph-backed prompt context only when active call evidence proves them.
- Normalized quantitative scorecards that rank source patch targets before editing.
- Minimal, reversible Java/resource/test patches in active sourceSets.

Out of scope:

- New domain ontologies unrelated to the active platform.
- External schedule, product-planning, and business claims as runtime behavior.
- New DB/vector provider stacks without current source ownership.
- New frameworks, graph libraries, duplicate GraphDB namespaces, or archive grafts.
- Any patch to secret files, local credential setup, or inactive mirror folders.

If a provided attachment contains mixed domain content, extract only generic GraphRAG/KG mechanics such as node, edge, property, relation, triple, graph evidence, graph traversal, graph validation, and explainable path. Discard domain-specific labels unless the active source already owns those labels.

---

## 2. Authority Order

Use this priority order:

1. Current repository files and actual command output.
2. `rag_only_patch_directive.md`, if present.
3. Current task attachments, after GraphRAG-only filtering.
4. `AGENTS.md` and repo prompt packs.
5. Official vendor/API docs for active dependencies.
6. Older memory, prompts, or generated reports.

If evidence is insufficient, write `evidence_needed` and stop that branch. Do not invent source files, provider credentials, graph nodes, tests, or build success.

---

## 3. Non-Negotiables

- Safe Patch only: modify the fewest files and lines needed.
- Confirm active sourceSets before editing.
- Keep Java 17 / Spring Boot 3 / LangChain4j `1.0.1` purity.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)` or the active equivalent boundary.
- Never log raw API keys, client secrets, owner tokens, Authorization headers, cookies, raw full prompts, or full environment dumps.
- Optional GraphDB/Neo4j credentials must fail soft with redacted reason and no fake success.
- Do not add a second GraphRAG architecture when an active seam already exists.
- Do not patch Mac mini/Notebook producer worktrees from Desktop unless the patch is submitted through PatchDrop and reverified on Desktop.

---

## 4. Decomposition Decision

Default decomposition:

```md
Decomposition decision:
- mode: 3-way
- reason: GraphRAG changes cross sourceSet, KG fusion, diagnostics, and verification surfaces.
- skipped_axes: none unless the task is a one-file compile/test failure.
```

Branch A - Source and owner axis:

- Identify active GraphRAG/KG owners.
- Reject inactive aliases, archives, duplicate namespaces, or generated output.
- Confirm tests before production code.

Branch B - Metric and trace axis:

- Normalize candidate value with source-backed scores.
- Require TraceStore/DebugEventStore keys for every fail-soft or fallback path.
- Prefer count/hash/host/path diagnostics over raw text.

Branch C - Failure and verification axis:

- Classify the primary failure before patching.
- Run the narrowest test that can disprove the patch.
- Broaden to Gradle gates only after focused proof.

Direct mode is allowed for exact compile errors, exact failing tests, YAML duplicate keys, or prompt-pack text-only cleanup.

---

## 5. Active GraphRAG Runtime Map

Use live repo evidence first. As of this directive, likely active seams include:

```text
main/java/com/example/lms/graphdb/GraphDbManualLearningService.java
main/java/com/example/lms/graphdb/GraphDbManualLearningController.java
main/java/com/example/lms/graphdb/GraphDbClient.java
main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java
main/java/com/example/lms/service/rag/graph/Neo4jKgChunkWriter.java
main/java/com/example/lms/service/rag/graph/BrainStateService.java
main/java/com/example/lms/service/rag/graph/QueryTimeAnchorMap.java
main/java/com/example/lms/service/rag/graph/SparseNodeInferenceService.java
main/java/com/example/lms/service/rag/kg/Neo4jKnowledgeGraphClient.java
main/java/com/example/lms/service/rag/kg/KgTailPowerMeanScorer.java
main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java
main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java
main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java
main/java/com/example/lms/prompt/PromptBuilder.java
main/java/com/example/lms/search/TraceStore.java
main/java/com/example/lms/debug/DebugEventStore.java
```

Known lane facts to verify before patching:

```text
lane: graphdb_manual_learning
write boundary: Neo4jKgChunkWriter.writeChunks
read boundary: Neo4jKgChunkWriter.readManualEvidence
vector boundary: VectorStoreService.enqueue
simultaneous ingest targets: vector, neo4j
raw text returned: false unless an existing promotion gate allows it
raw identifiers returned: false unless redacted or hashed
admin boundary: /api/admin/graph
non-dry-run proof requires reachable Bolt and non-placeholder credentials
```

Do not create a new `graphdb.*` stack unless live compile/runtime evidence proves the active stack is absent.

---

## 6. Normalized Metric Model

Before patching, score each candidate on a 0.00 to 1.00 scale:

| Metric | Weight | Pass evidence |
|---|---:|---|
| active sourceSet fit | 0.18 | file is in `main/java`, `main/resources`, `app/src/main/java_clean`, or active test root |
| GraphRAG owner fit | 0.18 | touches existing KG/GraphRAG seam, not a new architecture |
| failure evidence | 0.16 | failing test, trace gap, compile error, runtime log, or sourceScore gap |
| user value | 0.14 | improves graph recall, graph evidence explainability, or starvation diagnosis |
| secret/redaction safety | 0.10 | only count/hash/presence diagnostics |
| PromptBuilder boundary safety | 0.08 | prompt context is enriched without final string bypass |
| testability | 0.10 | focused unit/contract test is available or can be added narrowly |
| rollback simplicity | 0.06 | small diff, no new dependency, no migration |

Candidate score:

```text
normalizedScore =
  activeSourceSetFit * 0.18
+ graphRagOwnerFit * 0.18
+ failureEvidence * 0.16
+ userValue * 0.14
+ secretSafety * 0.10
+ promptBoundarySafety * 0.08
+ testability * 0.10
+ rollbackSimplicity * 0.06
```

Patch only candidates with `normalizedScore >= 0.72` unless they are exact P0 build/boot blockers. If two candidates tie, choose the one with the smaller diff and stronger focused test.

Report the scorecard in this format:

```md
| id | target | active proof | failure proof | normalizedScore | decision |
|---|---|---|---|---:|---|
| RAG-01 | ... | ... | ... | 0.84 | patch |
```

---

## 7. Patch Backlog

### RAG-00 - Intake and contamination guard

Goal: keep the pass GraphRAG-only.

Observe:

- `rag_only_patch_directive.md` presence.
- current root/sourceSets.
- prompt-pack contamination terms in the target directive.

Patch:

- Remove unrelated domain/business/vector-provider branches from the prompt pack only.
- Do not edit Java code in this step.

Verify:

```powershell
$blockedTerms = @("Y"+"-Biologics","Y"+"bio","We"+"Aid","W"+"BS","P"+"RD","IL"+"-2","pg"+"vector")
$pattern = ($blockedTerms | ForEach-Object { [regex]::Escape($_) }) -join "|"
Select-String -Path ".\agent-prompts\agents\demo1_graphrag_only_source_patch_9h\*.md",".\agent-prompts\out\demo1_graphrag_only_source_patch_9h.prompt" -Pattern $pattern -ErrorAction SilentlyContinue
```

Expected: no matches.

### RAG-01 - KG score preservation

Hypothesis: graph evidence can be retrieved but lose its score/selection reason before final merge.

Target files:

```text
KnowledgeGraphHandler.java
UnifiedRagOrchestrator.java
Neo4jKnowledgeGraphClient.java
KgTailPowerMeanScorer.java
TraceStore.java call sites
```

Patch only if source/test evidence shows score loss.

Required trace keys:

```text
kg.evidence.input.count
kg.evidence.selected.count
kg.evidence.score.min
kg.evidence.score.max
kg.evidence.score.method
kg.evidence.drop.reason
```

Focused tests:

```powershell
.\gradlew.bat test --tests "*KnowledgeGraphHandler*" --tests "*KgTailPowerMean*" --no-daemon --project-cache-dir "$ProjectCache"
```

### RAG-02 - KG fusion trace aliases

Hypothesis: answer fallback cannot explain whether graph, vector, web, or memory supplied the selected context.

Target files:

```text
UnifiedRagOrchestrator.java
DynamicRetrievalHandlerChain.java
KnowledgeGraphHandler.java
TraceStore.java call sites
```

Required trace keys:

```text
rag.source.graph.count
rag.source.vector.count
rag.source.web.count
rag.source.memory.count
rag.fusion.selected.graph.count
rag.fusion.zeroGraphReason
```

Patch only after confirming keys are absent or inconsistent.

### RAG-03 - LangGraph snapshot visibility

Hypothesis: graph execution state exists but is not visible enough to debug branch skips or empty graph outputs.

Target files:

```text
RagGraphExecutor.java
TraceStore.java call sites
DebugEventStore.java call sites
```

Allowed diagnostics:

```text
graph.node.count
graph.edge.count
graph.step.count
graph.branch.skipped.reason
graph.snapshot.redacted=true
```

Forbidden diagnostics:

```text
raw prompt
raw evidence body
credential values
full environment dump
```

### RAG-04 - Brain State and anchor fallback diagnostics

Hypothesis: Brain State, anchor map, or sparse node inference failures silently degrade into ordinary vector-only output.

Target files:

```text
BrainStateService.java
QueryTimeAnchorMap.java
SparseNodeInferenceService.java
PromptBuilder.java call sites
```

Required trace keys:

```text
brainState.enabled
brainState.item.count
brainState.disabled.reason
anchorMap.input.count
anchorMap.selected.count
sparseNodeInference.used
sparseNodeInference.fallback.reason
```

Prompt rule: enrich `PromptContext`; do not manually concatenate final prompts.

### RAG-05 - GraphDB manual learning lane proof

Hypothesis: manual learning writes to vector and Neo4j paths, but dry-run/non-dry-run state is not safely visible.

Target files:

```text
GraphDbManualLearningService.java
GraphDbManualLearningController.java
Neo4jKgChunkWriter.java
GraphRagChunkingService.java
```

Required trace/log fields:

```text
graphdb.lane
graphdb.dryRun
graphdb.write.vector.count
graphdb.write.neo4j.count
graphdb.disabled.reason
graphdb.bolt.reachable
graphdb.credentials.present
```

Only log credential presence, never values.

### RAG-06 - GraphRAG fail-soft and cancellation neutrality

Hypothesis: timeout, cancellation, or provider-disabled state in web/vector stages poisons graph fallback or graph merge.

Target files:

```text
HybridWebSearchProvider.java
NightmareBreaker.java
QueryTransformer.java
KnowledgeGraphHandler.java
UnifiedRagOrchestrator.java
```

Patch only if failure evidence crosses into GraphRAG. Do not broaden a provider patch into graph code without trace proof.

Required trace keys:

```text
graphFallback.used
graphFallback.reason
graphFallback.blockedByProviderState
cancel.interrupt.cleaned
queryTransformer.rawFallback.used
```

### RAG-07 - PromptBuilder boundary enforcement

Hypothesis: graph evidence may be appended directly into final prompts outside the builder.

Target files:

```text
PromptBuilder.java
PromptContext.java
ChatService / LLM call sites proven active by rg
```

Search:

```powershell
rg -n "PromptBuilder|PromptContext|build\\(|StringBuilder|append\\(|chat\\(|generate" main\java\com\example\lms main\java\ai\abandonware
```

Patch only active violations. Add tests around context enrichment rather than snapshotting full prompts.

---

## 8. Verification Ladder

Run only available commands and never claim success without output.

Windows setup:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
$Env:AWX_AGENT_HOST = "desktop"
$Env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$Env:AWX_BUILD_HOST_ID = "desktop"
$Env:GRADLE_USER_HOME = "$Env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$Env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $Env:GRADLE_USER_HOME,$ProjectCache | Out-Null
```

Prompt-pack verification:

```powershell
python agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_graphrag_only_source_patch_9h
python - <<'PY'
from pathlib import Path
src = Path('agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md').read_text(encoding='utf-8').replace('\r\n','\n')
out = Path('agent-prompts/out/demo1_graphrag_only_source_patch_9h.prompt').read_text(encoding='utf-8').replace('\r\n','\n')
print('[AWX][prompt] graphrag_only_output_matches_source=', src == out)
raise SystemExit(0 if src == out else 1)
PY
```

Source guard verification:

```powershell
.\gradlew.bat sourceScoreReport checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
```

Focused GraphRAG verification after Java patches:

```powershell
.\gradlew.bat test --tests "*GraphRag*" --tests "*KnowledgeGraph*" --tests "*BrainState*" --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
```

Secret scan, count only:

```powershell
$hits = Select-String -Path ".\main\java\**\*.java",".\main\resources\**\*.yml",".\main\resources\**\*.yaml",".\agent-prompts\agents\demo1_graphrag_only_source_patch_9h\*.md" `
  -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" `
  -Recurse -EA SilentlyContinue
Write-Host "[AWX][desktop][security] secretHits=$($hits.Count)"
```

---

## 9. Failure Classifier

Pick one primary class:

```text
wrong-sourceset
prompt-contamination
missing-directive
missing-task
langchain4j-version-purity
duplicate-class-fqcn
cannot-find-symbol
spring-bean
spring-bind
prompt-rule-violation
secret-leak-risk
graphdb-disabled
graphdb-credential-missing
graphdb-bolt-unreachable
graph-evidence-empty
graph-after-filter-starvation
graph-score-lost
trace-key-missing
timeout
rate-limit
cancel-toxicity
patch-drop-pending
index-lock-conflict
smb-conflict-risk
gradle-cache-collision
other
```

Retry only once per blocker class and only after a specific patch.

---

## 10. 9-Hour Operating Loop

Budget is agent workflow time, not product runtime behavior.

```text
00:00-00:30 intake, sourceSet proof, rag_only_patch_directive.md lookup, PatchDrop/index lock checks
00:30-01:30 score candidates with the normalized metric model
01:30-03:00 RAG-01 or highest P0 GraphRAG candidate, RED/GREEN focused test
03:00-04:30 RAG-02/RAG-03 trace visibility candidate, focused test
04:30-06:00 RAG-04/RAG-05 fallback or GraphDB lane proof candidate
06:00-07:15 RAG-06/RAG-07 only if live evidence proves cross-stage failure
07:15-08:15 Gradle source guards and focused regression sweep
08:15-09:00 final score delta, rollback notes, next single patch
```

Stop early when:

- the next blocker class changes,
- active sourceSet proof is missing,
- PatchDrop has an ambiguous pending bundle,
- a required credential is missing and no dry-run path exists,
- the only remaining work would require broad architecture or new dependency.

---

## 11. Final Report Format

Return exactly:

```md
## 요약
- 실제 수정 범위와 검증 상태만 2~5줄.

## do01 / Observation
- 실행한 명령.
- 핵심 로그 최대 10줄.
- repo root / branch / active sourceSets.
- Decomposition decision.
- rag_only_patch_directive.md 사용 여부.
- evidence_needed.

## do02 / Normalized Score
| id | target | active proof | failure proof | normalizedScore | decision |
|---|---|---|---|---:|---|

## do03 / Patch Blocks
파일별:
- Observation
- Before snippet, 최대 20줄
- After snippet, 최대 20줄
- Minimal unified diff
- Why this file only
- Trace keys added, if any
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

## do05 / Risks & Next Steps
- 최대 5개.
- SMB/PatchDrop risk: H/M/L.
- counterexample/limitation 1개.
- confidence: L/M/H.
- next single most urgent patch.
```

Never claim build, boot, provider, GraphDB, Neo4j, or browser success unless current command output proves it.

[DONE]
