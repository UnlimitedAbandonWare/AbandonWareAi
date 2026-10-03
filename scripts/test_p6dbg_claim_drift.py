#!/usr/bin/env python3
"""Self-test for p6dbg_claim_drift.py — builds a fake evidence md + fake
source tree under a temp dir and checks every verdict class.

Run: python -B scripts/test_p6dbg_claim_drift.py   (exit 0 = PASS)
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "p6dbg_claim_drift.py"


def build_tree(base: Path):
    src = base / "main" / "java" / "com" / "x"
    src.mkdir(parents=True)
    (src / "Same.java").write_text(
        "class Same {\n    int a() {\n        return 1;\n    }\n}\n", encoding="utf-8")
    (src / "Moved.java").write_text(
        "// header added\nclass Moved {\n    int b() {\n        return 2;\n    }\n}\n",
        encoding="utf-8")
    (src / "Changed.java").write_text(
        "class Changed {\n    int c() {\n        return 99; /* edited */\n    }\n}\n",
        encoding="utf-8")


def build_evidence(path: Path):
    path.write_text(
        "# fake evidence\n\n"
        "## E01 first claim\n\n"
        "### `main/java/com/x/Same.java:1-3`\n\n"
        "```text\n   1 | class Same {\n   2 |     int a() {\n   3 |         return 1;\n```\n\n"
        "### `main/java/com/x/Moved.java:1-3`\n\n"
        "```text\n   1 | class Moved {\n   2 |     int b() {\n   3 |         return 2;\n```\n\n"
        "### `ma3212in.zip::main/java/com/x/Changed.java:1-3`\n\n"
        "```text\n   1 | class Changed {\n   2 |     int c() {\n   3 |         return 0;\n```\n\n"
        "### `main/java/com/x/Gone.java:1-2`\n\n"
        "```text\n   1 | class Gone {\n   2 | }\n```\n",
        encoding="utf-8")


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="p6dbg_drift_") as td:
        base = Path(td)
        build_tree(base)
        ev = base / "EVID.md"
        build_evidence(ev)
        out_json = base / "out.json"
        out_md = base / "out.md"
        p = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(base),
             "--evidence", str(ev), "--out-json", str(out_json),
             "--out-md", str(out_md)],
            capture_output=True, text=True)
        assert p.returncode in (0,), f"exit={p.returncode} err={p.stderr}"
        report = json.loads(out_json.read_text(encoding="utf-8"))
        verdicts = {a["anchor"].split("`")[-2] if "`" in a["anchor"] else a["path"]: a["verdict"]
                    for a in report["anchors"]}
        by_path = {a["path"] + ":" + str(a["zip_range"][0]): a for a in report["anchors"]}
        assert by_path["main/java/com/x/Same.java:1"]["verdict"] == "STILL_PRESENT", by_path
        moved = by_path["main/java/com/x/Moved.java:1"]
        assert moved["verdict"] == "MOVED" and moved["line_shift"] == 1, moved
        changed = by_path["main/java/com/x/Changed.java:1"]
        assert changed["verdict"] == "CHANGED", changed
        gone = by_path["main/java/com/x/Gone.java:1"]
        assert gone["verdict"] == "FILE_MISSING", gone
        assert report["verdict_counts"]["STILL_PRESENT"] == 1
        assert report["verdict_counts"]["MOVED"] == 1
        assert report["verdict_counts"]["CHANGED"] == 1
        assert report["verdict_counts"]["FILE_MISSING"] == 1
        assert out_md.read_text(encoding="utf-8").startswith("# P6 claim drift")
    print(json.dumps({"status": "PASS", "cases": 4}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
