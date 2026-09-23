#!/usr/bin/env python3
"""Plan-only control-tower pipeline probe for AWX PatchDrop work.

This runner intentionally does not edit source. It gives producer nodes and the
Desktop a stable MCP-style command target for the safe PatchDrop sequence:
generate producer evidence, submit sidecars, ingest on Desktop, then audit.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "awx.mcp.control_tower_pipeline.v1"
SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|"
    r"pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
    r"sbp_[A-Za-z0-9_-]{10,}",
    re.ASCII,
)
REQUIRED_LOCAL_FILES = (
    "scripts/awx_mcp_toolbox.py",
    "scripts/awx_mcp_node_smoke.py",
    "scripts/awx_mcp_producer_handoff.py",
    "scripts/awx_mcp_completion_audit.py",
    "__patch_drop__/producer_bundle.py",
    "__patch_drop__/producer_bundle.ps1",
    "main/resources/mcp/awx-control-tower-tools.json",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="AWX MCP control-tower pipeline probe")
    parser.add_argument("--root", default=".", help="Desktop canonical root or producer-local worktree.")
    parser.add_argument("--patchdrop-root", default="", help="PatchDrop root to inspect or target.")
    parser.add_argument("--topic", default="mcp-stdio-bridge-verification")
    parser.add_argument("--node-role", default="desktop", choices=("desktop", "macmini", "notebook", "read-only"))
    parser.add_argument("--session-id", default="awx-mcp-control-tower-pipeline")
    parser.add_argument("--plan-only", action="store_true", help="Emit the safe command plan without running steps.")
    args = parser.parse_args()

    started = time.monotonic()
    root = Path(args.root).resolve()
    patchdrop_root = Path(args.patchdrop_root).resolve() if args.patchdrop_root else root / "__patch_drop__"
    missing_files = [rel for rel in REQUIRED_LOCAL_FILES if not (root / rel).is_file()]
    tools = {
        "toolbox": (root / "scripts" / "awx_mcp_toolbox.py").is_file(),
        "node_smoke": (root / "scripts" / "awx_mcp_node_smoke.py").is_file(),
        "producer_handoff": (root / "scripts" / "awx_mcp_producer_handoff.py").is_file(),
        "completion_audit": (root / "scripts" / "awx_mcp_completion_audit.py").is_file(),
        "producer_bundle_py": (root / "__patch_drop__" / "producer_bundle.py").is_file(),
        "producer_bundle_ps1": (root / "__patch_drop__" / "producer_bundle.ps1").is_file(),
    }
    evidence_needed = [
        f"{rel} / control tower pipeline local dependency missing" for rel in missing_files
    ]
    if args.node_role in {"macmini", "notebook", "read-only"} and is_shared_source_root(root):
        evidence_needed.append(
            "producer-local worktree / do not run producer pipeline from Desktop canonical or shared root"
        )

    commands = {
        "producer_kit_export": (
            "python scripts/awx_mcp_toolbox.py producer_kit_export "
            "< JSON payload with root, patchdrop_root, topic >"
        ),
        "producer_node_smoke": (
            "python scripts/awx_mcp_node_smoke.py --root <producer-worktree> "
            "--canonical-root C:/AbandonWare/demo-1/demo-1/src --node-role <macmini|notebook>"
        ),
        "producer_handoff": (
            "python scripts/awx_mcp_producer_handoff.py --source-root <producer-worktree> "
            "--canonical-root C:/AbandonWare/demo-1/demo-1/src --node-role <macmini|notebook> "
            "--topic "
            + shell_token(args.topic)
            + " --pathspec <changed-files>"
        ),
        "desktop_external_intake": (
            "python scripts/awx_mcp_toolbox.py external_evidence_intake "
            "< JSON payload with patchdrop_root and required_roles >"
        ),
        "desktop_external_audit": (
            "python scripts/awx_mcp_toolbox.py external_evidence_audit "
            "< JSON payload with patchdrop_root and required_roles >"
        ),
        "desktop_completion_audit": (
            "python scripts/awx_mcp_completion_audit.py --root . "
            "--output var/codex-smoke/awx-mcp-completion-audit-control-tower-current.json"
        ),
    }
    steps = [
        {
            "step": "producer-kit-export",
            "owner": "desktop",
            "sourceWrites": False,
            "desktopFinalProof": "evidence_needed",
        },
        {
            "step": "producer-node-smoke",
            "owner": "macmini|notebook",
            "sourceWrites": False,
            "desktopFinalProof": "evidence_needed",
        },
        {
            "step": "producer-handoff-bundle",
            "owner": "macmini|notebook",
            "sourceWrites": "producer-worktree-only",
            "requiredSidecars": [".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json"],
            "desktopFinalProof": "evidence_needed",
        },
        {
            "step": "desktop-external-evidence-intake",
            "owner": "desktop",
            "sourceWrites": False,
            "desktopFinalProof": "evidence_needed",
        },
        {
            "step": "desktop-final-verify",
            "owner": "desktop",
            "sourceWrites": "after git apply --check only",
            "desktopFinalProof": "required",
        },
    ]
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "ok": not evidence_needed,
        "requestId": "control-tower-pipeline",
        "sessionId": safe_scalar(args.session_id, 96),
        "nodeRole": args.node_role,
        "rootHash": stable_hash(str(root)),
        "patchDropHash": stable_hash(str(patchdrop_root)),
        "topic": safe_scalar(args.topic, 96),
        "tools": tools,
        "commands": commands,
        "steps": steps,
        "planOnly": bool(args.plan_only),
        "desktopFinalProof": "evidence_needed",
        "evidence_needed": evidence_needed,
        "rawSecretPatternHits": 0,
        "decision": "control_tower_pipeline_plan",
        "failReason": "" if not evidence_needed else "evidence-needed",
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
    }
    raw = json.dumps(result, ensure_ascii=True, separators=(",", ":"))
    result["rawSecretPatternHits"] = len(SECRET_RE.findall(raw))
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return 0 if not evidence_needed else 1


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def safe_scalar(value: Any, limit: int) -> str:
    text = str(value or "").replace("\r", " ").replace("\n", " ").strip()
    return text[:limit]


def shell_token(value: str) -> str:
    text = safe_scalar(value, 128)
    if re.fullmatch(r"[A-Za-z0-9._/-]+", text):
        return text
    return "'" + text.replace("'", "'\"'\"'") + "'"


def is_shared_source_root(root: Path) -> bool:
    text = str(root).replace("\\", "/").lower()
    return (
        "abandonware/demo-1/demo-1/src" in text
        or text.startswith("//")
        or text.startswith("/volumes/")
        or text.startswith("/mnt/")
        or text.startswith("/media/")
    )


if __name__ == "__main__":
    sys.exit(main())
