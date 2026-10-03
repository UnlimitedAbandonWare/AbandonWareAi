"""One-shot machine/agent context probe for demo-1 (awx.agent-machine-context.v1).

Run from the project root:

    python -B scripts/agent_machine_context.py [--pretty]
        [--section all|paths|env|tools|db|gpu|git|skills] [--write-report <path>]

Prints exactly one JSON document on stdout so any agent (Devin/Grok/Codex) can
parse it instead of hand-searching env vars, install paths, tool locations, or
DB lane state. Read-only by default; the only write is --write-report (a copy
of the same JSON, confined to the project root).

Secret policy: environment variables are reported by NAME only
(namesPresent / secretLikeNamesPresent). Values of key/token/secret/password-
like variables, .env file contents, and credentials are never read into the
report. Per-section probe failures degrade to an `error`/`evidence_needed`
field, never a crash.

Contract: DEMO1-DEVIN-AGENT-MACHINE-CONTEXT-DX-20260928
"""
from __future__ import annotations

import argparse
from datetime import datetime
import getpass
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

from awx_paths import resolve as _awx_resolve

SCHEMA = "awx.agent-machine-context.v1"
CANONICAL_ROOT = str(_awx_resolve("repo.root"))
SECTIONS = ("all", "paths", "env", "tools", "db", "gpu", "git", "skills")

# Non-secret env names worth surfacing (paths/flags an agent needs).
INTERESTING_ENV = (
    "JAVA_HOME", "OLLAMA_MODELS", "GRADLE_USER_HOME", "ANDROID_HOME",
    "MAVEN_HOME", "NODE_HOME", "PYTHON_HOME", "USERPROFILE", "COMPUTERNAME",
    "AWX_AGENT", "AWX_DB_VIA", "AWX_RAG_NO_PAUSE", "AWX_AGENT_ALLOW_PAID_MODELS",
)
INTERESTING_PREFIXES = ("AWX_", "LMS_", "META_", "DEMO_", "NOVA_")
SECRET_NAME = re.compile(
    r"(key|token|secret|password|passwd|credential|cookie|session|private|auth)",
    re.IGNORECASE)
PREFERRED_GIT = str(_awx_resolve("git.exe"))


def run_probe(argv, cwd=None, timeout=10):
    """Bounded subprocess probe -> (exitCode, stdout, stderr) or None on failure."""
    try:
        out = subprocess.run(argv, cwd=cwd, capture_output=True, text=True,
                             timeout=timeout, errors="replace")
        return out.returncode, out.stdout or "", out.stderr or ""
    except (OSError, subprocess.TimeoutExpired):
        return None


def last_json_line(text):
    """Parse the last non-empty stdout line as JSON (agent JSON contract)."""
    for line in reversed((text or "").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            return json.loads(line)
        except ValueError:
            continue
    return None


def path_field(path):
    p = Path(path)
    return {"path": str(p), "exists": p.exists()}


def section_paths(root):
    home = Path.home()
    fields = {
        "src": path_field(root),
        "startRagBat": path_field(root / "Start-RAG.bat"),
        "gradlew": path_field(root / "gradlew.bat"),
        "buildGradleKts": path_field(root / "build.gradle.kts"),
        "settingsGradleKts": path_field(root / "settings.gradle.kts"),
        "varMetaDisplayDb": path_field(root / "var" / "meta-display-db"),
        "agentHandoff": path_field(root / "data" / "agent-handoff"),
        "skillsRoot": path_field(root / ".agents" / "skills"),
        "downloadsHint": str(home / "Downloads"),
    }
    return fields


def section_env():
    names = set(os.environ)
    secret_like = sorted(n for n in names if SECRET_NAME.search(n))
    interesting = [
        n for n in names
        if n not in secret_like and (
            n in INTERESTING_ENV
            or any(n.startswith(p) for p in INTERESTING_PREFIXES))
    ]
    return {
        "namesPresent": sorted(interesting),
        "secretLikeNamesPresent": secret_like,
        "secretLikeCount": len(secret_like),
        "totalEnvCount": len(names),
        "secretValues": "REDACTED_POLICY",
    }


def _tool(name, extra_paths=(), version_argv=None, timeout=8):
    entry = {"path": None, "version": None}
    for cand in extra_paths:
        if cand and Path(cand).is_file():
            entry["path"] = str(Path(cand))
            break
    if entry["path"] is None:
        found = shutil.which(name)
        if found:
            entry["path"] = found
    if entry["path"] and version_argv:
        res = run_probe([entry["path"], *version_argv], timeout=timeout)
        if res:
            code, out, err = res
            text = (out.strip() or err.strip()).splitlines()
            entry["version"] = text[0].strip() if text else None
            if code != 0:
                entry["versionProbeExit"] = code
        else:
            entry["version"] = "evidence_needed"
    return entry


def section_tools(root):
    tools = {}
    tools["git"] = _tool("git", extra_paths=(PREFERRED_GIT,),
                         version_argv=("--version",))
    java = _tool("java", version_argv=("-version",))
    java["JAVA_HOME"] = os.environ.get("JAVA_HOME")
    ver = java.get("version") or ""
    m = re.search(r'"(\d+)(?:\.(\d+))?', ver) or re.search(r"^(\d+)(?:\.(\d+))?", ver)
    java["is17"] = bool(m and (m.group(1) == "17" or ver.startswith("1.7")))
    tools["java"] = java
    tools["python"] = {
        "path": sys.executable,
        "version": platform.python_version(),
        "venv": os.environ.get("VIRTUAL_ENV"),
    }
    tools["node"] = _tool("node", version_argv=("--version",))
    ollama = _tool("ollama", version_argv=("--version",), timeout=5)
    ollama["ok"] = bool(ollama["path"])
    ollama["OLLAMA_MODELS_present"] = "OLLAMA_MODELS" in os.environ
    tools["ollama"] = ollama
    tools["gradle"] = {
        "path": shutil.which("gradle"),
        "wrapperPreferred": (root / "gradlew.bat").is_file(),
    }
    tools["nvidiaSmi"] = _tool("nvidia-smi")
    return tools


def section_db(root, py):
    field = {"laneA_fileH2": {}, "laneB_mariadb": {"explicitOnly": True, "probed": False},
             "cheatSheet": "docs/DB_AGENT_CHEATSHEET.md",
             "cheatSheetPresent": (root / "docs" / "DB_AGENT_CHEATSHEET.md").is_file()}
    agent = root / "scripts" / "db_agent.py"
    if not agent.is_file():
        field["laneA_fileH2"] = {"status": "evidence_needed", "reason": "db_agent-missing"}
        return field
    res = run_probe([py, "-B", str(agent), "status"], cwd=root, timeout=25)
    if res is None:
        field["laneA_fileH2"] = {"status": "evidence_needed", "reason": "probe-timeout-or-oserror"}
        return field
    code, out, err = res
    data = last_json_line(out)
    if not isinstance(data, dict):
        field["laneA_fileH2"] = {"status": "evidence_needed", "reason": "unparsed-output",
                                 "exitCode": code}
        return field
    live = data.get("live") or {}
    jdbc = data.get("jdbc") or {}
    field["laneA_fileH2"] = {
        "path": data.get("dbFile"),
        "exists": data.get("exists"),
        "size": data.get("size"),
        "mtimeUtc": data.get("mtimeUtc"),
        "locked": bool(data.get("locked") or jdbc.get("locked")),
        "reason": data.get("reason"),
        "status": "locked" if (data.get("locked") or code == 3) else ("ok" if code == 0 else "probe-failed"),
        "live": {"reachable": live.get("reachable"), "httpStatus": live.get("httpStatus"),
                 "tableCount": live.get("tableCount")},
        "exitCode": code,
        "note": "exit 3 / locked = JVM holds lmsdb.mv.db; reads fall back to the live lane; never kill the server",
    }
    return field


def section_gpu():
    field = {"preferred": "RTX3090",
             "note": "power issue resolved 2026-09-24 - prefer local GPU; pin by UUID, never CUDA_VISIBLE_DEVICES=0"}
    smi = shutil.which("nvidia-smi")
    if not smi:
        field["status"] = "evidence_needed"
        field["nvidiaSmi"] = None
        return field
    field["nvidiaSmi"] = smi
    res = run_probe([smi, "--query-gpu=index,name,uuid,memory.total",
                     "--format=csv,noheader,nounits"], timeout=8)
    if res is None:
        field["status"] = "evidence_needed"
        return field
    code, out, _ = res
    gpus = []
    for line in out.splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) >= 4:
            gpus.append({"index": parts[0], "name": parts[1],
                         "uuid": parts[2], "memoryMiB": parts[3]})
    field["gpus"] = gpus
    field["status"] = "ok" if gpus else "evidence_needed"
    return field


def section_git(root):
    git_path = PREFERRED_GIT if Path(PREFERRED_GIT).is_file() else (shutil.which("git") or None)
    policy = {
        "preferredGit": PREFERRED_GIT,
        "preferredPresent": Path(PREFERRED_GIT).is_file(),
        "noPushDefault": True,
        "conditionalLocalGit": "scripts/conditional_local_git.py",
        "conditionalLocalGitPresent": (root / "scripts" / "conditional_local_git.py").is_file(),
    }
    field = {"path": git_path, "version": None, "policy": policy}
    if not git_path:
        field["status"] = "evidence_needed"
        return field
    res = run_probe([git_path, "--version"], timeout=8)
    if res:
        field["version"] = (res[1].strip() or None)
    repo = {"gitDirPresent": (root / ".git").exists()}
    res = run_probe([git_path, "-C", str(root), "rev-parse", "--is-inside-work-tree"],
                    timeout=8)
    if res and res[0] == 0 and res[1].strip() == "true":
        repo["isWorkTree"] = True
        for key, argv in (("branch", ("rev-parse", "--abbrev-ref", "HEAD")),
                          ("head", ("rev-parse", "--short", "HEAD"))):
            res2 = run_probe([git_path, "-C", str(root), *argv], timeout=8)
            repo[key] = res2[1].strip() if res2 and res2[0] == 0 else "evidence_needed"
    else:
        repo["isWorkTree"] = False
    field["repo"] = repo
    return field


def section_skills(root):
    skills_root = root / ".agents" / "skills"
    count = None
    try:
        count = len([p for p in skills_root.iterdir() if p.is_dir()]) if skills_root.is_dir() else 0
    except OSError:
        count = None
    return {
        "skillsRoot": str(skills_root),
        "count": count,
        "router": "scripts/demo1_vibe_skill_router.py",
        "routerPresent": (root / "scripts" / "demo1_vibe_skill_router.py").is_file(),
        "indexPath": ".agents/skills-intent-index.yaml",
        "indexPresent": (root / ".agents" / "skills-intent-index.yaml").is_file(),
    }


def journals_summary(root, py):
    """work_journal list --active -> taskIds only (no purposes/bodies)."""
    agent = root / "scripts" / "work_journal.py"
    if not agent.is_file():
        return {"status": "evidence_needed", "reason": "work_journal-missing"}
    res = run_probe([py, "-B", str(agent), "list", "--active"], cwd=root, timeout=15)
    if res is None:
        return {"status": "evidence_needed", "reason": "probe-timeout-or-oserror"}
    code, out, _ = res
    data = last_json_line(out)
    tasks = (data or {}).get("tasks") or []
    return {
        "status": "ok" if code == 0 else "probe-failed",
        "activeCount": len(tasks),
        "activeTaskIds": [t.get("taskId") for t in tasks if isinstance(t, dict)],
    }


def collect(root, section):
    root = Path(root).resolve()
    py = sys.executable
    report = {
        "schemaVersion": SCHEMA,
        "projectRoot": str(root),
        "projectRootExists": root.is_dir(),
        "canonicalProjectRoot": CANONICAL_ROOT,
        "matchesCanonical": str(root).lower() == CANONICAL_ROOT.lower(),
        "cwd": str(Path.cwd()),
        "host": {
            "computerName": os.environ.get("COMPUTERNAME") or platform.node(),
            "user": getpass.getuser(),
            "os": platform.platform(),
        },
        "section": section,
    }
    want = lambda name: section in ("all", name)
    if want("paths"):
        report["paths"] = section_paths(root)
    if want("env"):
        report["env"] = section_env()
    if want("tools"):
        report["tools"] = section_tools(root)
    if want("db"):
        report["db"] = section_db(root, py)
    if want("gpu"):
        report["gpu"] = section_gpu()
    if want("git"):
        report["git"] = section_git(root)
        report["gitPolicy"] = report["git"].pop("policy")
    if want("skills"):
        report["skillsIndex"] = section_skills(root)
    if section == "all":
        report["journals"] = journals_summary(root, py)
    report["evidence"] = {
        "generatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "command": " ".join(sys.argv),
    }
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="One-shot demo-1 machine context JSON probe (read-only; "
                    "env reported by name only, secrets never emitted).")
    parser.add_argument("--root", default=".", help="project root (default: cwd)")
    parser.add_argument("--pretty", action="store_true", help="indent JSON output")
    parser.add_argument("--section", default="all", choices=SECTIONS,
                        help="collect only one section (default: all)")
    parser.add_argument("--write-report", default=None, metavar="PATH",
                        help="also write the JSON to PATH (must be inside project root)")
    args = parser.parse_args(argv)
    try:
        report = collect(args.root, args.section)
    except Exception as failure:  # report must always be printable
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": type(failure).__name__}, ensure_ascii=True))
        return 2
    if args.write_report:
        target = Path(args.write_report)
        if not target.is_absolute():
            target = Path(report["projectRoot"]) / target
        target = target.resolve()
        try:
            target.relative_to(report["projectRoot"])
        except ValueError:
            print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                              "reason": "write-report-outside-root"},
                             ensure_ascii=True))
            return 2
        report["evidence"]["reportWrittenTo"] = str(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n",
                          encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True,
                     indent=2 if args.pretty else None))
    return 0


if __name__ == "__main__":
    sys.exit(main())
