#!/usr/bin/env python3
"""dot_tower_status.py - read-only status board for dot (ChatGPT control tower) briefs.

Collects (all local, 0 writes, 0 network):
 1. PASTE_CODEX_<UPPER_SNAKE>_<YYYYMMDD>.md files under --downloads and
    --documents (Documents/Codex/<date>/task-*), with name, size, sha12,
    mtime in KST.
 2. Journals under data/agent-handoff/**/journal.json and source-edit
    leases under __patch_drop__/source-edit-locks/*.lock/lease.json.
 3. Per-brief ledger match by UPPER_SNAKE tokens -> RUNNING / CLOSED /
    NOT_STARTED / UNKNOWN.
 4. configs/dot-tower-ratchet.json invariant states via behavior_ratchet.py
    check --json (read-only; missing config is not an error).

Exit codes: 0 ok / 3 lease conflict (same file under >=2 live leases) /
4 input path missing / 1 unexpected error.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from log_redact import redact_text
except ImportError:
    try:
        from build_error_miner import SECRET_FRAGMENT_RE

        def redact_text(text):
            return SECRET_FRAGMENT_RE.sub("[redacted]", text), {}
    except ImportError:
        def redact_text(text):
            return text, {}

SCHEMA = "awx.dot_tower_status.v1"
KST = timezone(timedelta(hours=9))
BRIEF_RE = re.compile(r"^PASTE_CODEX_([A-Z0-9]+(?:_[A-Z0-9]+)*)_(\d{8})\.md$")
JOURNAL_GLOB = "journal.json"
LEASE_GLOB = "*.lock/lease.json"
HANDOFF = "data/agent-handoff"
LOCKS = "__patch_drop__/source-edit-locks"
RATCHET_CFG = "configs/dot-tower-ratchet.json"
RATCHET_LOCK = "configs/dot-tower-ratchet.lock.json"
MATCH_STRONG = 0.5
EXIT_OK, EXIT_ERR, EXIT_CONFLICT, EXIT_MISSING = 0, 1, 3, 4


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig", errors="replace"))
    except (OSError, ValueError):
        return None


def sha12(path):
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    except OSError:
        return None


def kst(ts):
    return datetime.fromtimestamp(ts, KST).isoformat(timespec="seconds")


def brief_meta(path):
    m = BRIEF_RE.match(path.name)
    if not m:
        return None
    try:
        st = path.stat()
    except OSError:
        return None
    return {"name": path.name, "dir": str(path.parent), "topic": m.group(1),
            "date": m.group(2), "tokens": [t for t in m.group(1).split("_") if t],
            "size": st.st_size, "sha12": sha12(path),
            "mtimeKst": kst(st.st_mtime)}


def collect_briefs(downloads, documents, since_hours):
    cutoff = datetime.now(KST) - timedelta(hours=since_hours)
    found = {}
    roots = []
    if downloads and downloads.is_dir():
        roots += list(downloads.glob("PASTE_CODEX_*.md"))
    if documents and documents.is_dir():
        roots += list(documents.glob("*/task-*/PASTE_CODEX_*.md"))
        roots += list(documents.glob("task-*/PASTE_CODEX_*.md"))
    for p in roots:
        meta = brief_meta(p)
        if meta is None:
            continue
        try:
            if datetime.fromtimestamp(p.stat().st_mtime, KST) < cutoff:
                continue
        except OSError:
            continue
        prev = found.get(meta["name"])
        if prev is None or meta["mtimeKst"] > prev["mtimeKst"]:
            found[meta["name"]] = meta
    return sorted(found.values(), key=lambda b: b["mtimeKst"], reverse=True)


def token_hits(tokens, name):
    nl = name.lower()
    hits = []
    for tok in tokens:
        tl = tok.lower()
        cands = [tl] + ([tl[:-1]] if tl.endswith("s") and len(tl) > 3 else [])
        if any(re.search(r"\b" + re.escape(c) + r"\b", nl) for c in cands if c):
            hits.append(tok)
    return hits


def collect_ledgers(root):
    journals, leases = [], []
    handoff = root / HANDOFF
    if handoff.is_dir():
        for jf in sorted(handoff.rglob(JOURNAL_GLOB)):
            doc = read_json(jf)
            if not isinstance(doc, dict):
                continue
            status = str(doc.get("status") or "unknown").lower()
            state = ("running" if status == "in_progress" else
                     "closed" if status in ("closed", "done", "completed", "complete")
                     else "other")
            journals.append({"taskId": doc.get("taskId") or jf.parent.name,
                             "agent": doc.get("agent"), "status": status,
                             "state": state,
                             "purpose": (doc.get("purpose") or "")[:200],
                             "scope": doc.get("plannedScope") or [],
                             "updatedAtUtc": doc.get("updatedAtUtc")})
    lock_root = root / LOCKS
    now = datetime.now(timezone.utc)
    if lock_root.is_dir():
        for lf in sorted(lock_root.glob(LEASE_GLOB)):
            doc = read_json(lf)
            if not isinstance(doc, dict):
                continue
            exp = str(doc.get("expiresAtUtc") or doc.get("expiresAt") or "")
            live = bool(doc.get("mutationAllowed"))
            try:
                when = datetime.fromisoformat(exp.replace("Z", "+00:00"))
                if when.tzinfo is None:
                    when = when.replace(tzinfo=timezone.utc)
                live = when > now
            except ValueError:
                live = True  # unreadable expiry -> conservative live
            leases.append({"topic": lf.parent.name[:-5],
                           "files": doc.get("targetPaths") or [],
                           "expiresAtUtc": exp or None, "live": live})
    return journals, leases


def lease_conflicts(leases):
    owners = {}
    for le in leases:
        if not le["live"]:
            continue
        for f in le["files"]:
            owners.setdefault(f.replace("\\", "/").lower(), set()).add(le["topic"])
    return [{"file": f, "leases": sorted(t)} for f, t in sorted(owners.items())
            if len(t) > 1]


def classify_brief(meta, journals, leases):
    tokens = meta.get("tokens") or []
    if not tokens:
        return "UNKNOWN", []
    strong, weak = [], []
    candidates = [(j["taskId"], j["state"]) for j in journals] + \
                 [(le["topic"], "running" if le["live"] else "closed") for le in leases]
    for name, state in candidates:
        hits = token_hits(tokens, name)
        if not hits:
            continue
        (strong if len(hits) / len(tokens) >= MATCH_STRONG else weak).append(
            {"ledger": name, "state": state, "hits": hits})
    if strong:
        status = "RUNNING" if any(m["state"] == "running" for m in strong) else "CLOSED"
        return status, strong
    if weak:
        return "UNKNOWN", weak
    return "NOT_STARTED", []


def ratchet_summary(root):
    cfg = root / RATCHET_CFG
    if not cfg.is_file():
        return {"status": "config-missing", "config": RATCHET_CFG}
    argv = [sys.executable, "-B", str(root / "scripts" / "behavior_ratchet.py"),
            "--root", str(root), "--config", RATCHET_CFG,
            "--lock", RATCHET_LOCK, "check", "--json"]
    try:
        proc = subprocess.run(argv, cwd=str(root), capture_output=True,
                              text=True, timeout=30, encoding="utf-8",
                              errors="replace")
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"status": "error", "reason": str(exc)}
    try:
        doc = json.loads(proc.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {"status": "error", "reason": "unparseable-output",
                "exit": proc.returncode}
    return {"status": "ok", "exit": proc.returncode,
            "verdict": doc.get("verdict"), "counts": doc.get("counts"),
            "entries": [{"id": r.get("id"), "state": r.get("state")}
                        for r in doc.get("results") or []]}


def build_doc(root, downloads, documents, since_hours):
    briefs = collect_briefs(downloads, documents, since_hours)
    journals, leases = collect_ledgers(root)
    conflicts = lease_conflicts(leases)
    for b in briefs:
        status, matched = classify_brief(b, journals, leases)
        b["status"] = status
        b["matched"] = matched
    summary = {"briefs": len(briefs),
               "running": sum(1 for b in briefs if b["status"] == "RUNNING"),
               "closed": sum(1 for b in briefs if b["status"] == "CLOSED"),
               "notStarted": sum(1 for b in briefs if b["status"] == "NOT_STARTED"),
               "unknown": sum(1 for b in briefs if b["status"] == "UNKNOWN"),
               "leaseConflicts": len(conflicts)}
    return {"schemaVersion": SCHEMA,
            "generatedAtKst": datetime.now(KST).isoformat(timespec="seconds"),
            "sinceHours": since_hours, "root": str(root),
            "downloads": str(downloads), "documents": str(documents),
            "briefs": briefs, "journals": journals, "leases": leases,
            "leaseConflicts": conflicts, "ratchet": ratchet_summary(root),
            "summary": summary}


def render_md(doc):
    lines = ["# dot tower status (%s)" % doc["generatedAtKst"], "",
             "| brief | status | matched |", "|---|---|---|"]
    for b in doc["briefs"]:
        names = ", ".join(m["ledger"] for m in b.get("matched") or []) or "-"
        lines.append("| %s | %s | %s |" % (b["name"], b["status"], names))
    s = doc["summary"]
    lines += ["", "briefs=%(briefs)d RUNNING=%(running)d CLOSED=%(closed)d "
              "NOT_STARTED=%(notStarted)d UNKNOWN=%(unknown)d "
              "leaseConflicts=%(leaseConflicts)d" % s]
    rat = doc.get("ratchet") or {}
    if rat.get("entries"):
        lines.append("ratchet: " + ", ".join(
            "%s=%s" % (e["id"], e["state"]) for e in rat["entries"]))
    else:
        lines.append("ratchet: %s" % rat.get("status", "?"))
    return "\n".join(lines)


def main(argv=None):
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            try:
                s.reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError):
                pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--since-hours", type=int, default=24)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--downloads",
                    default=str(Path.home() / "Downloads"))
    ap.add_argument("--documents",
                    default=str(Path.home() / "Documents" / "Codex"))
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    downloads, documents = Path(args.downloads), Path(args.documents)
    if not downloads.is_dir() and not documents.is_dir():
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": "input-paths-missing",
                          "downloads": str(downloads),
                          "documents": str(documents)}, ensure_ascii=False))
        return EXIT_MISSING
    try:
        doc = build_doc(root, downloads, documents, args.since_hours)
    except Exception as exc:  # noqa: BLE001 - top-level guard for exit code 1
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"{type(exc).__name__}:{exc}"},
                         ensure_ascii=False))
        return EXIT_ERR
    out = json.dumps(doc, ensure_ascii=False, indent=2) if args.json else render_md(doc)
    out, _counts = redact_text(out)
    print(out)
    return EXIT_CONFLICT if doc["leaseConflicts"] else EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
