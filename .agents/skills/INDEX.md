# Typed Skill And Prompt Routing Index

`kind` + `canonicalId` is the route identity. Same-looking names in other namespaces are companions, not aliases.

```yaml
routes:

  - kind: 'skill'
    canonicalId: 'demo1-stepwise-ask-report'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Ask, Search, Stepwise Delivery'
      - '.agents/skills/demo1-stepwise-ask-report/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-stepwise-ask-report/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-stepwise-ask-report/agents/openai.yaml'
    trigger: >-
      Use before large/ambiguous/irreversible demo-1 work: search, report options
      and needed plugins, ask go/no-go, then take one step.

  - kind: 'skill'
    canonicalId: 'demo1-goal-complete-stop'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Stale Handoffs And Finished Goals'
      - '.agents/skills/demo1-goal-complete-stop/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-goal-complete-stop/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-goal-complete-stop/agents/openai.yaml'
    trigger: >-
      Use when a demo-1 goal is verified or the user says stop: confirm src/URL,
      summarize, treat stale handoffs as reference-only, and end without
      autonomous continuation.

  - kind: 'skill'
    canonicalId: 'demo1-triad-deliberation'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Triad Deliberation And Safe Integration'
      - '.agents/skills/demo1-triad-deliberation/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-triad-deliberation/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-triad-deliberation/agents/openai.yaml'
    trigger: >-
      Use for non-trivial demo-1 decisions needing affirmative/adversarial/judge
      cross-check, web fact repair, and verdict-first answers without saying mode.
  - kind: 'skill'
    canonicalId: 'demo1-api-spec-drift-guard'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Core Request Entry (Display / RAG / LLM)'
      - 'AGENTS.md#Prompt, Search, And Provider Hygiene'
      - '.agents/skills/demo1-api-spec-drift-guard/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-api-spec-drift-guard/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-api-spec-drift-guard/agents/openai.yaml'
    trigger: >-
      Use when stale docs/comments/skills disagree with live vendor APIs or
      Ollama inventory; refresh inventory and patch routing seams to current
      contracts.

  - kind: 'skill'
    canonicalId: 'demo1-core-request-router'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Core Request Entry (Display / RAG / LLM)'
      - '.agents/skills/demo1-core-request-router/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-core-request-router/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-core-request-router/agents/openai.yaml'
    trigger: >-
      Use at demo-1 request entry when Meta Display, core RAG, LLM/provider
      routing, or API wiring may change; classify one skill per phase. Multi-seam
      pasted briefs go through demo1-devin-source-orchestrator.

  - kind: 'skill'
    canonicalId: 'demo1-adaptive-rule-lab'
    legacyLocation: 'not-migrated:new-route-2026-09-14'
    legacyTextHash: '18a2f3b7b049cd947dbf5612cd84ab0b999b5cfa2837ab5fbc54e0f910f8d91c'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Skill And Prompt Routing'
      - '.agents/skills/demo1-adaptive-rule-lab/references/experiment-contract.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-adaptive-rule-lab/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-adaptive-rule-lab/agents/openai.yaml'
    trigger: >-
      Use when skills, rules or directives need semantic discovery or overlap
      review, or a reusable workflow needs bounded measured experimentation,
      error analysis, hypothesis revision and evidence-based rule calibration.

  - kind: 'skill'
    canonicalId: 'demo1-meta-display-browser-repair'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Meta Ray-Ban Display Tasks'
      - 'AGENTS.md#Evidence And Verification'
      - '.agents/skills/demo1-meta-display-browser-repair/references/evidence.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-meta-display-browser-repair/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-meta-display-browser-repair/agents/openai.yaml'
    trigger: >-
      Use when Meta Display needs real-browser functional QA or repair for
      input, submission, missing answers, stuck loading, layout, refresh,
      or reconnect failures. Reuse the existing verification stage owner.


  - kind: 'skill'
    canonicalId: 'demo1-meta-display-simple-caption'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Meta Ray-Ban Display Tasks'
      - '.agents/skills/demo1-meta-display-simple-caption/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-meta-display-simple-caption/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-meta-display-simple-caption/agents/openai.yaml'
    trigger: >-
      Use when Meta Ray-Ban Display / Fold6 caption output must stay minimal:
      conversation text and short hints only on a proven simple-page baseline.
      Not for DAT native UI, diagnostic-first overlays, or mixing Web App and
      custom relay stacks.
  - kind: 'skill'
    canonicalId: 'demo1-evidence-debugging'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Skill And Prompt Routing'
      - 'AGENTS.md#Evidence And Verification'
      - '.agents/skills/demo1-evidence-debugging/references/case-contract.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-evidence-debugging/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-evidence-debugging/agents/openai.yaml'
    trigger: >-
      Use when demo-1 debugging starts from logs or a reproducible symptom
      and involves competing causes, hidden activation conditions, duplicate
      actions, exceptions, timeout or cancellation recovery, or edge cases
      across source and runtime boundaries. Not for ordinary wording edits.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L76'
    legacyTextHash: '5e5b1751217f15d470f5cfeebaa681390359e92d971414235b679f32dc6e35c4'
    kind: 'standalone-prompt'
    canonicalId: 'agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md'
    pairedArtifact: '.agents/skills/demo1-codex-usage-triage/SKILL.md'
    trigger: >-
      Use for an explicit Desktop-only SMB decommission and Codex-usage
      optimization pass; never copy its task-specific /goal into global
      personal instructions.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L77'
    legacyTextHash: 'f64240b872b5880e614eb0ac34230cd339c0dbbff5930c3d78a1c7bd550ca0f9'
    kind: 'prompt-pack'
    canonicalId: 'demo1_p0_safe_patch_orchestrator'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Safe Patch Rules'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_p0_safe_patch_orchestrator/system.md'
    pairedArtifact: '.agents/skills/demo1-autonomous-patch-conductor/SKILL.md'
    trigger: >-
      Use for general adaptive Self-Ask Safe Patch work. When the user invokes
      @superpowers, keep that process subordinate to repo evidence, secret
      safety, active sourceSet proof, and verification.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L78'
    legacyTextHash: '7a09df2552617ba34225d22aef0751fb884deba625519399296b7d5994ece961'
    kind: 'skill'
    canonicalId: 'demo1-source-edit-three-way-preflight'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Skill And Prompt Routing'
    loadPolicy: 'mandatory-before-mutation'
    status: 'preserved'
    source: '.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml'
    trigger: >-
      Use before an explicitly requested application-source mutation. Freeze
      one redacted EvidenceSnapshot, run exactly the three root-defined queries,
      and enter the source-owner guard only after stable APPLY; do not trigger
      for read-only, Markdown-only, or test-only work.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L79'
    legacyTextHash: '0fa745c1786cd553e13d602284e48179ee65267e89e3ea0959932889523d094d'
    kind: 'skill'
    canonicalId: 'demo1-macsrc-smb-direct-patch'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
    loadPolicy: 'mandatory-before-mutation'
    status: 'preserved'
    source: '.agents/skills/demo1-macsrc-smb-direct-patch/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-macsrc-smb-direct-patch/agents/openai.yaml'
    trigger: >-
      Use the unchanged historical guard for explicitly authorized Notebook
      direct work on canonical Y:\, when OneDrive was incorrectly selected as
      the source-write destination, or when Git dubious ownership requires the
      checksum and lease fallback without restricting external reads or tools.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L80'
    legacyTextHash: '2e66c6172a27cd8d516c3a9c753d3e8fc58c6785d5daafa95be81abe8644d47d'
    kind: 'prompt-pack'
    canonicalId: 'demo1_dual_codex_smb_safe_patch'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_dual_codex_smb_safe_patch/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_dual_codex_smb_safe_patch'
    trigger: >-
      Use for Desktop canonical-root ownership plus a Mac mini patch producer
      coordinated over SMB and PatchDrop.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L81'
    legacyTextHash: 'cc43e73388c490fb7158665736a4ee8a7645135ecfe16098e9ca09ada8dfbec9'
    kind: 'prompt-pack'
    canonicalId: 'demo1_three_node_smb_codex'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_three_node_smb_codex'
    trigger: >-
      Use for Desktop, Mac mini, and Notebook coordination where Notebook or
      NAS paths, local worktrees, source-edit leases, and PatchDrop-only
      producer bundles must remain separate.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L82'
    legacyTextHash: '89f3907b29ea9572706be9d17d74993f980d5c3ac3d4d50ace7387266be24fa4'
    kind: 'prompt-pack'
    canonicalId: 'demo1_notebook_desktop_goal_handoff'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md'
    pairedArtifact: 'scripts/verify_ydrive_backing_identity.ps1'
    trigger: >-
      Use for recurring Notebook read-only EvidenceSnapshot and three-role goal
      evaluation that becomes a Desktop-owned GoalContract and SourceDirective.
      Verify Y-drive backing identity before authority decisions and expose only
      canonical workspace, verification boolean, and reason.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L83'
    legacyTextHash: '4f5471f1200d380b28bd9f44db1e6401ff03767862cb82fae1253a53386d14a2'
    kind: 'prompt-pack'
    canonicalId: 'demo1_mcp_control_tower'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_mcp_control_tower/system.md'
    pairedArtifact: '.agents/skills/demo1-mcp-control-tower/SKILL.md'
    trigger: >-
      Use when agents need the MCP-style source_scan, patch_plan, patch_render,
      archive_search, archive_restore, boot_verify, build_error_mine, and
      run_pipeline contracts with fixed schemas and Desktop, Mac mini, and
      Notebook role routing. This underscore ID is the prompt pack, not the
      hyphenated skill.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L84'
    legacyTextHash: '6ab9f4896ee8a433d46f81e358af8b25e9c3099d37482c5b0b2e92c718f5dc03'
    kind: 'prompt-pack'
    canonicalId: 'demo1_claude_peers_web_probe_agent_upgrade_5h'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_claude_peers_web_probe_agent_upgrade_5h/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_claude_peers_web_probe_agent_upgrade_5h'
    trigger: >-
      Use when an attached claude-peers-suite-style bundle must be adapted into
      demo-1 as a web-probe-first Peer Evidence Bus, Control Tower and
      PatchDrop upgrade, plus read-only Supabase, Browser, and Computer evidence
      directives without installing external launchers in the canonical root.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L85'
    legacyTextHash: 'c409f3d3fa9760feda360b67e8d0dfa71f046be112d22f1565525e024a491edf'
    kind: 'script/tool'
    canonicalId: 'scripts/awx_mcp_stdio_server.py'
    contractClass: 'route-only'
    contractRefs: []
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'scripts/awx_mcp_stdio_server.py'
    pairedArtifact: 'main/resources/mcp/awx-control-tower-tools.json'
    trigger: >-
      Use when a client needs line-delimited MCP-style stdio JSON-RPC for
      tools/list, tools/call, resources/list, and prompts/list over the
      control-tower manifest.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L86'
    legacyTextHash: '60f88a5d7abd7ed970c7c1d60a4f2a166e28d3450310697b2a4221dd7570b12c'
    kind: 'script/tool'
    canonicalId: 'main/resources/mcp/awx-control-tower-mcp-client.sample.json'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'main/resources/mcp/awx-control-tower-mcp-client.sample.json'
    pairedArtifact: 'scripts/awx_mcp_stdio_server.py'
    trigger: >-
      Use as the secret-free MCP client configuration sample. Mac mini and
      Notebook producers must set cwd to a producer-local worktree or clone,
      never the Desktop canonical root.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L87'
    legacyTextHash: 'a88b17b9f5ce5462a1181716cc14c9230906c18480df58af7fee080d8848c263'
    kind: 'script/tool'
    canonicalId: 'desktop_dispatch_packet'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'main/resources/mcp/awx-control-tower-tools.json#desktop_dispatch_packet'
    pairedArtifact: 'scripts/awx_mcp_toolbox.py'
    trigger: >-
      Use desktop_dispatch_packet or its desktop.dispatch_packet alias from
      Desktop when assigning both Mac mini and Notebook work. It emits both
      producer command packets plus Desktop external_evidence_intake and
      external_evidence_audit commands; when handing off through PatchDrop, set
      write_dispatch=true and keep dispatch_dir under __patch_drop__/dispatch/.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L88'
    legacyTextHash: '0d91daaf12f542aaa42117e7e1e846f23a9f5fb4dd68049b9f6d04da45556a61'
    kind: 'script/tool'
    canonicalId: 'producer_command_plan'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'main/resources/mcp/awx-control-tower-tools.json#producer_command_plan'
    pairedArtifact: 'scripts/awx_mcp_toolbox.py'
    trigger: >-
      Use producer_command_plan or its producer.command_plan alias from Desktop
      before sending Mac mini or Notebook work to render producer-local smoke
      and PatchDrop handoff commands, desktopEvidencePath, and
      environment-name-only hints without exposing secret values.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L89'
    legacyTextHash: '0a0edb15e687bfabc98a90e9488076472a61b8574b429ad1bcf5ad7d42d5162a'
    kind: 'script/tool'
    canonicalId: 'scripts/awx_mcp_node_smoke.py'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'scripts/awx_mcp_node_smoke.py'
    pairedArtifact: 'main/resources/mcp/awx-control-tower-tools.json'
    trigger: >-
      Run from producer-local worktrees with canonical-root and macmini or
      notebook node-role arguments before submitting PatchDrop evidence.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L90'
    legacyTextHash: 'b92205b72a5654205ae16264d7f93a368dc2ffd1c4eb56e315767f23a4b5e6e3'
    kind: 'script/tool'
    canonicalId: 'scripts/awx_mcp_producer_handoff.py'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'scripts/awx_mcp_producer_handoff.py'
    pairedArtifact: '__patch_drop__/producer_bundle.py'
    trigger: >-
      Use when a producer-local edit is ready to become a PatchDrop v3 bundle,
      supplying producer source root, Desktop canonical root, PatchDrop root,
      producer script, node role, topic, and explicit pathspec.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L91'
    legacyTextHash: '84cfb4590c7474da5b696ad33de8a93ef5c8da1a5e27af6a10e12c3d267b8210'
    kind: 'script/tool'
    canonicalId: 'external_evidence_audit'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Evidence And Verification'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'main/resources/mcp/awx-control-tower-tools.json#external_evidence_audit'
    pairedArtifact: 'scripts/awx_mcp_toolbox.py'
    trigger: >-
      Run from Desktop after copied Mac mini or Notebook smoke JSON is placed
      under data/agent-handoff/mcp-control-tower; external-host proof is not
      complete until this audit passes.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L92'
    legacyTextHash: 'e886446701fa499d58ed7d585a80b109ffb761fae9ab6645f8216861f55f3ae1'
    kind: 'script/tool'
    canonicalId: 'scripts/awx_mcp_completion_audit.py'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'scripts/awx_mcp_completion_audit.py'
    pairedArtifact: 'main/resources/mcp/awx-control-tower-tools.json'
    trigger: >-
      Run on Desktop to audit the local MCP tool, resource, and prompt contract
      without treating the result as external-host smoke proof.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L93'
    legacyTextHash: '294e52b6497265213427ab3554343e13b55277dc1fa5db6d3d3de8a6eb3dddc5'
    kind: 'skill'
    canonicalId: 'patchdrop-safe-patch-orchestrator'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Skill And Prompt Routing'
      - 'AGENTS.md#PatchDrop Bundle Rules'
    loadPolicy: 'mandatory-before-mutation'
    status: 'preserved'
    source: '.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md'
    pairedArtifact: '__patch_drop__/README.md'
    trigger: >-
      Use when deciding producer and consumer roles, enforcing one active
      cumulative PatchDrop v3 bundle, or applying Desktop and Mac mini collision
      gates before source edits.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L94'
    legacyTextHash: 'a777c7a1c612ef9ed0bf6ffe0d12a656649f58b760c63d5fbda4e454412aad72'
    kind: 'prompt-pack'
    canonicalId: 'demo1_graphrag_kg_macmini_patchdrop'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_graphrag_kg_macmini_patchdrop/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_graphrag_kg_macmini_patchdrop'
    trigger: >-
      Use for GraphRAG and KG runtime-hardening passes where Mac mini
      investigates on agent/macmini/<topic> and submits only patch, diff, and
      log evidence to PatchDrop for Desktop final application.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L95'
    legacyTextHash: 'fa71a579c24bbd1f6672bd9803838f9a2078da82ac8d587345d222a3c9fa88fa'
    kind: 'prompt-pack'
    canonicalId: 'demo1_macmini_subserver_integration'
    contractClass: 'route-only'
    contractRefs: []
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_macmini_subserver_integration/system.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_macmini_subserver_integration'
    trigger: >-
      Use for Mac mini subserver or helper-node integration work.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L96'
    legacyTextHash: '1e3480b04c0b45a249c757a1e1dc74d4a2fc0e35fcbaf92ead0f382e684d3463'
    kind: 'skill'
    canonicalId: 'demo1-mcp-control-tower'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/demo1-mcp-control-tower/SKILL.md'
    pairedArtifact: 'agent-prompts/agents/demo1_mcp_control_tower/system.md'
    trigger: >-
      Use when coordinating Desktop, Mac mini, and Notebook agents through the
      repo-local MCP-style toolbox while retaining Desktop final ownership and
      PatchDrop-only producer handoff. This hyphenated ID is the skill, not the
      underscore prompt pack.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L97'
    legacyTextHash: '3cbac4183752275df30022abcdff78f27109a7fbd3727d3bafeab845355b761a'
    kind: 'skill'
    canonicalId: 'archive.search'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/archive-search/SKILL.md'
    pairedArtifact: 'scripts/awx_mcp_toolbox.ps1'
    trigger: >-
      Use for a single-tool control-tower archive search through the toolbox;
      keep output redacted and path-only where required.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L97'
    legacyTextHash: '3cbac4183752275df30022abcdff78f27109a7fbd3727d3bafeab845355b761a'
    kind: 'skill'
    canonicalId: 'archive.restore'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Redaction'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/archive-restore/SKILL.md'
    pairedArtifact: 'scripts/awx_mcp_toolbox.ps1'
    trigger: >-
      Use for a reviewed single-tool archive restore through the toolbox with
      explicit mode, glob, target directory, audit log, and checksum evidence.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L97'
    legacyTextHash: '3cbac4183752275df30022abcdff78f27109a7fbd3727d3bafeab845355b761a'
    kind: 'skill'
    canonicalId: 'verify_boot'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/verify-boot/SKILL.md'
    pairedArtifact: 'scripts/awx_mcp_toolbox.ps1'
    trigger: >-
      Use for a single-tool role-local boot-verification plan, retaining
      Desktop canonical-root final proof and redacted audit output.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L97'
    legacyTextHash: '3cbac4183752275df30022abcdff78f27109a7fbd3727d3bafeab845355b761a'
    kind: 'skill'
    canonicalId: 'build_error_miner'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Redaction'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/build-error-miner/SKILL.md'
    pairedArtifact: 'scripts/awx_mcp_toolbox.ps1'
    trigger: >-
      Use for a single-tool classification of build or boot logs into stable
      failure classes with redacted output.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L97'
    legacyTextHash: '3cbac4183752275df30022abcdff78f27109a7fbd3727d3bafeab845355b761a'
    kind: 'skill'
    canonicalId: 'run_pipeline'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Redaction'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/run-pipeline/SKILL.md'
    pairedArtifact: 'scripts/awx_mcp_toolbox.ps1'
    trigger: >-
      Use for a single-tool run_pipeline probe and related verify_boot or
      build-error-miner command availability while keeping audit output
      redacted.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L98'
    legacyTextHash: '0e66878c678f704a4dbcc4c50414cb90d66caebeaad15da2d5f1778a8545ea4d'
    kind: 'prompt-pack'
    canonicalId: 'demo1_dynamic_rag_autonomous_patch_directive'
    contractClass: 'route-only'
    contractRefs: []
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_dynamic_rag_autonomous_patch_directive/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_dynamic_rag_autonomous_patch_directive'
    trigger: >-
      Use for Plan DSL, MLA and SSE, failure-pattern, Codex or OpenCode agent
      feedback, LangGraph auxiliary orchestration, MoE, and Hybrid LLM Gateway
      upgrade work.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L99'
    legacyTextHash: '1639720597399604d9cc23c834152923b9a5ae4e07d92aafbefa5626f8279e28'
    kind: 'prompt-pack'
    canonicalId: 'demo1_orchestration_feature_expansion_plan'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Safe Patch Rules'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_orchestration_feature_expansion_plan/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_orchestration_feature_expansion_plan'
    trigger: >-
      Use before adding new Plan DSL execution modes such as Brave, Zero100,
      RuleBreak, or safe_autorun; define owner boundaries, feature flags, mode
      priority, rollback paths, and trace keys first.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L100'
    legacyTextHash: '421b9453cd0234a239c0ee5c7ec755f393fe53fb3d94d97e2ad115c7b7c9e320'
    kind: 'prompt-pack'
    canonicalId: 'demo1_desktop_patchdrop_janitor'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#PatchDrop Bundle Rules'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_desktop_patchdrop_janitor/system_ko.md'
    pairedArtifact: '__patch_drop__/README.md'
    trigger: >-
      Use when Desktop must inventory and apply pending PatchDrop bundles,
      isolate orphan metadata, reject broken bundles, or clean up after a Mac
      mini session through janitor_inventory.ps1,
      janitor_promote_producer_pending.ps1, janitor_isolate_orphan.ps1, and
      janitor_apply_one.ps1.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L101'
    legacyTextHash: '385f1d680ac7440a2bdae02504a3e96f07c3a03d414bb484a8d5fc02999ff3a0'
    kind: 'prompt-pack'
    canonicalId: 'demo1_desktop_referee_intent_packet'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_desktop_referee_intent_packet/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_desktop_referee_intent_packet'
    trigger: >-
      Use when Mac mini output must remain a non-applyable intent packet while
      Desktop builds the queue matrix, priority index, runtime-verifier gate,
      and APPLY, HOLD, or REJECT judgment.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L102'
    legacyTextHash: 'fe9136790437529f5489a3d4080bc7866904d17d61acee7b929fe91d35d3d6c0'
    kind: 'script/tool'
    canonicalId: '__patch_drop__/producer_bundle.ps1'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '__patch_drop__/producer_bundle.ps1'
    pairedArtifact: '.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md'
    trigger: >-
      Use from a producer-local Windows worktree or clone to submit only the
      PatchDrop v3 patch, report, verification log, SHA sidecar, manifest, and
      pending notice for explicit pathspecs.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L102'
    legacyTextHash: 'fe9136790437529f5489a3d4080bc7866904d17d61acee7b929fe91d35d3d6c0'
    kind: 'script/tool'
    canonicalId: '__patch_drop__/producer_bundle.py'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '__patch_drop__/producer_bundle.py'
    pairedArtifact: '.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md'
    trigger: >-
      Use from a producer-local worktree or clone when the Python producer is
      needed to submit the same bounded PatchDrop v3 artifacts without writing
      Desktop canonical source.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L103'
    legacyTextHash: '855485d98c5d5ff6a53f59bd0c43340168469ed2c4bc910540ddb9a833ea3e3a'
    kind: 'script/tool'
    canonicalId: '__patch_drop__/three_node_patchdrop_smoke.ps1'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#PatchDrop Bundle Rules'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '__patch_drop__/three_node_patchdrop_smoke.ps1'
    pairedArtifact: '__patch_drop__/README.md'
    trigger: >-
      Run on Desktop for temp-only proof that Mac mini and Notebook local
      bundles can be created, inventoried, promoted one at a time, and consumed
      through the desktop-consumer gate without mutating the real queue.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L104'
    legacyTextHash: '8e0f0b2598322edcadb343a909b44ea4a412236a69c998f611a00003f361d935'
    kind: 'skill'
    canonicalId: 'macmini-safe-patch-assistant'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Desktop / Mac Mini / Notebook Workspaces'
      - 'AGENTS.md#PatchDrop Bundle Rules'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/macmini-safe-patch-assistant/SKILL.md'
    pairedArtifact: '__patch_drop__/producer_bundle.py'
    trigger: >-
      Use when Mac mini must produce exactly one cumulative PatchDrop v3
      handoff with no direct shared-source edits, temp-copy apply proof, SHA-256
      manifest, secret scan, dry run, focused Gradle proof, and Desktop consumer
      gates.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L105'
    legacyTextHash: '6e91010353edf97dd0cd951104b8a4f5200b261279536e8a7085c11282bf32de'
    kind: 'prompt-pack'
    canonicalId: 'demo1_orch_patch_scanner'
    contractClass: 'route-only'
    contractRefs: []
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_orch_patch_scanner'
    trigger: >-
      Use when Desktop must systematically find and score active-source patch
      candidates for silent failures, missing TraceStore keys, incomplete
      fail-soft ladders, or AOP proceed double-invocation risks.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L106'
    legacyTextHash: 'a49d3933ce0b4c71a5df65ee7e77c0962af9f3e4d9f8138e81eaf853a74ddf5c'
    kind: 'prompt-pack'
    canonicalId: 'demo1_orch_debug_scan'
    contractClass: 'route-only'
    contractRefs: []
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_orch_debug_scan/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_orch_debug_scan'
    trigger: >-
      Use after a runtime failure such as bootRun error, test failure,
      starvation loop, or empty SSE to classify the failing layer from Spring
      context through evidence output and map it to the responsible candidate.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L107'
    legacyTextHash: '6ef3f50cb224ba00cc955f09ab42021c1aefd5aff378abdf35b6061635143ae6'
    kind: 'prompt-pack'
    canonicalId: 'demo1_orch_directive_generator'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#PatchDrop Bundle Rules'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_orch_directive_generator/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_orch_directive_generator'
    trigger: >-
      Use to generate a ready-to-send Mac mini directive from a scored candidate
      with the full patch contract, RED and GREEN assertions, exact Gradle
      commands, PatchDrop checklist, and embedded P0, P1, or P2 priority.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L108'
    legacyTextHash: '501f41014e8584097ef6604af25f55020b5973548c262ab2db0690c5575df7e8'
    kind: 'prompt-pack'
    canonicalId: 'demo1_orch_kpi_dashboard'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_orch_kpi_dashboard/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_orch_kpi_dashboard'
    trigger: >-
      Use before and after a patch to measure static and dynamic KPIs and
      produce a quantitative before-and-after delta table.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L109'
    legacyTextHash: '93e1c6e9f6afbd64d1166c05818a08faffdff3cd7d10381bc53daffec6721fa2'
    kind: 'skill'
    canonicalId: 'quantitative-metric-normalizer'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/quantitative-metric-normalizer/SKILL.md'
    pairedArtifact: 'agent-prompts/agents/demo1_quant_metric_normalization_antigravity/system_ko.md'
    trigger: >-
      Use when an attached Dynamic RAG design needs source-backed score
      normalization, broken subsystem-combination analysis, or a read-only
      Antigravity audit prompt before patching.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L109'
    legacyTextHash: '93e1c6e9f6afbd64d1166c05818a08faffdff3cd7d10381bc53daffec6721fa2'
    kind: 'prompt-pack'
    canonicalId: 'demo1_quant_metric_normalization_antigravity'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Safe Patch Rules'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_quant_metric_normalization_antigravity/system_ko.md'
    pairedArtifact: '.agents/skills/quantitative-metric-normalizer/SKILL.md'
    trigger: >-
      Use with quantitative-metric-normalizer when an attached Dynamic RAG
      design requires an Antigravity-ready, source-backed, read-only
      normalization audit before patching.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L110'
    legacyTextHash: '26aec2348c5df0cf88fec11ea53407a1473edc14c5fb8db67e3c45c3a95c55f4'
    kind: 'skill'
    canonicalId: 'demo1-ablation-harmony-tracker'
    contractClass: 'decision-changing'
    contractRefs:
      - '.agents/skills/demo1-ablation-harmony-tracker/SKILL.md#Patch Gate'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/demo1-ablation-harmony-tracker/SKILL.md'
    pairedArtifact: 'agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md'
    trigger: >-
      Use immediately after quantitative normalization to classify harmony-break
      pairs, resolve DA-01 through DA-08, and produce a TraceStore-key-anchored
      directive. Preserve boosterMode.active, retrievalOrder.lastSetBy,
      extremeZ.cancelShieldWrapped, extremeZ.timeBudgetConsumedMs,
      hypernova.cvarPhi, cihRag.breadcrumb.queryRedacted,
      moe.evolverPlateRegistered, and cfvm.boltzmannTemp. Always use before
      changing multi-booster activation, ExtremeZ cancellation, HYPERNOVA
      amplification, retrieval-order authority, MLA breadcrumb redaction, MoE
      evolver registration, or CFVM temperature.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L110'
    legacyTextHash: '26aec2348c5df0cf88fec11ea53407a1473edc14c5fb8db67e3c45c3a95c55f4'
    kind: 'prompt-pack'
    canonicalId: 'demo1_ablation_harmony_patch_directive'
    contractClass: 'decision-changing'
    contractRefs:
      - 'agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md#Non-Negotiables'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md'
    pairedArtifact: '.agents/skills/demo1-ablation-harmony-tracker/SKILL.md'
    trigger: >-
      Use when the ablation-harmony result must become an executable nine-hour
      Desktop Safe Patch prompt retaining the required TraceStore verification
      keys and subsystem gates.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L111'
    legacyTextHash: '3dbee49f4714ce24f1906123840a7baabfa7c39a10e830ab5965d9cb37a07a3a'
    kind: 'skill'
    canonicalId: 'demo1-harmony-contamination-scanner'
    contractClass: 'decision-changing'
    contractRefs:
      - '.agents/skills/demo1-harmony-contamination-scanner/SKILL.md#Verification'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: '.agents/skills/demo1-harmony-contamination-scanner/SKILL.md'
    pairedArtifact: 'agent-prompts/demo1_harmony_9h_autonomous_patch.md'
    trigger: >-
      Use to recompute source-backed HB-01 through HB-12 harmony scores, silent
      catch ratio, duplicate FQCNs, cross-subsystem files, TraceStore coverage,
      and count-only secret scans.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L112'
    legacyTextHash: 'd58b328f468064c80e2b79b3583532d29a119699256654a91780f95ed73f8100'
    kind: 'skill'
    canonicalId: 'demo1-cross-subsystem-guard'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Skill And Prompt Routing'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'mandatory-before-mutation'
    status: 'preserved'
    source: '.agents/skills/demo1-cross-subsystem-guard/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-ablation-harmony-tracker/SKILL.md'
    trigger: >-
      Use before completion claims when a patch touches two or more S01-S08
      subsystems, shared booster arbitration, CFVM, MoE, HYPERNOVA, or ZCA seams,
      PromptBuilder boundaries, or auto-configuration wiring.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L113'
    legacyTextHash: 'd9d89dfb9e885ab17462f9eaff285c9c730a1a806264c15a00bf4bc7cb501c09'
    kind: 'standalone-prompt'
    canonicalId: 'agent-prompts/demo1_harmony_9h_autonomous_patch.md'
    contractClass: 'decision-changing'
    contractRefs:
      - 'agent-prompts/demo1_harmony_9h_autonomous_patch.md#Non-Negotiables'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/demo1_harmony_9h_autonomous_patch.md'
    pairedArtifact: '.agents/skills/demo1-harmony-contamination-scanner/SKILL.md'
    trigger: >-
      Use this exact unregistered standalone prompt for a long-running Desktop
      Safe Patch loop across HB-01 through HB-12 while preserving active
      sourceSet proof, Gradle gates, and secret safety.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L114'
    legacyTextHash: '79cad1d27077c43e6658e461dc56d2f82b9f7e2d1efc5b80d4819448c132726e'
    kind: 'prompt-pack'
    canonicalId: 'demo1_db_schema_source_patch_9h'
    contractClass: 'decision-changing'
    contractRefs:
      - 'agent-prompts/agents/demo1_db_schema_source_patch_9h/system_ko.md#2. Authority Order'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_db_schema_source_patch_9h/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_db_schema_source_patch_9h'
    trigger: >-
      Use when DB structure is suspected wrong and a bounded long pass must
      quantify Java, JPA, and DDL against live MariaDB, MySQL, H2, or Supabase
      metadata, add only needed secret-safe tools, and patch the smallest active
      source or DDL blocker.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L115'
    legacyTextHash: '2961108807a727dc1709bd8a27e5de05836a5ea31d53a88abbf6189fefa92fe1'
    kind: 'prompt-pack'
    canonicalId: 'demo1_graphrag_only_source_patch_9h'
    contractClass: 'decision-changing'
    contractRefs:
      - 'agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md#1. Scope Gate'
      - 'AGENTS.md#Safe Patch Rules'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md'
    pairedArtifact: 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md'
    trigger: >-
      Use when attached or pasted material must be reduced to GraphRAG and KG
      source-modification targets, source-backed normalized scoring, and a
      bounded Desktop Safe Patch directive without unrelated domain claims.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L116'
    legacyTextHash: 'cfdd3395dd791ef495dd991c56d229c245e22746c3dc42b1bc0c68ffe2eea782'
    kind: 'prompt-pack'
    canonicalId: 'demo1_ai_debug_metrics_ledger'
    contractClass: 'decision-changing'
    contractRefs:
      - 'agent-prompts/agents/demo1_ai_debug_metrics_ledger/system_ko.md#Metrics Contract'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_ai_debug_metrics_ledger/system_ko.md'
    pairedArtifact: 'agent-prompts/prompts.manifest.yaml#demo1_ai_debug_metrics_ledger'
    trigger: >-
      Use when turning the debugging surface into AI-assisted metrics, a usage
      ledger, Failure Pattern Analysis tiles, and read-only debug dashboard or
      API views on the existing DebugEventStore, TraceStore, redaction helpers,
      and admin debug pages.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L117'
    legacyTextHash: '5e714dd4a65b2c52da378e74e9f8040901727ebf891a5c8f50f4a8fa321c012d'
    kind: 'standalone-prompt'
    canonicalId: 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md'
    contractClass: 'decision-changing'
    contractRefs:
      - 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md#Desktop Canonical Codex Safe Patch Directive'
      - 'AGENTS.md#Prompt, Search, And Provider Hygiene'
      - 'AGENTS.md#Redaction'
    loadPolicy: 'on-demand'
    status: 'preserved'
    source: 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md'
    pairedArtifact: '.agents/skills/demo1-subsystem-patch-directive/SKILL.md'
    trigger: >-
      Use this exact unregistered standalone prompt for integrated GraphRAG
      conversation indexing, Brain State, Overdrive compression, CFVM, MoE,
      Matryoshka, ExtremeZ, and HYPERNOVA hardening while enforcing
      PromptBuilder-only construction, CancelShield toxicity rules, required
      TraceStore keys, and SpecialMode mutual exclusion.

  - legacyLocation: 'AGENTS.md#Reusable Prompt Packs:L118'
    legacyTextHash: 'f8d56e404ac8aaec626c8e0df45af0240e4887af658ea38def0a8f1523f06f39'
    kind: 'skill'
    canonicalId: 'demo1-subsystem-patch-directive'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Skill And Prompt Routing'
      - 'AGENTS.md#Safe Patch Rules'
      - 'AGENTS.md#Evidence And Verification'
    loadPolicy: 'mandatory-before-mutation'
    status: 'preserved'
    source: '.agents/skills/demo1-subsystem-patch-directive/SKILL.md'
    pairedArtifact: 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md'
    trigger: >-
      Use when subsystem work needs precise thresholds, algorithm snippets,
      file-to-file mappings, TraceStore keys, and verification gates for S01
      through S08. Always read it before adding class bodies or modifying core
      Overdrive, CFVM, MoE, Matryoshka, ExtremeZ, HYPERNOVA, CIH-RAG, MLA, or
      OpenAI-adapter algorithm logic.

  - kind: 'skill'
    canonicalId: 'demo1-work-ledger'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Work Ledger: status, journal, per-change backup (Git-free)'
      - 'docs/PROJECT_STATUS.md'
      - 'docs/codex-autonomous-work.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-work-ledger/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-work-ledger/agents/openai.yaml'
    trigger: >-
      Use for file-changing demo-1 work by any agent: read PROJECT_STATUS.md,
      register a work_journal task, preserve each change-set with
      codex_work_checkpoint cycles, record real verification, and recover only
      declared targets. Not for read-only explanation or search.

  - kind: 'skill'
    canonicalId: 'agent-session-watchdog'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Common Guard Entry Points (Codex / Grok / Devin / Cline)'
      - 'docs/CODEX_SESSION_SOURCE_REGRESSION_AUDIT_20260919.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/agent-session-watchdog/SKILL.md'
    pairedArtifact: '.agents/skills/agent-session-watchdog/agents/openai.yaml'
    trigger: >-
      Use when an agent session may be looping, editing from stale preimages,
      contaminated by compaction/model switch, or leaving stale subagent
      children: run scripts/agent_session_watch.py stores|scan|watch|diagnose
      (Watch-Agents.bat). Read-only; 'auto' findings run the bounded
      diagnostic bundle, warn/info only report.

  - kind: 'skill'
    canonicalId: 'demo1-devin-source-orchestrator'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Devin / multi-seam source orchestration'
      - '.agents/skills/demo1-devin-source-orchestrator/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-devin-source-orchestrator/SKILL.md'
    pairedArtifact: 'scripts/devin_task_orchestrate.py'
    trigger: >-
      Use when a pasted multi-seam demo-1 source brief (hint-input window,
      Fold background listen, video + settings + late-response) must be split
      into one-skill-per-phase tool plans instead of one primary skill or
      thirty @mentions. Run scripts/devin_task_orchestrate.py plan.

  - kind: 'skill'
    canonicalId: 'demo1-conversate-hint-context'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Conversate hint evidence (Fold6 / Meta Display)'
      - '.agents/skills/demo1-conversate-hint-context/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-conversate-hint-context/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-conversate-hint-context/agents/openai.yaml'
    trigger: >-
      Use when Fold/Conversate hints drag old topics and the user wants a
      settings-driven past-context input window, new-context button, and
      late-response discard without wiping stored transcripts. Not hint
      output length and not display TTL.

  - kind: 'skill'
    canonicalId: 'demo1-devin-directive-loop'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#External-Agent Directive Loops'
      - '.agents/skills/demo1-devin-directive-loop/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-devin-directive-loop/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-devin-directive-loop/agents/openai.yaml'
    trigger: >-
      Use when the user pastes an external agent (Devin) report and asks how
      to reply, or asks to draft a demo-1 source-fix directive: classify
      DRAFT/REVIEW/CLOSE, keep follow-ups delta-only against the evidence
      tiers, and close loops into PROJECT_STATUS.md so instructions never
      regress to pre-fix baselines.

  - kind: 'skill'
    canonicalId: 'demo1-nova-focus'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Nova Focus (''노바'' wake-word focused conversation)'
      - '.agents/skills/demo1-nova-focus/SKILL.md'
      - 'agent-prompts/nova-focus/'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-nova-focus/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-nova-focus/agents/openai.yaml'
    trigger: >-
      Use when implementing or fixing Nova Focus — the '노바' wake-word
      focused-conversation mode on the existing Fold6 Conversate transcript
      plus Meta lens sequential answer display and one persistent room.
      Digest + phase order + must-pass gates; spec copies live in
      agent-prompts/nova-focus/. Not for general hint TTL/paging or
      background-listen bugs.

  - kind: 'skill'
    canonicalId: 'agent-scope-lease'
    contractClass: 'coordination'
    contractRefs:
      - '.agents/skills/agent-scope-lease/SKILL.md'
      - 'scripts/agent_scope_lease.py'
      - '__patch_drop__/source_edit_session.ps1'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/agent-scope-lease/SKILL.md'
    pairedArtifact: '.agents/skills/agent-scope-lease/agents/openai.yaml'
    trigger: >-
      Use when several agents work this checkout in parallel (Codex writing
      source directives, Devin applying them, Grok, Notebook): check who owns
      which paths/features before editing, claim a per-task lease, release it
      on done/abort. Local lease+journal files, no Git; expired foreign
      leases still block overlapping targets.

  - kind: 'skill'
    canonicalId: 'demo1-agent-port-lease'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Agent dynamic port lease'
      - '.agents/skills/demo1-agent-port-lease/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-agent-port-lease/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-agent-port-lease/agents/openai.yaml'
    trigger: >-
      Use when a parallel agent must lease a free loopback port, start only
      its own server, record port/pid/traceId, and release that lease.
      Run scripts/agent_port_lease.py. Do not stop Meta Display 18180-18182.

  - kind: 'skill'
    canonicalId: 'demo1-chat-session-debug'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Common Guard Entry Points (Codex / Grok / Devin / Cline)'
      - '.agents/skills/demo1-chat-session-debug/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-chat-session-debug/SKILL.md'
    pairedArtifact: 'scripts/chat_session_debug_export.py'
    trigger: >-
      Use when answering "왜 이 모델/이 경고?" for a chat or Meta Display run:
      list/show/export the sanitized per-run trace under
      var/debug/chat-session-traces and share only the export path.

  - kind: 'skill'
    canonicalId: 'demo1-mutable-spec-policy'
    contractClass: 'decision-changing'
    contractRefs:
      - 'AGENTS.md#Mutable Spec vs Hard Constants'
      - 'docs/MUTABLE_SPEC_POLICY.md'
      - '.agents/skills/demo1-mutable-spec-policy/SKILL.md'
    loadPolicy: 'on-demand'
    status: 'active'
    source: '.agents/skills/demo1-mutable-spec-policy/SKILL.md'
    pairedArtifact: '.agents/skills/demo1-mutable-spec-policy/agents/openai.yaml'
    trigger: >-
      Use before implementing or patching API, model, Display, routing, or
      port behavior: mutable spec values are re-read from SSOT/live probes;
      only AGENTS.md hard constraints are constants. Never hardcode magic
      numbers or stale model tags from old docs/skills/handoffs.
```
