#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Self-test for p6dbg_debug_card.py using a synthetic JUnit XML."""
import json, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts/p6dbg_debug_card.py"

XML = """<?xml version="1.0"?>
<testsuite name="FakeSuite" tests="2" failures="1">
  <testcase classname="com.example.lms.Fake" name="ingestAdvances">
    <failure message="expected:&lt;1&gt; but was:&lt;0&gt;">java.lang.AssertionError
        at com.example.lms.uaw.autolearn.ingest.TrainRagIngestService.upsertSegments(TrainRagIngestService.java:300)
        at com.example.lms.Fake.ingestAdvances(Fake.java:42)
    </failure>
  </testcase>
  <testcase classname="com.example.lms.Fake" name="ok"/>
</testsuite>"""

def run(*args):
    p = subprocess.run([sys.executable, "-B", str(TOOL), "--root", str(ROOT), *args],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, json.loads(p.stdout)

def main():
    fails = []
    with tempfile.TemporaryDirectory() as td:
        xp = Path(td) / "TEST-com.example.lms.Fake.xml"
        xp.write_text(XML, encoding="utf-8")
        rc, r = run("--xml", str(xp))
        if not (rc == 0 and r["status"] == "PASS" and r["failures"] == 1
                and r["sources_cited"] >= 1 and Path(r["card"]).exists()):
            fails.append(("card", r, rc))
        else:
            card = Path(r["card"]).read_text(encoding="utf-8")
            if "TrainRagIngestService.java" not in card or "Hypotheses" not in card:
                fails.append(("content", r, rc))
    rc, r = run("NoSuchTestClassXYZ")
    if not (rc == 2 and r["status"] == "FAIL"):
        fails.append(("missing", r, rc))
    if fails:
        print(json.dumps({"status": "FAIL", "fails": [f[0] for f in fails]}))
        return 1
    print(json.dumps({"status": "PASS", "cases": 2}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
