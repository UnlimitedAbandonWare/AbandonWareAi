#!/usr/bin/env python3
"""Deterministic synthetic JSONL traces; creates a new tree under repo var only."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import tempfile
import unittest
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
EPOCH = datetime(2026, 10, 4, tzinfo=timezone.utc).timestamp()


def var_target(value: str) -> Path:
    path = Path(os.path.abspath(ROOT / value))
    path.relative_to(ROOT / "var")
    if path == ROOT / "var":
        raise ValueError("var-root-forbidden")
    for p in (path, *path.parents):
        if p.is_symlink() or (p.exists() and getattr(p.lstat(), "st_file_attributes", 0) & 1024):
            raise ValueError("reparse-path-forbidden")
    return path


def h12(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()[:12]


def row(seed: int, query: str, offset: int, index: int = 0) -> dict:
    return {"ts": datetime.fromtimestamp(EPOCH + offset, timezone.utc).isoformat().replace("+00:00", "Z"),
            "sessionId": "hash:" + h12(query), "runId": "hash:" + h12(f"run-{seed}-{index}"),
            "recordId": f"synthetic-{seed}-{index}", "surface": "chat",
            "effectiveModel": "synthetic", "outcome": "completed", "traceKeys": []}


def encode(record: dict) -> bytes:
    return (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()


def put(base: Path, day: str, stem: str, data: bytes, mtime: int = 0) -> None:
    path = base / day / (stem + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as f:
        f.write(data)
    os.utime(path, (EPOCH + mtime, EPOCH + mtime))


def generate(out: Path, seed: int, bench: int = 0, line_bytes: int = 2 * 1024 * 1024) -> dict:
    if out.exists():
        raise ValueError("output-must-be-new")
    if bench not in (0, 1, 10) or not 1024 <= line_bytes <= 8 * 1024 * 1024:
        raise ValueError("invalid-fixture-budget")
    out.mkdir(parents=True)
    traces = out / "var/debug/chat-session-traces"
    query = f"CANARY-RAW-QUERY-{seed}"
    queries = [query, "..", f"../{query}", f"..\\{query}"]
    cases = []
    # Fresh record with old mtime and old record with fresh mtime.
    for i, (offset, mtime) in enumerate(((60, -172800), (-172800, 60))):
        put(traces, "20261004", f"s-{h12(f'mtime-{seed}-{i}')}", encode(row(seed, query, offset, i)), mtime)
        cases.append({"case": f"mtime-ts-{i}", "tsOffset": offset, "mtimeOffset": mtime})
    for i, offset in enumerate((-1, 1)):
        put(traces, "20261003" if offset < 0 else "20261004", "s-" + h12(query),
            encode(row(seed, query, offset, i + 2)), offset)
    cases.append({"case": "utc-midnight", "sessionHash": h12(query)})
    valid = encode(row(seed, query, 2, 4))
    put(traces, "20261004", "s-" + h12(f"truncated-{seed}"), valid + b'{"ts":"2026-10')
    put(traces, "20261004", "s-" + h12(f"broken-{seed}"), valid + b'{broken}\n' + valid)
    long_row = row(seed, query, 3, 5)
    long_row["syntheticPadding"] = "x" * line_bytes
    put(traces, "20261004", "s-" + h12(f"large-{seed}"), encode(long_row))
    cases.extend({"case": name} for name in ("truncated-tail", "broken-middle", "oversize-line"))
    for i, candidate in enumerate(queries[1:]):
        put(traces, "20261004", "s-" + h12(candidate), encode(row(seed, candidate, 4 + i, 6 + i)))
    benchmark = None
    if bench:
        base = out / f"bench-{bench}x/var/debug/chat-session-traces"
        rng = random.Random(seed)
        remaining = 2670000 - 101000
        sizes = [101000] + [remaining // 242 + (i < remaining % 242) for i in range(242)]
        for i in range(243 * bench):
            rec = row(seed, f"bench-{seed}-{i}", rng.randrange(-86400, 86400), i)
            rec["syntheticPadding"] = ""
            length = sizes[i % 243]
            rec["syntheticPadding"] = "b" * (length - len(encode(rec)))
            put(base, "20261004", "s-" + h12(f"bench-{seed}-{i}"), encode(rec))
        benchmark = {"files": 243 * bench, "bytes": 2670000 * bench, "maxFileBytes": 101000,
                     "scale": bench, "coldVerified": False, "baseline": None, "target": None,
                     "observed": None, "dbComparison": "NOT_RUN"}
    manifest = {"schema": "awx.chat-trace-synthetic.v1", "seed": seed, "synthetic": True,
                "queryCases": queries, "cases": cases, "lineBytes": line_bytes,
                "benchmark": benchmark}
    (out / "fixture-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


class FixtureTests(unittest.TestCase):
    def test_deterministic_and_shape(self):
        (ROOT / "var").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "var", prefix="sol-fixture-") as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            generate(a, 7, 1, 2048)
            generate(b, 7, 1, 2048)
            def inventory(base):
                return [(p.relative_to(base).as_posix(), hashlib.sha256(p.read_bytes()).hexdigest())
                        for p in sorted(base.rglob("*")) if p.is_file()]
            self.assertEqual(inventory(a), inventory(b))
            bench = list((a / "bench-1x").rglob("*.json"))
            self.assertEqual(len(bench), 243)
            self.assertEqual(sum(p.stat().st_size for p in bench), 2670000)
            self.assertEqual(max(p.stat().st_size for p in bench), 101000)
            base = a / "var/debug/chat-session-traces"
            self.assertTrue((base / "20261003").is_dir())
            data = [p.read_bytes() for p in base.rglob("*.json")]
            self.assertTrue(any(b"{broken}\n" in d for d in data))
            self.assertTrue(any(d.endswith(b'{"ts":"2026-10') for d in data))
            self.assertTrue(any(len(d) > 2048 for d in data))
            self.assertEqual(sum(b'"ts"' in d for d in data), 10)
            self.assertTrue(any(p.stat().st_mtime == EPOCH - 172800 for p in base.rglob("*.json")))
            with self.assertRaises(ValueError):
                generate(a, 7)
    def test_output_boundary(self):
        for name in (".", "var", "scripts/new", "var/../scripts/new"):
            with self.assertRaises(ValueError):
                var_target(name)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--out", default="var/codex-assist-session-context/fixture-seed7")
    p.add_argument("--bench", type=int, choices=(1, 10), default=0, metavar="N",
                   help="Opt in to 243*N files, 2.67MB*N, max 101KB; no DB/cache claims.")
    p.add_argument("--line-bytes", type=int, default=2 * 1024 * 1024)
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args(argv)
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(FixtureTests))
        return 0 if result.wasSuccessful() else 1
    try:
        manifest = generate(var_target(args.out), args.seed, args.bench, args.line_bytes)
        print(json.dumps({"status": "created", "seed": args.seed, "cases": len(manifest["cases"]),
                          "benchmark": manifest["benchmark"]}))
        return 0
    except (OSError, ValueError):
        print(json.dumps({"status": "FAIL", "reason": "invalid-or-existing-output"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
