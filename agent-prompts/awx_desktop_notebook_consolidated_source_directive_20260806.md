# AWX Desktop Notebook Consolidated Source Program — 2026-08-06

## Authority And Current Root

```yaml
contractVersion: demo1.notebook-directive-consolidation.v1
programId: awx-desktop-notebook-consolidated-source-20260806
programIdMutable: false
latestRefreshId: awx-desktop-notebook-consolidated-source-20260807-r2
latestRefreshApprovedByUser: true
latestRefreshPrecedence: current-canonical-refresh-2026-08-07
canonicalExecutionRoot: 'C:\AbandonWare\demo-1\demo-1\src'
sourceOwner: desktop
activeSourceSets:
  - main/java
  - main/resources
  - app/src/main/java_clean
  - app/src/main/resources
inputAuthority: supporting_only
authorizedArtifactWrites:
  - agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
sourceMutationAuthorizedByThisProgram: false
retirementAuthorizedByThisProgram: false
commitPushDeployAuthorized: false
externalMutationAuthorized: false
desktopFinalProof: evidence_needed
artifactPublicationProof: pending-post-write-validation
evidence_needed: select one source work unit, then freeze its current target preimages and obtain a stable three-way APPLY before its RED test
```

The immutable program ID identifies this document only. Inventory inclusion does
not grant application-source authority, retirement eligibility, provider
authority, Browser proof, Supabase authority, or runtime-lineage proof. Current
C-root source and tests outrank all Notebook observations. Every future
source-changing work unit requires its own stable three-way `APPLY`, Desktop
source-owner lease, immediate preimage comparison, RED, GREEN, rollback
checkpoint, and current proof.

## DirectiveInventory

```yaml
schemaVersion: demo1.notebook-directive-inventory.v1
canonicalExecutionRoot: 'C:\AbandonWare\demo-1\demo-1\src'
inventoryCount: 15
inventorySnapshotRole: historical-baseline-inherited-by-latest-refresh
hashAlgorithm: SHA-256
readEncoding: UTF-8
hashCheckResult: pass
candidates:
  - path: data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    sha256: 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    bytes: 12605
    gitTracking: untracked
    provenance: notebook
    format: json
    directiveIds: [G-20260802-RAG-TAIL-WEB-01]
    targetFiles: []
    inclusionReason: standalone-notebook-directive
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md
    sha256: 89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110
    bytes: 24516
    gitTracking: untracked
    provenance: prompt
    format: markdown
    directiveIds: [G-20260802-RAG-TAIL-WEB-01, SD-20260802-RAG-TAIL-WEB-01]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java, main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java, main/resources/tool_manifest__kchat_gpt_pro.json]
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md
    sha256: 12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB
    bytes: 25284
    gitTracking: untracked
    provenance: notebook
    format: markdown
    directiveIds: [AWX-AGENT-CODE-EVIDENCE-GATE-20260804, AWX-DESKTOP-AGENT-CODE-EVIDENCE-GATE-V1]
    targetFiles: [build.gradle.kts, scripts/agent_code_evidence_gate.py, scripts/test_agent_code_evidence_gate.py, .agents/skills/demo1-agent-code-evidence-gate/SKILL.md]
    inclusionReason: standalone-notebook-directive
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: __reports__/notebook-risk-utility-triad-2026-08-04.json
    sha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
    bytes: 17731
    gitTracking: ignored
    provenance: notebook
    format: json
    directiveIds: [RISK-UTILITY-TRIAD-20260804]
    targetFiles: []
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
    sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
    bytes: 13946
    gitTracking: ignored
    provenance: desktop
    format: markdown
    directiveIds: [GD-RISK-UTILITY-SHADOW-20260804, SD-RISK-UTILITY-SHADOW-20260804]
    targetFiles: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java]
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json
    sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F
    bytes: 14820
    gitTracking: untracked
    provenance: notebook
    format: json
    directiveIds: [DPA-LINEAGE-P0-20260802]
    targetFiles: []
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md
    sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1
    bytes: 31203
    gitTracking: untracked
    provenance: notebook
    format: markdown
    directiveIds: [DPA-LINEAGE-P0-20260802, SD-DPA-LINEAGE-P0-20260802]
    targetFiles: [main/java/com/example/lms/prompt/StandardPromptBuilder.java, main/java/com/example/lms/service/ChatWorkflow.java, main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java, main/resources/application-llm.yaml]
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md
    sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6
    bytes: 133942
    gitTracking: untracked
    provenance: notebook
    format: markdown
    directiveIds: [DPA-LINEAGE-P0-20260802, SD-DPA-LINEAGE-P0-20260802]
    targetFiles: [main/java/com/example/lms/prompt/assembly, main/resources/prompts/assembly, src/test/java/com/example/lms/prompt/assembly]
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md
    sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160
    bytes: 20411
    gitTracking: untracked
    provenance: notebook-patchdrop-intent
    format: markdown
    directiveIds: [NEXT-BFF-9H-20260702]
    targetFiles: [frontend, main/resources/templates/chat-ui.html, main/resources/static/js/chat.js, main/resources/static/css/chat-style.css]
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md
    sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424
    bytes: 17004
    gitTracking: untracked
    provenance: notebook-patchdrop-intent
    format: markdown
    directiveIds: [MAIN-CHAT-NEXT-PORT-20260702]
    targetFiles: [main/resources/static/js/chat.js, main/resources/templates/chat-ui.html, main/resources/static/css/chat-style.css]
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md
    sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B
    bytes: 29636
    gitTracking: untracked
    provenance: notebook
    format: markdown
    directiveIds: [AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-20260805, AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2]
    targetFiles: [main/resources/static/js/chat.js, main/resources/templates/chat-ui.html, main/resources/static/css/chat-style.css]
    inclusionReason: standalone-notebook-directive
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md
    sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19
    bytes: 22750
    gitTracking: untracked
    provenance: prompt
    format: markdown
    directiveIds: [AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805]
    targetFiles: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js, main/resources/templates/chat-ui.html]
    inclusionReason: target-specific-red-directive
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md
    sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491
    bytes: 29817
    gitTracking: untracked
    provenance: prompt
    format: markdown
    directiveIds: [AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805]
    targetFiles: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    inclusionReason: target-specific-red-directive
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md
    sha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D
    bytes: 29168
    gitTracking: untracked
    provenance: prompt
    format: markdown
    directiveIds: [AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805]
    targetFiles: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    inclusionReason: target-specific-red-directive
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
  - path: __patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md
    sha256: B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B
    bytes: 2371
    gitTracking: untracked
    provenance: notebook-patchdrop-intent
    format: markdown
    directiveIds: [DESKTOP-SOURCE-EDIT-QUICK-PROMPT]
    targetFiles: []
    inclusionReason: approved-canonical-input
    lifecycleState: reconciled
    accessibility: readable-utf8
    retirementEligibility: hold
excludedFromRetirement:
  - reason: sealed-canary
  - reason: protected-june-5-acl
  - reason: reusable-prompt
  - reason: patchdrop-sidecar
```

## RequirementLedger

```yaml
schemaVersion: demo1.notebook-requirement-ledger.v1
sourcePointers:
  I9-GOAL: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Goal"}
  I9-EVIDENCE: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Current Source Evidence To Reconfirm"}
  I9-RULES: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Non-Negotiable Rules"}
  I9-DECOMP: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Decomposition Decision"}
  I9-END: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Target End State"}
  I9-SHAPE: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Suggested Next.js File Shape"}
  I9-BFF: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## BFF Route Contract"}
  I9-UI: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## UI Design Direction"}
  I9-P0: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 0: Desktop Preflight, 30-45 min"}
  I9-P1: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 1: Select Next Lane, 30-60 min"}
  I9-P2: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 2: Backend Contract Map, 45-60 min"}
  I9-P3: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 3: BFF Routes, 90-120 min"}
  I9-P4: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 4: Chat UI, 120-180 min"}
  I9-P5: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 5: Preserve Current Spring UI Fallback, 30-45 min"}
  I9-P6: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 6: Optional WebSocket, 45-90 min"}
  I9-P7: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 7: Verification And Browser Smoke, 60-90 min"}
  I9-P8: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "### Phase 8: Secret Scan And Report, 30 min"}
  I9-ACCEPT: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Acceptance Criteria"}
  I9-REPORT: {path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md, sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160, heading: "## Final Report Required Shape"}
  I10-GOAL: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Goal"}
  I10-FIRST: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Required First Move On Desktop"}
  I10-READS: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Required Reads"}
  I10-EVIDENCE: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Notebook Evidence To Reconfirm"}
  I10-SCOPE: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Scope"}
  I10-PARITY: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 1. Build A Parity Ledger First"}
  I10-HEADERS: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 2. Port Correlation Headers Into Main Chat Calls"}
  I10-SSE: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 3. Port SSE Parser Resilience"}
  I10-CANCEL: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 4. Port Cancel And Retry Semantics"}
  I10-SESSIONS: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 5. Port Session List/Detail Handling"}
  I10-EVIDENCEUI: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 6. Port Evidence Panel Behavior Without Losing Existing Debug UI"}
  I10-RAG: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 7. RAG Query/Probe Handling"}
  I10-BACKEND: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 8. Backend Patch Rules"}
  I10-NEXTGATE: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "### 9. Next Frontend Follow-Up Gate"}
  I10-VERIFY: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Verification Commands"}
  I10-SECRET: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Secret Scan"}
  I10-ACCEPT: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Acceptance Criteria"}
  I10-HOLD: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Failure Classifiers"}
  I10-REPORT: {path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md, sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424, heading: "## Final Report Shape"}
  I11-INTAKE: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Intake Contract"}
  I11-SNAPSHOT: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## EvidenceSnapshot"}
  I11-POSITIVE: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "### POSITIVE_QUERY — PositivePacket"}
  I11-NEGATIVE: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "### NEGATIVE_QUERY — NegativePacket"}
  I11-NEUTRAL: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "### NEUTRAL_QUERY — NeutralVerdict"}
  I11-GOAL: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## GoalContract"}
  I11-SOURCE: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## SourceDirective"}
  I11-BROWSER: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Browser boundary"}
  I11-P1: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 1"}
  I11-P2: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 2"}
  I11-P3: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 3"}
  I11-P4: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 4"}
  I11-P5: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 5"}
  I11-P6: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 6"}
  I11-P7: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 7"}
  I11-P8: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 8"}
  I11-P9: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Paste This Into Desktop Codex / Phase 9"}
  I11-VERIFY: {path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md, sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B, heading: "## Verification Status Of This Notebook Handoff"}
requirements:
  - requirementId: ND-RAG-WEB-001
    normalizedRequirement: "Directive G-20260802-RAG-TAIL-WEB-01 is a conditional Desktop goal; its 540-minute cap is a maximum budget, not a source-write authority."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## GoalContract / goalId"
    category: safety
    status: held
    liveEvidence: ["input-2 SHA-256 revalidated", "Desktop root/branch/worktree/lease proof not run in this fragment"]
    targetFiles: []
    redTests: ["desktop-preflight-missing"]
    greenTests: ["Desktop preflight and scoped RED are current before any declared-file change"]
    verificationCommands: ["git branch --show-current", "git worktree list", "git status --short", "Test-Path .git\\index.lock"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory preflight; no mutation from goal text alone"
    nonGoal: "automatic source patching"
    rollback: "no patch to roll back"
    holdCondition: "index-lock-conflict, dirty-target-overlap, pending PatchDrop, or ownership evidence absent"
  - requirementId: ND-RAG-WEB-002
    normalizedRequirement: "Directive SD-20260802-RAG-TAIL-WEB-01 retains Desktop as source owner and requires active source-set reconfirmation at the same revision."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## SourceDirective / directiveId and activeSourceSets"
    category: source
    status: evidence_needed
    liveEvidence: ["expected root main/java, main/resources, src/test/java only", "current source-set proof not captured"]
    targetFiles: [main/java, main/resources, src/test/java]
    redTests: ["wrong-sourceset"]
    greenTests: ["checkSourceSetHygiene passes at current revision"]
    verificationCommands: [".\\gradlew.bat checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory Desktop ownership; forbidden inactive/reference-root edits"
    nonGoal: "editing app/src/main/java or aliases"
    rollback: "restore only declared-file hunks after captured preimages"
    holdCondition: "active-sourceset-uncertain or preflight verdict is not stable APPLY"
  - requirementId: ND-RAG-WEB-003
    normalizedRequirement: "Final RAG prompt construction remains exclusively at PromptContext -> PromptBuilder; no web tool or strategy assembles a final prompt."
    sourcePaths: [data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json, agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44, 89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "input-1 /packets/NEUTRAL_QUERY/selectedOrRewrittenGoal; input-2 ## Global Constraints and ## Task 5"
    category: safety
    status: evidence_needed
    liveEvidence: ["Notebook claims StandardPromptBuilder observation only; no current Desktop boundary test"]
    targetFiles: [main/java/com/example/lms/prompt/PromptBuilder.java, src/test/java/**/PromptBuilderBoundaryTest.java]
    redTests: ["prompt-boundary-bypass"]
    greenTests: ["PromptBuilder boundary test passes and no final prompt is constructed in WebSearchTool/gateway"]
    verificationCommands: [".\\gradlew.bat test --tests '*PromptBuilderBoundaryTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory boundary preservation; forbidden ad hoc final-prompt concatenation"
    nonGoal: "PromptBuilder implementation rewrite"
    rollback: "revert only any proven boundary-violating declared hunk"
    holdCondition: "boundary test missing, failing, or source ownership is unproven"
  - requirementId: ND-RAG-WEB-004
    normalizedRequirement: "CounterEvidenceRetrieveTool keeps exactly AUTHORITATIVE_CONSTRAINT, ALTERNATIVE_OR_UNKNOWN, and PROVENANCE_AND_TIME slots and remains local-only."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Global Constraints; ## Task 5"
    category: verification
    status: evidence_needed
    liveEvidence: ["input asserts retrieveStrictLocal characterization; no current Desktop result"]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/ops/CounterEvidenceRetrieveTool.java, src/test/java/**/CounterEvidenceRetrieveToolTest.java]
    redTests: ["three-slot-exactness-missing", "local-path-calls-web"]
    greenTests: ["three exact slots and retrieveStrictLocal remain; no local counter invocation calls web"]
    verificationCommands: [".\\gradlew.bat test --tests '*CounterEvidenceRetrieveToolTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory exact local contract; forbidden silent hybrid/web switch"
    nonGoal: "new query planner"
    rollback: "revert only a proven production hunk and rerun characterization"
    holdCondition: "RED does not prove a specific defect"
  - requirementId: ND-RAG-WEB-005
    normalizedRequirement: "web.search remains a separately authorized external lane: manifest, registry, policy, web.get scope, and owner-token gates must agree."
    sourcePaths: [data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json, agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44, 89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "input-1 /packets/POSITIVE_QUERY/scenarioWorlds/0; input-2 ## GoalContract and ## Task 4"
    category: safety
    status: evidence_needed
    liveEvidence: ["Notebook observed manifest disabled with legacy_reference_not_exposed; live Desktop registry/policy state not proven"]
    targetFiles: [main/resources/tool_manifest__kchat_gpt_pro.json, src/test/java/**/AgentWebSearchToolConditionalWiringTest.java, src/test/java/**/AgentToolOpsConfigContextTest.java, src/test/java/**/InternalAgentToolControllerSecurityTest.java]
    redTests: ["manifest-registry-mismatch", "gateway-absent-invocation-allowed", "policy-denial-missing"]
    greenTests: ["gateway-present manifest/registry agreement; gateway-absent denial; existing authority required"]
    verificationCommands: [".\\gradlew.bat test --tests '*AgentWebSearchToolConditionalWiringTest' --tests '*AgentToolOpsConfigContextTest' --tests '*InternalAgentToolControllerSecurityTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory explicit authorization; forbidden unregistered or policy-bypassing web call"
    nonGoal: "enabling rag.retrieve"
    rollback: "restore web.search.enabled=false and only declared production hunks"
    holdCondition: "registry/policy proof absent, authorization bypass, or manifest contract drift"
  - requirementId: ND-RAG-WEB-006
    normalizedRequirement: "Weak, tail, or dissent signals have decisionAuthority=probe_only; correlated provenance counts once and unresolved contradiction yields HOLD."
    sourcePaths: [data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json, agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44, 89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "input-1 /packets/NEGATIVE_QUERY/scenarioAttacks/2; input-2 ## Global Constraints and ## Task 5"
    category: safety
    status: evidence_needed
    liveEvidence: ["Notebook only; current verifier behavior not tested"]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/ops/CausalProbeEvaluateTool.java, main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java, src/test/java/**/CausalProbeEvaluateToolTest.java, src/test/java/**/EvidenceCoherenceVerifyToolTest.java]
    redTests: ["weak-signal-decisive", "correlated-copies-multiple-votes", "unresolved-conflict-not-held"]
    greenTests: ["weak IDs absent from decisiveEvidenceIds; copies count once; conflict is HOLD/REJECT"]
    verificationCommands: [".\\gradlew.bat test --tests '*CausalProbeEvaluateToolTest' --tests '*EvidenceCoherenceVerifyToolTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory verifier-only verdict authority; forbidden tail-score truth shortcut"
    nonGoal: "HYPERNOVA threshold tuning or ExtremeZ fan-out change"
    rollback: "revert only a RED-proven declared hunk"
    holdCondition: "cross-subsystem defect is detected or focused tests are unavailable"
  - requirementId: ND-RAG-WEB-007
    normalizedRequirement: "WebSearchTool rejects a blank query before an external call and clamps topK to 1..20 with default 5."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Task 2; ## Task 3"
    category: source
    status: unimplemented
    liveEvidence: ["directive assertion only; RED fixture not run"]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java, src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java]
    redTests: ["blank-query-not-rejected", "topK-over-20-not-clamped"]
    greenTests: ["blank query returns web_search_query_required with zero gateway calls; gateway sees max 20"]
    verificationCommands: [".\\gradlew.bat test --tests '*AgentWebSearchToolConditionalWiringTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory bounded inputs; forbidden outbound call for blank query"
    nonGoal: "WebSearchGateway public-signature change"
    rollback: "revert declared WebSearchTool hunk only"
    holdCondition: "no valid RED, target preimage changed, or gateway contract unknown"
  - requirementId: ND-RAG-WEB-008
    normalizedRequirement: "Web invocation telemetry is hash/count/timing/outcome only: queryHash, queryLength, optionsHash, requestHash, requestedCount, returnedCount, elapsedMs, outcome, providerAttemptCount, and providerResponseCount."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Task 3 / WebSearchTool trace keys"
    category: safety
    status: evidence_needed
    liveEvidence: ["Notebook explicitly says complete request/options and provider lineage is unproven"]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java, src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java]
    redTests: ["hash-lineage-missing", "raw-query-or-secret-trace"]
    greenTests: ["all allowlisted keys exist; trace contains no raw query/snippet/credential/headers"]
    verificationCommands: [".\\gradlew.bat test --tests '*AgentWebSearchToolConditionalWiringTest' --no-daemon --project-cache-dir $ProjectCache", "powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\git_secret_guard.ps1 -Mode manual -Path <changed-rag-files>"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory redacted bounded telemetry; forbidden raw query, snippet, URL-sensitive data, API key, header, cookie, token, or environment value"
    nonGoal: "unbounded DebugEventStore/TraceStore payloads"
    rollback: "revert telemetry hunk and retain redacted rejected-path evidence"
    holdCondition: "redaction test missing or any secret risk"
  - requirementId: ND-RAG-WEB-009
    normalizedRequirement: "AcmeAICoreGateway emits one bounded redacted attempt row per provider with providerIdHash, attempted, responseState, returnedCount, elapsedMs, and errorType."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Task 2 gateway RED; ## Task 3 gateway hardening"
    category: source
    status: unimplemented
    liveEvidence: ["input says provider attempt/response lineage incomplete"]
    targetFiles: [main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java, src/test/java/com/abandonware/ai/agent/integrations/AcmeAICoreGatewayLineageTest.java]
    redTests: ["success-empty-throwing-provider-rows-missing", "provider-label-or-exception-leaked"]
    greenTests: ["one success, empty, and throwing provider each yield bounded row; states are success/empty/failed"]
    verificationCommands: [".\\gradlew.bat test --tests '*AcmeAICoreGatewayLineageTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory bounded per-provider lineage; forbidden raw provider labels, exception text, query, snippet, or credential"
    nonGoal: "new web-provider abstraction"
    rollback: "revert only gateway lineage hunk"
    holdCondition: "no existing test owner and new test scope is not approved by Desktop preflight"
  - requirementId: ND-RAG-WEB-010
    normalizedRequirement: "Provider disabled, empty, after-filter starvation, timeout, rate-limit, and typed generic failures remain separate where the owning provider has evidence."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Global Constraints; ## Task 3"
    category: verification
    status: evidence_needed
    liveEvidence: ["input prohibits inferring provider-specific reason from generic exception"]
    targetFiles: [main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java, main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java]
    redTests: ["provider-states-collapsed", "generic-exception-infers-provider-reason"]
    greenTests: ["typed owner-proven reasons stay distinct; generic row uses bounded failed classification"]
    verificationCommands: [".\\gradlew.bat test --tests '*AcmeAICoreGatewayLineageTest' --tests '*AgentWebSearchToolConditionalWiringTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory fail-soft distinction; forbidden fake or inferred provider taxonomy"
    nonGoal: "provider replacement"
    rollback: "restore prior typed handling only"
    holdCondition: "owner evidence does not expose a state or focused test cannot classify it"
  - requirementId: ND-RAG-WEB-011
    normalizedRequirement: "Web results are additive probe-only metadata with normalizationRequired=true and verificationGatePassed=false until the caller supplies provenance, time, directness, authority, independence, coverage, and relation."
    sourcePaths: [data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json, agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44, 89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "input-1 /packets/POSITIVE_QUERY/scenarioWorlds/1; input-2 ## Task 3 and ## Task 5"
    category: safety
    status: evidence_needed
    liveEvidence: ["input-1 and input-2 agree semantically; current Desktop normalization test is not run"]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java, main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java]
    redTests: ["web-result-verdict-ready", "normalization-fields-omitted"]
    greenTests: ["web result remains probe_only and cannot enter verifier/prompt without atomic normalization"]
    verificationCommands: [".\\gradlew.bat test --tests '*EvidenceCoherenceVerifyToolTest' --tests '*AgentWebSearchToolConditionalWiringTest' --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory normalization gate; forbidden auto-promotion into verifier or prompt"
    nonGoal: "verdict authority for unnormalized web output"
    rollback: "restore probe-only flags and reject current hunk"
    holdCondition: "normalization test absent, target preimage changes, or caller boundary is unproven"
  - requirementId: ND-RAG-WEB-012
    normalizedRequirement: "Only the declared RAG targets may change after preflight: WebSearchTool, AcmeAICoreGateway, web.search manifest, and their owned focused tests; causal/counter/coherence classes are characterization-only unless RED proves a defect."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## SourceDirective / targetFiles"
    category: source
    status: held
    liveEvidence: ["declared targets readable; no current target overlap or preimage evidence"]
    targetFiles: [main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java, main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java, main/resources/tool_manifest__kchat_gpt_pro.json, src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java, src/test/java/com/example/lms/config/AgentToolOpsConfigContextTest.java, src/test/java/com/example/lms/api/internal/InternalAgentToolControllerSecurityTest.java]
    redTests: ["undeclared-target-needed", "characterization-only-target-defect-unproven"]
    greenTests: ["diff path list contains only preflight-approved declared targets"]
    verificationCommands: ["git diff --name-only -- <declared-rag-targets>", "git status --short -- <declared-rag-targets>"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory minimal declared surface; forbidden aliases, mirrors, archives, generated output, public API, DB, secrets, and PromptBuilder bypass"
    nonGoal: "new RAG framework, Doctor DB, query planner, score fuser, or public route"
    rollback: "revert only this session declared-file hunks; never git reset --hard"
    holdCondition: "dirty-target-overlap, changed preimage, or RED expands to cross-subsystem work"
  - requirementId: ND-RAG-WEB-013
    normalizedRequirement: "RAG runtime lineage may be APPLY only when the same request has an options hash plus at least one matching provider attempt/response row; compile/test/boot/UI alone are insufficient."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Task 6 / live provider proof"
    category: runtime
    status: held
    liveEvidence: ["credential-backed request-specific provider evidence not observed"]
    targetFiles: []
    redTests: ["runtime-lineage-missing"]
    greenTests: ["same request correlation yields optionsHash and matching attempt/response count greater than zero"]
    verificationCommands: ["run one authorized configured-provider request and inspect redacted count/hash-only lineage"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory request-specific lineage; forbidden success claim from delivery/build/UI alone"
    nonGoal: "printing or persisting configured credentials"
    rollback: "set runtimeLineageVerdict=HOLD; no source rollback implied"
    holdCondition: "credentials absent, provider disabled, wireAttemptCoverage not observed, or correlation incomplete"
  - requirementId: ND-RAG-WEB-014
    normalizedRequirement: "RAG focused validation must preserve LangChain4j 1.0.1 and run source-set hygiene, focused causal/counter/coherence/web/security/PromptBuilder tests, then :app:classes and bootJar only after focused GREEN."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## Task 1 and ## Task 6"
    category: verification
    status: not_applicable
    liveEvidence: ["no RAG patch is authorized in this fragment"]
    targetFiles: []
    redTests: ["mixed-langchain4j-version", "focused-test-missing-or-failing"]
    greenTests: ["purity, hygiene, compileJava, named tests, :app:classes, and bootJar pass in order"]
    verificationCommands: [".\\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava --no-daemon --project-cache-dir $ProjectCache", ".\\gradlew.bat :app:classes bootJar --no-daemon --project-cache-dir $ProjectCache"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory current Desktop output; forbidden PASS for nonexistent owner test"
    nonGoal: "broad test or provider proof before focused RED/GREEN"
    rollback: "preserve narrow proof and roll back only failing work-unit hunk"
    holdCondition: "mixed/non-1.0.1 version, missing Java, or focused verification failure"
  - requirementId: ND-RAG-WEB-015
    normalizedRequirement: "RAG non-goals remain explicit: no LoRA/image work, DB/schema, public endpoint, provider replacement, new orchestration framework, automatic patching, commit, push, deploy, persisted environment change, or database mutation."
    sourcePaths: [agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md]
    sourceHashes: [89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110]
    sourceLocator: "## GoalContract / nonGoals; ## Global Constraints"
    category: non-goal
    status: verified
    liveEvidence: ["this fragment creates documentation only and performs none of these actions"]
    targetFiles: []
    redTests: ["scope-expansion-detected"]
    greenTests: ["diff and command audit show only approved ledger artifact creation"]
    verificationCommands: ["git status --short -- .superpowers/sdd/2026-08-06-notebook-directive-consolidation-and-chat-repair/task-2-fragment-rag-gate.md"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "forbidden scope expansion"
    nonGoal: "the listed prohibited domains and operations"
    rollback: "remove only a future unauthorized hunk after review"
    holdCondition: "a requested action expands beyond the declared family"
  - requirementId: ND-AGENT-GATE-001
    normalizedRequirement: "Directive AWX-DESKTOP-AGENT-CODE-EVIDENCE-GATE-V1 is Desktop-owned and is APPLY only for handoff/preflight; Notebook grants no application-source mutation."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## GoalContract / verdict and authorizedMutationSurface; ## SourceDirective / directiveId"
    category: safety
    status: held
    liveEvidence: ["input-3 says authorizedMutation=false on Notebook and provenRoot/provenBranch=evidence_needed"]
    targetFiles: []
    redTests: ["source-write-attempt-before-stable-APPLY"]
    greenTests: ["Desktop root/branch/target/sourceSet/lease/preimage are proved and separate three-way preflight is stable APPLY"]
    verificationCommands: ["git rev-parse --show-toplevel", "git branch --show-current", "git worktree list", "git status --short", "Test-Path .git\\index.lock"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory Desktop preflight; forbidden Notebook or automatic source mutation"
    nonGoal: "apply, rollback, or deploy automation"
    rollback: "no mutation allowed before proof"
    holdCondition: "any root/branch/target/sourceSet/lease/preimage proof is absent"
  - requirementId: ND-AGENT-GATE-002
    normalizedRequirement: "The gate reuses sourceHealthValidationLoop, demo1-docker-autograder, source-owner guard, count-only secret scanning, TraceStore, and DebugEventStore before considering a new framework."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## instructions / item 3; ## CapabilityContract / creationRule and nonDuplicationBasis"
    category: source
    status: evidence_needed
    liveEvidence: ["input says current loop is ledger-only and configured analyzer execution is unproven"]
    targetFiles: [scripts/source_health_validation_loop.py, scripts/agent_code_evidence_gate.py, .agents/skills/demo1-agent-code-evidence-gate/SKILL.md]
    redTests: ["duplicate-framework-proposed", "ledger-semantics-changed"]
    greenTests: ["existing ledger/autograder/guard/trace owners are reused; any thin adapter preserves ledger semantics"]
    verificationCommands: ["python -m unittest scripts.test_source_health_scorecard scripts.test_source_health_validation_loop", "python -m unittest scripts.test_demo1_docker_autograder"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory reuse-extend-create order; forbidden Docker/lease/secret/trace reimplementation"
    nonGoal: "second source-owner or patch-generation framework"
    rollback: "remove only thin gate wiring/script/test/skill and rerun baseline tests"
    holdCondition: "live call-path does not prove a minimal adapter or existing owner already covers full contract"
  - requirementId: ND-AGENT-GATE-003
    normalizedRequirement: "Candidate agents and decision oracles stay independent; candidates cannot alter hidden tests, baselines, tool configuration, approval rules, deploy paths, or mutation authority."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## instructions / items 4-5; ## DecisionPolicy"
    category: safety
    status: held
    liveEvidence: ["hidden-oracle target seam is not proven"]
    targetFiles: []
    redTests: ["hidden-oracle-mutated", "candidate-controls-authority"]
    greenTests: ["immutable independent oracle rejects mutation and candidate has no apply/deploy edge"]
    verificationCommands: ["run immutable seeded bad and good fixture through the existing isolated contract"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory oracle independence; forbidden candidate control of authority boundary"
    nonGoal: "self-review as correctness proof"
    rollback: "remove gate wiring that exposes oracle/configuration to candidate"
    holdCondition: "hidden-oracle boundary cannot be demonstrated"
  - requirementId: ND-AGENT-GATE-004
    normalizedRequirement: "Randomness, reflection, fuzzing, MCTS, DPO/ReST, and N-way exploration may only propose/read/order hypotheses and never select target files, patch body, apply/rollback/deploy, or mutation authority."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## instructions / item 5; ## DecisionPolicy / Search boundary"
    category: safety
    status: held
    liveEvidence: ["no concrete search integration is approved or observed"]
    targetFiles: []
    redTests: ["search-result-selects-mutation-authority", "seed-dependent-authority"]
    greenTests: ["fixed-seed repeated runs leave authority verdict invariant and have no mutation action"]
    verificationCommands: ["run fixed-seed bad/good fixture twice and compare bounded verdict/reason evidence"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "forbidden optimization-loop authority"
    nonGoal: "training, runtime self-healing, or autonomous patch selection"
    rollback: "remove exploratory integration from the gate boundary"
    holdCondition: "verdict differs by seed/order or search can mutate/control deployment"
  - requirementId: ND-AGENT-GATE-005
    normalizedRequirement: "A required analyzer or sandbox unavailable state is HOLD, never PASS; a single green tool result is not correctness proof."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## instructions / item 6; ## CapabilityContract / failurePolicy"
    category: safety
    status: held
    liveEvidence: ["JDK/Docker/pinned analyzer execution proof absent"]
    targetFiles: []
    redTests: ["required-tool-unavailable-emits-pass", "sandbox-unavailable-emits-pass"]
    greenTests: ["unavailable required adapter yields HOLD with bounded reason; all required adapters needed for PASS"]
    verificationCommands: ["python -m unittest scripts.test_agent_code_evidence_gate", "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\demo1_docker_autograder_contract_tests.ps1"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory fail-closed evidence completeness; forbidden downgrade to PASS"
    nonGoal: "automatic tool download or unchecked fallback"
    rollback: "remove fallback that reclassifies absence as PASS"
    holdCondition: "toolchain, Docker, analyzer pin, or sandbox proof is missing"
  - requirementId: ND-AGENT-GATE-006
    normalizedRequirement: "PASS means only eligible for Desktop human/owner review; automatic apply, automatic rollback, and deployment invocation counts must remain zero."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## instructions / item 7; ## DecisionPolicy / PASS; ## ExpectedEvidenceMatrix"
    category: safety
    status: held
    liveEvidence: ["no gate implementation or independent Desktop review evidence"]
    targetFiles: []
    redTests: ["pass-invokes-apply-or-deploy", "automatic-rollback-invoked"]
    greenTests: ["known-good fixture PASS has source/apply/deploy counts all zero"]
    verificationCommands: ["inspect bounded gate result for verdict and apply/deploy counters"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "forbidden automatic application, rollback, and deployment"
    nonGoal: "deployment pipeline changes"
    rollback: "remove any apply/deploy edge and preserve redacted evidence"
    holdCondition: "any automatic mutation edge is reachable"
  - requirementId: ND-AGENT-GATE-007
    normalizedRequirement: "The candidate gate target seam is evidence_needed: build.gradle.kts, a new gate script/tests, and a thin skill are candidates only; source_health_validation_loop changes require call-path proof."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## SourceDirective / targetFiles"
    category: source
    status: evidence_needed
    liveEvidence: ["build.gradle.kts and source_health_validation_loop.py exist; target seam scan/preflight not run"]
    targetFiles: [build.gradle.kts, scripts/agent_code_evidence_gate.py, scripts/test_agent_code_evidence_gate.py, .agents/skills/demo1-agent-code-evidence-gate/SKILL.md, scripts/source_health_validation_loop.py]
    redTests: ["target-seam-unproven", "source-health-ledger-semantics-regression"]
    greenTests: ["one declared candidate seam and immutable target set are proven before any minimal wiring"]
    verificationCommands: ["rg -n \"sourceHealthValidationLoop|agentCodeEvidenceGate|demo1-docker-autograder\" build.gradle.kts scripts main/java src/test/java"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory seam scan and preimage; forbidden application-source, hidden-oracle, public API, DB, credential, provider, or deploy edits"
    nonGoal: "broad source exploration or target selection by candidate agent"
    rollback: "restore verified preimage of any thin gate artifact only"
    holdCondition: "no exact target seam, dirty overlap, lease collision, or changed preimage"
  - requirementId: ND-AGENT-GATE-008
    normalizedRequirement: "Gate RED must independently exercise static-analysis-new-high, compile-failed, expected-signal-mismatch, junit-zero-tests, candidate-hash-mismatch, hidden-oracle-mutated, secret-leak-risk, sandbox-unavailable, required-tool-unavailable, and nondeterministic-verdict without PASS/apply/deploy."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## SourceDirective / redTest"
    category: test
    status: unimplemented
    liveEvidence: ["input notes source-health tests passed on Notebook, but no gate RED fixtures exist"]
    targetFiles: [scripts/test_agent_code_evidence_gate.py, scripts/agent_code_evidence_gate.py]
    redTests: ["all listed failure classes"]
    greenTests: ["each known-bad immutable fixture is non-PASS and has no apply/deploy invocation"]
    verificationCommands: ["python -m unittest scripts.test_agent_code_evidence_gate"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory independent failure fixtures"
    nonGoal: "zero-test or fake-green acceptance"
    rollback: "remove only added fixture/gate wiring if baseline regression occurs"
    holdCondition: "immutable fixture, pinned toolchain, or oracle boundary unavailable"
  - requirementId: ND-AGENT-GATE-009
    normalizedRequirement: "Gate GREEN requires a known-good immutable fixture with matching hashes, pinned toolchain, nonzero tests, no new blocking finding, zero secret hits, deterministic bounded result, complete evidence, and zero source/apply/deploy count."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## SourceDirective / greenTest; ## DecisionPolicy / PASS"
    category: verification
    status: evidence_needed
    liveEvidence: ["no Desktop immutable good-fixture or toolchain run"]
    targetFiles: []
    redTests: ["known-good-fixture-missing"]
    greenTests: ["all PASS prerequisites and zero mutation counters are recorded in bounded evidence"]
    verificationCommands: ["python -m unittest scripts.test_agent_code_evidence_gate", ".\\gradlew.bat --no-daemon --stacktrace --gradle-user-home $agentGateGradleHome --project-cache-dir $agentGateProjectCache checkLangchain4jVersionPurity checkSourceSetHygiene compileJava spotbugsMain agentCodeEvidenceGate :app:classes bootJar"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory all-required-adapter completion; forbidden PASS from partial evidence"
    nonGoal: "claiming runtime correctness from one static or test tool"
    rollback: "set verdict HOLD and retain redacted evidence"
    holdCondition: "missing tool, zero tests, mismatched hash, secret hit, nondeterminism, or runtime lineage incomplete"
  - requirementId: ND-AGENT-GATE-010
    normalizedRequirement: "Decision aggregation is tri-state and non-majoritarian: mandatory failure dominates; missing/freshness/root/tool evidence is HOLD; candidate-caused compile/test/static/secret/hash/oracle failure is REJECT."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## DecisionPolicy"
    category: safety
    status: held
    liveEvidence: ["no implementation evidence; policy is preserved only"]
    targetFiles: [scripts/agent_code_evidence_gate.py, scripts/test_agent_code_evidence_gate.py]
    redTests: ["majority-overrides-mandatory-failure", "candidate-failure-emits-hold-or-pass"]
    greenTests: ["hard failure dominates; absence is HOLD; reproducible candidate failure is REJECT"]
    verificationCommands: ["python -m unittest scripts.test_agent_code_evidence_gate"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "forbidden majority vote and advisory cancellation of hard failure"
    nonGoal: "probabilistic authority verdict"
    rollback: "revert decision reducer hunk only"
    holdCondition: "fixed-seed order changes verdict or required evidence schema is incomplete"
  - requirementId: ND-AGENT-GATE-011
    normalizedRequirement: "Gate outputs use only bounded allowlisted hashes, counts, timings, booleans, versions, reason codes, and redacted evidence; no raw source, query, headers, environment, logs, or sensitive code snippets."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## SourceDirective / expectedEvidence; ## CapabilityContract / redaction; ## ObservabilityContract"
    category: safety
    status: held
    liveEvidence: ["no gate result schema/source output exists"]
    targetFiles: [scripts/agent_code_evidence_gate.py, scripts/test_agent_code_evidence_gate.py]
    redTests: ["secret-leak-risk", "unbounded-log-or-raw-environment-output"]
    greenTests: ["count-only secret scan is zero and schema contains allowlisted bounded fields only"]
    verificationCommands: ["powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\git_secret_guard.ps1 -Mode manual -Path <agent-gate-files>"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "mandatory allowlist/redaction/low-cardinality discipline"
    nonGoal: "raw debug evidence publication"
    rollback: "remove offending output field and rerun secret/redaction fixtures"
    holdCondition: "secret hit, unbounded output, or schema lacks explicit allowlist"
  - requirementId: ND-AGENT-GATE-012
    normalizedRequirement: "Post-human-review observation may use existing Actuator/OpenTelemetry/TraceStore/DebugEventStore keys; a canary can recommend HOLD/manual rollback but never edits source, restarts production, applies rollback, or deploys."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## SourceDirective / callPathOrBoundary; ## ObservabilityContract"
    category: runtime
    status: held
    liveEvidence: ["no human-reviewed apply or runtime canary evidence"]
    targetFiles: []
    redTests: ["canary-mutation-edge", "high-cardinality-hash-metric-label"]
    greenTests: ["canary records only bounded observation and can recommend HOLD/manual rollback"]
    verificationCommands: ["inspect allowlisted TraceStore/DebugEventStore result keys after a separately authorized manual canary"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "forbidden canary mutation/restart/deploy and high-cardinality metric labels"
    nonGoal: "runtime self-healing"
    rollback: "disable observation wiring only; do not auto-rollback runtime"
    holdCondition: "no separate human-reviewed apply and no request-specific runtime lineage"
  - requirementId: ND-AGENT-GATE-013
    normalizedRequirement: "Agent-gate non-goals remain no model fine-tuning/reward model, unlimited exploration, public API/DB/DDL/credential changes, automatic apply/rollback/deploy, or Notebook application-source editing."
    sourcePaths: [data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md]
    sourceHashes: [12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB]
    sourceLocator: "## GoalContract / nonGoals and prohibitedSurface"
    category: non-goal
    status: verified
    liveEvidence: ["this extraction has no source, database, credential, provider, or deployment mutation"]
    targetFiles: []
    redTests: ["scope-expansion-detected"]
    greenTests: ["diff audit contains only the requested Task 2 fragment"]
    verificationCommands: ["git status --short -- .superpowers/sdd/2026-08-06-notebook-directive-consolidation-and-chat-repair/task-2-fragment-rag-gate.md"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "forbidden scope expansion"
    nonGoal: "the listed prohibited changes and operations"
    rollback: "remove future unauthorized hunk after review"
    holdCondition: "request expands beyond evidence-gate tooling and observation"
  - requirementId: ND-RISK-UTILITY-001
    family: ND-RISK-UTILITY
    directiveId: GD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Use exactly three actual reviewer agents: independent POSITIVE_QUERY and NEGATIVE_QUERY packets, then one NEUTRAL_QUERY adjudicator with stable forward and reverse ordering."
    source:
      path: __reports__/notebook-risk-utility-triad-2026-08-04.json
      sha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
      locator: /requiresLiteralSubagents, /processMode, /actualAgentCount, /packets/NEUTRAL_QUERY
    sourcePaths: [__reports__/notebook-risk-utility-triad-2026-08-04.json]
    sourceHashes: [179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130]
    category: safety
    status: verified
    decision: retain
    disposition: DEFER
    executionAuthority: directive_only
    targets: []
    mandatoryRules: ["actualAgentCount=3", "NEUTRAL is order-stable and decides without majority voting"]
    forbiddenRules: ["do not treat more agents as inherently safer", "do not let correlated evidence become independent corroboration"]
    redTests: ["A-B versus B-A NEUTRAL verdict disagreement is RED and yields HOLD"]
    greenTests: ["forwardVerdict=APPLY, reverseVerdict=APPLY, orderStable=true for the frozen evidence snapshot"]
    verificationCommands: ["Preserve the frozen evidenceSnapshotHash and inspect /packets/NEUTRAL_QUERY forwardOrder, reverseOrder, and orderStable."]
    nonGoals: ["runtime proof", "application-source mutation"]
    rollback: "No source mutation; discard only a non-published review packet."
    holdConditions: ["packet count is not exactly three", "orderStable=false", "evidence snapshot changes"]
    conflictsWith: []
    liveEvidence: ["EV-05", "EV-12", "EV-18", "EV-21"]

  - requirementId: ND-RISK-UTILITY-002
    family: ND-RISK-UTILITY
    directiveId: GD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Separate potential value from harm; credible high-impact harm is a non-compensatory veto before utility comparison."
    source:
      path: __reports__/notebook-risk-utility-triad-2026-08-04.json
      sha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
      locator: /packets/POSITIVE_QUERY/scenarioWorlds/0, /packets/NEGATIVE_QUERY/falsifiers, /packets/NEUTRAL_QUERY/selectedOrRewrittenGoal
    sourcePaths: [__reports__/notebook-risk-utility-triad-2026-08-04.json]
    sourceHashes: [179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130]
    category: safety
    status: already_present
    decision: retain
    disposition: DEFER
    executionAuthority: none_in_current_tranche
    targets: [main/java/com/example/lms/service/ChatWorkflow.java, "existing RagConstitutionalScorecard/EvidenceAwareGuard boundary"]
    mandatoryRules: ["harm veto precedes utility", "BLOCK remains invariant when putative benefit increases"]
    forbiddenRules: ["do not average away credible severe harm", "do not use decisionValueScore as expected benefit"]
    redTests: ["A larger putative benefit must not turn an existing BLOCK into allow."]
    greenTests: ["High value with credible severe harm is BLOCK or HOLD with a stable bounded reason."]
    verificationCommands: ["Inspect the existing scorecard BLOCK enforcement and run the focused veto-invariance characterization at its confirmed owner."]
    nonGoals: ["calibrating thresholds", "new global utility scorer"]
    rollback: "Preserve existing constitutional BLOCK ownership; revert only a future narrow owner-bound patch."
    holdConditions: ["guard owner is unproven", "focused characterization requires a target outside the approved boundary"]
    conflictsWith: [ND-RISK-UTILITY-HOLD-001]
    liveEvidence: ["EV-10", "EV-11", "EV-20", "EV-21"]

  - requirementId: ND-RISK-UTILITY-003
    family: ND-RISK-UTILITY
    directiveId: GD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Keep an unverified rare signal probe-only; a rare severe safety warning may cause protective HOLD or BLOCK but never risky execution."
    source:
      path: __reports__/notebook-risk-utility-triad-2026-08-04.json
      sha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
      locator: /packets/POSITIVE_QUERY/scenarioWorlds/1, /packets/NEGATIVE_QUERY/scenarioAttacks/1, /packets/NEUTRAL_QUERY/rejectedClaims
    sourcePaths: [__reports__/notebook-risk-utility-triad-2026-08-04.json]
    sourceHashes: [179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130]
    category: safety
    status: pending
    decision: retain
    disposition: DEFER
    executionAuthority: none_in_current_tranche
    targets: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, "existing evidence/guard boundary"]
    mandatoryRules: ["uncorroborated rarity has no action authority", "protective action is bounded to HOLD or BLOCK"]
    forbiddenRules: ["one unverified signal must not enable production action", "do not promote a probe result into source-write, provider, DB, or credential authority"]
    redTests: ["One unverified positive rare signal attempts to authorize a production action.", "One unverified severe warning attempts risky execution."]
    greenTests: ["Positive rare signal is PROBE_ONLY or HOLD without side effects; severe warning is only protective HOLD or BLOCK."]
    verificationCommands: ["Table-test rare positive, rare warning, duplicate provenance, and absent corroboration without side effects."]
    nonGoals: ["automatic agent action", "external mutation"]
    rollback: "Remove only a future additive authority label and restore the confirmed preimage."
    holdConditions: ["independent-corroboration rule undefined", "provenance grouping unavailable"]
    conflictsWith: [ND-RISK-UTILITY-HOLD-002]
    liveEvidence: ["EV-12", "EV-18"]

  - requirementId: ND-RISK-UTILITY-004
    family: ND-RISK-UTILITY
    directiveId: GD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Allow a reversible candidate to become eligible only after hard gates, calibrated lower-benefit and upper-harm bounds, lineage, rollback proof, and an approved margin are present."
    source:
      path: __reports__/notebook-risk-utility-triad-2026-08-04.json
      sha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
      locator: /packets/POSITIVE_QUERY/scenarioWorlds/2, /packets/NEGATIVE_QUERY/scenarioAttacks/2, /packets/NEUTRAL_QUERY/unknowns
    sourcePaths: [__reports__/notebook-risk-utility-triad-2026-08-04.json]
    sourceHashes: [179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130]
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    executionAuthority: none
    targets: []
    mandatoryRules: ["utility is below the hard-veto layer", "candidate must be reversible with intact lineage and rollback proof"]
    forbiddenRules: ["do not infer calibrated expected utility from decisionValueScore", "do not allow correlated support to hide tail harm"]
    redTests: ["Inject tail risk, absent lineage, and rollback failure; each must fail closed."]
    greenTests: ["Only independently supported, low-harm, reversible candidates become eligible below the veto."]
    verificationCommands: ["Desktop table tests plus an owned replay dataset, calibration contract, false-block budget, and request-scoped lineage proof."]
    nonGoals: ["production scoring implementation", "threshold invention"]
    rollback: "No implementation begins; no rollback action exists."
    holdConditions: ["severity calibration absent", "benefit/harm units or owner absent", "false-block budget absent", "rollback evidence absent"]
    conflictsWith: []
    liveEvidence: ["EV-12", "EV-19", "EV-20", "EV-21"]

  - requirementId: ND-RISK-UTILITY-005
    family: ND-RISK-UTILITY
    directiveId: GD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "The triad authorizes a Desktop-owned non-mutating directive only; it does not authorize application-source, external, commit, push, deployment, or runtime-success claims."
    source:
      path: __reports__/notebook-risk-utility-triad-2026-08-04.json
      sha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
      locator: /userRequest, /evidenceSnapshot/summary, /evidenceSnapshot/evidenceRows/17, /evidenceSnapshot/evidenceRows/18
    sourcePaths: [__reports__/notebook-risk-utility-triad-2026-08-04.json]
    sourceHashes: [179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130]
    category: safety
    status: verified
    decision: retain
    disposition: DEFER
    executionAuthority: directive_only
    targets: []
    mandatoryRules: ["keep notebook evidence supporting-only", "require Desktop runtime proof before a runtime claim"]
    forbiddenRules: ["no notebook source patch", "no external mutation", "no commit/push/deploy"]
    redTests: ["A provider or runtime-success claim without Desktop request-scoped lineage is RED."]
    greenTests: ["Canonical ledger states directive-only authority and runtimeLineageVerdict=HOLD/evidence_needed."]
    verificationCommands: ["Classify Notebook evidence as supporting_only; require focused Desktop Gradle and request-scoped TraceStore lineage before runtime claims."]
    nonGoals: ["source execution", "runtime certification"]
    rollback: "No mutation; retain the evidence record unchanged."
    holdConditions: ["Desktop root or runtime lineage is not proven"]
    conflictsWith: [ND-RISK-UTILITY-HOLD-003]
    liveEvidence: ["EV-18", "EV-19"]

  - requirementId: SD-RISK-UTILITY-SHADOW-20260804
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Phase 1 is a Desktop-only, RED-first, shadow-first directive limited to RagFailureBlackboxService and its focused test after explicit implementation approval and Desktop preflight."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## GoalContract > authorizedMutationSurface; ## SourceDirective > directiveId, targetFiles, beforeBehavior, afterBehavior > Phase 1 shadow"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: source
    status: pending
    decision: retain
    disposition: DEFER
    executionAuthority: desktop_after_explicit_approval_and_preflight
    targets: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java]
    mandatoryRules: ["compute bounded age metadata from seenAtMs", "emit only age bucket, freshness reason, provenance-group count, and authority reason", "do not relax or bypass BLOCK"]
    forbiddenRules: ["no source mutation in this Task 2 tranche", "no new allow path", "no raw query or unbounded evidence"]
    redTests: ["Characterize age-insensitive virtual-point prior behavior with materially different seenAtMs values."]
    greenTests: ["Shadow evidence is bounded/redacted, BLOCK is stable, and shadow labeling adds no action authority."]
    verificationCommands: ["Run Desktop preflight and the exact focused RagFailureBlackboxServiceTest command listed under ## SourceDirective before any later patch."]
    nonGoals: ["enforcement", "configuration guessing", "PromptBuilder/provider/DB changes"]
    rollback: "Before enforcement, remove only additive shadow fields and restore the two declared preimages."
    holdConditions: ["explicit implementation approval absent", "Desktop root/preimages/active source set not proven", "index lock or dirty target overlap", "focused RED not reproduced"]
    conflictsWith: [ND-RISK-UTILITY-HOLD-003]
    liveEvidence: ["EV-07", "EV-08", "EV-09", "EV-10", "EV-11", "EV-14", "EV-15"]

  - requirementId: ND-RISK-UTILITY-006
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Historical and minority evidence must be labeled PROBE_ONLY unless independent corroboration is proven; this label cannot create an allow path."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## SourceDirective > afterBehavior > Phase 1 authority; ## GoalContract > assumptions"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: safety
    status: pending
    decision: retain
    disposition: DEFER
    executionAuthority: desktop_after_phase_1_preflight
    targets: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java]
    mandatoryRules: ["missing or correlated evidence reduces authority", "correlated agents count as one provenance group"]
    forbiddenRules: ["no action authority from labels alone", "no production/source/provider/DB/credential mutation from a probe"]
    redTests: ["Duplicate/correlated evidence attempts to become two corroborating groups."]
    greenTests: ["Unsupported or correlated history remains PROBE_ONLY/HOLD and does not mutate the decision score."]
    verificationCommands: ["Focused table tests for duplicate provenance, absent corroboration, and bounded reason fields."]
    nonGoals: ["minority forecast promotion", "agent framework"]
    rollback: "Remove only the additive authority disposition evidence and rerun the focused test."
    holdConditions: ["independence definition absent", "trace owner cannot expose bounded provenance-group count"]
    conflictsWith: [ND-RISK-UTILITY-HOLD-002]
    liveEvidence: ["EV-12", "EV-13"]

  - requirementId: ND-RISK-UTILITY-ENFORCEMENT-20260804
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Phase 2 enforcement may prevent stale or unsupported priors from modifying the decision score only after calibrated policy and an existing owned configuration seam are proven."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## SourceDirective > targetFiles > Phase 2; ## SourceDirective > afterBehavior > Phase 2 enforcement; ## GoalContract > measurableSuccess"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: source
    status: held
    decision: hold
    disposition: HOLD
    executionAuthority: none_until_calibration_approval
    targets: []
    mandatoryRules: ["stale or unsupported prior becomes PROBE_ONLY or HOLD with no decision-score mutation", "fresh independently corroborated prior remains subordinate to hard veto"]
    forbiddenRules: ["do not guess a configuration target", "do not enforce from an uncalibrated age threshold"]
    redTests: ["Stale/unknown age, duplicate provenance, absent lineage, and rollback failure attempt to influence a score."]
    greenTests: ["Each unsafe case holds without score mutation; a fresh corroborated prior is merely eligible below veto."]
    verificationCommands: ["Require an owned replay dataset, unit/threshold owner, false-block budget, independent-corroboration rule, focused RED/GREEN, and request-scoped lineage."]
    nonGoals: ["phase 1 shadow evidence", "application.yml threshold change before ownership proof"]
    rollback: "After enforcement, revert the Desktop patch atomically and rerun the focused test; do not change global Git trust."
    holdConditions: ["threshold owner absent", "units absent", "calibration absent", "false-block budget absent", "existing configuration seam unproven"]
    conflictsWith: [ND-RISK-UTILITY-HOLD-001]
    liveEvidence: ["EV-08", "EV-09", "EV-12", "EV-16", "EV-19"]

  - requirementId: ND-RISK-UTILITY-007
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Preserve existing RagConstitutionalScorecard/EvidenceAwareGuard BLOCK ownership and the ChatWorkflow pre-decision call boundary; do not create a fifth global RiskScorer."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## Decision > rejected approaches and decision order; ## SourceDirective > callPathOrBoundary, afterBehavior, excludedFilesAndMirrors"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: safety
    status: already_present
    decision: retain
    disposition: DEFER
    executionAuthority: none_in_current_tranche
    targets: [main/java/com/example/lms/service/ChatWorkflow.java, "existing RagConstitutionalScorecard/EvidenceAwareGuard boundary"]
    mandatoryRules: ["preserve existing BLOCK owner", "keep final guard authority separate from blackbox scorecard projection"]
    forbiddenRules: ["no new global RiskScorer", "no fusion CVaR import without separate call-path proof", "do not reinterpret decisionValueScore as benefit"]
    redTests: ["A new scorer or benefit offset bypasses an existing BLOCK."]
    greenTests: ["Existing BLOCK stays authoritative and scorecard projection remains before guard consumption."]
    verificationCommands: ["Inspect ChatWorkflow evidenceGuard.preDecision path, RagConstitutionalScorecard routing decision, EvidenceAwareGuard block test, and count active RiskScorer declarations."]
    nonGoals: ["global scoring framework", "CVaR implementation"]
    rollback: "No source mutation; future changes revert only the declared narrow target files."
    holdConditions: ["call path drifts", "a required test needs a new global owner"]
    conflictsWith: []
    liveEvidence: ["EV-10", "EV-11", "EV-13", "EV-20", "EV-21"]

  - requirementId: ND-RISK-UTILITY-008
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Use bounded redacted age/freshness/provenance/authority reason evidence; no raw query, secret, provider credential, or unbounded payload may enter evidence."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## GoalContract > measurableSuccess and constraints; ## SourceDirective > afterBehavior > Phase 1 shadow; ## SourceDirective > expectedEvidence"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: safety
    status: pending
    decision: retain
    disposition: DEFER
    executionAuthority: desktop_after_phase_1_preflight
    targets: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java]
    mandatoryRules: ["use bounded categorical reason fields", "retain request-scoped hash-only lineage before any runtime-success claim"]
    forbiddenRules: ["no raw query", "no secret", "no provider credential", "no unbounded payload"]
    redTests: ["A declared diff or log contains a raw sensitive value or unbounded payload."]
    greenTests: ["Focused output exposes only age/freshness/provenance/authority categories and secret scan count is zero."]
    verificationCommands: ["Run the declared focused test and a changed-file count-only secret scan; require prompt/options hash plus provider attempt/response lineage for runtime claims."]
    nonGoals: ["new public diagnostics API", "provider telemetry implementation"]
    rollback: "Remove only additive shadow fields; preserve existing redaction owners."
    holdConditions: ["bounded output cannot be demonstrated", "runtime lineage missing for an allow claim"]
    conflictsWith: []
    liveEvidence: ["EV-19"]

  - requirementId: ND-RISK-UTILITY-009
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Do not touch inactive mirrors, PromptBuilder, public APIs, DB/Supabase, credentials, provider adapters, unrelated RiskScorer classes, archives, or generated outputs."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## GoalContract > prohibitedSurface and nonGoals; ## SourceDirective > excludedFilesAndMirrors, publicApiChange, secretMutation"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: non-goal
    status: verified
    decision: retain
    disposition: DEFER
    executionAuthority: none_in_current_tranche
    targets: []
    mandatoryRules: ["active source set only", "phase 1 target set stays exactly two files"]
    forbiddenRules: ["publicApiChange=forbidden", "secretMutation=forbidden", "no Y application-source mutation", "no DB/Supabase/provider/credential/prompt boundary changes"]
    redTests: ["A proposed patch includes any excluded path or mutation surface."]
    greenTests: ["Declared diff is confined to the two Phase 1 files after explicit authorization."]
    verificationCommands: ["Inspect declared paths before editing; run changed-file inventory and count-only secret scan after focused verification."]
    nonGoals: ["clinical threshold design", "literal 5??0 minute TTL", "medical diagnosis", "deployment"]
    rollback: "Revert only declared target files; never reset unrelated dirty worktree state."
    holdConditions: ["target expansion", "active source set uncertain", "dirty overlap"]
    conflictsWith: []
    liveEvidence: ["EV-05", "EV-13", "EV-18"]

  - requirementId: ND-RISK-UTILITY-010
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Run exact Desktop preflight, preimage, focused Gradle, app classes, and bootJar gates; classify missing runtime/provider evidence as HOLD rather than success."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## GoalContract > verificationCommands, stopConditions; ## SourceDirective > exactVerificationCommands, expectedEvidence, failureClassifications"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    executionAuthority: none_until_desktop_preflight_passes
    targets: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java]
    mandatoryRules: ["prove C-root and branch", "check lock/worktree/preimages", "use AWX split outputs and Desktop project cache", "run projects, compileJava, focused test, :app:classes, and bootJar"]
    forbiddenRules: ["do not claim provider success from static evidence", "do not alter global safe.directory"]
    redTests: ["Any preflight, preimage, Java/Gradle, or focused RED failure stops execution."]
    greenTests: ["All exact command gates pass with redacted output and recorded postimages."]
    verificationCommands: ["See source locator ## SourceDirective > exactVerificationCommands; execute verbatim only after a separately authorized implementation turn."]
    nonGoals: ["broad build retry", "runtime claim without request-scoped lineage"]
    rollback: "On a focused verification failure, revert only the declared targets and re-run the focused test."
    holdConditions: ["desktop-root-unproven", "index-lock-present", "dirty-overlap", "changed-preimage", "gradle-or-java-unavailable", "runtime-lineage-missing"]
    conflictsWith: []
    liveEvidence: ["EV-02", "EV-03", "EV-04", "EV-05", "EV-14", "EV-15", "EV-16", "EV-19"]

  - requirementId: ND-RISK-UTILITY-011
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Forecasting/minority-signal promotion is deferred: no forecast ledger exists without validAfter, validUntil, predicted observation, and an independent falsifier."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## Decision > Forecasting/minority-signal disposition"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: non-goal
    status: evidence_needed
    decision: hold
    disposition: HOLD
    executionAuthority: none
    targets: []
    mandatoryRules: ["keep forecast ledger absent until all four fields are independently proven"]
    forbiddenRules: ["do not infer a forecast from a rare signal", "do not create a ledger from analogy"]
    redTests: ["Missing any forecast field attempts to create or promote a forecast record."]
    greenTests: ["Absent fields leave disposition DEFER with no forecast ledger."]
    verificationCommands: ["Require a separately owned forecast contract with all four fields and an independent falsifier."]
    nonGoals: ["forecast implementation", "minority-signal promotion"]
    rollback: "No ledger or source mutation exists to roll back."
    holdConditions: ["validAfter absent", "validUntil absent", "predicted observation absent", "independent falsifier absent"]
    conflictsWith: []
    liveEvidence: ["EV-12", "EV-18"]

  - requirementId: ND-RISK-UTILITY-HOLD-001
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Unresolved calibration conflict: the directive defines U_low but expressly leaves lambda_domain and promotion margin unset; no enforcement threshold may be selected."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## Decision > decision order item 3; ## GoalContract > evidence_needed and stopConditions"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: safety
    status: conflict
    decision: hold
    disposition: HOLD
    executionAuthority: none
    targets: []
    mandatoryRules: ["hard veto remains before any utility comparison", "thresholds require Desktop-owned replay data, unit contract, threshold owner, and false-block budget"]
    forbiddenRules: ["no invented lambda_domain", "no invented promotion margin", "no enforcement from 5??0-minute analogy"]
    redTests: ["An unowned threshold authorizes or rejects an action."]
    greenTests: ["Until calibration exists, output is HOLD and no score mutation occurs."]
    verificationCommands: ["Obtain the named calibration artifacts and then run focused RED/GREEN at the existing owner."]
    nonGoals: ["calibration design", "TTL selection"]
    rollback: "No enforcement patch is authorized."
    holdConditions: ["all calibration artifacts not present"]
    conflictsWith: [ND-RISK-UTILITY-002, ND-RISK-UTILITY-004, ND-RISK-UTILITY-ENFORCEMENT-20260804]
    liveEvidence: ["EV-12", "EV-20"]

  - requirementId: ND-RISK-UTILITY-HOLD-002
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Unresolved provenance conflict: multiple agents or duplicate telemetry cannot establish independent corroboration, but the owner and independence rule are unproven."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "## Decision > decision order item 2; ## GoalContract > assumptions and evidence_needed; ## Tactical probes from the three reviewers"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E]
    category: safety
    status: conflict
    decision: hold
    disposition: HOLD
    executionAuthority: none
    targets: []
    mandatoryRules: ["treat correlated agents/telemetry as one group", "maintain protective HOLD/BLOCK for an uncorroborated severe warning"]
    forbiddenRules: ["do not turn count of agents into evidence independence", "do not promote an unsupported prior"]
    redTests: ["Duplicate harm or shared-source agents are counted as independent corroborators."]
    greenTests: ["Deduplication yields one provenance group and preserves no risky action authority."]
    verificationCommands: ["Run the false-veto, rare-warning, and correlation table probes after an owned independence definition exists."]
    nonGoals: ["agent-count scoring", "automatic promotion"]
    rollback: "No implementation begins before provenance ownership is defined."
    holdConditions: ["provenance-independence-unknown", "owner absent"]
    conflictsWith: [ND-RISK-UTILITY-003, ND-RISK-UTILITY-006]
    liveEvidence: ["EV-12", "EV-18"]

  - requirementId: ND-RISK-UTILITY-HOLD-003
    family: ND-RISK-UTILITY
    directiveId: SD-RISK-UTILITY-SHADOW-20260804
    normalizedRequirement: "Authority conflict is resolved in favor of the current Desktop canonical root: Notebook Y evidence is supporting-only, while execution remains HOLD until Desktop ownership/preflight and explicit implementation approval are current."
    source:
      path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
      sha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
      locator: "front matter > canonicalWorkspace/runtimeLineageVerdict/desktopFinalProof; ## GoalContract > authorizedMutationSurface and prohibitedSurface; ## SourceDirective > provenRoot/provenBranch"
    sourcePaths: [__reports__/desktop-risk-utility-source-directive-2026-08-04.md, __reports__/notebook-risk-utility-triad-2026-08-04.json]
    sourceHashes: [4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E, 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130]
    category: safety
    status: conflict
    decision: hold
    disposition: HOLD
    executionAuthority: none
    targets: [main/java/com/example/lms/resilience/RagFailureBlackboxService.java, src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java]
    mandatoryRules: ["canonicalExecutionRoot is C:\\AbandonWare\\demo-1\\demo-1\\src", "Notebook/Y evidence remains supporting-only", "Desktop must prove root, branch, source set, lock, lease, and preimages"]
    forbiddenRules: ["no Y application-source write", "no trust in a stale Notebook APPLY as execution authority"]
    redTests: ["A Y-only observation or directive-level APPLY attempts to authorize a source patch."]
    greenTests: ["A fresh Desktop preflight plus explicit approval is present before any Phase 1 execution."]
    verificationCommands: ["Run the Desktop collision and three-way preflight; compare current C-root preimages immediately before a narrow patch."]
    nonGoals: ["SMB authority change", "global safe.directory change"]
    rollback: "Hold without mutation; preserve the two inputs unchanged."
    holdConditions: ["desktop-root-unproven", "branch-ownership-mismatch", "index lock", "dirty overlap", "explicit approval absent"]
    conflictsWith: [ND-RISK-UTILITY-005, SD-RISK-UTILITY-SHADOW-20260804]
    liveEvidence: ["EV-01", "EV-02", "EV-03", "EV-04", "EV-05", "EV-18"]
  - requirementId: ND-DPA-001
    normalizedRequirement: Preserve immutable GoalContract ID `DPA-LINEAGE-P0-20260802`.
    category: source
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 16. GoalContract / goalId'}]
  - requirementId: ND-DPA-002
    normalizedRequirement: Preserve immutable SourceDirective ID `SD-DPA-LINEAGE-P0-20260802` and Desktop source ownership.
    category: source
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 17. SourceDirective / directiveId; sourceOwner'}]
  - requirementId: ND-DPA-003
    normalizedRequirement: Add a deterministic versioned prompt-fragment layer inside the existing PromptBuilder boundary, reusing PromptContext, PromptAssetService, TraceStore, and ModelRuntimeHealthTracker; add no second prompt path.
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 1. Decision Summary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Goal; Architecture'}
  - requirementId: ND-DPA-004
    normalizedRequirement: Keep `prompt-assembly.enabled=false` by default; disabled, no-match, invalid-manifest, out-of-scope, or unmatched paths append zero characters and preserve legacy instruction bytes.
    category: safety
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 1. Decision Summary; ## 11. Prompt Boundary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints'}
  - requirementId: ND-DPA-005
    normalizedRequirement: Preserve final instruction construction on `PromptBuilder.buildInstructions(PromptContext)`; ChatWorkflow may open scopes but must not concatenate prompt text.
    category: safety
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals; ## 11. Prompt Boundary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints'}
  - requirementId: ND-DPA-006
    normalizedRequirement: Bind one immutable hash/count-only assembly envelope to physical attempts that enter during the final-message fingerprint scope and match full hash, item count, and UTF-8 byte count; append from entry capture so late completions retain evidence and earlier/later/mismatched attempts remain untagged.
    category: runtime
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/0/scenarioWorlds/1'}
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 12. Lineage Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Steps 3-6'}
  - requirementId: ND-DPA-007
    normalizedRequirement: Preserve existing provider/adapter signatures, outcome/failure/terminal authority, and provider/wire flags; client attempt rows do not prove provider receipt or wire observation.
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/counterExamples/1'}
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals; ## 12. Lineage Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints; Task 5 / Step 7'}
  - requirementId: ND-DPA-008
    normalizedRequirement: Preserve the evidence snapshot hash `6bd8f126af17496fb45ed712d5f479b3198254283e5e508155edeec8635efbd7`, single-agent logical-role mode, actualAgentCount 1, and `requiresLiteralSubagents=false`.
    category: verification
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshotHash; /requiresLiteralSubagents; /processMode; /actualAgentCount'}]
  - requirementId: ND-DPA-009
    normalizedRequirement: 'E01: backing-share identity comparison observed boolean true; reverify by resolving Y: DisplayRoot only to a temporary value, normalizing it, comparing UTF-8 SHA-256 to the repository baseline, and printing only boolean and reason.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/0 (E01)'}]
  - requirementId: ND-DPA-010
    normalizedRequirement: 'E02: Notebook observed branch-main; verification command is `Get-Content -LiteralPath Y:\.git\HEAD -Raw`.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/1 (E02)'}]
  - requirementId: ND-DPA-011
    normalizedRequirement: 'E03: Notebook observed indexLockPresent=false; verification command is `Test-Path -LiteralPath Y:\.git\index.lock`.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/2 (E03)'}]
  - requirementId: ND-DPA-012
    normalizedRequirement: 'E04: root source sets were observed; inspect Y:\settings.gradle* and active Gradle sourceSets and reconfirm on Desktop before mutation.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/3 (E04)'}]
  - requirementId: ND-DPA-013
    normalizedRequirement: 'E05: PromptBuilder and StandardPromptBuilder boundaries were observed; verification commands are `Test-Path Y:\main\java\com\example\lms\prompt\PromptBuilder.java; Test-Path Y:\main\java\com\example\lms\prompt\StandardPromptBuilder.java`.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/4 (E05)'}]
  - requirementId: ND-DPA-014
    normalizedRequirement: 'E06: memory seam count 3; inspect PromptContext, conversation-summary reuse, and existing memory evidence seams without reading raw user content.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/5 (E06)'}]
  - requirementId: ND-DPA-015
    normalizedRequirement: 'E07: tool authority seam count 3; inspect existing tool registry, AgentToolInvoker, and tool-manifest authority boundaries.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/6 (E07)'}]
  - requirementId: ND-DPA-016
    normalizedRequirement: 'E08: owned versioned PromptAssembly catalog/planner/decision/trace count 0; search active source/resources for these types.'
    category: verification
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/7 (E08)'}]
  - requirementId: ND-DPA-017
    normalizedRequirement: 'E09: provider/wire proof was missing; inspect request timeline and attempt-row fields and require direct provider/wire evidence before promotion.'
    category: runtime
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/8 (E09)'}]
  - requirementId: ND-DPA-018
    normalizedRequirement: 'E10: Supabase project scope is unproven; do not probe or mutate Supabase until project scope and read authority are separately proven.'
    category: safety
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/9 (E10)'}]
  - requirementId: ND-DPA-019
    normalizedRequirement: 'E11: top-level PatchDrop patch count 0; verification command is `Get-ChildItem -LiteralPath Y:\__patch_drop__ -File -Filter *.patch`.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/10 (E11)'}]
  - requirementId: ND-DPA-020
    normalizedRequirement: 'E12: git status timed out; on Desktop run `git status --short` under the proven canonical checkout without changing global safe.directory.'
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/11 (E12)'}]
  - requirementId: ND-DPA-021
    normalizedRequirement: 'E13: packet validator was missing; verification command is `Test-Path -LiteralPath Y:\scripts\validate_goal_directive_packets.py`.'
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/12 (E13)'}]
  - requirementId: ND-DPA-022
    normalizedRequirement: 'E14: Notebook mutation-log count 0; review for application-source, DB, Supabase, credential, commit, push, and deployment writes.'
    category: safety
    status: verified
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/13 (E14)'}]
  - requirementId: ND-DPA-023
    normalizedRequirement: 'E15: router seam count 3; inspect request classification, model routing, and skill/tool routing seams and keep them outside P0.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/14 (E15)'}]
  - requirementId: ND-DPA-024
    normalizedRequirement: 'E16: StandardPromptBuilder Notebook preimage SHA-256 was ed5f3b26e3d9cf1047956d9f043e265ea27b2387c46041b0129f5463939671ca; reverify with `(Get-FileHash -LiteralPath Y:\main\java\com\example\lms\prompt\StandardPromptBuilder.java -Algorithm SHA256).Hash.ToLowerInvariant()`.'
    category: verification
    status: stale
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/15 (E16)'}]
  - requirementId: ND-DPA-025
    normalizedRequirement: 'POSITIVE W1: typed PromptContext signals select two general fragments; strict manifest, fixed priority, conflicts, and character budget deterministically fix order; identical inputs must repeat decision hash and instruction bytes; any change in order/hash/bytes falsifies the claim.'
    category: verification
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    evidenceIds: [E05, E08, E16]
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/0/scenarioWorlds/0'}]
  - requirementId: ND-DPA-026
    normalizedRequirement: 'POSITIVE W2: capture immutable assembly evidence only at physical-call entry within the finalized-message fingerprint scope; scope-before, mismatched, and scope-after attempts remain untagged; matching late completion retains hashes/counts; no raw content or provider/wire-success claim is present. Any spillover, lost late capture, or raw content falsifies the claim.'
    category: verification
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    evidenceIds: [E06, E09]
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/0/scenarioWorlds/1'}]
  - requirementId: ND-DPA-027
    normalizedRequirement: 'POSITIVE packet retains candidateGoal, validated assumptions, reusable assets, expected user value, and bounded proof exactly.'
    detail:
      candidateGoal: 'Desktop이 기존 PromptBuilder와 request-attempt ledger 경계를 보존하면서 default-off의 결정론적·버전형 prompt fragment assembly P0를 최소 변경으로 구현한다.'
      validatedAssumptions: ['PromptBuilder와 timeline/attempt 경계가 활성 소스에 있다.', '기존 memory·tool authority seam을 재사용하고 P0에서는 변경하지 않을 수 있다.']
      reusableAssets: [PromptAssetService, PromptBuilder/PromptContext, 'ModelRuntimeHealthTracker request timeline']
      expectedUserValue: '긴 대화에서 이미 압축된 맥락을 필요한 때만 안전하게 재사용하고, 어떤 fragment 버전이 선택됐는지 재현 가능한 증거를 남긴다.'
      minimalVerification: 'disabled byte equality, manifest fail-closed, deterministic replay, raw-content absence, matching physical-entry capture, late completion 보존, scope 전후·불일치 non-spillover를 focused tests로 입증한다.'
      evidenceIds: [E04, E05, E06, E07, E08, E09, E15, E16]
      unknowns: ['Desktop의 현재 branch/status/lease/preimage', '공식 packet validator 결과', 'provider/wire 직접 계보']
    category: verification
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/0'}]
  - requirementId: ND-DPA-028
    normalizedRequirement: 'NEGATIVE W1: raw-query regex/model judgment makes selection nondeterministic and sensitive-data-dependent; observed quality could instead come from summary or model changes; PromptBuilder bypass or tool-registry changes violate ownership; broader router/tool/DB edits enlarge rollback; disconfirm with typed-context equality across different raw strings.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    evidenceIds: [E05, E07, E08, E15]
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/scenarioAttacks/0'}]
  - requirementId: ND-DPA-029
    normalizedRequirement: 'NEGATIVE W2: pre-clear-only evidence cannot join the final call; append-time latest state allows verifier/guard contamination and loses late workers; client-side attempts may lack provider receipt; raw prompt/response violates redaction; provider signature changes expand blast radius; disconfirm with before/mismatch/matching/after plus latch-based late completion while provider/wire remain false.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    evidenceIds: [E06, E09, E14]
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/scenarioAttacks/1'}]
  - requirementId: ND-DPA-030
    normalizedRequirement: 'NEGATIVE packet falsifiers are exactly: disabled instruction bytes change; manifest errors fail open; attempt evidence contains raw content.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/falsifiers'}]
  - requirementId: ND-DPA-031
    normalizedRequirement: 'NEGATIVE counterexamples are exactly: prompt-fragment assembly remains useful without tool selection; a client attempt row is neither provider receipt nor wire observation.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/counterExamples'}]
  - requirementId: ND-DPA-032
    normalizedRequirement: 'NEGATIVE authority risks are Desktop preflight unconfirmed, public API/DB/credential expansion, and Notebook application-source mutation.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/authorityRisks'}]
  - requirementId: ND-DPA-033
    normalizedRequirement: 'NEGATIVE safety risks are raw conversation persistence, manifest path traversal, and retroactive joining from latest timeline state.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/safetyRisks'}]
  - requirementId: ND-DPA-034
    normalizedRequirement: 'NEGATIVE missing evidence is Desktop clean/lease/preimage proof, official packet-validator PASS, and direct provider/wire evidence; the smallest probe is official Desktop packet validation and rejection before source preflight on failure.'
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    evidenceIds: [E03, E05, E07, E09, E10, E12, E13, E14, E15]
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/1/missingEvidence; /packets/1/smallestDisconfirmingProbe'}]
  - requirementId: ND-DPA-035
    normalizedRequirement: 'NEUTRAL packet is order-stable HOLD in both POSITIVE->NEGATIVE and NEGATIVE->POSITIVE order; selected goal permits only default-off PromptBuilder P0 plus fingerprint-scoped physical-attempt capture after preflight and validator PASS; tool routing, DB, Supabase, and provider/wire claims are excluded.'
    detail:
      forwardOrder: [POSITIVE_QUERY, NEGATIVE_QUERY]
      reverseOrder: [NEGATIVE_QUERY, POSITIVE_QUERY]
      forwardVerdict: HOLD
      reverseVerdict: HOLD
      forwardDecisiveEvidenceIds: [E05, E08, E09, E13]
      reverseDecisiveEvidenceIds: [E05, E08, E09, E13]
      orderStable: true
      verdict: HOLD
      selectedOrRewrittenGoal: 'Desktop이 preflight와 validator PASS 뒤에만 PromptBuilder 내부의 default-off P0 fragment assembly와 fingerprint-scoped physical-attempt capture를 구현하며 tool routing, DB, Supabase, provider/wire 주장은 제외한다.'
      scoreInputs:
        evidenceStrength: {value: 0.88, evidenceIds: [E01, E04, E05, E06, E07, E08, E09, E15, E16]}
        causalStrength: {value: 0.76, evidenceIds: [E05, E06, E08, E09]}
        verificationFeasibility: {value: 0.72, evidenceIds: [E03, E04, E11, E12, E13]}
        userValue: {value: 0.86, evidenceIds: [E05, E06, E07]}
        reversibility: {value: 0.90, evidenceIds: [E05, E07, E14]}
        costEfficiency: {value: 0.74, evidenceIds: [E05, E06, E07, E15]}
        timeFit: {value: 0.70, evidenceIds: [E04, E14]}
        blastRadius: {value: 0.38, evidenceIds: [E05, E09, E15]}
        ambiguity: {value: 0.32, evidenceIds: [E12, E13]}
        authorityOrSafetyExpansion: {value: 0.28, evidenceIds: [E07, E10, E14]}
      goalScore: 62.8
      decisiveEvidence: [E05, E08, E09, E13]
      rejectedClaims: ['P0가 자동 tool/skill routing까지 구현한다.', 'Supabase가 P0 lineage 저장소다.', 'client attempt join이 provider/wire receipt를 증명한다.']
      nextSingleProof: 'Desktop에서 scripts\validate_goal_directive_packets.py로 이 artifact의 공식 validator PASS를 증명한다.'
      confidence: H
    category: verification
    status: held
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/2'}]
  - requirementId: ND-DPA-036
    normalizedRequirement: 'HOLD — validator neutrality: repository-owned `scripts/validate_goal_directive_packets.py` was absent in the frozen evidence; Tasks 2-6 may not start until the exact packet validates with canonicalQueryCount=3, snapshotHashMatch=true, orderStable=true, scenarioCoverageRatio=1.0, scoreMatches=true, and packetBoundsPass=true.'
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    holdReason: packet-validator-missing
    conflictsWith: [canonical-source-execution]
    sourceRefs:
      - {path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/evidenceSnapshot/evidenceRows/12; /packets/2/nextSingleProof'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints; Task 1 / Step 1'}
  - requirementId: ND-DPA-037
    normalizedRequirement: 'HOLD — source-preflight neutrality: Desktop must prove live root/branch/status/worktree/lease/index-lock/PatchDrop/sourceSets and run exactly POSITIVE_QUERY, NEGATIVE_QUERY, and NEUTRAL_QUERY over one frozen redacted EvidenceSnapshot; only a stable APPLY may enter the source-owner guard.'
    category: safety
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    conflictsWith: [canonical-source-execution]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Selected Architecture; ## 18. Acceptance Boundary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints; Task 1 / Steps 2-5'}
  - requirementId: ND-DPA-038
    normalizedRequirement: 'HOLD — authority gaps: Desktop current branch/status/lease/preimages and active source sets are unproven; provider/wire direct lineage and restart-durable lineage are absent; Supabase project/read authority is unproven; Notebook evidence and inclusion in this ledger authorize no mutation.'
    category: safety
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    conflictsWith: [provider-claim, wire-claim, durable-lineage-claim, supabase-access, notebook-source-write]
    sourceRefs:
      - {path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json, sha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F, locator: '/packets/2/verdict; /packets/2/rejectedClaims'}
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 12. Lineage Contract; ## 16. GoalContract; ## 18. Acceptance Boundary'}
  - requirementId: ND-DPA-039
    normalizedRequirement: Select only relevant checked-in fragments from structured current-request state.
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals / bullet 1'}]
  - requirementId: ND-DPA-040
    normalizedRequirement: Identical manifest and typed-signal snapshots must produce identical order and full decision hashes.
    category: verification
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals / bullet 2'}]
  - requirementId: ND-DPA-041
    normalizedRequirement: Preserve final prompt construction at `PromptBuilder.buildInstructions`.
    category: safety
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals / bullet 3'}]
  - requirementId: ND-DPA-042
    normalizedRequirement: Join assembly only to application/client attempts entering the physical wrapper while the final-message fingerprint scope matches, without provider API changes.
    category: runtime
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals / bullet 4'}]
  - requirementId: ND-DPA-043
    normalizedRequirement: Keep raw conversation and secret-shaped values out of traces and proof logs.
    category: safety
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals / bullet 5'}]
  - requirementId: ND-DPA-044
    normalizedRequirement: Preserve current output when P0 is disabled or unusable.
    category: safety
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 3. Goals / bullet 6'}]
  - requirementId: ND-DPA-045
    normalizedRequirement: 'Continuity follows only `event -> consented persistence -> bounded summary -> retrieval -> prompt injection -> model/tool behavior -> terminal outcome -> versioned evaluation`; the ledger stores only low-cardinality state, versions, hashes, counts, and reason codes.'
    category: safety
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 2. Causal Model'}]
  - requirementId: ND-DPA-046
    normalizedRequirement: Success/failure association is not causal learning; P0 performs no online weight mutation, and future promotion requires versioned replay, A/B, or holdout gates.
    category: non-goal
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 2. Causal Model'}]
  - requirementId: ND-DPA-047
    normalizedRequirement: Non-goal — model fine-tuning or parameter updates.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 1'}]
  - requirementId: ND-DPA-048
    normalizedRequirement: Non-goal — cross-session memory writes or rolling-summary changes.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 2'}]
  - requirementId: ND-DPA-049
    normalizedRequirement: Non-goal — LLM-based trigger evaluation.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 3'}]
  - requirementId: ND-DPA-050
    normalizedRequirement: Non-goal — tool or Codex-skill selection or execution.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 4'}]
  - requirementId: ND-DPA-051
    normalizedRequirement: Non-goal — AgentToolInvoker, tool manifest, model-router, PromptPose, or Plan DSL changes.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 5'}]
  - requirementId: ND-DPA-052
    normalizedRequirement: Non-goal — Supabase schema/table/RLS/grants/projection/credentials or any DB persistence.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 6'}]
  - requirementId: ND-DPA-053
    normalizedRequirement: Non-goal — restart-durable lineage storage.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 7'}]
  - requirementId: ND-DPA-054
    normalizedRequirement: Non-goal — provider receipt or independent wire-observation claims.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 8'}]
  - requirementId: ND-DPA-055
    normalizedRequirement: Non-goal — public API, dependency, environment-variable, credential, or secret-flow changes; keep every LangChain4j dependency exactly 1.0.1.
    category: non-goal
    status: not_applicable
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 4. Non-Goals / bullet 9'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints'}
  - requirementId: ND-DPA-056
    normalizedRequirement: 'Configuration is exactly prefix `prompt-assembly`, default disabled, classpath manifest `prompts/assembly/prompt-assembly.v1.yaml`, schema-version 1, max-fragments 8, max-chars 4000; use existing ConfigurationPropertiesScan and do not modify an application class.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 7. Configuration Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Steps 3 and 7'}
  - requirementId: ND-DPA-057
    normalizedRequirement: Require manifest resource to begin with classpath; reject filesystem, URL, user-controlled, environment-expanded, SpEL, JEXL, regex, and inline-prompt sources.
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 7. Configuration Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints; Task 2 / Step 4 gate 1'}
  - requirementId: ND-DPA-058
    normalizedRequirement: 'Typed signal `memory.present`: boolean from nonblank PromptContext.memory(); inspect presence only and never copy/hash/serialize/traverse content.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / memory.present'}]
  - requirementId: ND-DPA-059
    normalizedRequirement: 'Typed signal `history.present`: boolean from nonblank PromptContext.history(); inspect presence only and never copy/hash/serialize/traverse content.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / history.present'}]
  - requirementId: ND-DPA-060
    normalizedRequirement: 'Typed signal `lastAnswer.present`: boolean from nonblank lastAssistantAnswer(); inspect presence only and never copy/hash/serialize/traverse content.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / lastAnswer.present'}]
  - requirementId: ND-DPA-061
    normalizedRequirement: 'Typed signal `queryDomain`: QueryDomain.name() or UNKNOWN; raw domain/query text is forbidden.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / queryDomain'}]
  - requirementId: ND-DPA-062
    normalizedRequirement: 'Typed signal `guardProfile`: GuardProfile.name() or UNKNOWN.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / guardProfile'}]
  - requirementId: ND-DPA-063
    normalizedRequirement: 'Typed signal `answerMode`: AnswerMode.name() or UNKNOWN.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / answerMode'}]
  - requirementId: ND-DPA-064
    normalizedRequirement: 'Typed signal `memoryMode`: MemoryMode.name() or UNKNOWN.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / memoryMode'}]
  - requirementId: ND-DPA-065
    normalizedRequirement: 'Typed signal `ragEnabled`: exact Boolean truth.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / ragEnabled'}]
  - requirementId: ND-DPA-066
    normalizedRequirement: 'Typed signal `interaction.enforcementActive`: boolean from the current normalized policy decision.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / interaction.enforcementActive'}]
  - requirementId: ND-DPA-067
    normalizedRequirement: 'Typed signal `interaction.securityStance`: enum label from the current normalized policy decision.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / interaction.securityStance'}]
  - requirementId: ND-DPA-068
    normalizedRequirement: 'Typed signal `web.count`: list size clamped to 0..1024; never read bodies.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / web.count'}]
  - requirementId: ND-DPA-069
    normalizedRequirement: 'Typed signal `rag.count`: list size clamped to 0..1024; never read bodies.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / rag.count'}]
  - requirementId: ND-DPA-070
    normalizedRequirement: 'Typed signal `localDocs.count`: list size clamped to 0..1024; never read document bodies.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / localDocs.count'}]
  - requirementId: ND-DPA-071
    normalizedRequirement: 'Typed signal `evidence.count`: list size clamped to 0..1024; never read bodies.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / evidence.count'}]
  - requirementId: ND-DPA-072
    normalizedRequirement: 'Typed signal `unsupportedClaims.count`: null-safe list size clamped to 0..1024; never read bodies.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract / unsupportedClaims.count'}]
  - requirementId: ND-DPA-073
    normalizedRequirement: Allow exactly operators TRUE, PRESENT, EQ, IN, and GTE; unknown signals/operators invalidate the snapshot, and operands obey their exact typed shapes and bounds.
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 8. Signal Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 4 gate 8'}
  - requirementId: ND-DPA-074
    normalizedRequirement: 'Each fragment contains id, revision, assetId, contentSha256, slot, baseWeight, priority, maxChars, requiredAll, requiredAny, forbidden, exclusiveGroup, and conflictsWith; only slot INSTRUCTIONS_AFTER_CONTEXT_HINTS is valid.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 9. Manifest and Asset Contract'}]
  - requirementId: ND-DPA-075
    normalizedRequirement: 'IDs use lowercase bounded allowlists; asset IDs additionally forbid `..`; enum operands are exact uppercase; valid hashes are full lowercase sha256 and unavailable safe failures use exactly hash:unknown; duplicate IDs, unsafe/missing/hash-mismatched assets, excessive collections, non-finite weights, or invalid budgets fail the entire catalog closed.'
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 9. Manifest and Asset Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 4 gates 1-12'}
  - requirementId: ND-DPA-076
    normalizedRequirement: Use only `PromptAssetService.resolveSystemPromptText(assetId)`; `resolveTrustedSystemPromptText` is forbidden.
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 9. Manifest and Asset Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints; Task 2 / Step 4 gate 9'}
  - requirementId: ND-DPA-077
    normalizedRequirement: Canonicalize fragment and manifest content with `utf8-lf-strip-v1` (CRLF/CR to LF, remove leading UTF-8 BOM, Java String.strip, SHA-256 over UTF-8 bytes).
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 9. Manifest and Asset Contract / Content canonicalization'}]
  - requirementId: ND-DPA-078
    normalizedRequirement: 'Initial assets are exactly continuity-context.v1 (activates for memory/history/last answer; hash sha256:9323fe31770cf716aa151e286b718593e637b3ced118d2afe73aff4c7dfcf04d) and evidence-risk.v1 (activates for SENSITIVE, SAFE|STRICT, or DEFENSIVE; hash sha256:6b061ea5ec41c1a8dd893f96c85ec332d8b812c819c87e206cbd0d73839490b9); tool-selection text is forbidden in P0.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 9. Manifest and Asset Contract / Initial P0 assets'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Steps 5-6'}
  - requirementId: ND-DPA-079
    normalizedRequirement: 'Selection order is exact: requiredAll all match; requiredAny empty-or-any match; forbidden none match; score baseWeight plus matched-required weights; resolve conflicts/exclusive groups by priority DESC, score DESC, id ASC symmetrically; apply same survivor order; stop before maxFragments/maxChars; report only bounded counts/reason codes.'
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 10. Deterministic Selection'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 4'}
  - requirementId: ND-DPA-080
    normalizedRequirement: 'Decision envelope commits enabled/outcome/fixed failure/schema, manifest identity/hash, canonicalization, signal hash, ordered id/revision/contentHash/slot/order/score, selected/rejected/trigger counts, and fragment-content chars; raw fragment content is excluded.'
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 10. Deterministic Selection'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Steps 3-4'}
  - requirementId: ND-DPA-081
    normalizedRequirement: Preserve implicit public no-arg StandardPromptBuilder construction; inject planner only through one optional non-final field and treat null as disabled.
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 11. Prompt Boundary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 3'}
  - requirementId: ND-DPA-082
    normalizedRequirement: A planner bean alone is insufficient; only a short-lived ChatWorkflow-owned FinalChatBuildScope may activate assembly, and verifier/corrective callers remain unchanged.
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 11. Prompt Boundary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Steps 4-5; Task 5 / Step 6'}
  - requirementId: ND-DPA-083
    normalizedRequirement: Insert one subordinate dynamic block after resource-allocation and learning-role hints and before AnswerMode; later fixed mode/system/context/interaction blocks remain authoritative; never semantically truncate validated content.
    category: source
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 11. Prompt Boundary'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Steps 4-5'}
  - requirementId: ND-DPA-084
    normalizedRequirement: 'PromptAssemblyTrace stores exactly observed, outcome, failureClass, envelopeSchemaVersion, manifestSchemaVersion, canonicalizationVersion, manifestId/version/hash, decision/signal/ordered hashes, selected/rejected/trigger counts, fragmentContentChars, and rawIncluded=false.'
    category: runtime
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 12. Lineage Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 5'}
  - requirementId: ND-DPA-085
    normalizedRequirement: 'Safe failure permits only fixed allowlisted reasons, envelope schema awx.prompt-assembly.v1, manifest schema 1 for manifest_* or 0 for tracker-local failures, unknown labels, exactly hash:unknown, zero counts/chars, and rawIncluded=false; malformed metadata normalizes to failed/invalid_metadata.'
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 12. Lineage Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 5; Task 5 / Step 3'}
  - requirementId: ND-DPA-086
    normalizedRequirement: 'ChatWorkflow opens the binding only after final messages exist and closes around the primary retry/fallback call; binding contains immutable redacted evidence, full private scope hash, item count, UTF-8 byte count, and monotonic token; overlapping conflicting bind becomes failed/conflicting_bind and stale close cannot clear a newer scope.'
    category: runtime
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 11. Prompt Boundary; ## 12. Lineage Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Steps 4 and 6'}
  - requirementId: ND-DPA-087
    normalizedRequirement: Capture immutable assembly evidence at the first physical wrapper entry and append only from RequestAttemptAppContext; never read mutable binding at append/render time and never decorate rows retroactively.
    category: runtime
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 12. Lineage Contract'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Steps 4-5'}
  - requirementId: ND-DPA-088
    normalizedRequirement: 'Verdicts remain applicationAssemblyVerdict=HOLD, clientAttemptJoinVerdict=HOLD, restartDurableLineageVerdict=HOLD, providerAttemptVerdict=HOLD, wireAttemptVerdict=HOLD, runtimeLineageVerdict=HOLD until their exact Desktop proof exists.'
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 17. SourceDirective / trailing verdicts'}]
  - requirementId: ND-DPA-089
    normalizedRequirement: 'Modify target: main/java/com/example/lms/prompt/StandardPromptBuilder.java — optional planner injection, deterministic rendering, trace publication; approved preimage must be frozen immediately before edit.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/prompt/StandardPromptBuilder.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Modify'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Modify'}
  - requirementId: ND-DPA-090
    normalizedRequirement: 'Modify target: main/java/com/example/lms/service/ChatWorkflow.java — final-chat build scope and final-message fingerprint attempt scope only; no prompt concatenation.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/service/ChatWorkflow.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Modify'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Modify'}
  - requirementId: ND-DPA-091
    normalizedRequirement: 'Modify target: main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java — private validated evidence, scope binding, physical-entry capture, redacted attempt-row fields; preserve public signatures.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Modify'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Modify'}
  - requirementId: ND-DPA-092
    normalizedRequirement: 'Modify target: main/resources/application-llm.yaml — add explicit default-off prompt-assembly block only; do not edit application.yml or add environment placeholders.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/resources/application-llm.yaml]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Modify'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Modify; Task 2 / Step 7'}
  - requirementId: ND-DPA-093
    normalizedRequirement: 'Modify target: src/test/java/com/example/lms/llm/ModelRuntimeRequestTimelineWiringTest.java — prove source order/scopes, single final physical call, no terminal ownership change.'
    category: test
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [src/test/java/com/example/lms/llm/ModelRuntimeRequestTimelineWiringTest.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Modify'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Modify; Task 5 / Step 2'}
  - requirementId: ND-DPA-094
    normalizedRequirement: 'Create target: main/java/com/example/lms/prompt/assembly/PromptAssemblyProperties.java — default-off bounded configuration; no @Component.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/prompt/assembly/PromptAssemblyProperties.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Create'}
  - requirementId: ND-DPA-095
    normalizedRequirement: 'Create target: main/java/com/example/lms/prompt/assembly/PromptAssemblyCatalog.java — strict one-time classpath manifest parse and immutable validated catalog or one fixed failure code.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/prompt/assembly/PromptAssemblyCatalog.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Create'}
  - requirementId: ND-DPA-096
    normalizedRequirement: 'Create target: main/java/com/example/lms/prompt/assembly/PromptAssemblyPlanner.java — typed signals, exact rules, deterministic conflicts/budgets/hashes.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/prompt/assembly/PromptAssemblyPlanner.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Create'}
  - requirementId: ND-DPA-097
    normalizedRequirement: 'Create target: main/java/com/example/lms/prompt/assembly/PromptAssemblyDecision.java — immutable selected/rejected decision and render-safe selected fragments.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/prompt/assembly/PromptAssemblyDecision.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Create'}
  - requirementId: ND-DPA-098
    normalizedRequirement: 'Create target: main/java/com/example/lms/prompt/assembly/PromptAssemblyTrace.java — final-chat build eligibility scope and atomic hash/count-only internal envelope.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/java/com/example/lms/prompt/assembly/PromptAssemblyTrace.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Create'}
  - requirementId: ND-DPA-099
    normalizedRequirement: 'Create target: main/resources/prompts/assembly/prompt-assembly.v1.yaml — exact dpa-p0 versioned rules and asset hashes.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/resources/prompts/assembly/prompt-assembly.v1.yaml]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Create; Task 2 / Step 6'}
  - requirementId: ND-DPA-100
    normalizedRequirement: 'Create target: main/resources/prompts/system/continuity-context.v1.md — exact four-line REUSED CONVERSATION CONTEXT asset.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/resources/prompts/system/continuity-context.v1.md]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 5 / continuity-context.v1.md'}
  - requirementId: ND-DPA-101
    normalizedRequirement: 'Create target: main/resources/prompts/system/evidence-risk.v1.md — exact three-line SENSITIVE DECISION DISCIPLINE asset.'
    category: source
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [main/resources/prompts/system/evidence-risk.v1.md]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 5 / evidence-risk.v1.md'}
  - requirementId: ND-DPA-102
    normalizedRequirement: 'Create target: src/test/java/com/example/lms/prompt/assembly/PromptAssemblyDisabledBaselineTest.java — conditional binding, no-arg compatibility, disabled/no-match/out-of-scope byte equality, safe failure, enabled placement.'
    category: test
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [src/test/java/com/example/lms/prompt/assembly/PromptAssemblyDisabledBaselineTest.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1'}
  - requirementId: ND-DPA-103
    normalizedRequirement: 'Create target: src/test/java/com/example/lms/prompt/assembly/PromptAssemblyManifestValidationTest.java — strict YAML/classpath/duplicate/unknown/unsafe/hash/budget validation.'
    category: test
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [src/test/java/com/example/lms/prompt/assembly/PromptAssemblyManifestValidationTest.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 1'}
  - requirementId: ND-DPA-104
    normalizedRequirement: 'Create target: src/test/java/com/example/lms/prompt/assembly/PromptAssemblyPlannerTest.java — determinism, no match, caps, trace redaction, symmetric conflicts, trigger bound.'
    category: test
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [src/test/java/com/example/lms/prompt/assembly/PromptAssemblyPlannerTest.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1'}
  - requirementId: ND-DPA-105
    normalizedRequirement: 'Create target: src/test/java/com/example/lms/prompt/assembly/PromptAssemblyLineageJoinTest.java — builder-to-envelope-to-fingerprint-to-attempt lineage, nonretroactivity/nonspillover, late completion, redaction, and truth flags.'
    category: test
    status: held
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    targetFiles: [src/test/java/com/example/lms/prompt/assembly/PromptAssemblyLineageJoinTest.java]
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Add'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1'}
  - requirementId: ND-DPA-106
    normalizedRequirement: 'Explicitly unchanged/excluded: PromptBuilder, PromptContext, PromptAssetService; tool registry/policy/invoker/controller/manifest; model-router/PromptPose/Plan DSL/MCP/memory persistence; Gradle/dependencies; DB/Supabase/migrations/RLS/credentials/env names; app/src/main/java_clean and app/src/main/resources; inactive roots, archives, backups, generated outputs, alternate manifests.'
    category: non-goal
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 13. File Map / Explicitly unchanged'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Scope and File Map / Generated or inactive surfaces that remain untouched'}
  - requirementId: ND-DPA-107
    normalizedRequirement: 'P1 is separately authorized: deterministic capability eligibility, optional LLM ranking only among eligible IDs, then existing AgentToolInvoker authority; unknown apps yield read-only discovery or HOLD; no invented capability/plugin/permission; capability reuse->extend->create with schemas, authority, budget, redaction, failure, rollback, nonduplication, and falsifier.'
    category: non-goal
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 14. P1 and P2 Separation / P1'}]
  - requirementId: ND-DPA-108
    normalizedRequirement: 'P2 is separately authorized: version-isolated rewards, offline replay/holdout/shadow/canary/human-reviewed promotion and rollback; verifier-owned outcomes distinguish routing/authorization/invocation/timeout/verifier/user-abort; optional server-only redacted Supabase projection requires separate storage/schema/retention/migration/project/Data-API/RLS/grant authority and never stores raw prompts/summaries/queries/responses/credentials/headers/arbitrary metadata.'
    category: non-goal
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 14. P1 and P2 Separation / P2'}]
  - requirementId: ND-DPA-109
    normalizedRequirement: Every PowerShell block that runs Gradle or reads Task 1 state starts in the declared Desktop root, sets split build output host `desktop`, host-local GRADLE_USER_HOME and project cache, and reconstructs the fixed temp rollback state path.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    verificationCommand: |
      Set-Location "C:\AbandonWare\demo-1\demo-1\src"
      $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
      $env:AWX_BUILD_HOST_ID = 'desktop'
      $env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
      $pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop-dpa-p0"
      New-Item -ItemType Directory -Force -Path $pcd | Out-Null
      $stateRoot = Join-Path ([IO.Path]::GetTempPath()) 'awx-dpa-p0-preimage-20260802'
      $statePath = Join-Path $stateRoot 'state.json'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '### PowerShell bootstrap for every command block'}]
  - requirementId: ND-DPA-110
    normalizedRequirement: Validate the frozen Goal/Directive packet before implementation; missing validator or any metric failure is RED/HOLD.
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: packet validator is missing or canonicalQueryCount/snapshotHash/order/scenario coverage/score/bounds differs.
    greenAssertion: 'exit 0; canonicalQueryCount=3; snapshotHashMatch=true; orderStable=true; scenarioCoverageRatio=1.0; scoreMatches=true; packetBoundsPass=true.'
    verificationCommand: |
      python scripts\validate_goal_directive_packets.py validate `
        --input docs\superpowers\evidence\2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 1 / Step 1'}]
  - requirementId: ND-DPA-111
    normalizedRequirement: Capture current Desktop root, branch, status, worktrees, index lock, top-level PatchDrop patch count, and ports 8080/8081 before edit; unrelated dirty paths are preserved and documented.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: wrong root, absent branch, index lock, pending top-level patch, target overlap, or occupied verification port when runtime proof is needed.
    greenAssertion: declared root true, branch present, index lock false, pending patch count 0, no target dirty overlap.
    verificationCommand: 'Run the exact PowerShell block in Task 1 Step 3 (`git branch --show-current`, `git status --short`, `git worktree list`, `.git\index.lock`, top-level `*.patch`, and ports 8080/8081) and emit only counts/booleans.'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 1 / Step 3'}]
  - requirementId: ND-DPA-112
    normalizedRequirement: Prove Gradle root and :app projects and exact active source directories before edit.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: "`wrong-sourceset` when the four root source rows or at least two :app source rows are absent."
    greenAssertion: root project exists; root main/java, main/resources, src/test/java, src/test/resources and :app java_clean/resources are proven.
    verificationCommand: |
      .\gradlew.bat -q projects --no-daemon --project-cache-dir $pcd
      Select-String -Path build.gradle.kts -Pattern 'srcDirs\("main/java"\)','srcDirs\("main/resources"\)','srcDirs\("src/test/java"\)','srcDirs\("src/test/resources"\)'
      Select-String -Path app\build.gradle.kts -Pattern 'src/main/java_clean','src/main/resources'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 1 / Step 4'}]
  - requirementId: ND-DPA-113
    normalizedRequirement: Freeze five existing-file preimages and prove twelve declared new targets absent in a non-overwritable temp state; pin the ordered 17-target set to `sha256:de5b65a05d6dbb5e6a46c5cb97d0f8c81b44a439636d74433f6142a1afe7ee6d`.
    category: rollback
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: rollback-preimage-already-exists, declared-new-target-already-exists, target-set hash mismatch, missing preimage, or copy hash mismatch.
    greenAssertion: preimageHashCount=5, declaredNewTargetPreexistCount=0, stateWritten=true, pinned target-set hash exact, no checkout mutation.
    verificationCommand: 'Run the complete Task 1 Step 5 block; copy each existing target to `%TEMP%\awx-dpa-p0-preimage-20260802`, compare SHA-256, and atomically write schema `awx.dpa.rollback.v1` state.json.'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 1 / Step 5'}]
  - requirementId: ND-DPA-114
    normalizedRequirement: Existing prompt/asset/timeline baseline must be GREEN before P0; a pre-existing failure is `baseline-red` and stops implementation.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: all seven named baseline tests pass freshly.
    verificationCommand: |
      .\gradlew.bat test --rerun-tasks --fail-fast `
        --tests "com.example.lms.prompt.PromptBuilderBoundaryTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderAnswerDisciplineTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderRetrievalOffDirectModeTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderInteractionEvidencePolicyTest" `
        --tests "com.example.lms.service.prompt.PromptAssetServicePublicContractTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineWiringTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 1 / Step 6'}]
  - requirementId: ND-DPA-115
    normalizedRequirement: Manifest test `defaultsAreOffAndBounded` asserts disabled default, exact manifest resource, schema 1, max fragments 8, max chars 4000, SAFE_ID length 64/65 boundary, and SAFE_ASSET_ID length 128/129 boundary.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: every named default and regex boundary assertion passes.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 1 / defaultsAreOffAndBounded'}]
  - requirementId: ND-DPA-116
    normalizedRequirement: Manifest test `checkedInManifestLoadsOnlyThroughPublicAssetIds` asserts valid=true, failureClass none, manifest dpa-p0 version 1.0.0, exact two ordered public asset IDs, and full manifest SHA-256.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: valid fragment count 2 and public asset resolution only.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 1 / checkedInManifestLoadsOnlyThroughPublicAssetIds'}]
  - requirementId: ND-DPA-117
    normalizedRequirement: Manifest tests separately fail closed for unsafe external manifest location (`manifest_location_invalid`), missing manifest (`manifest_missing`), and configured+manifest future schema (`manifest_schema_invalid`).
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: invalid snapshots have empty fragment lists and exact fixed failure classes.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 1 / unsafeExternalManifestLocationFailsClosed; missingManifestFailsClosed; configurableSchemaCannotAdvanceBeyondP0'}]
  - requirementId: ND-DPA-118
    normalizedRequirement: 'Manifest invalid-family test covers every exact case and reason: duplicate key/manifest_duplicate_key; malformed YAML/manifest_parse_error; unknown root field/manifest_unknown_field; unknown signal/manifest_unknown_signal; invalid operand or unknown REGEX operator/manifest_rule_invalid; schema mismatch/manifest_schema_invalid; duplicate fragment/manifest_fragment_duplicate; traversal or internal `..` asset/manifest_asset_invalid; missing asset/manifest_asset_missing; hash mismatch/manifest_asset_hash_mismatch; maxChars 4001/manifest_budget_invalid; >65536 bytes/manifest_oversize. Serialized invalid snapshots contain neither raw-yaml-sentinel nor asset body.'
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: all fourteen invalid fixtures return their exact fixed reason, empty rows, and zero raw sentinel hits.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 1 / duplicateKeysUnknownSignalsUnsafeAssetsAndHashMismatchFailClosed'}]
  - requirementId: ND-DPA-119
    normalizedRequirement: Manifest RED is compilation failure because properties/catalog types do not exist; do not weaken tests.
    category: test
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: required PromptAssemblyProperties and PromptAssemblyCatalog types are absent.
    verificationCommand: '.\gradlew.bat test --tests "com.example.lms.prompt.assembly.PromptAssemblyManifestValidationTest" --no-daemon --project-cache-dir $pcd'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 2'}]
  - requirementId: ND-DPA-120
    normalizedRequirement: Manifest GREEN runs manifest validation plus existing PromptAssetService public-contract regression.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: PASS; valid fragment count 2; every invalid fixture fixed reason; raw sentinel hit count 0.
    verificationCommand: |
      .\gradlew.bat test --rerun-tasks --fail-fast `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyManifestValidationTest" `
        --tests "com.example.lms.service.prompt.PromptAssetServicePublicContractTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 2 / Step 8'}]
  - requirementId: ND-DPA-121
    normalizedRequirement: Planner test `sameTypedSignalsProduceTheSameOrderAndFullHashes` uses different raw memory/query strings but expects APPLIED order `[evidence-risk, continuity-context]`, identical signal and decision hashes, and full decision/ordered hashes.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: raw content cannot affect typed signal snapshot, ordering, or full hashes.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1 / sameTypedSignalsProduceTheSameOrderAndFullHashes'}]
  - requirementId: ND-DPA-122
    normalizedRequirement: Planner test `ordinaryNoMemoryContextSelectsNothingWithoutFailure` expects SKIPPED/no_match, empty fragments, and totalChars 0.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: ordinary GENERAL/NORMAL context has no failure and no dynamic bytes.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1 / ordinaryNoMemoryContextSelectsNothingWithoutFailure'}]
  - requirementId: ND-DPA-123
    normalizedRequirement: Planner test `fragmentAndCharacterCapsAreDeterministic` expects maxFragments=1 to select only evidence-risk and record rejection; maxChars=1 yields SKIPPED/budget_exhausted with empty fragments and a different decision hash.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: outcome/failure and aggregate state are committed by decision hash.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1 / fragmentAndCharacterCapsAreDeterministic'}]
  - requirementId: ND-DPA-124
    normalizedRequirement: Planner test `traceEnvelopeContainsOnlyAllowlistedMetadata` expects observed=true, rawIncluded=false, selectedCount=2, full decision hash, and no raw memory or either asset heading in serialized trace.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: raw sentinel and asset-text hit count 0.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1 / traceEnvelopeContainsOnlyAllowlistedMetadata'}]
  - requirementId: ND-DPA-125
    normalizedRequirement: Planner conflict test runs forward, reversed, and one-sided low->high manifests; high wins each, rejectedCount=1, and orderedFragmentHash is identical, proving symmetric conflict checks independent of manifest order.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: only high survives in all three fixtures and no raw content enters decision payload.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1 / higherPriorityConflictWinsRegardlessOfManifestOrder'}]
  - requirementId: ND-DPA-126
    normalizedRequirement: Planner trigger-bound test uses eight fragments with sixteen requiredAll and sixteen requiredAny matches each and asserts selected 8 and triggerCount exactly 256, never greater.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: triggerCount=256 and <=256.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 1 / selectedRuleTriggerCountReachesButNeverExceeds256'}]
  - requirementId: ND-DPA-127
    normalizedRequirement: Planner RED is compilation failure because planner, decision, and trace types do not exist.
    category: test
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: required PromptAssemblyPlanner, PromptAssemblyDecision, and PromptAssemblyTrace types are absent.
    verificationCommand: '.\gradlew.bat test --tests "com.example.lms.prompt.assembly.PromptAssemblyPlannerTest" --no-daemon --project-cache-dir $pcd'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 2'}]
  - requirementId: ND-DPA-128
    normalizedRequirement: Planner GREEN runs manifest and planner suites freshly.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: PASS with stable two-fragment order, raw-input-independent hashes, and zero raw trace hits.
    verificationCommand: |
      .\gradlew.bat test --rerun-tasks --fail-fast `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyManifestValidationTest" `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyPlannerTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 3 / Step 6'}]
  - requirementId: ND-DPA-129
    normalizedRequirement: Boundary test `noArgBuilderKeepsLegacyPathWhenPlannerIsAbsent` expects no dynamic headings and no observed trace from direct `new StandardPromptBuilder()`.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: implicit no-arg compatibility and exact legacy path.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1 / noArgBuilderKeepsLegacyPathWhenPlannerIsAbsent'}]
  - requirementId: ND-DPA-130
    normalizedRequirement: Boundary test `injectedPlannerWithNoMatchAppendsZeroBytes` compares UTF-8 bytes to a no-planner baseline inside FinalChatBuildScope and expects observed skipped/no_match.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: byte arrays exactly equal; no-match is traceable without new bytes.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1 / injectedPlannerWithNoMatchAppendsZeroBytes'}]
  - requirementId: ND-DPA-131
    normalizedRequirement: Boundary test `enabledPlannerRendersOnceBetweenContextHintsAndFixedModeGuards` expects one dynamic heading, one of each asset heading, placement after CRITICAL SYSTEM CONTEXT and before MODE, selectedCount=2, and output delta equal fragmentContentChars plus exact fixed framing.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: one render per fragment at the approved slot with exact framing accounting.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1 / enabledPlannerRendersOnceBetweenContextHintsAndFixedModeGuards'}]
  - requirementId: ND-DPA-132
    normalizedRequirement: Boundary test `plannerCannotActivateOutsideFinalChatBuildScope` gives a verifier-like last answer and expects no dynamic text and no observed trace.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: verifier/corrective call stays byte-compatible and untagged.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1 / plannerCannotActivateOutsideFinalChatBuildScope'}]
  - requirementId: ND-DPA-133
    normalizedRequirement: Boundary test `plannerFailureAppendsZeroBytesAndEmitsOnlyCanonicalSafeFailure` injects raw memory and exception sentinels but expects baseline byte equality plus observed failed/assembly_error, envelope schema v1, manifest schema 0, hash:unknown, and no sentinels.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: planner failure is rawless, fixed-code, and behavior-neutral.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1 / plannerFailureAppendsZeroBytesAndEmitsOnlyCanonicalSafeFailure'}]
  - requirementId: ND-DPA-134
    normalizedRequirement: Boundary test `conditionalBeanIsAbsentByDefaultAndPresentOnlyWhenEnabled` asserts PromptAssemblyPlanner bean absent without property and present only with `prompt-assembly.enabled=true`.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: conditional binding does not make P0 default-on.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 1 / conditionalBeanIsAbsentByDefaultAndPresentOnlyWhenEnabled'}]
  - requirementId: ND-DPA-135
    normalizedRequirement: Boundary RED requires enabled rendering assertions to fail because StandardPromptBuilder has no planner seam.
    category: test
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: no planner seam at canonical builder boundary.
    verificationCommand: '.\gradlew.bat test --tests "com.example.lms.prompt.assembly.PromptAssemblyDisabledBaselineTest" --no-daemon --project-cache-dir $pcd'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 2'}]
  - requirementId: ND-DPA-136
    normalizedRequirement: Boundary GREEN runs all new assembly tests plus existing prompt boundary/answer/retrieval/interaction/evidence/pose/asset regressions.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: no-planner, out-of-scope, and no-match add no bytes; enabled assets render once; verifier-like calls unchanged; all existing boundaries pass.
    verificationCommand: |
      .\gradlew.bat test --rerun-tasks --fail-fast `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyDisabledBaselineTest" `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyManifestValidationTest" `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyPlannerTest" `
        --tests "com.example.lms.prompt.PromptBuilderBoundaryTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderAnswerDisciplineTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderRetrievalOffDirectModeTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderInteractionEvidencePolicyTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderEvidenceMetadataTest" `
        --tests "com.example.lms.prompt.pose.PromptBuilderPromptPoseBoundaryTest" `
        --tests "com.example.lms.service.prompt.PromptAssetServicePublicContractTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 4 / Step 6'}]
  - requirementId: ND-DPA-137
    normalizedRequirement: Lineage test `matchingPhysicalAttemptReceivesRedactedAssemblyEvidence` performs builder scope then matching attempt scope and asserts observed v1/schema1/canonicalization/full hashes/selectedCount2/raw=false while providerAttemptObserved=false and wireAttemptObserved=false; serialized row omits request/session/memory/assistant/user/asset sentinels.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: exactly one matching physical attempt receives only redacted assembly metadata and unchanged truth flags.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1 / matchingPhysicalAttemptReceivesRedactedAssemblyEvidence'}]
  - requirementId: ND-DPA-138
    normalizedRequirement: Lineage non-spillover test makes exactly four calls in order — matching before scope, different inside scope, matching inside scope, matching after close — and asserts only index 2 tagged; prompt fingerprint tuple proves mismatch without printing messages.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: rows 0,1,3 remain false permanently; row 2 true; ledger size 4.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1 / onlyMatchingCallsEnteredInsideScopeAreTagged'}]
  - requirementId: ND-DPA-139
    normalizedRequirement: 'Lineage state-machine tests cover: rawIncluded=true snapshot -> failed/invalid_metadata with four hash:unknown and zero counts; every allowlisted manifest_* safe failure preserves exact reason, schema1, unknown hashes, zero counts; every safe-failure row has raw=false and unchanged provider/wire flags.'
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: invalid metadata normalizes, valid safe-failure taxonomy is preserved, and no raw field is promoted.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1 / invalid snapshot; catalog failure state machines'}]
  - requirementId: ND-DPA-140
    normalizedRequirement: Real catalog-to-attempt failure test sets schemaVersion 2 in YAML and proves builder output equals baseline, trace and matching attempt preserve failed/manifest_schema_invalid with manifest schema1, unknown hashes/zero counts, and no memory/manifest/asset text.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: manifest failure proof uses the real catalog->planner->builder->trace->attempt chain, not only a synthetic Snapshot.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1 / real manifest_schema_invalid chain'}]
  - requirementId: ND-DPA-141
    normalizedRequirement: 'Lineage edge tests cover overlapping A then different B with stale A close: B attempt receives failed/conflicting_bind and stale close cannot clear B; triggerCount 256 remains valid, while 257 normalizes to failed/invalid_metadata with zero counts.'
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: token ownership and trigger bounds fail closed without arbitrary trace values.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1 / overlap; trigger boundary state machines'}]
  - requirementId: ND-DPA-142
    normalizedRequirement: Lineage late-completion test propagates TraceStore context to a raw executor, uses two-second bounded entered/release/future waits, closes outer scope after physical entry, and expects the late first row tagged while a later same-message call after close is untagged; AfterEach clears TraceStore and executor is terminated.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: started worker retains immutable entry capture after scope close without tagging later attempts.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 1 / startedWorkerRetainsCaptureAfterScopeClose'}]
  - requirementId: ND-DPA-143
    normalizedRequirement: Scope-acquisition fail-soft test passes an AbstractList whose size/get throw fixed exceptions, expects no exception, invokes a bare delegate exactly once, and proves observability loss cannot skip or duplicate the model call.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: call counter exactly 1 despite scope fingerprint failure.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 4 / scopeAcquisitionFailureCannotSkipOrDuplicateTheModelCall'}]
  - requirementId: ND-DPA-144
    normalizedRequirement: Wiring test asserts FinalChatBuildScope precedes exact promptBuilder.buildInstructions(ctx), final UserMessage follows build, attempt scope follows final messages, and one callWithRetryReportingSuccess follows scope; exact try resource owns it, occurrence count is 1, no trace `.content`, builder checks scope before planner, FactVerifier opens no scope, and ChatWorkflow owns no terminal phase.
    category: test
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    greenAssertion: source order preserves one physical final call and no authority spillover.
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 2'}]
  - requirementId: ND-DPA-145
    normalizedRequirement: Lineage RED runs the lineage and wiring tests and must fail because scoped binding type/method and row fields are absent.
    category: test
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: physical-attempt binding seam and redacted row fields do not exist.
    verificationCommand: |
      .\gradlew.bat test `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyLineageJoinTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineWiringTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 2'}]
  - requirementId: ND-DPA-146
    normalizedRequirement: 'Tracker accepts only three evidence shapes: canonical unknown; valid catalog observation with schema1/full hashes/bounded counts and applied/none, skipped/no_match, or skipped/budget_exhausted; canonical failed manifest_* schema1 or assembly_error/invalid_metadata/conflicting_bind schema0 with unknown labels/hashes and zero counts. Everything else becomes failed/invalid_metadata; disabled is never an observed decision.'
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 3'}]
  - requirementId: ND-DPA-147
    normalizedRequirement: 'PromptAssemblyAttemptScope state machine is exact: terminal timeline or unknown trace -> no-op; valid/fixed failure plus valid fingerprint -> active; same evidence/fingerprint -> new token same binding; different overlap -> new token failed/conflicting_bind; matching-token close -> none; stale-token close -> no change; no terminal/attempt outcome changes. Scope is idempotent and stores no messages/prompts/fragments/credentials.'
    category: runtime
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 4 / RequestTimeline state machine'}]
  - requirementId: ND-DPA-148
    normalizedRequirement: 'At physical-call entry capture binding evidence by full private scopeHash + item count + UTF-8 byte count into RequestAttemptAppContext before delegate; at append require same timeline and public prompt tuple and read only that immutable context. ExpectedFailureAttemptEvidence and direct completions fallback use the same capture; responses-disabled remains through expected-failure helper; no signature changes or duplicate captures.'
    category: runtime
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Steps 4-5'}]
  - requirementId: ND-DPA-149
    normalizedRequirement: 'Every redacted attempt row appends exactly promptAssemblyObserved, Outcome, FailureClass, EnvelopeSchemaVersion, ManifestSchemaVersion, CanonicalizationVersion, ManifestId, ManifestVersion, ManifestHash, DecisionHash, SignalSnapshotHash, OrderedFragmentHash, SelectedCount, RejectedCount, TriggerCount, FragmentContentChars, and RawIncluded. Central proof may expose outcome/failure/full hashes/counts but forbids IDs, prompt text, raw values, timeline ID, endpoint URL, and arbitrary errors.'
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 5 / attempt row keys; central proof log'}]
  - requirementId: ND-DPA-150
    normalizedRequirement: ChatWorkflow replaces only the instruction assignment with FinalChatBuildScope and wraps only the existing primary retry/fallback invocation in PromptAssemblyAttemptScope after finalized messages; remove the unscoped invocation; do not bind early, read fragment content, add a SystemMessage, clear TraceStore, create tracker, write terminal phase, retain messages, open scopes in verifier/regeneration, or call the model twice.
    category: safety
    status: unimplemented
    decision: retain
    disposition: DEFER
    destinationWorkUnit: D-DPA-P0
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 6'}]
  - requirementId: ND-DPA-151
    normalizedRequirement: Lineage GREEN runs assembly lineage, timeline, wiring, OpenAI response adapter, and Ollama native adapter regressions.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: matching-only joins; before/after/mismatch untagged; late entry capture preserved; raw hits 0; client evidence unchanged; providerAttemptObserved=false and wireAttemptObserved=false remain unchanged.
    verificationCommand: |
      .\gradlew.bat test --rerun-tasks --fail-fast `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyLineageJoinTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineWiringTest" `
        --tests "ai.abandonware.nova.orch.llm.OpenAiResponsesChatModelTest" `
        --tests "com.example.lms.llm.OllamaNativeChatModelTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 5 / Step 7'}]
  - requirementId: ND-DPA-152
    normalizedRequirement: Final declared-path review validates rollback-state schema and exact target set, runs diff check/stat/scoped diff, requires Git status path equality to all 17 targets, and generates a complete no-index diff for each untracked new file from a fresh temp directory.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: preflight state missing/invalid/mismatched, whitespace error, undeclared/missing target, unavailable status, or incomplete new-file review.
    greenAssertion: only five declared modified plus twelve declared new files appear; no generated/dependency/API/DB/tool change; unrelated dirty paths untouched.
    verificationCommand: 'Run the complete Task 6 Step 1 block: validate `awx.dpa.rollback.v1` and pinned target-set hash, `git diff --check`, `git diff --stat`, scoped diff, porcelain path-set compare, and `git diff --no-index` for each new file.'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 1'}]
  - requirementId: ND-DPA-153
    normalizedRequirement: Recompute both prompt assets using UTF-8-sig read, CRLF/CR-to-LF conversion, strip, and SHA-256; compare to manifest pins.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: 'continuity-context.v1=sha256:9323fe31770cf716aa151e286b718593e637b3ced118d2afe73aff4c7dfcf04d; evidence-risk.v1=sha256:6b061ea5ec41c1a8dd893f96c85ec332d8b812c819c87e206cbd0d73839490b9.'
    verificationCommand: 'Run the exact Python `hashlib` block in Task 6 Step 2 over the two declared asset paths.'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 2'}]
  - requirementId: ND-DPA-154
    normalizedRequirement: Run the complete focused suite of four new DPA tests plus eleven existing prompt/asset/timeline/provider regressions with rerun-tasks and fail-fast.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: all fifteen named test selectors PASS with no skipped new test.
    verificationCommand: |
      .\gradlew.bat test --rerun-tasks --fail-fast `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyDisabledBaselineTest" `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyManifestValidationTest" `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyPlannerTest" `
        --tests "com.example.lms.prompt.assembly.PromptAssemblyLineageJoinTest" `
        --tests "com.example.lms.prompt.PromptBuilderBoundaryTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderAnswerDisciplineTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderRetrievalOffDirectModeTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderInteractionEvidencePolicyTest" `
        --tests "com.example.lms.prompt.StandardPromptBuilderEvidenceMetadataTest" `
        --tests "com.example.lms.prompt.pose.PromptBuilderPromptPoseBoundaryTest" `
        --tests "com.example.lms.service.prompt.PromptAssetServicePublicContractTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" `
        --tests "com.example.lms.llm.ModelRuntimeRequestTimelineWiringTest" `
        --tests "ai.abandonware.nova.orch.llm.OpenAiResponsesChatModelTest" `
        --tests "com.example.lms.llm.OllamaNativeChatModelTest" `
        --no-daemon --project-cache-dir $pcd
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 3'}]
  - requirementId: ND-DPA-155
    normalizedRequirement: Run compile, dependency/source-set hygiene, :app classes, and packaging serially with isolated caches.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: all commands PASS; LangChain4j exactly 1.0.1; no inactive source compiled as canonical P0.
    verificationCommand: |
      .\gradlew.bat compileJava --no-daemon --project-cache-dir $pcd
      .\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
      .\gradlew.bat :app:classes --no-daemon --project-cache-dir $pcd
      .\gradlew.bat bootJar --no-daemon --project-cache-dir $pcd
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 15. Verification and Rollback'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 4'}
  - requirementId: ND-DPA-156
    normalizedRequirement: Count-only secret scan covers only added diff lines in five modified files plus full contents of twelve new files; never prints matching lines.
    category: verification
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: "`secret-leak-risk` on any authorization, bearer, key, password, or api-key pattern; stop without exposing content."
    greenAssertion: addedLineSecretHitCount=0, newFileSecretHitCount=0, totalNewSecretHitCount=0.
    verificationCommand: 'Run the complete allowlisted-file regex/count block in Task 6 Step 5; do not rescan existing baseline text as a zero-baseline assertion.'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 5'}]
  - requirementId: ND-DPA-157
    normalizedRequirement: Freeze exactly 17 postimage SHA-256 rows atomically in `awx.dpa.postimage.v1`, revalidate the pinned ordered target-set hash, and do not enumerate directories to enlarge scope.
    category: rollback
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: missing/invalid preflight state, target-set mismatch, missing target, or preexisting postimage file.
    greenAssertion: postimageHashCount=17 and postimageStateWritten=true.
    verificationCommand: 'Run the complete Task 6 Step 6 postimage block against state.json and exact existingTargets+newTargets arrays.'
    sourceRefs: [{path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 6'}]
  - requirementId: ND-DPA-158
    normalizedRequirement: 'Final evidence may report applicationAssemblyVerdict=PASS and clientAttemptJoinVerdict=PASS only after their focused tests; restartDurableLineageVerdict, providerAttemptVerdict, wireAttemptVerdict, and runtimeLineageVerdict remain HOLD; desktopFinalProof=PASS only if every Desktop command passed.'
    category: verification
    status: evidence_needed
    decision: hold
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 12. Lineage Contract; ## 17. SourceDirective'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 6 / verdict report'}
  - requirementId: ND-DPA-159
    normalizedRequirement: Rollback first sets `prompt-assembly.enabled=false` and reruns PromptAssemblyDisabledBaselineTest plus existing prompt regressions; expected outcome is byte-compatible baseline.
    category: rollback
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    greenAssertion: disabled byte equality before any code rollback.
    verificationCommand: 'Set `prompt-assembly.enabled=false`; rerun PromptAssemblyDisabledBaselineTest and the existing prompt regression subset using the isolated Desktop caches.'
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 15. Verification and Rollback'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 7 / disabled baseline'}
  - requirementId: ND-DPA-160
    normalizedRequirement: Code rollback is compare-and-swap only and nonmutating while `$executeCodeRollback=false`; validate rollback/postimage schemas, counts 5+12+17, pinned target set, unique/exact postimage map, and current hash equality for all 17 before any write.
    category: rollback
    status: pending
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: rollback-evidence-missing/invalid, target mismatch, duplicate postimage, missing target, or any `rollback-ownership-changed`; keep feature disabled and leave source untouched.
    greenAssertion: rollbackCasReady=true, rollbackTargetCount=17, executeCodeRollback=false unless an explicit Desktop reviewer chooses rollback.
    verificationCommand: 'Run the exact CAS-readiness prefix of Task 6 Step 7 with `$executeCodeRollback=$false`.'
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 15. Verification and Rollback'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 7 / CAS readiness'}
  - requirementId: ND-DPA-161
    normalizedRequirement: After explicit reviewer selection only, code rollback revalidates five frozen preimage hashes, restores only those five existing files, removes only the twelve declared created leaf files non-recursively, then proves five restored hashes and twelve absences; never reset, recurse, overwrite later edits, or delete outside the exact target set.
    category: rollback
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    redReason: rollback-preimage-invalid, rollback-restore-mismatch, rollback-new-target-removal-mismatch, or ownership change.
    greenAssertion: exact five restores and twelve removals verified after explicit rollback approval.
    verificationCommand: 'Set `$executeCodeRollback=$true` only after explicit Desktop review and run the exact restore/removal/postcheck suffix in Task 6 Step 7.'
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 15. Verification and Rollback'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: 'Task 6 / Step 7 / executeCodeRollback branch'}
  - requirementId: ND-DPA-162
    normalizedRequirement: 'Stop conditions are dirty overlap, index lock, wrong source set, source lease/preimage conflict, pending PatchDrop, invalid manifest, disabled-byte mismatch, raw-content or secret hit, public API/DB/credential/dependency expansion, mixed LangChain4j, provider/wire claim promotion, focused or broad verification failure, and rollback ownership change. Time budget is 240 minutes hard cap; suggested commits are records only and no add/commit/push/deploy/publish is authorized.'
    category: safety
    status: held
    decision: retain
    disposition: HOLD
    destinationWorkUnit: D-DPA-P0
    sourceRefs:
      - {path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md, sha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1, locator: '## 15. Verification and Rollback; ## 16. GoalContract / stopConditions; timeBudgetMinutes'}
      - {path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md, sha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6, locator: '## Global Constraints; Task 1 Step 7; Task 2 Step 9; Task 3 Step 7; Task 4 Step 7; Task 5 Step 8; Task 6 Step 8'}
  - {requirementId: ND-MAIN-CHAT-PARENT-065, rowKind: non_goal, normalizedRequirement: "Do not replace the Spring chatbot wholesale or rewrite its layout; preserve /chat and /chat-ui as the authoritative Spring UI.", sourcePointers: [I10-GOAL, I10-EVIDENCEUI, I11-GOAL, I11-SOURCE], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-066, rowKind: non_goal, normalizedRequirement: "Browser is not a file editor and cannot authorize or prove source mutation.", sourcePointers: [I11-GOAL, I11-BROWSER], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-067, rowKind: non_goal, normalizedRequirement: "No speculative Java rewrite, duplicate route/helper, new route/orchestration framework, or public API change is in scope.", sourcePointers: [I10-BACKEND, I11-GOAL, I11-SOURCE, I11-P6], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-068, rowKind: non_goal, normalizedRequirement: "No DB, Supabase, provider, credential, environment-name, security-policy, deployment, or external-system mutation is in scope.", sourcePointers: [I11-GOAL, I11-P3, I11-P9], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-069, rowKind: non_goal, normalizedRequirement: "Do not infer provider success, semantic success, or cost savings from UI delivery or local fallback.", sourcePointers: [I9-P7, I10-VERIFY, I11-GOAL, I11-P8], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-070, rowKind: non_goal, normalizedRequirement: "Do not commit, stage, push, deploy, persist credentials, mutate DB/Supabase, or send external messages.", sourcePointers: [I11-GOAL, I11-P9], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-NEXT-BFF-093, rowKind: non_goal, normalizedRequirement: "Do not silently convert the current JS Next frontend to TypeScript merely to satisfy route.ts policy.", sourcePointers: [I10-NEXTGATE], category: non-goal, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY}
  - {requirementId: ND-NEXT-BFF-094, rowKind: non_goal, normalizedRequirement: "Do not claim Next architecture complete when only the existing Spring fallback UI was improved.", sourcePointers: [I9-DECOMP, I9-ACCEPT], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-095, rowKind: non_goal, normalizedRequirement: "Do not downgrade Next; if a proven owner uses Next 16, follow its local docs rather than forcing Next 14.", sourcePointers: [I9-DECOMP], category: non-goal, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-096, rowKind: rollback, normalizedRequirement: "Capture byte-for-byte task-local preimage copies and SHA-256 for every selected declared target under the same Desktop lease.", sourcePointers: [I11-GOAL, I11-P5], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-NEXT-BFF-097, rowKind: rollback, normalizedRequirement: "Immediately before apply_patch, rehash each selected target and HOLD on any preimage change.", sourcePointers: [I11-P5], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-NEXT-BFF-098, rowKind: rollback, normalizedRequirement: "On failure, restore only declared targets from verified task-local preimages under the same lease.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P7], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-NEXT-BFF-099, rowKind: rollback, normalizedRequirement: "After restoration, verify restored SHA-256, rerun focused fixtures, then release the exact lease.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P7, I11-P9], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-NEXT-BFF-100, rowKind: rollback, normalizedRequirement: "Never use git reset --hard or git checkout -- for rollback in the dirty checkout.", sourcePointers: [I11-GOAL, I11-P7], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-NEXT-BFF-101, rowKind: rollback, normalizedRequirement: "Final report records per-file rollback state and pre/postimage hashes even when no rollback was needed.", sourcePointers: [I9-REPORT, I10-REPORT, I11-P9], category: verification, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-071, rowKind: hold_condition, normalizedRequirement: "HOLD on c-canonical-unavailable or Desktop Git top-level mismatch.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-072, rowKind: hold_condition, normalizedRequirement: "HOLD on empty or unowned branch / branch-ownership-mismatch.", sourcePointers: [I9-P0, I11-GOAL, I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-073, rowKind: hold_condition, normalizedRequirement: "HOLD on index-lock-conflict.", sourcePointers: [I9-P0, I11-GOAL, I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-074, rowKind: hold_condition, normalizedRequirement: "HOLD on dirty-target-overlap.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-075, rowKind: hold_condition, normalizedRequirement: "HOLD on any complete top-level patch-drop-pending state.", sourcePointers: [I9-P0, I10-HOLD, I11-GOAL, I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-076, rowKind: hold_condition, normalizedRequirement: "HOLD on source-lease-conflict or foreign lease.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P5], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-077, rowKind: hold_condition, normalizedRequirement: "HOLD on active-sourceSet uncertainty or wrong-sourceset.", sourcePointers: [I9-P0, I10-HOLD, I11-GOAL, I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-078, rowKind: hold_condition, normalizedRequirement: "HOLD on LangChain4j version impurity.", sourcePointers: [I9-P0, I10-HOLD, I11-GOAL], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-079, rowKind: hold_condition, normalizedRequirement: "HOLD on preimage-changed.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P5], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-080, rowKind: hold_condition, normalizedRequirement: "HOLD on reparse-traversal-risk or any target outside canonical C-root.", sourcePointers: [I11-GOAL, I11-P5], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-081, rowKind: hold_condition, normalizedRequirement: "HOLD on target-red-missing.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P4], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-082, rowKind: hold_condition, normalizedRequirement: "HOLD on node-toolchain-incompatible; Notebook Node v14 fixture failures are not Desktop RED proof.", sourcePointers: [I11-SNAPSHOT, I11-SOURCE, I11-P4], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-083, rowKind: hold_condition, normalizedRequirement: "HOLD on stream-event-contract-gap unless a current target-specific RED owns the exact repair.", sourcePointers: [I10-HOLD, I11-SOURCE], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-084, rowKind: hold_condition, normalizedRequirement: "HOLD on session-contract-gap unless a current target-specific RED owns the exact repair.", sourcePointers: [I10-HOLD, I11-SOURCE], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-085, rowKind: hold_condition, normalizedRequirement: "HOLD on a concrete backend gap and issue a separate target-specific SourceDirective rather than widening V2.", sourcePointers: [I11-SOURCE, I11-P3], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND}
  - {requirementId: ND-MAIN-CHAT-PARENT-086, rowKind: hold_condition, normalizedRequirement: "HOLD on frontend-route-policy-gap until JS ownership versus route.ts migration is explicitly decided.", sourcePointers: [I10-NEXTGATE, I10-HOLD], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY}
  - {requirementId: ND-MAIN-CHAT-PARENT-087, rowKind: hold_condition, normalizedRequirement: "HOLD on security-probe-forbidden; do not loosen security to make Browser proof pass.", sourcePointers: [I10-RAG, I10-HOLD], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-RAG-PROBE}
  - {requirementId: ND-MAIN-CHAT-PARENT-088, rowKind: hold_condition, normalizedRequirement: "HOLD on secret-leak-risk.", sourcePointers: [I9-P0, I10-HOLD, I11-GOAL, I11-SOURCE, I11-P7], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-089, rowKind: hold_condition, normalizedRequirement: "HOLD on browser-tab-binding-unavailable.", sourcePointers: [I11-SOURCE, I11-BROWSER], category: runtime, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-MAIN-CHAT-PARENT-090, rowKind: hold_condition, normalizedRequirement: "HOLD on browser-smoke-blocked and report one exact blocker.", sourcePointers: [I9-ACCEPT, I10-HOLD, I11-SOURCE, I11-P8], category: runtime, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-MAIN-CHAT-PARENT-091, rowKind: hold_condition, normalizedRequirement: "HOLD runtimeLineageVerdict when direct request/options-hash and provider attempt/response evidence are missing.", sourcePointers: [I11-SOURCE, I11-P8, I11-VERIFY], category: runtime, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-MAIN-CHAT-PARENT-092, rowKind: hold_condition, normalizedRequirement: "HOLD desktopFinalProof until fresh current C-root command and Browser evidence exists.", sourcePointers: [I10-ACCEPT, I11-SOURCE, I11-P8, I11-VERIFY], category: verification, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-MAIN-CHAT-PARENT-093, rowKind: hold_condition, normalizedRequirement: "HOLD when the required verifier or compatible Java/Gradle/Git/Node tool is unavailable.", sourcePointers: [I9-P0, I11-GOAL], category: verification, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-MAIN-CHAT-PARENT-094, rowKind: hold_condition, normalizedRequirement: "HOLD if stable three-query adjudication is not APPLY in both A-B and B-A order.", sourcePointers: [I11-GOAL, I11-P2], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-042, rowKind: red, normalizedRequirement: "On Node 18 or newer, run all three existing chat UI fixtures before editing; a target-bound failing fixture for the intended missing parity behavior is mandatory.", sourcePointers: [I11-SOURCE, I11-P4], category: test, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, redTests: [scripts/chat_ui_browser_fault_fixture_tests.js, scripts/chat_ui_stream_contract_tests.js, scripts/chat_ui_view_layer_contract_tests.js]}
  - {requirementId: ND-MAIN-CHAT-PARENT-043, rowKind: red, normalizedRequirement: "Browser may reproduce a localhost RED only as supporting evidence; it cannot replace an existing target-bound fixture RED.", sourcePointers: [I11-SOURCE, I11-P4], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF, redTests: [fresh-localhost-browser-red]}
  - {requirementId: ND-MAIN-CHAT-PARENT-044, rowKind: red, normalizedRequirement: "If the desired parity row has no existing failing fixture, stop with target-red-missing and issue a separate target-specific directive; do not create or edit a fixture under V2.", sourcePointers: [I11-SOURCE, I11-P4], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, redTests: [target-red-missing]}
  - {requirementId: ND-MAIN-CHAT-PARENT-045, rowKind: red, normalizedRequirement: "If all desired behavior is already proved, return no_patch_needed and do not mutate source.", sourcePointers: [I11-P4], category: test, status: evidence_needed, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT, redTests: [already-present-characterization]}
  - {requirementId: ND-MAIN-CHAT-PARENT-046, rowKind: green, normalizedRequirement: "Rerun the exact failing RED fixture after a later authorized patch.", sourcePointers: [I11-SOURCE], category: test, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, greenTests: [exact-red-fixture]}
  - {requirementId: ND-MAIN-CHAT-PARENT-047, rowKind: green, normalizedRequirement: "All three chat UI fixtures must pass on compatible Node after a later authorized patch.", sourcePointers: [I11-SOURCE, I11-P7], category: test, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, greenTests: [scripts/chat_ui_browser_fault_fixture_tests.js, scripts/chat_ui_stream_contract_tests.js, scripts/chat_ui_view_layer_contract_tests.js]}
  - {requirementId: ND-MAIN-CHAT-PARENT-048, rowKind: green, normalizedRequirement: "Postimage hashes, declared changed-path subset, and changed-file count must match TargetBoundaryProof.", sourcePointers: [I11-SOURCE, I11-P5, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, greenTests: [postimage-and-path-subset]}
  - {requirementId: ND-MAIN-CHAT-PARENT-049, rowKind: green, normalizedRequirement: "Changed-file count-only secret scan must report zero hits without printing matching text.", sourcePointers: [I10-SECRET, I11-SOURCE, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, greenTests: [secretPatternHits=0]}
  - {requirementId: ND-MAIN-CHAT-PARENT-050, rowKind: green, normalizedRequirement: "After command proof, fresh Browser /chat must prove visible current content, final-or-typed-error, explicit cancel, session data-or-typed-state, bounded evidence state, unknown-event survival, and zero console crashes.", sourcePointers: [I10-VERIFY, I11-SOURCE, I11-P8], category: runtime, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF, greenTests: [fresh-browser-chat-smoke]}
  - {requirementId: ND-NEXT-BFF-081, rowKind: verification, normalizedRequirement: "Run canonical Desktop harness before any Next/BFF action.", sourcePointers: [I9-RULES, I9-P0, I10-FIRST, I11-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY, verificationCommand: 'powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\desktop_safe_patch_harness.ps1 -Root . -NoWrite'}
  - {requirementId: ND-NEXT-BFF-082, rowKind: verification, normalizedRequirement: "Prove current Gradle project topology.", sourcePointers: [I9-P0, I11-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY, verificationCommand: '.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache'}
  - {requirementId: ND-NEXT-BFF-083, rowKind: verification, normalizedRequirement: "Prove sourceSet hygiene and exact LangChain4j version purity.", sourcePointers: [I9-P0, I10-VERIFY, I11-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY, verificationCommand: '.\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity --no-daemon --project-cache-dir $ProjectCache'}
  - {requirementId: ND-NEXT-BFF-084, rowKind: verification, normalizedRequirement: "Reconfirm active root and app source directories from live Gradle files.", sourcePointers: [I9-EVIDENCE, I11-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY, verificationCommand: 'rg -n ''srcDirs|java_clean|main/resources'' build.gradle.kts app\build.gradle.kts'}
  - {requirementId: ND-NEXT-BFF-085, rowKind: verification, normalizedRequirement: "Discover a real Next owner without traversing generated/vendor folders.", sourcePointers: [I9-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER, verificationCommand: 'rg --files --hidden -g package.json -g next.config.* -g src/app/** -g app/**/route.ts -g pages/api/** -g !**/.git/** -g !**/node_modules/** -g !**/.next/** -g !**/build/** -g !**/.gradle/**'}
  - {requirementId: ND-NEXT-BFF-086, rowKind: verification, normalizedRequirement: "Read the selected Next package and only its existing scripts before invoking npm.", sourcePointers: [I9-P1, I10-NEXTGATE], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER, verificationCommand: 'Get-Content .\frontend\package.json'}
  - {requirementId: ND-NEXT-BFF-087, rowKind: verification, normalizedRequirement: "Map current backend endpoint contracts before considering Java changes.", sourcePointers: [I9-P2], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-BACKEND, verificationCommand: 'rg -n ''@RequestMapping|@GetMapping|@PostMapping|TEXT_EVENT_STREAM|ServerSentEvent|/api/chat|/api/rag|/internal/agent'' main/java main/resources -g *.java -g *.js -g *.html'}
  - {requirementId: ND-NEXT-BFF-088, rowKind: verification, normalizedRequirement: "Map security ownership before any browser-route proposal.", sourcePointers: [I9-P2], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-BACKEND, verificationCommand: 'rg -n ''SecurityFilterChain|/api/chat|/api/rag|/internal/agent|permitAll|hasRole|csrf'' main/java/com/example/lms/config main/java/com/example/lms/security -g *.java'}
  - {requirementId: ND-NEXT-BFF-089, rowKind: verification, normalizedRequirement: "Map prompt and trace boundaries before any integration proposal.", sourcePointers: [I9-P2], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-BACKEND, verificationCommand: 'rg -n ''PromptBuilder.build|setFinalPromptText|final prompt|TraceStore|DebugEventStore'' main/java/com/example/lms -g *.java'}
  - {requirementId: ND-NEXT-BFF-090, rowKind: verification, normalizedRequirement: "Run existing frontend lint only if the selected package defines it.", sourcePointers: [I9-P3, I9-P7, I10-NEXTGATE, I10-VERIFY], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER, verificationCommand: 'Push-Location .\frontend; npm run lint; Pop-Location'}
  - {requirementId: ND-NEXT-BFF-091, rowKind: verification, normalizedRequirement: "Run existing frontend tests only if the selected package defines them.", sourcePointers: [I10-NEXTGATE, I10-VERIFY], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER, verificationCommand: 'Push-Location .\frontend; npm test; Pop-Location'}
  - {requirementId: ND-NEXT-BFF-092, rowKind: verification, normalizedRequirement: "Run existing frontend build only if the selected package defines it.", sourcePointers: [I9-P3, I9-P7, I10-NEXTGATE, I10-VERIFY], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER, verificationCommand: 'Push-Location .\frontend; npm run build; Pop-Location'}
  - {requirementId: ND-MAIN-CHAT-PARENT-051, rowKind: verification, normalizedRequirement: "Verify Java compilation after an independently authorized backend/resource change.", sourcePointers: [I9-P7, I10-VERIFY], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND, verificationCommand: '.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache'}
  - {requirementId: ND-MAIN-CHAT-PARENT-052, rowKind: verification, normalizedRequirement: "Verify app classes after an independently authorized backend/resource change.", sourcePointers: [I9-P7, I10-VERIFY, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: '.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache'}
  - {requirementId: ND-MAIN-CHAT-PARENT-053, rowKind: verification, normalizedRequirement: "Verify bootJar after an independently authorized backend/resource change.", sourcePointers: [I9-P7, I10-VERIFY, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: '.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $ProjectCache'}
  - {requirementId: ND-MAIN-CHAT-PARENT-054, rowKind: verification, normalizedRequirement: "Run the Browser-fault fixture on compatible Node.", sourcePointers: [I11-P4, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'node .\scripts\chat_ui_browser_fault_fixture_tests.js'}
  - {requirementId: ND-MAIN-CHAT-PARENT-055, rowKind: verification, normalizedRequirement: "Run the stream-contract fixture on compatible Node.", sourcePointers: [I11-P4, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'node .\scripts\chat_ui_stream_contract_tests.js'}
  - {requirementId: ND-MAIN-CHAT-PARENT-056, rowKind: verification, normalizedRequirement: "Run the view-layer fixture on compatible Node.", sourcePointers: [I11-P4, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'node .\scripts\chat_ui_view_layer_contract_tests.js'}
  - {requirementId: ND-MAIN-CHAT-PARENT-057, rowKind: verification, normalizedRequirement: "Run PatchDrop inventory before a direct Desktop lane.", sourcePointers: [I11-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1'}
  - {requirementId: ND-MAIN-CHAT-PARENT-058, rowKind: verification, normalizedRequirement: "Check current Desktop source-edit lease state.", sourcePointers: [I11-P1], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action status -Role desktop -Root $desktopRoot'}
  - {requirementId: ND-MAIN-CHAT-PARENT-059, rowKind: verification, normalizedRequirement: "Freeze one EvidenceSnapshot and run exactly POSITIVE_QUERY, NEGATIVE_QUERY, and NEUTRAL_QUERY; A-B and B-A must both be APPLY.", sourcePointers: [I11-P2], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'demo1-source-edit-three-way-preflight: POSITIVE_QUERY, NEGATIVE_QUERY, NEUTRAL_QUERY; require stable APPLY in A-B and B-A'}
  - {requirementId: ND-MAIN-CHAT-PARENT-060, rowKind: verification, normalizedRequirement: "Acquire a Desktop source lease with the exact target set before preimage capture or mutation.", sourcePointers: [I11-P5], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action begin -Role desktop -Root $desktopRoot -Topic $Topic -OwnerId $OwnerId'}
  - {requirementId: ND-MAIN-CHAT-PARENT-061, rowKind: verification, normalizedRequirement: "Release the same Desktop lease after verified completion or completed rollback.", sourcePointers: [I11-P9], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action end -Role desktop -Root $desktopRoot -Topic $Topic -OwnerId $OwnerId'}
  - {requirementId: ND-MAIN-CHAT-PARENT-062, rowKind: verification, normalizedRequirement: "Start the current Desktop build only on a controlled free port and isolated Gradle cache after command proof.", sourcePointers: [I11-P8], category: runtime, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF, verificationCommand: '.\gradlew.bat bootRun --no-daemon --project-cache-dir $ProjectCache --args=''--server.port=18080'''}
  - {requirementId: ND-MAIN-CHAT-PARENT-063, rowKind: verification, normalizedRequirement: "Run fresh Browser proof at http://127.0.0.1:18080/chat only after local command proof.", sourcePointers: [I9-P7, I10-VERIFY, I11-P8], category: runtime, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF, verificationCommand: 'browser:control-in-app-browser open http://127.0.0.1:18080/chat and execute bounded chat/cancel/session/evidence/unknown-event checks'}
  - {requirementId: ND-MAIN-CHAT-PARENT-064, rowKind: verification, normalizedRequirement: "Run a changed-path count-only secret scan; never persist or print matching text.", sourcePointers: [I9-P8, I10-SECRET, I11-P7], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, verificationCommand: 'powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\git_secret_guard.ps1 -Mode manual -Path <declared-targets>'}
  - {requirementId: ND-NEXT-BFF-051, rowKind: rule, normalizedRequirement: "New Next App Router API handlers use app/**/route.ts only.", sourcePointers: [I9-RULES], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, conflictsWith: [ND-NEXT-BFF-060]}
  - {requirementId: ND-NEXT-BFF-052, rowKind: forbidden_rule, normalizedRequirement: "Do not add pages/api/** unless an existing Pages Router owner and explicit user request are both proven.", sourcePointers: [I9-RULES, I10-NEXTGATE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-053, rowKind: rule, normalizedRequirement: "Preserve or generate x-request-id across browser, BFF route, and Spring; preserve a backend-proven x-session-id without fabricating a session.", sourcePointers: [I9-RULES, I9-BFF, I10-EVIDENCE, I10-HEADERS, I11-SOURCE, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-054, rowKind: rule, normalizedRequirement: "Stream routes relay the Spring SSE body incrementally with backpressure and never buffer the full answer before rendering.", sourcePointers: [I9-RULES, I9-BFF, I10-EVIDENCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-055, rowKind: rule, normalizedRequirement: "Stream route exports dynamic=force-dynamic.", sourcePointers: [I9-BFF], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-056, rowKind: rule, normalizedRequirement: "Stream route reads the original request body as text and forwards content-type.", sourcePointers: [I9-BFF], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-057, rowKind: rule, normalizedRequirement: "Stream route calls the sanitized RAG_BACKEND_URL /api/chat/stream endpoint and returns a Response over upstream.body.", sourcePointers: [I9-BFF], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-058, rowKind: rule, normalizedRequirement: "Stream route preserves upstream status and an inspectable UI-safe failure body when failure occurs before streaming.", sourcePointers: [I9-BFF, I10-EVIDENCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-059, rowKind: rule, normalizedRequirement: "Stream response supplies text/event-stream when absent, cache-control no-store, x-session-id when proven, and x-request-id.", sourcePointers: [I9-BFF], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-060, rowKind: conflict, normalizedRequirement: "Current frontend ownership is JS-based route.js with jsconfig.json; strict route.ts policy cannot silently convert it and requires explicit TypeScript migration approval.", sourcePointers: [I9-RULES, I10-EVIDENCE, I10-NEXTGATE], category: safety, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, conflictsWith: [ND-NEXT-BFF-024, ND-NEXT-BFF-025, ND-NEXT-BFF-026, ND-NEXT-BFF-027, ND-NEXT-BFF-028, ND-NEXT-BFF-051]}
  - {requirementId: ND-NEXT-BFF-061, rowKind: rule, normalizedRequirement: "Sync, session, cancel, and RAG BFF routes proxy JSON with cache no-store.", sourcePointers: [I9-BFF], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-062, rowKind: forbidden_rule, normalizedRequirement: "BFF routes do not log raw prompts or raw response text.", sourcePointers: [I9-BFF, I10-EVIDENCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-063, rowKind: rule, normalizedRequirement: "Backend errors map only to typed UI-safe codes backend_unavailable, stream_failed, session_forbidden, cancel_failed, or invalid_payload as applicable.", sourcePointers: [I9-BFF, I10-EVIDENCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-064, rowKind: rule, normalizedRequirement: "BFF forwards only accept, accept-language, content-type, cookie, x-conversation-id, x-csrf-token, x-request-id, x-session-id, and x-xsrf-token.", sourcePointers: [I10-EVIDENCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-065, rowKind: forbidden_rule, normalizedRequirement: "BFF never forwards the browser Authorization header.", sourcePointers: [I10-EVIDENCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-066, rowKind: rule, normalizedRequirement: "BFF exposes only x-session-id, x-request-id, x-model-used, x-rag-used, and x-trace-snapshot-id response correlation metadata.", sourcePointers: [I10-EVIDENCE, I10-HEADERS], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-067, rowKind: rule, normalizedRequirement: "Non-stream timeout is the redacted typed outcome backend_timeout; unavailable backend is backend_unavailable.", sourcePointers: [I10-EVIDENCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-068, rowKind: rule, normalizedRequirement: "Upstream redirects become safe inspectable errors rather than raw login HTML.", sourcePointers: [I10-EVIDENCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-069, rowKind: rule, normalizedRequirement: "Stream failures are represented as SSE event:error frames without raw failure content.", sourcePointers: [I10-EVIDENCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-070, rowKind: rule, normalizedRequirement: "Next UI is an operational RAG/agent workbench, with usable chat first rather than a marketing hero.", sourcePointers: [I9-UI], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-071, rowKind: rule, normalizedRequirement: "Next UI keeps a readable session rail, transcript, evidence/diagnostics panel, and composer with no desktop/mobile overlap.", sourcePointers: [I9-UI, I9-END], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-072, rowKind: rule, normalizedRequirement: "Status, thought, token, evidence, final, and error events render as distinct bounded UI states.", sourcePointers: [I9-UI, I9-P4], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-073, rowKind: rule, normalizedRequirement: "The stream parser follows Spring ChatStreamEvent as source of truth and handles status, thought, token, trace, evidence, scoreDelta, debug_fx, transformer, session, final, and error.", sourcePointers: [I9-P4, I10-SSE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-074, rowKind: rule, normalizedRequirement: "Unknown event names render only as a compact diagnostic category and never crash or expose payload content.", sourcePointers: [I9-P4, I10-SSE, I11-P3, I11-P6, I11-P8], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-075, rowKind: rule, normalizedRequirement: "Do not force WebSocket; add it only after dependency/support, a need SSE cannot cover, security boundary, and focused build/test proof all exist.", sourcePointers: [I9-P6], category: safety, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-WEBSOCKET}
  - {requirementId: ND-NEXT-BFF-076, rowKind: rule, normalizedRequirement: "If WebSocket is authorized, limit it to typed redacted diagnostics/status, not primary token streaming or raw trace/secret content.", sourcePointers: [I9-P6], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-WEBSOCKET}
  - {requirementId: ND-NEXT-BFF-077, rowKind: non_goal, normalizedRequirement: "WebSocket omission is explicitly acceptable when its runtime use case or dependency is unproven; report evidence_needed and retain REST+SSE.", sourcePointers: [I9-P6, I9-ACCEPT], category: non-goal, status: not_applicable, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-078, rowKind: forbidden_rule, normalizedRequirement: "Do not create or commit node_modules, .next, .turbo, or .swc output.", sourcePointers: [I9-DECOMP, I10-SCOPE, I11-GOAL], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-079, rowKind: forbidden_rule, normalizedRequirement: "Do not expose admin/internal agent tool routes to browser UI without preserved server-side authorization and proven need.", sourcePointers: [I9-P3, I10-RAG], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-080, rowKind: rule, normalizedRequirement: "Optional CORS or health-contract changes are allowed only after a concrete browser-smoke contract gap; broad Java refactors remain forbidden.", sourcePointers: [I9-P5], category: safety, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-BACKEND}
  - {requirementId: ND-MAIN-CHAT-PARENT-009, rowKind: directive_id, normalizedRequirement: "Retain named directive AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2 in the canonical ledger as a deferred parent contract, not an immediate work unit.", sourcePointers: [I11-SOURCE], category: source, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT, directiveId: AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2}
  - {requirementId: ND-MAIN-CHAT-PARENT-010, rowKind: directive_id_hold, normalizedRequirement: "AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2 source mutation remains HOLD until a current Desktop TargetBoundaryProof, stable three-query APPLY, and valid existing target-bound RED exist.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P2, I11-P4, I11-P5], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, directiveId: AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2}
  - {requirementId: ND-MAIN-CHAT-PARENT-011, rowKind: rule, normalizedRequirement: "Build a parity ledger before mutation comparing current Next evidence to the Spring owners and mark already-present behavior no_patch_needed with current file/line proof.", sourcePointers: [I10-PARITY, I11-GOAL, I11-P3], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-012, rowKind: forbidden_rule, normalizedRequirement: "Do not duplicate existing chat.js helpers or replace the Spring chatbot/layout wholesale; patch only parity-ledger missing rows.", sourcePointers: [I10-GOAL, I10-PARITY, I10-EVIDENCEUI, I11-GOAL, I11-SOURCE, I11-P6], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-013, rowKind: rule, normalizedRequirement: "Every chat-start/control call generates x-request-id when absent, preserves only a known backend session ID, never invents a session, and uses event-stream accept for streaming and JSON content-type for sync/cancel/RAG.", sourcePointers: [I10-HEADERS, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-014, rowKind: rule, normalizedRequirement: "Read only x-session-id, x-request-id, x-model-used, x-rag-used, and x-trace-snapshot-id response headers into bounded visible status.", sourcePointers: [I10-HEADERS], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-015, rowKind: rule, normalizedRequirement: "Main chat SSE parser parses event and data frames, accepts valid JSON data or a safe bounded plain-text string, and treats message as token-compatible fallback.", sourcePointers: [I10-SSE, I11-P3, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-016, rowKind: rule, normalizedRequirement: "Main chat handles token, message, status, session, evidence, trace, scoreDelta, debug_fx, transformer, thought, understanding, final, and error without crashing.", sourcePointers: [I10-SSE, I11-P3, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-017, rowKind: rule, normalizedRequirement: "A session event persists only the backend session ID and updates the session rail.", sourcePointers: [I10-SSE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-018, rowKind: rule, normalizedRequirement: "An evidence event updates the evidence rail without raw trace content.", sourcePointers: [I10-SSE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-019, rowKind: rule, normalizedRequirement: "A final event completes bounded status, model, answer mode, RAG flag, evidence count, and session metadata.", sourcePointers: [I10-SSE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-020, rowKind: rule, normalizedRequirement: "An error event produces a recoverable typed state and keeps retry available.", sourcePointers: [I10-SSE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-021, rowKind: rule, normalizedRequirement: "Explicit user Stop aborts the local ReadableStream through AbortController.", sourcePointers: [I10-CANCEL, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-022, rowKind: rule, normalizedRequirement: "Explicit user Stop posts /api/chat/cancel only when a backend-proven session ID is known, with JSON body and x-session-id.", sourcePointers: [I10-CANCEL, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-023, rowKind: forbidden_rule, normalizedRequirement: "Browser detach or navigation alone must never be treated as explicit server cancel.", sourcePointers: [I10-CANCEL], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-024, rowKind: rule, normalizedRequirement: "Cancel 200, 403, 503, and network failures map to typed UI states.", sourcePointers: [I10-CANCEL, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-025, rowKind: rule, normalizedRequirement: "Retry remains available after stopped, cancelled, backend_unavailable, backend_timeout, or stream_failed.", sourcePointers: [I10-CANCEL, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-026, rowKind: rule, normalizedRequirement: "Session list uses exact GET /api/chat/sessions with cache no-store.", sourcePointers: [I10-SESSIONS, I11-SOURCE, I11-P6], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-027, rowKind: rule, normalizedRequirement: "Selecting a validated session sets the active ID and may hydrate transcript through GET /api/chat/sessions/{id}.", sourcePointers: [I10-SESSIONS, I11-SOURCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-028, rowKind: forbidden_rule, normalizedRequirement: "Do not switch session ownership to /api/chat-extra/sessions without current Desktop proof; delete only if already owned by current UI or separately required.", sourcePointers: [I10-SESSIONS], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-029, rowKind: rule, normalizedRequirement: "Preserve the existing debug heartbeat, cockpit, proof, and evidence surfaces while adding only missing evidence title/source/url/score/snippet extraction.", sourcePointers: [I10-EVIDENCEUI, I11-SOURCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-030, rowKind: rule, normalizedRequirement: "Empty evidence distinguishes local fallback/history fallback from provider evidence and exposes a bounded evidence count.", sourcePointers: [I10-EVIDENCEUI], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-031, rowKind: rule, normalizedRequirement: "Evidence and status UI must not overlap at desktop or mobile widths.", sourcePointers: [I10-EVIDENCEUI], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-032, rowKind: rule, normalizedRequirement: "User-facing RAG checks use POST /api/rag/query; /api/rag/probe is used only when current UI security evidence permits it.", sourcePointers: [I10-RAG], category: safety, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-RAG-PROBE}
  - {requirementId: ND-MAIN-CHAT-PARENT-033, rowKind: rule, normalizedRequirement: "When RAG probe is forbidden, render only session_forbidden or probe_forbidden without changing security.", sourcePointers: [I10-RAG], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-RAG-PROBE}
  - {requirementId: ND-MAIN-CHAT-PARENT-034, rowKind: rule, normalizedRequirement: "Non-JSON RAG responses may be classified from only a small bounded inspectable prefix and may not expose admin/internal routes.", sourcePointers: [I10-RAG], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-035, rowKind: conflict, normalizedRequirement: "Input 10 conditionally permits narrow Java correlation/DTO/test/CORS changes, but input 11 forbids Java/backend/new fixtures; the stricter V2 rule and approved W0-W3 design win, so parent-level backend widening stays HOLD.", sourcePointers: [I10-SCOPE, I10-BACKEND, I11-GOAL, I11-SOURCE, I11-P3], category: safety, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND, conflictsWith: [ND-MAIN-CHAT-PARENT-004, ND-MAIN-CHAT-PARENT-005, ND-MAIN-CHAT-PARENT-006, ND-MAIN-CHAT-PARENT-007, ND-MAIN-CHAT-PARENT-008]}
  - {requirementId: ND-MAIN-CHAT-PARENT-036, rowKind: forbidden_rule, normalizedRequirement: "Inactive mirrors project/src/main/java, app/src/main/java, demo-1, lms-core, compatibility aliases, backups, archives, generated outputs, build*, .gradle, .next, node_modules, and vendor Browser plugin files are never mutation targets.", sourcePointers: [I10-SCOPE, I11-GOAL, I11-SOURCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-037, rowKind: rule, normalizedRequirement: "A complete top-level PatchDrop patch blocks the direct Desktop lane and must be routed through the existing desktop-consumer contract.", sourcePointers: [I11-SOURCE, I11-P1], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-038, rowKind: rule, normalizedRequirement: "Actual source edits use Desktop-local apply_patch/file APIs inside a source-owner lease; Browser clicks never write files.", sourcePointers: [I11-BROWSER, I11-P6], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT}
  - {requirementId: ND-MAIN-CHAT-PARENT-039, rowKind: proof_boundary, normalizedRequirement: "Browser DOM, navigation, screenshot, rendered answer, or HTTP delivery proves UI behavior/delivery only—not file mutation, Gradle, provider attempt, semantic success, or runtime lineage.", sourcePointers: [I11-BROWSER, I11-SOURCE, I11-P8, I11-VERIFY], category: verification, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-MAIN-CHAT-PARENT-040, rowKind: proof_boundary, normalizedRequirement: "Provider lineage requires same-request request/options hashes plus direct provider attempt and response evidence; absent lineage keeps runtimeLineageVerdict HOLD.", sourcePointers: [I11-SOURCE, I11-P8, I11-VERIFY], category: runtime, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-MAIN-CHAT-PARENT-041, rowKind: proof_boundary, normalizedRequirement: "Refresh a stale Browser tab read-only, do not repeat pending actions, and HOLD when a controlled tab binding is unavailable.", sourcePointers: [I11-BROWSER, I11-SOURCE], category: runtime, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PROOF}
  - {requirementId: ND-NEXT-BFF-021, rowKind: target, normalizedRequirement: "Suggested Next target src/app/layout.tsx is deferred to a separately proven Next project owner.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/app/layout.tsx]}
  - {requirementId: ND-NEXT-BFF-022, rowKind: target, normalizedRequirement: "Suggested Next target src/app/page.tsx is deferred to a separately proven Next project owner.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/app/page.tsx]}
  - {requirementId: ND-NEXT-BFF-023, rowKind: target, normalizedRequirement: "Suggested Next target src/app/chat/page.tsx is deferred to a separately proven Next project owner.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/app/chat/page.tsx]}
  - {requirementId: ND-NEXT-BFF-024, rowKind: target, normalizedRequirement: "Suggested App Router target src/app/api/chat/stream/route.ts is deferred; it is not authority to convert the current JS route.", sourcePointers: [I9-SHAPE, I9-RULES, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [src/app/api/chat/stream/route.ts], conflictsWith: [ND-NEXT-BFF-060]}
  - {requirementId: ND-NEXT-BFF-025, rowKind: target, normalizedRequirement: "Suggested App Router target src/app/api/chat/sync/route.ts is deferred; it is not authority to convert the current JS route.", sourcePointers: [I9-SHAPE, I9-RULES, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [src/app/api/chat/sync/route.ts], conflictsWith: [ND-NEXT-BFF-060]}
  - {requirementId: ND-NEXT-BFF-026, rowKind: target, normalizedRequirement: "Suggested App Router target src/app/api/chat/cancel/route.ts is deferred; it is not authority to convert the current JS route.", sourcePointers: [I9-SHAPE, I9-RULES, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [src/app/api/chat/cancel/route.ts], conflictsWith: [ND-NEXT-BFF-060]}
  - {requirementId: ND-NEXT-BFF-027, rowKind: target, normalizedRequirement: "Suggested App Router target src/app/api/chat/sessions/route.ts is deferred; it is not authority to convert the current JS route.", sourcePointers: [I9-SHAPE, I9-RULES, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [src/app/api/chat/sessions/route.ts], conflictsWith: [ND-NEXT-BFF-060]}
  - {requirementId: ND-NEXT-BFF-028, rowKind: target, normalizedRequirement: "Suggested App Router target src/app/api/chat/sessions/[id]/route.ts is deferred; it is not authority to convert the current JS route.", sourcePointers: [I9-SHAPE, I9-RULES, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: ["src/app/api/chat/sessions/[id]/route.ts"], conflictsWith: [ND-NEXT-BFF-060]}
  - {requirementId: ND-NEXT-BFF-029, rowKind: target, normalizedRequirement: "Suggested App Router target src/app/api/rag/query/route.ts is deferred to a separately authorized Next unit.", sourcePointers: [I9-SHAPE, I9-RULES], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/app/api/rag/query/route.ts]}
  - {requirementId: ND-NEXT-BFF-030, rowKind: target, normalizedRequirement: "Suggested Next component target src/components/chat/ChatShell.tsx is deferred.", sourcePointers: [I9-SHAPE, I9-P4], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/components/chat/ChatShell.tsx]}
  - {requirementId: ND-NEXT-BFF-031, rowKind: target, normalizedRequirement: "Suggested Next component target src/components/chat/MessageList.tsx is deferred.", sourcePointers: [I9-SHAPE, I9-P4], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/components/chat/MessageList.tsx]}
  - {requirementId: ND-NEXT-BFF-032, rowKind: target, normalizedRequirement: "Suggested Next component target src/components/chat/Composer.tsx is deferred.", sourcePointers: [I9-SHAPE, I9-P4], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/components/chat/Composer.tsx]}
  - {requirementId: ND-NEXT-BFF-033, rowKind: target, normalizedRequirement: "Suggested Next component target src/components/chat/StreamingStatus.tsx is deferred.", sourcePointers: [I9-SHAPE, I9-P4], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/components/chat/StreamingStatus.tsx]}
  - {requirementId: ND-NEXT-BFF-034, rowKind: target, normalizedRequirement: "Suggested Next component target src/components/chat/EvidencePanel.tsx is deferred.", sourcePointers: [I9-SHAPE, I9-P4], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/components/chat/EvidencePanel.tsx]}
  - {requirementId: ND-NEXT-BFF-035, rowKind: target, normalizedRequirement: "Suggested Next component target src/components/chat/SessionRail.tsx is deferred.", sourcePointers: [I9-SHAPE, I9-P4], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/components/chat/SessionRail.tsx]}
  - {requirementId: ND-NEXT-BFF-036, rowKind: target, normalizedRequirement: "Suggested Next library target src/lib/rag/backend.ts is deferred.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/lib/rag/backend.ts]}
  - {requirementId: ND-NEXT-BFF-037, rowKind: target, normalizedRequirement: "Suggested Next library target src/lib/rag/ids.ts is deferred.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/lib/rag/ids.ts]}
  - {requirementId: ND-NEXT-BFF-038, rowKind: target, normalizedRequirement: "Suggested Next library target src/lib/rag/sse.ts is deferred.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/lib/rag/sse.ts]}
  - {requirementId: ND-NEXT-BFF-039, rowKind: target, normalizedRequirement: "Suggested Next library target src/lib/rag/types.ts is deferred.", sourcePointers: [I9-SHAPE], category: source, status: held, decision: hold, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [src/lib/rag/types.ts]}
  - {requirementId: ND-NEXT-BFF-040, rowKind: target, normalizedRequirement: "Current JS BFF comparison owner frontend/src/lib/bff.js is evidence-only in this family and is not authorized for mutation.", sourcePointers: [I10-EVIDENCE, I10-PARITY, I11-P3], category: source, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [frontend/src/lib/bff.js]}
  - {requirementId: ND-NEXT-BFF-041, rowKind: target, normalizedRequirement: "Current JS HTTP-result comparison owner frontend/src/lib/http-result.js is evidence-only in this family and is not authorized for mutation.", sourcePointers: [I10-EVIDENCE], category: source, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [frontend/src/lib/http-result.js]}
  - {requirementId: ND-NEXT-BFF-042, rowKind: target, normalizedRequirement: "Current JS page comparison owner frontend/src/app/chat/page.js is evidence-only in this family and is not authorized for mutation.", sourcePointers: [I10-EVIDENCE, I10-PARITY, I11-P3], category: source, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [frontend/src/app/chat/page.js]}
  - {requirementId: ND-NEXT-BFF-043, rowKind: target, normalizedRequirement: "Current JS stream route frontend/src/app/api/chat/stream/route.js remains owned by the existing frontend until a separate TypeScript-migration decision.", sourcePointers: [I10-EVIDENCE, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [frontend/src/app/api/chat/stream/route.js], conflictsWith: [ND-NEXT-BFF-024]}
  - {requirementId: ND-NEXT-BFF-044, rowKind: target, normalizedRequirement: "Current JS sync route frontend/src/app/api/chat/sync/route.js remains owned by the existing frontend until a separate TypeScript-migration decision.", sourcePointers: [I10-EVIDENCE, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [frontend/src/app/api/chat/sync/route.js], conflictsWith: [ND-NEXT-BFF-025]}
  - {requirementId: ND-NEXT-BFF-045, rowKind: target, normalizedRequirement: "Current JS cancel route frontend/src/app/api/chat/cancel/route.js remains owned by the existing frontend until a separate TypeScript-migration decision.", sourcePointers: [I10-EVIDENCE, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [frontend/src/app/api/chat/cancel/route.js], conflictsWith: [ND-NEXT-BFF-026]}
  - {requirementId: ND-NEXT-BFF-046, rowKind: target, normalizedRequirement: "Current JS sessions route frontend/src/app/api/chat/sessions/route.js remains owned by the existing frontend until a separate TypeScript-migration decision.", sourcePointers: [I10-EVIDENCE, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: [frontend/src/app/api/chat/sessions/route.js], conflictsWith: [ND-NEXT-BFF-027]}
  - {requirementId: ND-NEXT-BFF-047, rowKind: target, normalizedRequirement: "Current JS session-detail route frontend/src/app/api/chat/sessions/[id]/route.js remains owned by the existing frontend until a separate TypeScript-migration decision.", sourcePointers: [I10-EVIDENCE, I10-NEXTGATE], category: source, status: conflict, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-ROUTE-POLICY, targetFiles: ["frontend/src/app/api/chat/sessions/[id]/route.js"], conflictsWith: [ND-NEXT-BFF-028]}
  - {requirementId: ND-NEXT-BFF-048, rowKind: target, normalizedRequirement: "Current JS RAG query route frontend/src/app/api/rag/query/route.js is evidence-only and not authorized for mutation.", sourcePointers: [I10-EVIDENCE], category: source, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [frontend/src/app/api/rag/query/route.js]}
  - {requirementId: ND-NEXT-BFF-049, rowKind: target, normalizedRequirement: "Current JS RAG probe route frontend/src/app/api/rag/probe/route.js is evidence-only and must not broaden browser security authority.", sourcePointers: [I10-EVIDENCE, I10-RAG], category: source, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [frontend/src/app/api/rag/probe/route.js]}
  - {requirementId: ND-NEXT-BFF-050, rowKind: target, normalizedRequirement: "Current frontend test target frontend/test/bff.test.mjs is verification evidence only in this deferred family.", sourcePointers: [I10-EVIDENCE, I10-NEXTGATE], category: test, status: held, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF, targetFiles: [frontend/test/bff.test.mjs]}
  - {requirementId: ND-MAIN-CHAT-PARENT-001, rowKind: target, normalizedRequirement: "Legacy Spring UI target main/resources/static/js/chat.js is authorized only by later target-specific W0-W3 directives, not by inputs 9–11.", sourcePointers: [I9-DECOMP, I10-SCOPE, I11-GOAL, I11-SOURCE], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, targetFiles: [main/resources/static/js/chat.js]}
  - {requirementId: ND-MAIN-CHAT-PARENT-002, rowKind: target, normalizedRequirement: "Legacy Spring UI target main/resources/templates/chat-ui.html is authorized only by later target-specific W3 evidence, not by inputs 9–11.", sourcePointers: [I9-DECOMP, I10-SCOPE, I11-GOAL, I11-SOURCE], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, targetFiles: [main/resources/templates/chat-ui.html]}
  - {requirementId: ND-MAIN-CHAT-PARENT-003, rowKind: target, normalizedRequirement: "Legacy Spring UI target main/resources/static/css/chat-style.css is outside the approved W0-W3 source tranche unless new design evidence and authorization are obtained.", sourcePointers: [I9-DECOMP, I10-SCOPE, I11-GOAL, I11-SOURCE], category: source, status: not_applicable, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, targetFiles: [main/resources/static/css/chat-style.css]}
  - {requirementId: ND-MAIN-CHAT-PARENT-004, rowKind: target, normalizedRequirement: "Conditional backend target main/java/com/example/lms/api/ChatApiController.java is forbidden by the V2 parent directive and outside approved W0-W3.", sourcePointers: [I10-SCOPE, I10-BACKEND, I11-SOURCE, I11-P3], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND, targetFiles: [main/java/com/example/lms/api/ChatApiController.java]}
  - {requirementId: ND-MAIN-CHAT-PARENT-005, rowKind: target, normalizedRequirement: "Conditional backend target main/java/com/example/lms/dto/ChatStreamEvent.java is forbidden by the V2 parent directive and outside approved W0-W3.", sourcePointers: [I10-SCOPE, I10-BACKEND, I11-SOURCE, I11-P3], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND, targetFiles: [main/java/com/example/lms/dto/ChatStreamEvent.java]}
  - {requirementId: ND-MAIN-CHAT-PARENT-006, rowKind: target, normalizedRequirement: "Conditional security target main/java/com/example/lms/config/AppSecurityConfig.java is forbidden by the V2 parent directive and outside approved W0-W3.", sourcePointers: [I10-SCOPE, I10-BACKEND, I11-SOURCE, I11-P3], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND, targetFiles: [main/java/com/example/lms/config/AppSecurityConfig.java]}
  - {requirementId: ND-MAIN-CHAT-PARENT-007, rowKind: target, normalizedRequirement: "Conditional security target main/java/com/example/lms/security/ChatOpenSecurityConfig.java is forbidden by the V2 parent directive and outside approved W0-W3.", sourcePointers: [I10-SCOPE, I10-BACKEND, I11-SOURCE, I11-P3], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-BACKEND, targetFiles: [main/java/com/example/lms/security/ChatOpenSecurityConfig.java]}
  - {requirementId: ND-MAIN-CHAT-PARENT-008, rowKind: target, normalizedRequirement: "New or modified test/fixture files are explicitly forbidden by AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2; missing RED requires a separate target-specific directive.", sourcePointers: [I11-GOAL, I11-SOURCE, I11-P4], category: test, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-MAIN-CHAT-PARENT, targetFiles: [scripts/chat_ui_stream_contract_tests.js]}
  - {requirementId: ND-NEXT-BFF-001, rowKind: authority, normalizedRequirement: "Desktop C-root is the only canonical execution and final-proof root; Notebook/Y/UNC observations are supporting-only and never a fallback write root.", sourcePointers: [I9-GOAL, I9-RULES, I10-GOAL, I10-FIRST, I11-INTAKE, I11-GOAL], category: safety, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-NEXT-BFF-002, rowKind: authority, normalizedRequirement: "Spring Boot remains authoritative for RAG, provider keys, memory, database, authentication and permission decisions, tools, logs, and safety gates; Next.js is UI/BFF only.", sourcePointers: [I9-GOAL, I9-RULES, I10-GOAL, I11-GOAL], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-003, rowKind: rule, normalizedRequirement: "Final RAG prompt construction remains at PromptBuilder.build(PromptContext) or the proven repository-equivalent boundary.", sourcePointers: [I9-RULES, I10-GOAL, I10-BACKEND, I11-GOAL, I11-SOURCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-004, rowKind: rule, normalizedRequirement: "Every dev.langchain4j dependency remains exactly 1.0.1.", sourcePointers: [I9-RULES, I10-BACKEND, I11-GOAL], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-005, rowKind: rule, normalizedRequirement: "Existing environment-variable names and openssl/opnessl keys, values, and formats are unchanged.", sourcePointers: [I9-RULES, I10-BACKEND, I11-GOAL], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-006, rowKind: forbidden_rule, normalizedRequirement: "Do not edit API-key, .env, shell-profile, secret-setup, credential, provider-key, owner-token, admin-token, authorization-header, or backend-only configuration surfaces.", sourcePointers: [I9-RULES, I10-GOAL, I11-GOAL, I11-SOURCE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-007, rowKind: rule, normalizedRequirement: "Browser-visible code receives no raw prompt, sensitive query, cookie, authentication header, raw trace dump, provider payload, key, or secret.", sourcePointers: [I9-RULES, I10-HEADERS, I10-EVIDENCEUI, I10-RAG, I11-GOAL, I11-SOURCE, I11-P6], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-008, rowKind: forbidden_rule, normalizedRequirement: "Do not fabricate build, boot, browser, provider, WebSocket, runtime-lineage, or Desktop proof.", sourcePointers: [I9-RULES, I9-ACCEPT, I10-ACCEPT, I11-GOAL, I11-SOURCE, I11-VERIFY], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-PROOF}
  - {requirementId: ND-NEXT-BFF-009, rowKind: authority, normalizedRequirement: "Current Desktop C-root source and tests override conflicting Notebook claims and must reconfirm sourceSets, endpoints, branch, locks, leases, preimages, and project ownership before mutation.", sourcePointers: [I9-EVIDENCE, I10-READS, I10-EVIDENCE, I11-INTAKE, I11-SNAPSHOT, I11-GOAL, I11-SOURCE], category: verification, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PARENT-AUTHORITY}
  - {requirementId: ND-NEXT-BFF-010, rowKind: rule, normalizedRequirement: "Use one N-way route-selection decision, then independent narrow direct patch cycles; the time budget is a maximum and work stops early on proof or blocker.", sourcePointers: [I9-DECOMP, I9-P0], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-011, rowKind: rule, normalizedRequirement: "Preferred lane may connect an existing intended Next App Router project only after its root, package, local instructions, Next version, and src/app ownership are proven.", sourcePointers: [I9-DECOMP, I9-P1], category: source, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER}
  - {requirementId: ND-NEXT-BFF-012, rowKind: target, normalizedRequirement: "External candidate Next root C:/Users/nninn/OneDrive/Desktop/travel-graphrag-chatbot is evidence-only until separately proven and selected; it is outside canonical C-root authority.", sourcePointers: [I9-DECOMP, I9-P1], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER}
  - {requirementId: ND-NEXT-BFF-013, rowKind: rule, normalizedRequirement: "A repo-contained Next App Router may be created only under a clearly separate frontend/ folder after Node/npm availability and explicit user acceptance are proven; Spring sourceSets stay unchanged.", sourcePointers: [I9-DECOMP, I9-P1], category: source, status: evidence_needed, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER}
  - {requirementId: ND-NEXT-BFF-014, rowKind: target, normalizedRequirement: "frontend/ is the only suggested repo-contained Next root and is not authorized by this fragment.", sourcePointers: [I9-DECOMP, I9-P1, I10-EVIDENCE, I10-NEXTGATE], category: source, status: held, decision: hold, disposition: HOLD, destinationUnit: HOLD-NEXT-PROJECT-OWNER}
  - {requirementId: ND-NEXT-BFF-015, rowKind: rule, normalizedRequirement: "If a safe Next project cannot be proven, use only the existing Spring UI fallback and report Next.js as evidence_needed; fallback UI work must not be called completed Next architecture.", sourcePointers: [I9-DECOMP, I9-ACCEPT], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-016, rowKind: rule, normalizedRequirement: "Record selected lane, reason, skipped lanes, and one evidence_needed item before implementation.", sourcePointers: [I9-DECOMP, I9-ACCEPT], category: verification, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-017, rowKind: rule, normalizedRequirement: "In a selected Next lane, Browser primary chat traffic goes through Next BFF routes while Spring remains backend authority; Spring /chat and /chat-ui stay working unless a proven external reverse proxy intentionally changes deployment.", sourcePointers: [I9-END, I9-P5, I10-GOAL, I10-SESSIONS, I11-GOAL, I11-SOURCE], category: source, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-018, rowKind: rule, normalizedRequirement: "Next client components own only UI state, stream parsing, and interactions; backend URLs, tokens, and privileged calls remain in route handlers or server components.", sourcePointers: [I9-SHAPE], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-019, rowKind: rule, normalizedRequirement: "No secret value may be exposed through NEXT_PUBLIC_*.", sourcePointers: [I9-END], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - {requirementId: ND-NEXT-BFF-020, rowKind: rule, normalizedRequirement: "RAG_BACKEND_URL is server-only, HTTP/S-sanitized, and defaults to http://127.0.0.1:8080 only in the Next BFF; the direct Spring UI does not use it.", sourcePointers: [I9-END, I10-EVIDENCE, I10-HEADERS], category: safety, status: pending, decision: retain, disposition: DEFER, destinationUnit: DEFER-NEXT-BFF}
  - requirementId: ND-CHAT-SESSION-001
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "Session-list directive is prompt-only; source mutation remains HOLD until ownership, stable preflight, lease/preimages, fresh fixture GREEN, and the next staged RED."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "front matter; ## Decision"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: safety
    status: held
    decision: hold
    disposition: HOLD
    workUnitId: W3-CHAT-SESSION-LIST
    executionAuthority: none_until_all_gates
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js, main/resources/templates/chat-ui.html]
    mandatoryRules: ["fixture before source", "one staged RED at a time", "only the matching minimal patch after RED"]
    forbiddenRules: ["no source edit from this artifact alone", "no stage-jumping", "no Java route/API change"]
    redTests: ["The next literal SL-R row fails after unchanged-fixture GREEN."]
    greenTests: ["That same row alone passes and seals its postimage before the next row."]
    verificationCommands: ["node .\\scripts\\chat_ui_stream_contract_tests.js; then the directive's Node/Gradle ladder with desktop-session-list cache isolation."]
    nonGoals: ["parent-goal completion", "provider/runtime lineage certification"]
    rollback: "Restore only current GREEN-baseline manifest paths under the same lease; never reset the worktree."
    holdConditions: ["dirty-target-overlap", "target-red-missing", "fixture-first-failure-mask", "sibling-preimage-drift"]
    conflictsWith: [ND-CHAT-HOLD-OWNERSHIP-001]
    liveEvidence: ["fixture baseline was GREEN but lacks this behavior", "desktopFinalProof=evidence_needed"]
  - requirementId: ND-CHAT-SESSION-002
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "SL-R0 first retains options.cache in the existing fetch capture and proves behavior-neutral fixture GREEN; it grants no application-source authority."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Sequential RED-GREEN Contract > SL-R0"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: test
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W0-CHAT-FIXTURE
    executionAuthority: fixture_only_after_preflight
    targets: [scripts/chat_ui_stream_contract_tests.js]
    mandatoryRules: ["capture options.cache", "rerun unchanged behavior suite", "seal SL-R0 fixture postimage"]
    forbiddenRules: ["no chat.js change", "no behavior assertion bundled with instrumentation"]
    redTests: ["If behavior changes, classify fixture-contract-repair-red rather than product RED."]
    greenTests: ["Current suite remains GREEN and captures literal cache option."]
    verificationCommands: ["node .\\scripts\\chat_ui_stream_contract_tests.js"]
    nonGoals: ["session-list rendering", "application mutation"]
    rollback: "Restore the sealed fixture preimage if behavior changes."
    holdConditions: ["ownership/preflight absent", "unchanged suite is not GREEN"]
    conflictsWith: []
    liveEvidence: ["Current fixture does not retain options.cache."]
  - requirementId: ND-CHAT-SESSION-003
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "SL-R1 requires exactly GET /api/chat/sessions with cache:no-store, a fresh x-request-id, and x-session-id only for the exact backend-proven current session."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Sequential RED-GREEN Contract > SL-R1 — list transport and correlation"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W3-CHAT-SESSION-LIST
    executionAuthority: matching_SL-R1_RED_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["exact list URL", "literal no-store", "fresh request ID", "bind session ID only when backend-proven"]
    forbiddenRules: ["no detail-URL prefix match", "no fabricated session ID", "do not require list and detail request IDs to match"]
    redTests: ["session-list-request-missing", "session-list-cache-policy-missing", "session-list-request-id-missing", "session-list-current-session-correlation-missing", "session-list-session-id-fabricated"]
    greenTests: ["Each SL-R1 substage observes only its required request behavior through real options capture."]
    verificationCommands: ["Run each SL-R1 substage independently through chat_ui_stream_contract_tests.js."]
    nonGoals: ["detail no-store policy", "new networking wrapper"]
    rollback: "Restore the current manifest's fixture/chat.js GREEN postimages."
    holdConditions: ["SL-R0 not GREEN", "sibling preimage mismatch", "later substage reached after an earlier failure"]
    conflictsWith: []
    liveEvidence: ["Read-only probe observed zero exact list calls and no cache:no-store request."]
  - requirementId: ND-CHAT-SESSION-004
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "SL-R2 accepts only positive numeric backend IDs, renders at most 12 ordered candidates, exposes empty state, and keeps selectable rows separate from diagnostics."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Sequential RED-GREEN Contract > SL-R2 — validated, bounded rendering"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W3-CHAT-SESSION-LIST
    executionAuthority: matching_SL-R2_RED_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js, main/resources/templates/chat-ui.html]
    mandatoryRules: ["maxVisibleSessions=12", "one valid positive row proves positive path", "typed empty state", "data-session-list rows remain distinct from data-session-mode diagnostics"]
    forbiddenRules: ["do not accept missing/zero/negative/fractional/string IDs", "no CSS change", "HTML only if current surface cannot partition row kinds"]
    redTests: ["session-list-valid-row-not-rendered", "session-list-missing-id-accepted", "session-list-zero-id-accepted", "session-list-negative-id-accepted", "session-list-fractional-id-accepted", "session-list-string-id-accepted", "session-list-render-bounds-missing", "session-list-empty-state-missing", "session-list-diagnostic-row-collision"]
    greenTests: ["Exactly first twelve valid backend-order rows render, invalid rows do not, and diagnostics cannot clear/relabel selections."]
    verificationCommands: ["Run ordered SL-R2 fixture rows; if HTML is used, run chat_ui_view_layer_contract_tests.js and focused chatUiTest."]
    nonGoals: ["CSS geometry repair", "new fixture file", "Java route"]
    rollback: "Restore only paths in the current GREEN-baseline manifest."
    holdConditions: ["SL-R1 incomplete", "a dedicated accessible surface requires CSS", "HTML target lacks a matching RED"]
    conflictsWith: []
    liveEvidence: ["Existing data-session-mode-list is diagnostics-only; Next reference is comparative only."]
  - requirementId: ND-CHAT-SESSION-005
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "SL-R3 maps list failures categorically and preserves current session/transcript without raw response content."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Sequential RED-GREEN Contract > SL-R3 — typed fail-soft rows"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W3-CHAT-SESSION-LIST
    executionAuthority: matching_SL-R3_RED_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["403=session_list_forbidden retryable=false", "503=session_list_unavailable retryable=true", "invalid/non-array 200=session_list_invalid_response", "network=session_list_network_error"]
    forbiddenRules: ["no raw body", "no duplicate network wrapper", "no auth behavior change"]
    redTests: ["session-list-forbidden-map-missing", "session-list-unavailable-map-missing", "session-list-invalid-json-map-missing", "session-list-non-array-map-missing", "session-list-network-map-missing"]
    greenTests: ["Each reset scenario yields only its specified typed mapping and preserves session/transcript."]
    verificationCommands: ["Run SL-R3 rows independently through the real VM/DOM fixture boundary."]
    nonGoals: ["stream transport classifier redesign", "server authentication change"]
    rollback: "Restore the current fixture/chat.js checkpoint."
    holdConditions: ["SSE parser sibling not reconciled", "prior SL-R stage not GREEN"]
    conflictsWith: [ND-CHAT-FAILSOFT-003]
    liveEvidence: ["List failure behavior had no fixture coverage."]
  - requirementId: ND-CHAT-SESSION-006
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "SL-R4 and SL-R5 prevent active-run selection, require exact candidate correlation, and reject stale/invalid/detail failures without precommit or transcript mixing."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Sequential RED-GREEN Contract > SL-R4; ## Sequential RED-GREEN Contract > SL-R5"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W3-CHAT-SESSION-LIST
    executionAuthority: matching_SL-R4_or_SL-R5_RED_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["active controls aria-disabled and no-op", "exact candidate detail URL/request ID/session ID", "no state commit before validated matching current-generation detail"]
    forbiddenRules: ["no fabricated run capability", "no prefix/prior-session detail match", "no second hydration framework"]
    redTests: ["session-selection-active-run-guard-missing", "session-detail-url-missing", "session-detail-request-id-missing", "session-detail-session-id-mismatch", "session-detail-precommit-detected", "session-detail-id-mismatch-not-atomic", "session-detail-invalid-not-atomic", "session-detail-failure-not-atomic", "session-detail-stale-generation-not-atomic"]
    greenTests: ["Selection cannot mutate during active run; every rejected detail preserves all prior state/rows/transcript."]
    verificationCommands: ["Run each SL-R4/SL-R5 substage with reset request/event/state baselines."]
    nonGoals: ["cancel behavior change", "storage migration"]
    rollback: "Restore current stage GREEN checkpoint and rerun focused fixture."
    holdConditions: ["candidate is not backend-proven", "generation guard owner unproven", "earlier stage fails"]
    conflictsWith: []
    liveEvidence: ["Detail endpoint exists, but list-load/selection behavior is unproven."]
  - requirementId: ND-CHAT-SESSION-007
    family: ND-CHAT-SESSION
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
    normalizedRequirement: "SL-R6 atomically commits only validated current-generation detail and coalesces one successful-final refresh without stale overwrite."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Sequential RED-GREEN Contract > SL-R6 — atomic commit and refresh lifecycle"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W3-CHAT-SESSION-LIST
    executionAuthority: matching_SL-R6_RED_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["atomic validated detail commit", "exactly one successful-final refresh", "refresh coalescing", "generation guard"]
    forbiddenRules: ["do not change cancelled/error refresh behavior", "do not change unknown-event/evidence/prompt/cancel semantics"]
    redTests: ["session-selection-atomic-commit-missing", "session-list-terminal-refresh-missing", "session-list-refresh-overlap", "session-list-stale-refresh-overwrite"]
    greenTests: ["Current generation atomically commits; concurrent/late refreshes cannot overwrite current rows."]
    verificationCommands: ["Run ordered SL-R6 fixture rows then Node/Gradle/browser session-list ladder."]
    nonGoals: ["refresh on error/cancel", "provider proof"]
    rollback: "Restore current manifest checkpoint; retain earlier GREEN work."
    holdConditions: ["SL-R5 incomplete", "a terminal contract change belongs to another directive"]
    conflictsWith: []
    liveEvidence: ["No list refresh/selection coverage existed in the fixture."]
  - requirementId: ND-CHAT-SSE-001
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "SSE directive is prompt-only and serial: parser fixture/production work is held until ownership, stable three-way APPLY, lease/preimages, baseline, and matching RED."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "front matter; ## Decision; ## Sibling Serialization Gate"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: safety
    status: held
    decision: hold
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: none_until_all_gates
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["SSE precedes typed fail-soft", "one source lease", "each RED/GREEN stage seals baseline/RED/result hashes"]
    forbiddenRules: ["no second reader/wrapper/controller", "no sibling preimage overwrite", "no direct source authority from a prompt artifact"]
    redTests: ["Expected first unreached parser RED only after characterization GREEN."]
    greenTests: ["Matching fixture and production change GREEN with postimages sealed."]
    verificationCommands: ["Run baseline, then current three-way preflight and directive verification ladder."]
    nonGoals: ["reconnect/Last-Event-ID/retry semantics", "parent-goal completion"]
    rollback: "Restore exactly current stage manifest paths; never use git reset --hard or checkout --."
    holdConditions: ["dirty-target-overlap", "target-red-missing", "fixture-contract-conflict", "sibling-preimage-drift"]
    conflictsWith: [ND-CHAT-HOLD-OWNERSHIP-001]
    liveEvidence: ["Current fixture is GREEN but encodes a per-data-line conflict."]
  - requirementId: ND-CHAT-SSE-002
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Repair protocol characterizations before feature RED: frame boundary, UTF-8 chunk split, EOF-tail discard, comment-only, and unsupported-field-only behavior."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "## Baseline And Characterization Repair"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: test
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W0-CHAT-FIXTURE
    executionAuthority: fixture_only_after_preflight
    targets: [scripts/chat_ui_stream_contract_tests.js]
    mandatoryRules: ["each characterization GREEN against unchanged chat.js", "later rows not_run after a failure", "terminal latch remains preserved"]
    forbiddenRules: ["no production feature patch on characterization failure", "no malformed same-frame error/final as two events"]
    redTests: ["characterization-terminal-frame-boundary", "characterization-utf8-chunk-boundary", "characterization-eof-tail-discard", "characterization-comment-no-dispatch", "characterization-unsupported-field-no-dispatch"]
    greenTests: ["All five characterization rows pass and paired checkpoints preserve chat.js hash."]
    verificationCommands: ["node .\\scripts\\chat_ui_stream_contract_tests.js"]
    nonGoals: ["SSE feature behavior", "cap enforcement"]
    rollback: "Restore fixture to immediately preceding GREEN checkpoint and release lease."
    holdConditions: ["baseline-drift", "fixture-contract-repair-red", "ownership/preflight absent"]
    conflictsWith: [ND-CHAT-SSE-HOLD-001]
    liveEvidence: ["Existing error-same-frame-final fixture conflicts with correct event framing."]
  - requirementId: ND-CHAT-SSE-003
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Implement one parsed event per blank-line dispatch: UTF-8 chunks, LF/CRLF/CR, one leading-space removal, multi-data LF joining, message default/reset, ignored comments/unsupported fields, EOF-tail discard, and one render call."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "## Normative SSE Boundary; ## Ordered RED-GREEN Contract > RED-A through RED-B3"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: matching_RED-A_to_RED-B3_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["default type=message and reset after dispatch", "all data fields are one event", "render exactly once", "preserve all but one optional leading field space"]
    forbiddenRules: ["no per-data-line render", "no .trim() on event data", "no EOF-tail dispatch"]
    redTests: ["message-event-unhandled", "sse-default-message-type-unhandled", "sse-event-type-not-reset", "sse-data-lines-not-aggregated", "sse-field-whitespace-overtrimmed", "sse-lf-line-ending-unhandled", "sse-crlf-line-ending-unhandled", "sse-cr-line-ending-unhandled"]
    greenTests: ["Each ordered parser row passes through real reader/parser/renderer with one dispatch."]
    verificationCommands: ["Run RED-A/A1/A2, then only after cap decision RED-B1/B2/B3 in sequence through Node fixture."]
    nonGoals: ["id persistence", "Last-Event-ID", "reconnect", "retry timing"]
    rollback: "Restore only current stage manifest targets; keep earlier GREEN checkpoints."
    holdConditions: ["prior characterization missing", "plain-event-cap decision unresolved for RED-B1"]
    conflictsWith: [ND-CHAT-SSE-HOLD-001, ND-CHAT-SSE-004]
    liveEvidence: ["Current parser renders each data line separately and over-trims data."]
  - requirementId: ND-CHAT-SSE-HOLD-001
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Historical directive conflict: accumulated plain-event cap and overflow policy were undefined; 131073 JavaScript characters were only a lower-bound observation, not a UTF-8 byte policy."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "## Deferred Parent Row — Safe Bounded Plain Text; ## RED Acceptance Rules"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: safety
    status: conflict
    decision: hold
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: none_from_historical_observation
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["do not derive byte cap from character count", "no accumulator change while cap/outcome undefined"]
    forbiddenRules: ["no inferred 128 KiB/131072 cap", "no invented truncate/discard/terminal policy"]
    redTests: ["Cap-boundary and first-overflow test must remain not_run until an authoritative byte/outcome decision exists."]
    greenTests: ["HOLD is retained with no cap implementation derived from the old probe."]
    verificationCommands: ["Preserve the old probe as lower-bound evidence only; reconcile against the approved design before RED-B1."]
    nonGoals: ["unbounded plain fallback", "policy invention"]
    rollback: "No cap patch is permitted under this historical record."
    holdConditions: ["authoritative accumulated UTF-8 byte ceiling absent", "overflow outcome absent"]
    conflictsWith: [ND-CHAT-SSE-004]
    liveEvidence: ["inputChars=131073 and renderedChars=131073 are JavaScript-character observations only."]
  - requirementId: ND-CHAT-SSE-004
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Approved design resolves the future canonical boundary to maxEventUtf8Bytes=131072; the first overflow is terminal rawless stream_failed and later bytes are not rendered."
    source: {path: docs/superpowers/specs/2026-08-06-notebook-directive-consolidation-design.md, sha256: 376B7F1E30A35BF4C9F7AC70AF10F4104E6BDD8DD2559BDD7F533575EA63B6BA, locator: "## 6.3 Work unit W1: bounded SSE protocol parser"}
    sourcePaths: [docs/superpowers/specs/2026-08-06-notebook-directive-consolidation-design.md]
    sourceHashes: [376B7F1E30A35BF4C9F7AC70AF10F4104E6BDD8DD2559BDD7F533575EA63B6BA]
    category: safety
    status: pending
    decision: retain
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: matching_authorized_RED-B1_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["maxEventUtf8Bytes=131072", "first overflow=terminal stream_failed", "subsequent bytes not rendered", "outcome remains rawless"]
    forbiddenRules: ["no truncation", "no frame discard", "no raw overflow content", "no cap copied from typed body limit"]
    redTests: ["Cumulative UTF-8 bytes across fragmented chunks/multiple data fields pass at 131072 and fail terminally at first overflow byte."]
    greenTests: ["Exactly one terminal stream_failed occurs; no overflow bytes or raw detail render."]
    verificationCommands: ["Run W1 RED-B1 boundary/overflow fixture after W0, then W1 Node/Gradle ladder."]
    nonGoals: ["changing backend token chunk size", "nonterminal plain-text fallback"]
    rollback: "Restore current W1 manifest paths if cap GREEN/verification fails."
    holdConditions: ["W0 not GREEN", "ownership/preimage/lease gate missing", "valid RED not observed"]
    conflictsWith: [ND-CHAT-SSE-HOLD-001]
    liveEvidence: ["Approved design is the later explicit 131072/stream_failed decision; source execution remains separately held."]
  - requirementId: ND-CHAT-SSE-005
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Top-level scoreDelta uses existing safe diagnostic/rail ownership, one field assertion at a time, without unknown-event fallback."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "## Ordered RED-GREEN Contract > RED-C — top-level scoreDelta ownership"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: matching_RED-C_substage_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["one safe score diagnostic", "safe numeric/allowlisted fields only", "scoreDelta status rail"]
    forbiddenRules: ["no unknown diagnostic", "no broad payload dump", "no unreached field authorization"]
    redTests: ["score-delta-event-unhandled", "score-delta-value-missing", "score-delta-drop-ratio-missing", "score-delta-max-drawdown-missing", "score-delta-expected-value-missing", "score-delta-raw-value-missing", "score-delta-clamp-label-missing", "score-delta-stage-label-missing", "score-delta-guard-label-missing", "score-delta-event-id-missing", "score-delta-rail-missing"]
    greenTests: ["Each safe field/rail is added only after its corresponding RED and unknown diagnostics remain suppressed."]
    verificationCommands: ["Run ordered RED-C fixture rows through real chat.js and later W1 verification ladder."]
    nonGoals: ["new fusion/CVaR logic", "backend event schema change"]
    rollback: "Restore current stage paths and rerun the fixture."
    holdConditions: ["parser stages incomplete", "safe owner boundary not proven"]
    conflictsWith: []
    liveEvidence: ["ChatApiController already emits typed scoreDelta; current UI routes it to unknown diagnostic."]
  - requirementId: ND-CHAT-SSE-HOLD-002
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Conflict: current unknown-event fallback can retain raw/plain data/message/reason detail, while the future canonical rule requires categorical omission and a bounded event-name allowlist; no silent union is allowed."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "## Current C-Root Evidence Snapshot; ## Ordered RED-GREEN Contract > RED-D1 and RED-D2"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: safety
    status: conflict
    decision: hold
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: none_until_matching_RED
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["current C evidence selects rawBodyExposure=false", "diagnostic has only categorical safe event name", "unknown event stays nonterminal and fail-soft progress continues"]
    forbiddenRules: ["no payload.data/message/reason/query/other detail in DOM, ARIA, datasets, rails, console, or assistant content", "no raw unsafe/overlong event type"]
    redTests: ["unknown-event-data-not-omitted", "unknown-event-message-not-omitted", "unknown-event-reason-not-omitted", "unknown-event-other-detail-not-omitted", "unknown-event-name-character-not-bounded", "unknown-event-name-length-not-bounded"]
    greenTests: ["Only safe event name renders; token/final following unknown event still render once; unsafe names become 'unknown'."]
    verificationCommands: ["Run ordered RED-D1/D2 real stream fixture rows and ensure all sentinels are absent from every enumerated output surface."]
    nonGoals: ["new redaction helper", "terminalizing unknown events"]
    rollback: "Restore current W1 GREEN manifest; do not publish raw detail."
    holdConditions: ["fixture does not execute real reader/parser/renderer", "an alias-specific RED is not reached", "raw output would be retained"]
    conflictsWith: [ND-CHAT-SSE-006]
    liveEvidence: ["Current safeDebugCockpitDetail masking is pattern-based and insufficient for arbitrary query text."]
  - requirementId: ND-CHAT-SSE-006
    family: ND-CHAT-SSE
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
    normalizedRequirement: "Future unknown-event behavior is categorical omission: safe name only, no assistant content, nonterminal fail-soft progress, regex [A-Za-z0-9._-], and 1..64 name length."
    source: {path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, sha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, locator: "## Ordered RED-GREEN Contract > RED-D1 — unknown-event payload omission; ## RED-D2 — unknown-event name allowlist"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md]
    sourceHashes: [BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491]
    category: safety
    status: pending
    decision: retain
    disposition: HOLD
    workUnitId: W1-CHAT-SSE
    executionAuthority: matching_RED-D1_or_D2_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["safe name only", "no assistant append", "following token/final render once", "nonterminal unknown event"]
    forbiddenRules: ["no data/message/reason/query payload value", "no unsafe/raw type", "no second redaction helper"]
    redTests: ["RED-D1a through RED-D1d and RED-D2a through RED-D2b"]
    greenTests: ["Each alias is omitted; invalid/65-char types map to fixed 'unknown' label."]
    verificationCommands: ["Run real VM/DOM fixture with reset captured UI and console surfaces for each ordered RED-D row."]
    nonGoals: ["changing call Sync", "new diagnostic architecture"]
    rollback: "Restore current checkpoint if any output sentinel appears."
    holdConditions: ["ND-CHAT-SSE-HOLD-002 unresolved for the next RED", "source ownership gate absent"]
    conflictsWith: [ND-CHAT-SSE-HOLD-002]
    liveEvidence: ["Read-only unmapped_lane sentinel probe failed with unknown-event-query-red."]
  - requirementId: ND-CHAT-FAILSOFT-001
    family: ND-CHAT-FAILSOFT
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
    normalizedRequirement: "Typed fail-soft directive is prompt-only and follows SSE parser completion/reconciliation; mutation is held until current ownership, stable preflight, lease/preimages, baseline, and matching RED."
    source: {path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md, sha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D, locator: "front matter; ## Decision; ## Sibling Serialization Gate"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md]
    sourceHashes: [002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D]
    category: safety
    status: held
    decision: hold
    disposition: HOLD
    workUnitId: W2-CHAT-TYPED-FAILSOFT
    executionAuthority: none_until_all_gates
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["reconcile completed SSE postimage", "one lease", "one ordered RED at a time"]
    forbiddenRules: ["no sync generation", "no new route/controller/BFF", "no source authority from this artifact alone"]
    redTests: ["Matching typed failure row only after false-green characterization GREEN."]
    greenTests: ["Matching failure mapping GREEN with generic message_failed preserved."]
    verificationCommands: ["Run baseline, repair false-green characterizations, then ordered typed fixture and W2 verification ladder."]
    nonGoals: ["client deadline", "parent completion", "provider proof"]
    rollback: "Restore current two-target GREEN manifest under same lease."
    holdConditions: ["dirty-target-overlap", "fixture-false-green", "sibling-preimage-drift", "SSE parser unreconciled"]
    conflictsWith: [ND-CHAT-HOLD-OWNERSHIP-001]
    liveEvidence: ["Production discards non-2xx body and derives raw-ish exception diagnostic."]
  - requirementId: ND-CHAT-FAILSOFT-002
    family: ND-CHAT-FAILSOFT
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
    normalizedRequirement: "Before typed RED, repair three false-green fixture assumptions: no sync fallback, fresh turn baselines, and empty stream without run identity."
    source: {path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md, sha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D, locator: "## Baseline And False-Green Repair"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md]
    sourceHashes: [002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D]
    category: test
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W0-CHAT-FIXTURE
    executionAuthority: fixture_only_after_preflight
    targets: [scripts/chat_ui_stream_contract_tests.js]
    mandatoryRules: ["fresh child/event/request baselines", "zero exact /api/chat sync calls", "each characterization GREEN against unchanged chat.js"]
    forbiddenRules: ["no shared VM lifecycle leakage", "no application feature patch on characterization failure"]
    redTests: ["characterization-fresh-turn-no-sync", "characterization-ambiguous-stream-no-sync", "characterization-empty-stream-without-run-identity"]
    greenTests: ["Each renamed/rebuilt characterization proves generic failure, draft/session preservation, and no sync generation."]
    verificationCommands: ["node .\\scripts\\chat_ui_stream_contract_tests.js"]
    nonGoals: ["typed mapping", "sync fallback implementation"]
    rollback: "Restore fixture to preceding GREEN checkpoint if characterization fails."
    holdConditions: ["baseline-drift", "fixture-false-green", "ownership/preflight absent"]
    conflictsWith: []
    liveEvidence: ["Two sync-fallback-labelled blocks are false greens because production never tries sync generation."]
  - requirementId: ND-CHAT-FAILSOFT-003
    family: ND-CHAT-FAILSOFT
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
    normalizedRequirement: "Use one bounded internal failure schema and one non-2xx reader: maxFailureDecodedChars=1200, cancel reader remainder once, exact allowlisted scalar codes, no raw body or exception text."
    source: {path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md, sha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D, locator: "## Normative Transport And Typed Schema; ## Redaction And Bounded Parsing; ## Ordered RED-GREEN Contract > RED-P"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md]
    sourceHashes: [002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D]
    category: safety
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W2-CHAT-TYPED-FAILSOFT
    executionAuthority: matching_RED-P_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["maxFailureDecodedChars=1200", "one-pass non-2xx reader", "cancel remainder exactly once", "fixed keys only", "assistant text remains message_failed"]
    forbiddenRules: ["no recursive object search", "no substring acceptance", "no body/message/path/header/URL/query/stack/unknown data in UI/ARIA/dataset/console/TraceStore/proof"]
    redTests: ["bounded-failure-body-cap-missing"]
    greenTests: ["Oversized 503 reads no more than 1200 decoded chars, cancels once, ignores later allowlisted token, preserves draft, and exposes no typed metadata yet."]
    verificationCommands: ["Run RED-P through real sendMessage VM/DOM boundary before any HTTP-code mapping row."]
    nonGoals: ["SSE event-cap policy", "new error hierarchy", "retry button"]
    rollback: "Restore current fixture/chat.js checkpoint and rerun fixture."
    holdConditions: ["RED-P not valid", "reader cannot cancel once", "SSE postimage not reconciled"]
    conflictsWith: [ND-CHAT-SESSION-005]
    liveEvidence: ["Current non-2xx stream path throws before reading body."]
  - requirementId: ND-CHAT-FAILSOFT-004
    family: ND-CHAT-FAILSOFT
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
    normalizedRequirement: "Map only fixed typed outcomes with stated precedence: session_forbidden, auth statuses, backend unavailable/timeout, native network error, and categorical stream_failed fallback."
    source: {path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md, sha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D, locator: "## Normative Transport And Typed Schema; ## Ordered RED-GREEN Contract > RED-A through RED-F"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md]
    sourceHashes: [002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W2-CHAT-TYPED-FAILSOFT
    executionAuthority: matching_RED-A_to_RED-F_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["fixed failureKind/retryable/status/serverCode/nextAction schema", "session_forbidden precedes generic 401/403", "generic message_failed and exact-run precedence remain"]
    forbiddenRules: ["no open-ended enums", "no raw text", "no sync resubmit", "do not relabel model_wait as timeout"]
    redTests: ["typed-http-503-missing", "typed-http-403-forbidden-missing", "typed-http-403-session-denial-missing", "typed-http-403-status-session-preservation-missing", "typed-http-504-missing", "typed-network-error-missing", "bounded-failure-body-malformed-json-missing", "bounded-failure-body-unknown-token-missing", "typed-sse-backend-timeout-missing", "typed-sse-session-denial-missing", "typed-sse-unknown-error-missing"]
    greenTests: ["Each ordered scenario has exact categorical metadata, fixed next action, preserved draft, no unsafe sentinel, and zero sync calls."]
    verificationCommands: ["Run each RED-A through RED-F row independently after RED-P GREEN."]
    nonGoals: ["server API change", "automatic retry", "client timeout"]
    rollback: "Restore only current stage checkpoint; do not erase earlier GREEN stages."
    holdConditions: ["RED-P incomplete", "earlier B/E/F row not GREEN", "unsafe output observed"]
    conflictsWith: []
    liveEvidence: ["Backend/BFF already expose required categorical signals; no Java/BFF change justified."]
  - requirementId: ND-CHAT-FAILSOFT-005
    family: ND-CHAT-FAILSOFT
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
    normalizedRequirement: "Treat stream_failed and malformed terminal final JSON as terminal typed failures; preserve cancellation, terminal latch, exact-run recovery, no-client-deadline, and generic Java static statements."
    source: {path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md, sha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D, locator: "## Observed behavior; ## Redaction And Bounded Parsing; ## Ordered RED-GREEN Contract > RED-G and RED-H; ## Minimal GREEN Contract"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md]
    sourceHashes: [002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D]
    category: source
    status: pending
    decision: implement
    disposition: HOLD
    workUnitId: W2-CHAT-TYPED-FAILSOFT
    executionAuthority: matching_RED-G_or_RED-H_only
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js]
    mandatoryRules: ["stream_failed terminal", "malformed terminal final=terminal stream_failed", "message_failed/draft preservation", "confirmed AbortError/cancel stays stopped/cancelled"]
    forbiddenRules: ["no done/stream_end after stream_failed", "no raw malformed final as answer", "no client deadline"]
    redTests: ["stream-failed-terminal-missing", "malformed-final-fail-closed-missing"]
    greenTests: ["Partial token followed by stream_failed/malformed final produces one terminal generic failure, no completed answer or unsafe sentinel."]
    verificationCommands: ["Run RED-G and RED-H after RED-P/A-F, plus Java static contract and Node fixture checks."]
    nonGoals: ["ordinary nonterminal plain-message parser behavior", "cancel redesign"]
    rollback: "Restore current two-target manifest if terminality/verification fails."
    holdConditions: ["SSE parser not reconciled", "matching RED absent", "generic static contract drifts"]
    conflictsWith: [ND-CHAT-SSE-004]
    liveEvidence: ["Current stream_failed is nonterminal and malformed final can become raw completed answer."]
  - requirementId: ND-CHAT-HOLD-OWNERSHIP-001
    family: ND-CHAT-CROSS
    directiveId: AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805|AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805|AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
    normalizedRequirement: "Shared ownership and serialization HOLD: untracked overlapping targets require user hash attestation or canonical ref reconciliation, current C-root preflight, one lease, sibling postimage reconciliation, and stable three-way APPLY."
    source: {path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, sha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, locator: "## Mandatory Ownership Gate; ## Required Three-Way Preflight; ## Sibling Serialization Gate"}
    sourcePaths: [agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md, agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md, agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md]
    sourceHashes: [F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19, BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491, 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D]
    category: safety
    status: conflict
    decision: hold
    disposition: HOLD
    workUnitId: W0-CHAT-FIXTURE
    executionAuthority: none
    targets: [scripts/chat_ui_stream_contract_tests.js, main/resources/static/js/chat.js, main/resources/templates/chat-ui.html]
    mandatoryRules: ["current owner attestation or canonical ref", "POSITIVE_QUERY/NEGATIVE_QUERY/NEUTRAL_QUERY stable APPLY in A-B and B-A", "SSE precedes typed", "no shared lease"]
    forbiddenRules: ["no inference from matching bytes/Browser/JAR", "no overwriting sibling preimages", "no PatchDrop/lock/reparse/secret-risk bypass"]
    redTests: ["A source edit is proposed with absent ownership, unstable verdict, or mismatched sibling postimage."]
    greenTests: ["Current preflight/lease/attestation/postimage chain is complete for the next one bounded stage."]
    verificationCommands: ["Run Desktop collision preflight, exact three-way preflight, current hash reconciliation, and focused baseline before each mutation."]
    nonGoals: ["broad source lease", "canonical-root ownership inference from Git for untracked files"]
    rollback: "HOLD without mutation; preserve current source and input directives."
    holdConditions: ["dirty-target-overlap", "index lock", "foreign lease", "pending top-level PatchDrop", "changed preimage", "unstable verdict", "sibling-preimage-drift"]
    conflictsWith: [ND-CHAT-SESSION-001, ND-CHAT-SSE-001, ND-CHAT-FAILSOFT-001]
    liveEvidence: ["All declared source targets were untracked in the directive snapshots."]
  - requirementId: ND-DESKTOP-WRAPPER-001
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Modify only the user-designated blocker at the Desktop canonical root with the fewest files and lines."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] opening instruction"
    category: source
    status: held
    liveEvidence: ["input-15 hash revalidated", "no user-designated blocker or scoped target set is provided by this extraction"]
    targetFiles: []
    redTests: ["target-set-undeclared"]
    greenTests: ["one current preflight-approved blocker has a minimal declared diff"]
    verificationCommands: ["git diff --name-only -- <declared-targets>", "git diff --check -- <declared-targets>"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory minimal scoped change; forbidden unrelated edits"
    nonGoal: "broad cleanup or framework replacement"
    rollback: "revert only current work-unit declared-file hunks"
    authorityCondition: "Desktop canonical root is expected but not sufficient; stable preflight APPLY is required"
    collisionCondition: "unexplained dirty target overlap or branch/worktree collision holds the unit"
    leaseCondition: "a source-owner lease must be acquired for the exact declared target set"
    inputPreimageCondition: "capture and match each source target hash immediately before apply_patch"
    holdCondition: "blocker, target set, or preimages are not proven"
  - requirementId: ND-DESKTOP-WRAPPER-002
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Current files and actual command output outrank stale prompt claims."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 1"
    category: verification
    status: held
    liveEvidence: ["input-15 is supporting intent only; no current source/test observation is asserted"]
    targetFiles: []
    redTests: ["stale-claim-overrides-current-evidence"]
    greenTests: ["ledger decision cites current C-root source/test/command evidence"]
    verificationCommands: ["Get-Content -Raw -Encoding utf8 <current-target>", "<focused-current-test-command>"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory current evidence precedence; forbidden stale-prompt-only implementation claim"
    nonGoal: "trusting Notebook delivery as Desktop proof"
    rollback: "discard unsupported planned hunk before mutation"
    authorityCondition: "current C-root evidence is the authority"
    collisionCondition: "conflicting stale/current evidence remains HOLD until live source resolves it"
    leaseCondition: "no lease is authorized from historical text alone"
    inputPreimageCondition: "input-15 hash must remain B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B for retirement consideration"
    holdCondition: "current source/test output is missing or conflicts unresolved"
  - requirementId: ND-DESKTOP-WRAPPER-003
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Before editing, prove location, Git root/branch/worktree/status, index lock state, top-level PatchDrop patch state, and active/corrupt source-edit lease state."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 2"
    category: safety
    status: evidence_needed
    liveEvidence: ["read-only snapshot: index lock absent, zero top-level PatchDrop patches, known lease file absent; root/branch/status/worktree were not frozen for a source unit"]
    targetFiles: []
    redTests: ["desktop-collision-preflight-incomplete"]
    greenTests: ["all listed collision observations are current and classified"]
    verificationCommands: ["Get-Location", "git rev-parse --show-toplevel", "git branch --show-current", "git worktree list", "git status --short", "Test-Path .git\\index.lock", "powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\janitor_inventory.ps1", "powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\source_edit_session.ps1 -Action status -Role desktop -Root ."]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory collision preflight; forbidden edit before state is classified"
    nonGoal: "bypassing the repository collision guard"
    rollback: "no edit starts until preflight is green"
    authorityCondition: "observed Desktop root must match canonicalExecutionRoot"
    collisionCondition: "index lock, pending patch, dirty overlap, branch/worktree conflict, or corrupt lease is blocking"
    leaseCondition: "active or corrupt lease blocks a new source-owner session"
    inputPreimageCondition: "input-15 itself remains hash-pinned for future retirement only"
    holdCondition: "any preflight observation is absent, stale, or blocking"
  - requirementId: ND-DESKTOP-WRAPPER-004
    directiveId: none-declared-in-input-15
    normalizedRequirement: "If the resolved root is UNC, Y:, NAS, or SMB, stop with smb-direct-edit; role naming cannot bypass shared-path restrictions."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 3"
    category: safety
    status: not_applicable
    liveEvidence: ["current process working directory is C:\\AbandonWare\\demo-1\\demo-1\\src; no source unit root-resolution proof is frozen"]
    targetFiles: []
    redTests: ["shared-root-source-edit-attempt"]
    greenTests: ["shared-root detection stops without source mutation"]
    verificationCommands: ["Get-Location", "git rev-parse --show-toplevel"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "forbidden direct shared-path source edit under this wrapper"
    nonGoal: "using desktop role text to evade SMB guards"
    rollback: "no edit on detected shared root"
    authorityCondition: "only a proven Desktop local canonical root can enter its Desktop flow"
    collisionCondition: "shared workspace is a conflict-risk classification"
    leaseCondition: "no Desktop lease authorizes an SMB bypass"
    inputPreimageCondition: "source target hashes are not evaluated after smb-direct-edit STOP"
    holdCondition: "root resolves to UNC, Y:, NAS, SMB, or backing identity is unproven"
  - requirementId: ND-DESKTOP-WRAPPER-005
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Index locks, unrelated dirty changes, pending patches, active/corrupt leases, and branch/worktree conflicts require owner reporting and one evidence_needed action; never delete, reset, or checkout around them."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 4"
    category: safety
    status: held
    liveEvidence: ["no active source session; future collision state must be rechecked immediately before editing"]
    targetFiles: []
    redTests: ["collision-bypass-destructive-command"]
    greenTests: ["blocking classifier is reported without destructive recovery"]
    verificationCommands: ["Test-Path .git\\index.lock", "git status --short", "git worktree list", "powershell -NoProfile -ExecutionPolicy Bypass -File .\\__patch_drop__\\janitor_inventory.ps1"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "forbidden delete, git reset, or git checkout conflict bypass"
    nonGoal: "dirty-tree normalization"
    rollback: "preserve current owner changes; do not alter collision artifacts"
    authorityCondition: "only the source owner with non-conflicting state may proceed"
    collisionCondition: "each listed condition is an immediate HOLD"
    leaseCondition: "active/corrupt lease is an immediate HOLD"
    inputPreimageCondition: "changed target preimage is an immediate HOLD"
    holdCondition: "any collision or unresolved ownership exists"
  - requirementId: ND-DESKTOP-WRAPPER-006
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Prove active source through Gradle settings/build/sourceSets and edit only that active sourceSet."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 5"
    category: source
    status: evidence_needed
    liveEvidence: ["root and :app source-set expectations exist, but no work-unit current Gradle proof"]
    targetFiles: [main/java, main/resources, src/test/java, app/src/main/java_clean, app/src/main/resources]
    redTests: ["active-sourceset-uncertain", "inactive-root-targeted"]
    greenTests: ["checkSourceSetHygiene confirms the exact active owner before mutation"]
    verificationCommands: [".\\gradlew.bat checkSourceSetHygiene --no-daemon --project-cache-dir $awxProjectCache"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory Gradle/sourceSet proof; forbidden inactive/reference-root edit"
    nonGoal: "editing aliases, archives, generated outputs, or inferred mirrors"
    rollback: "revert only a proven active-source work-unit hunk"
    authorityCondition: "Gradle current output determines runtime owner"
    collisionCondition: "source-set drift is blocking"
    leaseCondition: "lease target set must remain inside proven active sourceSet"
    inputPreimageCondition: "each active target preimage must match immediately before apply"
    holdCondition: "settings/build/sourceSet proof fails or is stale"
  - requirementId: ND-DESKTOP-WRAPPER-007
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Do not disable login, logout, remember-me, CSRF, HTTPS, token/filter/interceptor, or diagnostics access control, and do not broaden permitAll; test-only relaxation requires an explicit profile gate and negative test."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 6"
    category: safety
    status: held
    liveEvidence: ["no security target or negative test is in the authorized chat tranche"]
    targetFiles: []
    redTests: ["access-control-disabled", "permitAll-broadened", "test-profile-gate-missing"]
    greenTests: ["existing security controls remain and any test-only exception has profile gate plus negative test"]
    verificationCommands: [".\\gradlew.bat test --tests '*Security*Test' --no-daemon --project-cache-dir $awxProjectCache"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "forbidden security-control disablement or access widening"
    nonGoal: "security framework replacement"
    rollback: "restore existing security control preimage and run negative tests"
    authorityCondition: "security scope needs an explicit separate target and approval"
    collisionCondition: "unrelated security dirty overlap blocks edit"
    leaseCondition: "no chat lease authorizes a security edit"
    inputPreimageCondition: "security-file preimage must be captured in its own work unit"
    holdCondition: "test-only profile/negative proof absent or public access broadens"
  - requirementId: ND-DESKTOP-WRAPPER-008
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Do not modify or print secret files, .env files, API keys, authorization/cookie values, or openssl/opnessl names, values, and formats."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 7"
    category: safety
    status: verified
    liveEvidence: ["this extraction does not access or mutate secret material"]
    targetFiles: []
    redTests: ["secret-leak-risk", "openssl-or-opnessl-contract-changed"]
    greenTests: ["count-only changed-target secret scan has zero findings and no protected config path is changed"]
    verificationCommands: ["powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\git_secret_guard.ps1 -Mode manual -Path <changed-targets>"]
    conflictsWith: []
    decision: retain
    disposition: DEFER
    mandatoryOrForbidden: "forbidden secret/config mutation or output"
    nonGoal: "credential setup, environment persistence, or secret normalization"
    rollback: "remove unauthorized hunk without printing sensitive data"
    authorityCondition: "no source preflight grants secret authority"
    collisionCondition: "secret risk is blocking"
    leaseCondition: "no source lease includes secret files"
    inputPreimageCondition: "protected secret-bearing files remain excluded from preimage/apply scope"
    holdCondition: "secret hit, sensitive path in target set, or redaction proof unavailable"
  - requirementId: ND-DESKTOP-WRAPPER-009
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Keep every dev.langchain4j dependency at 1.0.1 and preserve PromptBuilder.build(PromptContext) as the final RAG prompt boundary."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 8"
    category: safety
    status: evidence_needed
    liveEvidence: ["no current version-purity or PromptBuilder boundary command is run for this wrapper work unit"]
    targetFiles: [build.gradle.kts, main/java/com/example/lms/prompt/PromptBuilder.java]
    redTests: ["mixed-langchain4j-version", "prompt-boundary-bypass"]
    greenTests: ["checkLangchain4jVersionPurity and PromptBuilderBoundaryTest pass"]
    verificationCommands: [".\\gradlew.bat checkLangchain4jVersionPurity --no-daemon --project-cache-dir $awxProjectCache", ".\\gradlew.bat test --tests '*PromptBuilderBoundaryTest' --no-daemon --project-cache-dir $awxProjectCache"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory 1.0.1 and canonical prompt boundary; forbidden dependency drift and prompt bypass"
    nonGoal: "LangChain4j upgrade or prompt-builder replacement"
    rollback: "restore only declared dependency/boundary hunk to its captured preimage"
    authorityCondition: "RAG seam requires independent source owner and focused RED"
    collisionCondition: "mixed version or boundary failure blocks any RAG claim"
    leaseCondition: "wrapper does not share a broad RAG lease"
    inputPreimageCondition: "build and prompt-boundary target hashes are captured immediately before edit"
    holdCondition: "purity/boundary proof missing or failing"
  - requirementId: ND-DESKTOP-WRAPPER-010
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Do not add duplicate filters, controllers, routes, shadow implementations, or a new orchestration/security framework."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 9"
    category: non-goal
    status: held
    liveEvidence: ["no declared implementation target or duplicate analysis is in scope"]
    targetFiles: []
    redTests: ["duplicate-owner-or-shadow-implementation-proposed"]
    greenTests: ["existing owner seam is extended minimally or no patch is needed"]
    verificationCommands: ["rg -n \"class .*Controller|SecurityFilterChain|@RequestMapping|@GetMapping|@PostMapping\" main/java"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "forbidden duplicate/shadow/framework additions"
    nonGoal: "parallel route, controller, security, or orchestration stack"
    rollback: "remove newly added duplicate only after owner/preimage review"
    authorityCondition: "current call-path evidence selects one owner seam"
    collisionCondition: "existing parallel owner ambiguity is HOLD"
    leaseCondition: "lease cannot expand to create a shadow seam"
    inputPreimageCondition: "owner file preimages are required if RED identifies a real defect"
    holdCondition: "no unique live owner is proven"
  - requirementId: ND-DESKTOP-WRAPPER-011
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Verify in order: focused RED, minimum implementation, focused GREEN, compile/Gradle gate, then diff and count-only secret scan."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rule 10"
    category: verification
    status: evidence_needed
    liveEvidence: ["no source work unit has started in this extraction"]
    targetFiles: []
    redTests: ["missing-valid-RED", "green-without-focused-red", "secret-scan-skipped"]
    greenTests: ["one scoped work unit records the entire order with command outputs"]
    verificationCommands: ["<focused-red-command>", "<focused-green-command>", ".\\gradlew.bat compileJava --no-daemon --project-cache-dir $awxProjectCache", "git diff --check -- <declared-targets>", "powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\git_secret_guard.ps1 -Mode manual -Path <declared-targets>"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory verification order; forbidden implementation-first or unverified GREEN claim"
    nonGoal: "broad build before the narrowest behavior is characterized"
    rollback: "if focused verification fails, revert current unit hunk only"
    authorityCondition: "stable preflight APPLY precedes RED and source-owner guard"
    collisionCondition: "new collision after RED ends the unit"
    leaseCondition: "lease covers only one work unit and is released after evidence"
    inputPreimageCondition: "post-RED target hash is reconfirmed immediately before patch"
    holdCondition: "valid RED, focused GREEN, compile, diff, or secret proof is absent/failing"
  - requirementId: ND-DESKTOP-WRAPPER-012
    directiveId: none-declared-in-input-15
    normalizedRequirement: "Do not claim build, boot, HTTPS, login, provider, or browser success without actual current output; Notebook/Mac mini evidence is supporting only, and final report is Observation, Patch, Verification, Risks & Next with one classified next action on failure."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] rules 11-12"
    category: runtime
    status: held
    liveEvidence: ["this artifact has no build/boot/HTTPS/login/provider/browser execution result"]
    targetFiles: []
    redTests: ["claim-without-current-output", "external-supporting-evidence-promoted-to-desktop-proof"]
    greenTests: ["each asserted result has same-turn command/browser output and final report has required four sections"]
    verificationCommands: ["<run-only-the-in-scope-command>", "<fresh-browser-or-runtime-proof-when-requested>"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory current evidence and categorical failure reporting; forbidden unsupported success claim"
    nonGoal: "treating rendered UI, HTTP 200, or handoff existence as semantic/provider success"
    rollback: "withdraw unsupported claim and retain only observed evidence"
    authorityCondition: "Desktop current output is final proof authority"
    collisionCondition: "unrelated runtime failure is reported as external blocker, not patched here"
    leaseCondition: "runtime/browser proof never extends a source lease"
    inputPreimageCondition: "input-15 retirement remains HOLD until all mapped work units are GREEN and exact hash is unchanged"
    holdCondition: "required current output or classified single next action is unavailable"
  - requirementId: ND-DESKTOP-WRAPPER-013
    directiveId: none-declared-in-input-15
    normalizedRequirement: "When security or lease recovery is needed, read security-guardrail-audit-desktop-directive.md first and apply its STOP gates."
    sourcePaths: [__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md]
    sourceHashes: [B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B]
    sourceLocator: "[DESKTOP CODEX / SOURCE EDIT] final paragraph"
    category: safety
    status: held
    liveEvidence: ["no security/lease recovery request or audit-directive intake is in scope"]
    targetFiles: [__patch_drop__/notebook/security-guardrail-audit-desktop-directive.md]
    redTests: ["security-or-lease-recovery-without-audit-stop-gate"]
    greenTests: ["recovery decision cites the audit directive and preserves its STOP classification"]
    verificationCommands: ["Get-Content -Raw -Encoding utf8 .\\__patch_drop__\\notebook\\security-guardrail-audit-desktop-directive.md"]
    conflictsWith: []
    decision: hold
    disposition: HOLD
    mandatoryOrForbidden: "mandatory prerequisite reading for recovery; forbidden direct recovery without applicable STOP gates"
    nonGoal: "automatic ACL, lock, lease, or security recovery"
    rollback: "perform no recovery mutation until its separate guard proves authority"
    authorityCondition: "wrapper delegates recovery authority to the referenced audit contract"
    collisionCondition: "security/lease incident remains blocking until audit classification"
    leaseCondition: "corrupt lease recovery is not authorized by wrapper alone"
    inputPreimageCondition: "referenced audit input must be re-read and current before a recovery decision"
    holdCondition: "recovery needed but prerequisite audit evidence is absent"
```

## Conflict Decisions

```yaml
conflictDecisions:
  currentCEvidenceWins: true
  rawBodyExposure: false
  decision: hold
  outcome: HOLD
  rule: "Current C-root source/tests decide factual conflicts; stricter compatible safety/redaction/authority rule wins, otherwise no silent union."
  unresolved:
    - conflictId: DPA-PACKET-VALIDATOR-MISSING
      decision: hold
      outcome: HOLD
      reason: "DPA official packet-validator evidence is absent."
    - conflictId: NEXT-ROUTE-POLICY
      decision: hold
      outcome: HOLD
      reason: "Next/Spring authority and frontend boundary require their separate deferred work unit."
    - conflictId: RUNTIME-LINEAGE
      decision: hold
      outcome: HOLD
      reason: "Delivery, HTTP success, build, boot, or rendered UI do not prove provider/wire/semantic success."
    - conflictId: RAW-EXPOSURE
      decision: hold
      outcome: HOLD
      reason: "Raw provider bodies, prompts, responses, credentials, and sensitive query material remain prohibited."
```

## Immediate Chat Work Units

```yaml
workUnits:
  - workUnitId: W0-CHAT-FIXTURE
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
    nextProof: behavior-neutral fixture GREEN then message-event-unhandled RED
  - workUnitId: W1-CHAT-SSE
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
      - main/resources/static/js/chat.js
    maxEventUtf8Bytes: 131072
    overflowOutcome: stream_failed
  - workUnitId: W2-CHAT-TYPED-FAILSOFT
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
      - main/resources/static/js/chat.js
    maxFailureDecodedChars: 1200
  - workUnitId: W3-CHAT-SESSION-LIST
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
      - main/resources/static/js/chat.js
      - main/resources/templates/chat-ui.html
    maxVisibleSessions: 12
```

## Deferred Work Units

```yaml
workUnits:
  - workUnitId: DEFER-RAG-WEB
    status: hold
    requirementFamilies: [ND-RAG-WEB]
    reason: "Desktop preflight, focused RED/GREEN, and request-specific provider lineage are incomplete."
  - workUnitId: DEFER-AGENT-CODE-EVIDENCE-GATE
    status: hold
    requirementFamilies: [ND-AGENT-GATE]
    reason: "Immutable fixture, pinned required tools, hidden-oracle boundary, and Desktop proof are incomplete."
  - workUnitId: W-RISK-UTILITY
    status: hold
    requirementFamilies: [ND-RISK-UTILITY]
    reason: "Risk/utility evidence is preserved without source authority."
  - workUnitId: D-DPA-P0
    status: hold
    requirementFamilies: [ND-DPA]
    reason: "Packet validation, Desktop preflight, and P0-specific evidence gates are incomplete."
  - workUnitId: DEFER-NEXT-BFF
    status: hold
    requirementFamilies: [ND-NEXT-BFF]
    reason: "Next/Spring authority separation and frontend boundary remain deferred."
  - workUnitId: HOLD-MAIN-CHAT-PARENT
    status: hold
    requirementFamilies: [ND-MAIN-CHAT-PARENT]
    reason: "Parent-chat authority, collision, and physical-attempt lineage remain held."
  - workUnitId: DEFER-SECURITY-HTTP-SUPABASE
    status: hold
    reason: "Security, HTTP rollback, and Supabase work require separate scoped evidence and authority."
```

## Historical Auxiliary Contract Hold

```yaml
status: hold
scope: historical stash blobs and auxiliary prompt-boundary drafts
authority: none
sourceMutationAuthorized: false
retirementAuthorized: false
reason: "Historical material is not an active sourceSet and is outside this readable tranche."
nextProof: "A separate explicit evidence pass must map every requirement to this published canonical ledger and prove exact retirement eligibility."
```

## ACL-Protected Hold

```yaml
status: hold
protectedArtifactCount: 37
contentRead: false
aclMutationAuthorized: false
retirementAuthorized: false
reason: "ACL-protected June 5 reports are unreadable; their contents must not be inferred, recovered, listed, or deleted."
nextProof: "Only a later explicit authorized accessibility/evidence pass may reconsider one exact leaf."
```

## Verification And Rollback

```yaml
executionOrder:
  - Desktop collision preflight
  - freeze redacted EvidenceSnapshot
  - exactly POSITIVE_QUERY, NEGATIVE_QUERY, NEUTRAL_QUERY
  - stable APPLY
  - source-owner lease and immediate declared-target preimages
  - focused RED
  - smallest implementation
  - focused GREEN
  - broadened verification only when in scope
  - exact-path diff and count-only secret scan
rollbackRule: "Revert only current work-unit declared-file hunks to captured preimages; never use git reset --hard, checkout restoration, wildcard deletion, or cross-work-unit rollback."
stopRule: "Index lock, dirty overlap, PatchDrop collision, lease failure/corruption, sourceSet uncertainty, changed preimage, missing valid RED, secret risk, or failed focused verification is HOLD."
```

## RetirementManifest

```yaml
schemaVersion: demo1.notebook-directive-retirement.v1
canonicalDirectivePath: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
canonicalDirectiveSha256Evidence: external-final-output
allRequiredWorkUnitsGreen: false
status: pending
strongGates:
- 6 evidence IDs and triad constraints fully represented
- 7 complete DPA architecture and file map represented
- 8 complete DPA tests, rollback, and physical-attempt lineage represented
- 9 Next/Spring authority separation and frontend boundary represented
items:
- itemNumber: 1
  path: data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
  expectedSha256: 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
  gitTracking: unknown
  absorbedRequirementIds:
  - ND-RAG-WEB-003
  - ND-RAG-WEB-005
  - ND-RAG-WEB-006
  - ND-RAG-WEB-011
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical directive unpublished; all RAG RED/GREEN and request-specific
    runtime lineage remain unproven
  deletionResult: not_run
  status: hold
- itemNumber: 2
  path: agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md
  expectedSha256: 89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110
  gitTracking: unknown
  absorbedRequirementIds:
  - ND-RAG-WEB-001
  - ND-RAG-WEB-002
  - ND-RAG-WEB-003
  - ND-RAG-WEB-004
  - ND-RAG-WEB-005
  - ND-RAG-WEB-006
  - ND-RAG-WEB-007
  - ND-RAG-WEB-008
  - ND-RAG-WEB-009
  - ND-RAG-WEB-010
  - ND-RAG-WEB-012
  - ND-RAG-WEB-013
  - ND-RAG-WEB-014
  - ND-RAG-WEB-015
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical directive unpublished; Desktop preflight, focused/broad GREEN,
    and runtime lineage proof absent
  deletionResult: not_run
  status: hold
- itemNumber: 3
  path: data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md
  expectedSha256: 12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB
  gitTracking: unknown
  absorbedRequirementIds:
  - ND-AGENT-GATE-001
  - ND-AGENT-GATE-002
  - ND-AGENT-GATE-003
  - ND-AGENT-GATE-004
  - ND-AGENT-GATE-005
  - ND-AGENT-GATE-006
  - ND-AGENT-GATE-007
  - ND-AGENT-GATE-008
  - ND-AGENT-GATE-009
  - ND-AGENT-GATE-010
  - ND-AGENT-GATE-011
  - ND-AGENT-GATE-012
  - ND-AGENT-GATE-013
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical directive unpublished; immutable fixture, pinned required
    tools, hidden-oracle proof, and Desktop gate GREEN absent
  deletionResult: not_run
  status: hold
- itemNumber: 4
  path: __reports__/notebook-risk-utility-triad-2026-08-04.json
  expectedSha256: 179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130
  gitTracking: untracked
  absorbedRequirementIds:
  - GD-RISK-UTILITY-SHADOW-20260804
  - ND-RISK-UTILITY-001
  - ND-RISK-UTILITY-002
  - ND-RISK-UTILITY-003
  - ND-RISK-UTILITY-004
  - ND-RISK-UTILITY-005
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: Deferred requirements and unresolved calibration/provenance/authority
    HOLD rows remain; all required work units are not GREEN.
  deletionResult: not_run
  status: hold
- itemNumber: 5
  path: __reports__/desktop-risk-utility-source-directive-2026-08-04.md
  expectedSha256: 4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E
  gitTracking: untracked
  absorbedRequirementIds:
  - SD-RISK-UTILITY-SHADOW-20260804
  - ND-RISK-UTILITY-006
  - ND-RISK-UTILITY-ENFORCEMENT-20260804
  - ND-RISK-UTILITY-007
  - ND-RISK-UTILITY-008
  - ND-RISK-UTILITY-009
  - ND-RISK-UTILITY-010
  - ND-RISK-UTILITY-011
  - ND-RISK-UTILITY-HOLD-001
  - ND-RISK-UTILITY-HOLD-002
  - ND-RISK-UTILITY-HOLD-003
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: Phase 1 is deferred, Phase 2 is HOLD, and current canonical-root/approval/verification
    gates are not complete.
  deletionResult: not_run
  status: hold
- itemNumber: 6
  path: docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json
  expectedSha256: BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-DPA-006
  - ND-DPA-007
  - ND-DPA-008
  - ND-DPA-009
  - ND-DPA-010
  - ND-DPA-011
  - ND-DPA-012
  - ND-DPA-013
  - ND-DPA-014
  - ND-DPA-015
  - ND-DPA-016
  - ND-DPA-017
  - ND-DPA-018
  - ND-DPA-019
  - ND-DPA-020
  - ND-DPA-021
  - ND-DPA-022
  - ND-DPA-023
  - ND-DPA-024
  - ND-DPA-025
  - ND-DPA-026
  - ND-DPA-027
  - ND-DPA-028
  - ND-DPA-029
  - ND-DPA-030
  - ND-DPA-031
  - ND-DPA-032
  - ND-DPA-033
  - ND-DPA-034
  - ND-DPA-035
  - ND-DPA-036
  - ND-DPA-037
  - ND-DPA-038
  coverageDecision: hold
  strongGate: 6 evidence IDs and triad constraints fully represented
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: 6 evidence IDs and triad constraints fully represented
  deletionResult: not_run
  status: hold
- itemNumber: 7
  path: docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md
  expectedSha256: DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-DPA-001
  - ND-DPA-002
  - ND-DPA-003
  - ND-DPA-004
  - ND-DPA-005
  - ND-DPA-006
  - ND-DPA-007
  - ND-DPA-037
  - ND-DPA-038
  - ND-DPA-039
  - ND-DPA-040
  - ND-DPA-041
  - ND-DPA-042
  - ND-DPA-043
  - ND-DPA-044
  - ND-DPA-045
  - ND-DPA-046
  - ND-DPA-047
  - ND-DPA-048
  - ND-DPA-049
  - ND-DPA-050
  - ND-DPA-051
  - ND-DPA-052
  - ND-DPA-053
  - ND-DPA-054
  - ND-DPA-055
  - ND-DPA-056
  - ND-DPA-057
  - ND-DPA-058
  - ND-DPA-059
  - ND-DPA-060
  - ND-DPA-061
  - ND-DPA-062
  - ND-DPA-063
  - ND-DPA-064
  - ND-DPA-065
  - ND-DPA-066
  - ND-DPA-067
  - ND-DPA-068
  - ND-DPA-069
  - ND-DPA-070
  - ND-DPA-071
  - ND-DPA-072
  - ND-DPA-073
  - ND-DPA-074
  - ND-DPA-075
  - ND-DPA-076
  - ND-DPA-077
  - ND-DPA-078
  - ND-DPA-079
  - ND-DPA-080
  - ND-DPA-081
  - ND-DPA-082
  - ND-DPA-083
  - ND-DPA-084
  - ND-DPA-085
  - ND-DPA-086
  - ND-DPA-087
  - ND-DPA-088
  - ND-DPA-089
  - ND-DPA-090
  - ND-DPA-091
  - ND-DPA-092
  - ND-DPA-093
  - ND-DPA-094
  - ND-DPA-095
  - ND-DPA-096
  - ND-DPA-097
  - ND-DPA-098
  - ND-DPA-099
  - ND-DPA-100
  - ND-DPA-101
  - ND-DPA-102
  - ND-DPA-103
  - ND-DPA-104
  - ND-DPA-105
  - ND-DPA-106
  - ND-DPA-107
  - ND-DPA-108
  - ND-DPA-155
  - ND-DPA-158
  - ND-DPA-159
  - ND-DPA-160
  - ND-DPA-161
  - ND-DPA-162
  coverageDecision: hold
  strongGate: 7 complete DPA architecture and file map represented
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: 7 complete DPA architecture and file map represented
  deletionResult: not_run
  status: hold
- itemNumber: 8
  path: docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md
  expectedSha256: D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-DPA-003
  - ND-DPA-004
  - ND-DPA-005
  - ND-DPA-006
  - ND-DPA-007
  - ND-DPA-036
  - ND-DPA-037
  - ND-DPA-055
  - ND-DPA-056
  - ND-DPA-057
  - ND-DPA-073
  - ND-DPA-075
  - ND-DPA-076
  - ND-DPA-078
  - ND-DPA-079
  - ND-DPA-080
  - ND-DPA-081
  - ND-DPA-082
  - ND-DPA-083
  - ND-DPA-084
  - ND-DPA-085
  - ND-DPA-086
  - ND-DPA-087
  - ND-DPA-089
  - ND-DPA-090
  - ND-DPA-091
  - ND-DPA-092
  - ND-DPA-093
  - ND-DPA-094
  - ND-DPA-095
  - ND-DPA-096
  - ND-DPA-097
  - ND-DPA-098
  - ND-DPA-099
  - ND-DPA-100
  - ND-DPA-101
  - ND-DPA-102
  - ND-DPA-103
  - ND-DPA-104
  - ND-DPA-105
  - ND-DPA-106
  - ND-DPA-109
  - ND-DPA-110
  - ND-DPA-111
  - ND-DPA-112
  - ND-DPA-113
  - ND-DPA-114
  - ND-DPA-115
  - ND-DPA-116
  - ND-DPA-117
  - ND-DPA-118
  - ND-DPA-119
  - ND-DPA-120
  - ND-DPA-121
  - ND-DPA-122
  - ND-DPA-123
  - ND-DPA-124
  - ND-DPA-125
  - ND-DPA-126
  - ND-DPA-127
  - ND-DPA-128
  - ND-DPA-129
  - ND-DPA-130
  - ND-DPA-131
  - ND-DPA-132
  - ND-DPA-133
  - ND-DPA-134
  - ND-DPA-135
  - ND-DPA-136
  - ND-DPA-137
  - ND-DPA-138
  - ND-DPA-139
  - ND-DPA-140
  - ND-DPA-141
  - ND-DPA-142
  - ND-DPA-143
  - ND-DPA-144
  - ND-DPA-145
  - ND-DPA-146
  - ND-DPA-147
  - ND-DPA-148
  - ND-DPA-149
  - ND-DPA-150
  - ND-DPA-151
  - ND-DPA-152
  - ND-DPA-153
  - ND-DPA-154
  - ND-DPA-155
  - ND-DPA-156
  - ND-DPA-157
  - ND-DPA-158
  - ND-DPA-159
  - ND-DPA-160
  - ND-DPA-161
  - ND-DPA-162
  coverageDecision: hold
  strongGate: 8 complete DPA tests, rollback, and physical-attempt lineage represented
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: 8 complete DPA tests, rollback, and physical-attempt lineage represented
  deletionResult: not_run
  status: hold
- itemNumber: 9
  path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md
  expectedSha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-NEXT-BFF-001
  - ND-NEXT-BFF-002
  - ND-NEXT-BFF-003
  - ND-NEXT-BFF-004
  - ND-NEXT-BFF-005
  - ND-NEXT-BFF-006
  - ND-NEXT-BFF-007
  - ND-NEXT-BFF-008
  - ND-NEXT-BFF-009
  - ND-NEXT-BFF-010
  - ND-NEXT-BFF-011
  - ND-NEXT-BFF-012
  - ND-NEXT-BFF-013
  - ND-NEXT-BFF-014
  - ND-NEXT-BFF-015
  - ND-NEXT-BFF-016
  - ND-NEXT-BFF-017
  - ND-NEXT-BFF-018
  - ND-NEXT-BFF-019
  - ND-NEXT-BFF-020
  - ND-NEXT-BFF-021
  - ND-NEXT-BFF-022
  - ND-NEXT-BFF-023
  - ND-NEXT-BFF-024
  - ND-NEXT-BFF-025
  - ND-NEXT-BFF-026
  - ND-NEXT-BFF-027
  - ND-NEXT-BFF-028
  - ND-NEXT-BFF-029
  - ND-NEXT-BFF-030
  - ND-NEXT-BFF-031
  - ND-NEXT-BFF-032
  - ND-NEXT-BFF-033
  - ND-NEXT-BFF-034
  - ND-NEXT-BFF-035
  - ND-NEXT-BFF-036
  - ND-NEXT-BFF-037
  - ND-NEXT-BFF-038
  - ND-NEXT-BFF-039
  - ND-NEXT-BFF-051
  - ND-NEXT-BFF-052
  - ND-NEXT-BFF-053
  - ND-NEXT-BFF-054
  - ND-NEXT-BFF-055
  - ND-NEXT-BFF-056
  - ND-NEXT-BFF-057
  - ND-NEXT-BFF-058
  - ND-NEXT-BFF-059
  - ND-NEXT-BFF-061
  - ND-NEXT-BFF-062
  - ND-NEXT-BFF-063
  - ND-NEXT-BFF-070
  - ND-NEXT-BFF-071
  - ND-NEXT-BFF-072
  - ND-NEXT-BFF-073
  - ND-NEXT-BFF-074
  - ND-NEXT-BFF-075
  - ND-NEXT-BFF-076
  - ND-NEXT-BFF-077
  - ND-NEXT-BFF-078
  - ND-NEXT-BFF-079
  - ND-NEXT-BFF-080
  - ND-NEXT-BFF-081
  - ND-NEXT-BFF-082
  - ND-NEXT-BFF-083
  - ND-NEXT-BFF-084
  - ND-NEXT-BFF-085
  - ND-NEXT-BFF-087
  - ND-NEXT-BFF-088
  - ND-NEXT-BFF-089
  - ND-NEXT-BFF-090
  - ND-NEXT-BFF-092
  - ND-NEXT-BFF-094
  - ND-NEXT-BFF-095
  coverageDecision: hold
  strongGate: 9 Next/Spring authority separation and frontend boundary represented
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical-directive-unpublished-and-required-work-units-not-green
  deletionResult: not_run
  status: hold
- itemNumber: 10
  path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md
  expectedSha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-MAIN-CHAT-PARENT-001
  - ND-MAIN-CHAT-PARENT-002
  - ND-MAIN-CHAT-PARENT-003
  - ND-MAIN-CHAT-PARENT-004
  - ND-MAIN-CHAT-PARENT-005
  - ND-MAIN-CHAT-PARENT-006
  - ND-MAIN-CHAT-PARENT-007
  - ND-MAIN-CHAT-PARENT-011
  - ND-MAIN-CHAT-PARENT-012
  - ND-MAIN-CHAT-PARENT-013
  - ND-MAIN-CHAT-PARENT-014
  - ND-MAIN-CHAT-PARENT-015
  - ND-MAIN-CHAT-PARENT-016
  - ND-MAIN-CHAT-PARENT-017
  - ND-MAIN-CHAT-PARENT-018
  - ND-MAIN-CHAT-PARENT-019
  - ND-MAIN-CHAT-PARENT-020
  - ND-MAIN-CHAT-PARENT-021
  - ND-MAIN-CHAT-PARENT-022
  - ND-MAIN-CHAT-PARENT-023
  - ND-MAIN-CHAT-PARENT-024
  - ND-MAIN-CHAT-PARENT-025
  - ND-MAIN-CHAT-PARENT-026
  - ND-MAIN-CHAT-PARENT-027
  - ND-MAIN-CHAT-PARENT-028
  - ND-MAIN-CHAT-PARENT-029
  - ND-MAIN-CHAT-PARENT-030
  - ND-MAIN-CHAT-PARENT-031
  - ND-MAIN-CHAT-PARENT-032
  - ND-MAIN-CHAT-PARENT-033
  - ND-MAIN-CHAT-PARENT-034
  - ND-MAIN-CHAT-PARENT-035
  - ND-MAIN-CHAT-PARENT-036
  - ND-MAIN-CHAT-PARENT-051
  - ND-MAIN-CHAT-PARENT-052
  - ND-MAIN-CHAT-PARENT-053
  - ND-MAIN-CHAT-PARENT-063
  - ND-MAIN-CHAT-PARENT-064
  - ND-MAIN-CHAT-PARENT-065
  - ND-MAIN-CHAT-PARENT-067
  - ND-MAIN-CHAT-PARENT-069
  - ND-MAIN-CHAT-PARENT-072
  - ND-MAIN-CHAT-PARENT-073
  - ND-MAIN-CHAT-PARENT-075
  - ND-MAIN-CHAT-PARENT-077
  - ND-MAIN-CHAT-PARENT-078
  - ND-MAIN-CHAT-PARENT-083
  - ND-MAIN-CHAT-PARENT-084
  - ND-MAIN-CHAT-PARENT-086
  - ND-MAIN-CHAT-PARENT-087
  - ND-MAIN-CHAT-PARENT-088
  - ND-MAIN-CHAT-PARENT-090
  coverageDecision: hold
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical-directive-unpublished-and-required-work-units-not-green
  deletionResult: not_run
  status: hold
- itemNumber: 11
  path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md
  expectedSha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-MAIN-CHAT-PARENT-008
  - ND-MAIN-CHAT-PARENT-009
  - ND-MAIN-CHAT-PARENT-010
  - ND-MAIN-CHAT-PARENT-011
  - ND-MAIN-CHAT-PARENT-012
  - ND-MAIN-CHAT-PARENT-013
  - ND-MAIN-CHAT-PARENT-015
  - ND-MAIN-CHAT-PARENT-016
  - ND-MAIN-CHAT-PARENT-021
  - ND-MAIN-CHAT-PARENT-022
  - ND-MAIN-CHAT-PARENT-024
  - ND-MAIN-CHAT-PARENT-026
  - ND-MAIN-CHAT-PARENT-035
  - ND-MAIN-CHAT-PARENT-036
  - ND-MAIN-CHAT-PARENT-037
  - ND-MAIN-CHAT-PARENT-038
  - ND-MAIN-CHAT-PARENT-039
  - ND-MAIN-CHAT-PARENT-040
  - ND-MAIN-CHAT-PARENT-041
  - ND-MAIN-CHAT-PARENT-042
  - ND-MAIN-CHAT-PARENT-043
  - ND-MAIN-CHAT-PARENT-044
  - ND-MAIN-CHAT-PARENT-045
  - ND-MAIN-CHAT-PARENT-046
  - ND-MAIN-CHAT-PARENT-047
  - ND-MAIN-CHAT-PARENT-048
  - ND-MAIN-CHAT-PARENT-049
  - ND-MAIN-CHAT-PARENT-050
  - ND-MAIN-CHAT-PARENT-054
  - ND-MAIN-CHAT-PARENT-055
  - ND-MAIN-CHAT-PARENT-056
  - ND-MAIN-CHAT-PARENT-057
  - ND-MAIN-CHAT-PARENT-058
  - ND-MAIN-CHAT-PARENT-059
  - ND-MAIN-CHAT-PARENT-060
  - ND-MAIN-CHAT-PARENT-061
  - ND-MAIN-CHAT-PARENT-062
  - ND-MAIN-CHAT-PARENT-063
  - ND-MAIN-CHAT-PARENT-064
  - ND-MAIN-CHAT-PARENT-066
  - ND-MAIN-CHAT-PARENT-068
  - ND-MAIN-CHAT-PARENT-069
  - ND-MAIN-CHAT-PARENT-070
  - ND-MAIN-CHAT-PARENT-071
  - ND-MAIN-CHAT-PARENT-072
  - ND-MAIN-CHAT-PARENT-073
  - ND-MAIN-CHAT-PARENT-074
  - ND-MAIN-CHAT-PARENT-075
  - ND-MAIN-CHAT-PARENT-076
  - ND-MAIN-CHAT-PARENT-077
  - ND-MAIN-CHAT-PARENT-079
  - ND-MAIN-CHAT-PARENT-080
  - ND-MAIN-CHAT-PARENT-081
  - ND-MAIN-CHAT-PARENT-082
  - ND-MAIN-CHAT-PARENT-083
  - ND-MAIN-CHAT-PARENT-084
  - ND-MAIN-CHAT-PARENT-085
  - ND-MAIN-CHAT-PARENT-088
  - ND-MAIN-CHAT-PARENT-089
  - ND-MAIN-CHAT-PARENT-090
  - ND-MAIN-CHAT-PARENT-091
  - ND-MAIN-CHAT-PARENT-092
  - ND-MAIN-CHAT-PARENT-093
  - ND-MAIN-CHAT-PARENT-094
  coverageDecision: hold
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical-directive-unpublished-and-required-work-units-not-green
  deletionResult: not_run
  status: hold
- itemNumber: 12
  path: agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md
  expectedSha256: F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-CHAT-SESSION-001
  - ND-CHAT-SESSION-002
  - ND-CHAT-SESSION-003
  - ND-CHAT-SESSION-004
  - ND-CHAT-SESSION-005
  - ND-CHAT-SESSION-006
  - ND-CHAT-SESSION-007
  - ND-CHAT-HOLD-OWNERSHIP-001
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: W0/W3 pending; ownership, sibling, RED/GREEN, Gradle, Browser, and current
    proof gates are incomplete.
  deletionResult: not_run
  status: hold
- itemNumber: 13
  path: agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md
  expectedSha256: BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-CHAT-SSE-001
  - ND-CHAT-SSE-002
  - ND-CHAT-SSE-003
  - ND-CHAT-SSE-HOLD-001
  - ND-CHAT-SSE-004
  - ND-CHAT-SSE-005
  - ND-CHAT-SSE-HOLD-002
  - ND-CHAT-SSE-006
  - ND-CHAT-HOLD-OWNERSHIP-001
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: W1 pending; historical cap and raw-detail conflicts remain explicit
    HOLD records; no source-stage proof is complete.
  deletionResult: not_run
  status: hold
- itemNumber: 14
  path: agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md
  expectedSha256: 002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D
  gitTracking: untracked
  absorbedRequirementIds:
  - ND-CHAT-FAILSOFT-001
  - ND-CHAT-FAILSOFT-002
  - ND-CHAT-FAILSOFT-003
  - ND-CHAT-FAILSOFT-004
  - ND-CHAT-FAILSOFT-005
  - ND-CHAT-HOLD-OWNERSHIP-001
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: W2 must follow/reconcile W1 and still lacks current ownership, RED/GREEN,
    Gradle, Browser, and runtime-lineage proof.
  deletionResult: not_run
  status: hold
- itemNumber: 15
  path: __patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md
  expectedSha256: B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B
  gitTracking: unknown
  absorbedRequirementIds:
  - ND-DESKTOP-WRAPPER-001
  - ND-DESKTOP-WRAPPER-002
  - ND-DESKTOP-WRAPPER-003
  - ND-DESKTOP-WRAPPER-004
  - ND-DESKTOP-WRAPPER-005
  - ND-DESKTOP-WRAPPER-006
  - ND-DESKTOP-WRAPPER-007
  - ND-DESKTOP-WRAPPER-008
  - ND-DESKTOP-WRAPPER-009
  - ND-DESKTOP-WRAPPER-010
  - ND-DESKTOP-WRAPPER-011
  - ND-DESKTOP-WRAPPER-012
  - ND-DESKTOP-WRAPPER-013
  coverageDecision: incomplete
  strongGate: not_applicable
  eligibility: hold
  exclusionReason: incomplete-coverage
  holdReason: canonical directive hash has not been published; this wrapper has no
    declared directive ID; every mapped work unit, current collision/preimage proof,
    and final Desktop verification remains incomplete
  deletionResult: not_run
  status: hold
```

## Completion Contract

```yaml
completeOnlyWhen:
  - canonical directive is published with externally reported SHA-256
  - every included requirement has disposition and current evidence
  - each required work unit has independent RED/GREEN and verification evidence
  - retirement removes only exact unchanged eligible leaves after publication
  - protected and held artifacts remain untouched and categorically reported
  - final report contains path/hash, included/excluded/status counts, work-unit verification, retired/held counts, and desktopFinalProof
desktopFinalProof: evidence_needed
singleNextEvidenceNeeded: "Superseded by Current Canonical Refresh — 2026-08-07."
```

## Current Canonical Refresh — 2026-08-07

This section is the latest decision layer for this one-file program. It does
not discard the preceding inventory, requirement ledger, conflict decisions,
or work-unit detail. Where a statement below conflicts with an earlier
statement, this section wins. The earlier fifteen-item inventory remains the
historical baseline; the refresh inventory records the newly discovered or
rechecked leaves, two ACL-protected leaves, the canonical output itself, and
one excluded PatchDrop sidecar.

### Refresh Authority And Non-Mutation Boundary

```yaml
refreshId: awx-desktop-notebook-consolidated-source-20260807-r2
designDecision: update-one-existing-canonical-file
designApproval: user-approved-2026-08-07
canonicalExecutionRoot: 'C:\AbandonWare\demo-1\demo-1\src'
canonicalOutput: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
canonicalOutputPreimageSha256: A93E6857188320594A9A04C919AB5A8E7A028EC8A66BA69D5BBA7FC2765F6B79
attachmentPath: 'C:\Users\nninn\.codex\attachments\218b63fc-7a85-42bb-823e-03208995c86a\pasted-text-1.txt'
attachmentSha256: 4B626D3875023547D2030D48810A79139C7D91FB82DBC1398DCCAD3A14BFE516
attachmentBytes: 8243
inputAuthority: supporting_only
liveCheckoutAuthority: true
sourceMutationAuthorized: false
sourceLeaseAuthorized: false
retirementAuthorized: false
aclMutationAuthorized: false
supabaseMutationAuthorized: false
browserMutationAuthorized: false
computerMutationAuthorized: false
gitStageCommitPushAuthorized: false
originalDirectiveDeletionAuthorized: false
```

The approved action is an artifact-only consolidation. It authorizes this
Markdown update and its read-only validation. It does not authorize any Java,
resource, script, test, ACL, database, browser-state, Git-index, commit, push,
deployment, or original-directive mutation.

### Refresh DirectiveInventory

```yaml
schemaVersion: demo1.notebook-directive-inventory-refresh.v2
generatedUtc: '2026-08-10T00:37:48.6026961Z'
hashAlgorithm: SHA-256
readEncoding: UTF-8
refreshCandidateCount: 13
refreshReadableCount: 11
refreshAclHeldCount: 2
refreshCanonicalOutputCount: 1
refreshExternalInputCount: 12
historicalBaselineInputCount: 15
historicalOverlapCount: 4
refreshOnlyInputCount: 8
historicallyIncorporatedDistinctInputCount: 23
historicallyReadableAtIncorporationCount: 21
currentPresentDistinctInputCount: 14
currentReadableDistinctInputCount: 12
currentAclHeldDistinctInputCount: 2
currentMissingHistoricalInputCount: 9
historicalBaselinePresentNowCount: 6
historicalBaselineMissingNowCount: 9
historicalBaselinePathsMissingAtFinalValidation:
  - data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md
  - __reports__/desktop-risk-utility-source-directive-2026-08-04.md
  - docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json
  - docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md
  - docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md
  - agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md
  - agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md
  - agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md
  - __patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md
excludedSidecarCount: 1
retiredCount: 0
candidates:
  - item: R-I01
    path: data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    sha256: 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    bytes: 12605
    gitTracking: untracked
    directiveIds: [G-20260802-RAG-TAIL-WEB-01]
    relationToBaseline: inherited-overlap
    disposition: retained-hold
  - item: R-I02
    path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md
    sha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B
    bytes: 29636
    gitTracking: untracked
    directiveIds:
      - AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-20260805
      - AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2
    relationToBaseline: inherited-overlap
    disposition: retained-hold
  - item: R-I03
    path: __patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md
    sha256: 467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160
    bytes: 20411
    gitTracking: untracked
    directiveIds: [NEXT-BFF-9H-20260702]
    relationToBaseline: inherited-overlap
    disposition: retained-hold
  - item: R-I04
    path: __patch_drop__/notebook/http-rollback-local-smoke-desktop-directive.md
    sha256: 6CE0D0244671889184191AD7BE1BF55A8DFDC9C0779BD6D4CF08281B4ED89FD9
    bytes: 5320
    gitTracking: untracked
    relationToBaseline: refresh-only
    disposition: superseded-retained
    supersededBy: R-I05
  - item: R-I05
    path: __patch_drop__/notebook/http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md
    sha256: 7126188A0174101E766E8F87AB88E7311EEA0ECE56EC68FCA740D9D7E7A40499
    bytes: 11448
    gitTracking: untracked
    relationToBaseline: refresh-only
    disposition: authoritative-for-http-smoke-semantics
  - item: R-I06
    path: __patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md
    sha256: 87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424
    bytes: 17004
    gitTracking: untracked
    directiveIds: [MAIN-CHAT-NEXT-PORT-20260702]
    relationToBaseline: inherited-overlap
    disposition: retained-hold
  - item: R-I07
    path: __patch_drop__/notebook/security-guardrail-audit-desktop-directive.md
    sha256: CA15D56C5331D418BEE77D0611A68C6752574C244FE304FF467F2C881264FF2F
    bytes: 14004
    gitTracking: untracked
    relationToBaseline: refresh-only
    disposition: retained-prerequisite-hold
  - item: R-I08
    path: __patch_drop__/notebook/supabase-readonly-desktop-codex-directive.md
    sha256: D02061DA9F04055DB821A0363E1CBA5EE71EA815A3F5ECA951C5AE66EF2E71EF
    bytes: 12858
    gitTracking: untracked
    relationToBaseline: refresh-only
    disposition: retained-supporting-only
  - item: R-I09
    path: agent-prompts/awx_desktop_chat_restore_identity_postprocess_source_directive_20260806.md
    sha256: EAE8CDD040516D3266AB741485EDE45FE9CDFC8BA73B46E83B629BE2366070C1
    bytes: 70496
    gitTracking: untracked
    directiveIds: [AWX-CHAT-RESTORE-IDENTITY-P0-20260806]
    relationToBaseline: refresh-only
    disposition: retained-no-op-first-hold
  - item: R-I10
    path: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
    sha256AtRefreshStart: A93E6857188320594A9A04C919AB5A8E7A028EC8A66BA69D5BBA7FC2765F6B79
    bytesAtRefreshStart: 388261
    gitTracking: untracked
    relationToBaseline: canonical-output
    disposition: updated-in-place
  - item: R-I11
    path: __reports__/rag-postprocess-focused-probe-desktop-directive-2026-06-05.md
    bytesFromDirectoryMetadata: 17966
    sha256: access-denied
    accessibility: acl-denied
    relationToBaseline: refresh-only
    disposition: protected-hold
  - item: R-I12
    path: __reports__/notebook-auxiliary-prompt-boundary-contract-directive-2026-06-05.md
    bytesFromDirectoryMetadata: 4307
    sha256: access-denied
    accessibility: acl-denied
    relationToBaseline: refresh-only
    disposition: protected-hold
  - item: R-I13
    path: data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md
    sha256: 3D5E8B79B23CC1A713CE432806654193D5C6D770A47B52B561151ADA3435CB0F
    bytes: 15813
    gitTracking: untracked
    relationToBaseline: refresh-only
    classification: partially-applied
    disposition: retained-hold
    conflictDecision: eight-unit-projection-reference-only
excluded:
  - item: R-E01
    path: __patch_drop__/notebook/training-roi-signal-directive-notebook-v3.report.md
    sha256: 0BD0D9B9B6865BFE311136446F06740C6AD29224A2A1D60AC48C10250795F91B
    bytes: 3581
    reason: patchdrop-sidecar-not-an-executable-source-directive
nonLeafExclusions:
  - canary-v1-v2-sealed-material
  - ollama-model-intake-directory
  - backups-archives-generated-build-output
```

The four overlap items are R-I01, R-I02, R-I03, and R-I06. The historically
incorporated input count is therefore `15 + 8 = 23`; R-I10 is the canonical
output and is not counted as an input. Final filesystem validation found only
six of the fifteen historical baseline paths still present. Adding the eight
refresh-only inputs yields fourteen currently present distinct inputs: twelve
readable and two ACL-held. The nine missing paths remain content-level
provenance because their requirements were already absorbed into this file;
their absence is not classified as retirement or deletion by this refresh.
ACL-denied content is not reconstructed from lossy snippets, inferred from
filenames, or unlocked. R-I04 remains present for provenance but cannot
override R-I05.

```yaml
projectionDecision:
  stagedEightUnitPath: .superpowers/sdd/2026-08-07-desktop-notebook-consolidated-program-controller/task-3-exact-controller-manifest.json
  stagedEightUnitSha256: 644720FB562526F6FF5ABFFC476F33D9E7B5A0FFEDEB259D0B00ED5164290952
  stagedEightUnitRole: schema-reference-only
  stagedEightUnitSelectedForExecution: false
  authoritativeControllerWorkUnitCount: 14
```

The eight-unit staging report itself retained both publication and integration
at `HOLD`; it is a schema reference only and does not replace the published
fourteen-work-unit execution graph.

### Review Packet Register

```yaml
reviewPolicy: evidence-packets-not-majority-votes
packets:
  - name: Directive locator
    taskId: 019f558f-81f3-72e3-8bfe-d3ee7d9901b9
    status: final
    acceptedFinding: two June-05 candidate leaves exist but are ACL-denied
  - name: Test surface audit
    taskId: 019f558f-d716-78f2-8b8f-3026bf0a91a9
    status: final
    acceptedFinding: active focused ensemble, prompt-boundary, verifier, and chat tests exist
  - name: Final reviewer2
    taskId: 019f55a7-f177-7700-b824-df0ef3697c73
    status: interrupted-no-final
    acceptedFinding: none-promoted
  - name: Call path audit
    taskId: 019f558f-ace7-7181-82b2-4c15fd720bf7
    status: final
    acceptedFinding: logical stages and physical provider attempts must be reported separately
  - name: Independent reviewer
    taskId: 019f559c-c48e-7073-ad37-86343f19be6e
    status: final
    acceptedFinding: Notebook assertions are supporting evidence until reconciled with the live checkout
  - name: ACL directive recovery
    taskId: 019f55ab-f794-7561-9aed-ed9096fcc884
    status: final
    acceptedFinding: recovered fragments are lossy and cannot replace the protected originals
  - name: Verifier contract audit
    taskId: 019f55ac-20b7-7ed0-aef9-035d4a66cce2
    status: final
    acceptedFinding: final verifier is policy-gated zero-or-one, not unconditional exactly-once
  - name: Atomic bundle audit
    taskId: 019f55ac-4b8e-70d3-a091-56fd6042a5fe
    status: final
    acceptedFinding: old codex/postprocess-atomic worktree evidence is not current final proof
  - name: Final contract reviewer
    taskId: 019f55bb-e8f9-7a51-984f-9ae4f987a76a
    status: final
    acceptedFinding: a scoped comment-or-test correction may be ready, but review does not authorize source mutation
```

The interrupted packet contributes no verdict. No conclusion below is based
on reviewer count. Current command and source evidence outrank every packet.

### Live Source Reconciliation

```yaml
activeRootSourceSets:
  - main/java
  - main/resources
  - src/test/java
  - src/test/resources
activeAppSourceSets:
  - app/src/main/java_clean
  - app/src/main/resources
inactiveUnlessReproven:
  - project/src/main/java
  - app/src/main/java
  - demo-1
  - lms-core
  - backups
  - archives
preflightSnapshot:
  indexLock: false
  topLevelPatchDropPatchCount: 0
  activeSourceLeaseCount: 0
  conflictEntryCount: 0
  stagedEntryCount: 0
  trackedChangedEntryCount: 737
  untrackedEntryCount: 12623
  statusEntryCount: 13360
  worktreeCount: 30
  selectedSourceTargetPreimagesFrozen: false
  decision: artifact-only-apply-source-work-hold
liveCallContract:
  auxiliarySamplingDefault: zero-to-two-candidates
  auxiliarySamplingAlternativeSupportTriad: zero-to-three-candidates-when-enabled
  ensembleJudgeCalls: zero
  primaryFinalLogicalStage: one-on-main-non-short-path
  primaryFinalPhysicalAttempts: one-to-many-unless-strict-three-role-mode
  finalVerifierCalls: zero-to-one-policy-gated
  finalPostprocessLogicalStage: one
  finalPromptBoundary: PromptBuilder.build-PromptContext-or-current-equivalent
atomicWorktreeEvidence:
  worktree: codex/postprocess-atomic
  decision: reject-as-current-proof
  reasons:
    - head-mismatch
    - unrelated-dirty-state
```

The stable repository snapshot is safe for this artifact write only. Its large
dirty/untracked surface is not permission to take a broad source lease. Every
source unit below must freeze only its declared target preimages immediately
before its own three-way preflight.

### RequirementLedger Delta

All preceding requirement rows remain inherited unless a row below explicitly
supersedes them. This delta is complete for the attachment, refreshed
inventory, linked review packets, and live-call conflicts introduced in this
refresh.

| ID | Durable requirement or decision | Disposition | Current evidence |
|---|---|---|---|
| DR-001 | Desktop C-root and active sourceSets outrank Notebook and review claims. | verified | settings/sourceSet probe and repository instructions |
| DR-002 | Preserve the attached SUPPORT/FALSIFY concept as a design input. | verified | attachment hash fixed above |
| DR-003 | SUPPORT uses temperature 0.85/top-p 0.9; FALSIFY uses 0.0/0.4; compute evidence rate, source diversity, contradiction, and a 0.70/0.20/0.10 grounding score; no evidence scores zero; invalid IDs are ignored; gap below 0.05 is unresolved. | hold | no current behavioral RED/GREEN proves the whole formula |
| DR-004 | If implemented, both dossiers cross the canonical PromptBuilder/PromptContext boundary; the legacy judge remains rollback-only. | hold | boundary exists; dossier transport is not proven current |
| DR-005 | Do not force the attachment's fixed auxiliary-two/judge-zero/primary-one physical-call claim onto the checkout. | superseded | live default dual and optional triad plus retry topology |
| DR-006 | Report auxiliary, judge, logical primary, physical attempts, verifier, and postprocess separately. | verified | live call-path audit |
| DR-007 | Reject any unconditional exactly-once verifier assertion. | rejected | current verifier contract is policy-gated zero-to-one |
| DR-008 | Chat restore identity is no-op-first and targets only the Node contract plus `chat.js` unless its current RED proves a source gap. | hold | target preimages and current RED not frozen |
| DR-009 | The first HTTP rollback directive may not govern local URL selection. | superseded | R-I05 explicitly supersedes R-I04 |
| DR-010 | Explicit `BaseUrl` wins; inherited public URL is allowed only for assume-running; local boot defaults fail-closed to loopback HTTP and restores environment state. | hold | four static script RED/GREEN pairs not rerun in this refresh |
| DR-011 | Security guardrail recovery precedes any security Java edit and remains phase-scoped/fail-closed. | hold | no security source mutation authorized |
| DR-012 | Supabase is read-only, project-scoped supporting evidence by default and may block only when explicitly required or when Supabase source is selected. | blocked-supporting | connector is not connected and no project scope is proven |
| DR-013 | ACL-protected June-05 leaves remain held without reconstruction or ACL changes. | hold | access denied for both leaves |
| DR-014 | Do not delete any present original directive; retirement requires exact unchanged hashes plus all relevant work units green and Desktop final proof. Nine historical paths already absent remain missing provenance, not refresh retirement. | verified | this refresh deleted zero files |
| DR-015 | Keep the published fourteen-work-unit graph authoritative; the staged eight-unit projection remains schema-reference-only and unselected while its publication and integration verdicts are `HOLD`. | verified | staging manifest hash is pinned and the staging report retained both gates at `HOLD` |

```yaml
deltaRequirementCounts:
  total: 15
  verified: 5
  hold: 6
  superseded: 2
  rejected: 1
  blockedSupporting: 1
```

### Conflict Decisions

| Conflict | Winning contract | Losing or held claim | Reason |
|---|---|---|---|
| HTTP v1 versus v2 | R-I05 | R-I04 | v2 supplies explicit `BaseUrl`, assume-running, loopback, and environment restoration semantics |
| Attached dual design versus live optional triad | current live source | fixed two-only interpretation | source proves default dual and feature-gated triad |
| Judge/verifier count language | judge zero; verifier zero-to-one | unconditional verifier exactly-once | live policy gate |
| Logical final answer versus physical requests | report both | one-call shorthand | retries make physical attempts one-to-many outside strict mode |
| Chat restore identity versus broader chat parity | serialize restore contract first, then parity | simultaneous edits | overlapping `chat.js` target and independent rollback needs |
| Security changes versus UI/HTTP work | guardrail phase first only when a selected unit touches security | broad security sweep | smallest seam and fail-closed ordering |
| Supabase proof versus local work | local proof continues by default | missing Supabase as global blocker | external lane is not connected and is supporting-only |
| ACL fragments versus originals | protected hold | lossy recovery | fragments do not preserve exact bytes |
| Old atomic worktree versus current checkout | current C-root | old worktree result | head mismatch and unrelated dirt |

### Serialized Work Units

Only `WU-R0` belongs to this approved publication. The remaining units are an
execution plan, not edit authority. A future run selects exactly one source
unit, performs its immediate preimage/ownership/three-way gate, obtains RED,
patches the smallest seam, verifies GREEN, records rollback hashes, and releases
its narrow lease before another unit starts.

#### WU-R0 — Publish This Consolidated Artifact

```yaml
owner: desktop-artifact
requirements: [DR-001, DR-002, DR-006, DR-009, DR-012, DR-013, DR-014]
targetFiles:
  - agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
excludedFiles:
  - application-source
  - tests
  - original-directives
  - acl-protected-files
  - git-index
steps:
  - verify exact preimage hash and absent index lock
  - apply one in-place Markdown patch
  - decode the full file as strict UTF-8
  - verify section and identifier cardinality
  - run count-only high-risk secret-pattern scan
  - publish postimage SHA-256 outside the self-referential file
rollback: restore the exact preimage only if artifact validation fails
status: implemented-awaiting-post-write-validation
```

#### WU-E10 — Characterize Or Repair The Ensemble Contract

```yaml
owner: desktop-source-owner
requirements: [DR-003, DR-004, DR-005, DR-006, DR-007]
causalBoundary: auxiliary-sampling-to-primary-final-and-optional-verifier
targetFiles:
  - main/java/com/example/lms/service/ChatWorkflow.java
  - main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java
  - main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java
  - main/java/com/example/lms/prompt/PromptBuilder.java
  - main/java/com/example/lms/prompt/StandardPromptBuilder.java
focusedTests:
  - src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java
  - src/test/java/com/example/lms/ensemble/EnsembleFinalAnswerServiceTest.java
  - src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java
  - src/test/java/com/example/lms/service/ChatWorkflowStrictSingleAttemptHttpIntegrationTest.java
  - src/test/java/com/example/lms/prompt/PromptBuilderBoundaryTest.java
redGate: add or select one focused assertion that falsifies exactly one unmet DR-003 or DR-004 behavior; do not edit source if characterization already passes
greenCommands:
  - '.\gradlew.bat test --tests com.example.lms.ensemble.DiverseSamplingOrchestratorTest --tests com.example.lms.ensemble.EnsembleFinalAnswerServiceTest'
  - '.\gradlew.bat test --tests com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest --tests com.example.lms.service.ChatWorkflowStrictSingleAttemptHttpIntegrationTest --tests com.example.lms.prompt.PromptBuilderBoundaryTest'
rollback: restore only selected target postimages to their immediately recorded preimages
status: hold-preimage-and-red-needed
```

Do not add a second orchestrator, final-prompt concatenation path, or mandatory
verifier. A documentation/comment-only correction is acceptable only when the
focused tests prove source behavior is already correct.

#### WU-C20 — Chat Restore Identity, No-Op First

```yaml
owner: desktop-source-owner
requirements: [DR-008]
causalBoundary: restored-session-identity-and-stream-fixture
targetFiles:
  - scripts/chat_ui_stream_contract_tests.js
  - main/resources/static/js/chat.js
excludedFiles:
  - Java-controller
  - Java-service
  - database
  - Supabase
redGate: run the existing Node contract and capture a failing restored-session identity assertion before editing `chat.js`
greenCommands:
  - 'node scripts/chat_ui_stream_contract_tests.js'
rollback: restore the two exact recorded preimages only
status: hold-preimage-and-current-red-needed
```

If the Node contract already passes, record `no_patch_needed` and do not touch
`chat.js`. Complete this unit before WU-C21 because both may own that file.

#### WU-C21 — Main Chat UI/Controller Parity

```yaml
owner: desktop-source-owner
requirementsSource:
  - historical Immediate Chat Work Units W0-W3
  - R-I02
  - R-I06
causalBoundary: legacy-chat-ui-stream-cancel-session-and-redacted-events
primaryTargetFiles:
  - main/resources/static/js/chat.js
  - main/resources/templates/chat-ui.html
  - main/resources/static/css/chat-style.css
conditionalBackendTargets:
  - main/java/com/example/lms/api/ChatApiController.java
  - main/java/com/example/lms/dto/ChatStreamEvent.java
focusedTests:
  - scripts/chat_ui_stream_contract_tests.js
  - src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java
  - src/test/java/com/example/lms/api/ChatApiControllerStateSecurityTest.java
dependency: WU-C20-completed-or-no-patch-needed
redGate: one named UI or API contract must fail on the current checkout
greenCommands:
  - 'node scripts/chat_ui_stream_contract_tests.js'
  - '.\gradlew.bat test --tests com.example.lms.api.ChatApiControllerCancelTest --tests com.example.lms.api.ChatApiControllerStateSecurityTest'
browserProof: required-only-after-a-resource-or-controller-change
rollback: restore only files selected by the failing contract
status: hold-overlap-and-red-needed
```

Backend security files named by R-I06 stay excluded unless a focused backend
RED proves the UI contract cannot be fixed at the resource/controller seam.

#### WU-H30 — Local HTTP Smoke Rollback V2

```yaml
owner: desktop-script-owner
requirements: [DR-009, DR-010, DR-012]
causalBoundary: local-boot-base-url-resolution-and-environment-restoration
targetPairs:
  - [scripts/smoke_websoak_kpi_provider_disabled.ps1, scripts/smoke_websoak_kpi_provider_disabled_tests.ps1]
  - [scripts/smoke_chat_debug_events_readback.ps1, scripts/smoke_chat_debug_events_readback_tests.ps1]
  - [scripts/smoke_chat_debug_fx_sse.ps1, scripts/smoke_chat_debug_fx_sse_tests.ps1]
  - [scripts/trace_memory_recovery_synthetic_smoke.ps1, scripts/trace_memory_recovery_synthetic_smoke_tests.ps1]
excludedUnlessSeparatelyProven:
  - scripts/smoke_agent_mariadb_context.ps1
redGate: each selected pair proves explicit, assume-running, local-loopback, and restoration behavior before implementation
greenCommands:
  - 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_websoak_kpi_provider_disabled_tests.ps1'
  - 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_chat_debug_events_readback_tests.ps1'
  - 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_chat_debug_fx_sse_tests.ps1'
  - 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trace_memory_recovery_synthetic_smoke_tests.ps1'
rollback: restore each smoke/test pair independently
status: hold-v2-tests-not-run
```

#### WU-S40 — Guardrail And Security Phases

```yaml
owner: desktop-security-owner
requirements: [DR-011]
causalBoundary: source-edit-guard-before-auth-https-diagnostics-runtime-and-oauth
phase0Targets:
  - scripts/awx_mcp_toolbox_tests.ps1
  - scripts/test_awx_mcp_toolbox.py
conditionalJavaTargets:
  - main/java/com/example/lms/config/AppSecurityConfig.java
  - main/java/com/example/lms/config/CustomSecurityConfig.java
  - main/java/com/example/lms/security/AdminTokenGuardInterceptor.java
focusedTests:
  - src/test/java/com/example/lms/config/AppSecurityConfigContractTest.java
  - src/test/java/com/example/lms/config/CustomSecurityConfigContractTest.java
  - src/test/java/com/example/lms/config/ForceHttpsSecurityBoundaryTest.java
  - src/test/java/com/example/lms/boot/RuntimeConfigGuardTest.java
  - src/test/java/com/example/lms/api/KakaoOAuthControllerTest.java
ordering: phase0-guardrail-must-be-green-before-any-conditional-java-target
redGate: one phase-specific negative contract at a time
greenCommands:
  - 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/awx_mcp_toolbox_tests.ps1'
  - '.\gradlew.bat test --tests com.example.lms.config.AppSecurityConfigContractTest --tests com.example.lms.config.CustomSecurityConfigContractTest --tests com.example.lms.config.ForceHttpsSecurityBoundaryTest'
rollback: phase-local exact preimages; never broad checkout rollback
status: hold-separate-security-authorization-needed
```

Never alter credential files, secret names, `openssl`/`opnessl` spellings, or
security policy merely to make a test pass.

#### WU-S50 — Supabase Read-Only Default Routing

```yaml
owner: desktop-script-owner
requirements: [DR-012]
causalBoundary: local-next-action-routing-versus-opt-in-external-proof
conditionalTargets:
  - scripts/goal_next_auto_tests.ps1
  - scripts/test_awx_mcp_toolbox.py
  - scripts/test_source_health_scorecard.py
  - .mcp.json
externalState: not-connected
mutationAllowed: false
redGate: a local focused test must first prove missing Supabase connectivity is incorrectly promoted to the primary local blocker
acceptance:
  - default local work continues with Supabase marked supporting evidence needed
  - explicit require-Supabase mode may block on project-scoped proof
  - no migration, RLS, grant, schema, client, token, or project mutation
rollback: restore only a test-proven local routing change
status: blocked-external-but-nonblocking-for-local-work
```

#### WU-D60 — Inherited Deferred Directive Families

```yaml
owner: desktop-source-owner-per-selected-family
families:
  - rag-tail-counter-evidence-web
  - agent-code-evidence-gate
  - risk-utility-triad
  - dynamic-prompt-assembly
  - next-bff-discovery
requirementSource: preceding RequirementLedger and Deferred Work Units
selectionRule: choose-exactly-one-family-per-run
nextBffGate: prove-a-real-Next-package-and-config-before-any-Next-file-creation
sourceGate: stable-three-way-APPLY-plus-current-target-preimages-plus-one-RED
retirementGate: family-green-plus-Desktop-final-proof-plus-unchanged-input-hash
status: hold-inherited-not-executed
```

This aggregate is scheduling-only; it does not merge leases or rollback units.
The detailed target, RED/GREEN, verification, and rollback contracts earlier in
this document remain authoritative within the one selected family.

### External Evidence Lanes

```yaml
browser:
  role: supporting_only
  initialized: true
  openTabCountAtProbe: 0
  relevantProof: none
computer:
  role: supporting_only
  observedWindowCount: 33
  relevantDirectiveOrNotebookWindowCount: 0
  uiMutation: none
supabase:
  role: supporting_only
  connectionState: not_connected
  projectScopeProven: false
  mutation: none
externalLaneDecision: no-lane-proves-source-or-runtime-completion
```

### Refresh RetirementManifest

```yaml
deleteAuthorized: false
allRelevantWorkUnitsGreen: false
desktopFinalProof: evidence_needed
retiredByRefreshCount: 0
historicallyIncorporatedInputCount: 23
presentHeldInputCount: 14
missingHistoricalInputCount: 9
aclHeldCount: 2
supersededButRetainedCount: 1
excludedSidecarCount: 1
canonicalOutputCount: 1
protectedActions:
  - do-not-delete-originals
  - do-not-change-acl
  - do-not-stage-or-commit
  - do-not-mutate-external-systems
```

### Refresh Completion And Handoff Contract

```yaml
artifactScope:
  inventoryComplete: true
  reviewPacketsIntegrated: 9
  attachmentIntegrated: true
  conflictsResolvedOrHeld: true
  historicalLeafPresenceRechecked: true
  historicalPathsMissingBeforePublication: 9
  sourceEditsPerformed: 0
  originalDirectivesDeletedByRefresh: 0
  aclChangesPerformed: 0
  externalMutationsPerformed: 0
  gitStageCommitPushPerformed: 0
artifactPublicationProof: requires-postimage-hash-and-structural-validation-outside-this-file
desktopFinalProof: evidence_needed
sourceProgramStatus: held-not-executed
singleNextEvidenceNeeded: "Select exactly one source work unit, freeze its declared target preimages, and obtain a stable Desktop three-way APPLY plus its focused RED before editing."
```

The artifact goal may complete when WU-R0 passes post-write validation and its
postimage hash is reported. That completion does not make any source unit green,
does not authorize retirement, and does not claim Browser, Supabase, runtime,
provider, database, build, or deployment proof.

Controller commands use the program-scoped default state path
`data/agent-handoff/notebook/consolidated/awx-desktop-notebook-consolidated-source-20260806/program-state.json`.
For example, `invoke_consolidated_program.ps1 -Action Snapshot -WhatIf` and
`invoke_consolidated_program.ps1 -Action Next` resolve that same path unless an
explicit bounded in-repo `-StatePath` is supplied.

<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->
```json
{
  "schemaVersion": "awx.notebook.directive.controller-manifest.v1",
  "programId": "awx-desktop-notebook-consolidated-source-20260806",
  "sourceOwner": "desktop",
  "activeSourceSets": ["main/java", "main/resources", "app/src/main/java_clean", "app/src/main/resources"],
  "desktopFinalProof": "evidence_needed",
  "runtimeLineageVerdict": "HOLD",
  "deleteAuthorized": false,
  "legacyRequirementCoverage": [
    {"matchKind":"prefix","value":"ND-RAG-WEB-","workUnitId":"WU-R70","expectedCount":15},
    {"matchKind":"prefix","value":"ND-AGENT-GATE-","workUnitId":"WU-G80","expectedCount":13},
    {"matchKind":"prefix","value":"ND-RISK-UTILITY-","workUnitId":"WU-U90","expectedCount":15},
    {"matchKind":"exact","value":"SD-RISK-UTILITY-SHADOW-20260804","workUnitId":"WU-U90","expectedCount":1},
    {"matchKind":"prefix","value":"ND-DPA-","workUnitId":"WU-P100","expectedCount":162},
    {"matchKind":"prefix","value":"ND-NEXT-BFF-","workUnitId":"WU-N110","expectedCount":101},
    {"matchKind":"prefix","value":"ND-MAIN-CHAT-PARENT-","workUnitId":"WU-N110","expectedCount":94},
    {"matchKind":"prefix","value":"ND-CHAT-SESSION-","workUnitId":"WU-C20","expectedCount":7},
    {"matchKind":"exact","value":"ND-CHAT-HOLD-OWNERSHIP-001","workUnitId":"WU-C20","expectedCount":1},
    {"matchKind":"prefix","value":"ND-CHAT-SSE-","workUnitId":"WU-C21","expectedCount":8},
    {"matchKind":"prefix","value":"ND-CHAT-FAILSOFT-","workUnitId":"WU-C21","expectedCount":5},
    {"matchKind":"prefix","value":"ND-DESKTOP-WRAPPER-","workUnitId":"WU-FINAL","expectedCount":13}
  ],
  "directiveInventory": {
    "schemaVersion":"demo1.notebook-directive-inventory.v1",
    "canonicalExecutionRoot":"C:\\AbandonWare\\demo-1\\demo-1\\src",
    "candidateRoots":["data/agent-handoff/notebook","__patch_drop__/notebook","agent-prompts"],
    "candidates":[
      {"path":"__patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md","sha256":"467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160","bytes":20411,"gitTracking":"untracked","provenance":"notebook-patchdrop-intent","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"target-specific-red-directive"},
      {"path":"__patch_drop__/notebook/http-rollback-local-smoke-desktop-directive.md","sha256":"6CE0D0244671889184191AD7BE1BF55A8DFDC9C0779BD6D4CF08281B4ED89FD9","bytes":5320,"gitTracking":"untracked","provenance":"notebook-patchdrop-intent","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"target-specific-red-directive"},
      {"path":"__patch_drop__/notebook/http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md","sha256":"7126188A0174101E766E8F87AB88E7311EEA0ECE56EC68FCA740D9D7E7A40499","bytes":11448,"gitTracking":"untracked","provenance":"notebook-patchdrop-intent","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"target-specific-red-directive"},
      {"path":"__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md","sha256":"87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424","bytes":17004,"gitTracking":"untracked","provenance":"notebook-patchdrop-intent","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"target-specific-red-directive"},
      {"path":"__patch_drop__/notebook/security-guardrail-audit-desktop-directive.md","sha256":"CA15D56C5331D418BEE77D0611A68C6752574C244FE304FF467F2C881264FF2F","bytes":14004,"gitTracking":"untracked","provenance":"notebook-patchdrop-intent","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"target-specific-red-directive"},
      {"path":"__patch_drop__/notebook/supabase-readonly-desktop-codex-directive.md","sha256":"D02061DA9F04055DB821A0363E1CBA5EE71EA815A3F5ECA951C5AE66EF2E71EF","bytes":12858,"gitTracking":"untracked","provenance":"notebook-patchdrop-intent","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"target-specific-red-directive"},
      {"path":"agent-prompts/awx_desktop_chat_restore_identity_postprocess_source_directive_20260806.md","sha256":"EAE8CDD040516D3266AB741485EDE45FE9CDFC8BA73B46E83B629BE2366070C1","bytes":70496,"gitTracking":"untracked","provenance":"prompt","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"approved-canonical-input"},
      {"path":"data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json","sha256":"702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44","bytes":12605,"gitTracking":"untracked","provenance":"notebook","format":"json","directiveIds":[],"targetFiles":[],"inclusionReason":"standalone-notebook-directive"},
      {"path":"data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md","sha256":"BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B","bytes":29636,"gitTracking":"untracked","provenance":"notebook","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"standalone-notebook-directive"},
      {"path":"data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md","sha256":"3D5E8B79B23CC1A713CE432806654193D5C6D770A47B52B561151ADA3435CB0F","bytes":15813,"gitTracking":"untracked","provenance":"notebook","format":"markdown","directiveIds":[],"targetFiles":[],"inclusionReason":"standalone-notebook-directive"}
    ],
    "excluded":[
      {"path":"__patch_drop__/notebook/codex-computer-env-autostart-notebook-v3.manifest.json","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/codex-computer-env-autostart-notebook-v3.report.md","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/codex-computer-env-autostart-notebook-v3.sha256.txt","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/codex-computer-env-autostart-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/constitutional-scorecard-predecision-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/dynamic-rag-robust-evolution-notebook-v3.manifest.json","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/dynamic-rag-robust-evolution-notebook-v3.report.md","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/dynamic-rag-robust-evolution-notebook-v3.sha256.txt","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/dynamic-rag-robust-evolution-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/evaluation-integrity-dissent-notebook-v3.manifest.json","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/evaluation-integrity-dissent-notebook-v3.report.md","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/evaluation-integrity-dissent-notebook-v3.sha256.txt","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/evaluation-integrity-dissent-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/notebook-smb-global-prompt-hardening-notebook-v3.manifest.json","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/notebook-smb-global-prompt-hardening-notebook-v3.report.md","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/notebook-smb-global-prompt-hardening-notebook-v3.sha256.txt","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/notebook-smb-global-prompt-hardening-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/security-guardrail-audit-notebook.report.md","reason":"report-or-verification"},{"path":"__patch_drop__/notebook/spire-debug-operator-notebook-v3.manifest.json","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/spire-debug-operator-notebook-v3.report.md","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/spire-debug-operator-notebook-v3.sha256.txt","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/spire-debug-operator-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/tailscale-smb-load-shed-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/three-node-agents-source-lease-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/three-node-source-conflict-guard-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/three-node-source-lease-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/training-roi-disabled-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/training-roi-signal-directive-notebook-v3.manifest.json","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/training-roi-signal-directive-notebook-v3.report.md","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/training-roi-signal-directive-notebook-v3.sha256.txt","reason":"patchdrop-sidecar"},{"path":"__patch_drop__/notebook/training-roi-signal-directive-notebook-v3.verify.log","reason":"patchdrop-sidecar"},{"path":"agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md","reason":"reusable-prompt"},{"path":"data/agent-handoff/notebook/source-directive-canary-v1","reason":"sealed-canary"},{"path":"data/agent-handoff/notebook/source-directive-canary-v2","reason":"sealed-canary"}
    ]
  },
  "workUnits": [
    {"workUnitId":"WU-A0","status":"pending","dependencies":[],"required":true,"kind":"safety","targetFiles":["agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md",".agents/skills/demo1-consolidating-notebook-directives/scripts/invoke_consolidated_program.ps1",".agents/skills/demo1-consolidating-notebook-directives/scripts/consolidated_program_core.psm1",".agents/skills/demo1-consolidating-notebook-directives/tests/consolidated_program_core.tests.ps1",".agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program.tests.ps1",".agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_contract.tests.ps1",".agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_composition.tests.ps1",".agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_events.tests.ps1",".agents/skills/desktop-smb-ack/SKILL.md",".agents/skills/desktop-smb-ack/tests/desktop_smb_ack_contract.tests.ps1"],"requirementCoverage":[],"redCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program.tests.ps1"],"greenCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/desktop-smb-ack/tests/desktop_smb_ack_contract.tests.ps1","powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_contract.tests.ps1","powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/consolidated_program_core.tests.ps1","powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_composition.tests.ps1","powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_events.tests.ps1","powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/consolidated_program_retirement_core.tests.ps1","powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program.tests.ps1"],"rollback":"Revert only WU-A0 controller, tests, and canonical manifest block.","retirementCoverage":[],"causalBoundary":"Controller semantics and immutable canonical state contract only."},
    {"workUnitId":"WU-S40-P0","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"safety","targetFiles":["scripts/awx_mcp_toolbox_tests.ps1","scripts/test_awx_mcp_toolbox.py"],"requirementCoverage":[],"redCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File scripts/awx_mcp_toolbox_tests.ps1"],"greenCommands":["python scripts/test_awx_mcp_toolbox.py"],"rollback":"Revert only the security P0 test changes.","retirementCoverage":["__patch_drop__/notebook/security-guardrail-audit-desktop-directive.md"],"causalBoundary":"Prove the security-control failure before Java source mutation."},
    {"workUnitId":"WU-C20","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"source","targetFiles":["scripts/chat_ui_stream_contract_tests.js","main/resources/static/js/chat.js"],"requirementCoverage":["ND-CHAT-SESSION-*","ND-CHAT-HOLD-OWNERSHIP-001"],"redCommands":["node scripts/chat_ui_stream_contract_tests.js"],"greenCommands":["node scripts/chat_ui_stream_contract_tests.js"],"rollback":"Revert only session ownership and client-stream changes.","retirementCoverage":["__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md","agent-prompts/awx_desktop_chat_restore_identity_postprocess_source_directive_20260806.md","data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md"],"causalBoundary":"Browser session identity and client stream lifecycle only."},
    {"workUnitId":"WU-C21","status":"pending","dependencies":["WU-C20"],"required":true,"kind":"source","targetFiles":["scripts/chat_ui_stream_contract_tests.js","main/resources/static/js/chat.js","main/resources/templates/chat-ui.html","main/resources/static/css/chat-style.css","main/java/com/example/lms/api/ChatApiController.java","main/java/com/example/lms/dto/ChatStreamEvent.java"],"requirementCoverage":["ND-CHAT-SSE-*","ND-CHAT-FAILSOFT-*"],"redCommands":["node scripts/chat_ui_stream_contract_tests.js"],"greenCommands":["gradlew.bat test --tests *ChatApiController*"],"rollback":"Revert only SSE fail-soft and legacy chat visibility changes.","retirementCoverage":["__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md","data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md"],"causalBoundary":"Legacy chat SSE rendering, cancel, and fail-soft behavior only."},
    {"workUnitId":"WU-H30","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"verification","targetFiles":["scripts/smoke_websoak_kpi_provider_disabled.ps1","scripts/smoke_websoak_kpi_provider_disabled_tests.ps1","scripts/smoke_chat_debug_events_readback.ps1","scripts/smoke_chat_debug_events_readback_tests.ps1","scripts/smoke_chat_debug_fx_sse.ps1","scripts/smoke_chat_debug_fx_sse_tests.ps1","scripts/trace_memory_recovery_synthetic_smoke.ps1","scripts/trace_memory_recovery_synthetic_smoke_tests.ps1"],"requirementCoverage":[],"redCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_websoak_kpi_provider_disabled_tests.ps1"],"greenCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke_chat_debug_events_readback_tests.ps1"],"rollback":"Revert only the HTTP and debug smoke harness changes.","retirementCoverage":["__patch_drop__/notebook/http-rollback-local-smoke-desktop-directive.md","__patch_drop__/notebook/http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md"],"causalBoundary":"Local HTTP rollback and redacted debug-evidence harnesses only."},
    {"workUnitId":"WU-S50","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"safety","targetFiles":["scripts/goal_next_auto_tests.ps1","scripts/test_awx_mcp_toolbox.py","scripts/test_source_health_scorecard.py",".mcp.json"],"requirementCoverage":[],"redCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File scripts/goal_next_auto_tests.ps1"],"greenCommands":["python scripts/test_source_health_scorecard.py"],"rollback":"Revert only source-health and read-only Supabase boundary changes.","retirementCoverage":["__patch_drop__/notebook/http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md","__patch_drop__/notebook/supabase-readonly-desktop-codex-directive.md"],"causalBoundary":"Source-health convergence and Supabase read-only boundary only."},
    {"workUnitId":"WU-E10","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"source","targetFiles":["main/java/com/example/lms/service/ChatWorkflow.java","main/java/com/example/lms/service/DiverseSamplingOrchestrator.java","main/java/com/example/lms/service/EnsembleFinalAnswerService.java","main/java/com/example/lms/prompt/PromptBuilder.java","main/java/com/example/lms/prompt/StandardPromptBuilder.java"],"requirementCoverage":[],"redCommands":["gradlew.bat test --tests *Ensemble*"],"greenCommands":["gradlew.bat test --tests *PromptBuilder*"],"rollback":"Revert only ensemble and prompt-boundary changes.","retirementCoverage":[],"causalBoundary":"Ensemble arbitration and PromptBuilder boundary only."},
    {"workUnitId":"WU-P100","status":"pending","dependencies":["WU-E10"],"required":true,"kind":"source","targetFiles":["main/java/com/example/lms/prompt/assembly/PromptAssemblyProperties.java","main/java/com/example/lms/prompt/assembly/PromptAssemblyCatalog.java","main/java/com/example/lms/prompt/assembly/PromptAssemblyPlanner.java","main/java/com/example/lms/prompt/assembly/PromptAssemblyDecision.java","main/java/com/example/lms/prompt/assembly/PromptAssemblyTrace.java","main/resources/prompts/assembly/prompt-assembly.v1.yaml","main/resources/prompts/system/continuity-context.v1.md","main/resources/prompts/system/evidence-risk.v1.md"],"requirementCoverage":["ND-DPA-*"],"redCommands":["gradlew.bat test --tests *PromptAssembly*"],"greenCommands":["gradlew.bat test --tests *PromptAssembly*"],"rollback":"Revert only manifest-driven prompt assembly additions.","retirementCoverage":[],"causalBoundary":"Disabled-by-default prompt assembly and lineage only."},
    {"workUnitId":"WU-R70","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"source","targetFiles":["main/java/com/example/lms/prompt/PromptBuilder.java","main/java/com/abandonware/ai/agent/tool/impl/ops/CounterEvidenceRetrieveTool.java","main/resources/tool_manifest__kchat_gpt_pro.json","main/java/com/abandonware/ai/agent/tool/impl/ops/CausalProbeEvaluateTool.java","main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java","main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java","main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java"],"requirementCoverage":["ND-RAG-WEB-*"],"redCommands":["gradlew.bat test --tests *CounterEvidenceRetrieveToolTest"],"greenCommands":["gradlew.bat test --tests *EvidenceCoherenceVerifyToolTest"],"rollback":"Revert only RAG web and counter-evidence tool changes.","retirementCoverage":["data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json"],"causalBoundary":"Counter-evidence, causal probe, and web-search evidence coherence only."},
    {"workUnitId":"WU-G80","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"safety","targetFiles":["build.gradle.kts","scripts/agent_code_evidence_gate.py","scripts/test_agent_code_evidence_gate.py",".agents/skills/demo1-agent-code-evidence-gate/SKILL.md","scripts/source_health_validation_loop.py"],"requirementCoverage":["ND-AGENT-GATE-*"],"redCommands":["python scripts/test_agent_code_evidence_gate.py"],"greenCommands":["python scripts/test_agent_code_evidence_gate.py"],"rollback":"Revert only agent code-evidence gate changes.","retirementCoverage":[],"causalBoundary":"Agent-produced code evidence gating only."},
    {"workUnitId":"WU-U90","status":"pending","dependencies":["WU-A0"],"required":true,"kind":"source","targetFiles":["main/java/com/example/lms/resilience/RagFailureBlackboxService.java","src/test/java/com/example/lms/resilience/RagFailureBlackboxServiceTest.java"],"requirementCoverage":["ND-RISK-UTILITY-*","SD-RISK-UTILITY-SHADOW-20260804"],"redCommands":["gradlew.bat test --tests *RagFailureBlackboxServiceTest"],"greenCommands":["gradlew.bat test --tests *RagFailureBlackboxServiceTest"],"rollback":"Revert only risk-utility shadow-recording changes.","retirementCoverage":[],"causalBoundary":"Count-only risk and utility shadow evidence only."},
    {"workUnitId":"WU-S40-JAVA","status":"pending","dependencies":["WU-S40-P0"],"required":true,"kind":"source","targetFiles":["main/java/com/example/lms/config/AppSecurityConfig.java","main/java/com/example/lms/config/CustomSecurityConfig.java","main/java/com/example/lms/security/AdminTokenGuardInterceptor.java"],"requirementCoverage":[],"redCommands":["gradlew.bat test --tests *Security*"],"greenCommands":["gradlew.bat test --tests *RuntimeConfigGuardTest"],"rollback":"Revert only security configuration source changes.","retirementCoverage":["__patch_drop__/notebook/security-guardrail-audit-desktop-directive.md"],"causalBoundary":"Security configuration and admin-token guard only."},
    {"workUnitId":"WU-N110","status":"hold","dependencies":["WU-C21"],"required":true,"kind":"source","targetFiles":["frontend/package.json","frontend/next.config.mjs","frontend/src/lib/bff.js","frontend/src/lib/http-result.js","frontend/src/app/chat/page.js","frontend/src/app/api/chat/stream/route.js","frontend/src/app/api/chat/sync/route.js","frontend/src/app/api/chat/cancel/route.js","frontend/test/bff.test.mjs","main/resources/static/js/chat.js","main/resources/templates/chat-ui.html","main/resources/static/css/chat-style.css"],"requirementCoverage":["ND-NEXT-BFF-*","ND-MAIN-CHAT-PARENT-*"],"redCommands":["npm --prefix frontend test"],"greenCommands":["npm --prefix frontend test"],"rollback":"Revert only the Next BFF and parent chat port changes.","retirementCoverage":["__patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md","__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md","data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md"],"causalBoundary":"Next BFF transport and parent-chat parity only.","approvalGate":{"schemaVersion":"awx.notebook.directive.approval-gate.v1","requiredForBegin":true,"acceptAny":["noNewPublicApi","explicitApproval"]}},
    {"workUnitId":"WU-FINAL","status":"pending","dependencies":["WU-A0","WU-S40-P0","WU-C20","WU-C21","WU-H30","WU-S50","WU-E10","WU-P100","WU-R70","WU-G80","WU-U90","WU-S40-JAVA","WU-N110"],"required":true,"kind":"retirement","targetFiles":[],"requirementCoverage":["ND-DESKTOP-WRAPPER-*"],"redCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/scripts/invoke_consolidated_program.ps1 -Action Next"],"greenCommands":["powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program.tests.ps1","node scripts/chat_ui_stream_contract_tests.js","gradlew.bat projects","gradlew.bat compileJava","gradlew.bat :app:classes","gradlew.bat bootJar"],"rollback":"Preserve the last validated release and do not retire originals.","retirementCoverage":["all-listed-originals","data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md"],"causalBoundary":"Final Desktop proof publication only; Release and Retire are later explicit controller actions."}
  ],
  "retirement": {
    "schemaVersion":"demo1.notebook-directive-retirement.v1",
    "deleteAuthorized":false,
    "canonicalDirectivePath":"agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md",
    "canonicalDirectiveSha256Evidence":"external-final-output",
    "allRequiredWorkUnitsGreen":false,
    "desktopFinalProof":"evidence_needed",
    "status":"hold",
    "items":[
      {"path":"__patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md","sha256":"467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160","bytes":20411,"gitTracking":"untracked","coverage":["WU-N110"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"__patch_drop__/notebook/http-rollback-local-smoke-desktop-directive.md","sha256":"6CE0D0244671889184191AD7BE1BF55A8DFDC9C0779BD6D4CF08281B4ED89FD9","bytes":5320,"gitTracking":"untracked","coverage":["WU-H30"],"eligibility":"hold","holdReason":"superseded-by-http-rollback-v2","deletionResult":"not_run","status":"hold"},
      {"path":"__patch_drop__/notebook/http-rollback-local-smoke-supabase-boundary-desktop-directive-v2.md","sha256":"7126188A0174101E766E8F87AB88E7311EEA0ECE56EC68FCA740D9D7E7A40499","bytes":11448,"gitTracking":"untracked","coverage":["WU-H30","WU-S50"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md","sha256":"87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424","bytes":17004,"gitTracking":"untracked","coverage":["WU-C20","WU-C21","WU-N110"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"__patch_drop__/notebook/security-guardrail-audit-desktop-directive.md","sha256":"CA15D56C5331D418BEE77D0611A68C6752574C244FE304FF467F2C881264FF2F","bytes":14004,"gitTracking":"untracked","coverage":["WU-S40-P0","WU-S40-JAVA"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"__patch_drop__/notebook/supabase-readonly-desktop-codex-directive.md","sha256":"D02061DA9F04055DB821A0363E1CBA5EE71EA815A3F5ECA951C5AE66EF2E71EF","bytes":12858,"gitTracking":"untracked","coverage":["WU-S50"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"agent-prompts/awx_desktop_chat_restore_identity_postprocess_source_directive_20260806.md","sha256":"EAE8CDD040516D3266AB741485EDE45FE9CDFC8BA73B46E83B629BE2366070C1","bytes":70496,"gitTracking":"untracked","coverage":["WU-C20"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json","sha256":"702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44","bytes":12605,"gitTracking":"untracked","coverage":["WU-R70"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md","sha256":"BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B","bytes":29636,"gitTracking":"untracked","coverage":["WU-C20","WU-C21","WU-N110"],"eligibility":"hold","holdReason":"desktop-work-unit-proof-pending","deletionResult":"not_run","status":"hold"},
      {"path":"data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md","sha256":"3D5E8B79B23CC1A713CE432806654193D5C6D770A47B52B561151ADA3435CB0F","bytes":15813,"gitTracking":"untracked","coverage":["WU-FINAL"],"eligibility":"hold","holdReason":"full-program-proof-pending","deletionResult":"not_run","status":"hold"}
    ]
  }
}
```
<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->
