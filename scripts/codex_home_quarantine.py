#!/usr/bin/env python3
"""codex_home_quarantine.py — one-purpose quarantine tool for validated stale
files under the Codex home directory (sessions rollouts, tmp leftovers,
stale sqlite copies, gradle cache subtrees under visualizations/).

Moves only — never deletes. Every move is recorded for restore.

Modes:
  candidates   build a NEW manifest-<utc>-<id>.jsonl (old manifests kept)
  preview      summarize a manifest (--run <id> or latest)
  apply        move manifest entries into the rescue root
               [--run <id>] [--expect-sha256 <hex>]   # approval bound to hash
               [--classes a,b,c] [--limit N] [--dry-run]
  status       per-run moved/pending/skipped/conflict breakdown
  restore      move applied entries back, guided by the run's apply-log;
               refuses to overwrite foreign dst changes or a recreated src

Selection rules (deliberately conservative — see
docs/codex-session-cleanup-directive-20260919.md):
  - child-stale-no-evidence : spawned child threads whose rollout file is
    stale (>7d), not archived, not pinned, absent from session_index, and
    having NO fileChange/userMessage items in thread_history.
  - automation-stale        : thread_source='automation', same staleness and
    evidence exclusions.
  - tmp-globalstate         : *codex-global-state.json.tmp-* orphans at the
    Codex home root (atomic-write leftovers).
  - stale-db-copy           : ~/.codex/sqlite/* files idle >60 days, excluding
    the live codex-dev.* set.
  - viz-gradle-cache        : directories named 'caches' whose parent starts
    with 'gradle' under visualizations/ (dependency caches only — evidence,
    png/ndjson/md, and source trees are never selected).

Run lifecycle: every `candidates` run gets a unique runId and an immutable
manifest file; `latest-run.json` points at the newest. `apply` appends to a
per-run apply log and never rewrites an old manifest — rerunning `candidates`
can no longer erase prior evidence (the 2026-09-19 1,952->9 overwrite).
"""

import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import time
import uuid
from pathlib import Path

CODEX_HOME = Path(os.environ.get("CODEX_HOME", r"C:\Users\nninn\.codex"))
RESCUE = Path(r"C:\AbandonWare\_rescue\codex-quarantine-20260919")
LEGACY_MANIFEST = "manifest.jsonl"      # pre-hardening name; read-only fallback
LEGACY_APPLYLOG = "apply-log.jsonl"
STALE_MS = 7 * 24 * 3600 * 1000
DB_IDLE_S = 60 * 24 * 3600


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def file_hash(path: Path, limit=None) -> str | None:
    """sha256 of a file; bounded to `limit` bytes if given."""
    h = hashlib.sha256()
    try:
        with open(path, "rb") as f:
            remaining = limit
            while True:
                want = 1 << 20 if remaining is None else min(1 << 20, remaining)
                if want <= 0:
                    break
                chunk = f.read(want)
                if not chunk:
                    break
                h.update(chunk)
                if remaining is not None:
                    remaining -= len(chunk)
        return h.hexdigest()
    except OSError:
        return None


def manifest_path(run_id: str) -> Path:
    return RESCUE / f"manifest-{run_id}.jsonl"


def applylog_path(run_id: str) -> Path:
    return RESCUE / (LEGACY_APPLYLOG if run_id == "legacy" else f"apply-log-{run_id}.jsonl")


def restorelog_path(run_id: str) -> Path:
    return RESCUE / f"restore-log-{run_id}.jsonl"


def latest_pointer() -> dict | None:
    p = RESCUE / "latest-run.json"
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def resolve_run(run_id: str | None, manifest: str | None) -> tuple[str, Path]:
    """Pick the manifest for a command. Explicit --manifest wins; --run names a
    stored run; otherwise the latest pointer, then the legacy manifest."""
    if manifest:
        p = Path(manifest)
        rid = p.stem.removeprefix("manifest-") or "external"
        return rid, p
    if run_id:
        return run_id, manifest_path(run_id)
    latest = latest_pointer()
    if latest:
        return latest["runId"], RESCUE / latest["manifest"]
    return "legacy", RESCUE / LEGACY_MANIFEST


def ro(db: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)


# Lines worth parsing when scanning a rollout for write/user evidence.
# 'apply_patch' inside session_meta/instructions is ignored: only
# response_item payloads (function_call/custom_tool_call/local_shell_call)
# and event_msg user_message count.
SUSPICIOUS_LINE = re.compile(
    rb"apply_patch|user_message|function_call|custom_tool_call|local_shell_call"
)
WRITE_ARG = re.compile(
    r"apply_patch|Set-Content|Out-File|Add-Content|Copy-Item|Move-Item|New-Item"
    r"|Remove-Item|Rename-Item|Set-ItemProperty|\bmkdir\b|\brm\b|\bdel\b"
    r"|git\s+(add|commit|checkout|restore|apply)|sed\s+-i|\btee\b|>>"
    r"|(?<![0-9&|>])>\s*[\"'\w$]|python\s+-c|code\s"
)


def rollout_write_evidence(path: Path, offset: int = 0):
    """Return a short marker string if the rollout (from byte offset) shows
    file-write or interactive-user evidence, else None."""
    try:
        f = open(path, "rb")
    except OSError:
        return "unreadable"
    with f:
        if offset:
            f.seek(offset)
        for raw in f:
            if not SUSPICIOUS_LINE.search(raw):
                continue
            try:
                j = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            t = j.get("type")
            p = j.get("payload") if isinstance(j.get("payload"), dict) else {}
            if t == "event_msg":
                if p.get("type") == "user_message":
                    return "user_message-event"
                continue
            if t != "response_item":
                continue
            pt = p.get("type")
            if pt == "fileChange":
                return "fileChange-item"
            if pt in ("function_call", "custom_tool_call", "local_shell_call"):
                name = str(p.get("name") or "")
                args = str(p.get("arguments") or p.get("input") or "")
                if name == "apply_patch" or WRITE_ARG.search(args):
                    return f"{name}:{args[:60]}"


def build_candidates() -> list[dict]:
    now_ms = int(time.time() * 1000)
    out: list[dict] = []
    rejected: dict[str, int] = {}

    state = ro(CODEX_HOME / "state_5.sqlite")
    threads = {}
    for r in state.execute(
        "SELECT id, rollout_path, updated_at_ms, archived, is_pinned, title, thread_source "
        "FROM threads"
    ):
        threads[r[0]] = {
            "rollout": r[1], "updated_ms": r[2] or 0, "archived": r[3] or 0,
            "pinned": r[4] or 0, "title": r[5] or "", "source": r[6] or "",
        }
    children = {r[0] for r in state.execute("SELECT DISTINCT child_thread_id FROM thread_spawn_edges")}
    state.close()

    hist = ro(CODEX_HOME / "thread_history_1.sqlite")
    evidence = {
        r[0]
        for r in hist.execute(
            "SELECT DISTINCT thread_id FROM thread_items "
            "WHERE item_type IN ('fileChange','userMessage')"
        )
    }
    projected_ids = {
        r[0] for r in hist.execute("SELECT DISTINCT thread_id FROM thread_items")
    }
    offsets = {
        r[0]: r[1] or 0
        for r in hist.execute(
            "SELECT thread_id, next_rollout_byte_offset FROM thread_history_projection_state"
        )
    }
    hist.close()

    indexed = set()
    idx = CODEX_HOME / "session_index.jsonl"
    if idx.exists():
        for line in idx.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                indexed.add(json.loads(line).get("id"))
            except json.JSONDecodeError:
                continue

    def stale(t):
        return t["updated_ms"] < now_ms - STALE_MS

    def protected(tid, t):
        return (
            t["archived"] or t["pinned"] or tid in evidence or tid in indexed
        )

    def rollout_entry(tid, t, cls):
        p = Path(t["rollout"]) if t["rollout"] else None
        if not p or not p.exists():
            return None
        st = p.stat()
        # Verification basis: threads fully projected into thread_history are
        # DB-verified (no fileChange/userMessage items). Threads absent from
        # or only partially covered by the projection get a rollout scan of
        # the unprojected bytes for write/interactive evidence.
        basis = "items-clean"
        scan_from = 0
        if tid in projected_ids:
            off = offsets.get(tid, 0)
            if off < st.st_size:
                scan_from = off
                basis = "tail-scan"
            else:
                scan_from = -1
        if scan_from >= 0:
            marker = rollout_write_evidence(p, scan_from)
            if marker:
                rejected[marker.split(":")[0]] = rejected.get(marker.split(":")[0], 0) + 1
                return None
            basis = "scan-clean" if scan_from == 0 else "tail-scan-clean"
        return {
            "class": cls, "id": tid, "src": str(p), "bytes": st.st_size,
            "mtime_ns": st.st_mtime_ns, "basis": basis,
            "reason": f"{cls}; updated_ms={t['updated_ms']}; title={t['title'][:60]}",
        }

    for tid, t in threads.items():
        if not stale(t) or protected(tid, t):
            continue
        if tid in children:
            e = rollout_entry(tid, t, "child-stale-no-evidence")
        elif t["source"] == "automation":
            e = rollout_entry(tid, t, "automation-stale")
        else:
            continue
        if e:
            out.append(e)
    if rejected:
        print("rejected by rollout scan:", rejected, file=sys.stderr)

    for f in sorted(CODEX_HOME.glob("*.tmp-*")):
        if "codex-global-state" in f.name and f.is_file():
            st = f.stat()
            out.append({"class": "tmp-globalstate", "id": f.name, "src": str(f),
                        "bytes": st.st_size, "mtime_ns": st.st_mtime_ns,
                        "reason": "atomic-write leftover"})

    sq = CODEX_HOME / "sqlite"
    if sq.is_dir():
        cutoff = time.time() - DB_IDLE_S
        for f in sorted(sq.iterdir()):
            if f.is_file() and not f.name.startswith("codex-dev") and f.stat().st_mtime < cutoff:
                st = f.stat()
                out.append({"class": "stale-db-copy", "id": f.name, "src": str(f),
                            "bytes": st.st_size, "mtime_ns": st.st_mtime_ns,
                            "reason": "idle>60d db copy under sqlite/"})

    viz = CODEX_HOME / "visualizations"
    if viz.is_dir():
        for root, dirs, _files in os.walk(viz):
            for d in list(dirs):
                p = Path(root) / d
                if d == "caches" and p.parent.name.lower().startswith("gradle"):
                    out.append({"class": "viz-gradle-cache", "id": str(p.relative_to(CODEX_HOME)),
                                "src": str(p), "bytes": tolerant_size(p),
                                "mtime_ns": p.stat().st_mtime_ns, "is_dir": True,
                                "reason": "gradle dependency cache subtree"})
                    dirs.remove(d)  # do not descend into selected tree
    return out


def tolerant_size(p: Path) -> int:
    """Best-effort byte count; some cache paths exceed Win32 limits."""
    total = 0
    def onerr(_e):
        return None
    for root, _dirs, files in os.walk(p, onerror=onerr):
        for f in files:
            try:
                total += (Path(root) / f).stat().st_size
            except OSError:
                continue
    return total


def norm_src(text: str) -> Path:
    """DB rollout paths may carry the \\\\?\\ extended-length prefix; strip it
    for relative-path math while keeping the real location."""
    return Path(text[4:] if text.startswith("\\\\?\\") else text)


def dst_for(entry: dict) -> Path:
    rel = norm_src(entry["src"]).resolve().relative_to(CODEX_HOME.resolve())
    return RESCUE / entry["class"] / rel


def cmd_candidates() -> int:
    RESCUE.mkdir(parents=True, exist_ok=True)
    entries = build_candidates()
    seen = set()
    uniq = []
    for e in entries:
        key = str(Path(e["src"]).resolve())
        if key not in seen:
            seen.add(key)
            uniq.append(e)
    # Every generation is a new, immutable manifest — reruns never overwrite.
    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8]
    mpath = manifest_path(run_id)
    with mpath.open("x", encoding="utf-8") as w:
        for e in uniq:
            w.write(json.dumps(e, ensure_ascii=False) + "\n")
    pointer = {"runId": run_id, "manifest": mpath.name,
               "manifestSha256": sha256(mpath), "entries": len(uniq),
               "createdAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    tmp = RESCUE / f"latest-run.{uuid.uuid4().hex}.tmp"
    tmp.write_text(json.dumps(pointer, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, RESCUE / "latest-run.json")
    print(f"runId: {run_id}")
    print(f"manifest: {mpath} entries={len(uniq)} sha256={pointer['manifestSha256']}")
    cmd_preview(run_id=run_id)
    return 0


def cmd_preview(run_id=None, manifest=None) -> int:
    rid, mpath = resolve_run(run_id, manifest)
    agg: dict[str, list] = {}
    for line in mpath.read_text(encoding="utf-8").splitlines():
        e = json.loads(line)
        a = agg.setdefault(e["class"], [0, 0, []])
        a[0] += 1
        a[1] += e["bytes"]
        if len(a[2]) < 3:
            a[2].append(e["id"])
    print(f"run={rid} manifest={mpath.name} sha256={sha256(mpath)}")
    for cls, (n, b, samples) in sorted(agg.items()):
        print(f"{cls:28s} n={n:5d} bytes={b:>13,} ({b/1048576:,.1f} MiB)")
        for s in samples:
            print(f"    sample: {s}")
    return 0


def iter_manifest(mpath: Path, classes=None):
    for line in mpath.read_text(encoding="utf-8").splitlines():
        e = json.loads(line)
        if classes and e["class"] not in classes:
            continue
        yield e


def dir_file_count(p: Path) -> int:
    n = 0
    def onerr(_e):
        return None
    for _root, _dirs, files in os.walk(p, onerror=onerr):
        n += len(files)
    return n


def cmd_apply(run_id=None, manifest=None, expect_sha256=None,
              classes=None, limit=None, dry_run=False) -> int:
    rid, mpath = resolve_run(run_id, manifest)
    if not mpath.exists():
        print(f"manifest missing: {mpath}")
        return 2
    actual_sha = sha256(mpath)
    if expect_sha256 and actual_sha != expect_sha256.lower():
        print(f"manifest-approval-mismatch: expected {expect_sha256} actual {actual_sha}")
        print("the approved list is not this manifest - approval is bound to hash")
        return 2
    home = CODEX_HOME.resolve()
    log_path = applylog_path(rid)
    moved = skipped = 0
    log = log_path.open("a", encoding="utf-8")
    for e in iter_manifest(mpath, classes):
        if limit and moved >= limit:
            break
        src = Path(e["src"])
        rec = {**e, "runId": rid, "manifestSha256": actual_sha}
        try:
            resolved = norm_src(e["src"]).resolve()
            resolved.relative_to(home)
        except (ValueError, OSError):
            log.write(json.dumps({**rec, "result": "skip-outside-home"}) + "\n")
            skipped += 1
            continue
        dst = dst_for(e)
        if not src.exists():
            if dst.exists():
                log.write(json.dumps({**rec, "dst": str(dst),
                                      "result": "already-moved"}) + "\n")
            else:
                log.write(json.dumps({**rec, "result": "skip-missing"}) + "\n")
            skipped += 1
            continue
        st = src.stat()
        unchanged = (
            st.st_mtime_ns == e["mtime_ns"]
            if e.get("is_dir")
            else (st.st_size == e["bytes"] and st.st_mtime_ns == e["mtime_ns"])
        )
        if not unchanged:
            log.write(json.dumps({**rec, "result": "skip-changed-since-scan"}) + "\n")
            skipped += 1
            continue
        if dst.exists():
            log.write(json.dumps({**rec, "result": "skip-dst-exists"}) + "\n")
            skipped += 1
            continue
        if dry_run:
            print(f"DRY move {src} -> {dst} ({e['bytes']:,}B)")
            moved += 1
            continue
        # Hash evidence: files get full content hashes; dirs get a file count.
        rec["preSha256"] = None if e.get("is_dir") else file_hash(src)
        rec["preFileCount"] = dir_file_count(src) if e.get("is_dir") else None
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
        ok = dst.exists() and not src.exists()
        if ok:
            rec["postSha256"] = None if e.get("is_dir") else file_hash(dst)
            rec["postFileCount"] = dir_file_count(dst) if e.get("is_dir") else None
            if not e.get("is_dir") and rec["preSha256"] != rec["postSha256"]:
                ok = False
                rec["result"] = "move-hash-mismatch"
            elif e.get("is_dir") and rec["preFileCount"] != rec["postFileCount"]:
                ok = False
                rec["result"] = "move-filecount-mismatch"
        log.write(json.dumps({**rec, "dst": str(dst),
                              "result": rec.get("result") or ("moved" if ok else "move-uncertain"),
                              "moved_at": time.time()}) + "\n")
        moved += ok
        skipped += (not ok)
    log.close()
    print(f"apply done: run={rid} moved={moved} skipped={skipped} dry={dry_run} log={log_path.name}")
    return 0


def cmd_status(run_id=None, manifest=None) -> int:
    rid, mpath = resolve_run(run_id, manifest)
    results: dict[str, int] = {}
    moved_ids: set[str] = set()
    if applylog_path(rid).exists():
        for line in applylog_path(rid).read_text(encoding="utf-8").splitlines():
            e = json.loads(line)
            r = e.get("result", "?")
            results[r] = results.get(r, 0) + 1
            if r == "moved":
                moved_ids.add(e["id"])
    total = sum(1 for _ in iter_manifest(mpath)) if mpath.exists() else 0
    pending = max(0, total - sum(results.values()))
    print(json.dumps({"runId": rid, "manifestEntries": total, "pending": pending,
                      "results": results, "movedCount": len(moved_ids)},
                     ensure_ascii=False))
    return 0


def cmd_restore(run_id=None, manifest=None) -> int:
    rid, mpath = resolve_run(run_id, manifest)
    if not applylog_path(rid).exists():
        print(f"no apply-log for run={rid}")
        return 1
    # Idempotent: entries already restored stay restored.
    restored_ids: set[str] = set()
    if restorelog_path(rid).exists():
        for line in restorelog_path(rid).read_text(encoding="utf-8").splitlines():
            e = json.loads(line)
            if e.get("result") == "restored":
                restored_ids.add(e["id"])
    rlog = restorelog_path(rid).open("a", encoding="utf-8")
    restored = failed = 0
    for line in applylog_path(rid).read_text(encoding="utf-8").splitlines():
        e = json.loads(line)
        if e.get("result") != "moved" or e["id"] in restored_ids:
            continue
        src, dst = Path(e["src"]), Path(e["dst"])
        rec = {**e, "runId": rid}
        if src.exists():
            # Something recreated the source path — a foreign change. Never
            # overwrite it; report and leave both sides intact.
            rec["result"] = "conflict-src-exists"
            rec["srcSha256"] = None if e.get("is_dir") else file_hash(src)
            rlog.write(json.dumps(rec) + "\n")
            failed += 1
            continue
        if not dst.exists():
            rec["result"] = "conflict-dst-missing"
            rlog.write(json.dumps(rec) + "\n")
            failed += 1
            continue
        if not e.get("is_dir") and e.get("postSha256") and file_hash(dst) != e["postSha256"]:
            rec["result"] = "conflict-dst-changed"
            rec["dstSha256"] = file_hash(dst)
            rlog.write(json.dumps(rec) + "\n")
            failed += 1
            continue
        src.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(dst), str(src))
        ok = src.exists() and not dst.exists()
        rec["result"] = "restored" if ok else "restore-uncertain"
        rec["restored_at"] = time.time()
        rlog.write(json.dumps(rec) + "\n")
        restored += ok
        failed += (not ok)
    rlog.close()
    print(f"restore done: run={rid} restored={restored} conflict/skipped={failed} log={restorelog_path(rid).name}")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    global CODEX_HOME, RESCUE
    mode = args[0]
    opts = set()
    kv = {}
    for a in args[1:]:
        if a.startswith("--"):
            if "=" in a:
                k, v = a[2:].split("=", 1)
                kv[k] = v
            else:
                opts.add(a[2:])
    if "codex-home" in kv:
        CODEX_HOME = Path(kv["codex-home"])
    if "rescue" in kv:
        RESCUE = Path(kv["rescue"])
    if mode == "candidates":
        return cmd_candidates()
    if mode == "preview":
        return cmd_preview(run_id=kv.get("run"), manifest=kv.get("manifest"))
    if mode == "apply":
        classes = set(kv["classes"].split(",")) if "classes" in kv else None
        return cmd_apply(run_id=kv.get("run"), manifest=kv.get("manifest"),
                         expect_sha256=kv.get("expect-sha256"),
                         classes=classes,
                         limit=int(kv.get("limit", "0")) or None,
                         dry_run="dry-run" in opts)
    if mode == "status":
        return cmd_status(run_id=kv.get("run"), manifest=kv.get("manifest"))
    if mode == "restore":
        return cmd_restore(run_id=kv.get("run"), manifest=kv.get("manifest"))
    print("unknown mode")
    return 1


if __name__ == "__main__":
    sys.exit(main())
