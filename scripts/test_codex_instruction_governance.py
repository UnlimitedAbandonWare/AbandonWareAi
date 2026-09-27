from __future__ import annotations

import copy
from contextlib import contextmanager
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import tomllib
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
PROJECT_AGENTS = ROOT / "AGENTS.md"
ROUTING_INDEX = ROOT / ".agents" / "skills" / "INDEX.md"
PROMPT_MANIFEST = ROOT / "agent-prompts" / "prompts.manifest.yaml"
GLOBAL_TEMPLATE = ROOT / "agent-prompts" / "codex_global_windows_personal_instructions.md"
CODEX_HOME = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
GLOBAL_AGENTS = CODEX_HOME / "AGENTS.md"
CODEX_CONFIG = CODEX_HOME / "config.toml"
PROJECT_DOC_CAPACITY = 65_536

ALLOWED_KINDS = {"skill", "prompt-pack", "standalone-prompt", "script/tool"}
ROUTE_SCHEMA_FIELDS = {
    "legacyLocation",
    "legacyTextHash",
    "kind",
    "canonicalId",
    "source",
    "pairedArtifact",
    "trigger",
    "contractClass",
    "contractRefs",
    "loadPolicy",
    "status",
}
EXPECTED_ROUTES = {
    ("standalone-prompt", "agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md"):
        "agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md",
    ("prompt-pack", "demo1_p0_safe_patch_orchestrator"):
        "agent-prompts/agents/demo1_p0_safe_patch_orchestrator/system.md",
    ("skill", "demo1-source-edit-three-way-preflight"):
        ".agents/skills/demo1-source-edit-three-way-preflight/SKILL.md",
    ("skill", "demo1-macsrc-smb-direct-patch"):
        ".agents/skills/demo1-macsrc-smb-direct-patch/SKILL.md",
    ("prompt-pack", "demo1_dual_codex_smb_safe_patch"):
        "agent-prompts/agents/demo1_dual_codex_smb_safe_patch/system_ko.md",
    ("prompt-pack", "demo1_three_node_smb_codex"):
        "agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md",
    ("prompt-pack", "demo1_notebook_desktop_goal_handoff"):
        "agent-prompts/agents/demo1_notebook_desktop_goal_handoff/system_ko.md",
    ("prompt-pack", "demo1_mcp_control_tower"):
        "agent-prompts/agents/demo1_mcp_control_tower/system.md",
    ("prompt-pack", "demo1_claude_peers_web_probe_agent_upgrade_5h"):
        "agent-prompts/agents/demo1_claude_peers_web_probe_agent_upgrade_5h/system_ko.md",
    ("script/tool", "scripts/awx_mcp_stdio_server.py"):
        "scripts/awx_mcp_stdio_server.py",
    ("script/tool", "main/resources/mcp/awx-control-tower-mcp-client.sample.json"):
        "main/resources/mcp/awx-control-tower-mcp-client.sample.json",
    ("script/tool", "desktop_dispatch_packet"):
        "main/resources/mcp/awx-control-tower-tools.json#desktop_dispatch_packet",
    ("script/tool", "producer_command_plan"):
        "main/resources/mcp/awx-control-tower-tools.json#producer_command_plan",
    ("script/tool", "scripts/awx_mcp_node_smoke.py"):
        "scripts/awx_mcp_node_smoke.py",
    ("script/tool", "scripts/awx_mcp_producer_handoff.py"):
        "scripts/awx_mcp_producer_handoff.py",
    ("script/tool", "external_evidence_audit"):
        "main/resources/mcp/awx-control-tower-tools.json#external_evidence_audit",
    ("script/tool", "scripts/awx_mcp_completion_audit.py"):
        "scripts/awx_mcp_completion_audit.py",
    ("skill", "patchdrop-safe-patch-orchestrator"):
        ".agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md",
    ("prompt-pack", "demo1_graphrag_kg_macmini_patchdrop"):
        "agent-prompts/agents/demo1_graphrag_kg_macmini_patchdrop/system_ko.md",
    ("prompt-pack", "demo1_macmini_subserver_integration"):
        "agent-prompts/agents/demo1_macmini_subserver_integration/system.md",
    ("skill", "demo1-mcp-control-tower"):
        ".agents/skills/demo1-mcp-control-tower/SKILL.md",
    ("skill", "archive.search"): ".agents/skills/archive-search/SKILL.md",
    ("skill", "archive.restore"): ".agents/skills/archive-restore/SKILL.md",
    ("skill", "verify_boot"): ".agents/skills/verify-boot/SKILL.md",
    ("skill", "build_error_miner"): ".agents/skills/build-error-miner/SKILL.md",
    ("skill", "run_pipeline"): ".agents/skills/run-pipeline/SKILL.md",
    ("prompt-pack", "demo1_dynamic_rag_autonomous_patch_directive"):
        "agent-prompts/agents/demo1_dynamic_rag_autonomous_patch_directive/system_ko.md",
    ("prompt-pack", "demo1_orchestration_feature_expansion_plan"):
        "agent-prompts/agents/demo1_orchestration_feature_expansion_plan/system_ko.md",
    ("prompt-pack", "demo1_desktop_patchdrop_janitor"):
        "agent-prompts/agents/demo1_desktop_patchdrop_janitor/system_ko.md",
    ("prompt-pack", "demo1_desktop_referee_intent_packet"):
        "agent-prompts/agents/demo1_desktop_referee_intent_packet/system_ko.md",
    ("script/tool", "__patch_drop__/producer_bundle.ps1"):
        "__patch_drop__/producer_bundle.ps1",
    ("script/tool", "__patch_drop__/producer_bundle.py"):
        "__patch_drop__/producer_bundle.py",
    ("script/tool", "__patch_drop__/three_node_patchdrop_smoke.ps1"):
        "__patch_drop__/three_node_patchdrop_smoke.ps1",
    ("skill", "macmini-safe-patch-assistant"):
        ".agents/skills/macmini-safe-patch-assistant/SKILL.md",
    ("prompt-pack", "demo1_orch_patch_scanner"):
        "agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md",
    ("prompt-pack", "demo1_orch_debug_scan"):
        "agent-prompts/agents/demo1_orch_debug_scan/system_ko.md",
    ("prompt-pack", "demo1_orch_directive_generator"):
        "agent-prompts/agents/demo1_orch_directive_generator/system_ko.md",
    ("prompt-pack", "demo1_orch_kpi_dashboard"):
        "agent-prompts/agents/demo1_orch_kpi_dashboard/system_ko.md",
    ("skill", "quantitative-metric-normalizer"):
        ".agents/skills/quantitative-metric-normalizer/SKILL.md",
    ("prompt-pack", "demo1_quant_metric_normalization_antigravity"):
        "agent-prompts/agents/demo1_quant_metric_normalization_antigravity/system_ko.md",
    ("skill", "demo1-ablation-harmony-tracker"):
        ".agents/skills/demo1-ablation-harmony-tracker/SKILL.md",
    ("prompt-pack", "demo1_ablation_harmony_patch_directive"):
        "agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md",
    ("skill", "demo1-harmony-contamination-scanner"):
        ".agents/skills/demo1-harmony-contamination-scanner/SKILL.md",
    ("skill", "demo1-cross-subsystem-guard"):
        ".agents/skills/demo1-cross-subsystem-guard/SKILL.md",
    ("standalone-prompt", "agent-prompts/demo1_harmony_9h_autonomous_patch.md"):
        "agent-prompts/demo1_harmony_9h_autonomous_patch.md",
    ("prompt-pack", "demo1_db_schema_source_patch_9h"):
        "agent-prompts/agents/demo1_db_schema_source_patch_9h/system_ko.md",
    ("prompt-pack", "demo1_graphrag_only_source_patch_9h"):
        "agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md",
    ("prompt-pack", "demo1_ai_debug_metrics_ledger"):
        "agent-prompts/agents/demo1_ai_debug_metrics_ledger/system_ko.md",
    ("standalone-prompt", "agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md"):
        "agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md",
    ("skill", "demo1-subsystem-patch-directive"):
        ".agents/skills/demo1-subsystem-patch-directive/SKILL.md",
}

# Frozen from the 44 exact UTF-8 bullets in the pre-migration
# ``AGENTS.md#Reusable Prompt Packs`` block. These values intentionally do not
# come from the current routing index.
LEGACY_BULLET_SHA256 = {
    75: "492fb7a52cbd4112b3a360363f66051bb66ec05b8a66b24a14b6c356ed2ab763",
    76: "5e5b1751217f15d470f5cfeebaa681390359e92d971414235b679f32dc6e35c4",
    77: "f64240b872b5880e614eb0ac34230cd339c0dbbff5930c3d78a1c7bd550ca0f9",
    78: "7a09df2552617ba34225d22aef0751fb884deba625519399296b7d5994ece961",
    79: "0fa745c1786cd553e13d602284e48179ee65267e89e3ea0959932889523d094d",
    80: "2e66c6172a27cd8d516c3a9c753d3e8fc58c6785d5daafa95be81abe8644d47d",
    81: "cc43e73388c490fb7158665736a4ee8a7645135ecfe16098e9ca09ada8dfbec9",
    82: "89f3907b29ea9572706be9d17d74993f980d5c3ac3d4d50ace7387266be24fa4",
    83: "4f5471f1200d380b28bd9f44db1e6401ff03767862cb82fae1253a53386d14a2",
    84: "6ab9f4896ee8a433d46f81e358af8b25e9c3099d37482c5b0b2e92c718f5dc03",
    85: "c409f3d3fa9760feda360b67e8d0dfa71f046be112d22f1565525e024a491edf",
    86: "60f88a5d7abd7ed970c7c1d60a4f2a166e28d3450310697b2a4221dd7570b12c",
    87: "a88b17b9f5ce5462a1181716cc14c9230906c18480df58af7fee080d8848c263",
    88: "0d91daaf12f542aaa42117e7e1e846f23a9f5fb4dd68049b9f6d04da45556a61",
    89: "0a0edb15e687bfabc98a90e9488076472a61b8574b429ad1bcf5ad7d42d5162a",
    90: "b92205b72a5654205ae16264d7f93a368dc2ffd1c4eb56e315767f23a4b5e6e3",
    91: "84cfb4590c7474da5b696ad33de8a93ef5c8da1a5e27af6a10e12c3d267b8210",
    92: "e886446701fa499d58ed7d585a80b109ffb761fae9ab6645f8216861f55f3ae1",
    93: "294e52b6497265213427ab3554343e13b55277dc1fa5db6d3d3de8a6eb3dddc5",
    94: "a777c7a1c612ef9ed0bf6ffe0d12a656649f58b760c63d5fbda4e454412aad72",
    95: "fa71a579c24bbd1f6672bd9803838f9a2078da82ac8d587345d222a3c9fa88fa",
    96: "1e3480b04c0b45a249c757a1e1dc74d4a2fc0e35fcbaf92ead0f382e684d3463",
    97: "3cbac4183752275df30022abcdff78f27109a7fbd3727d3bafeab845355b761a",
    98: "0e66878c678f704a4dbcc4c50414cb90d66caebeaad15da2d5f1778a8545ea4d",
    99: "1639720597399604d9cc23c834152923b9a5ae4e07d92aafbefa5626f8279e28",
    100: "421b9453cd0234a239c0ee5c7ec755f393fe53fb3d94d97e2ad115c7b7c9e320",
    101: "385f1d680ac7440a2bdae02504a3e96f07c3a03d414bb484a8d5fc02999ff3a0",
    102: "fe9136790437529f5489a3d4080bc7866904d17d61acee7b929fe91d35d3d6c0",
    103: "855485d98c5d5ff6a53f59bd0c43340168469ed2c4bc910540ddb9a833ea3e3a",
    104: "8e0f0b2598322edcadb343a909b44ea4a412236a69c998f611a00003f361d935",
    105: "6e91010353edf97dd0cd951104b8a4f5200b261279536e8a7085c11282bf32de",
    106: "a49d3933ce0b4c71a5df65ee7e77c0962af9f3e4d9f8138e81eaf853a74ddf5c",
    107: "6ef3f50cb224ba00cc955f09ab42021c1aefd5aff378abdf35b6061635143ae6",
    108: "501f41014e8584097ef6604af25f55020b5973548c262ab2db0690c5575df7e8",
    109: "93e1c6e9f6afbd64d1166c05818a08faffdff3cd7d10381bc53daffec6721fa2",
    110: "26aec2348c5df0cf88fec11ea53407a1473edc14c5fb8db67e3c45c3a95c55f4",
    111: "3dbee49f4714ce24f1906123840a7baabfa7c39a10e830ab5965d9cb37a07a3a",
    112: "d58b328f468064c80e2b79b3583532d29a119699256654a91780f95ed73f8100",
    113: "d9d89dfb9e885ab17462f9eaff285c9c730a1a806264c15a00bf4bc7cb501c09",
    114: "79cad1d27077c43e6658e461dc56d2f82b9f7e2d1efc5b80d4819448c132726e",
    115: "2961108807a727dc1709bd8a27e5de05836a5ea31d53a88abbf6189fefa92fe1",
    116: "cfdd3395dd791ef495dd991c56d229c245e22746c3dc42b1bc0c68ffe2eea782",
    117: "5e714dd4a65b2c52da378e74e9f8040901727ebf891a5c8f50f4a8fa321c012d",
    118: "f8d56e404ac8aaec626c8e0df45af0240e4887af658ea38def0a8f1523f06f39",
}

LEGACY_LINE_BY_ROUTE = {
    ("standalone-prompt", "agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md"): 76,
    ("prompt-pack", "demo1_p0_safe_patch_orchestrator"): 77,
    ("skill", "demo1-source-edit-three-way-preflight"): 78,
    ("skill", "demo1-macsrc-smb-direct-patch"): 79,
    ("prompt-pack", "demo1_dual_codex_smb_safe_patch"): 80,
    ("prompt-pack", "demo1_three_node_smb_codex"): 81,
    ("prompt-pack", "demo1_notebook_desktop_goal_handoff"): 82,
    ("prompt-pack", "demo1_mcp_control_tower"): 83,
    ("prompt-pack", "demo1_claude_peers_web_probe_agent_upgrade_5h"): 84,
    ("script/tool", "scripts/awx_mcp_stdio_server.py"): 85,
    ("script/tool", "main/resources/mcp/awx-control-tower-mcp-client.sample.json"): 86,
    ("script/tool", "desktop_dispatch_packet"): 87,
    ("script/tool", "producer_command_plan"): 88,
    ("script/tool", "scripts/awx_mcp_node_smoke.py"): 89,
    ("script/tool", "scripts/awx_mcp_producer_handoff.py"): 90,
    ("script/tool", "external_evidence_audit"): 91,
    ("script/tool", "scripts/awx_mcp_completion_audit.py"): 92,
    ("skill", "patchdrop-safe-patch-orchestrator"): 93,
    ("prompt-pack", "demo1_graphrag_kg_macmini_patchdrop"): 94,
    ("prompt-pack", "demo1_macmini_subserver_integration"): 95,
    ("skill", "demo1-mcp-control-tower"): 96,
    ("skill", "archive.search"): 97,
    ("skill", "archive.restore"): 97,
    ("skill", "verify_boot"): 97,
    ("skill", "build_error_miner"): 97,
    ("skill", "run_pipeline"): 97,
    ("prompt-pack", "demo1_dynamic_rag_autonomous_patch_directive"): 98,
    ("prompt-pack", "demo1_orchestration_feature_expansion_plan"): 99,
    ("prompt-pack", "demo1_desktop_patchdrop_janitor"): 100,
    ("prompt-pack", "demo1_desktop_referee_intent_packet"): 101,
    ("script/tool", "__patch_drop__/producer_bundle.ps1"): 102,
    ("script/tool", "__patch_drop__/producer_bundle.py"): 102,
    ("script/tool", "__patch_drop__/three_node_patchdrop_smoke.ps1"): 103,
    ("skill", "macmini-safe-patch-assistant"): 104,
    ("prompt-pack", "demo1_orch_patch_scanner"): 105,
    ("prompt-pack", "demo1_orch_debug_scan"): 106,
    ("prompt-pack", "demo1_orch_directive_generator"): 107,
    ("prompt-pack", "demo1_orch_kpi_dashboard"): 108,
    ("skill", "quantitative-metric-normalizer"): 109,
    ("prompt-pack", "demo1_quant_metric_normalization_antigravity"): 109,
    ("skill", "demo1-ablation-harmony-tracker"): 110,
    ("prompt-pack", "demo1_ablation_harmony_patch_directive"): 110,
    ("skill", "demo1-harmony-contamination-scanner"): 111,
    ("skill", "demo1-cross-subsystem-guard"): 112,
    ("standalone-prompt", "agent-prompts/demo1_harmony_9h_autonomous_patch.md"): 113,
    ("prompt-pack", "demo1_db_schema_source_patch_9h"): 114,
    ("prompt-pack", "demo1_graphrag_only_source_patch_9h"): 115,
    ("prompt-pack", "demo1_ai_debug_metrics_ledger"): 116,
    ("standalone-prompt", "agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md"): 117,
    ("skill", "demo1-subsystem-patch-directive"): 118,
}

MANDATORY_ROOT_ROUTE_IDS = {
    "demo1-source-edit-three-way-preflight",
    "demo1-macsrc-smb-direct-patch",
    "patchdrop-safe-patch-orchestrator",
    "demo1-subsystem-patch-directive",
    "demo1-cross-subsystem-guard",
}

MANDATORY_BEFORE_MUTATION_ROUTES = {
    ('skill', 'demo1-source-edit-three-way-preflight'),
    ('skill', 'demo1-macsrc-smb-direct-patch'),
    ('skill', 'patchdrop-safe-patch-orchestrator'),
    ('skill', 'demo1-cross-subsystem-guard'),
    ('skill', 'demo1-subsystem-patch-directive'),
}

ROUTE_ONLY_ROUTES = {
    ('script/tool', 'scripts/awx_mcp_stdio_server.py'),
    ('prompt-pack', 'demo1_macmini_subserver_integration'),
    ('prompt-pack', 'demo1_dynamic_rag_autonomous_patch_directive'),
    ('prompt-pack', 'demo1_orch_patch_scanner'),
    ('prompt-pack', 'demo1_orch_debug_scan'),
}

DECISION_CHANGING_ROUTES = frozenset(EXPECTED_ROUTES) - ROUTE_ONLY_ROUTES

EXPECTED_CONTRACT_REFS = {
    ('standalone-prompt', 'agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Evidence And Verification'),
    ('prompt-pack', 'demo1_p0_safe_patch_orchestrator'): ('AGENTS.md#Safe Patch Rules', 'AGENTS.md#Evidence And Verification'),
    ('skill', 'demo1-source-edit-three-way-preflight'): ('AGENTS.md#Skill And Prompt Routing',),
    ('skill', 'demo1-macsrc-smb-direct-patch'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces',),
    ('prompt-pack', 'demo1_dual_codex_smb_safe_patch'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces',),
    ('prompt-pack', 'demo1_three_node_smb_codex'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces',),
    ('prompt-pack', 'demo1_notebook_desktop_goal_handoff'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces',),
    ('prompt-pack', 'demo1_mcp_control_tower'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Redaction'),
    ('prompt-pack', 'demo1_claude_peers_web_probe_agent_upgrade_5h'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Redaction'),
    ('script/tool', 'main/resources/mcp/awx-control-tower-mcp-client.sample.json'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Redaction'),
    ('script/tool', 'desktop_dispatch_packet'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules', 'AGENTS.md#Redaction'),
    ('script/tool', 'producer_command_plan'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Redaction'),
    ('script/tool', 'scripts/awx_mcp_node_smoke.py'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Evidence And Verification'),
    ('script/tool', 'scripts/awx_mcp_producer_handoff.py'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules'),
    ('script/tool', 'external_evidence_audit'): ('AGENTS.md#Evidence And Verification', 'AGENTS.md#Redaction'),
    ('script/tool', 'scripts/awx_mcp_completion_audit.py'): ('AGENTS.md#Evidence And Verification',),
    ('skill', 'patchdrop-safe-patch-orchestrator'): ('AGENTS.md#Skill And Prompt Routing', 'AGENTS.md#PatchDrop Bundle Rules'),
    ('prompt-pack', 'demo1_graphrag_kg_macmini_patchdrop'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules'),
    ('skill', 'demo1-mcp-control-tower'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules', 'AGENTS.md#Redaction'),
    ('skill', 'archive.search'): ('AGENTS.md#Redaction',),
    ('skill', 'archive.restore'): ('AGENTS.md#Redaction', 'AGENTS.md#Evidence And Verification'),
    ('skill', 'verify_boot'): ('AGENTS.md#Evidence And Verification',),
    ('skill', 'build_error_miner'): ('AGENTS.md#Redaction', 'AGENTS.md#Evidence And Verification'),
    ('skill', 'run_pipeline'): ('AGENTS.md#Redaction', 'AGENTS.md#Evidence And Verification'),
    ('prompt-pack', 'demo1_orchestration_feature_expansion_plan'): ('AGENTS.md#Safe Patch Rules', 'AGENTS.md#Evidence And Verification'),
    ('prompt-pack', 'demo1_desktop_patchdrop_janitor'): ('AGENTS.md#PatchDrop Bundle Rules',),
    ('prompt-pack', 'demo1_desktop_referee_intent_packet'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#Evidence And Verification'),
    ('script/tool', '__patch_drop__/producer_bundle.ps1'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules'),
    ('script/tool', '__patch_drop__/producer_bundle.py'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules'),
    ('script/tool', '__patch_drop__/three_node_patchdrop_smoke.ps1'): ('AGENTS.md#PatchDrop Bundle Rules', 'AGENTS.md#Evidence And Verification'),
    ('skill', 'macmini-safe-patch-assistant'): ('AGENTS.md#Desktop / Mac Mini / Notebook Workspaces', 'AGENTS.md#PatchDrop Bundle Rules', 'AGENTS.md#Redaction'),
    ('prompt-pack', 'demo1_orch_directive_generator'): ('AGENTS.md#PatchDrop Bundle Rules', 'AGENTS.md#Evidence And Verification'),
    ('prompt-pack', 'demo1_orch_kpi_dashboard'): ('AGENTS.md#Evidence And Verification',),
    ('skill', 'quantitative-metric-normalizer'): ('AGENTS.md#Evidence And Verification',),
    ('prompt-pack', 'demo1_quant_metric_normalization_antigravity'): ('AGENTS.md#Safe Patch Rules', 'AGENTS.md#Evidence And Verification'),
    ('skill', 'demo1-ablation-harmony-tracker'): ('.agents/skills/demo1-ablation-harmony-tracker/SKILL.md#Patch Gate', 'AGENTS.md#Evidence And Verification'),
    ('prompt-pack', 'demo1_ablation_harmony_patch_directive'): ('agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md#Non-Negotiables', 'AGENTS.md#Evidence And Verification'),
    ('skill', 'demo1-harmony-contamination-scanner'): ('.agents/skills/demo1-harmony-contamination-scanner/SKILL.md#Verification', 'AGENTS.md#Redaction'),
    ('skill', 'demo1-cross-subsystem-guard'): ('AGENTS.md#Skill And Prompt Routing', 'AGENTS.md#Evidence And Verification'),
    ('standalone-prompt', 'agent-prompts/demo1_harmony_9h_autonomous_patch.md'): ('agent-prompts/demo1_harmony_9h_autonomous_patch.md#Non-Negotiables', 'AGENTS.md#Evidence And Verification'),
    ('prompt-pack', 'demo1_db_schema_source_patch_9h'): ('agent-prompts/agents/demo1_db_schema_source_patch_9h/system_ko.md#2. Authority Order', 'AGENTS.md#Redaction'),
    ('prompt-pack', 'demo1_graphrag_only_source_patch_9h'): ('agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md#1. Scope Gate', 'AGENTS.md#Safe Patch Rules'),
    ('prompt-pack', 'demo1_ai_debug_metrics_ledger'): ('agent-prompts/agents/demo1_ai_debug_metrics_ledger/system_ko.md#Metrics Contract', 'AGENTS.md#Redaction'),
    ('standalone-prompt', 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md'): ('agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md#Desktop Canonical Codex Safe Patch Directive', 'AGENTS.md#Prompt, Search, And Provider Hygiene', 'AGENTS.md#Redaction'),
    ('skill', 'demo1-subsystem-patch-directive'): ('AGENTS.md#Skill And Prompt Routing', 'AGENTS.md#Safe Patch Rules', 'AGENTS.md#Evidence And Verification'),
}


ROOT_INDEX_LOAD_CLAUSES = (
    "Read `.agents/skills/INDEX.md` only when a specialized workflow is needed",
    "Select exactly one primary route",
    "Add one extra guard only when independently required",
    "do not load the whole index for ordinary read-only, Markdown-only, or narrow local edits",
    "When a closer repository preflight or review exists, use it as the sole review and do not stack a global review",
)

THREE_QUERY_CLAUSES = (
    "POSITIVE_QUERY",
    "NEGATIVE_QUERY",
    "NEUTRAL_QUERY",
    "stable `APPLY`",
    "read-only",
    "Markdown-only",
    "test-only",
)

MACSRC_COMPATIBILITY_CLAUSES = (
    "`MACSRC_SMB_DIRECT` is a compatibility input only",
    "Accept it internally only",
    "user-facing output must not echo it",
)

YDRIVE_GUARD_CLAUSES = (
    "`YDRIVE_SMB_GUARDED_DIRECT`",
    "backing-share identity mismatch",
    "index lock",
    "changed preimage",
    "lease collision",
    "reparse traversal",
    "secret risk",
    "failed verification",
    "shared source lease",
    "preimage verification immediately before `apply_patch`",
    "focused verification",
    "postimage hashes",
    "count-only secret results",
    "no source-write or fallback authorization",
)

ROOT_SECTION_MARKERS = {
    "Runtime Boundary": (
        "root `main/java` and `main/resources`",
        "`app/src/main/java_clean` and `app/src/main/resources`",
        "`project/src/main/java`",
        "inactive/reference",
    ),
    "Desktop / Mac Mini / Notebook Workspaces": (
        "Desktop original/final verification area",
        "canonical `Y:\\`",
        "backing-identity SHA-256",
        *MACSRC_COMPATIBILITY_CLAUSES,
        *YDRIVE_GUARD_CLAUSES,
        "producer clones can use",
        "without writing the Desktop canonical source",
    ),
    "Safe Patch Rules": (
        "`dev.langchain4j`",
        "`1.0.1`",
        "active sourceSet",
    ),
    "Prompt, Search, And Provider Hygiene": (
        "`PromptBuilder.build(PromptContext)`",
        "must fail soft",
        "`disabledReason`",
        "no outbound provider call",
        "Never emit fake search results",
    ),
    "Skill And Prompt Routing": (
        *ROOT_INDEX_LOAD_CLAUSES,
        *THREE_QUERY_CLAUSES,
        "$demo1-source-edit-three-way-preflight",
        "$demo1-macsrc-smb-direct-patch",
        "$patchdrop-safe-patch-orchestrator",
        "$demo1-subsystem-patch-directive",
        "$demo1-cross-subsystem-guard",
    ),
    "PatchDrop Bundle Rules": (
        "PatchDrop v3",
        "exactly one active top-level cumulative patch",
        "manifest-pinned cumulative v3 patch",
        "must be promoted before Desktop consumption",
        "source-edit lease as `desktop-consumer`",
    ),
    "Redaction": (
        "counts",
        "hash-only values",
        "allowlisted and redacted",
        "TraceStore",
    ),
    "Evidence And Verification": (
        "blocker lane-local",
        "repository-wide `HOLD`",
        "narrowest changed surface first",
    ),
}

NON_ROOT_SECTION_MARKERS = {
    ".agents/skills/demo1-ablation-harmony-tracker/SKILL.md#Patch Gate": (
        "active sourceSet", "canonical class or caller", "observable trace/behavior gap",
        "smallest reversible patch", "known verification command",
    ),
    ".agents/skills/demo1-harmony-contamination-scanner/SKILL.md#Verification": (
        "narrowest useful proof", "quick_validate.py", "skill-family validator",
        "Desktop Gradle commands",
    ),
    "agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md#Non-Negotiables": (
        "active sourceSets", "`1.0.1`", "`PromptBuilder.build(PromptContext)`",
        "raw keys", "Do not fabricate",
    ),
    "agent-prompts/demo1_harmony_9h_autonomous_patch.md#Non-Negotiables": (
        "LangChain4j", "`1.0.1`", "PromptBuilder", "inactive mirror", "fabricated",
    ),
    "agent-prompts/agents/demo1_db_schema_source_patch_9h/system_ko.md#2. Authority Order": (
        "Current repository files", "Live DB metadata", "approved read-only tool",
        "evidence_needed",
    ),
    "agent-prompts/agents/demo1_graphrag_only_source_patch_9h/system_ko.md#1. Scope Gate": (
        "GraphRAG/KG", "active sourceSets", "External schedule", "New frameworks",
        "secret files",
    ),
    "agent-prompts/agents/demo1_ai_debug_metrics_ledger/system_ko.md#Metrics Contract": (
        "snapshot/slot/tile", "score 산식", "redaction allowlist",
    ),
    "agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md#Desktop Canonical Codex Safe Patch Directive": (
        "Desktop 기준", "캐노니컬 루트", "Safe Patch",
    ),
}

# Frozen, self-contained root baseline. It is intentionally assembled only
# from the static semantic contracts above, never from the live AGENTS.md.
GOLDEN_ROOT_TEXT = "\n\n".join(
    f"## {section}\n" + "\n".join(f"- {marker}" for marker in markers)
    for section, markers in ROOT_SECTION_MARKERS.items()
)

ROOT_POINTER_COMPANION_IDS = {"demo1_mcp_control_tower", "demo1-mcp-control-tower"}

# These helpers are durable PatchDrop mechanics as well as typed routing entries.
ROOT_DURABLE_HELPER_IDS = {
    "__patch_drop__/producer_bundle.ps1",
    "__patch_drop__/producer_bundle.py",
}

EXPECTED_PAIRED_ARTIFACTS = {
    ('standalone-prompt', 'agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md'): '.agents/skills/demo1-codex-usage-triage/SKILL.md',
    ('prompt-pack', 'demo1_p0_safe_patch_orchestrator'): '.agents/skills/demo1-autonomous-patch-conductor/SKILL.md',
    ('skill', 'demo1-source-edit-three-way-preflight'): '.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml',
    ('skill', 'demo1-macsrc-smb-direct-patch'): '.agents/skills/demo1-macsrc-smb-direct-patch/agents/openai.yaml',
    ('prompt-pack', 'demo1_dual_codex_smb_safe_patch'): 'agent-prompts/prompts.manifest.yaml#demo1_dual_codex_smb_safe_patch',
    ('prompt-pack', 'demo1_three_node_smb_codex'): 'agent-prompts/prompts.manifest.yaml#demo1_three_node_smb_codex',
    ('prompt-pack', 'demo1_notebook_desktop_goal_handoff'): 'scripts/verify_ydrive_backing_identity.ps1',
    ('prompt-pack', 'demo1_mcp_control_tower'): '.agents/skills/demo1-mcp-control-tower/SKILL.md',
    ('prompt-pack', 'demo1_claude_peers_web_probe_agent_upgrade_5h'): 'agent-prompts/prompts.manifest.yaml#demo1_claude_peers_web_probe_agent_upgrade_5h',
    ('script/tool', 'scripts/awx_mcp_stdio_server.py'): 'main/resources/mcp/awx-control-tower-tools.json',
    ('script/tool', 'main/resources/mcp/awx-control-tower-mcp-client.sample.json'): 'scripts/awx_mcp_stdio_server.py',
    ('script/tool', 'desktop_dispatch_packet'): 'scripts/awx_mcp_toolbox.py',
    ('script/tool', 'producer_command_plan'): 'scripts/awx_mcp_toolbox.py',
    ('script/tool', 'scripts/awx_mcp_node_smoke.py'): 'main/resources/mcp/awx-control-tower-tools.json',
    ('script/tool', 'scripts/awx_mcp_producer_handoff.py'): '__patch_drop__/producer_bundle.py',
    ('script/tool', 'external_evidence_audit'): 'scripts/awx_mcp_toolbox.py',
    ('script/tool', 'scripts/awx_mcp_completion_audit.py'): 'main/resources/mcp/awx-control-tower-tools.json',
    ('skill', 'patchdrop-safe-patch-orchestrator'): '__patch_drop__/README.md',
    ('prompt-pack', 'demo1_graphrag_kg_macmini_patchdrop'): 'agent-prompts/prompts.manifest.yaml#demo1_graphrag_kg_macmini_patchdrop',
    ('prompt-pack', 'demo1_macmini_subserver_integration'): 'agent-prompts/prompts.manifest.yaml#demo1_macmini_subserver_integration',
    ('skill', 'demo1-mcp-control-tower'): 'agent-prompts/agents/demo1_mcp_control_tower/system.md',
    ('skill', 'archive.search'): 'scripts/awx_mcp_toolbox.ps1',
    ('skill', 'archive.restore'): 'scripts/awx_mcp_toolbox.ps1',
    ('skill', 'verify_boot'): 'scripts/awx_mcp_toolbox.ps1',
    ('skill', 'build_error_miner'): 'scripts/awx_mcp_toolbox.ps1',
    ('skill', 'run_pipeline'): 'scripts/awx_mcp_toolbox.ps1',
    ('prompt-pack', 'demo1_dynamic_rag_autonomous_patch_directive'): 'agent-prompts/prompts.manifest.yaml#demo1_dynamic_rag_autonomous_patch_directive',
    ('prompt-pack', 'demo1_orchestration_feature_expansion_plan'): 'agent-prompts/prompts.manifest.yaml#demo1_orchestration_feature_expansion_plan',
    ('prompt-pack', 'demo1_desktop_patchdrop_janitor'): '__patch_drop__/README.md',
    ('prompt-pack', 'demo1_desktop_referee_intent_packet'): 'agent-prompts/prompts.manifest.yaml#demo1_desktop_referee_intent_packet',
    ('script/tool', '__patch_drop__/producer_bundle.ps1'): '.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md',
    ('script/tool', '__patch_drop__/producer_bundle.py'): '.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md',
    ('script/tool', '__patch_drop__/three_node_patchdrop_smoke.ps1'): '__patch_drop__/README.md',
    ('skill', 'macmini-safe-patch-assistant'): '__patch_drop__/producer_bundle.py',
    ('prompt-pack', 'demo1_orch_patch_scanner'): 'agent-prompts/prompts.manifest.yaml#demo1_orch_patch_scanner',
    ('prompt-pack', 'demo1_orch_debug_scan'): 'agent-prompts/prompts.manifest.yaml#demo1_orch_debug_scan',
    ('prompt-pack', 'demo1_orch_directive_generator'): 'agent-prompts/prompts.manifest.yaml#demo1_orch_directive_generator',
    ('prompt-pack', 'demo1_orch_kpi_dashboard'): 'agent-prompts/prompts.manifest.yaml#demo1_orch_kpi_dashboard',
    ('skill', 'quantitative-metric-normalizer'): 'agent-prompts/agents/demo1_quant_metric_normalization_antigravity/system_ko.md',
    ('prompt-pack', 'demo1_quant_metric_normalization_antigravity'): '.agents/skills/quantitative-metric-normalizer/SKILL.md',
    ('skill', 'demo1-ablation-harmony-tracker'): 'agent-prompts/agents/demo1_ablation_harmony_patch_directive/system_ko.md',
    ('prompt-pack', 'demo1_ablation_harmony_patch_directive'): '.agents/skills/demo1-ablation-harmony-tracker/SKILL.md',
    ('skill', 'demo1-harmony-contamination-scanner'): 'agent-prompts/demo1_harmony_9h_autonomous_patch.md',
    ('skill', 'demo1-cross-subsystem-guard'): '.agents/skills/demo1-ablation-harmony-tracker/SKILL.md',
    ('standalone-prompt', 'agent-prompts/demo1_harmony_9h_autonomous_patch.md'): '.agents/skills/demo1-harmony-contamination-scanner/SKILL.md',
    ('prompt-pack', 'demo1_db_schema_source_patch_9h'): 'agent-prompts/prompts.manifest.yaml#demo1_db_schema_source_patch_9h',
    ('prompt-pack', 'demo1_graphrag_only_source_patch_9h'): 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md',
    ('prompt-pack', 'demo1_ai_debug_metrics_ledger'): 'agent-prompts/prompts.manifest.yaml#demo1_ai_debug_metrics_ledger',
    ('standalone-prompt', 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md'): '.agents/skills/demo1-subsystem-patch-directive/SKILL.md',
    ('skill', 'demo1-subsystem-patch-directive'): 'agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md',
}

REQUIRED_TRIGGER_MARKERS = {
    ("standalone-prompt", "agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md"):
        ("Desktop-only", "SMB decommission", "/goal"),
    ("prompt-pack", "demo1_p0_safe_patch_orchestrator"):
        ("@superpowers", "repo evidence", "active sourceSet", "verification"),
    ("skill", "demo1-source-edit-three-way-preflight"):
        ("application-source mutation", "source-owner guard", "read-only", "Markdown-only", "test-only"),
    ("skill", "demo1-macsrc-smb-direct-patch"):
        ("canonical Y:\\", "OneDrive", "dubious ownership", "external reads"),
    ("prompt-pack", "demo1_dual_codex_smb_safe_patch"):
        ("Desktop canonical-root", "Mac mini", "patch producer", "SMB", "PatchDrop"),
    ("prompt-pack", "demo1_three_node_smb_codex"):
        ("Desktop", "Mac mini", "Notebook", "source-edit leases", "PatchDrop-only producer bundles"),
    ("prompt-pack", "demo1_notebook_desktop_goal_handoff"):
        ("read-only EvidenceSnapshot", "GoalContract", "SourceDirective", "backing identity", "verification boolean"),
    ("prompt-pack", "demo1_mcp_control_tower"):
        ("source_scan", "patch_plan", "archive_restore", "run_pipeline", "fixed schemas", "underscore ID"),
    ("prompt-pack", "demo1_claude_peers_web_probe_agent_upgrade_5h"):
        ("claude-peers-suite", "web-probe-first", "Peer Evidence Bus", "Control Tower", "read-only Supabase", "canonical root"),
    ("script/tool", "scripts/awx_mcp_stdio_server.py"):
        ("stdio JSON-RPC", "tools/list", "tools/call", "resources/list", "prompts/list"),
    ("script/tool", "main/resources/mcp/awx-control-tower-mcp-client.sample.json"):
        ("secret-free", "producer-local", "never the Desktop canonical root"),
    ("script/tool", "desktop_dispatch_packet"):
        ("desktop.dispatch_packet", "external_evidence_intake", "external_evidence_audit", "write_dispatch=true", "__patch_drop__/dispatch/"),
    ("script/tool", "producer_command_plan"):
        ("producer.command_plan", "desktopEvidencePath", "environment-name-only"),
    ("script/tool", "scripts/awx_mcp_node_smoke.py"):
        ("producer-local worktrees", "canonical-root", "node-role", "PatchDrop evidence"),
    ("script/tool", "scripts/awx_mcp_producer_handoff.py"):
        ("producer-local edit", "PatchDrop v3 bundle", "producer source root", "node role", "explicit pathspec"),
    ("script/tool", "external_evidence_audit"):
        ("Desktop", "smoke JSON", "data/agent-handoff/mcp-control-tower", "external-host proof"),
    ("script/tool", "scripts/awx_mcp_completion_audit.py"):
        ("Desktop", "local MCP tool", "resource", "prompt contract", "external-host smoke proof"),
    ("skill", "patchdrop-safe-patch-orchestrator"):
        ("producer and consumer roles", "one active cumulative", "PatchDrop v3", "collision gates", "source edits"),
    ("prompt-pack", "demo1_graphrag_kg_macmini_patchdrop"):
        ("GraphRAG", "KG runtime-hardening", "Mac mini", "agent/macmini/<topic>", "PatchDrop", "Desktop final application"),
    ("prompt-pack", "demo1_macmini_subserver_integration"):
        ("Mac mini", "subserver", "helper-node integration"),
    ("skill", "demo1-mcp-control-tower"):
        ("Desktop", "Mac mini", "Notebook", "MCP-style toolbox", "Desktop final ownership", "hyphenated ID"),
    ("skill", "archive.search"):
        ("single-tool", "archive search", "toolbox", "redacted", "path-only"),
    ("skill", "archive.restore"):
        ("single-tool", "archive restore", "explicit mode", "audit log", "checksum evidence"),
    ("skill", "verify_boot"):
        ("single-tool", "boot-verification plan", "Desktop canonical-root", "final proof", "redacted audit output"),
    ("skill", "build_error_miner"):
        ("single-tool", "build or boot logs", "stable failure classes", "redacted output"),
    ("skill", "run_pipeline"):
        ("single-tool", "run_pipeline probe", "verify_boot", "build-error-miner", "redacted"),
    ("prompt-pack", "demo1_dynamic_rag_autonomous_patch_directive"):
        ("Plan DSL", "MLA", "SSE", "failure-pattern", "LangGraph", "Hybrid LLM Gateway"),
    ("prompt-pack", "demo1_orchestration_feature_expansion_plan"):
        ("Plan DSL execution modes", "owner boundaries", "feature flags", "mode priority", "rollback paths", "trace keys"),
    ("prompt-pack", "demo1_desktop_patchdrop_janitor"):
        ("janitor_inventory.ps1", "janitor_promote_producer_pending.ps1", "janitor_isolate_orphan.ps1", "janitor_apply_one.ps1"),
    ("prompt-pack", "demo1_desktop_referee_intent_packet"):
        ("non-applyable intent packet", "queue matrix", "priority index", "runtime-verifier gate", "APPLY", "REJECT"),
    ("script/tool", "__patch_drop__/producer_bundle.ps1"):
        ("producer-local Windows", "PatchDrop v3", "verification log", "SHA sidecar", "manifest", "explicit pathspecs"),
    ("script/tool", "__patch_drop__/producer_bundle.py"):
        ("producer-local worktree", "Python producer", "PatchDrop v3", "Desktop canonical source"),
    ("script/tool", "__patch_drop__/three_node_patchdrop_smoke.ps1"):
        ("Desktop", "temp-only proof", "promoted one at a time", "desktop-consumer gate", "real queue"),
    ("skill", "macmini-safe-patch-assistant"):
        ("Mac mini", "one cumulative PatchDrop v3", "no direct shared-source edits", "SHA-256 manifest", "focused Gradle", "Desktop consumer gates"),
    ("prompt-pack", "demo1_orch_patch_scanner"):
        ("Desktop", "active-source patch candidates", "silent failures", "TraceStore keys", "fail-soft ladders", "AOP proceed"),
    ("prompt-pack", "demo1_orch_debug_scan"):
        ("runtime failure", "bootRun error", "starvation loop", "empty SSE", "failing layer", "responsible candidate"),
    ("prompt-pack", "demo1_orch_directive_generator"):
        ("Mac mini directive", "scored candidate", "RED and GREEN", "Gradle commands", "PatchDrop checklist", "P0"),
    ("prompt-pack", "demo1_orch_kpi_dashboard"):
        ("before and after", "static", "dynamic KPIs", "quantitative", "delta table"),
    ("skill", "quantitative-metric-normalizer"):
        ("Dynamic RAG design", "source-backed score normalization", "subsystem-combination", "read-only Antigravity", "before patching"),
    ("prompt-pack", "demo1_quant_metric_normalization_antigravity"):
        ("quantitative-metric-normalizer", "Dynamic RAG design", "Antigravity-ready", "source-backed", "read-only", "before patching"),
    ("skill", "demo1-ablation-harmony-tracker"):
        ("DA-01 through DA-08", "boosterMode.active", "retrievalOrder.lastSetBy", "extremeZ.cancelShieldWrapped", "hypernova.cvarPhi", "cfvm.boltzmannTemp"),
    ("prompt-pack", "demo1_ablation_harmony_patch_directive"):
        ("ablation-harmony", "executable nine-hour", "Desktop Safe Patch", "TraceStore verification keys", "subsystem gates"),
    ("skill", "demo1-harmony-contamination-scanner"):
        ("HB-01 through HB-12", "silent catch ratio", "duplicate FQCNs", "cross-subsystem files", "TraceStore coverage", "count-only secret scans"),
    ("skill", "demo1-cross-subsystem-guard"):
        ("two or more S01-S08", "shared booster arbitration", "CFVM", "PromptBuilder boundaries", "auto-configuration wiring"),
    ("standalone-prompt", "agent-prompts/demo1_harmony_9h_autonomous_patch.md"):
        ("exact unregistered standalone prompt", "Desktop Safe Patch", "HB-01 through HB-12", "active sourceSet proof", "Gradle gates", "secret safety"),
    ("prompt-pack", "demo1_db_schema_source_patch_9h"):
        ("DB structure", "Java, JPA, and DDL", "MariaDB", "Supabase metadata", "secret-safe tools", "active source or DDL blocker"),
    ("prompt-pack", "demo1_graphrag_only_source_patch_9h"):
        ("GraphRAG", "KG source-modification targets", "source-backed normalized scoring", "Desktop Safe Patch", "unrelated domain claims"),
    ("prompt-pack", "demo1_ai_debug_metrics_ledger"):
        ("AI-assisted metrics", "usage ledger", "Failure Pattern Analysis tiles", "read-only debug dashboard", "DebugEventStore", "TraceStore"),
    ("standalone-prompt", "agent-prompts/agents/demo1_graphrag_brain_moe_patch/PROMPT.md"):
        ("PromptBuilder-only", "CancelShield", "TraceStore", "SpecialMode"),
    ("skill", "demo1-subsystem-patch-directive"):
        ("S01 through S08", "Overdrive", "CFVM", "ExtremeZ", "HYPERNOVA", "OpenAI-adapter"),
}

ABLATION_REQUIRED_TRACE_KEYS = (
    "boosterMode.active",
    "retrievalOrder.lastSetBy",
    "extremeZ.cancelShieldWrapped",
    "extremeZ.timeBudgetConsumedMs",
    "hypernova.cvarPhi",
    "cihRag.breadcrumb.queryRedacted",
    "moe.evolverPlateRegistered",
    "cfvm.boltzmannTemp",
)


def normalized_id(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.casefold()).strip("-")


def golden_routing_rows() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for identity, source in EXPECTED_ROUTES.items():
        legacy_line = LEGACY_LINE_BY_ROUTE[identity]
        trigger_markers = REQUIRED_TRIGGER_MARKERS.get(identity)
        trigger = (
            "; ".join(trigger_markers)
            if trigger_markers
            else f"Preserve the legacy on-demand route for {identity[1]}."
        )
        if identity == ("skill", "demo1-ablation-harmony-tracker"):
            trigger += "; " + "; ".join(ABLATION_REQUIRED_TRACE_KEYS)
        decision_changing = identity in DECISION_CHANGING_ROUTES
        rows.append(
            {
                "legacyLocation": f"AGENTS.md#Reusable Prompt Packs:L{legacy_line}",
                "legacyTextHash": LEGACY_BULLET_SHA256[legacy_line],
                "kind": identity[0],
                "canonicalId": identity[1],
                "source": source,
                "pairedArtifact": EXPECTED_PAIRED_ARTIFACTS.get(identity, source),
                "trigger": trigger,
                "contractClass": "decision-changing" if decision_changing else "route-only",
                "contractRefs": list(EXPECTED_CONTRACT_REFS.get(identity, ())),
                "loadPolicy": (
                    "mandatory-before-mutation"
                    if identity in MANDATORY_BEFORE_MUTATION_ROUTES
                    else "on-demand"
                ),
                "status": "preserved",
            }
        )
    return rows


def _artifact_exists(spec: object) -> bool:
    if not isinstance(spec, str) or not spec.strip():
        return False
    source_name = spec.partition("#")[0]
    if "\\" in source_name or re.match(r"^[A-Za-z]:", source_name):
        return False
    relative = PurePosixPath(source_name)
    if relative.is_absolute() or ".." in relative.parts:
        return False
    resolved = (ROOT / Path(*relative.parts)).resolve()
    try:
        resolved.relative_to(ROOT.resolve())
    except ValueError:
        return False
    return resolved.is_file()


def _section_text(text: str, requested_section: str) -> str | None:
    """Return one exact Markdown section by its full heading title."""

    lines = text.splitlines()
    start = None
    level = None
    for index, line in enumerate(lines):
        match = re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
        if not match:
            continue
        title = match.group(2).strip()
        if title == requested_section:
            start = index
            level = len(match.group(1))
            break
    if start is None or level is None:
        return None
    end = len(lines)
    for index in range(start + 1, len(lines)):
        match = re.match(r"^(#{1,6})\s+", lines[index])
        if match and len(match.group(1)) <= level:
            end = index
            break
    return "\n".join(lines[start:end])


def _section_contains(section_text: str, marker: str) -> bool:
    return " ".join(marker.split()) in " ".join(section_text.split())


def _section_lines(section_text: str | None) -> tuple[str, ...]:
    """Return whitespace-normalized lines for narrow semantic checks."""

    if section_text is None:
        return ()
    return tuple(" ".join(line.split()) for line in section_text.splitlines())


def _line_matches_any(lines: tuple[str, ...], patterns: tuple[str, ...]) -> bool:
    return any(
        re.search(pattern, line, flags=re.IGNORECASE) is not None
        for line in lines
        for pattern in patterns
    )


def _legacy_macsrc_user_visible(text: str) -> bool:
    """Detect affirmative, user-visible legacy-mode output sentences only."""

    for sentence in re.split(r"(?<=[.!?])\s+|\r?\n", text):
        if "MACSRC_SMB_DIRECT" not in sentence:
            continue
        if re.search(r"\busers?\b", sentence, flags=re.IGNORECASE) is None:
            continue
        if re.search(
            r"\b(?:must\s+not|never|do\s+not|not\s+echo|compatibility\s+input\s+only)\b",
            sentence,
            flags=re.IGNORECASE,
        ):
            continue
        if re.search(
            r"(?:"
            r"\b(?:return|print|emit|output|show|display|deliver|send|provide)\b"
            r"[^.!?]*\bMACSRC_SMB_DIRECT\b[^.!?]*\busers?\b"
            r"|\busers?\b[^.!?]*\b(?:receive|get|see)\b"
            r"[^.!?]*\bMACSRC_SMB_DIRECT\b"
            r")",
            sentence,
            flags=re.IGNORECASE,
        ):
            return True
    return False


def _contract_ref_section(spec: object, root_text: str) -> str | None:
    if not isinstance(spec, str) or "#" not in spec:
        return None
    source_name, _, section = spec.partition("#")
    if not source_name or not section:
        return None
    if source_name == "AGENTS.md":
        return _section_text(root_text, section)
    if not _artifact_exists(source_name):
        return None
    source = ROOT / Path(*PurePosixPath(source_name).parts)
    text = source.read_text(encoding="utf-8")
    return _section_text(text, section)


def governance_error_codes(
    root_text: str,
    rows: list[dict[str, object]],
    config_value: object,
) -> list[str]:
    """Return stable governance failures without consulting the live index."""

    errors: list[str] = []

    def add(code: str) -> None:
        if code not in errors:
            errors.append(code)

    identities: list[tuple[str, str]] = []
    rows_by_identity: dict[tuple[str, str], dict[str, object]] = {}
    normalized_by_namespace: set[tuple[str, str]] = set()
    namespaces_by_normalized: dict[str, set[str]] = {}

    for row in rows:
        if set(row) != ROUTE_SCHEMA_FIELDS:
            add("ROUTE_SCHEMA_INVALID")
        kind = row.get("kind")
        canonical_id = row.get("canonicalId")
        if not isinstance(kind, str) or not isinstance(canonical_id, str):
            add("ROUTE_SCHEMA_INVALID")
            continue
        identity = (kind, canonical_id)
        identities.append(identity)
        if identity in rows_by_identity:
            add("ROUTE_NORMALIZED_DUPLICATE")
        rows_by_identity[identity] = row

        normalized = normalized_id(canonical_id)
        normalized_key = (kind, normalized)
        if normalized_key in normalized_by_namespace:
            add("ROUTE_NORMALIZED_DUPLICATE")
        normalized_by_namespace.add(normalized_key)
        namespaces_by_normalized.setdefault(normalized, set()).add(kind)

    companion_identities = {
        ("prompt-pack", "demo1_mcp_control_tower"),
        ("skill", "demo1-mcp-control-tower"),
    }
    identity_set = set(identities)
    if not companion_identities.issubset(identity_set):
        companion_normalized = normalized_id("demo1_mcp_control_tower")
        if any(normalized_id(route_id) == companion_normalized for _, route_id in identities):
            add("NORMALIZED_COMPANION_COLLAPSED")

    for route_id, kinds in namespaces_by_normalized.items():
        if len(kinds) <= 1:
            continue
        collision_identities = {
            identity for identity in identity_set if normalized_id(identity[1]) == route_id
        }
        if collision_identities != companion_identities:
            add("ROUTE_NORMALIZED_DUPLICATE")

    expected_identity_set = set(EXPECTED_ROUTES)
    if expected_identity_set - identity_set:
        add("ROUTE_MISSING")
    if identity_set - expected_identity_set:
        add("ROUTE_UNEXPECTED")

    for identity, expected_source in EXPECTED_ROUTES.items():
        row = rows_by_identity.get(identity)
        if row is None:
            continue
        if row.get("source") != expected_source:
            add("ROUTE_SOURCE_MISMATCH")
        if not _artifact_exists(row.get("source")):
            add("ROUTE_SOURCE_MISSING")
        if not _artifact_exists(row.get("pairedArtifact")):
            add("ROUTE_PAIRED_ARTIFACT_MISSING")
        expected_pair = EXPECTED_PAIRED_ARTIFACTS.get(identity)
        if expected_pair is not None and row.get("pairedArtifact") != expected_pair:
            add("ROUTE_PAIRED_ARTIFACT_MISMATCH")

        legacy_line = LEGACY_LINE_BY_ROUTE[identity]
        if row.get("legacyLocation") != f"AGENTS.md#Reusable Prompt Packs:L{legacy_line}":
            add("LEGACY_LOCATION_MISMATCH")
        if row.get("legacyTextHash") != LEGACY_BULLET_SHA256[legacy_line]:
            add("LEGACY_TEXT_HASH_MISMATCH")

        expected_class = (
            "decision-changing" if identity in DECISION_CHANGING_ROUTES else "route-only"
        )
        if row.get("contractClass") != expected_class:
            add("CONTRACT_WEAKENED")
        refs = row.get("contractRefs")
        if expected_class == "decision-changing":
            if not isinstance(refs, list) or not refs:
                add("CONTRACT_REFS_MISSING")
            else:
                expected_refs = list(EXPECTED_CONTRACT_REFS.get(identity, ()))
                if refs != expected_refs:
                    add("CONTRACT_WEAKENED")
                for ref in refs:
                    section_text = _contract_ref_section(ref, root_text)
                    if section_text is None:
                        add("CONTRACT_ORPHAN")
                        continue
                    if isinstance(ref, str) and ref.startswith("AGENTS.md#"):
                        section_name = ref.partition("#")[2]
                        markers = ROOT_SECTION_MARKERS.get(section_name, ())
                    else:
                        markers = NON_ROOT_SECTION_MARKERS.get(str(ref), ())
                    if any(not _section_contains(section_text, marker) for marker in markers):
                        add("CONTRACT_WEAKENED")

        expected_load_policy = (
            "mandatory-before-mutation"
            if identity in MANDATORY_BEFORE_MUTATION_ROUTES
            else "on-demand"
        )
        if row.get("loadPolicy") != expected_load_policy:
            if identity == ("skill", "demo1-source-edit-three-way-preflight"):
                add("MUTATION_GATE_ON_DEMAND")
            else:
                add("ROUTE_LOAD_POLICY_MISMATCH")
        if row.get("status") != "preserved":
            add("ROUTE_STATUS_NOT_PRESERVED")

        trigger = row.get("trigger")
        if not isinstance(trigger, str) or not trigger.strip():
            add("ROUTE_TRIGGER_WEAKENED")
        else:
            for marker in REQUIRED_TRIGGER_MARKERS.get(identity, ()):
                if marker.casefold() not in trigger.casefold():
                    add("ROUTE_TRIGGER_WEAKENED")

        if identity == ("skill", "demo1-ablation-harmony-tracker") and isinstance(
            trigger, str
        ):
            if any(key.casefold() not in trigger.casefold() for key in ABLATION_REQUIRED_TRACE_KEYS):
                add("ROUTE_TRIGGER_WEAKENED")

    for section_name, markers in ROOT_SECTION_MARKERS.items():
        section_text = _section_text(root_text, section_name)
        if section_text is None:
            add("CONTRACT_ORPHAN")
        elif any(not _section_contains(section_text, marker) for marker in markers):
            add("CONTRACT_WEAKENED")

    routing_lines = _section_lines(
        _section_text(root_text, "Skill And Prompt Routing")
    )
    optional_three_query = any(
        all(query in line for query in ("POSITIVE_QUERY", "NEGATIVE_QUERY", "NEUTRAL_QUERY"))
        and re.search(r"\b(?:may|optional(?:ly)?)\b", line, flags=re.IGNORECASE)
        for line in routing_lines
    )
    excluded_work_triggered = _line_matches_any(
        routing_lines,
        (
            r"^(?!.*\b(?:do\s+not|never)\b).*\btrigger\s+this\s+gate\s+for\b"
            r".*\bread-only\b.*\bmarkdown-only\b.*\btest-only\b",
        ),
    )
    if optional_three_query or excluded_work_triggered:
        add("THREE_QUERY_GATE_MISSING")

    desktop_lines = _section_lines(
        _section_text(root_text, "Desktop / Mac Mini / Notebook Workspaces")
    )
    optional_direct_gates = any(
        re.search(r"\b(?:may|optional(?:ly)?)\b", line, flags=re.IGNORECASE)
        and (
            all(
                marker in line
                for marker in (
                    "shared source lease",
                    "preimage verification immediately before `apply_patch`",
                    "focused verification",
                )
            )
            or "count-only secret results" in line
        )
        for line in desktop_lines
    )
    continue_on_direct_failures = any(
        re.search(r"\bcontinue\s+on\b", line, flags=re.IGNORECASE)
        and all(
            marker in line
            for marker in (
                "backing-share identity mismatch",
                "index lock",
                "changed preimage",
                "lease collision",
                "failed verification",
            )
        )
        for line in desktop_lines
    )
    inverted_write_boundary = _line_matches_any(
        desktop_lines,
        (
            r"\bYDRIVE_SMB_GUARDED_DIRECT\b.*\bdoes\s+not\s+restrict\b"
            r".*\bapplication-source\s+write\s+destination\b",
        ),
    )
    raw_secret_results = _line_matches_any(
        desktop_lines,
        (
            r"\bcount-only\s+secret\s+results\b[^.]*\boptional\b",
            r"\braw\s+secret\s+results\b[^.]*\ballowed\b",
        ),
    )
    if (
        optional_direct_gates
        or continue_on_direct_failures
        or inverted_write_boundary
        or raw_secret_results
    ):
        add("YDRIVE_GUARD_UNLINKED")

    prompt_lines = _section_lines(
        _section_text(root_text, "Prompt, Search, And Provider Hygiene")
    )
    evidence_lines = _section_lines(
        _section_text(root_text, "Evidence And Verification")
    )
    patchdrop_lines = _section_lines(
        _section_text(root_text, "PatchDrop Bundle Rules")
    )
    if (
        _line_matches_any(
            prompt_lines,
            (
                r"\bmust\s+not\s+stay\s+on\b"
                r".*`PromptBuilder\.build\(PromptContext\)`",
            ),
        )
        or _line_matches_any(
            evidence_lines,
            (
                r"\bdo\s+not\s+keep\b.*\bblocker\s+lane-local\b"
                r".*\balways\s+expand\b.*\brepository-wide\s+`HOLD`",
            ),
        )
        or _line_matches_any(
            patchdrop_lines,
            (
                r"\bdoes\s+not\s+require\s+exactly\s+one\s+active\s+top-level\s+"
                r"cumulative\s+patch\s+per\s+slug\b",
            ),
        )
    ):
        add("CONTRACT_WEAKENED")

    if any(clause not in root_text for clause in ROOT_INDEX_LOAD_CLAUSES):
        add("ROOT_INDEX_CATALOG_MISSING")
    if "NEGATIVE_QUERY" not in root_text:
        add("THREE_QUERY_NEGATIVE_MISSING")
    elif any(clause not in root_text for clause in THREE_QUERY_CLAUSES):
        add("THREE_QUERY_GATE_MISSING")

    if any(clause not in root_text for clause in MACSRC_COMPATIBILITY_CLAUSES):
        add("LEGACY_MACSRC_COMPATIBILITY_MISSING")
    exposed_legacy_mode = re.search(
        r"(?im)^(?!.*(?:must not|never|do not)).*(?:user-facing|output mode|emits?).*MACSRC_SMB_DIRECT",
        root_text,
    )
    if exposed_legacy_mode or _legacy_macsrc_user_visible(root_text):
        add("LEGACY_MACSRC_USER_VISIBLE")

    if any(clause not in root_text for clause in YDRIVE_GUARD_CLAUSES):
        add("YDRIVE_GUARD_UNLINKED")
    if config_value != PROJECT_DOC_CAPACITY:
        add("PROJECT_DOC_CAPACITY_MISMATCH")

    return errors


def routing_rows(text: str) -> list[dict[str, object]]:
    match = re.search(r"```yaml\s*(routes:.*)\s*```", text, flags=re.DOTALL)
    if not match:
        return []
    payload = yaml.safe_load(match.group(1)) or {}
    return payload.get("routes", [])


def skill_name(skill_path: Path) -> str:
    text = skill_path.read_text(encoding="utf-8")
    parts = text.split("---", 2)
    if len(parts) < 3:
        return ""
    return str((yaml.safe_load(parts[1]) or {}).get("name", ""))


def prompt_manifest_by_id() -> dict[str, dict[str, object]]:
    manifest = yaml.safe_load(PROMPT_MANIFEST.read_text(encoding="utf-8")) or {}
    return {str(row["id"]): row for row in manifest.get("agents", [])}


def tool_names(manifest_path: Path) -> set[str]:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    names: set[str] = set()
    for tool in payload.get("tools", []):
        names.add(str(tool["name"]))
        names.update(str(alias) for alias in tool.get("aliases", []))
    return names


def codex_command() -> str:
    appdata = os.environ.get("APPDATA")
    if appdata:
        candidate = Path(appdata) / "npm" / "codex.cmd"
        if candidate.is_file():
            return str(candidate)
    resolved = shutil.which("codex.cmd") or shutil.which("codex")
    if not resolved:
        raise FileNotFoundError("codex CLI was not found")
    return resolved


def prompt_input_result(
    cwd: Path,
    prompt: str,
    *,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    command = [codex_command()]
    command.extend(("debug", "prompt-input", prompt))
    return subprocess.run(
        command,
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
        check=False,
        env=env,
    )


def prompt_input_text(
    cwd: Path,
    prompt: str,
    *,
    env: dict[str, str] | None = None,
) -> str:
    result = prompt_input_result(cwd, prompt, env=env)
    if result.returncode:
        raise AssertionError(
            f"codex debug prompt-input failed ({result.returncode}): {result.stderr[:500]}"
        )
    payload = json.loads(result.stdout)
    return "\n".join(
        str(block.get("text", ""))
        for item in payload
        for block in item.get("content", [])
        if isinstance(block, dict)
    )


def write_instruction_fixture(path: Path, total_bytes: int, head: str, tail: str) -> None:
    prefix = f"# Instruction Boundary Fixture\n\n{head}\n".encode("utf-8")
    suffix = f"\n{tail}\n".encode("utf-8")
    filler_size = total_bytes - len(prefix) - len(suffix)
    if filler_size < 0:
        raise ValueError("fixture markers exceed requested byte count")
    path.write_bytes(prefix + (b"x" * filler_size) + suffix)


@contextmanager
def codex_boundary_fixture(*, global_agents: bytes | None = None):
    temp_root = Path(tempfile.gettempdir()).resolve()
    fixture = Path(
        tempfile.mkdtemp(prefix="codex-instruction-boundary-", dir=temp_root)
    ).resolve()
    try:
        if (
            fixture.parent != temp_root
            or not fixture.name.startswith("codex-instruction-boundary-")
        ):
            raise RuntimeError(f"unsafe boundary fixture path: {fixture}")

        private_codex_home = fixture / "codex-home"
        private_codex_home.mkdir()
        (private_codex_home / "config.toml").write_bytes(
            b"project_doc_max_bytes = 65536\n"
        )
        if global_agents is not None:
            (private_codex_home / "AGENTS.md").write_bytes(global_agents)

        repository = fixture / "repo"
        repository.mkdir()
        subprocess.run(
            ("git", "init", "-q", str(repository)),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=True,
        )
        fixture_env = os.environ.copy()
        fixture_env["CODEX_HOME"] = str(private_codex_home)
        yield repository, fixture_env
    finally:
        safe_fixture = fixture.resolve()
        if (
            safe_fixture.parent != temp_root
            or not safe_fixture.name.startswith("codex-instruction-boundary-")
        ):
            raise RuntimeError(f"refusing unsafe fixture cleanup: {safe_fixture}")
        shutil.rmtree(safe_fixture)
        if safe_fixture.exists():
            raise RuntimeError(f"fixture cleanup failed: {safe_fixture}")


class CodexInstructionGovernanceTest(unittest.TestCase):
    def assert_repo_relative_file(self, spec: str) -> tuple[Path, str]:
        source_name, _, fragment = spec.partition("#")
        self.assertTrue(source_name, spec)
        self.assertNotIn("\\", source_name, f"use repo-relative POSIX separators: {spec}")
        self.assertIsNone(re.match(r"^[A-Za-z]:", source_name), spec)

        relative = PurePosixPath(source_name)
        self.assertFalse(relative.is_absolute(), spec)
        self.assertNotIn("..", relative.parts, spec)
        self.assertEqual(source_name, relative.as_posix(), spec)

        resolved = (ROOT / Path(*relative.parts)).resolve()
        try:
            resolved.relative_to(ROOT.resolve())
        except ValueError:
            self.fail(f"route escapes repository root: {spec}")
        self.assertTrue(resolved.is_file(), spec)

        cursor = ROOT
        for part in relative.parts:
            self.assertIn(
                part,
                {entry.name for entry in cursor.iterdir()},
                f"route casing differs from the actual directory entry: {spec}",
            )
            cursor /= part

        tracked = subprocess.run(
            ("git", "ls-files", "--", source_name),
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        ).stdout.splitlines()
        if source_name not in tracked:
            untracked = subprocess.run(
                ("git", "ls-files", "--others", "--exclude-standard", "--", source_name),
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=30,
                check=False,
            ).stdout.splitlines()
            self.assertIn(
                source_name,
                untracked,
                f"route path is neither Git-canonical tracked nor explicitly untracked: {spec}",
            )
        return resolved, fragment

    def test_user_config_sets_64_kib_project_instruction_capacity(self):
        self.assertTrue(CODEX_CONFIG.is_file(), str(CODEX_CONFIG))
        config = tomllib.loads(CODEX_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(PROJECT_DOC_CAPACITY, config.get("project_doc_max_bytes"))
        self.assertLess(len(PROJECT_AGENTS.read_bytes()), PROJECT_DOC_CAPACITY)

    def test_live_prompt_input_loads_the_project_tail(self):
        # Codex CLI 0.144.1 exposes `debug prompt-input` without the older
        # `--strict-config` wrapper. Exercise only the installed, supported
        # command form; persisted config is verified independently above.
        help_result = subprocess.run(
            (codex_command(), "debug", "prompt-input", "--help"),
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        )
        self.assertEqual(0, help_result.returncode, help_result.stderr[:500])
        self.assertIn("debug prompt-input", help_result.stdout)
        rendered = prompt_input_text(ROOT, "instruction-tail-probe")
        self.assertIn("<!-- END DEMO1-LOCAL-FIRST-RAG -->", rendered)

    def test_boundary_ascii_60_kib_root_and_nested_tails_are_visible(self):
        with codex_boundary_fixture() as (repository, fixture_env):
            nested = repository / "nested"
            nested.mkdir()
            root_size = 20 * 1024
            nested_size = (60 * 1024) - root_size
            write_instruction_fixture(
                repository / "AGENTS.md",
                root_size,
                "BOUNDARY_ASCII_60_ROOT_HEAD_VISIBLE",
                "BOUNDARY_ASCII_60_ROOT_TAIL_VISIBLE",
            )
            write_instruction_fixture(
                nested / "AGENTS.md",
                nested_size,
                "BOUNDARY_ASCII_60_NESTED_HEAD_VISIBLE",
                "BOUNDARY_ASCII_60_NESTED_TAIL_VISIBLE",
            )

            self.assertEqual(60 * 1024, root_size + nested_size)
            self.assertEqual(
                PROJECT_DOC_CAPACITY - (60 * 1024),
                PROJECT_DOC_CAPACITY - root_size - nested_size,
            )
            rendered = prompt_input_text(
                nested,
                "boundary-ascii-60-kib",
                env=fixture_env,
            )
            for marker in (
                "BOUNDARY_ASCII_60_ROOT_HEAD_VISIBLE",
                "BOUNDARY_ASCII_60_ROOT_TAIL_VISIBLE",
                "BOUNDARY_ASCII_60_NESTED_HEAD_VISIBLE",
                "BOUNDARY_ASCII_60_NESTED_TAIL_VISIBLE",
            ):
                self.assertIn(marker, rendered)

    def test_boundary_exact_65536_byte_root_exhausts_nested_budget(self):
        with codex_boundary_fixture() as (repository, fixture_env):
            nested = repository / "nested"
            nested.mkdir()
            root_agents = repository / "AGENTS.md"
            write_instruction_fixture(
                root_agents,
                PROJECT_DOC_CAPACITY,
                "BOUNDARY_EXACT_ROOT_HEAD_VISIBLE",
                "BOUNDARY_EXACT_ROOT_TAIL_VISIBLE",
            )
            write_instruction_fixture(
                nested / "AGENTS.md",
                1024,
                "BOUNDARY_EXACT_NESTED_HEAD_HIDDEN",
                "BOUNDARY_EXACT_NESTED_TAIL_HIDDEN",
            )

            root_size = len(root_agents.read_bytes())
            remaining = PROJECT_DOC_CAPACITY - root_size
            self.assertEqual(PROJECT_DOC_CAPACITY, root_size)
            self.assertEqual(0, remaining)
            rendered = prompt_input_text(
                nested,
                "boundary-exact-project-cap",
                env=fixture_env,
            )
            self.assertIn("BOUNDARY_EXACT_ROOT_HEAD_VISIBLE", rendered)
            self.assertIn("BOUNDARY_EXACT_ROOT_TAIL_VISIBLE", rendered)
            self.assertNotIn("BOUNDARY_EXACT_NESTED_HEAD_HIDDEN", rendered)
            self.assertNotIn("BOUNDARY_EXACT_NESTED_TAIL_HIDDEN", rendered)

    def test_boundary_ascii_66_kib_shares_budget_and_truncates_nested_tail(self):
        with codex_boundary_fixture() as (repository, fixture_env):
            nested = repository / "nested"
            nested.mkdir()
            root_size = 20 * 1024
            nested_size = (66 * 1024) - root_size
            write_instruction_fixture(
                repository / "AGENTS.md",
                root_size,
                "BOUNDARY_ASCII_66_ROOT_HEAD_VISIBLE",
                "BOUNDARY_ASCII_66_ROOT_TAIL_VISIBLE",
            )
            write_instruction_fixture(
                nested / "AGENTS.md",
                nested_size,
                "BOUNDARY_ASCII_66_NESTED_HEAD_VISIBLE",
                "BOUNDARY_ASCII_66_NESTED_TAIL_HIDDEN",
            )

            remaining_for_nested = PROJECT_DOC_CAPACITY - root_size
            self.assertEqual(66 * 1024, root_size + nested_size)
            self.assertEqual(44 * 1024, remaining_for_nested)
            self.assertEqual(2 * 1024, nested_size - remaining_for_nested)
            rendered = prompt_input_text(
                nested,
                "boundary-ascii-66-kib",
                env=fixture_env,
            )
            self.assertIn("BOUNDARY_ASCII_66_ROOT_HEAD_VISIBLE", rendered)
            self.assertIn("BOUNDARY_ASCII_66_ROOT_TAIL_VISIBLE", rendered)
            self.assertIn("BOUNDARY_ASCII_66_NESTED_HEAD_VISIBLE", rendered)
            self.assertNotIn("BOUNDARY_ASCII_66_NESTED_TAIL_HIDDEN", rendered)

    def test_boundary_global_agents_uses_separate_budget_from_exact_project_cap(self):
        global_agents = (
            b"# Private Global Instructions\n\n"
            b"BOUNDARY_GLOBAL_SEPARATE_BUDGET_VISIBLE\n"
        )
        with codex_boundary_fixture(global_agents=global_agents) as (
            repository,
            fixture_env,
        ):
            root_agents = repository / "AGENTS.md"
            write_instruction_fixture(
                root_agents,
                PROJECT_DOC_CAPACITY,
                "BOUNDARY_GLOBAL_PROJECT_HEAD_VISIBLE",
                "BOUNDARY_GLOBAL_PROJECT_TAIL_VISIBLE",
            )

            self.assertEqual(PROJECT_DOC_CAPACITY, len(root_agents.read_bytes()))
            rendered = prompt_input_text(
                repository,
                "boundary-global-separate-budget",
                env=fixture_env,
            )
            self.assertIn("BOUNDARY_GLOBAL_SEPARATE_BUDGET_VISIBLE", rendered)
            self.assertIn("BOUNDARY_GLOBAL_PROJECT_HEAD_VISIBLE", rendered)
            self.assertIn("BOUNDARY_GLOBAL_PROJECT_TAIL_VISIBLE", rendered)

    def test_boundary_same_directory_override_replaces_agents(self):
        with codex_boundary_fixture() as (repository, fixture_env):
            write_instruction_fixture(
                repository / "AGENTS.md",
                2048,
                "BOUNDARY_OVERRIDE_BASE_HEAD_HIDDEN",
                "BOUNDARY_OVERRIDE_BASE_TAIL_HIDDEN",
            )
            write_instruction_fixture(
                repository / "AGENTS.override.md",
                2048,
                "BOUNDARY_OVERRIDE_HEAD_VISIBLE",
                "BOUNDARY_OVERRIDE_TAIL_VISIBLE",
            )

            rendered = prompt_input_text(
                repository,
                "boundary-same-directory-override",
                env=fixture_env,
            )
            self.assertNotIn("BOUNDARY_OVERRIDE_BASE_HEAD_HIDDEN", rendered)
            self.assertNotIn("BOUNDARY_OVERRIDE_BASE_TAIL_HIDDEN", rendered)
            self.assertIn("BOUNDARY_OVERRIDE_HEAD_VISIBLE", rendered)
            self.assertIn("BOUNDARY_OVERRIDE_TAIL_VISIBLE", rendered)

    def test_boundary_utf8_split_hides_korean_and_post_budget_sentinels(self):
        with codex_boundary_fixture() as (repository, fixture_env):
            nested = repository / "nested"
            nested.mkdir()
            prefix = (
                b"# UTF-8 Boundary Fixture\n\n"
                b"BOUNDARY_UTF8_PRE_SPLIT_VISIBLE\n"
            )
            korean = "가".encode("utf-8")
            suffix = b"\nBOUNDARY_UTF8_POST_SPLIT_HIDDEN\n"
            korean_offset = PROJECT_DOC_CAPACITY - 1
            filler_size = korean_offset - len(prefix)
            self.assertGreaterEqual(filler_size, 0)
            root_payload = prefix + (b"x" * filler_size)
            nested_payload = korean + suffix
            combined_payload = root_payload + nested_payload
            root_agents = repository / "AGENTS.md"
            root_agents.write_bytes(root_payload)
            (nested / "AGENTS.md").write_bytes(nested_payload)

            self.assertEqual(korean_offset, combined_payload.index(korean))
            self.assertEqual(PROJECT_DOC_CAPACITY - 1, korean_offset)
            self.assertEqual(PROJECT_DOC_CAPACITY - 1, len(root_payload))
            self.assertEqual(len(korean) + len(suffix), len(nested_payload))
            self.assertGreater(len(combined_payload), PROJECT_DOC_CAPACITY)
            rendered = prompt_input_text(
                nested,
                "boundary-utf8-split",
                env=fixture_env,
            )
            self.assertIn("BOUNDARY_UTF8_PRE_SPLIT_VISIBLE", rendered)
            self.assertNotIn("BOUNDARY_UTF8_POST_SPLIT_HIDDEN", rendered)
            instruction_start = rendered.index("BOUNDARY_UTF8_PRE_SPLIT_VISIBLE")
            instruction_end = rendered.index("</INSTRUCTIONS>", instruction_start)
            boundary_slice = rendered[instruction_start:instruction_end]
            self.assertNotIn("\uac00", boundary_slice)
            # Decoder replacement at a clipped code point is CLI-version-dependent;
            # the repository contract is that neither the character nor post-cap text leaks.
            self.assertLessEqual(boundary_slice.count("\ufffd"), 1)

    def test_global_template_is_byte_identical_and_has_bounded_authority(self):
        self.assertTrue(GLOBAL_TEMPLATE.is_file(), str(GLOBAL_TEMPLATE))
        self.assertTrue(GLOBAL_AGENTS.is_file(), str(GLOBAL_AGENTS))
        template = GLOBAL_TEMPLATE.read_bytes()
        self.assertEqual(template, GLOBAL_AGENTS.read_bytes())
        text = template.decode("utf-8")

        required = (
            "Windows 10 Desktop",
            "PowerShell",
            "closest project `AGENTS.md`",
            "counts, hashes, and reason codes",
            "Browser, Computer, Supabase",
            "maximum budgets",
            "cannot prove current branch, build, runtime, ownership, authority, or external state",
            "including changes to existing dependency declarations or public contracts",
            "adding a new production dependency",
            "live credential, ACL, database, or production mutation",
            "commit, push, deploy",
            "operation-level authority",
            "Before asking the user",
            "Repository-mandated preflight roles",
            "bounded read-only evidence collection",
            "explicitly requested multi-node orchestration",
            "do not count against the lane limit",
            "Do not stack an equivalent review",
            "fallback only when no closer repository review or preflight contract exists",
            "holdScope",
            "firstBlockingRule",
            "blockingEvidence",
            "independentWorkCompleted",
            "repositoryWideHold",
            "SUPPORT_CONTRACT",
            "SUPPORT_SCENARIO",
            "FALSIFY",
            "NEUTRAL",
            "APPLY | HOLD | REJECT",
        )
        for marker in required:
            with self.subTest(marker=marker):
                self.assertIn(marker, text)

        for forbidden in (
            "/goal",
            r"C:\AbandonWare",
            "9시간",
            "Return exactly this structure",
            "Current Source Evidence Snapshot",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)

    def test_frozen_legacy_matrix_covers_44_bullets_and_50_route_identities(self):
        self.assertEqual(set(range(75, 119)), set(LEGACY_BULLET_SHA256))
        self.assertEqual(set(EXPECTED_ROUTES), set(LEGACY_LINE_BY_ROUTE))
        self.assertEqual(set(EXPECTED_ROUTES), set(REQUIRED_TRIGGER_MARKERS))
        self.assertTrue(
            all(2 <= len(markers) <= 6 for markers in REQUIRED_TRIGGER_MARKERS.values())
        )
        referenced_sections = {
            ref for refs in EXPECTED_CONTRACT_REFS.values() for ref in refs
        }
        self.assertEqual(
            {ref for ref in referenced_sections if not ref.startswith("AGENTS.md#")},
            set(NON_ROOT_SECTION_MARKERS),
        )
        self.assertTrue(
            {
                ref.partition("#")[2]
                for ref in referenced_sections
                if ref.startswith("AGENTS.md#")
            }.issubset(ROOT_SECTION_MARKERS)
        )
        self.assertEqual(50, len(LEGACY_LINE_BY_ROUTE))
        self.assertEqual(set(range(76, 119)), set(LEGACY_LINE_BY_ROUTE.values()))
        expanded_counts = {
            line: list(LEGACY_LINE_BY_ROUTE.values()).count(line)
            for line in set(LEGACY_LINE_BY_ROUTE.values())
        }
        self.assertEqual(
            {97: 5, 102: 2, 109: 2, 110: 2},
            {line: count for line, count in expanded_counts.items() if count > 1},
        )
        for line, digest in LEGACY_BULLET_SHA256.items():
            with self.subTest(line=line):
                self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_current_artifacts_satisfy_frozen_governance_contract(self):
        rows = routing_rows(ROUTING_INDEX.read_text(encoding="utf-8"))
        config = tomllib.loads(CODEX_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(
            [],
            governance_error_codes(
                PROJECT_AGENTS.read_text(encoding="utf-8"),
                rows,
                config.get("project_doc_max_bytes"),
            ),
        )

    def test_governance_mutations_emit_specific_error_codes(self):
        baseline_rows = golden_routing_rows()
        self.assertEqual(
            [],
            governance_error_codes(GOLDEN_ROOT_TEXT, baseline_rows, PROJECT_DOC_CAPACITY),
        )

        cases = (
            (
                "delete-route",
                "ROUTE_MISSING",
                {"delete": ("prompt-pack", "demo1_orch_debug_scan")},
            ),
            (
                "same-namespace-normalized-duplicate",
                "ROUTE_NORMALIZED_DUPLICATE",
                {"duplicateNormalized": ("prompt-pack", "demo1_p0_safe_patch_orchestrator")},
            ),
            (
                "wrong-source",
                "ROUTE_SOURCE_MISMATCH",
                {
                    "identity": ("prompt-pack", "demo1_p0_safe_patch_orchestrator"),
                    "field": "source",
                    "value": "AGENTS.md",
                },
            ),
            (
                "missing-paired-artifact",
                "ROUTE_PAIRED_ARTIFACT_MISSING",
                {
                    "identity": ("prompt-pack", "demo1_p0_safe_patch_orchestrator"),
                    "field": "pairedArtifact",
                    "value": "missing/paired-artifact.md",
                },
            ),
            (
                "delete-decision-contract-refs",
                "CONTRACT_REFS_MISSING",
                {
                    "identity": ("skill", "demo1-source-edit-three-way-preflight"),
                    "deleteField": "contractRefs",
                },
            ),
            (
                "weaken-mutation-gate-load-policy",
                "MUTATION_GATE_ON_DEMAND",
                {
                    "identity": ("skill", "demo1-source-edit-three-way-preflight"),
                    "field": "loadPolicy",
                    "value": "on-demand",
                },
            ),
            (
                "delete-root-on-demand-load-rule",
                "ROOT_INDEX_CATALOG_MISSING",
                {"removeRootClause": ROOT_INDEX_LOAD_CLAUSES[0]},
            ),
            (
                "delete-negative-query-gate",
                "THREE_QUERY_NEGATIVE_MISSING",
                {"removeRootClause": "NEGATIVE_QUERY"},
            ),
            (
                "collapse-normalized-prompt-skill-companion",
                "NORMALIZED_COMPANION_COLLAPSED",
                {
                    "identity": ("prompt-pack", "demo1_mcp_control_tower"),
                    "field": "canonicalId",
                    "value": "demo1-mcp-control-tower",
                },
            ),
            (
                "re-expose-legacy-macsrc-output",
                "LEGACY_MACSRC_USER_VISIBLE",
                {"appendRoot": "\nUser-facing output mode: `MACSRC_SMB_DIRECT`."},
            ),
            (
                "unlink-ydrive-guard",
                "YDRIVE_GUARD_UNLINKED",
                {"removeRootClause": "shared source lease"},
            ),
            (
                "reduce-project-doc-capacity",
                "PROJECT_DOC_CAPACITY_MISMATCH",
                {"configValue": 32_768},
            ),
        )

        for name, expected_code, mutation in cases:
            with self.subTest(name=name, expectedCode=expected_code):
                rows = copy.deepcopy(baseline_rows)
                root_text = GOLDEN_ROOT_TEXT
                config_value = PROJECT_DOC_CAPACITY

                identity = mutation.get("identity")
                if "delete" in mutation:
                    rows = [
                        row
                        for row in rows
                        if (row["kind"], row["canonicalId"]) != mutation["delete"]
                    ]
                elif "duplicateNormalized" in mutation:
                    duplicate_identity = mutation["duplicateNormalized"]
                    duplicate = copy.deepcopy(
                        next(
                            row
                            for row in rows
                            if (row["kind"], row["canonicalId"]) == duplicate_identity
                        )
                    )
                    duplicate["canonicalId"] = str(duplicate["canonicalId"]).replace("_", "-")
                    rows.append(duplicate)
                elif identity is not None:
                    row = next(
                        row
                        for row in rows
                        if (row["kind"], row["canonicalId"]) == identity
                    )
                    if "deleteField" in mutation:
                        del row[mutation["deleteField"]]
                    else:
                        row[mutation["field"]] = mutation["value"]

                if "removeRootClause" in mutation:
                    clause = mutation["removeRootClause"]
                    root_text = root_text.replace(str(clause), "", 1)
                if "appendRoot" in mutation:
                    root_text += str(mutation["appendRoot"])
                if "configValue" in mutation:
                    config_value = mutation["configValue"]

                errors = governance_error_codes(root_text, rows, config_value)
                self.assertIn(expected_code, errors, errors)

        weakened_section = GOLDEN_ROOT_TEXT.replace(
            "Never emit fake search results", "", 1
        )
        semantic_errors = governance_error_codes(
            weakened_section, baseline_rows, PROJECT_DOC_CAPACITY
        )
        self.assertIn("CONTRACT_WEAKENED", semantic_errors, semantic_errors)

    def test_semantic_contradictions_emit_specific_error_codes(self):
        baseline_root = PROJECT_AGENTS.read_text(encoding="utf-8")
        baseline_rows = routing_rows(ROUTING_INDEX.read_text(encoding="utf-8"))
        baseline_config = tomllib.loads(CODEX_CONFIG.read_text(encoding="utf-8")).get(
            "project_doc_max_bytes"
        )

        cases = (
            (
                "optional-three-query-preflight",
                "run exactly",
                "may optionally run exactly",
                "THREE_QUERY_GATE_MISSING",
            ),
            (
                "expand-three-query-preflight-to-excluded-work",
                "Do not trigger this gate for read-only, Markdown-only, or test-only work.",
                "Trigger this gate for read-only, Markdown-only, and test-only work.",
                "THREE_QUERY_GATE_MISSING",
            ),
            (
                "make-ydrive-direct-gates-optional",
                "Require a declared target set, current boundary evidence, shared source lease, "
                "preimage verification immediately before `apply_patch`, focused verification, "
                "postimage hashes, and count-only secret results.",
                "Treat a declared target set, current boundary evidence, shared source lease, "
                "preimage verification immediately before `apply_patch`, focused verification, "
                "postimage hashes, and count-only secret results as optional.",
                "YDRIVE_GUARD_UNLINKED",
            ),
            (
                "continue-on-ydrive-direct-gate-failures",
                "HOLD on backing-share identity mismatch, index lock, changed preimage, lease "
                "collision, reparse traversal, secret risk, or failed verification.",
                "Continue on backing-share identity mismatch, index lock, changed preimage, lease "
                "collision, reparse traversal, secret risk, or failed verification.",
                "YDRIVE_GUARD_UNLINKED",
            ),
            (
                "invert-ydrive-source-write-boundary",
                "`YDRIVE_SMB_GUARDED_DIRECT` restricts the application-source write destination, "
                "not access.",
                "`YDRIVE_SMB_GUARDED_DIRECT` does not restrict the application-source write "
                "destination, only access.",
                "YDRIVE_GUARD_UNLINKED",
            ),
            (
                "invert-prompt-builder-boundary",
                "Final RAG prompt construction must stay on",
                "Final RAG prompt construction must not stay on",
                "CONTRACT_WEAKENED",
            ),
            (
                "invert-lane-local-hold-boundary",
                "Keep a blocker lane-local and continue independent provable work; never expand a "
                "lane-local blocker into a repository-wide `HOLD`.",
                "Do not keep a blocker lane-local; always expand a lane-local blocker into a "
                "repository-wide `HOLD`.",
                "CONTRACT_WEAKENED",
            ),
            (
                "weaken-one-active-patchdrop-v3-bundle",
                "allows exactly one active top-level cumulative patch per slug",
                "does not require exactly one active top-level cumulative patch per slug",
                "CONTRACT_WEAKENED",
            ),
            (
                "allow-raw-secret-results",
                "count-only secret results.",
                "count-only secret results are optional; raw secret results are allowed.",
                "YDRIVE_GUARD_UNLINKED",
            ),
            (
                "return-legacy-macsrc-mode",
                None,
                "Return MACSRC_SMB_DIRECT to users.",
                "LEGACY_MACSRC_USER_VISIBLE",
            ),
            (
                "print-legacy-macsrc-mode",
                None,
                "Print MACSRC_SMB_DIRECT for the user.",
                "LEGACY_MACSRC_USER_VISIBLE",
            ),
            (
                "deliver-legacy-macsrc-mode",
                None,
                "The user should receive MACSRC_SMB_DIRECT.",
                "LEGACY_MACSRC_USER_VISIBLE",
            ),
        )

        for name, source, replacement, expected_code in cases:
            with self.subTest(name=name, expectedCode=expected_code):
                self.assertEqual(
                    [],
                    governance_error_codes(
                        baseline_root,
                        baseline_rows,
                        baseline_config,
                    ),
                )
                if source is None:
                    self.assertEqual(0, baseline_root.count(replacement))
                    mutated_root = f"{baseline_root}\n{replacement}\n"
                    self.assertEqual(1, mutated_root.count(replacement))
                else:
                    self.assertEqual(1, baseline_root.count(source))
                    mutated_root = baseline_root.replace(source, replacement, 1)
                self.assertNotEqual(baseline_root, mutated_root)

                errors = governance_error_codes(
                    mutated_root,
                    baseline_rows,
                    baseline_config,
                )
                self.assertIn(expected_code, errors, errors)

    def test_root_routes_through_typed_index_and_keeps_only_mandatory_gates(self):
        text = PROJECT_AGENTS.read_text(encoding="utf-8")
        self.assertIn("`.agents/skills/INDEX.md`", text)
        self.assertNotIn("## Reusable Prompt Packs", text)

        for route_id in MANDATORY_ROOT_ROUTE_IDS:
            required = f"${route_id}"
            with self.subTest(required=required):
                self.assertIn(required, text)

        allowed_route_ids = (
            MANDATORY_ROOT_ROUTE_IDS
            | ROOT_POINTER_COMPANION_IDS
            | ROOT_DURABLE_HELPER_IDS
        )
        for _, route_id in EXPECTED_ROUTES:
            if route_id in allowed_route_ids:
                continue
            with self.subTest(catalog_route_removed=route_id):
                self.assertNotIn(route_id, text)
                self.assertNotIn(route_id.replace("/", "\\"), text)
        for legacy_standalone_selector in (
            "demo1_harmony_9h_autonomous_patch",
            "demo1_graphrag_brain_moe_patch",
        ):
            with self.subTest(catalog_route_removed=legacy_standalone_selector):
                self.assertNotIn(legacy_standalone_selector, text)

        self.assertEqual(1, text.count("demo1-source-edit-three-way-preflight"))
        effective_routing_texts = (
            text,
            ROUTING_INDEX.read_text(encoding="utf-8"),
            GLOBAL_TEMPLATE.read_text(encoding="utf-8"),
        )
        for query_name in ("POSITIVE_QUERY", "NEGATIVE_QUERY", "NEUTRAL_QUERY"):
            with self.subTest(unique_application_source_referee=query_name):
                self.assertEqual(1, sum(item.count(query_name) for item in effective_routing_texts))

    def test_typed_index_preserves_every_previous_route(self):
        self.assertTrue(ROUTING_INDEX.is_file(), str(ROUTING_INDEX))
        rows = routing_rows(ROUTING_INDEX.read_text(encoding="utf-8"))
        self.assertTrue(rows, "typed routes YAML block missing")
        by_identity: dict[tuple[str, str], dict[str, object]] = {}
        for index, row in enumerate(rows):
            with self.subTest(index=index, canonicalId=row.get("canonicalId")):
                self.assertEqual(ROUTE_SCHEMA_FIELDS, set(row))
                self.assertIn(row["kind"], ALLOWED_KINDS)
                for field in ROUTE_SCHEMA_FIELDS - {"contractRefs"}:
                    self.assertIsInstance(row[field], str)
                    self.assertTrue(str(row[field]).strip())
                self.assertIsInstance(row["contractRefs"], list)
                identity = (row["kind"], row["canonicalId"])
                self.assertNotIn(identity, by_identity, f"duplicate route identity: {identity}")
                by_identity[identity] = row

        self.assertEqual(set(EXPECTED_ROUTES), set(by_identity))
        for identity, expected_source in EXPECTED_ROUTES.items():
            with self.subTest(identity=identity):
                self.assertEqual(expected_source, by_identity[identity]["source"])
                expected_class = (
                    "decision-changing"
                    if identity in DECISION_CHANGING_ROUTES
                    else "route-only"
                )
                self.assertEqual(expected_class, by_identity[identity]["contractClass"])
                self.assertEqual(
                    list(EXPECTED_CONTRACT_REFS.get(identity, ())),
                    by_identity[identity]["contractRefs"],
                )
                self.assertEqual(
                    "mandatory-before-mutation"
                    if identity in MANDATORY_BEFORE_MUTATION_ROUTES
                    else "on-demand",
                    by_identity[identity]["loadPolicy"],
                )
                self.assertEqual("preserved", by_identity[identity]["status"])
                self.assertEqual(
                    EXPECTED_PAIRED_ARTIFACTS[identity],
                    by_identity[identity]["pairedArtifact"],
                )
        for identity, markers in REQUIRED_TRIGGER_MARKERS.items():
            trigger = by_identity[identity]["trigger"].casefold()
            for marker in markers:
                with self.subTest(trigger_identity=identity, marker=marker):
                    self.assertIn(marker.casefold(), trigger)
        ablation_trigger = by_identity[
            ("skill", "demo1-ablation-harmony-tracker")
        ]["trigger"].casefold()
        for trace_key in ABLATION_REQUIRED_TRACE_KEYS:
            with self.subTest(ablation_trace_key=trace_key):
                self.assertIn(trace_key.casefold(), ablation_trigger)

    def test_typed_routes_resolve_and_collide_only_across_namespaces(self):
        self.assertTrue(ROUTING_INDEX.is_file(), str(ROUTING_INDEX))
        rows = routing_rows(ROUTING_INDEX.read_text(encoding="utf-8"))
        prompt_rows = prompt_manifest_by_id()
        seen: set[tuple[str, str]] = set()
        namespaces_by_normalized_id: dict[str, set[str]] = {}

        for row in rows:
            kind = row["kind"]
            canonical_id = row["canonicalId"]
            normalized = normalized_id(canonical_id)
            key = (kind, normalized)
            self.assertNotIn(key, seen, f"same-namespace collision: {key}")
            seen.add(key)
            namespaces_by_normalized_id.setdefault(normalized, set()).add(kind)

            source_spec = row["source"]
            with self.subTest(kind=kind, canonicalId=canonical_id):
                source, fragment = self.assert_repo_relative_file(source_spec)
                if kind == "skill":
                    self.assertEqual(canonical_id, skill_name(source))
                elif kind == "prompt-pack":
                    self.assertIn(canonical_id, prompt_rows)
                    expected = ROOT / "agent-prompts" / str(prompt_rows[canonical_id]["system"])
                    self.assertEqual(expected.resolve(), source.resolve())
                elif kind == "standalone-prompt":
                    self.assertFalse(fragment, source_spec)
                    self.assertEqual(canonical_id, source_spec)
                elif kind == "script/tool":
                    if fragment:
                        self.assertEqual(canonical_id, fragment)
                        self.assertIn(fragment, tool_names(source))
                    else:
                        self.assertEqual(canonical_id, source_spec)

                paired_artifact = row["pairedArtifact"]
                if "/" in paired_artifact or "\\" in paired_artifact:
                    paired_path, paired_fragment = self.assert_repo_relative_file(paired_artifact)
                    if paired_fragment:
                        self.assertEqual(PROMPT_MANIFEST.resolve(), paired_path.resolve())
                        self.assertIn(paired_fragment, prompt_rows)

        companion = normalized_id("demo1_mcp_control_tower")
        self.assertIn(("prompt-pack", companion), seen)
        self.assertIn(("skill", companion), seen)
        cross_namespace_collisions = {
            route_id: kinds
            for route_id, kinds in namespaces_by_normalized_id.items()
            if len(kinds) > 1
        }
        self.assertEqual({companion: {"prompt-pack", "skill"}}, cross_namespace_collisions)


if __name__ == "__main__":
    unittest.main()
