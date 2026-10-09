#!/usr/bin/env python3
"""deliver_to_downloads.py — copy agent deliverables into the user's Downloads.

Contract DEMO1-DELIVERY-DOWNLOADS: only an explicitly declared dot final
directive is delivered here. Reviews, drafts, logs and intermediate artifacts
stay in the project. Model names and filenames do not grant delivery authority.

  --file <path> [...] --role dot --artifact-kind final-directive
                         copy the explicitly selected final directives
  --scan                 compatibility no-copy entry; automatic scanning disabled
  --quiet               print only when something was copied
  --hook-stop            compatibility no-copy entry; never reads a transcript.
                         stdout remains a single `{}` JSON line.
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
import re
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


def matches_dot_name(name):
    """dot brief session matcher: scan rules plus PASTE_*.md (dot's own format)."""
    low = name.lower()
    return matches_scan_name(name) or (low.startswith("paste_") and low.endswith(".md"))


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


def deliver_one(src, downloads, *, role=None, artifact_kind=None):
    """Copy src into downloads. Returns (action, dest_path, sha12, size)."""
    if role != "dot" or artifact_kind != "final-directive":
        return ("REFUSED:dot-final-directive-required", None, None, 0)
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


def scan(roots, since_minutes, downloads, deadline, name_fn=matches_scan_name):
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
                if not name_fn(path.name):
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


# ---------- --hook-stop (dot-session-scoped Stop hook) ----------

DOT_TAG = "[DOT-BRIEF]"
TRANSCRIPT_MAX_BYTES = 2 * 1024 * 1024
TRANSCRIPT_MAX_LINES = 400
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,128}$")
HOOK_STOP_MAX_MINUTES = 2880  # 48h cap on the session-start-derived window


def _parse_iso_epoch(text):
    if not isinstance(text, str) or not text.strip():
        return None
    try:
        from datetime import timezone as _tz
        dt = datetime.fromisoformat(text.strip().replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=_tz.utc)
        return dt.timestamp()
    except ValueError:
        return None


def read_first_user_message(path):
    """Text of the first user-role message in a codex rollout jsonl, else None."""
    try:
        with open(path, "rb") as fh:
            data = fh.read(TRANSCRIPT_MAX_BYTES + 1)
    except OSError:
        return None
    for raw in data.splitlines()[:TRANSCRIPT_MAX_LINES]:
        line = raw.strip()
        if not line:
            continue
        try:
            rec = json.loads(line.decode("utf-8-sig", "replace"))
        except ValueError:
            continue
        if not isinstance(rec, dict):
            continue
        if rec.get("type") not in ("response_item", "user_message"):
            continue
        pl = rec.get("payload") if isinstance(rec.get("payload"), dict) else rec
        if pl.get("role") != "user":
            continue
        content = pl.get("content")
        parts = []
        if isinstance(content, list):
            for item in content:
                if isinstance(item, dict) and isinstance(item.get("text"), str):
                    parts.append(item["text"])
        elif isinstance(content, str):
            parts.append(content)
        elif isinstance(pl.get("message"), str):
            parts.append(pl["message"])
        return "\n".join(parts)
    return None


def session_start_epoch(path):
    """First record timestamp (session_meta/turn start), else file mtime."""
    try:
        with open(path, "rb") as fh:
            for _ in range(20):
                line = fh.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except ValueError:
                    continue
                ts = _parse_iso_epoch(rec.get("timestamp")) or \
                    _parse_iso_epoch((rec.get("payload") or {}).get("timestamp")) or \
                    _parse_iso_epoch((rec.get("payload") or {}).get("started_at"))
                if ts:
                    return ts
    except OSError:
        pass
    try:
        return path.stat().st_mtime
    except OSError:
        return None


def find_transcript_by_session(session_id):
    """Locate a rollout jsonl by session id under ~/.codex/sessions."""
    if not session_id or not SESSION_ID_RE.match(str(session_id)):
        return None
    home = Path(os.environ.get("USERPROFILE", str(Path.home())))
    base = home / ".codex" / "sessions"
    if not base.is_dir():
        return None
    needle = str(session_id).lower()
    try:
        cands = [p for p in base.glob("**/rollout-*.jsonl")
                 if needle in p.name.lower() and p.is_file()]
    except OSError:
        return None
    if not cands:
        return None
    cands.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return str(cands[0])


def hook_stop_roots(event):
    home = Path(os.environ.get("USERPROFILE", str(Path.home())))
    roots = [
        str(home / "Documents" / "Codex" / "**" / "task*"),
        str(ROOT / "var" / "codex-assist-*"),
        str(ROOT / "agent-prompts" / "**"),
    ]
    cwd = event.get("cwd")
    if isinstance(cwd, str) and cwd and Path(cwd).is_dir():
        rp = str(Path(cwd).resolve())
        if rp not in roots:
            roots.append(rp)
    return roots


def run_hook_stop(args, downloads, record):
    """Compatibility no-copy entry; never inspects a transcript or session."""
    return [], {"dot": False, "reason": "automatic-delivery-disabled", "delivered": 0}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", action="append", default=[])
    ap.add_argument("--role", help="Explicit sender role; only dot is eligible")
    ap.add_argument("--artifact-kind", help="Only final-directive is eligible")
    ap.add_argument("--scan", action="store_true")
    ap.add_argument("--hook-stop", action="store_true",
                    help="Codex Stop hook: deliver only for [DOT-BRIEF] first-"
                         "user-message sessions; stdout is a single {} line")
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
        if (not log_path.resolve().is_relative_to(ROOT.resolve())
                or log_path.resolve().is_relative_to(downloads.resolve())):
            print("{}" if args.hook_stop else "REFUSED project-log-required")
            return 0
        record = {"ts": datetime.now(timezone.utc).isoformat(), "downloads": str(downloads)}
        out_lines = []

        if args.hook_stop or args.scan:
            record.update({"mode": "hook-stop" if args.hook_stop else "scan",
                           "dot": False, "reason": "automatic-delivery-disabled", "delivered": 0})
            append_log(log_path, record)
            print("{}" if args.hook_stop else "REFUSED automatic-delivery-disabled")
            return 0

        if args.file:
            record["mode"] = "file"
            results = []
            for f in args.file:
                action, dest, sha12, size = deliver_one(
                    f, downloads, role=args.role, artifact_kind=args.artifact_kind)
                results.append({"src": f, "action": action,
                                "dst": str(dest) if dest else None, "sha12": sha12, "size": size})
                if action == "DELIVERED":
                    out_lines.append(f"DELIVERED {dest} {size}B sha12={sha12} MATCH")
                elif action == "SKIP_SAME":
                    out_lines.append(f"SKIP_SAME {dest} {size}B sha12={sha12}")
                else:
                    out_lines.append(f"{action.split(':')[0]} {f} {action.split(':', 1)[-1]}")
            record["results"] = results
        else:
            ap.print_help()
            return 0

        append_log(log_path, record)
        if args.hook_stop:
            # Codex Stop hooks parse stdout as JSON; plain text is invalid.
            print("{}")
        elif not args.quiet or out_lines:
            for line in out_lines:
                print(line)
        return 0
    except Exception as e:  # noqa: BLE001 - hook safety: never block a turn
        warn(str(e))
        if getattr(args, "hook_stop", False):
            print("{}")
        return 0


if __name__ == "__main__":
    sys.exit(main())
