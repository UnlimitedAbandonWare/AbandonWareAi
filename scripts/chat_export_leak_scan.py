#!/usr/bin/env python3
"""Read-only export leak scan. All discovered names are represented by hashes."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HASHED = re.compile(r"export-[0-9a-f]{16}\Z")
QUERY_HASH = re.compile(r"(?:hash:)?[0-9a-f]{12,64}\Z|export-[0-9a-f]{16}\Z")
SECRET_NAME = re.compile(r"(?i)(^\.env|^\.secrets$|token|credential|\.(pem|key|pfx|p12|jks)$)")


def sha12(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()[:12]


def scan(root: Path, extra=(), canary: str | None = None, max_files=2000, max_bytes=32 * 1024 * 1024):
    result = {"exportDirsTotal": 0, "hashedNames": 0, "rawLookingNames": 0,
              "canaryFileOccurrences": 0, "canaryNameOccurrences": 0, "pathLikeNames": 0,
              "rawQueryFields": 0, "metadataPathTraversal": 0, "filesScanned": 0,
              "bytesScanned": 0, "skippedSecretLike": 0, "unreadable": 0,
              "linksSkipped": 0, "budgetExceeded": False, "nameHashes": []}
    if root.is_symlink() or not root.is_dir():
        result["unreadable"] += 1
        return result
    dirs = sorted(p for p in root.iterdir() if p.is_dir())
    for p in dirs:
        result["exportDirsTotal"] += 1
        result["hashedNames"] += bool(HASHED.fullmatch(p.name))
        result["rawLookingNames"] += not bool(HASHED.fullmatch(p.name))
        result["pathLikeNames"] += ".." in p.name or "/" in p.name or "\\" in p.name
        result["nameHashes"].append(sha12(p.name))
    def candidates():
        # Do not follow directory links; no private inputs are opened.
        stack = [root]
        while stack:
            directory = stack.pop()
            for p in sorted(directory.iterdir()):
                if canary:
                    result["canaryNameOccurrences"] += p.name.count(canary)
                if directory != root:
                    result["pathLikeNames"] += ".." in p.name or "/" in p.name or "\\" in p.name
                if p.is_symlink() or (getattr(p.lstat(), "st_file_attributes", 0) & 1024):
                    result["linksSkipped"] += 1
                elif SECRET_NAME.search(p.name):
                    result["skippedSecretLike"] += 1
                elif p.is_dir():
                    stack.append(p)
                elif p.is_file():
                    yield p
        yield from extra
    seen = set()
    def metadata(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == "query" and isinstance(item, str) and not QUERY_HASH.fullmatch(item):
                    result["rawQueryFields"] += 1
                if key == "exportDir" and isinstance(item, str):
                    result["metadataPathTraversal"] += ".." in item.replace("\\", "/").split("/")
                metadata(item)
        elif isinstance(value, list):
            for item in value:
                metadata(item)
    try:
        for p in candidates():
            if p in seen:
                continue
            seen.add(p)
            if p.is_symlink() or (p.exists() and getattr(p.lstat(), "st_file_attributes", 0) & 1024):
                result["linksSkipped"] += 1
                continue
            if SECRET_NAME.search(p.name) or any(s == ".secrets" or s.startswith(".env") for s in p.parts):
                result["skippedSecretLike"] += 1
                continue
            if result["filesScanned"] >= max_files:
                result["budgetExceeded"] = True
                break
            try:
                remaining = max_bytes - result["bytesScanned"]
                if p.stat().st_size > remaining:
                    result["budgetExceeded"] = True
                    break
                with p.open("rb") as f:
                    data = f.read(remaining + 1)
                if len(data) > remaining:
                    result["budgetExceeded"] = True
                    break
                result["filesScanned"] += 1
                result["bytesScanned"] += len(data)
                if canary:
                    result["canaryFileOccurrences"] += data.count(canary.encode())
                if p.name in ("manifest.json", "latest.json") or p in extra:
                    try:
                        metadata(json.loads(data))
                    except (ValueError, UnicodeError):
                        result["unreadable"] += 1
            except OSError:
                result["unreadable"] += 1
    except OSError:
        result["unreadable"] += 1
    return result


def failed(result):
    return any(result[k] for k in ("rawLookingNames", "canaryFileOccurrences",
               "canaryNameOccurrences", "pathLikeNames", "rawQueryFields", "metadataPathTraversal",
               "unreadable", "budgetExceeded", "linksSkipped", "skippedSecretLike"))


class LeakTests(unittest.TestCase):
    def test_nested_canary_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nested = root / ("export-" + "a" * 16) / "CANARY-RAW-QUERY-7"
            nested.mkdir(parents=True)
            (nested / "manifest.json").write_text('{"query":"hash:aaaaaaaaaaaa"}')
            result = scan(root, canary="CANARY-RAW-QUERY-7")
            self.assertTrue(failed(result))
            self.assertEqual(result["canaryNameOccurrences"], 1)
            self.assertNotIn("CANARY", json.dumps(result))
    def test_counts_redaction_and_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            clean = root / ("export-" + "a" * 16)
            clean.mkdir()
            (clean / "manifest.json").write_text('{"query":"hash:aaaaaaaaaaaa"}', encoding="utf-8")
            self.assertFalse(failed(scan(root)))
            raw = root / "CANARY-RAW-QUERY-7"
            raw.mkdir()
            (raw / "manifest.json").write_text('{"query":"CANARY-RAW-QUERY-7","exportDir":"../x"}', encoding="utf-8")
            data = scan(root, canary="CANARY-RAW-QUERY-7")
            self.assertTrue(failed(data))
            self.assertEqual(data["exportDirsTotal"], 2)
            self.assertEqual(data["canaryFileOccurrences"], 1)
            self.assertEqual(data["rawQueryFields"], 1)
            self.assertEqual(data["metadataPathTraversal"], 1)
            self.assertNotIn("CANARY", json.dumps(data))
    def test_budget_and_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "large.json").write_bytes(b"x" * 33)
            self.assertTrue(scan(root, max_bytes=32)["budgetExceeded"])
            self.assertEqual(scan(root / "missing")["unreadable"], 1)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--export-root", type=Path, default=ROOT / "var/debug/chat-session-traces/export")
    p.add_argument("--manifest", type=Path, action="append", default=[])
    p.add_argument("--latest", type=Path, action="append", default=[])
    p.add_argument("--canary", help="Optional synthetic marker; never echoed.")
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args(argv)
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(LeakTests))
        return 0 if result.wasSuccessful() else 1
    result = scan(args.export_root, tuple(args.manifest + args.latest), args.canary)
    print(json.dumps(result, sort_keys=True))
    return 1 if failed(result) else 0


if __name__ == "__main__":
    raise SystemExit(main())
