#!/usr/bin/env python3
"""Self-test for p6dbg_success_mask_scan.py — synthetic sources exercising
each detector family a..f. Run: python -B scripts/test_p6dbg_success_mask_scan.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "p6dbg_success_mask_scan.py"

DEMO = """package x;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
class Demo {
    private final AtomicBoolean blocked = new AtomicBoolean();
    private java.util.Map<String,List<String>> cacheMap = new java.util.HashMap<>();
    private final Store store = new Store();
    void ingest() {
        store.flush();              // (a) Outcome discarded
        blocked.set(true);          // (e) never cleared
        try { store.save(); }
        catch (Throwable t) {       // (b)
            log.debug("swallow");   // (c) debug-only
        }
        try { risky(); }
        catch (Exception e) {
            this.cacheMap.put("k", List.of());  // (f)
        }
        try { risky(); }
        catch (Exception ignore) {
            log.debug("multi-line swallow arg={}",
                    ignore.getMessage());   // (c) continuation line
        }
    }
    synchronized void refresh() {
        var tpl = new RestTemplate();           // (d)
        tpl.getForObject("http://127.0.0.1", String.class);
    }
    void risky() {}
    static class RestTemplate { Object getForObject(String u, Class<?> c){return null;} }
    static class log { static void debug(String s){} }
    static class Store {
        FlushOutcome flush() { return null; }
        SaveResult save() { return null; }
    }
    static class FlushOutcome { boolean durable(){return true;} }
    static class SaveResult {}
}
"""


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="p6dbg_mask_") as td:
        base = Path(td)
        src = base / "main" / "java" / "x"
        src.mkdir(parents=True)
        (src / "Demo.java").write_text(DEMO, encoding="utf-8")
        out_json = base / "o.json"
        out_md = base / "o.md"
        p = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(base),
             "--out-json", str(out_json), "--out-md", str(out_md),
             "--allowlist", "nonexistent-allowlist.txt"],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        assert p.returncode == 0, f"exit={p.returncode} err={p.stderr} out={p.stdout}"
        report = json.loads(out_json.read_text(encoding="utf-8"))
        pats = set(report["by_pattern"])
        for want in ("a-result-discard", "b-catch-error", "c-log-only-catch",
                     "d-http-in-synchronized", "e-one-way-flag",
                     "f-empty-cache-on-failure"):
            assert want in pats, f"missing {want} in {pats}"
        # a Test.java sibling must not be scanned
        (src / "DemoTest.java").write_text(
            "package x; class DemoTest { void t(){ store.flush(); } void x(){ store.save(); } }",
            encoding="utf-8")
        (src / "Demo.java").write_text(
            DEMO + "\n// extra\n", encoding="utf-8")
        p2 = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(base),
             "--out-json", str(out_json), "--out-md", str(out_md),
             "--allowlist", "nonexistent-allowlist.txt"],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        report2 = json.loads(out_json.read_text(encoding="utf-8"))
        assert all(not f["file"].endswith("DemoTest.java") for f in report2["findings"])
        # allowlist suppresses
        allow = base / "allow.txt"
        allow.write_text("x/Demo.java#e-one-way-flag\n", encoding="utf-8")
        subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(base),
             "--out-json", str(out_json), "--out-md", str(out_md),
             "--allowlist", str(allow)],
            capture_output=True, text=True)
        report3 = json.loads(out_json.read_text(encoding="utf-8"))
        assert "e-one-way-flag" not in report3["by_pattern"], report3["by_pattern"]
    print(json.dumps({"status": "PASS", "patterns": 6}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
