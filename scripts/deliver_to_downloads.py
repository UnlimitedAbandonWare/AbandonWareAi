#!/usr/bin/env python3
"""deliver_to_downloads.py — copy agent deliverables into the user's Downloads.

Contract DEMO1-DELIVERY-DOWNLOADS: a directive/report/brief an agent writes for
the human is not finished until it also exists in user.downloads and the reply
carries the tool's DELIVERED line. Three layers: rule doc, this tool, hook.

  --file <path> [...]   copy the named files
  --scan [--since-minutes N] [--roots ...]   copy matching recent outputs that
                        are not already present with the same sha256
  --quiet               print only when something was copied
  --downloads <dir>     destination override (tests); default = registry
                        user.downloads, fallback %USERPROFILE%\\Downloads
  --log <path>          jsonl log override; default <repo>/var/deliver/deliver-log.jsonl
  --deadline-sec <f>    scan wall-clock budget (default 2.7); on exceed the scan
                        truncates early and still exits 0

Always exits 0 — a failure prints one WARN line, never blocks a Codex turn.
Never deletes/moves sources. Writes only inside Downloads plus the log file.
"""

import argparse
import glob
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAX_SIZE = 1024 * 1024
BLOCKED_NAME_PARTS = (".env", "secret", "token", "key")
DEFAULT_SINCE_MINUTES = 240
MAX_VARIANT = 9
SCAN_BATCH = 32


def warn(msg):
    print(f"WARN deliver_to_downloads: {msg}", file=sys.stderr)


def resolve_downloads(override):
    if override:
        return Path(override)
    env_dir = os.environ.get("AWX_DOWNLOADS_DIR")
    if env_dir:
        return Path(env_dir)
    try:
        sys.path.insert(0, str(ROOT / "scripts"))
        from awx_paths import resolve
        return Path(resolve("user.downloads"))
    except Exception:
        return Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Downloads"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 256), b""):
            h.update(chunk)
    return h.hexdigest()


def is_binary(path):
    try:
        with open(path, "rb") as fh:
            return b"\x00" in fh.read(8192)
    except OSError:
        return True


def name_blocked(name):
    low = name.lower()
    return any(part in low for part in BLOCKED_NAME_PARTS)


def matches_scan_name(name):
    # PASTE_*.txt | *directive*.md | *지시서* | *_report_*.md | *brief*.md
    low = name.lower()
    if "지시서" in name:
        return True
    if low.endswith(".txt"):
        return low.startswith("paste_")
    if low.endswith(".md"):
        return ("directive" in low) or ("_report_" in low) or ("brief" in low)
    return False


def classify(path):
    """None = deliverable, else refusal reason."""
    try:
        size = path.stat().st_size
    except OSError:
        return "missing"
    if size > MAX_SIZE:
        return "oversize>1MB"
    if name_blocked(path.name):
        return "blocked-name"
    if is_binary(path):
        return "binary"
    return None


def variant_path(downloads, name, n):
    p = Path(name)
    if n <= 1:
        return downloads / name
    return downloads / f"{p.stem}_v{n}{p.suffix}"


def deliver_one(src, downloads):
    """Copy src into downloads. Returns (action, dest_path, sha12, size)."""
    src = Path(src)
    reason = classify(src)
    if reason:
        return (f"REFUSED:{reason}", None, None, 0)
    sha = sha256_of(src)
    sha12, size = sha[:12], src.stat().st_size
    free_slot = None
    for n in range(1, MAX_VARIANT + 1):
        dest = variant_path(downloads, src.name, n)
        if not dest.exists():
            free_slot = dest
            break
        if dest.is_file() and sha256_of(dest) == sha:
            return ("SKIP_SAME", dest, sha12, size)
    if free_slot is None:
        return ("REFUSED:no-free-variant", None, sha12, size)
    downloads.mkdir(parents=True, exist_ok=True)
    data = src.read_bytes()
    free_slot.write_bytes(data)
    if sha256_of(free_slot) != sha:
        return ("ERROR:sha-mismatch", free_slot, sha12, size)
    return ("DELIVERED", free_slot, sha12, size)


def default_roots():
    home = Path(os.environ.get("USERPROFILE", str(Path.home())))
    return [
        str(home / "Documents" / "Codex" / "**" / "task"),
        str(ROOT / "var" / "codex-assist-*"),
        str(ROOT / "agent-prompts" / "**"),
    ]


def iter_root_files(root_spec, warnings):
    """Yield candidate files under a root spec (dir path or glob)."""
    spec = root_spec.rstrip("/\\")
    if spec.endswith("**"):
        base = Path(spec[:-2].rstrip("/\\"))
        if base.is_dir():
            for dirpath, _dirs, files in os.walk(base, onerror=lambda e: warnings.append(str(e))):
                for f in files:
                    yield Path(dirpath) / f
        return
    matched = glob.glob(root_spec, recursive=True)
    if not matched:
        if Path(root_spec).is_dir():
            matched = [root_spec]
        else:
            warnings.append(f"root unmatched: {root_spec}")
    for m in matched:
        p = Path(m)
        if p.is_dir():
            for dirpath, _dirs, files in os.walk(p, onerror=lambda e: warnings.append(str(e))):
                for f in files:
                    yield Path(dirpath) / f
        elif p.is_file():
            yield p


def scan(roots, since_minutes, downloads, deadline):
    warnings, delivered, skipped, refused, seen = [], [], [], [], set()
    cutoff = time.time() - since_minutes * 60
    truncated = False
    count = 0
    for spec in roots:
        try:
            candidates = iter_root_files(spec, warnings)
            for path in candidates:
                if truncated:
                    break
                count += 1
                if count % SCAN_BATCH == 0 and time.monotonic() > deadline:
                    truncated = True
                    break
                try:
                    rp = str(path.resolve())
                except OSError:
                    continue
                if rp in seen:
                    continue
                seen.add(rp)
                try:
                    if path.stat().st_mtime < cutoff:
                        continue
                except OSError:
                    continue
                if not matches_scan_name(path.name):
                    continue
                action, dest, sha12, size = deliver_one(path, downloads)
                entry = (str(path), action, str(dest) if dest else "", sha12, size)
                if action == "DELIVERED":
                    delivered.append(entry)
                elif action == "SKIP_SAME":
                    skipped.append(entry)
                else:
                    refused.append(entry)
        except Exception as e:  # noqa: BLE001 - one bad root must not kill the scan
            warnings.append(f"{spec}: {e}")
    return {
        "delivered": delivered, "skipped": skipped, "refused": refused,
        "warnings": warnings, "truncated": truncated, "scanned": count,
    }


def append_log(log_path, record):
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError:
        pass


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", action="append", default=[])
    ap.add_argument("--scan", action="store_true")
    ap.add_argument("--since-minutes", type=int, default=DEFAULT_SINCE_MINUTES)
    ap.add_argument("--roots", nargs="*", default=None)
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--downloads", default=None)
    ap.add_argument("--log", default=None)
    ap.add_argument("--deadline-sec", type=float, default=2.7)
    args = ap.parse_args(argv)

    try:
        downloads = resolve_downloads(args.downloads)
        log_path = Path(args.log) if args.log else ROOT / "var" / "deliver" / "deliver-log.jsonl"
        record = {"ts": datetime.now(timezone.utc).isoformat(), "downloads": str(downloads)}
        out_lines = []

        if args.file:
            record["mode"] = "file"
            results = []
            for f in args.file:
                action, dest, sha12, size = deliver_one(f, downloads)
                results.append({"src": f, "action": action,
                                "dst": str(dest) if dest else None, "sha12": sha12, "size": size})
                if action == "DELIVERED":
                    out_lines.append(f"DELIVERED {dest} {size}B sha12={sha12} MATCH")
                elif action == "SKIP_SAME":
                    out_lines.append(f"SKIP_SAME {dest} {size}B sha12={sha12}")
                else:
                    out_lines.append(f"{action.split(':')[0]} {f} {action.split(':', 1)[-1]}")
            record["results"] = results
        elif args.scan:
            record["mode"] = "scan"
            roots = args.roots if args.roots else default_roots()
            deadline = time.monotonic() + args.deadline_sec
            res = scan(roots, args.since_minutes, downloads, deadline)
            record.update({"roots": roots, "since_minutes": args.since_minutes,
                           "scanned": res["scanned"], "truncated": res["truncated"],
                           "delivered": len(res["delivered"]), "skipped": len(res["skipped"]),
                           "refused": len(res["refused"]),
                           "warnings": res["warnings"][:20]})
            for src, _a, dest, sha12, size in res["delivered"]:
                out_lines.append(f"DELIVERED {dest} {size}B sha12={sha12} MATCH")
            if not args.quiet:
                for src, _a, dest, sha12, size in res["skipped"]:
                    out_lines.append(f"SKIP_SAME {dest} {size}B sha12={sha12}")
                for src, action, _d, _s, _z in res["refused"]:
                    out_lines.append(f"{action.split(':')[0]} {src} {action.split(':', 1)[-1]}")
                for w in res["warnings"][:10]:
                    warn(w)
                if res["truncated"]:
                    warn("scan truncated at deadline; rerun with a smaller --since-minutes")
        else:
            ap.print_help()
            return 0

        append_log(log_path, record)
        if not args.quiet or out_lines:
            for line in out_lines:
                print(line)
        return 0
    except Exception as e:  # noqa: BLE001 - hook safety: never block a turn
        warn(str(e))
        return 0


if __name__ == "__main__":
    sys.exit(main())
