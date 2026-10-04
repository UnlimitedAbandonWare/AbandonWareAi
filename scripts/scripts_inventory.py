"""Inventory demo-1 script assets.

Scans eight extensions and writes var/diagnostics/scripts_manifest.json.
Stdlib only. No network. Stored paths are project-relative.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from script_mask import Parser, configure_stdio, mask_text, project_root_hardcode_lines

EXTENSIONS = (".bat", ".cmd", ".js", ".mjs", ".ps1", ".py", ".sh", ".sql")
SKIP_DIRS = {
    ".git", ".gradle", ".idea", ".venv", "__pycache__", "build", "dist",
    "node_modules", "target", "venv",
}
STALE_DAYS = 180
SCHEMA = "awx.scripts-manifest.v1"
DEFAULT_MANIFEST = Path("var/diagnostics/scripts_manifest.json")
NAME_RE = re.compile(r"[\w.-]+\.(?:py|ps1|bat|cmd|sh|mjs|js|sql)\b", re.IGNORECASE)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def iter_script_files(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [
            name for name in dirnames
            if name not in SKIP_DIRS and not name.startswith(".gradle")
        ]
        for name in filenames:
            if Path(name).suffix.lower() in EXTENSIONS:
                yield Path(dirpath) / name


def categorize(rel_posix: str) -> str:
    name = rel_posix.rsplit("/", 1)[-1].lower()
    suffix = Path(name).suffix.lower()
    folded = "/" + rel_posix.lower()
    if name == "gradlew.bat":
        return "UNCLASSIFIED"
    if suffix == ".sql":
        return "DB_MIGRATION"
    if suffix == ".mjs":
        return "SIDECAR_NODE"
    if suffix in {".bat", ".cmd"}:
        return "LAUNCHER_FACADE"
    if (name.startswith(("test_", "verify_", "check_"))
            or "/tests/" in folded or "/test/" in folded):
        return "TEST_VERIFY"
    if any(token in name for token in ("doctor", "diagnos", "preflight", "inventory", "debug_")):
        return "OPS_DIAGNOSTIC"
    if any(token in name for token in ("agent_", "codex_", "devin_", "grok", "agy")):
        return "AGENT_TOOL"
    if suffix == ".js" and (rel_posix.startswith("tools/") or "/sidecar" in folded):
        return "SIDECAR_NODE"
    return "UNCLASSIFIED"


def sha12_of(path: Path) -> str | None:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
    except OSError:
        return None
    return digest.hexdigest()[:12]


def filename_tokens(path: Path) -> set[str]:
    try:
        blob = path.read_bytes()[:262144]
    except OSError:
        return set()
    text = blob.decode("utf-8", "replace")
    return {match.group(0).lower() for match in NAME_RE.finditer(text)}


def collect(root: Path | None = None) -> dict:
    root = (root or repo_root()).resolve()
    now = datetime.now(timezone.utc)
    rows = []
    tokens: set[str] = set()
    for path in iter_script_files(root):
        rel = path.relative_to(root).as_posix()
        try:
            stat = path.stat()
            size = int(stat.st_size)
            age_days = (now - datetime.fromtimestamp(stat.st_mtime, timezone.utc)).days
        except OSError:
            size = 0
            age_days = 0
        tokens.update(filename_tokens(path))
        rows.append({
            "path": rel,
            "size": size,
            "sha12": sha12_of(path),
            "category": categorize(rel),
            "extension": Path(rel).suffix.lower(),
            "ageDays": age_days,
        })
    for row in rows:
        basename = row["path"].rsplit("/", 1)[-1].lower()
        at_root = "/" not in row["path"]
        row["stale"] = (not at_root) and row["ageDays"] >= STALE_DAYS and basename not in tokens
        del row["ageDays"]
    rows.sort(key=lambda row: row["path"].lower())
    by_extension = dict(sorted(Counter(row["extension"] for row in rows).items()))
    by_category = dict(sorted(Counter(row["category"] for row in rows).items()))
    return {
        "schemaVersion": SCHEMA,
        "generatedAt": now.isoformat(),
        "projectRoot": ".",
        "anchor": "scripts/scripts_inventory.py:__file__",
        "orphanPolicy": "STALE_FLAG_ONLY",
        "extensions": list(EXTENSIONS),
        "total": len(rows),
        "staleCount": sum(1 for row in rows if row["stale"]),
        "byExtension": by_extension,
        "byCategory": by_category,
        "files": rows,
    }


def resolve_under(root: Path, value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    return root / path


def display_path(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except (OSError, ValueError):
        return path.name


def write_manifest(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def print_summary(payload: dict) -> None:
    parts = ["%s=%s" % (key, value) for key, value in payload["byExtension"].items()]
    print("total=%s stale=%s %s" % (payload["total"], payload["staleCount"], " ".join(parts)))


def check_manifest(path: Path, fresh: dict) -> int:
    if not path.is_file():
        print("manifest-missing", file=sys.stderr)
        return 1
    try:
        saved = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        print("manifest-unreadable", file=sys.stderr)
        return 1
    if saved.get("schemaVersion") != SCHEMA:
        print("manifest-schema", file=sys.stderr)
        return 1
    if int(saved.get("total") or 0) < 1500:
        print("manifest-below-1500", file=sys.stderr)
        return 1
    saved_ext = saved.get("byExtension") or {}
    if set(saved_ext) != set(EXTENSIONS):
        print("manifest-extensions", file=sys.stderr)
        return 1
    if int(saved.get("total") or -1) != fresh["total"] or saved_ext != fresh["byExtension"]:
        print("manifest-drift", file=sys.stderr)
        return 1
    print("manifest-ok total=%s" % saved["total"])
    return 0


def hardcoded_project_roots(root: Path) -> list[str]:
    hits = []
    for pattern in ("*.bat", "*.cmd"):
        for path in sorted(root.glob(pattern)):
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for line_no in project_root_hardcode_lines(text):
                hits.append("%s:%s" % (path.name, line_no))
    return hits


def build_parser() -> Parser:
    parser = Parser(description="Inventory script assets under the project root.")
    parser.add_argument("--json", metavar="PATH",
                        help="write the manifest (path relative to the project root unless absolute)")
    parser.add_argument("--manifest", metavar="PATH",
                        help="manifest path for --check; default var/diagnostics/scripts_manifest.json")
    parser.add_argument("--check", action="store_true",
                        help="exit 1 when the manifest is missing, short, or drifted")
    parser.add_argument("--check-hardcoded-paths", action="store_true",
                        help="exit 1 when a root .bat or .cmd pins C:\\AbandonWare")
    parser.add_argument("--root", default=None,
                        help="project root override for tests; default is the parent of this script")
    return parser


def main(argv: list[str] | None = None) -> int:
    configure_stdio()
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(args.root).resolve() if args.root else repo_root()
    try:
        if args.check_hardcoded_paths:
            hits = hardcoded_project_roots(root)
            print("hardcoded-project-root count=%s" % len(hits))
            for hit in hits:
                print(hit)
            if hits:
                return 1
            if not args.json and not args.check:
                return 0
        manifest_arg = args.json or args.manifest
        manifest_path = resolve_under(root, manifest_arg) if manifest_arg else (root / DEFAULT_MANIFEST)
        if args.check and not args.json and not manifest_path.is_file():
            print("manifest-missing", file=sys.stderr)
            return 1
        payload = collect(root)
        if args.json:
            write_manifest(manifest_path, payload)
            print("wrote %s total=%s" % (display_path(root, manifest_path), payload["total"]))
        if args.check:
            return check_manifest(manifest_path, payload)
        if not args.json:
            print_summary(payload)
        return 0
    except OSError as exc:
        print(mask_text(str(exc)), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
