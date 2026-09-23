#!/usr/bin/env python3
"""Cross-node smoke runner for the AWX MCP-style toolbox.

Runs the read-only/control-plane tool contract from a producer node without
modifying the Desktop canonical source. Missing optional artifacts are reported
as evidence_needed rather than as smoke failures.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "awx.mcp.node_smoke.v1"
SAFE_AUDIT_FIELDS = {
    "requestId",
    "sessionId",
    "nodeRole",
    "toolName",
    "inputHash",
    "outputCount",
    "elapsedMs",
    "decision",
    "failReason",
}
SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|"
    r"pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
    r"sbp_[A-Za-z0-9_-]{10,}",
    re.ASCII,
)


def deployment_public_base_url() -> str:
    return (os.environ.get("APP_PUBLIC_BASE_URL", "").strip() or os.environ.get("PUBLIC_BASE_URL", "").strip())


def main() -> int:
    parser = argparse.ArgumentParser(description="AWX MCP node smoke")
    parser.add_argument("--root", default=".", help="Node-local repo/worktree root to probe.")
    parser.add_argument("--canonical-root", default="", help="Desktop canonical root; used only for restore-block proof.")
    parser.add_argument("--node-role", default="macmini", choices=("desktop", "macmini", "notebook", "read-only"))
    parser.add_argument("--query", default="safe patch evidence")
    parser.add_argument("--session-id", default="awx-mcp-node-smoke")
    args = parser.parse_args()

    started = time.monotonic()
    root = Path(args.root).resolve()
    canonical_root = Path(args.canonical_root).resolve() if args.canonical_root else root
    source_isolation = source_isolation_evidence(args.root, root, canonical_root, args.node_role)
    if args.node_role in {"macmini", "notebook", "read-only"} and source_isolation["guard"] != "PASS":
        result = {
            "schemaVersion": SCHEMA_VERSION,
            "ok": False,
            "requestId": "smoke-node",
            "sessionId": args.session_id,
            "nodeRole": args.node_role,
            "queryHash": stable_hash(args.query),
            "queryLength": len(args.query),
            "sourceRootInputHash": stable_hash(args.root.strip()),
            "rootHash": stable_hash(str(root)),
            "canonicalRootHash": stable_hash(str(canonical_root)),
            "sourceIsolation": source_isolation,
            "steps": [],
            "evidence_needed": ["switch to a producer-local worktree before running node smoke"],
            "rawSecretPatternHits": 0,
            "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
            "decision": "node_smoke_failed",
            "failReason": "source-isolation-violation",
        }
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1
    toolbox = Path(__file__).resolve().with_name("awx_mcp_toolbox.py")
    request_base = {
        "sessionId": args.session_id,
        "nodeRole": args.node_role,
    }

    raw_outputs: list[str] = []
    steps: list[dict[str, Any]] = []
    evidence_needed: list[str] = []
    failures: list[str] = []

    def run_step(tool: str, payload: dict[str, Any]) -> dict[str, Any]:
        payload = {**request_base, **payload}
        payload.setdefault("requestId", f"smoke-{tool}")
        result = invoke_tool(toolbox, root, tool, payload)
        raw_outputs.append(result["raw"])
        data = result["json"] if isinstance(result.get("json"), dict) else {}
        step_evidence = safe_evidence_items(data.get("evidence_needed", ""), 220)
        step = {
            "toolName": str(data.get("toolName") or tool),
            "exitCode": result["exitCode"],
            "ok": bool(data.get("ok", result["exitCode"] == 0)),
            "decision": safe_scalar(data.get("decision", ""), 80),
            "failReason": safe_scalar(data.get("failReason", ""), 80),
            "localFallbackPresent": isinstance(data.get("localFallback"), dict),
            "outputCount": int(data.get("outputCount", 0) or 0),
            "elapsedMs": max(0, int(data.get("elapsedMs", 0) or 0)),
            "evidence_needed": "; ".join(step_evidence),
        }
        steps.append(step)
        evidence_needed.extend(step_evidence)
        if result["exitCode"] != 0:
            failures.append(f"{tool}:exitCode={result['exitCode']}")
        return data

    run_step("source_scan", {"root": str(root)})
    public_base_url = deployment_public_base_url()
    runtime_base_url = os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL", "").strip() or public_base_url
    trace_base_url = os.environ.get("AWX_TRACE_SNAPSHOT_BASE_URL", "").strip() or runtime_base_url
    local_runtime_probe = {
        "root": str(root),
        "base_url": runtime_base_url or "http://127.0.0.1:1",
        "timeout_sec": 1,
    }
    run_step("agent_db_snapshot", local_runtime_probe)
    trace_runtime_probe = {**local_runtime_probe, "base_url": trace_base_url or local_runtime_probe["base_url"], "limit": 3}
    run_step("trace_snapshot_probe", trace_runtime_probe)
    run_step("supabase_context_probe", {"root": str(root), "timeout_sec": 5})
    with tempfile.TemporaryDirectory(prefix="awx-mcp-supabase-snapshot-") as snapshot_tmp_raw:
        snapshot_path = Path(snapshot_tmp_raw) / "supabase-schema-snapshot.json"
        run_step(
            "supabase_schema_snapshot",
            {
                "root": str(root),
                "output_path": str(snapshot_path),
                "timeout_sec": 5,
            },
        )
    archive_payload = {
        "q": args.query,
        "filters": {},
        "top_k": 3,
    }
    root_archive_index = root / "BackupsXS" / "index.jsonl"
    if root_archive_index.exists():
        archive_payload["index_path"] = str(root_archive_index)
    run_step("archive_search", archive_payload)
    run_step(
        "patch_plan",
        {
            "findings": [{"source": "node_smoke", "class": "mcp-contract"}],
            "target_files": [],
        },
    )
    run_step("patch_render", {"topic": "mcp-node-smoke"})
    run_step("boot_verify", {"root": str(root)})

    with tempfile.TemporaryDirectory(prefix="awx-mcp-node-smoke-") as tmp_raw:
        tmp = Path(tmp_raw)
        build_log = tmp / "build.log"
        build_log.write_text("error: cannot find symbol\n", encoding="utf-8")
        audit_log = tmp / "audit.ndjson"
        archive = tmp / "BackupsXS"
        archive_file = archive / "__awx_mcp_smoke_forbidden__" / "blocked.txt"
        archive_file.parent.mkdir(parents=True, exist_ok=True)
        archive_file.write_text("blocked restore content", encoding="utf-8")

        run_step("build_error_mine", {"log_path": str(build_log)})
        run_step("run_pipeline", {"root": str(root)})
        restore_target = (
            canonical_root
            if args.node_role in {"macmini", "notebook", "read-only"}
            else tmp / "restore-target"
        )
        forbidden_dest = restore_target / "__awx_mcp_smoke_forbidden__" / "blocked.txt"
        forbidden_preexisting = forbidden_dest.exists()
        forbidden_before_hash = sha256_file(forbidden_dest) if forbidden_preexisting else ""
        restore = run_step(
            "archive_restore",
            {
                "mode": "restore",
                "archive_root": str(archive),
                "glob": "__awx_mcp_smoke_forbidden__/*.txt",
                "target_dir": str(restore_target),
                "canonical_root": str(canonical_root),
                "audit_log": str(audit_log),
            },
        )
        if args.node_role in {"macmini", "notebook", "read-only"}:
            if restore.get("decision") != "restore_target_blocked" or restore.get("failReason") != "smb-conflict-risk":
                failures.append("archive_restore:canonical_restore_not_blocked")
            forbidden_after_exists = forbidden_dest.exists()
            forbidden_after_hash = sha256_file(forbidden_dest) if forbidden_after_exists else ""
            if not forbidden_preexisting and forbidden_after_exists:
                failures.append("archive_restore:forbidden_target_written")
                try:
                    forbidden_dest.unlink()
                    forbidden_dest.parent.rmdir()
                except OSError:
                    pass
            elif forbidden_preexisting and (not forbidden_after_exists or forbidden_after_hash != forbidden_before_hash):
                failures.append("archive_restore:forbidden_target_mutated")
        if not audit_log.exists():
            failures.append("archive_restore:audit_missing")
        elif not audit_is_safe(audit_log):
            failures.append("archive_restore:audit_unsafe")

    combined_raw = "\n".join(raw_outputs)
    raw_secret_hits = len(SECRET_RE.findall(combined_raw))
    if raw_secret_hits:
        failures.append("secret-leak-risk")

    evidence_needed = sorted({item for item in evidence_needed if item})
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "ok": not failures,
        "requestId": "smoke-node",
        "sessionId": args.session_id,
        "nodeRole": args.node_role,
        "queryHash": stable_hash(args.query),
        "queryLength": len(args.query),
        "sourceRootInputHash": stable_hash(args.root.strip()),
        "rootHash": stable_hash(str(root)),
        "canonicalRootHash": stable_hash(str(canonical_root)),
        "sourceIsolation": source_isolation,
        "steps": steps,
        "evidence_needed": evidence_needed,
        "rawSecretPatternHits": raw_secret_hits,
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
        "decision": "node_smoke" if not failures else "node_smoke_failed",
        "failReason": ",".join(failures),
    }
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return 0 if result["ok"] else 1


def invoke_tool(toolbox: Path, root: Path, tool: str, payload: dict[str, Any]) -> dict[str, Any]:
    proc = subprocess.run(
        [sys.executable, str(toolbox), tool],
        input=json.dumps(payload, ensure_ascii=True, separators=(",", ":")),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(root),
        check=False,
    )
    raw = (proc.stdout or "") + (proc.stderr or "")
    parsed: Any = None
    try:
        parsed = json.loads(proc.stdout)
    except json.JSONDecodeError:
        parsed = {
            "ok": False,
            "toolName": tool,
            "decision": "json_parse_failed",
            "failReason": "json-parse",
        }
    return {"exitCode": proc.returncode, "raw": raw, "json": parsed}


def audit_is_safe(path: Path) -> bool:
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            return False
        if not isinstance(row, dict):
            return False
        if set(row.keys()) - SAFE_AUDIT_FIELDS:
            return False
    return True


def safe_scalar(value: Any, limit: int) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\r", " ").replace("\n", " ").strip()
    return text[:limit]


def safe_evidence_items(value: Any, limit: int) -> list[str]:
    if value is None or value == "":
        return []
    if isinstance(value, (list, tuple)):
        return [item for item in (safe_scalar(entry, limit) for entry in value) if item]
    item = safe_scalar(value, limit)
    return [item] if item else []


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def sha256_file(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return ""


def source_isolation_evidence(raw_root: str, root: Path, canonical_root: Path, node_role: str) -> dict[str, Any]:
    shared_source = is_shared_source_path(raw_root)
    desktop_canonical = is_relative_to_path(root, canonical_root)
    git_root = git_toplevel(root)
    git_root_matches_source = git_root is not None and normalized_path(git_root) == normalized_path(root)
    if shared_source:
        source_root_kind = "shared-root"
    elif desktop_canonical:
        source_root_kind = "desktop-canonical"
    elif git_root is None:
        source_root_kind = "not-git-root"
    elif not git_root_matches_source:
        source_root_kind = "git-nested-root"
    else:
        source_root_kind = "local-worktree"
    guard = "PASS" if node_role == "desktop" or source_root_kind == "local-worktree" else "FAIL"
    return {
        "guard": guard,
        "sourceRootKind": source_root_kind,
        "sharedSourceRoot": shared_source,
        "desktopCanonicalSourceRoot": desktop_canonical,
        "directCanonicalSourceEdit": desktop_canonical,
        "gitRootPresent": git_root is not None,
        "gitRootMatchesSourceRoot": git_root_matches_source,
        "gitRootHash": stable_hash(str(git_root)) if git_root is not None else "",
    }


def is_relative_to_path(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def is_shared_source_path(raw_path: str) -> bool:
    stripped = raw_path.strip()
    if stripped.startswith("\\\\"):
        return True
    normalized = stripped.replace("\\", "/").lower()
    parts = [part for part in normalized.split("/") if part]
    if "patchdrop" in parts or "__patch_drop__" in parts:
        return True
    return normalized.startswith(("/volumes/", "/mnt/", "/media/"))


def git_toplevel(root: Path) -> Path | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--show-toplevel"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
        )
    except (OSError, ValueError):
        return None
    if result.returncode != 0:
        return None
    text = (result.stdout or "").strip()
    if not text:
        return None
    return Path(text).resolve()


def normalized_path(path: Path) -> str:
    text = str(path.resolve()).replace("\\", "/").rstrip("/")
    return text.lower() if os.name == "nt" else text


if __name__ == "__main__":
    raise SystemExit(main())
