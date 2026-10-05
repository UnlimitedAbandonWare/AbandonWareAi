#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""90-day TTL janitor for docs/provider-limits SSOT pages.

Each provider doc carries a ```yaml metadata fence with capturedAt, ttlDays,
expiresAt, status, expiryAction. This tool detects expired docs, reports
per-doc freshness, and archives expired pages into docs/provider-limits/archive/
with a watermark — the (a) archive-isolation default from
PASTE_DEVIN_PROVIDER_LIMITS_90D_TTL_20261005.

Commands:
  check    exit 1 if any doc is EXPIRED, else 0 (NO_TTL docs only warn)
  status   print per-doc capturedAt/expiresAt/daysLeft/state table
  archive  move EXPIRED docs to archive/ and prepend an expiry watermark
           (--dry-run lists planned moves without touching files)

Docs are read-only SSOT; expiry here is a *documentation* contract and never
touches runtime admission, GroqFreeTierGuard evidence TTL, or API keys.

Exit codes: 0 ok, 1 expired-found (check) / nothing-or-error per command notes,
2 = usage or I/O error.
"""
import argparse
import datetime as _dt
import json
import os
import re
import sys

SCHEMA = "awx.provider-limits-janitor.v1"
DEFAULT_DIR = "docs/provider-limits"
ARCHIVE_DIRNAME = "archive"
SEOUL = _dt.timezone(_dt.timedelta(hours=9), name="Asia/Seoul")

_YAML_FENCE = re.compile(r"```yaml\s*\n(.*?)```", re.DOTALL)
_KV = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.*)$")
_WATERMARK_RE = re.compile(r"<!--\s*ttl-expired\b")


def _today(today=None):
    if today is not None:
        return today
    return _dt.datetime.now(SEOUL).date()


def _parse_date(text):
    try:
        return _dt.date.fromisoformat(text.strip().strip('"').strip("'"))
    except (ValueError, AttributeError):
        return None


def parse_meta(path):
    """Extract the first ```yaml fence as a flat key->str map (stdlib subset)."""
    try:
        with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return None
    m = _YAML_FENCE.search(text)
    if not m:
        return {}
    meta = {}
    for line in m.group(1).splitlines():
        kv = _KV.match(line)
        if not kv:
            continue
        key, val = kv.group(1), kv.group(2).strip()
        if val.startswith(">"):  # folded block scalars — skip the value
            continue
        meta[key] = val.strip().strip('"').strip("'")
    return meta


def classify(path, rel, today):
    meta = parse_meta(path)
    if meta is None:
        return {"file": rel, "state": "UNREADABLE", "daysLeft": None,
                "capturedAt": None, "expiresAt": None, "expiryBasis": "none"}
    cap = _parse_date(meta.get("capturedAt", "") or "")
    exp = _parse_date(meta.get("expiresAt", "") or "")
    basis = "explicit"
    if exp is None and cap is not None:
        days = meta.get("ttlDays") or meta.get("reviewAfterDays")
        try:
            days = int(str(days).strip())
        except (TypeError, ValueError):
            days = None
        if days:
            exp = cap + _dt.timedelta(days=days)
            basis = "derived"
    if exp is None:
        return {"file": rel, "state": "NO_TTL", "daysLeft": None,
                "capturedAt": cap.isoformat() if cap else None,
                "expiresAt": None, "expiryBasis": "none"}
    days_left = (exp - today).days
    state = "EXPIRED" if days_left < 0 else "ACTIVE"
    return {"file": rel, "state": state, "daysLeft": days_left,
            "capturedAt": cap.isoformat() if cap else None,
            "expiresAt": exp.isoformat(), "expiryBasis": basis,
            "docStatus": meta.get("status", "")}


def scan(root, doc_dir=DEFAULT_DIR, today=None):
    base = os.path.join(root, doc_dir)
    out = []
    if not os.path.isdir(base):
        return out
    for name in sorted(os.listdir(base)):
        if not name.lower().endswith(".md"):
            continue
        full = os.path.join(base, name)
        if not os.path.isfile(full):
            continue
        rel = "%s/%s" % (doc_dir, name)
        out.append(classify(full, rel, today or _today()))
    return out


def _watermark(entry, today):
    return ('<!-- ttl-expired archivedAt="%s" expiresAt="%s" '
            'janitor="provider_limits_janitor.py" -->\n\n'
            % (today.isoformat(), entry.get("expiresAt") or "unknown"))


def archive_one(root, entry, today, dry_run=False):
    src = os.path.join(root, entry["file"])
    dst_dir = os.path.join(root, DEFAULT_DIR, ARCHIVE_DIRNAME)
    dst = os.path.join(dst_dir, os.path.basename(entry["file"]))
    if dry_run:
        return {"file": entry["file"], "to": "%s/%s/%s" % (DEFAULT_DIR,
                ARCHIVE_DIRNAME, os.path.basename(entry["file"])),
                "moved": False, "dryRun": True}
    with open(src, "r", encoding="utf-8-sig", errors="replace") as fh:
        body = fh.read()
    if not _WATERMARK_RE.search(body):
        body = _watermark(entry, today) + body
    if 'status: "ACTIVE"' in body:
        body = body.replace('status: "ACTIVE"', 'status: "ARCHIVED"', 1)
    os.makedirs(dst_dir, exist_ok=True)
    if os.path.exists(dst):
        return {"file": entry["file"], "to": None, "moved": False,
                "error": "archive-target-exists"}
    with open(dst, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)
    os.remove(src)
    return {"file": entry["file"],
            "to": "%s/%s/%s" % (DEFAULT_DIR, ARCHIVE_DIRNAME,
                                os.path.basename(entry["file"])),
            "moved": True}


def main(argv=None):
    ap = argparse.ArgumentParser(description="provider-limits 90-day TTL "
                                 "janitor (check/status/archive).")
    ap.add_argument("command", choices=["check", "status", "archive"])
    ap.add_argument("--root", default=".")
    ap.add_argument("--dir", default=DEFAULT_DIR,
                    help="doc dir relative to root (default %(default)s)")
    ap.add_argument("--today", default=None,
                    help="override today (YYYY-MM-DD, Asia/Seoul default)")
    ap.add_argument("--dry-run", action="store_true",
                    help="archive: print planned moves only")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)

    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except Exception:
        pass

    root = args.root
    if not os.path.isdir(root):
        print("ERROR root-not-a-directory:%s" % root)
        return 2
    today = _parse_date(args.today) if args.today else _today()
    if today is None:
        print("ERROR bad --today:%s" % args.today)
        return 2

    entries = scan(root, args.dir, today)
    if not os.path.isdir(os.path.join(root, args.dir)):
        print("ERROR doc-dir-missing:%s" % args.dir)
        return 2

    expired = [e for e in entries if e["state"] == "EXPIRED"]
    no_ttl = [e for e in entries if e["state"] == "NO_TTL"]

    if args.command == "check":
        if args.as_json:
            print(json.dumps({"schemaVersion": SCHEMA, "today":
                              today.isoformat(), "docsScanned": len(entries),
                              "expired": expired, "noTtl": [e["file"] for e in
                              no_ttl]}, ensure_ascii=False, indent=1))
        else:
            for e in expired:
                print("EXPIRED %s (expiresAt=%s, %+d days)"
                      % (e["file"], e["expiresAt"], e["daysLeft"]))
            for e in no_ttl:
                print("NO_TTL %s (no expiresAt; ttl unmonitored)" % e["file"])
            print("SUMMARY docs=%d expired=%d no_ttl=%d"
                  % (len(entries), len(expired), len(no_ttl)))
        return 1 if expired else 0

    if args.command == "status":
        if args.as_json:
            print(json.dumps({"schemaVersion": SCHEMA, "today":
                              today.isoformat(), "docs": entries},
                             ensure_ascii=False, indent=1))
        else:
            print("%-42s %-11s %-11s %7s %s"
                  % ("doc", "capturedAt", "expiresAt", "days", "state"))
            for e in entries:
                print("%-42s %-11s %-11s %7s %s"
                      % (e["file"], e["capturedAt"] or "-",
                         e["expiresAt"] or "-",
                         "-" if e["daysLeft"] is None else e["daysLeft"],
                         e["state"]))
            print("SUMMARY docs=%d expired=%d active=%d no_ttl=%d"
                  % (len(entries), len(expired),
                     sum(1 for e in entries if e["state"] == "ACTIVE"),
                     len(no_ttl)))
        return 0

    # archive
    results = [archive_one(root, e, today, dry_run=args.dry_run)
               for e in expired]
    if args.as_json:
        print(json.dumps({"schemaVersion": SCHEMA, "today": today.isoformat(),
                          "dryRun": args.dry_run, "moved": results},
                         ensure_ascii=False, indent=1))
    else:
        for r in results:
            mark = "ARCHIVED" if r.get("moved") else (
                "WOULD-ARCHIVE" if r.get("dryRun") else "SKIP")
            print("%s %s -> %s %s" % (mark, r["file"], r.get("to"),
                                      r.get("error") or ""))
        print("SUMMARY expired=%d moved=%d dry_run=%s"
              % (len(expired), sum(1 for r in results if r.get("moved")),
                 args.dry_run))
    return 0


if __name__ == "__main__":
    sys.exit(main())
