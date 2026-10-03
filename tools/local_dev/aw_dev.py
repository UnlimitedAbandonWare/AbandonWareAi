#!/usr/bin/env python3
"""aw-dev: single dispatcher for repo-local dev tooling (offline/mock first).

Thin dispatcher only. It calls existing tools and never reimplements them:
  - execution/recording  -> scripts/run_verified_command.py
  - entry diagnostics    -> scripts/agent_preflight.py
  - git diagnostics      -> scripts/git_doctor.py
  - JUnit class summary  -> scripts/junit_owned_summary.py
  - Jev offline checks   -> scripts/test_jev_*.py, scripts/zdr_guard.py,
                            scripts/jev_mock_gateway.py (loopback mock)
  - Jev campaign tools   -> Grok G-1..G-8 scripts when they exist (exit 12
                            while absent), Devin D3 runner when it exists.

aw-dev owned exit codes (children's codes pass through, origin="child"):
  0  PASS / operation ok
  2  usage or refused (bad/injected args, unregistered case, foreign run)
  10 NOT_RUN_ENV (required tool/file missing, or the action is on HOLD)
  11 INCONCLUSIVE (0 tests executed, stale/missing XML, missing evidence)
  12 PENDING_UPSTREAM (a registered upstream tool file is absent)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.aw-dev.v1"
EXIT_OK = 0
EXIT_USAGE = 2
EXIT_NOT_RUN_ENV = 10
EXIT_INCONCLUSIVE = 11
EXIT_PENDING_UPSTREAM = 12

ACTIONS = ("doctor", "bootstrap", "test", "smoke", "status", "stop",
           "report", "handoff", "list", "start")

CASE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
FQCN_RE = re.compile(r"^[A-Za-z_][\w.]*$")
ARG_TOKEN_RE = re.compile(r"^[\w.\-:/\\]+$")
CMD_BOUNDARY_RE = re.compile(r'[&|<>^%!"\'`;=\r\n]')
SECRET_NAME_RES = [
    re.compile(p, re.IGNORECASE) for p in (
        r".*_API_KEY$", r".*_TOKEN$", r".*SECRET.*", r".*PASSWORD.*",
        r"AI_GATEWAY_.*", r"OPENAI_.*", r"GEMINI_.*", r"GOOGLE_API_KEY",
        r"ANTHROPIC_.*", r"VERCEL_.*", r"AWX_JEV_VIA_WRAPPER$")
]
SECRET_VALUE_RE = re.compile(
    r"(?:AIza[\w\-]{20,}|sk-[\w\-]{16,}|vck_[\w\-]{8,}|Bearer\s+\S+|"
    r"[0-9a-fA-F]{40,})")
TAIL_LIMIT = 4096

REGISTRY_FIELDS = {
    "id", "action", "argv", "allowedArgs", "cwd", "timeoutS", "network",
    "needsLease", "envPolicy", "resultKind", "upstreamOwner", "requiredFiles",
    "requiredTools", "envAdd", "testCountRegex", "jsonChecks", "steps",
    "xmlDir", "suites", "sources", "fixtures", "junitClasses", "preCheck",
    "note",
}
NETWORK_KINDS = {"none", "loopback", "remote-paid"}
ENV_POLICIES = {"scrub", "inherit-min"}
RESULT_KINDS = {"exit", "junit", "json"}

INHERIT_MIN_NAMES = {
    "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "PATH", "PATHEXT", "COMSPEC",
    "TEMP", "TMP", "OS", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE",
    "USERPROFILE", "JAVA_HOME", "AWX_REPO_ROOT", "AWX_PYTHON",
    "AWX_SPLIT_BUILD_OUTPUTS", "AWX_BUILD_HOST_ID", "AWX_DEV_RUN_DIR",
    "AWX_DEV_RUN_ID", "PYTHONUTF8", "PYTHONIOENCODING",
}

UNTRACKED_TOOL_PROBE = [
    "tools/local_dev", "tools/agents/doctor.mjs", "scripts/apikit",
    "scripts/jev_ledger.py", "scripts/jev_mock_gateway.py",
    "scripts/junit_owned_summary.py",
    "docs/diagnostics/multi-session-build-coexist-0928.md",
]

ROOT_MARKERS = ("gradlew.bat", "build.gradle.kts", "main/java",
                "scripts/agent_preflight.py")


def utcnow():
    return datetime.now(timezone.utc)


def iso(dt=None):
    return (dt or utcnow()).isoformat()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    return sha256_bytes(Path(path).read_bytes())


def manifest_hash(paths, root, limit=200):
    """sha256 over sorted (relpath, sha256) pairs; dirs expand to members."""
    entries = []
    for rel in paths or []:
        base = (Path(root) / rel).resolve()
        if base.is_file():
            entries.append((rel.replace("\\", "/"), sha256_file(base)))
        elif base.is_dir():
            members = []
            for m in sorted(base.rglob("*"))[:limit]:
                if m.is_file():
                    members.append((str(m.relative_to(base)).replace("\\", "/"),
                                    sha256_file(m)))
            entries.append((rel.replace("\\", "/") + "/",
                            sha256_bytes(json.dumps(members).encode())))
        else:
            entries.append((rel.replace("\\", "/"), None))
    return sha256_bytes(json.dumps(entries).encode()), entries


def wrapper_dir():
    # tools/local_dev/aw_dev.py -> parents[2] is the wrapper directory.
    return Path(__file__).resolve().parents[2]


def bindings_path():
    return wrapper_dir() / "var" / "local_dev" / "bindings.local.json"


def load_json_file(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return None


def load_bindings():
    data = load_json_file(bindings_path())
    return data if isinstance(data, dict) else {}


def resolve_python(env=None, bindings=None):
    env = os.environ if env is None else env
    bindings = load_bindings() if bindings is None else bindings
    cands = []
    if env.get("AWX_PYTHON"):
        cands.append(("env", env["AWX_PYTHON"]))
    if bindings.get("python"):
        cands.append(("bindings", bindings["python"]))
    uv = Path(env.get("USERPROFILE", str(Path.home()))) / ".local" / "bin" / "python3.11.exe"
    cands.append(("uv-cpython-3.11", str(uv)))
    found = shutil.which("python", path=env.get("PATH") or None)
    if found:
        cands.append(("PATH", found))
    for source, cand in cands:
        if cand and Path(cand).is_file():
            return {"path": str(Path(cand).resolve()), "source": source}
    if found:  # PATH hit that resolved oddly
        return {"path": found, "source": "PATH"}
    return None


def interpreter_info(py):
    info = {"path": py["path"], "source": py["source"],
            "version": None, "basePrefix": None}
    try:
        proc = subprocess.run(
            [py["path"], "-B", "-c",
             "import sys;print(sys.version.split()[0]);print(sys.base_prefix)"],
            capture_output=True, text=True, timeout=20)
        lines = (proc.stdout or "").splitlines()
        if proc.returncode == 0 and lines:
            info["version"] = lines[0].strip()
            if len(lines) > 1:
                info["basePrefix"] = lines[1].strip()
    except (OSError, subprocess.TimeoutExpired):
        pass
    return info


def resolve_root(args_root, env=None, bindings=None):
    env = os.environ if env is None else env
    bindings = load_bindings() if bindings is None else bindings
    source = "wrapper"
    raw = None
    if args_root:
        raw, source = args_root, "--root"
    elif env.get("AWX_REPO_ROOT"):
        raw, source = env["AWX_REPO_ROOT"], "env:AWX_REPO_ROOT"
    elif bindings.get("repoRoot"):
        raw, source = bindings["repoRoot"], "bindings.repoRoot"
    else:
        raw = str(wrapper_dir())
    resolved = Path(raw).resolve()
    markers = {m: (resolved / m).exists() for m in ROOT_MARKERS}
    return {"root": resolved, "source": source, "raw": raw,
            "markers": markers, "rootReady": all(markers.values())}


def worktree_base(env=None):
    env = os.environ if env is None else env
    return (Path(env.get("USERPROFILE", str(Path.home()))) /
            ".cline" / "worktrees")


def is_under(path, base):
    try:
        Path(path).resolve().relative_to(Path(base).resolve())
        return True
    except (ValueError, OSError):
        return False


def worktree_status(root, cwd, env=None):
    base = worktree_base(env)
    root_wt = is_under(root, base)
    cwd_wt = is_under(cwd, base)
    return {"worktreeMismatch": bool(root_wt or cwd_wt),
            "worktreeBase": str(base),
            "rootIsWorktree": root_wt, "cwdIsWorktree": cwd_wt}


def forbidden_env_name(name):
    return any(r.match(name) for r in SECRET_NAME_RES)


def scrub_env(env=None, policy="scrub"):
    env = dict(os.environ if env is None else env)
    if policy == "inherit-min":
        env = {k: v for k, v in env.items()
               if k.upper() in INHERIT_MIN_NAMES}
    return {k: v for k, v in env.items() if not forbidden_env_name(k)}


def scrub_text(text):
    if not text:
        return text
    lines = []
    for line in text.splitlines():
        m = re.match(r"^(.*?)([A-Za-z_][\w.]*)=(\S+)(.*)$", line)
        if m and forbidden_env_name(m.group(2)):
            line = m.group(1) + m.group(2) + "=<redacted>" + m.group(4)
        lines.append(SECRET_VALUE_RE.sub("<redacted>", line))
    return "\n".join(lines)


def tail_of(path, limit=TAIL_LIMIT):
    try:
        data = Path(path).read_bytes()
    except OSError:
        return ""
    return scrub_text(data[-limit:].decode("utf-8", "replace"))


def registry_path():
    return Path(__file__).resolve().parent / "tool-registry.json"


def load_registry():
    data = load_json_file(registry_path())
    if not isinstance(data, dict) or not isinstance(data.get("cases"), list):
        return {"cases": []}
    return data


def find_case(registry, action, case_id):
    for case in registry.get("cases", []):
        if case.get("id") == case_id and case.get("action") == action:
            return case
    return None


def bad_arg(reason):
    return {"error": "invalid-argument", "reason": reason}, EXIT_USAGE


def check_value(value):
    if not isinstance(value, str) or not value:
        return "empty"
    if CMD_BOUNDARY_RE.search(value):
        return "boundary"
    if "\n" in value or "\r" in value:
        return "newline"
    return None


def resolve_arg_path(root, value):
    """Resolve a caller path under root; reject escapes incl. junction hops."""
    if not isinstance(value, str) or not value:
        raise ValueError("empty-path")
    if CMD_BOUNDARY_RE.search(value):
        raise ValueError("boundary-char")
    cand = Path(value)
    if not cand.is_absolute():
        cand = Path(root) / cand
    resolved = cand.resolve()
    try:
        resolved.relative_to(Path(root).resolve())
    except ValueError:
        raise ValueError("path-escape")
    return resolved


def parse_case_args(case, extra):
    """Validate leftover --flag value pairs against allowedArgs schema."""
    schema = {a["name"].lstrip("-"): a for a in case.get("allowedArgs") or []}
    values = {}
    i = 0
    while i < len(extra):
        entry = extra[i]
        if not entry.startswith("--"):
            raise ValueError("unexpected-arg:" + entry)
        name = entry[2:]
        if name not in schema:
            raise ValueError("unregistered-arg:" + entry)
        spec = schema[name]
        if spec.get("takesValue", True):
            if i + 1 >= len(extra):
                raise ValueError("missing-value:" + entry)
            raw = extra[i + 1]
            i += 2
        else:
            raw = True
            i += 1
        if isinstance(raw, str):
            err = check_value(raw)
            if err:
                raise ValueError("bad-value:" + name + ":" + err)
            kind = spec.get("kind", "token")
            if kind == "fqcn" or kind == "fqcn-allow":
                if not FQCN_RE.match(raw):
                    raise ValueError("bad-fqcn:" + name)
                allow = spec.get("allow")
                if allow and raw not in allow:
                    raise ValueError("fqcn-not-allowed:" + raw)
            elif kind == "int":
                if not re.fullmatch(r"\d{1,5}", raw):
                    raise ValueError("bad-int:" + name)
                raw = int(raw)
            elif kind == "token":
                if not ARG_TOKEN_RE.match(raw):
                    raise ValueError("bad-token:" + name)
            # kind relpath is resolved later (needs root)
        values[name] = raw
    for key, spec in schema.items():
        if spec.get("required") and key not in values:
            raise ValueError("missing-required:" + key)
    return values


def subst(text, mapping):
    out = text
    for key, value in mapping.items():
        out = out.replace("{" + key + "}", str(value))
    return out


def git_revision(root):
    try:
        proc = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                              capture_output=True, text=True, timeout=20)
        if proc.returncode == 0:
            return proc.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        pass
    return None


def case_mapping(root, run_dir, args, env_add):
    mapping = {"root": str(root), "runDir": str(run_dir),
               "buildHostId": env_add.get("AWX_BUILD_HOST_ID", "local")}
    for name, value in args.items():
        mapping["arg:" + name] = str(value)
    return mapping


def build_command(argv_template, mapping, root, interp):
    argv = [subst(a, mapping) for a in argv_template]
    head = argv[0]
    if head.startswith("py:"):
        target = resolve_arg_path(root, head[3:])
        return [interp["path"], "-B", str(target)] + argv[1:], "python"
    if head.startswith("node:"):
        node = shutil.which("node")
        if not node:
            raise FileNotFoundError("tool-missing:node")
        target = resolve_arg_path(root, head[5:])
        return [node, str(target)] + argv[1:], "node"
    if head.startswith("bat:"):
        target = resolve_arg_path(root, head[4:])
        return ["cmd.exe", "/c", "", str(target)] + argv[1:], "bat"
    if head.startswith("ps1:"):
        target = resolve_arg_path(root, head[4:])
        return [str(Path(os.environ.get("SystemRoot", r"C:\Windows"))
                    / "System32" / "WindowsPowerShell" / "v1.0" /
                    "powershell.exe"), "-NoProfile", "-ExecutionPolicy",
                "Bypass", "-File", str(target)] + argv[1:], "ps1"
    target = resolve_arg_path(root, head)
    return [str(target)] + argv[1:], "file"


def required_tools_missing(tools):
    missing = []
    for tool in tools or []:
        if tool == "python":
            continue  # resolved interpreter already required
        if not shutil.which(tool):
            missing.append(tool)
    return missing


def files_missing(root, files):
    return [f for f in files or [] if not (Path(root) / f).exists()]


def run_child(root, interp, out_dir, argv, env, cwd, timeout_s, scope,
              xml_dir=None, suites=None, sources=None):
    """Delegate one execution to scripts/run_verified_command.py."""
    cmd = [interp["path"], "-B", "scripts/run_verified_command.py",
           "--cwd", str(cwd), "--output", str(out_dir),
           "--timeout", str(int(timeout_s)), "--scope", scope]
    if xml_dir:
        cmd += ["--xml-dir", str(xml_dir)]
        for suite in suites or []:
            cmd += ["--suite", suite]
    for src in sources or []:
        cmd += ["--source", src]
    cmd += ["--"] + [str(a) for a in argv]
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env,
                              capture_output=True, text=True,
                              timeout=int(timeout_s) + 120)
    except subprocess.TimeoutExpired:
        return {"launchError": "runner-timeout"}
    except OSError as exc:
        return {"launchError": type(exc).__name__}
    summary = None
    for line in reversed((proc.stdout or "").splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                summary = json.loads(line)
                break
            except ValueError:
                continue
    record = load_json_file(Path(out_dir) / "run.json") or {}
    return {"runnerExit": proc.returncode, "runnerStdout": summary,
            "run": record, "outDir": str(out_dir)}


def count_from_log(out_dir, pattern):
    try:
        text = (Path(out_dir) / "command.log").read_text(
            encoding="utf-8", errors="replace")
    except OSError:
        return None
    m = re.search(pattern, text)
    return int(m.group(1)) if m else None


def eval_json_checks(run_dir, checks, mapping):
    """Return (state, detail): ok | fail:<field> | missing:<path>."""
    for chk in checks or []:
        rel = subst(chk["path"], mapping)
        path = Path(rel)
        if not path.is_absolute():
            path = (run_dir / rel)
        try:
            path = path.resolve()
            path.relative_to(run_dir.resolve())
        except (ValueError, OSError):
            return "missing", chk["path"]
        if not path.is_file():
            return "missing", chk["path"]
        data = load_json_file(path)
        if data is None:
            return "missing", chk["path"]
        node = data
        for part in str(chk.get("field", "")).split("."):
            if isinstance(node, dict) and part in node:
                node = node[part]
            else:
                node = None
                break
        if node != chk.get("equals"):
            return "fail", "%s!=%r (got %r)" % (chk["field"],
                                                chk.get("equals"), node)
    return "ok", None


def gradle_test_busy():
    """True when another session runs a Gradle :test JVM on this box."""
    try:
        ps = str(Path(os.environ.get("SystemRoot", r"C:\Windows"))
                 / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe")
        proc = subprocess.run(
            [ps, "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
             "Where-Object { $_.CommandLine -match ':test|GradleWorkerMain' } |"
             " Measure-Object | Select-Object -ExpandProperty Count"],
            capture_output=True, text=True, timeout=40)
        if proc.returncode == 0:
            return int((proc.stdout or "0").strip() or "0") > 0
    except (OSError, subprocess.TimeoutExpired, ValueError):
        pass
    return False


def classify_exec(spec, run, run_dir, mapping, evidence_dir=None):
    """Turn a run_verified record into (exitCode, result, reasonCode, origin,
    testCounts)."""
    if run.get("launchError"):
        return (EXIT_NOT_RUN_ENV, "NOT_RUN_ENV",
                "runner-" + run["launchError"], "aw-dev", {})
    rec = run.get("run") or {}
    child_exit = rec.get("exitCode")
    failures = rec.get("failures") or []
    status = rec.get("status")
    totals = rec.get("totals") or {}
    kind = spec.get("resultKind", "exit")
    counts = {"executed": None, "passed": None, "failed": None,
              "skipped": None}
    if kind == "junit" or totals.get("tests"):
        counts.update(executed=totals.get("tests", 0),
                      failed=(totals.get("failures", 0) or 0) +
                             (totals.get("errors", 0) or 0),
                      skipped=totals.get("skipped", 0))
        if counts["executed"] is not None and counts["failed"] is not None:
            counts["passed"] = max(counts["executed"] - counts["failed"]
                                   - (counts["skipped"] or 0), 0)
    pattern = spec.get("testCountRegex")
    if pattern:
        n = count_from_log(run_dir, pattern)
        if n is not None:
            counts["executed"] = n
            if counts.get("passed") is None:
                counts["passed"] = n if child_exit == 0 else None
    if status in ("interrupted", "launch_failed", "stopped"):
        code = rec.get("verificationExitCode") or (child_exit or 3)
        return (code, "FAIL", "runner-" + str(status), "child", counts)
    if child_exit not in (0, None):
        code = rec.get("verificationExitCode") or child_exit
        return (code, "FAIL", "child-exit", "child", counts)
    if child_exit is None:
        return (EXIT_INCONCLUSIVE, "INCONCLUSIVE", "no-exit-recorded",
                "aw-dev", counts)
    # child exit == 0 from here on
    evidence_bad = [f for f in failures if f.startswith(
        ("missing_xml", "stale_xml", "invalid_xml"))]
    if evidence_bad:
        return (EXIT_INCONCLUSIVE, "INCONCLUSIVE", evidence_bad[0],
                "aw-dev", counts)
    if "junit_failure" in failures:
        return (1, "FAIL", "junit-failures", "aw-dev", counts)
    if failures or status != "passed":
        return (EXIT_INCONCLUSIVE, "INCONCLUSIVE",
                "evidence-incomplete:" + (failures[0] if failures else status),
                "aw-dev", counts)
    if kind in ("junit", "exit") and spec.get("testCountRegex") is not None \
            and not counts.get("executed"):
        return (EXIT_INCONCLUSIVE, "INCONCLUSIVE",
                "zero-tests-executed", "aw-dev", counts)
    if kind == "junit" and not counts.get("executed"):
        return (EXIT_INCONCLUSIVE, "INCONCLUSIVE",
                "zero-tests-executed", "aw-dev", counts)
    state, detail = eval_json_checks(evidence_dir or run_dir,
                                     spec.get("jsonChecks"), mapping)
    if state == "missing":
        return (EXIT_INCONCLUSIVE, "INCONCLUSIVE",
                "evidence-missing:" + detail, "aw-dev", counts)
    if state == "fail":
        return (1, "FAIL", "json-check:" + detail, "aw-dev", counts)
    return (EXIT_OK, "PASS", "ok", "aw-dev", counts)


def new_run_id():
    return "run-" + utcnow().strftime("%Y%m%d-%H%M%S") + "-" + \
        uuid.uuid4().hex[:6]


def write_result(run_dir, result):
    path = Path(run_dir) / "result.json"
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n",
                   encoding="utf-8")
    tmp.replace(path)
    return path


def junit_class_check(interp, root, env, xml_dir, classes):
    cmd = [interp["path"], "-B", "scripts/junit_owned_summary.py",
           "--xml-dir", str(xml_dir), "--json"]
    for cls in classes:
        cmd += ["--class", cls]
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env, capture_output=True,
                              text=True, timeout=60)
        data = json.loads(proc.stdout or "{}")
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None
    return {"exit": proc.returncode, "named": data.get("named")}


def execute_case(ctx, case, args, run_dir):
    """Run one case (single exec or steps). Returns result dict fields."""
    root, interp = ctx["root"], ctx["interpreter"]
    env_add = dict(case.get("envAdd") or {})
    env = scrub_env(ctx.get("env"), case.get("envPolicy", "scrub"))
    env.update(env_add)
    env["AW_DEV_RUN_DIR"] = str(run_dir)
    env["AW_DEV_RUN_ID"] = Path(run_dir).name
    mapping = case_mapping(root, Path(run_dir), args, env_add)
    scope = ("LOCAL_JAVA" if case.get("resultKind") == "junit" and
             case.get("id") == "java-jev-focused" else
             "MOCK" if case.get("network") == "loopback" else "OFFLINE")
    cwd = Path(root) / case.get("cwd", ".")
    steps = case.get("steps") or [case]
    step_results = []
    aggregate_counts = {"executed": 0, "passed": 0, "failed": 0, "skipped": 0}
    counted = False
    final_exit, final_result, reason, origin = 0, "PASS", "ok", "aw-dev"
    for spec in steps:
        missing = files_missing(root, spec.get("requiredFiles"))
        tools = required_tools_missing(spec.get("requiredTools"))
        if missing or tools:
            owner = case.get("upstreamOwner", "devin")
            code = (EXIT_PENDING_UPSTREAM if owner != "aw-dev"
                    else EXIT_NOT_RUN_ENV)
            return {"exitCode": code,
                    "result": ("PENDING_UPSTREAM" if code == 12
                               else "NOT_RUN_ENV"),
                    "reasonCode": "missing:" +
                                  ",".join(missing + tools),
                    "origin": "aw-dev", "steps": step_results}
        try:
            argv, _launcher = build_command(
                spec.get("argv") or [], mapping, root, interp)
        except FileNotFoundError as exc:
            return {"exitCode": EXIT_NOT_RUN_ENV, "result": "NOT_RUN_ENV",
                    "reasonCode": str(exc), "origin": "aw-dev",
                    "steps": step_results}
        except ValueError as exc:
            return {"exitCode": EXIT_USAGE, "result": "FAIL",
                    "reasonCode": "argv-" + str(exc), "origin": "aw-dev",
                    "steps": step_results}
        sub = Path(run_dir) / ("steps/" + spec.get("id", "step")
                               if len(steps) > 1 else "verify")
        xml_dir = spec.get("xmlDir")
        xml_dir = Path(subst(xml_dir, mapping)) if xml_dir else None
        suites = [subst(s, mapping) for s in spec.get("suites") or []]
        run = run_child(root, interp, sub, argv, env, cwd,
                        spec.get("timeoutS") or case.get("timeoutS") or 600,
                        scope, xml_dir=xml_dir, suites=suites,
                        sources=spec.get("sources") or case.get("sources"))
        exit_code, result, reason_c, orig, counts = classify_exec(
            spec, run, sub, mapping, evidence_dir=Path(run_dir))
        rec = run.get("run") or {}
        step_results.append({
            "id": spec.get("id", case["id"]),
            "exitCode": exit_code, "result": result, "reasonCode": reason_c,
            "origin": orig,
            "childExitCode": rec.get("exitCode"),
            "childStatus": rec.get("status"),
            "runnerRunId": rec.get("runId"),
            "failures": rec.get("failures") or [],
            "testCounts": counts,
            "outputTail": tail_of(sub / "command.log"),
            "outDir": str(sub),
        })
        for key, val in counts.items():
            if isinstance(val, int):
                aggregate_counts[key] += val
                counted = True
        # junit named-class summary (MISSING -> INCONCLUSIVE)
        classes = [subst(c, mapping) for c in spec.get("junitClasses") or []]
        if classes and xml_dir:
            check = junit_class_check(interp, root, env, xml_dir, classes)
            step_results[-1]["junitOwnedSummary"] = check
            if check and any(r.get("status") == "MISSING"
                             for r in check.get("named") or []):
                exit_code, result, reason_c = (
                    EXIT_INCONCLUSIVE, "INCONCLUSIVE",
                    "junit-class-missing")
                step_results[-1].update(exitCode=exit_code, result=result,
                                        reasonCode=reason_c)
        if exit_code != 0 and final_exit == 0:
            final_exit, final_result, reason, origin = (
                exit_code, result, reason_c, orig)
        if exit_code != 0:
            break  # stop case at first failing step
    if final_exit == 0 and counted and not aggregate_counts["executed"]:
        final_exit, final_result, reason, origin = (
            EXIT_INCONCLUSIVE, "INCONCLUSIVE", "zero-tests-executed",
            "aw-dev")
    return {"exitCode": final_exit, "result": final_result,
            "reasonCode": reason, "origin": origin,
            "testCounts": aggregate_counts if counted else None,
            "steps": step_results}


def action_test_or_smoke(ctx, action, argv):
    parser = argparse.ArgumentParser(prog="aw-dev " + action)
    parser.add_argument("--case", dest="case_id")
    parser.add_argument("--root")
    parser.add_argument("--json", action="store_true")
    known, extra = parser.parse_known_args(argv)
    def fail2(payload):
        print_out(payload, ctx)
        return EXIT_USAGE

    if not known.case_id:
        return fail2({"error": "--case required"})
    if not CASE_ID_RE.match(known.case_id):
        return fail2({"error": "bad-case-id"})
    ctx2 = prepare_ctx(known.root, ctx)
    registry = load_registry()
    case = find_case(registry, action, known.case_id)
    if case is None:
        return fail2({"error": "unregistered-case",
                      "case": known.case_id, "action": action})
    try:
        args = parse_case_args(case, extra)
    except ValueError as exc:
        return fail2({"error": str(exc)})
    # resolve path-kind args now (they need root)
    schema = {a["name"].lstrip("-"): a
              for a in case.get("allowedArgs") or []}
    for name, spec in schema.items():
        if spec.get("kind") == "relpath" and name in args:
            try:
                resolved = resolve_arg_path(ctx2["root"], args[name])
            except ValueError as exc:
                return fail2({"error": "arg-path:" + str(exc)})
            if spec.get("mustExist", True) and not resolved.is_file():
                return fail2({"error": "arg-path-absent:" + name})
            args[name] = str(resolved)
    run_dir = ctx2["runsDir"] / new_run_id()
    run_dir.mkdir(parents=True)
    marker = {"createdBy": "aw-dev", "schemaVersion": SCHEMA,
              "runId": run_dir.name, "action": action,
              "caseId": known.case_id, "startedAt": iso(),
              "recorderPid": os.getpid()}
    (run_dir / "aw-dev-run.json").write_text(
        json.dumps(marker, indent=2) + "\n", encoding="utf-8")
    pre = case.get("preCheck") or {}
    if pre.get("type") == "no-gradle-test-busy" and gradle_test_busy():
        outcome = {"exitCode": EXIT_NOT_RUN_ENV, "result": "NOT_RUN_ENV",
                   "reasonCode": "gradle-test-busy", "origin": "aw-dev",
                   "steps": []}
    elif not ctx2["rootReady"]:
        outcome = {"exitCode": EXIT_NOT_RUN_ENV, "result": "NOT_RUN_ENV",
                   "reasonCode": "root-not-ready", "origin": "aw-dev",
                   "steps": []}
    else:
        outcome = execute_case(ctx2, case, args, run_dir)
    source_hash, source_entries = manifest_hash(
        case.get("sources"), ctx2["root"])
    fixture_hash, fixture_entries = manifest_hash(
        case.get("fixtures"), ctx2["root"])
    ended_at = iso()
    started_ts = _parse_iso(marker["startedAt"])
    ended_ts = _parse_iso(ended_at)
    duration_ms = (int((ended_ts - started_ts) * 1000)
                   if started_ts and ended_ts else None)
    result = {
        "schemaVersion": SCHEMA, "createdBy": "aw-dev",
        "runId": run_dir.name, "campaignId": None, "actionId": action,
        "caseId": known.case_id, "stage": action,
        "sourceRevision": git_revision(ctx2["root"]),
        "sourceHashes": {p: h for p, h in source_entries},
        "sourceManifestSha256": source_hash,
        "fixtureHash": (fixture_hash if case.get("fixtures") else None),
        "startedAt": marker["startedAt"], "endedAt": ended_at,
        "durationMs": duration_ms, "exitCode": outcome["exitCode"],
        "origin": outcome.get("origin", "aw-dev"),
        "testCounts": outcome.get("testCounts"),
        "verificationScope": ("MOCK" if case.get("network") == "loopback"
                              else "OFFLINE"),
        "result": outcome["result"], "reasonCode": outcome["reasonCode"],
        "evidencePaths": [str(run_dir.relative_to(ctx2["root"]))
                          if is_under(run_dir, ctx2["root"]) else str(run_dir)],
        "interpreter": ctx["interpreter"],
        "worktreeMismatch": ctx2["worktreeMismatch"],
        "steps": outcome.get("steps"),
        "network": case.get("network"),
        "upstreamOwner": case.get("upstreamOwner"),
    }
    write_result(run_dir, result)
    print_out(result, ctx2)
    return outcome["exitCode"]


def prepare_ctx(root_arg, base=None):
    base = base or {}
    env = base.get("env") or dict(os.environ)
    bindings = load_bindings()
    rootinfo = resolve_root(root_arg, env, bindings)
    root = rootinfo["root"]
    wt = worktree_status(root, base.get("cwd") or Path.cwd(), env)
    interp = base.get("interpreter") or interpreter_info(
        resolve_python(env, bindings) or {"path": sys.executable,
                                          "source": "self"})
    runs_dir = root / "var" / "local_dev" / "runs"
    return {"env": env, "bindings": bindings, "root": root,
            "rootSource": rootinfo["source"], "markers": rootinfo["markers"],
            "rootReady": rootinfo["rootReady"],
            "worktreeMismatch": wt["worktreeMismatch"],
            "worktree": wt, "interpreter": interp, "runsDir": runs_dir}


def print_out(payload, ctx=None):
    print(json.dumps(payload, ensure_ascii=True, indent=2,
                     default=str))
    return payload


# ---------------------------------------------------------------- doctor

def probe_cmd(argv, timeout=20, env=None, cwd=None, first_only=True):
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              timeout=timeout, env=env, cwd=cwd)
        text = (proc.stdout or "") + ("\n" + proc.stderr if proc.stderr else "")
        return {"exit": proc.returncode,
                "head": [l for l in text.splitlines() if l.strip()][:6]}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"exit": None, "error": type(exc).__name__}


def doctor(argv):
    parser = argparse.ArgumentParser(prog="aw-dev doctor")
    parser.add_argument("--root")
    parser.add_argument("--json", action="store_true", default=True)
    args = parser.parse_args(argv)
    ctx = prepare_ctx(args.root)
    root = ctx["root"]
    checks = []

    def add(name, required, ok, detail):
        checks.append({"name": name, "required": required, "ok": bool(ok),
                       "detail": detail})

    interp = ctx["interpreter"]
    add("python", True, bool(interp.get("path")),
        {"path": interp.get("path"), "version": interp.get("version"),
         "basePrefix": interp.get("basePrefix"),
         "source": interp.get("source")})
    java = probe_cmd(["java", "-version"])
    java_ver = None
    for line in java.get("head", []):
        m = re.search(r'version "([\d._]+)"', line)
        if m:
            java_ver = m.group(1)
            break
    java_ok = java_ver is not None and java_ver.split(".")[0] == "17"
    add("java17", True, java_ok,
        {"version": java_ver, "JAVA_HOME": bool(os.environ.get("JAVA_HOME"))})
    wrapper_props = root / "gradle" / "wrapper" / "gradle-wrapper.properties"
    dist_url, dist_ver, dist_cached = None, None, False
    if wrapper_props.is_file():
        for line in wrapper_props.read_text(
                encoding="utf-8", errors="replace").splitlines():
            if line.strip().startswith("distributionUrl"):
                dist_url = line.split("=", 1)[1].strip()
                m = re.search(r"gradle-(\d+\.\d+)", dist_url)
                dist_ver = m.group(1) if m else None
    if dist_ver:
        dists = Path(os.environ.get("USERPROFILE", "")) / ".gradle" / \
            "wrapper" / "dists"
        try:
            dist_cached = any(p.name.startswith("gradle-" + dist_ver)
                              for p in dists.iterdir())
        except OSError:
            dist_cached = False
    wrapper_ok = (root / "gradlew.bat").is_file() and dist_ver is not None
    add("gradle-wrapper", True, wrapper_ok,
        {"distributionUrl": dist_url, "version": dist_ver,
         "distCached": dist_cached})
    git = probe_cmd(["git", "--version"])
    add("git", True, git.get("exit") == 0,
        {"head": git.get("head", [])[:1]})
    add("root-markers", True, ctx["rootReady"],
        {"markers": ctx["markers"], "source": ctx["rootSource"],
         "root": str(root)})
    # optional tools
    for name, argv_ in (("node", ["node", "--version"]),
                        ("rg", ["rg", "--version"]),
                        ("uv", ["uv", "--version"])):
        probe = probe_cmd(argv_)
        add(name, False, probe.get("exit") == 0,
            {"head": probe.get("head", [])[:1]})
    # ollama: list only (no generate/pull)
    ollama_ok, ollama_detail = False, {}
    try:
        import urllib.request
        with urllib.request.urlopen(
                "http://127.0.0.1:11434/api/tags", timeout=3) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        ollama_ok = True
        ollama_detail = {"models": len(data.get("models") or [])}
    except Exception as exc:  # offline optional dependency
        ollama_detail = {"error": type(exc).__name__}
    add("ollama-local", False, ollama_ok, ollama_detail)
    # Cline desktop presence + rules dir
    cline_exe = Path(r"D:\ai\Cline\cline-app.exe")
    rules_dir = root / ".clinerules"
    rules = sorted(p.name for p in rules_dir.glob("*")
                   if p.is_file()) if rules_dir.is_dir() else []
    tracked = set()
    try:
        proc = subprocess.run(["git", "-C", str(root), "ls-files", "--",
                               ".clinerules"], capture_output=True, text=True,
                              timeout=30)
        if proc.returncode == 0:
            tracked = {Path(l).name for l in proc.stdout.splitlines()
                       if l.strip()}
    except (OSError, subprocess.TimeoutExpired):
        pass
    rule_rows = [{"file": r,
                  "tracked": r in tracked} for r in rules]
    add("cline-desktop", False, cline_exe.is_file(),
        {"exePresent": cline_exe.is_file(),
         "rulesDir": len(rules), "rules": rule_rows,
         "worktreesPresent":
             len(list(worktree_base().glob("*")))
             if worktree_base().is_dir() else 0})
    # worktree mismatch
    missing_untracked = [p for p in UNTRACKED_TOOL_PROBE
                         if not (root / p).exists()]
    wt_detail = dict(ctx["worktree"])
    wt_detail["missingUntrackedTools"] = missing_untracked
    settings_both = (root / "settings.gradle").is_file() and \
        (root / "settings.gradle.kts").is_file()
    add("worktree-location", ctx["worktreeMismatch"],
        not ctx["worktreeMismatch"], wt_detail)
    # environment key names only (values never recorded)
    key_names = sorted(k for k in os.environ if forbidden_env_name(k))
    add("secret-env-names", False, True,
        {"namesPresent": key_names, "note": "names only; values never read"})
    add("settings-dual", False, True,
        {"settingsGradleAndKtsBothPresent": settings_both})
    # delegated read-only diagnostics (summaries only)
    py = interp["path"]
    pre = probe_cmd([py, "-B", "scripts/agent_preflight.py", "--root",
                     str(root)], timeout=120, cwd=str(root))
    pre_sum = {"exit": pre.get("exit")}
    try:
        data = json.loads("".join(l for l in pre.get("head", [])))
    except ValueError:
        data = None
    if isinstance(data, dict):
        pj = (data.get("projectRoot") or {}).get("result") or {}
        pre_sum["projectRootClass"] = pj.get("class")
        leases = (data.get("leases") or {}).get("result") or {}
        pre_sum["leaseBlocking"] = leases.get("sourceLeaseBlockingCount")
        gs = data.get("goalSwitch") or {}
        pre_sum["goalSwitchAllowedOpen"] = (gs.get("check") or {}).get(
            "allowedOpen")
    add("agent-preflight", False, pre.get("exit") == 0, pre_sum)
    gd = probe_cmd([py, "-B", "scripts/git_doctor.py", "--root", str(root),
                    "--json"], timeout=60, cwd=str(root))
    gd_sum = {"exit": gd.get("exit")}
    add("git-doctor", False, gd.get("exit") == 0, gd_sum)
    # optional agents doctor (node, no pause)
    agents_out = root / "var" / "local_dev" / "doctor-agents-last.txt"
    node = shutil.which("node")
    if node and (root / "tools" / "agents" / "doctor.mjs").is_file():
        env = scrub_env()
        env["AWX_RAG_NO_PAUSE"] = "1"
        probe = probe_cmd([node, str(root / "tools" / "agents" /
                                        "doctor.mjs")],
                          timeout=90, env=env, cwd=str(root))
        try:
            agents_out.parent.mkdir(parents=True, exist_ok=True)
            agents_out.write_text("\n".join(probe.get("head", [])) + "\n",
                                  encoding="utf-8")
        except OSError:
            pass
        add("agents-doctor", False, probe.get("exit") == 0,
            {"exit": probe.get("exit"),
             "out": str(agents_out.relative_to(root))})
    else:
        add("agents-doctor", False, True, {"skipped": "node-or-script-absent"})
    required_fail = [c["name"] for c in checks if c["required"] and
                     not c["ok"]]
    if ctx["worktreeMismatch"]:
        required_fail = sorted(set(required_fail) | {"worktree-location"})
    payload = {"schemaVersion": SCHEMA, "action": "doctor",
               "root": str(root), "rootSource": ctx["rootSource"],
               "rootReady": ctx["rootReady"],
               "worktreeMismatch": ctx["worktreeMismatch"],
               "worktree": ctx["worktree"],
               "interpreter": interp,
               "checks": checks, "requiredFailed": required_fail,
               "bindingsFile": str(bindings_path()),
               "bindingsPresent": bindings_path().is_file(),
               "checkedAt": iso()}
    print(json.dumps(payload, ensure_ascii=True, indent=2))
    return EXIT_NOT_RUN_ENV if required_fail else EXIT_OK


# ---------------------------------------------------------------- bootstrap

TOOL_PLAN = {
    "git": {"package": "Git for Windows", "version": "2.x",
            "url": "https://git-scm.com/download/win",
            "verify": "git --version",
            "installPath": "<user choice>", "impact": "system PATH"},
    "java17": {"package": "Eclipse Temurin JDK 17",
               "version": "17.0.x",
               "url": "https://adoptium.net/temurin/releases/?version=17",
               "verify": "java -version",
               "installPath": "<user choice>; set JAVA_HOME",
               "impact": "system PATH + JAVA_HOME"},
    "python": {"package": "CPython 3.11 (uv) or python.org",
               "version": "3.11.x",
               "url": "https://docs.astral.sh/uv/ or https://www.python.org/",
               "verify": "python --version",
               "installPath": "%USERPROFILE%\\.local\\bin",
               "impact": "user-level; no admin"},
}


def compute_plan(ctx):
    """Missing-required list -> plan items; hash over stable fields only."""
    items = []
    interp = ctx["interpreter"]
    if not interp.get("path"):
        items.append({"id": "python", **TOOL_PLAN["python"]})
    java = probe_cmd(["java", "-version"])
    java_ok = any('version "17.' in l for l in java.get("head", []))
    if not java_ok:
        items.append({"id": "java17", **TOOL_PLAN["java17"]})
    if not shutil.which("git"):
        items.append({"id": "git", **TOOL_PLAN["git"]})
    if not (ctx["root"] / "gradlew.bat").is_file():
        items.append({"id": "gradle-wrapper", "package":
                      "project Gradle wrapper (repo file)",
                      "version": "8.7 (wrapper)",
                      "url": "restore from repo",
                      "verify": "gradlew.bat --version",
                      "installPath": "<repo root>",
                      "impact": "repo file; no install"})
    plan_core = {"items": items}
    plan_hash = sha256_bytes(json.dumps(plan_core, sort_keys=True).encode())
    return {"schemaVersion": SCHEMA, "mode": "plan", "planHash": plan_hash,
            "items": items,
            "note": "plan is read-only; --apply writes only "
                    "var/local_dev/bindings.local.json when absent",
            "bindings": {"path": str(bindings_path()),
                         "present": bindings_path().is_file()}}


def action_bootstrap(ctx, argv):
    parser = argparse.ArgumentParser(prog="aw-dev bootstrap")
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--plan-sha")
    parser.add_argument("--root")
    args = parser.parse_args(argv)
    ctx2 = prepare_ctx(args.root, ctx)
    plan = compute_plan(ctx2)
    if not args.apply:
        plan["observed"] = {"interpreter": ctx2["interpreter"],
                            "root": str(ctx2["root"])}
        print(json.dumps(plan, ensure_ascii=True, indent=2))
        return EXIT_OK
    if not args.plan_sha or args.plan_sha.lower() != plan["planHash"]:
        print(json.dumps({"error": "plan-sha-mismatch",
                          "expected": plan["planHash"]}, indent=2))
        return EXIT_USAGE
    path = bindings_path()
    if path.is_file():
        print(json.dumps({"applied": False, "reason": "bindings-exist",
                          "path": str(path)}, indent=2))
        return EXIT_OK
    example = load_json_file(
        Path(__file__).resolve().parent / "bindings.local.example.json") or {}
    binding = {"schemaVersion": "awx.aw-dev.bindings.v1",
               "python": ctx2["interpreter"]["path"],
               "repoRoot": str(ctx2["root"]), "createdAt": iso(),
               "createdBy": "aw-dev bootstrap --apply",
               "fields": example.get("fields")}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(binding, ensure_ascii=True, indent=2) + "\n",
                    encoding="utf-8")
    print(json.dumps({"applied": True, "path": str(path)}, indent=2))
    return EXIT_OK


# ---------------------------------------------------------------- status/stop

def runs_dir(ctx):
    return ctx["runsDir"]


def load_owned_run(ctx, run_id):
    if not CASE_ID_RE.match(run_id or ""):
        return None, "bad-run-id"
    run_dir = ctx["runsDir"] / run_id
    marker = load_json_file(run_dir / "aw-dev-run.json")
    if not isinstance(marker, dict) or marker.get("createdBy") != "aw-dev" \
            or marker.get("runId") != run_id:
        return None, "foreign-or-unknown-run"
    return run_dir, None


def _proc_create_time(pid):
    if os.name != "nt":
        return None
    import ctypes
    import ctypes.wintypes
    handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
    if not handle:
        return None
    try:
        c, e, k, u = (ctypes.wintypes.FILETIME() for _ in range(4))
        if not ctypes.windll.kernel32.GetProcessTimes(
                handle, ctypes.byref(c), ctypes.byref(e), ctypes.byref(k),
                ctypes.byref(u)):
            return None
        return (c.dwHighDateTime << 32 | c.dwLowDateTime) / 1e7 - 11644473600
    finally:
        ctypes.windll.kernel32.CloseHandle(handle)


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0 or pid == os.getpid():
        return False
    if os.name == "nt":
        import ctypes
        import ctypes.wintypes
        h = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
        if not h:
            return False
        try:
            code = ctypes.wintypes.DWORD()
            ok = ctypes.windll.kernel32.GetExitCodeProcess(
                h, ctypes.byref(code))
            return bool(ok) and code.value == 259
        finally:
            ctypes.windll.kernel32.CloseHandle(h)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _parse_iso(text):
    try:
        return datetime.fromisoformat(str(text).replace("Z", "+00:00"))\
            .timestamp()
    except (ValueError, TypeError):
        return None


def owned_subruns(run_dir):
    """Yield run_verified output dirs belonging to this aw-dev run."""
    verify = run_dir / "verify"
    steps_root = run_dir / "steps"
    if verify.is_dir():
        yield verify
    if steps_root.is_dir():
        for sub in sorted(steps_root.iterdir()):
            if (sub / "run.json").is_file():
                yield sub


def delegate_runner(ctx, action, out_dir):
    cmd = [ctx["interpreter"]["path"], "-B",
           "scripts/run_verified_command.py", action, "--output",
           str(out_dir)]
    try:
        proc = subprocess.run(cmd, cwd=str(ctx["root"]),
                              env=scrub_env(ctx.get("env")),
                              capture_output=True, text=True, timeout=120)
        data = json.loads(proc.stdout or "{}")
        return proc.returncode, data
    except (OSError, subprocess.TimeoutExpired, ValueError) as exc:
        return EXIT_USAGE, {"error": type(exc).__name__}


def action_status(ctx, argv):
    parser = argparse.ArgumentParser(prog="aw-dev status")
    parser.add_argument("--run", dest="run_id", required=True)
    parser.add_argument("--root")
    args = parser.parse_args(argv)
    ctx2 = prepare_ctx(args.root, ctx)
    run_dir, err = load_owned_run(ctx2, args.run_id)
    if err:
        print(json.dumps({"error": err, "runId": args.run_id}, indent=2))
        return EXIT_USAGE
    observations = []
    for sub in owned_subruns(run_dir):
        code, data = delegate_runner(ctx2, "status", sub)
        data["outDir"] = str(sub)
        observations.append(data)
    result = load_json_file(run_dir / "result.json")
    print(json.dumps({"runId": args.run_id, "observations": observations,
                      "result": (result or {}).get("result"),
                      "exitCode": (result or {}).get("exitCode")},
                     indent=2))
    return EXIT_OK


def action_stop(ctx, argv, runner=None):
    parser = argparse.ArgumentParser(prog="aw-dev stop")
    parser.add_argument("--run", dest="run_id", required=True)
    parser.add_argument("--root")
    args = parser.parse_args(argv)
    ctx2 = prepare_ctx(args.root, ctx)
    run_dir, err = load_owned_run(ctx2, args.run_id)
    if err:
        print(json.dumps({"error": err, "runId": args.run_id, "kills": 0},
                         indent=2))
        return EXIT_USAGE
    runner = runner or delegate_runner
    kills, observations = 0, []
    for sub in owned_subruns(run_dir):
        rec = load_json_file(sub / "run.json") or {}
        pid = rec.get("pid")
        status = rec.get("status")
        if status != "running":
            observations.append({"outDir": str(sub), "status": status,
                                 "stopped": False})
            continue
        started = _parse_iso(rec.get("startedAt"))
        create = _proc_create_time(pid) if isinstance(pid, int) else None
        alive = pid_alive(pid)
        consistent = (alive and create is not None and started is not None
                      and started - 300 <= create <= time.time() + 60)
        if not (alive and consistent):
            observations.append({"outDir": str(sub), "status": status,
                                 "pid": pid, "stopped": False,
                                 "reason": ("pid-not-alive" if not alive else
                                            "pid-identity-unproven")})
            continue
        code, data = runner(ctx2, "stop", sub)
        kills += 1
        observations.append({"outDir": str(sub), "runnerExit": code,
                             "stopped": True, "observation": data})
    refused = any(o.get("reason") == "pid-identity-unproven"
                  for o in observations)
    print(json.dumps({"runId": args.run_id, "kills": kills,
                      "observations": observations}, indent=2))
    return EXIT_USAGE if refused else EXIT_OK


# ---------------------------------------------------------------- report

def action_report(ctx, argv):
    parser = argparse.ArgumentParser(prog="aw-dev report")
    parser.add_argument("--last", type=int, default=10)
    parser.add_argument("--root")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    ctx2 = prepare_ctx(args.root, ctx)
    runs = []
    base = ctx2["runsDir"]
    if base.is_dir():
        for d in base.iterdir():
            res = load_json_file(d / "result.json")
            if isinstance(res, dict) and res.get("runId"):
                runs.append(res)
    runs.sort(key=lambda r: r.get("startedAt") or "")
    runs = runs[-max(args.last, 1):]
    rows = []
    for r in runs:
        counts = r.get("testCounts") or {}
        rows.append({"runId": r.get("runId"), "caseId": r.get("caseId"),
                     "action": r.get("actionId"), "result": r.get("result"),
                     "exitCode": r.get("exitCode"),
                     "executed": counts.get("executed"),
                     "durationMs": r.get("durationMs"),
                     "startedAt": r.get("startedAt"),
                     "reasonCode": r.get("reasonCode")})
    ledger = ctx2["root"] / "data" / "agent-handoff" / "jev-spend" / \
        "ledger.jsonl"
    ledger_note = {"present": ledger.is_file()}
    if ledger.is_file():
        try:
            total, rows_n = 0.0, 0
            for line in ledger.read_text(encoding="utf-8",
                                         errors="replace").splitlines()[-5000:]:
                if not line.strip():
                    continue
                rows_n += 1
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                cost = entry.get("costUsd")
                if isinstance(cost, (int, float)):
                    total += cost
            ledger_note.update(rows=rows_n,
                               costUsdApprox=round(total, 6),
                               note="float ledger; advisory only")
        except OSError:
            pass
    tally_path = ctx2["root"] / "scripts" / "jev_spend_tally.py"
    registry = load_registry()
    pending = [c["id"] for c in registry.get("cases", [])
               if files_missing(ctx2["root"], all_required_files(c))]
    report = {"schemaVersion": SCHEMA, "generatedAt": iso(),
              "root": str(ctx2["root"]), "runs": rows,
              "runCount": len(rows),
              "pendingCases": sorted(set(pending)),
              "ledger": ledger_note,
              "tallyTool": ("present" if tally_path.is_file() else "absent"),
              "uncontrolledCost":
                  "Cline/agent coding-model spend is not metered by this "
                  "ledger (out of scope, unmeasured)"}
    out_dir = ctx2["root"] / "var" / "local_dev" / "reports"
    if args.out:
        out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = utcnow().strftime("%Y%m%d-%H%M%S")
    jpath = out_dir / ("report-%s.json" % stamp)
    mpath = out_dir / ("report-%s.md" % stamp)
    jpath.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n",
                     encoding="utf-8")
    lines = ["# aw-dev report %s" % stamp, "",
             "| runId | case | result | exit | tests | reason |", 
             "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            r["runId"], r["caseId"], r["result"], r["exitCode"],
            r["executed"], r["reasonCode"]))
    lines += ["", "pendingCases: %s" % ", ".join(report["pendingCases"]),
              "ledger: %s" % json.dumps(ledger_note, ensure_ascii=True),
              "uncontrolledCost: %s" % report["uncontrolledCost"], ""]
    mpath.write_text("\n".join(lines), encoding="utf-8")
    report["reportJson"] = str(jpath)
    report["reportMd"] = str(mpath)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return EXIT_OK


def all_required_files(case):
    files = list(case.get("requiredFiles") or [])
    for step in case.get("steps") or []:
        files += step.get("requiredFiles") or []
    return files


# ---------------------------------------------------------------- handoff

def action_handoff(ctx, argv):
    parser = argparse.ArgumentParser(prog="aw-dev handoff")
    parser.add_argument("--root")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    ctx2 = prepare_ctx(args.root, ctx)
    root = ctx2["root"]
    bundle = ["aw-dev.cmd", "aw-dev.ps1"]
    for base in ("tools/local_dev", "docs/local_dev"):
        bdir = root / base
        if bdir.is_dir():
            for p in sorted(bdir.rglob("*")):
                if p.is_file() and "__pycache__" not in p.parts:
                    bundle.append(str(p.relative_to(root))
                                  .replace("\\", "/"))
    excluded = ["**/__pycache__/**", "**/.env*", "**/*.key", "**/*.pem",
                "**/providers.json", "**/.secrets/**", "**/node_modules/**",
                "**/venv/**", "**/.gradle/**", "**/session*.db*",
                "var/local_dev/runs/** (raw logs excluded)"]
    files = []
    for rel in bundle:
        p = root / rel
        if not p.is_file():
            continue
        files.append({"path": rel, "sha256_12": sha256_file(p)[:12],
                      "bytes": p.stat().st_size})
    out_dir = Path(args.out) if args.out else \
        root / "data" / "agent-handoff" / \
        "devin-local-dev-foundation-20260930"
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = {"schemaVersion": SCHEMA, "generatedAt": iso(),
               "root": str(root), "files": files, "excluded": excluded,
               "note": "paths are repo-relative; bundle ships files, "
                       "never secrets/venv/caches/raw logs"}
    out = out_dir / "BUNDLE.json"
    out.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n",
                   encoding="utf-8")
    payload["out"] = str(out)
    print(json.dumps(payload, ensure_ascii=True, indent=2))
    return EXIT_OK


# ---------------------------------------------------------------- list/start

def action_list(ctx):
    registry = load_registry()
    rows = []
    for case in registry.get("cases", []):
        missing = files_missing(ctx["root"], all_required_files(case))
        owner = case.get("upstreamOwner", "devin")
        avail = ("ready" if not missing else
                 "pending_upstream" if owner != "aw-dev" else "missing_env")
        rows.append({"id": case["id"], "action": case["action"],
                     "network": case.get("network"),
                     "upstreamOwner": owner,
                     "availability": avail,
                     "missing": missing})
    print(json.dumps({"schemaVersion": SCHEMA, "cases": rows}, indent=2))
    return EXIT_OK


def action_start(argv):
    parser = argparse.ArgumentParser(prog="aw-dev start")
    parser.add_argument("--target")
    parser.add_argument("--isolated", action="store_true")
    parser.add_argument("--root")
    args = parser.parse_args(argv)
    if args.target and (check_value(args.target) or
                        not ARG_TOKEN_RE.match(args.target)):
        print(json.dumps({"error": "bad-target"}, indent=2))
        return EXIT_USAGE
    print(json.dumps({
        "schemaVersion": SCHEMA, "action": "start",
        "result": "NOT_RUN_ENV", "reasonCode": "HOLD_NOT_ISOLATED",
        "detail": "no verified isolated launcher (mock provider + separate "
                  "store + owned port) exists for this checkout; Start-RAG "
                  "ForceRestart is a shared-owner launcher and is never "
                  "invoked by aw-dev"}, indent=2))
    return EXIT_NOT_RUN_ENV


def action_resolve_python():
    found = resolve_python()
    if found:
        print(found["path"])
        return EXIT_OK
    return EXIT_NOT_RUN_ENV


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        print(json.dumps({"error": "usage: aw-dev <action> [options]",
                          "actions": list(ACTIONS)}, indent=2))
        return EXIT_USAGE
    action = argv[0]
    rest = argv[1:]
    ctx = {"env": dict(os.environ)}
    ctx["interpreter"] = interpreter_info(
        resolve_python(ctx["env"]) or {"path": sys.executable,
                                       "source": "self"})
    try:
        if action == "resolve-python":
            return action_resolve_python()
        if action == "doctor":
            return doctor(rest)
        if action == "bootstrap":
            return action_bootstrap(ctx, rest)
        if action in ("test", "smoke"):
            return action_test_or_smoke(ctx, action, rest)
        if action == "status":
            return action_status(ctx, rest)
        if action == "stop":
            return action_stop(ctx, rest)
        if action == "report":
            return action_report(ctx, rest)
        if action == "handoff":
            return action_handoff(ctx, rest)
        if action == "list":
            return action_list(prepare_ctx(None, ctx))
        if action == "start":
            return action_start(rest)
        print(json.dumps({"error": "unknown-action:" + action}, indent=2))
        return EXIT_USAGE
    except BrokenPipeError:
        return EXIT_USAGE


if __name__ == "__main__":
    raise SystemExit(main())
