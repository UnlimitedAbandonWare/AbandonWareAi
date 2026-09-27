#!/usr/bin/env python3
"""patchdrop_bundle_guard.py — pre-apply integrity gate for PatchDrop
producer bundles (0913k0/0916c0 lineage, directive P3).

Bundle layout (patchdrop-producer-v3):
  __patch_drop__/<node>/<slug>.manifest.json | .patch | .report.md |
                       .verify.log | .sha256.txt   (sha256sum format)
  pending marker: <slug>.<node>-pending.md in the node parent listing

Actions:
  check --bundle <path-to-.manifest.json>
        [--patchdrop-root DIR] [--policy policy.json]
        --inventory-file <janitor_inventory.ps1 captured output>
        [--apply-moves]
      Gate order: inventory receipt -> schema v3 -> parts completeness ->
      sha256 integrity -> duplicate slug -> whitelist/thresholds.
      Verdicts: apply-ok | hold:<reason> | reject:<reason>.
      reject:* + --apply-moves moves the slug fileset to <root>/rejected/;
      without it, only `wouldMove` is reported. Nothing is moved silently.
  queue [--patchdrop-root DIR]
      Classify patch-drop-pending/ + pending/ queue entries:
      complete-parts | missing-parts | unknown -> recommended action.

Policy file: desktop_patchdrop_auto_intake.policy.sample.json shape
(allowedPathPrefixes, maxPatchBytes, maxChangedFiles, maxHunks,
allowedTopics, allowedNodes).

Exit codes: 0 apply-ok/queue done, 4 hold:*, 5 reject:*, 2 usage/io.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.patchdrop-bundle-guard.v1"
BUNDLE_SCHEMA = "patchdrop-producer-v3"
PARTS = (".patch", ".report.md", ".verify.log", ".sha256.txt")
ROOT = Path(__file__).resolve().parents[1]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slug_of(manifest_path: Path) -> str:
    name = manifest_path.name
    return name[:-len(".manifest.json")] if name.endswith(".manifest.json") \
        else manifest_path.stem


def _parse_sha_list(path: Path) -> list[tuple[str, str]]:
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+)$", line.strip())
        if match:
            rows.append((match.group(1).lower(), match.group(2).strip()))
    return rows


def _verify_hashes(bundle_dir: Path, sha_file: Path) -> list[str]:
    bad = []
    for expect, name in _parse_sha_list(sha_file):
        target = (bundle_dir / name).resolve()
        if not target.is_file():
            bad.append(f"missing:{name}")
            continue
        if sha256_file(target) != expect:
            bad.append(f"mismatch:{name}")
    return bad


def _inventory_status(inv_text: str, slug: str) -> str | None:
    for line in inv_text.splitlines():
        match = re.search(r"topic=(\S+)\s+status=(\S+)", line)
        if match and match.group(1) == slug:
            return match.group(2)
    return None


def _patch_paths(patch_path: Path) -> list[str]:
    paths = []
    for line in patch_path.read_text(encoding="utf-8", errors="replace") \
            .splitlines():
        match = re.match(r"^\+\+\+ b/(.+)$", line)
        if match:
            paths.append(match.group(1).strip())
    return paths


def _hunks(patch_path: Path) -> int:
    return sum(1 for line in
               patch_path.read_text(encoding="utf-8", errors="replace")
               .splitlines() if line.startswith("@@"))


def _load_policy(path: str | None) -> dict:
    if not path:
        return {}
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    return data if isinstance(data, dict) else {}


def cmd_check(args) -> tuple[dict, int]:
    manifest_path = Path(args.bundle)
    patchdrop = Path(args.patchdrop_root)
    policy = _load_policy(args.policy)
    checks = {}
    if not manifest_path.is_file():
        return {"schemaVersion": SCHEMA, "verdict": "reject:missing-manifest",
                    "bundle": str(manifest_path)}, 5
    slug = slug_of(manifest_path)
    node_dir = manifest_path.parent

    # 1. inventory receipt gate — apply is impossible without it
    if not args.inventory_file or not Path(args.inventory_file).is_file():
        checks["inventory"] = "absent"
        return {"schemaVersion": SCHEMA, "verdict": "hold:inventory-required",
                "slug": slug, "checks": checks,
                "why": "janitor_inventory.ps1 output required before apply"}, 4
    inv_status = _inventory_status(
        Path(args.inventory_file).read_text(encoding="utf-8",
                                            errors="replace"), slug)
    checks["inventory"] = inv_status or "not-listed"
    if inv_status != "READY":
        return {"schemaVersion": SCHEMA, "verdict": "hold:inventory-not-ready",
                "slug": slug, "checks": checks}, 4

    # 2. schema — v3 only
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    except ValueError:
        return {"schemaVersion": SCHEMA, "verdict": "reject:bad-manifest",
                "slug": slug, "checks": checks}, 5
    checks["schema"] = manifest.get("schemaVersion")
    if manifest.get("schemaVersion") != BUNDLE_SCHEMA:
        return {"schemaVersion": SCHEMA,
                "verdict": "reject:schema-not-v3", "slug": slug,
                "checks": checks}, 5

    # 3. parts completeness
    missing = [ext for ext in PARTS if not (node_dir / (slug + ext)).is_file()]
    checks["missingParts"] = missing
    if not (node_dir / (slug + ".patch")).is_file():
        return {"schemaVersion": SCHEMA, "verdict": "reject:missing-patch",
                "slug": slug, "checks": checks}, 5
    if missing:
        return {"schemaVersion": SCHEMA, "verdict": "reject:missing-meta",
                "slug": slug, "checks": checks}, 5

    # 4. sha256 integrity
    bad = _verify_hashes(node_dir, node_dir / (slug + ".sha256.txt"))
    checks["hashMismatches"] = bad
    if bad:
        return {"schemaVersion": SCHEMA, "verdict": "reject:integrity",
                "slug": slug, "checks": checks}, 5

    # 5. duplicate slug across nodes (single v3 bundle per slug)
    dupes = [str(p.relative_to(patchdrop)) for p in
             patchdrop.rglob(slug + ".manifest.json")
             if p.resolve() != manifest_path.resolve()] \
        if patchdrop.is_dir() else []
    checks["duplicateSlugs"] = dupes
    if dupes:
        return {"schemaVersion": SCHEMA, "verdict": "hold:duplicate-slug",
                "slug": slug, "checks": checks}, 4

    if manifest.get("emptyPatch"):
        return {"schemaVersion": SCHEMA, "verdict": "reject:empty-patch",
                "slug": slug, "checks": checks}, 5

    # 6. whitelist + thresholds
    patch_path = node_dir / (slug + ".patch")
    paths = [f.get("path") for f in manifest.get("files") or []
             if isinstance(f, dict) and f.get("path")] or _patch_paths(patch_path)
    checks["targetPaths"] = paths
    prefixes = [p.replace("\\", "/").rstrip("/") + "/" for p in
                policy.get("allowedPathPrefixes") or []]
    if prefixes:
        outside = [p for p in paths if not any(
            str(p).replace("\\", "/").startswith(pre) for pre in prefixes)]
        checks["outsideWhitelist"] = outside
        if outside:
            return {"schemaVersion": SCHEMA,
                    "verdict": "reject:whitelist", "slug": slug,
                    "checks": checks}, 5
    topics = policy.get("allowedTopics") or []
    nodes = policy.get("allowedNodes") or []
    if topics and slug not in topics:
        return {"schemaVersion": SCHEMA, "verdict": "hold:topic-not-allowed",
                "slug": slug, "checks": checks}, 4
    if nodes and node_dir.name not in nodes:
        return {"schemaVersion": SCHEMA, "verdict": "hold:node-not-allowed",
                "slug": slug, "checks": checks}, 4
    def _limit(name, default):
        value = policy.get(name)
        return default if value is None else int(value)

    over = []
    if patch_path.stat().st_size > _limit("maxPatchBytes", 1 << 62):
        over.append("maxPatchBytes")
    if len(paths) > _limit("maxChangedFiles", 1 << 30):
        over.append("maxChangedFiles")
    if _hunks(patch_path) > _limit("maxHunks", 1 << 30):
        over.append("maxHunks")
    checks["thresholdsExceeded"] = over
    if over:
        return {"schemaVersion": SCHEMA,
                "verdict": "hold:threshold-exceeded", "slug": slug,
                "checks": checks}, 4

    return {"schemaVersion": SCHEMA, "verdict": "apply-ok", "slug": slug,
            "checks": checks,
            "contract": "verify-then-move applied/ or rejected/ stays with "
                        "the caller"}, 0


def cmd_queue(args) -> tuple[dict, int]:
    patchdrop = Path(args.patchdrop_root)
    entries = []
    for name in ("patch-drop-pending", "pending"):
        folder = patchdrop / name
        if not folder.is_dir():
            continue
        for item in sorted(folder.iterdir()):
            if not item.is_file():
                continue
            suffix = item.suffix.lower()
            kind = {".patch": "patch", ".json": "manifest",
                    ".md": "doc", ".log": "log", ".txt": "hash"}.get(
                        suffix, "unknown")
            entries.append({"queue": name, "file": item.name, "kind": kind,
                            "action": "intake-check" if kind == "patch"
                            else "review-manually"})
    return {"schemaVersion": SCHEMA, "queueCount": len(entries),
            "entries": entries}, 0


def apply_moves(manifest_path: Path, patchdrop: Path, verdict: str) -> dict:
    """Move a rejected slug's fileset to rejected/ — only when asked."""
    slug = slug_of(manifest_path)
    rejected = patchdrop / "rejected"
    moved = []
    for path in manifest_path.parent.glob(slug + ".*"):
        rejected.mkdir(exist_ok=True)
        dest = rejected / path.name
        shutil.move(str(path), str(dest))
        moved.append(path.name)
    return {"movedTo": "rejected/", "files": moved}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("check")
    p.add_argument("--bundle", required=True)
    p.add_argument("--patchdrop-root", default=str(ROOT / "__patch_drop__"))
    p.add_argument("--policy")
    p.add_argument("--inventory-file")
    p.add_argument("--apply-moves", action="store_true",
                   help="physically move reject:* filesets to rejected/")
    p = sub.add_parser("queue")
    p.add_argument("--patchdrop-root", default=str(ROOT / "__patch_drop__"))
    args = parser.parse_args(argv)

    if args.action == "queue":
        result, code = cmd_queue(args)
    else:
        result, code = cmd_check(args)
        if (result["verdict"].startswith("reject:")
                and args.apply_moves
                and "slug" in result):
            bundle = Path(args.bundle)
            if bundle.is_file():
                result["moved"] = apply_moves(
                    bundle, Path(args.patchdrop_root), result["verdict"])
    print(json.dumps(result, ensure_ascii=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
