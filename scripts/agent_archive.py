#!/usr/bin/env python3
"""Agent archive: scan/find/show/plan/prune for report+script triage.

Scans the report/script residue areas, scores every file on the same
five axes (usage, recency, rarity, contradiction, reusability; 0-2 each,
0-10 total), assigns one of DELETE / COLD / RECYCLE / KEEP-LIVE / TRACKED /
SKIP, writes a JSONL catalog, and emits copy_residue_cleanup-format plans
(sha-pinned DELETE/QUARANTINE rows only) so the existing apply/restore
machinery executes them.

Reuses copy_residue_cleanup helpers so protection semantics (leases,
in-progress journals, 72h recency, forbidden zones, KEEP names) match.

Stdlib only.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import copy_residue_cleanup as crc

norm = crc.norm
sha256_file = crc.sha256_file
KEEP_NAME_RE = crc.KEEP_NAME_RE
FORBIDDEN_PREFIXES = crc.FORBIDDEN_PREFIXES
under_prefix = crc.under_prefix
TOKEN_RE = crc.TOKEN_RE

RECENT_SECONDS = crc.RECENT_SECONDS          # 72h
DONE_AGE_SECONDS = crc.DONE_AGE_SECONDS      # 7d
RECENCY_1_SECONDS = 14 * 24 * 3600
SCRIPT_ARCHIVE_AGE_S = 7 * 24 * 3600   # W5: scripts must be older than 7d

CATALOG_DEFAULT = "docs/agent-archive/catalog.jsonl"
CARDS_DIR = "docs/agent-archive/cards"
OWN_LEDGER_TOKEN = "devin-report-script-triage"

# Fixed scan areas (repo-relative). var children are expanded by name.
SCAN_DIRS = [
    "docs/diagnostics",
    "docs/superpowers",          # index only: files here are git-tracked
    "docs/reports",
    "data/agent-handoff",
    "logs",
    "build/reports",
]
VAR_CHILD_GLOBS = ("debug", "rag-launcher", "codex-assist")
SCRIPTS_TOP = "scripts"

# Reference sources for the usage signal.
REF_DOC_DIRS = [".agents/skills", ".windsurf/rules", ".grok", ".clinerules",
                "docs", "scripts"]
REF_DOC_EXTS = {".md", ".txt", ".yaml", ".yml", ".json", ".bat", ".ps1",
                ".py", ".js", ".cjs", ".mjs", ".java", ".gradle", ".kts"}
REF_MAX_BYTES = 2 * 1024 * 1024

SCRIPT_EXTS = {".py", ".ps1", ".js", ".cjs", ".mjs", ".sh", ".bat"}
LOG_EXTS = {".log", ".ndjson", ".out"}
CACHE_EXTS = {".pyc", ".pyo", ".class"}
REPORT_HEAD_BYTES = 16 * 1024
JOURNAL_WINDOW_SECONDS = 7 * 24 * 3600
JOURNAL_TEXT_CAP = 5 * 1024 * 1024

RARE_RE = re.compile(
    r"root.?cause|post.?mortem|measured|signature|repro|verdict|"
    r"final[-_ ]?report|ssot|runbook|실측|근본 ?원인|수치", re.I)
CONFLICT_RE = re.compile(
    r"conflict|contradict|disagree|rebuttal|모순|충돌|vs.?live|vs.?source", re.I)
REUSE_RE = re.compile(r"argparse|def main|if __name__|Add-Type|param\(", re.I)
REPORT_REUSE_RE = re.compile(
    r"```|python -B |powershell |gradlew|checklist|단계|절차", re.I)
TAG_VOCAB = {
    "timeout", "oauth", "chat", "display", "lens", "rag", "glm", "jev",
    "f01b", "p6dbg", "trace", "meta", "fold", "nova", "quarantine",
    "lease", "journal", "h2", "ddl", "gpu", "ollama", "settings",
    "verify", "smoke", "orchestra", "grokbot", "agy", "budget",
    "handoff", "superpowers", "autograde", "copilot", "model",
    "routing", "watchdog", "checkpoint", "conversate", "mcp", "dbus",
}
AGENT_PREFIXES = ("devin", "codex", "grok", "cline", "agy", "clean",
                  "mgain", "uaw", "abandonware", "att", "mcp")

NOISE_SUFFIX_RE = re.compile(
    r"(-?[0-9a-f]{6,8}|-?\d{8}(-\d{6})?|-r\d+|-stop|-stop-\d+)$")


def kind_of(rel, base):
    parts = rel.split("/")
    ext = os.path.splitext(base)[1].lower()
    if "__pycache__" in parts or ext in CACHE_EXTS or \
            rel.startswith("build/reports/"):
        return "cache"
    if ext in LOG_EXTS or base.endswith(".log"):
        return "log"
    if ext in SCRIPT_EXTS:
        return "script"
    return "report"


def topic_of(rel):
    """Session-prefix topic: task/subdir name, or normalized stem."""
    parts = rel.split("/")
    if parts[:2] == ["data", "agent-handoff"]:
        raw = parts[3] if len(parts) > 4 and parts[2] == "codex-autonomy" \
            else parts[2] if len(parts) > 3 else \
            os.path.splitext(parts[-1])[0]
    elif parts[:2] == ["docs", "diagnostics"] and len(parts) > 3:
        raw = parts[2]
    elif parts[:2] == ["var", "rag-launcher"]:
        raw = "rag-launcher"
    elif parts[0] == "var" and len(parts) > 2:
        raw = parts[1]
    else:
        raw = os.path.splitext(parts[-1])[0]
    t = raw.lower()
    prev = None
    while prev != t:
        prev = t
        t = NOISE_SUFFIX_RE.sub("", t)
    return t or raw.lower()


def tags_of(rel, topic):
    toks = set(re.split(r"[^a-z0-9]+", rel.lower()))
    tags = sorted(t for t in toks & TAG_VOCAB)
    first = topic.split("-")[0]
    if first in AGENT_PREFIXES:
        tags = [first] + [t for t in tags if t != first]
    return tags


def head_text(path, cap=REPORT_HEAD_BYTES):
    try:
        if os.lstat(path).st_size > 64 * 1024 * 1024:
            return ""
        with open(path, "rb") as f:
            return f.read(cap).decode("utf-8", "replace")
    except OSError:
        return ""


def title_of(path, base):
    txt = head_text(path)
    for line in txt.splitlines():
        s = line.strip().lstrip("#").strip()
        if s:
            return s[:110]
    return base


def iter_scan_files(root):
    """Yield abs file paths inside the scan scope."""
    for d in SCAN_DIRS:
        base = os.path.join(root, d)
        if not os.path.isdir(base):
            continue
        for dp, _dn, fn in os.walk(base):
            for n in fn:
                yield os.path.join(dp, n)
    vdir = os.path.join(root, "var")
    if os.path.isdir(vdir):
        for name in sorted(os.listdir(vdir)):
            if not any(name.startswith(g) for g in VAR_CHILD_GLOBS):
                continue
            base = os.path.join(vdir, name)
            if not os.path.isdir(base):
                continue
            for dp, _dn, fn in os.walk(base):
                for n in fn:
                    yield os.path.join(dp, n)
    sdir = os.path.join(root, SCRIPTS_TOP)
    if os.path.isdir(sdir):
        for n in sorted(os.listdir(sdir)):
            p = os.path.join(sdir, n)
            if os.path.isfile(p):
                yield p
        pc = os.path.join(sdir, "__pycache__")
        if os.path.isdir(pc):
            for dp, _dn, fn in os.walk(pc):
                for n in fn:
                    yield os.path.join(dp, n)


def session_dir_of(rel):
    """Dir whose 72h recency protects a whole session (or None)."""
    parts = rel.split("/")
    if parts[:2] == ["data", "agent-handoff"]:
        if len(parts) > 3 and parts[2] == "codex-autonomy":
            return "/".join(parts[:4])
        if len(parts) > 2:
            return "/".join(parts[:3])
    if parts[:2] == ["docs", "diagnostics"] and len(parts) > 3:
        return "/".join(parts[:3])
    if parts[0] == "var" and len(parts) > 3:
        return "/".join(parts[:2]) if parts[1] != "rag-launcher" \
            else "/".join(parts[:3])
    return None


def collect_ref_map(root):
    """token -> set(doc rel) over reference documents."""
    doc_files = []
    for name in sorted(os.listdir(root)):
        p = os.path.join(root, name)
        if os.path.isfile(p) and os.path.splitext(name)[1].lower() in \
                {".md", ".bat", ".ps1", ".txt"}:
            doc_files.append(p)
    for d in REF_DOC_DIRS:
        dp = os.path.join(root, d)
        if not os.path.isdir(dp):
            continue
        if d == "scripts":
            for n in sorted(os.listdir(dp)):
                p = os.path.join(dp, n)
                if os.path.isfile(p) and n.endswith(".py"):
                    doc_files.append(p)
            continue
        for rdp, _dn, fn in os.walk(dp):
            for n in fn:
                if os.path.splitext(n)[1].lower() in REF_DOC_EXTS:
                    p = os.path.join(rdp, n)
                    try:
                        if os.lstat(p).st_size <= REF_MAX_BYTES:
                            doc_files.append(p)
                    except OSError:
                        pass
    token_docs = {}
    all_tokens = set()
    for p in doc_files:
        rel = norm(os.path.relpath(p, root))
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                text = f.read(REF_MAX_BYTES)
        except OSError:
            continue
        toks = set()
        for m in TOKEN_RE.finditer(text):
            tok = m.group(0).strip("./\\\"'`:;,()[]{}<>")
            if tok:
                toks.add(norm(tok))
                toks.add(norm(tok).lstrip("./"))
        for t in toks:
            token_docs.setdefault(t, set()).add(rel)
        all_tokens |= toks
    return token_docs, all_tokens


def journal_text_window(root, now):
    """Basename mentions inside journals modified in the last 7d."""
    texts = []
    total = 0
    hand = os.path.join(root, "data", "agent-handoff")
    if not os.path.isdir(hand):
        return ""
    for dp, _dn, fn in os.walk(hand):
        for n in fn:
            if n != "journal.json":
                continue
            p = os.path.join(dp, n)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            if st.st_mtime < now - JOURNAL_WINDOW_SECONDS:
                continue
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as f:
                    chunk = f.read(256 * 1024)
            except OSError:
                continue
            texts.append(chunk)
            total += len(chunk)
            if total > JOURNAL_TEXT_CAP:
                return "\n".join(texts)
    return "\n".join(texts)


def live_pids(root):
    """Live PIDs via tasklist (Windows) for the dead-pid DELETE rule."""
    pids = set()
    try:
        import subprocess
        out = subprocess.run(["tasklist", "/FO", "CSV", "/NH"],
                             capture_output=True, timeout=60).stdout
        for line in out.decode("utf-8", "replace").splitlines():
            parts = line.split('","')
            if len(parts) > 1 and parts[1].strip('"').isdigit():
                pids.add(int(parts[1].strip('"')))
    except Exception:
        pass
    return pids


def collect_empty_dirs(root):
    """Empty dirs strictly inside the scan roots."""
    found = []
    tops = [os.path.join(root, d) for d in SCAN_DIRS
            if os.path.isdir(os.path.join(root, d))]
    vdir = os.path.join(root, "var")
    if os.path.isdir(vdir):
        tops += [os.path.join(vdir, n) for n in sorted(os.listdir(vdir))
                 if any(n.startswith(g) for g in VAR_CHILD_GLOBS)
                 and os.path.isdir(os.path.join(vdir, n))]
    for top in tops:
        for dp, dn, fn in os.walk(top):
            if fn:
                continue
            if crc.is_empty_dir(dp):
                found.append(dp)
                dn[:] = []
    return found


def scan(root, original_dirs=None, downloads_dirs=None, progress=None,
         tracked_file=None, skip_file=None):
    """Full scan -> list of catalog row dicts."""
    root = os.path.abspath(root)
    now = time.time()
    original_dirs = original_dirs or []
    downloads_dirs = downloads_dirs or []

    tracked = crc.load_tracked(root, "git", tracked_file)
    if not tracked and not tracked_file:
        tracked = crc.load_tracked(root, r"F:\git\cmd\git.exe", None)
    if not tracked and not tracked_file and \
            os.path.isdir(os.path.join(root, ".git")):
        raise SystemExit(
            "git-tracked-empty: refusing to classify without the index")
    skip_prefixes = [norm(p) for p in
                     crc.live_skip_prefixes(root, skip_file)] \
        + FORBIDDEN_PREFIXES
    token_docs, all_tokens = collect_ref_map(root)
    jtext = journal_text_window(root, now)
    pids = live_pids(root)

    # ---- enumerate candidates -------------------------------------------
    cands = []          # (abs, rel, size, mtime)
    newest_by_sdir = {}
    for p in iter_scan_files(root):
        rel = norm(os.path.relpath(p, root))
        if OWN_LEDGER_TOKEN in rel or "/agent-archive/" in rel:
            continue
        try:
            st = os.lstat(p)
        except OSError:
            continue
        cands.append((p, rel, st.st_size, st.st_mtime))
        sdir = session_dir_of(rel)
        if sdir and st.st_mtime > newest_by_sdir.get(sdir, 0.0):
            newest_by_sdir[sdir] = st.st_mtime

    # ---- size index for dup detection ------------------------------------
    cand_by_size = {}
    for p, rel, size, _mt in cands:
        cand_by_size.setdefault(size, []).append((p, rel))
    originals_index = {}
    scan_prefixes = [d for d in SCAN_DIRS] + ["var/" + n for n in
                        os.listdir(os.path.join(root, "var"))
                        if any(n.startswith(g) for g in VAR_CHILD_GLOBS)] \
        if os.path.isdir(os.path.join(root, "var")) else [d for d in SCAN_DIRS]
    scan_prefixes += ["scripts"]
    oroots = []
    for name in sorted(os.listdir(root)):
        p = os.path.join(root, name)
        if name.startswith(".") or under_prefix(name, FORBIDDEN_PREFIXES):
            continue
        if name in ("data", "var", "logs", "build", "docs", "scripts"):
            # these contain scan roots; descend with pruning instead
            continue
        if os.path.isdir(p):
            oroots.append(p)
        elif os.path.isfile(p):
            try:
                originals_index.setdefault(os.lstat(p).st_size, []).append(p)
            except OSError:
                pass
    # non-scan parts of mixed roots (docs/* not scanned, scripts subdirs only)
    mixed = []
    for d in ("docs", "scripts"):
        dp = os.path.join(root, d)
        if os.path.isdir(dp):
            mixed.append((d, dp))
    prune_set = set(FORBIDDEN_PREFIXES) | set(scan_prefixes)
    for oroot in oroots + [os.path.abspath(d) for d in original_dirs]:
        if not os.path.isdir(oroot):
            continue
        for dp, dn, fn in os.walk(oroot):
            rel_dp = norm(os.path.relpath(dp, root)) if dp.startswith(root) \
                else dp
            if dp.startswith(root):
                dn[:] = [x for x in dn if not under_prefix(
                    norm(os.path.join(rel_dp, x)), prune_set)]
            for n in fn:
                if n in crc.FORBIDDEN_NAMES:
                    continue
                fp = os.path.join(dp, n)
                try:
                    originals_index.setdefault(
                        os.lstat(fp).st_size, []).append(fp)
                except OSError:
                    pass
    for mname, mroot in mixed:
        for dp, dn, fn in os.walk(mroot):
            rel_dp = norm(os.path.relpath(dp, root))
            if mname == "scripts":
                # scan covers top-level files + __pycache__ only; originals
                # come solely from real subdirs (never the candidate set)
                if rel_dp == "scripts":
                    dn[:] = [x for x in dn if x != "__pycache__"]
                    fn = []
            else:
                dn[:] = [x for x in dn if not under_prefix(
                    norm(os.path.join(rel_dp, x)), prune_set)]
            for n in fn:
                if n in crc.FORBIDDEN_NAMES:
                    continue
                fp = os.path.join(dp, n)
                try:
                    originals_index.setdefault(
                        os.lstat(fp).st_size, []).append(fp)
                except OSError:
                    pass
    for d in downloads_dirs:
        d = os.path.abspath(d)
        if not os.path.isdir(d):
            continue
        for dp, _dn, fn in os.walk(d):
            for n in fn:
                fp = os.path.join(dp, n)
                try:
                    originals_index.setdefault(
                        os.lstat(fp).st_size, []).append(fp)
                except OSError:
                    pass

    # ---- hash colliding groups only --------------------------------------
    sha_of = {}

    def ensure_sha(abs_path):
        key = norm(abs_path)
        if key not in sha_of:
            try:
                sha_of[key] = sha256_file(abs_path)
            except OSError:
                sha_of[key] = None
        return sha_of[key]

    sha_groups = {}
    for size, members in cand_by_size.items():
        origs = originals_index.get(size, [])
        if not origs and len(members) == 1:
            continue
        if size == 0:
            continue
        for p, rel in members:
            s = ensure_sha(p)
            if s:
                sha_groups.setdefault(s, {"c": [], "o": []})["c"].append(rel)
        for op in origs:
            s = ensure_sha(op)
            if s and s in sha_groups:
                orel = norm(os.path.relpath(op, root)) \
                    if op.startswith(root) else op
                sha_groups[s]["o"].append(orel)
    dup_of = {}
    for s, g in sha_groups.items():
        cands_g = g["c"]
        if not cands_g:
            continue
        if g["o"]:
            for c in cands_g:
                dup_of[c] = (g["o"][0], s)
        elif len(cands_g) > 1:
            survivor = choose_survivor(cands_g, token_docs)
            for c in cands_g:
                if c != survivor:
                    dup_of[c] = (survivor, s)

    # ---- classify ---------------------------------------------------------
    rows = []
    dl_prefixes = [norm(os.path.abspath(d)) for d in downloads_dirs]
    for p, rel, size, mtime in cands:
        base = os.path.basename(p)
        topic = topic_of(rel)
        kind = kind_of(rel, base)
        ref_docs = sorted(set().union(*(
            [token_docs.get(k, set()) for k in ref_keys(rel, base)])))[:8]
        stem = os.path.splitext(base)[0]
        in_journal = bool(jtext) and base in jtext
        tracked_f = rel in tracked
        sdir = session_dir_of(rel)
        recent_dir = bool(sdir and newest_by_sdir.get(sdir, 0) >
                          now - RECENT_SECONDS)
        parts = {"use": 0, "rec": 0, "rar": 0, "con": 0, "reu": 0}
        if ref_docs:
            parts["use"] = 2
        elif in_journal or stem in all_tokens:
            parts["use"] = 1
        parts["rec"] = 2 if mtime > now - RECENT_SECONDS else \
            (1 if mtime > now - RECENCY_1_SECONDS else 0)
        txt = ""
        if kind == "report":
            txt = head_text(p)
            parts["rar"] = 1  # representative bump applied post-pass
            if RARE_RE.search(base) or RARE_RE.search(txt):
                parts["rar"] = 2
            if CONFLICT_RE.search(base) or CONFLICT_RE.search(txt):
                parts["con"] = 2 if "CONFLICT" in base.upper() else 1
            parts["reu"] = 1 if REPORT_REUSE_RE.search(txt) else 0
        elif kind == "script":
            txt = head_text(p)
            mainish = bool(REUSE_RE.search(txt))
            docish = '"""' in txt[:4000] or "help=" in txt or \
                ".SYNOPSIS" in txt or ".DESCRIPTION" in txt
            parts["reu"] = 2 if (mainish and docish) else \
                (1 if mainish else 0)
        score = sum(parts.values())

        # strip self-pair references for scripts
        if kind == "script":
            ref_docs = [d for d in ref_docs
                        if not is_test_pair(base, os.path.basename(d))]

        bin_, why = classify(rel, base, kind, tracked_f, ref_docs,
                             recent_dir, mtime, now, sdir, root,
                             pids, score, parts, dup_of, dl_prefixes,
                             skip_prefixes, p)
        sha_full = None
        if bin_ in ("DELETE", "COLD"):
            sha_full = hashlib.sha256(b"").hexdigest() if size == 0 \
                else ensure_sha(p)
        rows.append({
            "path": rel, "kind": kind, "topic": topic,
            "tags": tags_of(rel, topic), "bin": bin_, "score": score,
            "parts": parts, "why": why,
            "sha12": (sha_full or "")[:12] or None, "sha256": sha_full,
            "size": size, "mtime": mtime,
            "tracked": tracked_f, "referenced_by": ref_docs,
            "original": dup_of[rel][0] if rel in dup_of else None,
            "superseded_by": None, "is_representative": False,
            "run_hint": run_hint(rel, kind),
            "summary": title_of(p, base) if kind == "report" else "",
            "archive_to": archive_target(rel, kind, parts, tracked_f,
                                        ref_docs, mtime, now),
        })

    # ---- empty dirs --------------------------------------------------------
    for dp in collect_empty_dirs(root):
        rel = norm(os.path.relpath(dp, root))
        if OWN_LEDGER_TOKEN in rel or under_prefix(rel, FORBIDDEN_PREFIXES):
            continue
        try:
            mt = os.lstat(dp).st_mtime
        except OSError:
            continue
        prot = rel in tracked or under_prefix(rel, skip_prefixes) or \
            mt > now - RECENT_SECONDS or rel in all_tokens
        sdir_e = session_dir_of(rel)
        if sdir_e and newest_by_sdir.get(sdir_e, 0) > now - RECENT_SECONDS:
            prot = True
        bin_ = "KEEP-LIVE" if prot else "DELETE"
        rows.append({
            "path": rel, "kind": "dir", "topic": topic_of(rel),
            "tags": tags_of(rel, topic_of(rel)), "bin": bin_,
            "score": 0, "parts": {"use": 0, "rec": 0, "rar": 0,
                                  "con": 0, "reu": 0},
            "why": "unreferenced-empty-dir" if not prot
                   else "protected-empty-dir",
            "sha12": None, "sha256": None, "size": 0, "mtime": mt,
            "tracked": rel in tracked, "referenced_by": [],
            "superseded_by": None, "is_representative": False,
            "run_hint": "", "summary": "", "archive_to": None,
        })

    # ---- supersession: per (topic, report-kind) newest = representative ----
    groups = {}
    for r in rows:
        if r["kind"] == "report" and r["bin"] != "TRACKED":
            groups.setdefault((r["topic"], r["path"].split("/")[0]), []) \
                .append(r)
    for gkey, members in groups.items():
        if len(members) < 2:
            continue
        members.sort(key=lambda r: (r["mtime"], r["path"]))
        rep = members[-1]
        rep["is_representative"] = True
        for m in members[:-1]:
            if m["bin"] in ("COLD", "RECYCLE"):
                m["superseded_by"] = rep["path"]
    return rows


def choose_survivor(rels, token_docs):
    """Pick which member of a candidate dup-group keeps living in place.
    Mirrors copy_residue_cleanup.choose_original: named/referenced/shallow
    paths win; preimage blobs lose."""
    def score(rel):
        s = 0
        base = os.path.basename(rel)
        if "/before/" in rel or "/preimages/" in rel:
            s += 4
        if KEEP_NAME_RE.match(base):
            s -= 8
        if any(t in token_docs for t in ref_keys(rel, base)):
            s -= 4
        s += rel.count("/")
        return (s, len(rel), rel)
    return min(rels, key=score)


def ref_keys(rel, base):
    stem = os.path.splitext(base)[0]
    keys = [rel, rel.lstrip("./"), base]
    if len(stem) >= 7:
        keys.append(stem)
    return keys


def is_test_pair(base_a, base_b):
    sa = os.path.splitext(base_a)[0].lower()
    sb = os.path.splitext(base_b)[0].lower()
    return ("test_" + sa) == sb or sa == ("test_" + sb) or \
        (sa.startswith("test_") and sa[5:] == sb) or \
        (sb.startswith("test_") and sb[5:] == sa)


def archive_target(rel, kind, parts, tracked_f, ref_docs, mtime, now):
    if kind != "script" or parts.get("reu", 0) < 2:
        return None
    if not (rel.startswith("scripts/") and rel.count("/") == 1):
        return None   # W5 archive scope = scripts top-level only
    if tracked_f or ref_docs:
        return None   # W5 = unreferenced untracked scripts only
    if mtime > now - SCRIPT_ARCHIVE_AGE_S:
        return None   # W5 = strictly older than 7 days
    stem = os.path.splitext(os.path.basename(rel))[0]
    fam = re.split(r"[_\-]", stem.lower())[0] or "misc"
    return "scripts/_archive/%s/%s" % (fam, os.path.basename(rel))


def run_hint(rel, kind):
    if kind != "script":
        return ""
    ext = os.path.splitext(rel)[1].lower()
    if ext == ".py":
        return "python -B %s" % rel
    if ext == ".ps1":
        return "powershell -NoProfile -ExecutionPolicy Bypass -File %s" % rel
    if ext in (".js", ".cjs", ".mjs"):
        return "node %s" % rel
    if ext == ".bat":
        return rel
    return ""


def classify(rel, base, kind, tracked_f, ref_docs, recent_dir, mtime, now,
             sdir, root, pids, score, parts, dup_of, dl_prefixes,
             skip_prefixes, abs_path):
    if under_prefix(rel, FORBIDDEN_PREFIXES):
        return "SKIP", "forbidden-prefix"
    if tracked_f:
        return "TRACKED", "git-tracked-report-only"
    if under_prefix(rel, skip_prefixes):
        return "KEEP-LIVE", "active-lease-or-protected"
    if recent_dir or mtime > now - RECENT_SECONDS:
        return "KEEP-LIVE", "recent-72h"
    if KEEP_NAME_RE.match(base):
        return "KEEP-LIVE", "history-or-identity-name"
    if "quarantine" in rel.split("/"):
        return "KEEP-LIVE", "quarantine-family-failure-card"
    if base.endswith(".pid"):
        try:
            pid = int(open(abs_path).read().strip())
            if pid in pids:
                return "KEEP-LIVE", "live-process-pid"
            return "DELETE", "dead-process-pid"
        except (OSError, ValueError):
            return "KEEP-LIVE", "unreadable-pid"
    if ref_docs:
        return "KEEP-LIVE", "referenced"
    # DELETE criteria (exact)
    if rel in dup_of:
        original, _s = dup_of[rel]
        in_dl = any(norm(original).startswith(dp) for dp in dl_prefixes)
        return "DELETE", ("duplicate-of-downloads" if in_dl
                          else "duplicate-sha-original-exists")
    if size_is_zero(abs_path):
        return "DELETE", "zero-byte-file"
    if kind == "cache":
        return "DELETE", "regenerable-cache"
    # score bins
    if score >= 6 or parts["rar"] >= 2 or parts["con"] >= 2:
        return "RECYCLE", "score%d-r%d-c%d" % (score, parts["rar"],
                                              parts["con"])
    if score >= 4:
        return "KEEP-LIVE", "score%d-mid" % score
    return "COLD", "cold-score%d" % score


def size_is_zero(abs_path):
    try:
        return os.lstat(abs_path).st_size == 0
    except OSError:
        return False


# --------------------------------------------------------------------------
# cards


def card_claim_key(text):
    """Normalized claim sentence for duplicate-card detection."""
    t = re.sub(r"[^a-z0-9가-힣]+", " ", (text or "").lower())
    return " ".join(t.split())[:160]


def list_cards(root):
    cdir = os.path.join(root, CARDS_DIR)
    if not os.path.isdir(cdir):
        return []
    out = []
    for n in sorted(os.listdir(cdir)):
        if n.endswith(".md"):
            out.append(os.path.join(cdir, n))
    return out


def dup_cards(root):
    seen = {}
    dups = []
    for p in list_cards(root):
        rel = norm(os.path.relpath(p, root))
        key = card_claim_key(head_text(p, 4096))
        if key in seen:
            dups.append((rel, seen[key]))
        else:
            seen[key] = rel
    return dups


# --------------------------------------------------------------------------
# commands


def cmd_scan(args):
    rows = scan(args.root, original_dirs=args.original_dir,
                downloads_dirs=args.downloads_dir,
                tracked_file=args.tracked_file, skip_file=args.skip_file)
    out = args.catalog or os.path.join(args.root, CATALOG_DEFAULT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    counts = {}
    for r in rows:
        counts[r["bin"]] = counts.get(r["bin"], 0) + 1
    print(json.dumps({"catalog": out, "rows": len(rows), "bins": counts},
                     ensure_ascii=False))
    return 0


def load_catalog(root, catalog=None):
    path = catalog or os.path.join(root, CATALOG_DEFAULT)
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def cmd_find(args):
    root = args.root
    terms = [t.lower() for t in args.query]
    hits = []
    for card in list_cards(root):
        rel = norm(os.path.relpath(card, root))
        blob = (rel + " " + head_text(card, 4096)).lower()
        if all(t in blob for t in terms):
            hits.append({"path": rel, "bin": "CARD", "score": 999,
                         "summary": title_of(card, os.path.basename(card))})
    try:
        rows = load_catalog(root, args.catalog)
    except OSError:
        rows = []
    for r in rows:
        blob = " ".join([r["path"], r.get("topic") or "",
                         " ".join(r.get("tags") or []),
                         r.get("summary") or "", r.get("why") or ""]).lower()
        if all(t in blob for t in terms):
            hits.append({"path": r["path"], "bin": r["bin"],
                         "score": r["score"] +
                         (30 if r["bin"] == "RECYCLE" else 0) +
                         (10 if r.get("is_representative") else 0),
                         "summary": r.get("summary") or r.get("why")})
    hits.sort(key=lambda h: -h["score"])
    for h in hits[: args.limit]:
        print("%s | %s | %s | %s" % (h["bin"], h["score"], h["path"],
                                   h["summary"][:100]))
    print(json.dumps({"hits": len(hits), "shown": min(len(hits), args.limit)}))
    return 0


def cmd_show(args):
    root = args.root
    q = args.target
    for card in list_cards(root):
        rel = norm(os.path.relpath(card, root))
        if q in (rel, os.path.basename(card),
                 os.path.splitext(os.path.basename(card))[0]):
            with open(card, "r", encoding="utf-8") as f:
                print(f.read())
            return 0
    try:
        rows = load_catalog(root, args.catalog)
    except OSError:
        rows = []
    for r in rows:
        if r["path"] == q or r["path"].endswith("/" + q):
            print(json.dumps(r, ensure_ascii=False, indent=1))
            return 0
    print(json.dumps({"error": "not-found", "target": q}))
    return 4


def plan_rows_from(rows):
    """catalog rows -> copy_residue_cleanup plan row dicts."""
    out = []
    for r in rows:
        cls = {"DELETE": "DELETE", "COLD": "QUARANTINE",
               "RECYCLE": "KEEP", "KEEP-LIVE": "KEEP",
               "TRACKED": "TRACKED_CANDIDATE",
               "SKIP": "SKIP"}.get(r["bin"], "SKIP")
        if cls == "QUARANTINE" and r.get("archive_to"):
            cls = "KEEP"   # archive-bound scripts move via W5, not quarantine
        out.append({"path": r["path"], "class": cls, "reason": r["why"],
                    "kind": "dir" if r["kind"] == "dir" else "file",
                    "size": r["size"], "mtime": r["mtime"],
                    "sha256": r.get("sha256"),
                    "original": r.get("original")})
    return out


def cmd_plan(args):
    rows = scan(args.root, original_dirs=args.original_dir,
                downloads_dirs=args.downloads_dir,
                tracked_file=args.tracked_file, skip_file=args.skip_file)
    prows = plan_rows_from(rows)
    ctx = {"root": os.path.abspath(args.root)}
    out_json = args.plan_json
    out_md = args.plan_md or (out_json.rsplit(".", 1)[0] + ".md")
    os.makedirs(os.path.dirname(out_json), exist_ok=True)
    sha = crc.write_plan(prows, out_json, out_md, ctx)
    counts = {}
    for r in prows:
        counts[r["class"]] = counts.get(r["class"], 0) + 1
    print(json.dumps({"plan": out_json, "planMd": out_md,
                      "planSha256": sha, "rows": len(prows),
                      "classes": counts}, ensure_ascii=False))
    return 0


def cmd_prune(args):
    rows = load_catalog(args.root, args.catalog)
    act = [r for r in rows if r["bin"] in ("DELETE", "COLD")
           and not r.get("archive_to")]
    for r in act:
        p = os.path.join(args.root, r["path"].replace("/", os.sep))
        if os.path.isfile(p) and r["kind"] != "dir":
            try:
                r["sha256"] = sha256_file(p)
            except OSError:
                r["sha256"] = None
    prows = plan_rows_from(act)
    ctx = {"root": os.path.abspath(args.root)}
    out_json = args.plan_json
    out_md = args.plan_md or (out_json.rsplit(".", 1)[0] + ".md")
    os.makedirs(os.path.dirname(out_json), exist_ok=True)
    sha = crc.write_plan(prows, out_json, out_md, ctx)
    print(json.dumps({"plan": out_json, "planMd": out_md,
                      "planSha256": sha, "rows": len(prows),
                      "note": "cold/delete candidates only; apply is manual"},
                     ensure_ascii=False))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("scan")
    s.add_argument("--root", default=".")
    s.add_argument("--catalog")
    s.add_argument("--original-dir", action="append", default=[])
    s.add_argument("--downloads-dir", action="append", default=[])
    s.add_argument("--tracked-file")
    s.add_argument("--skip-file")
    f = sub.add_parser("find")
    f.add_argument("query", nargs="+")
    f.add_argument("--root", default=".")
    f.add_argument("--catalog")
    f.add_argument("--limit", type=int, default=10)
    sh = sub.add_parser("show")
    sh.add_argument("target")
    sh.add_argument("--root", default=".")
    sh.add_argument("--catalog")
    pl = sub.add_parser("plan")
    pl.add_argument("--root", default=".")
    pl.add_argument("--plan-json", required=True)
    pl.add_argument("--plan-md")
    pl.add_argument("--original-dir", action="append", default=[])
    pl.add_argument("--downloads-dir", action="append", default=[])
    pl.add_argument("--tracked-file")
    pl.add_argument("--skip-file")
    pr = sub.add_parser("prune")
    pr.add_argument("--root", default=".")
    pr.add_argument("--catalog")
    pr.add_argument("--plan", dest="plan_json", required=True)
    pr.add_argument("--plan-md")
    args = ap.parse_args(argv)
    if args.mode == "scan":
        return cmd_scan(args)
    if args.mode == "find":
        return cmd_find(args)
    if args.mode == "show":
        return cmd_show(args)
    if args.mode == "plan":
        return cmd_plan(args)
    if args.mode == "prune":
        return cmd_prune(args)


if __name__ == "__main__":
    sys.exit(main())
