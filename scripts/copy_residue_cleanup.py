#!/usr/bin/env python3
"""Copy-residue cleanup: plan / apply / restore / report.

Classifies untracked residue under the candidate scopes into
DELETE / QUARANTINE / KEEP-INDEX / TRACKED_CANDIDATE / SKIP, executes only a
hash-pinned plan, moves ambiguous items to a quarantine tree with a manifest
and restore.ps1, and never touches git-tracked files, forbidden zones, or
work owned by active leases/journals.

Stdlib only. PowerShell is invoked only for the live lease/status inventory
and the running-process check; both can be replaced by --skip-file and
--process-list-file for tests.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

CHUNK = 1024 * 1024
RECENT_SECONDS = 72 * 3600
DONE_AGE_SECONDS = 7 * 24 * 3600

# Candidate scopes (repo-relative). Everything else is never an action target.
CANDIDATE_DIRS = [
    "agent-prompts",
    "data/agent-handoff",
    "__patch_drop__",
    "scratch",
    "toss",
    "logs",
    "output",
    "__reports__",
]
CANDIDATE_ROOT_GLOBS = (".gradle-",)  # root-level extra gradle homes

# Never an action target even inside a candidate scope.
FORBIDDEN_PREFIXES = [
    ".git", ".secrets", "var/meta-display-db", "build", "tools", "main",
    "app", "configs", "frontend", "src", "bin", ".gradle", "node_modules",
    "__patch_drop__/source-edit-locks",
    "__patch_drop__/source-edit-quarantine",
    "__patch_drop__/source-edit-heartbeats",
    "__patch_drop__/source-edit-events",
    "__patch_drop__/source-edit-scopes",
]
FORBIDDEN_NAMES = {".env", ".env.local", ".env.production", "chat.js", "apikey.txt"}

# Regenerable caches -> DELETE without an original (rebuildable by tools).
CACHE_DIR_MARKERS = {
    "__pycache__", ".pytest_cache", ".mypy_cache", "gradle-cache",
    "gradle-project", "executionHistory", "fileHashes",
}
CACHE_PATH_MARKERS = ("gradle-cache", "gradle-project", "/.gradle/")

# Names that keep history/context -> KEEP-INDEX, never moved.
KEEP_NAME_RE = re.compile(
    r"^(REPORT.*|PLAN.*|INDEX.*|journal\.json|checkpoint\.json|manifest.*|"
    r"decision.*|handoff\.json|FOR_.*|LEASE_RELEASE_REQUEST.*|TARGET_DECISION.*|"
    r"README.*|change\.diff|scope-claim-.*|CURRENT_STATE.*)$", re.I)

# __patch_drop__ loose-file residue patterns -> QUARANTINE; anything else there
# is live machinery and stays KEEP-INDEX.
PATCHDROP_RESIDUE_RE = re.compile(
    r"(\.pending\.[^.]+$|\.log$|^cycle\d+-|^preflight_.*\.json$|"
    r"^commit-msg\.txt$|\.pending$)", re.I)

# Reference files scanned for literal path mentions.
REF_DIRS = ["docs", ".agents", ".grok", ".devin", ".windsurf", ".clinerules",
            ".codex", ".cline", "configs"]
REF_ROOT_GLOBS = (".md", ".bat", ".ps1", ".txt")
REF_FILE_EXTS = {".md", ".txt", ".yaml", ".yml", ".json", ".bat", ".ps1",
                 ".properties", ".html", ".js", ".ts", ".java", ".gradle", ".kts"}
REF_MAX_BYTES = 2 * 1024 * 1024

TOKEN_RE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_\-./\\]{6,}")

SAFE_CLEANUP_COVERED = ("logs", "__reports__", "build")


def norm(rel):
    return rel.replace(os.sep, "/").replace("\\", "/")


def _long(p):
    """Windows MAX_PATH escape: \\?\\ prefix for paths over ~240 chars."""
    if os.name == "nt":
        p = os.path.abspath(p)
        if len(p) > 240 and not p.startswith("\\\\?\\"):
            return "\\\\?\\" + p
    return p


def sha256_file(path, h=None):
    h = h or hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def dir_stats(path):
    total = 0
    newest = 0.0
    for dp, _dn, fn in os.walk(path):
        for name in fn:
            try:
                st = os.lstat(os.path.join(dp, name))
            except OSError:
                continue
            total += st.st_size
            if st.st_mtime > newest:
                newest = st.st_mtime
    return total, newest


def is_empty_dir(path):
    for _dp, _dn, fn in os.walk(path):
        if fn:
            return False
    return True


def rel_of(path, root):
    return norm(os.path.relpath(path, root))


def under_prefix(rel, prefixes):
    rel = rel.rstrip("/")
    for p in prefixes:
        p = p.rstrip("/")
        if rel == p or rel.startswith(p + "/"):
            return True
    return False


def journal_status(dirpath):
    """status of journal.json in a handoff dir, or None."""
    jp = os.path.join(dirpath, "journal.json")
    try:
        with open(jp, "r", encoding="utf-8") as f:
            return json.load(f).get("status")
    except (OSError, ValueError):
        return None


def load_tracked(root, git_exe, tracked_file):
    if tracked_file:
        with open(tracked_file, "r", encoding="utf-8") as f:
            return set(t.strip().replace("\\", "/") for t in f if t.strip())
    try:
        out = subprocess.run([git_exe, "ls-files", "-z"], cwd=root,
                             capture_output=True, timeout=120).stdout
        return set(t.decode("utf-8", "replace") for t in out.split(b"\x00") if t)
    except (OSError, subprocess.SubprocessError):
        return set()


def live_skip_prefixes(root, skip_file):
    """Active-lease targets + in-progress journal dirs -> SKIP prefixes."""
    if skip_file:
        with open(skip_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        return [norm(p) for p in data.get("skipPrefixes", [])]
    prefixes = []
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             os.path.join(root, "__patch_drop__", "source_edit_session.ps1"),
             "-Action", "status", "-Json"],
            cwd=root, capture_output=True, timeout=120)
        text = out.stdout.decode("utf-8", "replace")
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("{"):
                leases = json.loads(line).get("sourceLeases", [])
                for lease in leases:
                    prefixes += [norm(t) for t in lease.get("targetPaths", [])]
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    try:
        out = subprocess.run(
            [sys.executable, "-B", os.path.join("scripts", "work_journal.py"),
             "list", "--active"], cwd=root, capture_output=True, timeout=120)
        for task in json.loads(out.stdout.decode("utf-8", "replace")).get("tasks", []):
            tid = task.get("taskId")
            if tid:
                prefixes.append("data/agent-handoff/codex-autonomy/" + tid)
                prefixes.append("data/agent-handoff/" + tid)
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    return prefixes


def collect_ref_tokens(root):
    tokens = set()
    files = []
    for name in os.listdir(root):
        p = os.path.join(root, name)
        if os.path.isfile(p) and os.path.splitext(name)[1].lower() in REF_ROOT_GLOBS:
            files.append(p)
    for d in REF_DIRS:
        dp = os.path.join(root, d)
        if not os.path.isdir(dp):
            continue
        for rdp, _dn, fn in os.walk(dp):
            for name in fn:
                if os.path.splitext(name)[1].lower() in REF_FILE_EXTS:
                    p = os.path.join(rdp, name)
                    try:
                        if os.lstat(p).st_size <= REF_MAX_BYTES:
                            files.append(p)
                    except OSError:
                        pass
    for extra in ("agent-prompts/INDEX.md", "AGENTS.md", "Abandon_X.txt"):
        p = os.path.join(root, extra)
        if os.path.isfile(p):
            files.append(p)
    for p in files:
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError:
            continue
        for m in TOKEN_RE.finditer(text):
            tok = m.group(0).strip("./\\\"'`:;,()[]{}<>")
            if tok:
                tokens.add(norm(tok))
                tokens.add(norm(tok).lstrip("./"))
    return tokens


def process_cmdlines(root, process_list_file):
    """Cmdlines of java/gradle-ish processes for the .gradle-* residency check."""
    if process_list_file:
        try:
            with open(process_list_file, "r", encoding="utf-8") as f:
                return f.read().splitlines()
        except OSError:
            return []
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process | Where-Object {$_.Name -match "
             "'java|gradle'} | ForEach-Object {$_.CommandLine}"],
            cwd=root, capture_output=True, timeout=120)
        return out.stdout.decode("utf-8", "replace").splitlines()
    except (OSError, subprocess.SubprocessError):
        return []


def choose_original(members):
    """Pick the surviving member of a candidate-only duplicate group."""
    def score(rel):
        s = 0
        if "/before/" in rel:
            s += 4
        if KEEP_NAME_RE.match(rel.rsplit("/", 1)[-1]):
            s -= 8
        s += rel.count("/")
        return (s, len(rel), rel)
    return min(members, key=score)


def handoff_dir_completed(dirpath, now):
    """A handoff task dir counts as closed for the 7-day rule."""
    status = journal_status(dirpath)
    if status == "in_progress":
        return False
    if status:
        return True
    _total, newest = dir_stats(dirpath)
    return newest < now - DONE_AGE_SECONDS


def plan(ctx):
    root = ctx["root"]
    now = ctx["now"]
    rows = []
    tracked = ctx["tracked"]
    ref_tokens = ctx["ref_tokens"]
    skip_prefixes = [norm(p) for p in ctx["skip_prefixes"]] + FORBIDDEN_PREFIXES

    # ancestor dirs must be *below* the scope root: "data/agent-handoff"
    # itself is cited everywhere and must not keep every file under it.
    def scope_depth(parts):
        if parts[0] == "data":
            if len(parts) > 3 and parts[1] == "agent-handoff" \
                    and parts[2] == "codex-autonomy":
                return 3
            return 2
        return 1

    def task_name(parts):
        if parts[:2] == ["data", "agent-handoff"]:
            return parts[3] if len(parts) > 3 and parts[2] == "codex-autonomy" \
                else parts[2] if len(parts) > 2 else None
        if parts[0] == "agent-prompts":
            return parts[1] if len(parts) > 1 else None
        return None

    def is_referenced(rel):
        if rel in ref_tokens:
            return True
        parts = rel.split("/")
        depth = scope_depth(parts)
        for i in range(depth + 1, len(parts)):
            if "/".join(parts[:i]) in ref_tokens:
                return True
        nm = task_name(parts)
        return bool(nm and len(nm) >= 8 and nm in ref_tokens)

    # ---- gather candidate dirs -------------------------------------------
    scope_dirs = []
    for d in CANDIDATE_DIRS + ctx.get("extra_candidate_dirs", []):
        if os.path.isdir(os.path.join(root, d)):
            scope_dirs.append(d)
    gradle_homes = []
    for name in sorted(os.listdir(root)):
        p = os.path.join(root, name)
        if name.startswith(CANDIDATE_ROOT_GLOBS) and os.path.isdir(p):
            gradle_homes.append(name)

    # ---- recent (72h) handoff children -> whole-dir SKIP ------------------
    recent_dir_prefixes = []
    hand = os.path.join(root, "data", "agent-handoff")
    if os.path.isdir(hand):
        children = []
        for child in sorted(os.listdir(hand)):
            cp = os.path.join(hand, child)
            if not os.path.isdir(cp):
                continue
            if child == "codex-autonomy":
                for g in sorted(os.listdir(cp)):
                    gp = os.path.join(cp, g)
                    if os.path.isdir(gp):
                        children.append(gp)
            else:
                children.append(cp)
        for cp in children:
            _t, newest = dir_stats(cp)
            if newest > now - RECENT_SECONDS:
                recent_dir_prefixes.append(rel_of(cp, root))

    skip_prefixes_all = skip_prefixes + recent_dir_prefixes

    # ---- original-area size index ----------------------------------------
    # Everything under root that is not itself a candidate scope plus any
    # --original-dir outside the root (Downloads, HF cache, ...).
    def in_scope(rel):
        if under_prefix(rel, FORBIDDEN_PREFIXES):
            return False
        for d in scope_dirs + gradle_homes:
            if rel == d or rel.startswith(d + "/"):
                return True
        return False

    size_index = {}

    def add_original(path):
        try:
            st = os.lstat(path)
        except OSError:
            return
        size_index.setdefault(st.st_size, []).append(("original", path))

    original_roots = []
    for name in sorted(os.listdir(root)):
        rel = name
        p = os.path.join(root, name)
        if name.startswith("."):
            continue
        if in_scope(rel) or under_prefix(rel, FORBIDDEN_PREFIXES):
            continue
        if os.path.isdir(p):
            original_roots.append(p)
        elif os.path.isfile(p):
            add_original(p)
    for odir in ctx["original_dirs"]:
        if os.path.isdir(odir):
            original_roots.append(odir)
    prune_set = FORBIDDEN_PREFIXES + scope_dirs + gradle_homes
    for oroot in original_roots:
        for dp, dn, fn in os.walk(oroot):
            # prune candidate scopes and forbidden zones inside original areas
            rp = rel_of(dp, root) if dp.startswith(root) else dp
            if dp.startswith(root):
                dn[:] = [d for d in dn if not under_prefix(
                    norm(os.path.join(rp, d)), prune_set)]
            for name in fn:
                if name in FORBIDDEN_NAMES:
                    continue
                add_original(os.path.join(dp, name))

    # agent-prompts tracked files count as originals too
    ap = os.path.join(root, "agent-prompts")
    if os.path.isdir(ap):
        for dp, _dn, fn in os.walk(ap):
            for name in fn:
                p = os.path.join(dp, name)
                if rel_of(p, root) in tracked:
                    add_original(p)

    # ---- enumerate candidate files ---------------------------------------
    cand_files = []
    empty_dirs = []
    for d in scope_dirs:
        base = os.path.join(root, d)
        for dp, dn, fn in os.walk(base):
            rel_dp = rel_of(dp, root)
            dn[:] = [x for x in dn if not under_prefix(
                norm(os.path.join(rel_dp, x)), FORBIDDEN_PREFIXES)]
            if fn:
                for name in fn:
                    cand_files.append(os.path.join(dp, name))
            # empty dir detection handled in the global pass below
    # candidate files may share a size with originals -> hash lazily
    cand_by_size = {}
    for p in cand_files:
        rel = rel_of(p, root)
        if os.path.basename(p) in FORBIDDEN_NAMES:
            rows.append(row(rel, "SKIP", "forbidden-name", p))
            continue
        try:
            st = os.lstat(p)
        except OSError:
            continue
        cand_by_size.setdefault(st.st_size, []).append(p)

    # ---- global empty-dir list (only inside scopes + unprotected roots) ----
    protected_roots = {".git", "build", "tools", "main", "app", "src",
                       "frontend", "configs", ".gradle", ".agents", "bin",
                       "data", "var", ".grok", ".devin", ".windsurf", ".codex",
                       ".cline", ".clinerules", ".secrets", "references",
                       "scripts", "docs"}
    empty_ok = set(scope_dirs) | set(gradle_homes)
    for dp, dn, fn in os.walk(root):
        rel_dp = rel_of(dp, root)
        if rel_dp == ".":
            continue
        parts = rel_dp.split("/")
        inside_scope = under_prefix(rel_dp, list(empty_ok))
        if (parts[0] in protected_roots and not inside_scope) \
                or under_prefix(rel_dp, FORBIDDEN_PREFIXES):
            dn[:] = []
            continue
        if fn:
            continue
        if not is_empty_dir(dp):
            continue
        empty_dirs.append((rel_dp, dp))
        dn[:] = []

    # ---- classify candidate files -----------------------------------------
    # Group candidates by size; hash only sizes that collide with originals
    # or with each other.
    sha_of = {}

    def ensure_sha(p):
        rel = rel_of(p, root)
        if rel not in sha_of:
            try:
                sha_of[rel] = sha256_file(p)
            except OSError:
                sha_of[rel] = None
        return sha_of[rel]

    dup_groups = {}   # sha -> {"originals": [...], "candidates": [...]}
    for size, paths in cand_by_size.items():
        originals = size_index.get(size, [])
        if not originals and len(paths) == 1:
            continue
        for p in paths:
            sha = ensure_sha(p)
            if not sha:
                continue
            g = dup_groups.setdefault(sha, {"originals": [], "candidates": []})
            g["candidates"].append(rel_of(p, root))
        if originals:
            for kind, opath in originals:
                try:
                    osha = sha256_file(opath)
                except OSError:
                    continue
                g = dup_groups.get(osha)
                if g is not None:
                    orel = opath if not opath.startswith(root) else rel_of(opath, root)
                    if orel not in g["originals"]:
                        g["originals"].append(orel)

    # member -> (original_rel | None, group_key)
    dup_of = {}
    for sha, g in dup_groups.items():
        cands = g["candidates"]
        if not cands:
            continue
        if g["originals"]:
            for c in cands:
                dup_of[c] = (g["originals"][0], sha)
        elif len(cands) > 1:
            original = choose_original(cands)
            for c in cands:
                if c != original:
                    dup_of[c] = (original, sha)

    downloads_prefixes = ctx["downloads_prefixes"]

    for p in cand_files:
        rel = rel_of(p, root)
        base = os.path.basename(p)
        if under_prefix(rel, FORBIDDEN_PREFIXES) or base in FORBIDDEN_NAMES:
            continue  # already emitted a SKIP row above
        try:
            st = os.lstat(p)
        except OSError:
            continue
        size, mtime = st.st_size, st.st_mtime

        if rel in tracked:
            rows.append(row(rel, "TRACKED_CANDIDATE", "git-tracked-report-only",
                            p, size=size, mtime=mtime))
            continue
        if under_prefix(rel, skip_prefixes):
            rows.append(row(rel, "SKIP", "active-lease-or-forbidden", p,
                            size=size, mtime=mtime))
            continue
        if under_prefix(rel, recent_dir_prefixes):
            rows.append(row(rel, "SKIP", "recent-dir-72h", p, size=size, mtime=mtime))
            continue
        if mtime > now - RECENT_SECONDS:
            rows.append(row(rel, "SKIP", "recent-file-72h", p, size=size, mtime=mtime))
            continue
        # in-progress journal guard (task dir may hold stale in_progress journal)
        hdir = handoff_task_dir(rel)
        if hdir:
            if hdir not in ctx["_journal_memo"]:
                ctx["_journal_memo"][hdir] = journal_status(
                    os.path.join(root, hdir))
            if ctx["_journal_memo"][hdir] == "in_progress":
                rows.append(row(rel, "SKIP", "in-progress-journal", p,
                                size=size, mtime=mtime))
                continue
        # Large model copies in handoff get an explicit rule: DELETE only when
        # a byte-identical file sits in a real use location (originals index),
        # never kept just because the task dir is referenced.
        if (base.lower() == "model.bin"
                and rel.startswith("data/agent-handoff/")
                and size > 100 * 1024 * 1024):
            dup = dup_of.get(rel)
            if dup and not dup[0].startswith("data/agent-handoff/"):
                rows.append(row(rel, "DELETE",
                                "duplicate-sha-original-exists", p,
                                size=size, mtime=mtime, sha=ensure_sha(p),
                                original=dup[0]))
            else:
                rows.append(row(rel, "QUARANTINE",
                                "model-copy-no-in-use-original", p,
                                size=size, mtime=mtime, sha=ensure_sha(p)))
            continue
        if is_referenced(rel):
            rows.append(row(rel, "KEEP", "referenced-by-docs-or-rules", p,
                            size=size, mtime=mtime))
            continue
        if KEEP_NAME_RE.match(base):
            rows.append(row(rel, "KEEP", "history-or-identity-name", p,
                            size=size, mtime=mtime))
            continue
        if "quarantine" in rel.split("/"):
            rows.append(row(rel, "KEEP", "quarantine-family-failure-card", p,
                            size=size, mtime=mtime))
            continue
        parts = rel.split("/")
        if any(seg in CACHE_DIR_MARKERS for seg in parts) or \
                any(m in rel for m in CACHE_PATH_MARKERS):
            rows.append(row(rel, "DELETE", "regenerable-cache", p,
                            size=size, mtime=mtime, sha=ensure_sha(p)))
            continue
        dup = dup_of.get(rel)
        if dup:
            original, _sha = dup
            in_downloads = any(original.startswith(dp) or
                               norm(original).startswith(norm(dp))
                               for dp in downloads_prefixes)
            rows.append(row(rel, "DELETE",
                            "duplicate-of-downloads" if in_downloads
                            else "duplicate-sha-original-exists",
                            p, size=size, mtime=mtime, sha=ensure_sha(p),
                            original=original))
            continue
        # area defaults
        if rel.startswith("data/agent-handoff/"):
            rows.append(row(rel, "QUARANTINE",
                            quarantine_reason_handoff(rel, root, now), p,
                            size=size, mtime=mtime, sha=ensure_sha(p)))
        elif rel.startswith("__patch_drop__/"):
            if PATCHDROP_RESIDUE_RE.search(base):
                rows.append(row(rel, "QUARANTINE", "old-patchdrop-residue", p,
                                size=size, mtime=mtime, sha=ensure_sha(p)))
            else:
                rows.append(row(rel, "KEEP", "patchdrop-live-machinery", p,
                                size=size, mtime=mtime))
        elif rel.split("/")[0] in SAFE_CLEANUP_COVERED:
            rows.append(row(rel, "SKIP", "covered-by-safe-cleanup-tool", p,
                            size=size, mtime=mtime))
        elif rel.startswith("agent-prompts/"):
            rows.append(row(rel, "QUARANTINE",
                            "untracked-agent-prompts-no-downloads-match", p,
                            size=size, mtime=mtime, sha=ensure_sha(p)))
        else:
            rows.append(row(rel, "QUARANTINE", "untracked-residue", p,
                            size=size, mtime=mtime, sha=ensure_sha(p)))

    # ---- root .gradle-* homes ---------------------------------------------
    cmdlines = ctx["process_cmdlines"]
    for g in gradle_homes:
        rel = g
        used = any(g in (c or "") for c in cmdlines)
        _total, newest = dir_stats(os.path.join(root, g))
        if used:
            rows.append(row(rel, "SKIP", "gradle-home-in-use-by-process", None))
        elif newest > now - RECENT_SECONDS:
            rows.append(row(rel, "SKIP", "recent-dir-72h", None))
        else:
            rows.append({"path": rel, "class": "DELETE",
                         "reason": "regenerable-gradle-home", "kind": "dir",
                         "size": _total, "mtime": newest, "sha256": None,
                         "original": None})

    # ---- empty dirs ---------------------------------------------------------
    for rel_dp, dp in empty_dirs:
        base = os.path.basename(dp)
        try:
            mtime = os.lstat(dp).st_mtime
        except OSError:
            continue
        if under_prefix(rel_dp, FORBIDDEN_PREFIXES) or rel_dp in tracked:
            cls, reason = "SKIP", "forbidden-or-tracked"
        elif base == "pki-validation":
            cls, reason = "KEEP", "possible-cert-validation-use"
        elif under_prefix(rel_dp, skip_prefixes):
            cls, reason = "SKIP", "active-lease-or-forbidden"
        elif under_prefix(rel_dp, recent_dir_prefixes) or mtime > now - RECENT_SECONDS:
            cls, reason = "SKIP", "recent-dir-72h"
        elif is_referenced(rel_dp):
            cls, reason = "KEEP", "referenced-empty-dir"
        else:
            cls, reason = "DELETE", "unreferenced-empty-dir"
        rows.append({"path": rel_dp, "class": cls, "reason": reason,
                     "kind": "dir", "size": 0, "mtime": mtime, "sha256": None,
                     "original": None})

    # Post-pass: plan rows carry sha256 for every enumerated file (apply
    # re-verifies before acting); lease/forbidden-prefixed files stay null
    # because they are never enumerated as candidates.
    by_rel = {r["path"]: r for r in rows}
    for p in cand_files:
        r = by_rel.get(rel_of(p, root))
        if r is not None and r.get("kind") == "file" and not r.get("sha256"):
            r["sha256"] = ensure_sha(p)

    rows.sort(key=lambda r: (r["class"], r["path"]))
    return rows


def handoff_task_dir(rel):
    parts = rel.split("/")
    if len(parts) >= 3 and parts[0] == "data" and parts[1] == "agent-handoff":
        return "/".join(parts[:3]) if parts[2] != "codex-autonomy" \
            else "/".join(parts[:4]) if len(parts) >= 4 else None
    return None


def quarantine_reason_handoff(rel, root, now):
    parts = rel.split("/")
    if parts[-1].lower() == "model.bin" and os.path.getsize(
            os.path.join(root, rel)) > 100 * 1024 * 1024:
        return "model-copy-no-in-use-original"
    if "/before/" in rel and rel.endswith(".bin"):
        tdir = handoff_task_dir(rel)
        if tdir and handoff_dir_completed(os.path.join(root, tdir),
                                          time.time()):
            return "completed-task-preimage-copy"
        return "preimage-copy"
    return "ambiguous-handoff-residue"


def row(rel, cls, reason, path, size=None, mtime=None, sha=None, original=None):
    if path is not None:
        if size is None or mtime is None:
            try:
                st = os.lstat(path)
                size, mtime = st.st_size, st.st_mtime
            except OSError:
                size = mtime = None
    return {"path": rel, "class": cls, "reason": reason, "kind": "file",
            "size": size, "mtime": mtime, "sha256": sha, "original": original}


def original_present(original, root, qdir):
    """A DELETE-by-duplicate row still has a surviving byte-identical copy."""
    if os.path.isabs(original):
        return os.path.isfile(original)
    return (os.path.isfile(os.path.join(root, original.replace("/", os.sep)))
            or os.path.isfile(os.path.join(qdir, original.replace("/", os.sep))))


def write_plan(rows, out_json, out_md, ctx):
    summary = {}
    for r in rows:
        s = summary.setdefault(r["class"], {"rows": 0, "bytes": 0})
        s["rows"] += 1
        s["bytes"] += r.get("size") or 0
    doc = {"schema": "awx.copy-residue-plan.v1",
           "createdAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "root": ctx["root"], "summary": summary, "rows": rows}
    os.makedirs(os.path.dirname(out_json), exist_ok=True)
    payload = json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True)
    with open(out_json, "wb") as f:
        f.write(payload.encode("utf-8"))
    plan_sha = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    with open(out_md, "w", encoding="utf-8") as f:
        f.write("# copy-residue cleanup plan\n\n")
        f.write("| class | rows | MB |\n|---|---:|---:|\n")
        for cls in ("DELETE", "QUARANTINE", "KEEP", "TRACKED_CANDIDATE", "SKIP"):
            s = summary.get(cls, {"rows": 0, "bytes": 0})
            f.write("| %s | %d | %.1f |\n" % (cls, s["rows"], s["bytes"] / 1048576))
        f.write("\n| path | class | reason | MB | sha256 | original |\n")
        f.write("|---|---|---|---:|---|---|\n")
        for r in rows:
            f.write("| %s | %s | %s | %.2f | %s | %s |\n" % (
                r["path"], r["class"], r["reason"],
                (r.get("size") or 0) / 1048576,
                (r.get("sha256") or "")[:12], r.get("original") or ""))
        f.write("\nplanSha256=%s\n" % plan_sha)
    return plan_sha


def apply_plan(plan_file, plan_sha, root, quarantine_root, ledger, only=None,
               dry_run=False, process_list_file=None):
    with open(plan_file, "rb") as f:
        payload = f.read()
    actual = hashlib.sha256(payload).hexdigest()
    if plan_sha and actual != plan_sha:
        return {"error": "plan-sha-mismatch", "expected": plan_sha, "actual": actual}
    plan_doc = json.loads(payload.decode("utf-8"))
    rows = plan_doc["rows"]
    qdir = os.path.join(quarantine_root, "src")
    manifest_path = os.path.join(quarantine_root, "quarantine-manifest.json")
    log_path = os.path.join(ledger, "apply-log.jsonl")
    os.makedirs(ledger, exist_ok=True)
    results = []
    cmdlines = None
    now = time.time()
    with open(log_path, "a", encoding="utf-8") as log:
        for r in rows:
            cls, reason, rel = r["class"], r["reason"], r["path"]
            if only and cls != only:
                continue
            if cls not in ("DELETE", "QUARANTINE"):
                continue
            res = {"path": rel, "class": cls, "reason": reason}
            target = os.path.join(root, rel.replace("/", os.sep))
            try:
                if r.get("kind") == "dir":
                    if cls == "DELETE":
                        if not os.path.isdir(target):
                            res["result"] = "missing"
                        elif rel.startswith(".gradle-"):
                            if cmdlines is None:
                                cmdlines = process_cmdlines(
                                    root, process_list_file)
                            if any(rel in (c or "") for c in cmdlines):
                                res["result"] = "skipped-process-uses-dir"
                            elif dir_stats(target)[1] > now - RECENT_SECONDS:
                                res["result"] = "skipped-became-recent"
                            elif dry_run:
                                res["result"] = "would-delete-dir"
                            else:
                                shutil.rmtree(target)
                                res["result"] = "deleted-dir"
                        elif is_empty_dir(target):
                            if dry_run:
                                res["result"] = "would-rmdir"
                            else:
                                shutil.rmtree(target)
                                res["result"] = "rmdir"
                        else:
                            res["result"] = "skipped-not-empty"
                    else:
                        res["result"] = "skipped-dir-quarantine-not-supported"
                elif not os.path.isfile(target):
                    res["result"] = "missing"
                elif cls == "DELETE":
                    if r.get("sha256") and sha256_file(target) != r["sha256"]:
                        res["result"] = "skipped-sha-drift"
                    elif r.get("original") and not original_present(
                            r["original"], root, qdir):
                        res["result"] = "skipped-original-missing"
                    else:
                        if dry_run:
                            res["result"] = "would-delete"
                        else:
                            os.remove(target)
                            res["result"] = "deleted"
                else:  # QUARANTINE
                    if r.get("sha256") and sha256_file(target) != r["sha256"]:
                        res["result"] = "skipped-sha-drift"
                    elif dry_run:
                        res["result"] = "would-quarantine"
                    else:
                        dest = _long(os.path.join(
                            qdir, rel.replace("/", os.sep)))
                        os.makedirs(os.path.dirname(dest), exist_ok=True)
                        if os.path.exists(dest):
                            res["result"] = "skipped-dest-exists"
                        else:
                            shutil.move(target, dest)
                            sha_after = sha256_file(dest)
                            res["result"] = "quarantined"
                            res["sha256After"] = sha_after
                            if r.get("sha256") and sha_after != r["sha256"]:
                                res["result"] = "failed-sha-mismatch-after-move"
            except OSError as e:
                res["result"] = "failed"
                res["error"] = str(e)[:200]
            results.append(res)
            log.write(json.dumps(res, ensure_ascii=False) + "\n")
    # refresh manifest + restore.ps1
    manifest = {"schema": "awx.copy-residue-quarantine.v1",
                "quarantineRoot": quarantine_root, "srcRoot": root,
                "updatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                              time.gmtime()),
                "entries": []}
    if os.path.isfile(manifest_path):
        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest["entries"] = json.load(f).get("entries", [])
        except (OSError, ValueError):
            pass
    known = {e["quarantinePath"] for e in manifest["entries"]}
    for res in results:
        if res.get("result") == "quarantined":
            rel = res["path"]
            qp = norm(os.path.join("src", rel))
            if qp not in known:
                row_src = next((x for x in rows if x["path"] == rel), {})
                manifest["entries"].append({
                    "originalPath": rel, "quarantinePath": qp,
                    "bytes": row_src.get("size"), "sha256": res["sha256After"],
                    "reason": res["reason"],
                    "movedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                                time.gmtime()),
                    "restoredAtUtc": None})
    if not dry_run:
        os.makedirs(quarantine_root, exist_ok=True)
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=1)
        write_restore_ps1(quarantine_root)
    counts = {}
    for res in results:
        counts[res["result"]] = counts.get(res["result"], 0) + 1
    return {"planSha256": actual, "results": counts, "rows": len(results),
            "manifest": manifest_path}


def write_restore_ps1(quarantine_root):
    ps1 = r"""# restore.ps1 - move quarantined files back to their original src paths.
# Idempotent: verifies sha256 before/after; entries already restored are skipped.
param([string]$Manifest = (Join-Path $PSScriptRoot 'quarantine-manifest.json'))
$ErrorActionPreference = 'Continue'
$m = Get-Content $Manifest -Raw | ConvertFrom-Json
$ok = 0; $skip = 0; $fail = 0
foreach ($e in $m.entries) {
    if ($e.restoredAtUtc) { $skip++; continue }
    $src = Join-Path $PSScriptRoot ($e.quarantinePath -replace '/','\')
    $dst = Join-Path $m.srcRoot ($e.originalPath -replace '/','\')
    if ($src.Length -gt 240 -and -not $src.StartsWith('\\?\')) { $src = '\\?\' + $src }
    if ($dst.Length -gt 240 -and -not $dst.StartsWith('\\?\')) { $dst = '\\?\' + $dst }
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { $skip++; continue }
    $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $src).Hash.ToLower()
    if ($e.sha256 -and $h -ne $e.sha256) { Write-Output "FAIL sha $src"; $fail++; continue }
    New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
    Move-Item -LiteralPath $src -Destination $dst -Force
    $h2 = (Get-FileHash -Algorithm SHA256 -LiteralPath $dst).Hash.ToLower()
    if ($h2 -eq $h) { $e.restoredAtUtc = (Get-Date).ToUniversalTime().ToString('o'); $ok++ }
    else { Write-Output "FAIL post-sha $dst"; $fail++ }
}
$m | ConvertTo-Json -Depth 6 | Set-Content $Manifest -Encoding utf8
Write-Output "restored=$ok skipped=$skip failed=$fail"
"""
    with open(os.path.join(quarantine_root, "restore.ps1"), "w",
              encoding="utf-8") as f:
        f.write(ps1)


def restore(manifest_path):
    with open(manifest_path, "r", encoding="utf-8") as f:
        m = json.load(f)
    root, qroot = m["srcRoot"], m["quarantineRoot"]
    out = {"restored": 0, "skipped": 0, "failed": []}
    for e in m["entries"]:
        if e.get("restoredAtUtc"):
            out["skipped"] += 1
            continue
        src = os.path.join(qroot, e["quarantinePath"].replace("/", os.sep))
        dst = os.path.join(root, e["originalPath"].replace("/", os.sep))
        if not os.path.isfile(src):
            out["skipped"] += 1
            continue
        if e.get("sha256") and sha256_file(src) != e["sha256"]:
            out["failed"].append({"path": e["originalPath"],
                                  "error": "pre-restore-sha-mismatch"})
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(src, _long(dst))
        if sha256_file(dst) == e.get("sha256"):
            e["restoredAtUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                               time.gmtime())
            out["restored"] += 1
        else:
            out["failed"].append({"path": e["originalPath"],
                                  "error": "post-restore-sha-mismatch"})
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="mode", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--root", default=".")
    p.add_argument("--ledger", required=True)
    p.add_argument("--git-exe", default="git")
    p.add_argument("--tracked-file")
    p.add_argument("--skip-file")
    p.add_argument("--original-dir", action="append", default=[])
    p.add_argument("--downloads-dir", action="append", default=[])
    p.add_argument("--process-list-file")
    p.add_argument("--extra-skip-prefix", action="append", default=[])
    p.add_argument("--extra-candidate-dir", action="append", default=[],
                   help="additional repo-relative candidate scope "
                        "(repeatable); forbidden prefixes stay excluded")
    p.add_argument("--plan-json")
    p.add_argument("--plan-md")
    a = sub.add_parser("apply")
    a.add_argument("--root", default=".")
    a.add_argument("--plan", required=True)
    a.add_argument("--plan-sha", required=True)
    a.add_argument("--ledger", required=True)
    a.add_argument("--quarantine-root", required=True)
    a.add_argument("--only", choices=["DELETE", "QUARANTINE"])
    a.add_argument("--dry-run", action="store_true")
    a.add_argument("--process-list-file")
    r = sub.add_parser("restore")
    r.add_argument("--manifest", required=True)
    rp = sub.add_parser("report")
    rp.add_argument("--plan", required=True)
    rp.add_argument("--apply-log")
    args = ap.parse_args(argv)

    if args.mode == "plan":
        root = os.path.abspath(args.root)
        ledger = os.path.abspath(args.ledger)
        extra_dirs = []
        for d in args.extra_candidate_dir:
            ap = os.path.abspath(os.path.join(root, d))
            try:
                rel = norm(os.path.relpath(ap, root))
            except ValueError:
                continue
            if rel.startswith("..") or rel in ("", ".") \
                    or under_prefix(rel, FORBIDDEN_PREFIXES) \
                    or rel in CANDIDATE_DIRS or rel in extra_dirs:
                continue
            extra_dirs.append(rel)
        ctx = {
            "root": root, "now": time.time(),
            "tracked": load_tracked(root, args.git_exe, args.tracked_file),
            "skip_prefixes": live_skip_prefixes(root, args.skip_file)
            + args.extra_skip_prefix,
            "ref_tokens": collect_ref_tokens(root),
            "original_dirs": [os.path.abspath(d) for d in args.original_dir],
            "downloads_prefixes": [norm(os.path.abspath(d))
                                   for d in args.downloads_dir],
            "process_cmdlines": process_cmdlines(root, args.process_list_file),
            "extra_candidate_dirs": extra_dirs,
            "_journal_memo": {},
        }
        rows = plan(ctx)
        out_json = args.plan_json or os.path.join(ledger, "plan.json")
        out_md = args.plan_md or os.path.join(ledger, "PLAN.md")
        sha = write_plan(rows, out_json, out_md, ctx)
        print(json.dumps({"plan": out_json, "planMd": out_md,
                          "planSha256": sha,
                          "rows": len(rows)}))
        return 0
    if args.mode == "apply":
        res = apply_plan(args.plan, args.plan_sha, os.path.abspath(args.root),
                         os.path.abspath(args.quarantine_root),
                         os.path.abspath(args.ledger), only=args.only,
                         dry_run=args.dry_run,
                         process_list_file=args.process_list_file)
        print(json.dumps(res, ensure_ascii=False))
        return 0 if "error" not in res else 3
    if args.mode == "restore":
        print(json.dumps(restore(args.manifest), ensure_ascii=False))
        return 0
    if args.mode == "report":
        with open(args.plan, "r", encoding="utf-8") as f:
            doc = json.load(f)
        out = {"summary": doc["summary"], "rows": len(doc["rows"])}
        if args.apply_log and os.path.isfile(args.apply_log):
            counts = {}
            with open(args.apply_log, "r", encoding="utf-8") as f:
                for line in f:
                    r = json.loads(line)
                    counts[r["result"]] = counts.get(r["result"], 0) + 1
            out["apply"] = counts
        print(json.dumps(out, ensure_ascii=False))
        return 0


if __name__ == "__main__":
    sys.exit(main())
