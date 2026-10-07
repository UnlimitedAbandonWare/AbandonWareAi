#!/usr/bin/env python3
"""git_ship.py -- bounded status/junk/scan/commit/push/verify/ship pipeline.

Companion to Git-Ship.bat. Automates the 2026-10-03 hand-run flow for when a
user explicitly asks to commit/push this tree. Defaults are read-only or
dry-run; real changes need --apply plus approval env vars:

  AWX_PUBLISH_APPROVED=1   required for a real `push --apply`
  AWX_SHIP_SKIP_GUARD=1    with --skip-guard --reason "..." adds --no-verify

Rules honored: every git call uses `git --no-optional-locks`,
GIT_TERMINAL_PROMPT=0, no index.lock delete/move, no --amend/-a/force,
no main/master push, never prints a full secret value (prefix+length only),
no permanent `git config` writes (publish.allow* passed via one-shot -c).

Exit codes: 0 ok | 2 real secret found | 3 index.lock timeout |
4 policy/approval refusal | 5 post-push remote sha mismatch |
6 pre-commit hook guard refused | 1 other error.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import tempfile
from pathlib import Path

EXIT_OK = 0
EXIT_SECRET = 2
EXIT_LOCK = 3
EXIT_POLICY = 4
EXIT_VERIFY = 5
EXIT_HOOK = 6
EXIT_ERROR = 1

DEFAULT_GIT = os.environ.get("AWX_GIT_EXE", "git")
GIT_TIMEOUT = 180
# pre-push 훅(가드 + publish-review)이 트리 크기에 따라 10분을 넘을 수 있다.
PUSH_TIMEOUT_S = 1500
SAFE_ENV = {"GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}

PROTECTED_BRANCHES = {"main", "master"}
DEFAULT_LOCK_WAIT_S = 3.0
DEFAULT_LOCK_RETRIES = 10
DEFAULT_MAX_MB = 10.0
WARN_BLOB_MB = 50.0
BLOCK_BLOB_MB = 100.0
LAST_COMMIT_JSON = Path("var/codex-assist-git-ship/last-commit.json")

# --- secret patterns (loose: no left boundary; boundary applied in classify) ---
PATTERNS = [
    ("openai", re.compile(r"sk-(?:proj-|ant-|svcacct-)?[A-Za-z0-9_-]{20,}")),
    ("google-ai", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
    ("github", re.compile(
        r"(?:ghp|gho|ghu|ghs|ghr|ght)_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,}")),
    ("slack", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("aws", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("vercel", re.compile(r"vck_[A-Za-z0-9]{20,}")),
    ("groq", re.compile(r"gsk_[A-Za-z0-9]{20,}")),
    ("huggingface", re.compile(r"hf_[A-Za-z0-9]{30,}")),
    ("private-key", re.compile(r"-{5}BEGIN [A-Z0-9 ]*PRIVATE KEY-{5}")),
]
WORD_CHARS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
FAKE_MARKERS = (
    "fake", "synthetic", "example", "xxxx", "0123", "1234", "abcdef",
    "dummy", "placeholder", "redacted", "sample", "notreal", "changeme",
    "test-key", "testkey",
)
MAX_FINDINGS = 500

# --- junk rules: single source of truth (spec: constant list, no config file) ---
_QUAR_CFG = re.compile(r"^application.*\.(yml|yaml|properties)$", re.IGNORECASE)
_ROOT_OUT = re.compile(r"^_.*_out\.txt$", re.IGNORECASE)


def _norm(path: str) -> str:
    return path.replace("\\", "/")


def _is_pycache(path: str, _st: str) -> bool:
    p = _norm(path)
    return "__pycache__/" in p or p.endswith(".pyc")


def _is_root_out_txt(path: str, _st: str) -> bool:
    p = _norm(path)
    return "/" not in p and bool(_ROOT_OUT.match(p))


def _is_quarantine_cfg(path: str, _st: str) -> bool:
    p = _norm(path)
    return p.startswith("app/quarantine/") and bool(
        _QUAR_CFG.match(p.rsplit("/", 1)[-1]))


_DEVNULL_NAMES = {"$null", "nul"}
_WIN_JUNK_NAMES = {"thumbs.db", "desktop.ini"}
_WIN_JUNK_EXT = (".lnk", ".url")


def _is_devnull_name(path: str, _st: str) -> bool:
    """cmd/PowerShell에서 `> $null` 같은 리다이렉트 실수로 생긴 파일."""
    return _norm(path).rsplit("/", 1)[-1].lower() in _DEVNULL_NAMES


def _is_windows_junk(path: str, _st: str) -> bool:
    leaf = _norm(path).rsplit("/", 1)[-1].lower()
    return leaf in _WIN_JUNK_NAMES or leaf.endswith(_WIN_JUNK_EXT)


JUNK_RULES = [
    ("python-cache", _is_pycache),
    ("root-out-txt", _is_root_out_txt),
    ("quarantine-config", _is_quarantine_cfg),
    ("dev-null-name", _is_devnull_name),
    ("windows-shell-junk", _is_windows_junk),
]


def sanitize(text: str, limit: int = 200) -> str:
    text = re.sub(r"(://|//)[^/@\s]+@", r"\1***@", text or "")
    lines = [ln for ln in text.splitlines() if ln.strip()][:2]
    return " | ".join(lines)[:limit]


class ShipError(Exception):
    def __init__(self, code: int, message: str, details: dict | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}


class Git:
    def __init__(self, root: str, git_exe: str | None = None):
        self.root = root
        self.exe = git_exe or DEFAULT_GIT

    def run(self, args, timeout: int = GIT_TIMEOUT, input_text: str | None = None):
        argv = [self.exe, "--no-optional-locks", "-C", self.root] + [str(a) for a in args]
        env = dict(os.environ)
        env.update(SAFE_ENV)
        try:
            proc = subprocess.run(
                argv, capture_output=True, timeout=timeout, env=env,
                text=True, encoding="utf-8", errors="replace",
                input=input_text)
        except FileNotFoundError:
            raise ShipError(EXIT_ERROR, f"git-not-found: {self.exe}")
        except subprocess.TimeoutExpired:
            raise ShipError(EXIT_ERROR, "git-timeout")
        return proc.returncode, proc.stdout, proc.stderr

    def out(self, args, timeout: int = GIT_TIMEOUT, input_text: str | None = None) -> str:
        rc, o, e = self.run(args, timeout=timeout, input_text=input_text)
        if rc != 0:
            raise ShipError(EXIT_ERROR, f"git {' '.join(map(str, args))}: {sanitize(e)}")
        return o

    def try_out(self, args, timeout: int = GIT_TIMEOUT, input_text: str | None = None) -> str | None:
        rc, o, _e = self.run(args, timeout=timeout, input_text=input_text)
        return o if rc == 0 else None


def git_dir(g: Git) -> Path:
    return Path(g.out(["rev-parse", "--absolute-git-dir"]).strip())


def index_lock_path(g: Git) -> Path:
    return git_dir(g) / "index.lock"


def head_sha(g: Git) -> str:
    return g.out(["rev-parse", "HEAD"]).strip()


def current_branch(g: Git) -> str:
    return g.out(["rev-parse", "--abbrev-ref", "HEAD"]).strip()


def repo_root(g: Git) -> Path:
    return Path(g.out(["rev-parse", "--show-toplevel"]).strip())


# --------------------------------------------------------------------------
# status
# --------------------------------------------------------------------------

def cmd_status(g: Git, _args) -> dict:
    branch = current_branch(g)
    head = head_sha(g)
    upstream = g.try_out(["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
    upstream = upstream.strip() if upstream else None
    ahead = behind = None
    if upstream:
        counts = g.try_out(["rev-list", "--left-right", "--count", f"{upstream}...HEAD"])
        if counts:
            left, right = counts.split()[:2]
            behind, ahead = int(left), int(right)
    staged = g.out(["diff", "--cached", "--name-only"]).splitlines()
    porcelain = g.out(["status", "--porcelain"]).splitlines()
    lock = index_lock_path(g)
    lock_info = {"present": False}
    if lock.is_file():
        st = lock.stat()
        lock_info = {"present": True, "sizeBytes": st.st_size,
                     "ageSeconds": round(time.time() - st.st_mtime, 1)}
    return {
        "ok": True, "branch": branch, "head": head, "upstream": upstream,
        "ahead": ahead, "behind": behind,
        "stagedCount": len([p for p in staged if p]),
        "changedCount": len([p for p in porcelain if p]),
        "indexLock": lock_info,
    }


# --------------------------------------------------------------------------
# junk
# --------------------------------------------------------------------------

def staged_name_status(g: Git) -> list[tuple[str, str]]:
    out = g.out(["diff", "--cached", "--name-status", "-z"])
    parts = [p for p in out.split("\0") if p]
    rows = []
    i = 0
    while i < len(parts):
        st = parts[i]
        i += 1
        if st.startswith(("R", "C")):
            i += 1  # old path; keep the new one
            if i >= len(parts):
                break
            rows.append((st, parts[i]))
            i += 1
        else:
            rows.append((st, parts[i]))
            i += 1
    return rows


def staged_blob_sizes(g: Git) -> dict[str, int]:
    listing = g.out(["ls-files", "-s"])
    sha_by_path = {}
    for ln in listing.splitlines():
        meta, _, p = ln.partition("\t")
        cols = meta.split()
        if len(cols) >= 3 and cols[2] == "0":
            sha_by_path[p] = cols[1]
    sizes: dict[str, int] = {}
    if not sha_by_path:
        return sizes
    blob_shas = list(sha_by_path.values())
    chk = g.out(["cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"],
                input_text="\n".join(blob_shas) + "\n")
    size_by_sha = {}
    for ln in chk.splitlines():
        cols = ln.split()
        if len(cols) == 3 and cols[1] == "blob":
            size_by_sha[cols[0]] = int(cols[2])
    for p, sha in sha_by_path.items():
        if sha in size_by_sha:
            sizes[p] = size_by_sha[sha]
    return sizes


def check_ignored(g: Git, paths: list[str]) -> set[str]:
    if not paths:
        return set()
    rc, o, _e = g.run(["check-ignore", "--no-index", "-z", "--stdin"],
                      input_text="\0".join(paths) + "\0")
    if rc not in (0, 1):
        return set()
    return {p for p in o.split("\0") if p}


def find_junk(g: Git, max_mb: float) -> list[dict]:
    rows = staged_name_status(g)
    sizes = staged_blob_sizes(g)
    added = [p for st, p in rows if st == "A"]
    ignored = check_ignored(g, added)
    findings = []
    for st, p in rows:
        if st == "D":
            continue  # staged deletions are never touched
        for rule_id, fn in JUNK_RULES:
            if fn(p, st):
                findings.append({"path": p, "rule": rule_id, "status": st})
                break
        else:
            if p in sizes and sizes[p] > max_mb * 1024 * 1024:
                findings.append({"path": p, "rule": "oversize-blob",
                                 "status": st, "sizeBytes": sizes[p]})
                continue
            if st == "A" and p in ignored:
                findings.append({"path": p, "rule": "ignored-force-add", "status": st})
    return findings


def cmd_junk(g: Git, args) -> dict:
    findings = find_junk(g, args.max_mb)
    applied = False
    if findings and args.apply:
        paths = [f["path"] for f in findings]
        for i in range(0, len(paths), 200):
            g.out(["restore", "--staged", "--"] + paths[i:i + 200])
        applied = True
    return {"ok": True, "applied": applied, "count": len(findings),
            "findings": findings}


# --------------------------------------------------------------------------
# scan
# --------------------------------------------------------------------------

def _path_is_test(path: str) -> bool:
    p = _norm(path)
    return (p.startswith("scripts/test_") or "/scripts/test_" in p
            or p.startswith("fixtures/") or "/fixtures/" in p
            or p.startswith("src/test/") or "/src/test/" in p)


def _has_fake_marker(value: str) -> bool:
    low = value.lower()
    return any(m in low for m in FAKE_MARKERS)


def _preview(value: str) -> str:
    return f"{value[:6]}\u2026(len {len(value)})"


def _classify(line: str, match: re.Match, path: str) -> str:
    if match.start() > 0 and line[match.start() - 1] in WORD_CHARS:
        return "word"
    if _path_is_test(path) or _has_fake_marker(match.group(0)):
        return "fake"
    return "real"


def _scan_added(diff_text: str, sha_label: str) -> list[dict]:
    findings = []
    path = None
    for ln in diff_text.splitlines():
        if ln.startswith("+++ "):
            p = ln[4:].strip()
            path = p[2:] if p.startswith("b/") else p
            if path == "/dev/null":
                path = None
            continue
        if ln.startswith("diff --git"):
            path = None
            continue
        if not ln.startswith("+") or path is None:
            continue
        body = ln[1:]
        for rule_id, rx in PATTERNS:
            for m in rx.finditer(body):
                findings.append({
                    "rule": rule_id, "class": _classify(body, m, path),
                    "path": path, "ref": sha_label,
                    "preview": _preview(m.group(0)),
                })
                if len(findings) >= MAX_FINDINGS:
                    return findings
    return findings


def _scan_range_diffs(g: Git, range_args: list[str]) -> list[dict]:
    out = g.out(["log", "--format=@@@%H", "--unified=0", "-p"] + range_args)
    findings: list[dict] = []
    for block in re.split(r"(?=^@@@)", out, flags=re.MULTILINE):
        if not block.strip():
            continue
        sha = block.splitlines()[0].lstrip("@").strip()[:12]
        findings.extend(_scan_added(block, sha))
        if len(findings) >= MAX_FINDINGS:
            break
    return findings[:MAX_FINDINGS]


def _load_branch_context(g: Git) -> dict:
    """configs/git-branch-context.json = branch-role SSOT (DEMO1-GIT-BRANCH-TOPOLOGY)."""
    cfg = Path(g.root) / "configs" / "git-branch-context.json"
    try:
        if cfg.is_file():
            data = json.loads(cfg.read_text(encoding="utf-8-sig"))
            if isinstance(data, dict):
                return data
    except (OSError, ValueError):
        return {}
    top = g.try_out(["rev-parse", "--show-toplevel"])
    if top and Path(top.strip()) != Path(g.root):
        try:
            cfg = Path(top.strip()) / "configs" / "git-branch-context.json"
            if cfg.is_file():
                data = json.loads(cfg.read_text(encoding="utf-8-sig"))
                if isinstance(data, dict):
                    return data
        except (OSError, ValueError):
            pass
    return {}


def resolve_range_args(g: Git, base: str | None, remote: str) -> tuple[list[str], str]:
    if base:
        if g.try_out(["rev-parse", "--verify", "--quiet", f"{base}^{{commit}}"]) is not None:
            return [f"{base}..HEAD"], base
        raise ShipError(EXIT_ERROR, f"base-ref-not-found: {base}")
    # Base order (DEMO1-GIT-BRANCH-TOPOLOGY): @{u} -> config remote/workBranch
    # -> <remote>/HEAD only when it shares history with HEAD -> all-local
    # fallback. A base with no merge-base is never picked; skips are recorded
    # in the returned label.
    skipped: list[str] = []

    def usable(ref: str) -> bool:
        if g.try_out(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"]) is None:
            skipped.append(f"{ref}=absent")
            return False
        if g.try_out(["merge-base", ref, "HEAD"]) is None:
            skipped.append(f"{ref}=unrelated-history")
            return False
        return True

    upstream = g.try_out(
        ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
    if upstream and usable(upstream.strip()):
        ref = upstream.strip()
        return [f"{ref}..HEAD"], ref
    cfg = _load_branch_context(g)
    cfg_ref = (f"{cfg.get('remote', remote)}/{cfg['workBranch']}"
               if cfg.get("workBranch") else None)
    if cfg_ref and usable(cfg_ref):
        return [f"{cfg_ref}..HEAD"], cfg_ref
    upstream_default = g.try_out(
        ["symbolic-ref", "--short", f"refs/remotes/{remote}/HEAD"])
    if upstream_default and usable(upstream_default.strip()):
        ref = upstream_default.strip()
        return [f"{ref}..HEAD"], ref
    label = f"HEAD-not-on-{remote}"
    if skipped:
        label += "(skip:" + ",".join(skipped) + ")"
    return ["HEAD", "--not", f"--remotes={remote}"], label


def run_scan(g: Git, staged: bool, range_args: list[str] | None = None,
             range_label: str = "") -> dict:
    if staged:
        findings = _scan_added(g.out(["diff", "--cached", "--unified=0"]), "staged")
        scope = "staged"
    else:
        findings = _scan_range_diffs(g, range_args or [])
        scope = range_label or "..".join(range_args or [])
    counts = {"real": 0, "fake": 0, "word": 0}
    for f in findings:
        counts[f["class"]] += 1
    return {"ok": counts["real"] == 0, "scope": scope, "counts": counts,
            "findings": findings}


def cmd_scan(g: Git, args) -> dict:
    if args.staged:
        return run_scan(g, staged=True)
    base = args.range
    if base and ".." in base:
        left = base.split("..", 1)[0]
        if g.try_out(["rev-parse", "--verify", "--quiet",
                      f"{left}^{{commit}}"]) is None:
            raise ShipError(EXIT_ERROR, f"base-ref-not-found: {left}")
        range_args, label = [base], base
    elif base:
        range_args, label = resolve_range_args(g, base, args.remote)
    else:
        range_args, label = resolve_range_args(g, None, args.remote)
    return run_scan(g, staged=False, range_args=range_args, range_label=label)


# --------------------------------------------------------------------------
# staged guard (the real pre-commit checker, reused -- never reimplemented)
# --------------------------------------------------------------------------

GUARD_SCRIPT = Path(__file__).resolve().parent / "git_staged_guard.py"
GUARD_TIMEOUT = 120


def run_staged_guard(root: str, git_exe: str | None = None) -> dict:
    """Run scripts/git_staged_guard.py as a subprocess and return its JSON
    verdict -- the same checker the pre-commit hook runs. {"ok": None} means
    the checker itself could not run; that alone never blocks a commit."""
    if not GUARD_SCRIPT.is_file():
        return {"ok": None, "reason": "guard-script-missing"}
    env = dict(os.environ)
    env.update(SAFE_ENV)
    exe_path = Path(git_exe or DEFAULT_GIT)
    if exe_path.is_absolute() and exe_path.is_file():
        env["PATH"] = str(exe_path.parent) + os.pathsep + env.get("PATH", "")
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(GUARD_SCRIPT), "--root", str(root)],
            capture_output=True, timeout=GUARD_TIMEOUT, env=env,
            text=True, encoding="utf-8", errors="replace")
    except (OSError, subprocess.TimeoutExpired):
        return {"ok": None, "reason": "guard-run-unavailable"}
    try:
        lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
        return json.loads(lines[-1])
    except (IndexError, ValueError):
        return {"ok": None, "reason": "guard-output-unreadable"}


def map_guard_findings(g: Git, findings: list[dict] | None) -> list[dict]:
    """pathHash -> staged path. 해시를 stage된 경로 목록으로만 되짚는다.
    값(비밀 내용)은 절대 읽거나 보여 주지 않는다."""
    out = g.out(["diff", "--cached", "--name-only", "-z"])
    by_hash = {hashlib.sha256(p.encode("utf-8")).hexdigest(): p
               for p in out.split("\0") if p}
    mapped = []
    for f in findings or []:
        h = str(f.get("pathHash") or "")
        mapped.append({"path": by_hash.get(h) or f"pathHash:{h[:12]}",
                       "rule": str(f.get("rule") or "unknown")})
    return mapped


def _hook_blocked_error(g: Git, guard: dict) -> ShipError:
    reason = str(guard.get("reason") or "")
    msg = "pre-commit hook check (git_staged_guard) refused"
    if reason:
        msg += f": {reason}"
    return ShipError(EXIT_HOOK, msg, details={
        "hookFindings": map_guard_findings(g, guard.get("findings")),
        "reason": reason})


def _is_hook_block(stderr: str) -> bool:
    return any(m in stderr for m in (
        "[AWX][git-guard]", "git_staged_guard", "awx.git-staged-scan",
        "pre-commit"))


# --------------------------------------------------------------------------
# commit
# --------------------------------------------------------------------------

def _skip_guard_ok(g: Git, args, scan_result: dict) -> tuple[bool, str]:
    if not args.skip_guard:
        return False, ""
    if scan_result["counts"]["real"] != 0:
        return False, "scan-real-not-zero"
    if os.environ.get("AWX_SHIP_SKIP_GUARD") != "1":
        return False, "env-AWX_SHIP_SKIP_GUARD-missing"
    if not (args.reason or "").strip():
        return False, "reason-empty"
    return True, "user-approved"


@contextmanager
def _commit_index(g, paths):
    """Scan the worktree bytes --only will commit, without altering staging."""
    if paths is None:
        yield
        return
    if not paths:
        raise ShipError(EXIT_POLICY, "empty-commit-paths")
    previous = os.environ.get("GIT_INDEX_FILE")
    with tempfile.TemporaryDirectory(prefix="gitship-index-") as tmp:
        os.environ["GIT_INDEX_FILE"] = str(Path(tmp) / "index")
        try:
            g.out(["read-tree", "HEAD"])
            specs = [":(literal)" + p for p in paths]
            g.out(["add", "--pathspec-from-file=-", "--pathspec-file-nul"],
                  input_text="\0".join(specs) + "\0")
            yield
        finally:
            if previous is None:
                os.environ.pop("GIT_INDEX_FILE", None)
            else:
                os.environ["GIT_INDEX_FILE"] = previous


def cmd_commit(g: Git, args, paths=None) -> dict:
    paths = None if paths is None else sorted(set(paths))
    with _commit_index(g, paths):
        junk = find_junk(g, args.max_mb)
        scan = run_scan(g, staged=True)
        if scan["counts"]["real"] > 0:
            raise ShipError(
                EXIT_SECRET,
                f"commit refused: {scan['counts']['real']} real secret(s) staged")
        no_verify, why = _skip_guard_ok(g, args, scan)
        if args.skip_guard and not no_verify:
            raise ShipError(EXIT_POLICY, f"--skip-guard refused: {why}")
        hook_state = "skipped-no-verify" if no_verify else "ok"
        if not no_verify:
            guard = run_staged_guard(g.root, g.exe)
            if guard.get("ok") is False:
                raise _hook_blocked_error(g, guard)
            if guard.get("ok") is None:
                hook_state = f"unavailable:{guard.get('reason')}"
        staged_count = len(staged_name_status(g))
    plan = {
        "message": args.message,
        "stagedCount": staged_count,
        "junkWarnings": len(junk),
        "scanCounts": scan["counts"],
        "hookCheck": hook_state,
        "noVerify": no_verify,
    }
    if not args.apply:
        plan["dryRun"] = True
        return {"ok": True, "commit": plan}
    lock = index_lock_path(g)
    attempts = 0
    commit_rc = None
    last_err = ""
    while attempts < args.lock_retries:
        if lock.is_file():
            attempts += 1
            time.sleep(args.lock_wait)
            continue
        argv = ["commit", "-m", args.message]
        if args.message2:
            argv += ["-m", args.message2]
        if no_verify:
            argv.append("--no-verify")
        commit_input = None
        if paths is not None:
            argv.insert(1, "--only")
            if len(paths) > 100:
                argv += ["--pathspec-from-file=-", "--pathspec-file-nul"]
                commit_input = "\0".join(":(literal)" + p for p in paths) + "\0"
            else:
                argv += ["--"] + [":(literal)" + p for p in paths]
        rc, _o, e = g.run(argv, input_text=commit_input)
        commit_rc = rc
        if rc == 0:
            break
        last_err = sanitize(e)
        if "index.lock" in e or "Unable to create" in e:
            attempts += 1
            time.sleep(args.lock_wait)
            continue
        if _is_hook_block(e):
            raise ShipError(EXIT_HOOK,
                            f"git commit blocked by pre-commit hook: {last_err}")
        raise ShipError(EXIT_ERROR, f"git commit failed: {last_err}")
    if commit_rc != 0:
        raise ShipError(EXIT_LOCK,
                        "index.lock timeout: 다른 에이전트 git 작업 중")
    sha = head_sha(g)
    files = [ln for ln in g.out(
        ["show", "--pretty=format:", "--name-only", "HEAD"]).splitlines() if ln]
    remaining = len([p for p in g.out(["status", "--porcelain"]).splitlines() if p])
    record = {"sha": sha, "fileCount": len(files),
              "junkUnstagedNotApplied": len(junk),
              "remainingChangedCount": remaining,
              "noVerify": no_verify, "atUtc": time.strftime(
                  "%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    try:
        target = repo_root(g) / LAST_COMMIT_JSON
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    except OSError:
        pass
    return {"ok": True, "commit": plan, "sha": sha,
            "fileCount": len(files), "remainingChangedCount": remaining,
            "record": str(LAST_COMMIT_JSON)}


# --------------------------------------------------------------------------
# push / verify
# --------------------------------------------------------------------------

def _push_url(g: Git, remote: str) -> str | None:
    url = g.try_out(["remote", "get-url", "--push", remote])
    if not url:
        url = g.try_out(["remote", "get-url", remote])
    return url.strip() if url else None


def _oversize_in_range(g: Git, range_args: list[str]) -> list[tuple[str, int]]:
    out = g.out(["rev-list", "--objects"] + range_args)
    shas, sha_path = [], {}
    for ln in out.splitlines():
        cols = ln.split(None, 1)
        if not cols:
            continue
        shas.append(cols[0])
        if len(cols) > 1:
            sha_path[cols[0]] = cols[1]
    if not shas:
        return []
    chk = g.out(["cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"],
                input_text="\n".join(shas) + "\n")
    big = []
    for ln in chk.splitlines():
        cols = ln.split()
        if len(cols) == 3 and cols[1] == "blob":
            size = int(cols[2])
            if size > WARN_BLOB_MB * 1024 * 1024:
                big.append((sha_path.get(cols[0], cols[0]), size))
    return big


def cmd_push(g: Git, args) -> dict:
    branch = current_branch(g)
    if branch in PROTECTED_BRANCHES:
        raise ShipError(EXIT_POLICY, f"push refused: protected branch {branch}")
    if branch.startswith("+") or ":" in branch:
        raise ShipError(EXIT_POLICY, "push refused: force/refspec form")
    remote = args.remote
    range_args, range_label = resolve_range_args(g, args.base, remote)
    scan = run_scan(g, staged=False, range_args=range_args, range_label=range_label)
    if scan["counts"]["real"] > 0:
        raise ShipError(
            EXIT_SECRET,
            f"push refused: {scan['counts']['real']} real secret(s) in {range_label}")
    big = _oversize_in_range(g, range_args)
    blockers = [(p, s) for p, s in big if s > BLOCK_BLOB_MB * 1024 * 1024]
    if blockers:
        raise ShipError(EXIT_POLICY, "push refused: blob(s) over 100MB: "
                        + ", ".join(f"{p}({s}B)" for p, s in blockers))
    warnings = [f"{p}({s}B)" for p, s in big]
    no_verify, why = _skip_guard_ok(g, args, scan)
    if args.skip_guard and not no_verify:
        raise ShipError(EXIT_POLICY, f"--skip-guard refused: {why}")
    plan = {"branch": branch, "remote": remote, "range": range_label,
            "scanCounts": scan["counts"], "bigBlobWarnings": warnings,
            "noVerify": no_verify}
    if not args.apply:
        plan["dryRun"] = True
        return {"ok": True, "push": plan}
    if os.environ.get("AWX_PUBLISH_APPROVED") != "1":
        raise ShipError(EXIT_POLICY, "push refused: AWX_PUBLISH_APPROVED!=1")
    url = _push_url(g, remote)
    if not url:
        raise ShipError(EXIT_ERROR, f"no push URL for remote {remote}")
    if __package__:
        from .git_publish_review import parse_target
    else:
        from git_publish_review import parse_target
    target = parse_target(url)
    allow_target = f"{target['host']}/{target['owner']}/{target['repo']}"
    argv = ["-c", f"publish.allowTarget={allow_target}",
            "-c", f"publish.allowRef=refs/heads/{branch}",
            "push", "-u", remote, branch]
    if no_verify:
        argv.append("--no-verify")
    rc, _o, e = g.run(argv, timeout=PUSH_TIMEOUT_S)
    if rc != 0:
        # Hooks emit diagnostics on stdout; retain only rule/reason codes.
        rules = {}
        for rule in re.findall(r"\[AWX\]\[git-guard\]\[BLOCK\].*? rule=([a-z-]+)", _o):
            rules[rule] = rules.get(rule, 0) + 1
        reasons = re.findall(r"\[publish-review\]\[reason\] ([a-z-]+)", _o)
        detail = "pre-push guard: " + ", ".join(f"{rule}={n}" for rule, n in sorted(rules.items())) if rules else ""
        if reasons:
            detail += ("; " if detail else "") + "publish-review: " + ", ".join(sorted(set(reasons)))
        raise ShipError(EXIT_ERROR, f"git push failed: {detail or sanitize(e)}")
    remote_sha = ls_remote_sha(g, remote, branch)
    head = head_sha(g)
    if remote_sha != head:
        raise ShipError(
            EXIT_VERIFY,
            f"post-push mismatch: remote={remote_sha or 'missing'} head={head}")
    plan.update({"pushed": True, "remoteSha": remote_sha, "head": head})
    return {"ok": True, "push": plan}


def ls_remote_sha(g: Git, remote: str, branch: str) -> str | None:
    out = g.try_out(["ls-remote", remote, f"refs/heads/{branch}"], timeout=60)
    if not out:
        return None
    for ln in out.splitlines():
        cols = ln.split()
        if len(cols) == 2 and cols[1] == f"refs/heads/{branch}":
            return cols[0]
    return None


def _pr_url(g: Git, remote: str, branch: str) -> str | None:
    url = _push_url(g, remote)
    if not url:
        return None
    u = url[:-4] if url.endswith(".git") else url
    if u.startswith("git@"):
        host, _, path = u[4:].partition(":")
        u = f"https://{host}/{path}"
    if not u.startswith("http"):
        return None
    return f"{u}/pull/new/{branch}"


def cmd_verify(g: Git, args) -> dict:
    branch = current_branch(g)
    head = head_sha(g)
    remote_sha = ls_remote_sha(g, args.remote, branch)
    return {
        "ok": remote_sha == head,
        "branch": branch, "head": head, "remoteSha": remote_sha,
        "remoteReachable": remote_sha is not None,
        "prUrl": _pr_url(g, args.remote, branch),
    }


# --------------------------------------------------------------------------
# ship
# --------------------------------------------------------------------------

def cmd_ship(g: Git, args) -> dict:
    result: dict = {"ok": True, "steps": {}}
    status = cmd_status(g, args)
    result["steps"]["status"] = status
    junk = find_junk(g, args.max_mb)
    result["steps"]["junk"] = {"count": len(junk), "findings": junk,
                               "applied": bool(args.apply and junk)}
    scan = run_scan(g, staged=True)
    result["steps"]["scan"] = scan
    if not args.apply:
        plan = {
            "commit": {"message": args.message,
                       "stagedCount": status["stagedCount"],
                       "refused": scan["counts"]["real"] > 0},
            "push": {"branch": status["branch"],
                     "protectedBranchRefused":
                         status["branch"] in PROTECTED_BRANCHES},
            "verify": "planned",
        }
        result["plan"] = plan
        result["dryRun"] = True
        return result
    if args.apply and junk:
        cmd_junk(g, args)
    cres = cmd_commit(g, args)
    result["steps"]["commit"] = cres
    pres = cmd_push(g, args)
    result["steps"]["push"] = pres
    vres = cmd_verify(g, args)
    result["steps"]["verify"] = vres
    result["ok"] = bool(vres.get("ok"))
    return result


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def _print_findings(findings: list[dict]) -> None:
    for f in findings:
        loc = f"{f['path']}@{f['ref']}" if f.get("ref") else f["path"]
        if "rule" in f and "class" in f:
            print(f"  {f['class']:5} {f['rule']:13} {loc} {f['preview']}")
        else:
            print(f"  {f['rule']:18} {loc}")


def _emit(res: dict, as_json: bool, pretty=None) -> None:
    if as_json:
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return
    if pretty:
        pretty(res)


def _pretty_status(r: dict) -> None:
    print(f"branch: {r['branch']}  head: {r['head'][:12]}")
    print(f"upstream: {r['upstream']}  ahead: {r['ahead']}  behind: {r['behind']}")
    print(f"staged: {r['stagedCount']}  changed: {r['changedCount']}")
    lk = r["indexLock"]
    extra = f" size={lk.get('sizeBytes')}B age={lk.get('ageSeconds')}s" if lk["present"] else ""
    print(f"index.lock: {'present' + extra if lk['present'] else 'absent'}")


def _pretty_junk(r: dict) -> None:
    print(f"junk candidates: {r['count']}  applied: {r['applied']}")
    _print_findings(r["findings"])


def _pretty_scan(r: dict) -> None:
    c = r["counts"]
    print(f"scan {r['scope']}: real={c['real']} fake={c['fake']} word={c['word']}")
    _print_findings(r["findings"])


def _pretty_commit(r: dict) -> None:
    p = r["commit"]
    tag = " [dry-run]" if p.get("dryRun") else ""
    print(f"commit{tag}: msg={p['message']!r} staged={p['stagedCount']} "
          f"junkWarn={p['junkWarnings']} hook={p.get('hookCheck')} "
          f"noVerify={p['noVerify']}")
    if r.get("sha"):
        print(f"  -> {r['sha']}  files={r['fileCount']} "
              f"remaining={r['remainingChangedCount']}")


def _pretty_push(r: dict) -> None:
    p = r["push"]
    tag = " [dry-run]" if p.get("dryRun") else ""
    print(f"push{tag}: {p['branch']} -> {p['remote']} range={p['range']} "
          f"noVerify={p['noVerify']}")
    if p.get("bigBlobWarnings"):
        print("  big-blob warnings: " + ", ".join(p["bigBlobWarnings"]))
    if p.get("pushed"):
        print(f"  pushed ok: remote={p['remoteSha'][:12]} head={p['head'][:12]}")


def _pretty_verify(r: dict) -> None:
    state = "match" if r["ok"] else (
        "unreachable" if not r["remoteReachable"] else "MISMATCH")
    print(f"verify {r['branch']}: remote={r['remoteSha'] or 'n/a'} "
          f"head={r['head'][:12]} -> {state}")
    if r.get("prUrl"):
        print(f"pr: {r['prUrl']}")


def _pretty_ship(r: dict) -> None:
    if r.get("dryRun"):
        print("DRY-RUN: 바뀐 것 없음")
    st = r["steps"]
    _pretty_status(st["status"])
    _pretty_junk(st["junk"])
    _pretty_scan(st["scan"])
    if r.get("dryRun"):
        print("plan:", json.dumps(r["plan"], ensure_ascii=False))
        return
    _pretty_commit(st["commit"])
    _pretty_push(st["push"])
    _pretty_verify(st["verify"])


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="git_ship.py", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--root", default=".", help="repo root (default: cwd)")
    p.add_argument("--git-exe", default=None, help="git binary (default: AWX_GIT_EXE or 'git')")
    p.add_argument("--json", action="store_true")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--json", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status", parents=[common],
                   help="branch/upstream/staged/lock status")

    pj = sub.add_parser("junk", parents=[common],
                        help="find staged junk; --apply unstages it")
    pj.add_argument("--apply", action="store_true")
    pj.add_argument("--max-mb", type=float, default=DEFAULT_MAX_MB)

    ps = sub.add_parser("scan", parents=[common],
                        help="scan staged or range for secret shapes")
    grp = ps.add_mutually_exclusive_group()
    grp.add_argument("--staged", action="store_true")
    grp.add_argument("--range", default=None, help="e.g. origin/main..HEAD")
    ps.add_argument("--remote", default="origin")

    pc = sub.add_parser("commit", parents=[common],
                        help="commit staged tree (lock-wait + guards)")
    pc.add_argument("--message", required=True)
    pc.add_argument("--message2", default=None)
    pc.add_argument("--apply", action="store_true")
    pc.add_argument("--skip-guard", action="store_true")
    pc.add_argument("--reason", default="")
    pc.add_argument("--max-mb", type=float, default=DEFAULT_MAX_MB)
    pc.add_argument("--lock-wait", type=float, default=DEFAULT_LOCK_WAIT_S)
    pc.add_argument("--lock-retries", type=int, default=DEFAULT_LOCK_RETRIES)

    pp = sub.add_parser("push", parents=[common],
                        help="push current branch (never main/master)")
    pp.add_argument("--apply", action="store_true")
    pp.add_argument("--remote", default="origin")
    pp.add_argument("--base", default=None)
    pp.add_argument("--skip-guard", action="store_true")
    pp.add_argument("--reason", default="")

    pv = sub.add_parser("verify", parents=[common],
                        help="compare remote branch sha vs HEAD")
    pv.add_argument("--remote", default="origin")

    ph = sub.add_parser("ship", parents=[common],
                        help="status->junk->scan->commit->push->verify")
    ph.add_argument("--message", default=None)
    ph.add_argument("--message2", default=None)
    ph.add_argument("--apply", action="store_true")
    ph.add_argument("--remote", default="origin")
    ph.add_argument("--base", default=None)
    ph.add_argument("--skip-guard", action="store_true")
    ph.add_argument("--reason", default="")
    ph.add_argument("--max-mb", type=float, default=DEFAULT_MAX_MB)
    ph.add_argument("--lock-wait", type=float, default=DEFAULT_LOCK_WAIT_S)
    ph.add_argument("--lock-retries", type=int, default=DEFAULT_LOCK_RETRIES)
    return p


HANDLERS = {
    "status": (cmd_status, _pretty_status),
    "junk": (cmd_junk, _pretty_junk),
    "scan": (cmd_scan, _pretty_scan),
    "commit": (cmd_commit, _pretty_commit),
    "push": (cmd_push, _pretty_push),
    "verify": (cmd_verify, _pretty_verify),
    "ship": (cmd_ship, _pretty_ship),
}


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    g = Git(args.root, args.git_exe)
    handler, pretty = HANDLERS[args.cmd]
    try:
        if args.cmd == "ship" and args.apply and not args.message:
            raise ShipError(EXIT_ERROR, "ship --apply needs --message")
        res = handler(g, args)
    except ShipError as exc:
        if args.json:
            print(json.dumps({"ok": False, "error": exc.message,
                              "exit": exc.code,
                              "details": exc.details}, ensure_ascii=False))
        else:
            print(f"error[{exc.code}]: {exc.message}", file=sys.stderr)
            for f in exc.details.get("hookFindings") or []:
                print(f"  {f['path']}  {f['rule']}", file=sys.stderr)
        return exc.code
    _emit(res, args.json, pretty)
    if args.cmd == "scan" and not res["ok"]:
        return EXIT_SECRET
    if args.cmd == "verify" and not res["ok"] and res["remoteReachable"]:
        return EXIT_VERIFY
    if args.cmd == "ship" and not res["ok"]:
        return EXIT_ERROR
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
