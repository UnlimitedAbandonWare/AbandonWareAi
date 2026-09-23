"""Merge host-local MCP settings and personal skill precedence without clobbering.

Source access is independent of config storage. Producers use local clones;
explicit shared-read setup grants no source mutation authority.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import sys
from pathlib import Path
from typing import Any

try:
    from scripts.awx_shared_state import Change, Conflict, apply_changes, merge_config, read_optional, encode_config, parse_config
    from scripts.awx_host_runtime import local_state_root, host_facts
except ModuleNotFoundError:
    from awx_shared_state import Change, Conflict, apply_changes, merge_config, read_optional, encode_config, parse_config
    from awx_host_runtime import local_state_root, host_facts


SCHEMA_VERSION = "awx.mcp.node_setup.v1"
DESKTOP_CANONICAL = Path("C:/AbandonWare/demo-1/demo-1/src")
ALLOWED_ENV_REFS = (
    "NAVER_KEYS",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
)
SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|"
    r"pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
    r"sbp_[A-Za-z0-9_-]{10,}",
    re.ASCII,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="AWX host-local setup; all personal values are preserved")
    parser.add_argument("--node-role", default="auto", choices=("auto","desktop","macmini","macbook","notebook","read-only"))
    parser.add_argument("--source-root", default=str(Path(__file__).absolute().parents[1]))
    parser.add_argument("--canonical-root", default=str(DESKTOP_CANONICAL))
    parser.add_argument("--output", help="Optional host-local JSON or TOML output")
    parser.add_argument("--format", choices=("json","toml"), default="json")
    parser.add_argument("--register-codex", action="store_true", help="Merge a namespaced server into this host's personal Codex config")
    parser.add_argument("--with-glm", action="store_true", help="Include GLM after this host has a local Java runtime and JAR")
    parser.add_argument("--shared-read", action="store_true", help="Configure shared source reads only; grants no source write authority")
    parser.add_argument("--state-root")
    parser.add_argument("--session-id", default="awx-mcp-node-setup")
    parser.add_argument("--audit-log", default="")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    started=time.monotonic()
    source_root=Path(args.source_root).absolute()
    canonical_root=Path(args.canonical_root).resolve()
    if args.node_role=="auto":args.node_role=host_facts(source_root)["role"]
    try:
        state=local_state_root(source_root,args.state_root)
        kind="toml" if args.register_codex else args.format
        codex_home=local_state_root(source_root,os.environ.get("CODEX_HOME") or Path.home()/".codex")
        output=Path(args.output).absolute() if args.output else (codex_home/"config.toml" if args.register_codex else state/("awx-control-tower.mcp."+kind))
        local_state_root(source_root,output.parent)
        if args.audit_log:local_state_root(source_root,Path(args.audit_log).absolute().parent)
        isolation=source_isolation_evidence(args.source_root,source_root,canonical_root,args.node_role)
        if args.shared_read:
            isolation=shared_read_evidence(source_root,isolation)
        elif args.node_role!="desktop" and isolation["guard"]!="PASS":
            raise Conflict("source-isolation-violation")
        config=render_config(args.node_role,source_root,state,args.shared_read)
        if kind=="toml":
            server=config["mcpServers"]["awx-control-tower"]
            config={"mcp_servers":{"awx-shared":server}}
            if args.with_glm:config['mcp_servers']['glm_agent']=render_glm_config(source_root,state)
        elif args.with_glm:raise Conflict('glm-requires-toml-registration')
        if kind=='toml':
            project_raw=read_optional(source_root/'.codex/config.toml')
            project=parse_config(project_raw,'toml') if project_raw else {}
            for name,definition in config['mcp_servers'].items():
                override=project.get('mcp_servers',{}).get(name)
                if override is not None and override!=definition:raise Conflict('project-mcp-override-conflict')
        text=encode_config(config,kind)
        if SECRET_RE.search(text.decode()):raise Conflict("secret-leak-risk")
        before=read_optional(output)
        after=merge_config(before or (b"{}" if kind=="json" else b""),text,kind)
        try:
            from scripts.awx_skill_registry import scan_skills, personal_priority_config
        except ModuleNotFoundError:
            from awx_skill_registry import scan_skills, personal_priority_config
        registry=scan_skills(source_root/".agents/skills",[Path.home()/".agents/skills",codex_home/"skills"])
        if kind=="toml":after=personal_priority_config(after,source_root/".agents/skills",registry)
        registry_path=state/"skill-registry.json"
        changes=[Change(output,before,after),Change(registry_path,read_optional(registry_path),encode_config(registry,"json"))]
        result=result_base(args,source_root,canonical_root,output,isolation,started)
        if args.dry_run:
            transaction={"status":"planned","changedCount":sum(c.before!=c.after for c in changes)}
        else:
            state.mkdir(parents=True,exist_ok=True)
            transaction=apply_changes(changes,output.parent/".awx-backups")
        result.update({"ok":True,"configPath":str(output),"configHash":stable_hash(after.decode()),
                       "outputCount":transaction["changedCount"],"transaction":transaction,
                       "sharedSkillCount":registry["sharedCount"],"personalSkillCount":registry["personalCount"],
                       "skillConflictCount":registry["conflictCount"],"skillPriorityApplied":kind=="toml",
                       "decision":"mcp_node_setup","failReason":""})
        if not args.dry_run:append_audit(args.audit_log,result)
        print(json.dumps(result,ensure_ascii=True));return 0
    except (Conflict,OSError,ValueError) as error:
        print(json.dumps({"ok":False,"outputCount":0,"decision":"mcp_node_setup_failed",
                          "failReason":str(error) if isinstance(error,Conflict) else "host-setup-validation-failed"}))
        return 1


def shared_read_evidence(root: Path, isolation: dict):
    if os.name=="nt" and str(root).replace("\\","/").lower()=="y:/":
        helper=root/"scripts/verify_ydrive_backing_identity.ps1"
        if not helper.is_file():raise Conflict("smb-root-identity-evidence-needed")
        command=["powershell","-NoProfile","-File",str(helper),"-ExpectedSha256",
                 "30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9"]
        check=subprocess.run(command,capture_output=True,timeout=15)
        evidence=json.loads(check.stdout)
        if check.returncode or evidence.get("backingShareIdentityVerified") is not True:
            raise Conflict("smb-root-identity-changed")
    elif os.name=="posix" and str(root).startswith(("/Volumes/","/mnt/","/media/")):
        evidence={"backingShareIdentityVerified":False,"backingShareIdentityReason":"evidence-needed"}
    else:raise Conflict("shared-read-root-not-recognized")
    if not all((root/name).is_file() for name in ("AGENTS.md","settings.gradle.kts","scripts/awx_mcp_stdio_server.py")):
        raise Conflict("shared-source-sentinels-missing")
    return {**isolation,**evidence,"guard":"PASS","sourceWriteRoot":None,"authorizedMutation":False,
            "accessMode":"shared-read","gitRootHash":""}


def render_glm_config(root: Path, state: Path):
    try:
        from scripts.awx_host_runtime import runtime_command
    except ModuleNotFoundError:
        from awx_host_runtime import runtime_command
    runtime_command(root,'glm',state)
    common=parse_config((root/'.codex/shared-runtime.json').read_bytes(),'json')['glm']
    return {**common,'command':sys.executable,
            'args':['-B',str(root/'scripts/awx_host_runtime.py'),'run','--root',str(root),'--runtime','glm','--state-root',str(state)],
            'cwd':str(state)}


def render_config(node_role: str, source_root: Path, state: Path | None = None, shared_read: bool = False) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "mcpServers": {
            "awx-control-tower": {
                "command": sys.executable,
                "args": ['-B', str(source_root / 'scripts' / 'awx_host_runtime.py'), 'run', '--root', str(source_root), '--state-root', str(state or local_state_root(source_root))],
                "cwd": str(state or local_state_root(source_root)),
                "env": {
                    "AWX_AGENT_HOST": node_role,
                    "AWX_MCP_NODE_ROLE": node_role,
                    "AWX_MCP_SOURCE_ACCESS": "shared-read" if shared_read else "guarded",
                },
            }
        },
        "allowedEnvRefs": list(ALLOWED_ENV_REFS),
        "sourcePolicy": {
            "desktopFinalProof": "evidence_needed",
            "producerCwd": "producer-local-worktree-or-clone",
            "forbiddenCwd": "Desktop canonical source root or shared SMB/NAS mount",
        },
    }


def result_base(
    args: argparse.Namespace,
    source_root: Path,
    canonical_root: Path,
    output_path: Path,
    source_isolation: dict[str, Any],
    started: float,
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "requestId": "mcp-node-setup",
        "sessionId": safe_scalar(args.session_id, 96),
        "nodeRole": args.node_role,
        "toolName": "mcp_node_setup",
        "sourceRootHash": stable_hash(str(source_root)),
        "canonicalRootHash": stable_hash(str(canonical_root)),
        "outputPathHash": stable_hash(str(output_path)),
        "inputHash": stable_hash(
            json.dumps(
                {
                    "nodeRole": args.node_role,
                    "sourceRootHash": stable_hash(str(source_root)),
                    "canonicalRootHash": stable_hash(str(canonical_root)),
                    "outputPathHash": stable_hash(str(output_path)),
                },
                sort_keys=True,
            )
        ),
        "sourceIsolation": source_isolation,
        "allowedEnvRefs": list(ALLOWED_ENV_REFS),
        "desktopFinalProof": "evidence_needed",
        "rawSecretPatternHits": 0,
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
    }


def source_isolation_evidence(raw_root: str, source_root: Path, canonical_root: Path, node_role: str) -> dict[str, Any]:
    shared_source = is_shared_source_path(raw_root)
    desktop_canonical = is_relative_to_path(source_root, canonical_root)
    git_root = git_toplevel(source_root)
    git_root_matches_source = git_root is not None and normalized_path(git_root) == normalized_path(source_root)
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
    normalized = stripped.replace("\\", "/").lower()
    if normalized.startswith(("//","y:/")):
        return True
    parts = [part for part in normalized.split("/") if part]
    if "patchdrop" in parts or "__patch_drop__" in parts:
        return True
    return normalized.startswith(("/volumes/", "/mnt/", "/media/"))


def git_toplevel(source_root: Path) -> Path | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(source_root), "rev-parse", "--show-toplevel"],
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


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def safe_scalar(value: Any, limit: int) -> str:
    text = str(value or "").replace("\r", " ").replace("\n", " ").strip()
    return text[:limit]


def append_audit(audit_log: str, result: dict[str, Any]) -> None:
    if not audit_log.strip():
        return
    path = Path(audit_log).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {
        "requestId": safe_scalar(result.get("requestId", ""), 96),
        "sessionId": safe_scalar(result.get("sessionId", ""), 96),
        "nodeRole": safe_scalar(result.get("nodeRole", ""), 48),
        "toolName": "mcp_node_setup",
        "inputHash": safe_scalar(result.get("inputHash", ""), 96),
        "outputCount": int(result.get("outputCount", 0) or 0),
        "elapsedMs": int(result.get("elapsedMs", 0) or 0),
        "decision": safe_scalar(result.get("decision", ""), 80),
        "failReason": safe_scalar(result.get("failReason", ""), 160),
    }
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=True, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
