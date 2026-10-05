#!/usr/bin/env python3
"""behavior_ratchet.py - one-way ratchet so landed behavior/rules never silently revert.

stdlib only. SSOT: configs/behavior-ratchet.json (entries) +
configs/behavior-ratchet.lock.json (what has been observed and locked).

States per entry
  PENDING   new behavior not landed yet (not a failure; patch still coming)
  LANDED    new behavior observed but not locked yet (run `update` to lock)
  LOCKED    locked and still holding
  IN_FLIGHT locked entry looks broken BUT its file is under a live source-edit
            lease (owning session is mid-edit) -> reported, not failed
  REVERTED  locked entry no longer holds and no live lease covers it -> exit 4

Commands
  check  [--json] [--id ID]          read-only; exit 0 ok/pending, 4 reverted, 1 config error
  update [--dry-run] [--task T]      lock LANDED entries (skips files under a live lease);
                                     test-name locks only ever grow
  unlock --id ID --adr PATH          only with an ADR: status: ACCEPTED, approvedBy: user,
                                     ratchet: ID  (recorded in lock history)
  list                               entries + state, short

Entry kinds (configs/behavior-ratchet.json "entries")
  present     {"file", "pattern", "oldPattern"?, "section"?}
              holds when pattern matches and (if given) oldPattern does not
  absent      {"files": [glob...], "exclude": [glob...]?, "pattern"}
              holds when no file matches pattern
  test_names  {"file", "lang": "java"|"js"}
              holds when every locked test name is still present
"section": {"start": regex, "mode": "indent"} limits matching to the YAML-style
block that starts at the first line matching `start` (until a line with
indentation <= the start line).

Alternate entry file: `--config <path>` + `--lock <path>` (defaults stay
configs/behavior-ratchet*.json) let separate invariant packs keep separate
locks. Config-level "flags": {name: bool} + entry "pendingAs" + "promoteFlag":
a failing entry reports the pendingAs string (warn, not failure) while the
named flag is false; set it true to restore normal PENDING/REVERTED semantics.
"""
import argparse
import datetime as _dt
import fnmatch
import hashlib
import json
import os
import re
import sys
from pathlib import Path

SCHEMA = "awx.behavior-ratchet.v1"
CONFIG_REL = "configs/behavior-ratchet.json"
LOCK_REL = "configs/behavior-ratchet.lock.json"
LEASE_DIR_REL = "__patch_drop__/source-edit-locks"
ADR_DIR_REL = "docs/architecture/decisions"
EXIT_OK, EXIT_ERR, EXIT_REVERTED = 0, 1, 4

JAVA_TEST_ANN_RE = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
# JUnit test methods return void; @TestFactory returns a stream/collection of dynamic tests.
JAVA_METHOD_RE = re.compile(
    r"\b(?:void|Stream<[^>]*>|Collection<[^>]*>|Iterable<[^>]*>|List<[^>]*>|DynamicNode|DynamicTest|DynamicContainer)"
    r"\s+(\w+)\s*\(")
JS_TEST_RE = re.compile(r"\b(?:test|it)\s*\(\s*(['\"`])((?:\\.|(?!\1).)+)\1", re.S)


class RatchetError(Exception):
    pass


def utcnow():
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat()


def read_text(path: Path):
    try:
        return path.read_bytes().decode("utf-8", errors="replace").replace("\r\n", "\n")
    except FileNotFoundError:
        return None


def sha12(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12] if text is not None else None


def load_json(path: Path, default=None):
    text = read_text(path)
    if text is None:
        if default is not None:
            return default
        raise RatchetError("missing:" + str(path.name))
    try:
        return json.loads(text)
    except ValueError as err:
        raise RatchetError(f"bad-json:{path.name}:{err}")


def save_json_atomic(path: Path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def canon(rel):
    return str(rel).replace("\\", "/").strip("/").lower()


def live_leased_paths(root: Path, now=None):
    """Canonical (lowercase) target paths of unexpired source-edit leases."""
    now = now or _dt.datetime.now(_dt.timezone.utc)
    out = set()
    base = root / LEASE_DIR_REL
    if not base.is_dir():
        return out
    for lease in base.rglob("lease.json"):
        try:
            doc = json.loads(read_text(lease) or "{}")
        except ValueError:
            continue
        exp = str(doc.get("expiresAtUtc") or doc.get("expiresAt") or "")
        try:
            when = _dt.datetime.fromisoformat(exp.replace("Z", "+00:00"))
            if when.tzinfo is None:
                when = when.replace(tzinfo=_dt.timezone.utc)
            if when <= now:
                continue
        except ValueError:
            pass  # unreadable expiry -> treat as live (conservative: only delays locks)
        for p in doc.get("targetPaths") or []:
            out.add(canon(p))
    return out


def section_of(text, section):
    if not section:
        return text
    lines = text.split("\n")
    start_re = re.compile(section["start"])
    for i, line in enumerate(lines):
        if start_re.search(line):
            indent = len(line) - len(line.lstrip(" "))
            body = [line]
            for nxt in lines[i + 1:]:
                if nxt.strip() == "" or nxt.lstrip().startswith("#"):
                    body.append(nxt)
                    continue
                if len(nxt) - len(nxt.lstrip(" ")) <= indent:
                    break
                body.append(nxt)
            return "\n".join(body)
    return None


def test_names(text, lang):
    if text is None:
        return None
    if lang == "java":
        names = set()
        for ann in JAVA_TEST_ANN_RE.finditer(text):
            m = JAVA_METHOD_RE.search(text, ann.end())
            if m:
                names.add(m.group(1))
        return sorted(names)
    if lang == "js":
        return sorted(set(m[1].strip() for m in JS_TEST_RE.findall(text)))
    raise RatchetError("bad-lang:" + str(lang))


def expand(root: Path, globs, excludes=()):
    files = []
    for g in globs:
        for p in sorted(root.glob(g)):
            if not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            if any(fnmatch.fnmatch(rel, ex) for ex in excludes):
                continue
            files.append(rel)
    return sorted(set(files))


def evaluate(root: Path, entry, lock, leased, flags=None):
    kind = entry.get("kind")
    eid = entry["id"]
    res = {"id": eid, "kind": kind, "contract": entry.get("contract"),
           "locked": lock is not None}
    files = []
    if kind == "present":
        files = [entry["file"]]
        text = read_text(root / entry["file"])
        scope = section_of(text, entry.get("section")) if text is not None else None
        new_ok = scope is not None and re.search(entry["pattern"], scope, re.M) is not None
        old_hit = bool(entry.get("oldPattern")) and scope is not None and \
            re.search(entry["oldPattern"], scope, re.M) is not None
        holds = new_ok and not old_hit
        res["detail"] = {"fileMissing": text is None, "sectionMissing": text is not None and scope is None,
                         "newMatched": new_ok, "oldMatched": old_hit}
    elif kind == "absent":
        files = expand(root, entry["files"], entry.get("exclude", []))
        hits = []
        rx = re.compile(entry["pattern"], re.M)
        for rel in files:
            text = read_text(root / rel) or ""
            for m in rx.finditer(text):
                hits.append(f"{rel}:{text.count(chr(10), 0, m.start()) + 1}")
                if len(hits) >= 10:
                    break
        holds = not hits and bool(files or entry.get("allowNoFiles"))
        res["detail"] = {"filesScanned": len(files), "hits": hits}
    elif kind == "test_names":
        files = [entry["file"]]
        names = test_names(read_text(root / entry["file"]), entry.get("lang", "java"))
        locked_names = set((lock or {}).get("names") or [])
        missing = sorted(locked_names - set(names or []))
        holds = names is not None and (not locked_names or not missing) and bool(names)
        res["detail"] = {"fileMissing": names is None, "count": len(names or []),
                         "lockedCount": len(locked_names), "missing": missing[:20]}
        res["_names"] = names or []
    else:
        raise RatchetError(f"bad-kind:{eid}:{kind}")

    res["files"] = files
    under_lease = sorted(f for f in files if canon(f) in leased)
    res["leased"] = under_lease
    pending_as = entry.get("pendingAs")
    promoted = bool((flags or {}).get(entry.get("promoteFlag") or "", False))
    if not holds and pending_as and not promoted:
        res["state"] = pending_as
    elif lock is None:
        res["state"] = "LANDED" if holds else "PENDING"
    elif holds:
        res["state"] = "LOCKED"
    else:
        res["state"] = "IN_FLIGHT" if under_lease else "REVERTED"
    return res


def run_check(root: Path, cfg_path: Path, lock_path: Path, only=None):
    cfg = load_json(cfg_path)
    lockdoc = load_json(lock_path, default={"schemaVersion": SCHEMA, "locks": {}, "history": []})
    flags = cfg.get("flags") or {}
    entries = cfg.get("entries") or []
    ids = [e.get("id") for e in entries]
    if len(ids) != len(set(ids)) or not all(ids):
        raise RatchetError("duplicate-or-empty-id")
    leased = live_leased_paths(root)
    results = []
    for e in entries:
        if only and e["id"] != only:
            continue
        results.append(evaluate(root, e, lockdoc["locks"].get(e["id"]), leased, flags))
    for stale in sorted(set(lockdoc["locks"]) - set(ids)):
        # a lock whose entry was deleted from the config is itself a revert of the rule
        results.append({"id": stale, "kind": "config", "locked": True, "files": [str(cfg_path)],
                        "leased": [], "state": "REVERTED",
                        "detail": {"reason": "entry-removed-from-config-while-locked"}})
    return cfg, lockdoc, results


def summarize(results):
    counts = {}
    for r in results:
        counts[r["state"]] = counts.get(r["state"], 0) + 1
    return counts


def cmd_check(root, args):
    _, _, results = run_check(root, args.cfg_path, args.lock_path, args.id)
    counts = summarize(results)
    verdict = "REVERTED" if counts.get("REVERTED") else "OK"
    doc = {"schemaVersion": SCHEMA, "action": "check", "verdict": verdict, "counts": counts,
           "results": [{k: v for k, v in r.items() if not k.startswith("_")} for r in results]}
    if args.json:
        print(json.dumps(doc, ensure_ascii=False))
    else:
        print(f"[behavior-ratchet] verdict={verdict} " + " ".join(f"{k}={v}" for k, v in sorted(counts.items())))
        for r in results:
            if r["state"] in ("REVERTED", "IN_FLIGHT", "LANDED") or args.verbose:
                print(f"  {r['state']:<9} {r['id']}  {json.dumps(r.get('detail'), ensure_ascii=False)}")
        if verdict == "REVERTED":
            print("  -> restore the locked behavior, or record a user-approved ADR and run `unlock`.")
    return EXIT_REVERTED if verdict == "REVERTED" else EXIT_OK


def cmd_update(root, args):
    _, lockdoc, results = run_check(root, args.cfg_path, args.lock_path)
    changed = []
    for r in results:
        if r["kind"] == "config":
            continue
        if r["leased"]:
            continue  # owning session still editing; lock after it releases
        cur = lockdoc["locks"].get(r["id"])
        if r["state"] == "LANDED" or (r["kind"] == "test_names" and r["state"] == "LOCKED"):
            rec = dict(cur or {})
            rec.setdefault("lockedAtUtc", utcnow())
            rec["kind"] = r["kind"]
            rec["files"] = r["files"]
            if args.task:
                rec.setdefault("lockedByTask", args.task)
            if r["kind"] == "test_names":
                grown = sorted(set(rec.get("names") or []) | set(r.get("_names") or []))
                if grown == sorted(rec.get("names") or []) and cur is not None:
                    continue
                rec["names"] = grown
            elif cur is not None:
                continue
            lockdoc["locks"][r["id"]] = rec
            changed.append(r["id"])
    skipped = [r["id"] for r in results if r["leased"] and r["state"] in ("LANDED", "PENDING", "IN_FLIGHT")]
    if changed and not args.dry_run:
        lockdoc.setdefault("history", []).append({"at": utcnow(), "action": "lock", "ids": changed,
                                                  "task": args.task})
        lockdoc["schemaVersion"] = SCHEMA
        save_json_atomic(args.lock_path, lockdoc)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "update", "dryRun": bool(args.dry_run),
                      "locked": changed, "skippedLeased": skipped}, ensure_ascii=False))
    return EXIT_OK


def adr_allows(path: Path, eid):
    text = read_text(path)
    if text is None or not text.startswith("---"):
        return False
    front = text.split("---", 2)[1] if text.count("---") >= 2 else ""
    fields = {}
    for line in front.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            fields[k.strip()] = v.strip().strip("'\"")
    return (fields.get("status") == "ACCEPTED" and fields.get("approvedBy") == "user"
            and eid in [s.strip() for s in fields.get("ratchet", "").split(",")])


def cmd_unlock(root, args):
    adr = (root / args.adr).resolve()
    try:
        adr.relative_to((root / ADR_DIR_REL).resolve())
    except ValueError:
        raise RatchetError("adr-outside-decisions-dir")
    if not adr_allows(adr, args.id):
        raise RatchetError("adr-not-accepted-by-user-for-" + args.id)
    lockdoc = load_json(args.lock_path, default={"schemaVersion": SCHEMA, "locks": {}, "history": []})
    if args.id not in lockdoc["locks"]:
        raise RatchetError("not-locked:" + args.id)
    del lockdoc["locks"][args.id]
    lockdoc.setdefault("history", []).append({"at": utcnow(), "action": "unlock", "ids": [args.id],
                                              "adr": adr.relative_to(root).as_posix()})
    save_json_atomic(args.lock_path, lockdoc)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "unlock", "id": args.id}))
    return EXIT_OK


def cmd_list(root, args):
    _, _, results = run_check(root, args.cfg_path, args.lock_path)
    for r in results:
        print(f"{r['state']:<9} {r['id']}  ({r.get('contract') or '-'})")
    return EXIT_OK


def build_parser():
    p = argparse.ArgumentParser(description="one-way behavior/rule ratchet")
    p.add_argument("--root", default=".")
    p.add_argument("--config", default=CONFIG_REL,
                   help="entries json, relative to --root unless absolute")
    p.add_argument("--lock", default=LOCK_REL,
                   help="lock json, relative to --root unless absolute")
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check"); c.add_argument("--json", action="store_true")
    c.add_argument("--id"); c.add_argument("--verbose", action="store_true"); c.set_defaults(func=cmd_check)
    u = sub.add_parser("update"); u.add_argument("--dry-run", action="store_true")
    u.add_argument("--task"); u.set_defaults(func=cmd_update)
    x = sub.add_parser("unlock"); x.add_argument("--id", required=True)
    x.add_argument("--adr", required=True); x.set_defaults(func=cmd_unlock)
    l = sub.add_parser("list"); l.set_defaults(func=cmd_list)
    return p


def main(argv=None):
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            try:
                s.reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError):
                pass
    args = build_parser().parse_args(argv)
    root = Path(args.root).resolve()
    args.cfg_path = Path(args.config)
    if not args.cfg_path.is_absolute():
        args.cfg_path = root / args.cfg_path
    args.lock_path = Path(args.lock)
    if not args.lock_path.is_absolute():
        args.lock_path = root / args.lock_path
    try:
        return args.func(root, args)
    except (RatchetError, re.error, KeyError) as err:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"{type(err).__name__}:{err}"}, ensure_ascii=False))
        return EXIT_ERR


if __name__ == "__main__":
    sys.exit(main())
