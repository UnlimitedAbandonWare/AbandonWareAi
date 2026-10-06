"""Self-check for probe_front_router_consistency.py — synthetic Java trees.

A buggy fixture tree (a `new QueryComplexityGate()` inside a policy class, a
token-cap promotion predicate, a file-existence-driven classifier) must yield
the known rule ids; a clean tree and --mock-only must exit 0. The embedded Jev
rerank mock must show the naive policy dropping protected candidates while the
guarded policy preserves them.

Run: python -B scripts/test_probe_front_router_consistency.py
Exit 0 = all cases behaved; 1 = a case disagreed.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "probe_front_router_consistency.py"
SCHEMA = "awx.front-router-consistency.v1"

BUGGY_POLICY = """package com.example.lms.service.routing;
class RouterPolicy {
    public boolean complexMainRequest(String query, String intent) {
        var level = new com.example.lms.service.rag.QueryComplexityGate()
                .assess(query);
        double score = level == com.example.lms.service.rag
                .QueryComplexityGate.Level.COMPLEX ? .70 : .40;
        return score >= 0.55;
    }
    public boolean shouldPromote(RouteSignal s) {
        if (s.maxTokens() >= tokensThreshold) { return true; }
        return false;
    }
}
"""

BUGGY_CLASSIFIER = """package com.example.lms.service.rag;
class ModelBasedQueryComplexityClassifier implements QueryComplexityClassifier {
    private volatile boolean modelUnavailable;
    void init() {
        if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(modelPath)))
            modelUnavailable = true;
    }
    public QueryComplexityGate.Level classify(String query) {
        int score = 1;
        if (score >= 1 || modelUnavailable)
            return QueryComplexityGate.Level.AMBIGUOUS;
        return QueryComplexityGate.Level.SIMPLE;
    }
}
"""

GATE = """package com.example.lms.service.rag;
class QueryComplexityGate {
    public enum Level { SIMPLE, AMBIGUOUS, COMPLEX }
    public Level assess(String q) { return Level.SIMPLE; }
}
"""

CLEAN_POLICY = """package com.example.lms.service.routing;
class RouterPolicy {
    private final com.example.lms.service.rag.QueryComplexityGate gate;
    RouterPolicy(com.example.lms.service.rag.QueryComplexityGate gate) {
        this.gate = gate;
    }
    public boolean complexMainRequest(String query) {
        return gate.assess(query) ==
               com.example.lms.service.rag.QueryComplexityGate.Level.COMPLEX;
    }
}
"""

CONF_FILE = """package com.example.lms.assist;
class Choice {
    boolean confidenceAccepted = probability >= threshold;
    double probability; double threshold;
}
"""

TOKEN_ONLY_POLICY = """package com.example.lms.service.routing;
class RouterPolicy {
    private final com.example.lms.service.rag.QueryComplexityGate gate;
    RouterPolicy(com.example.lms.service.rag.QueryComplexityGate gate) {
        this.gate = gate;
    }
    public boolean shouldPromote(RouteSignal s) {
        if (s.maxTokens() >= tokensThreshold) { return true; }
        return false;
    }
}
"""


def run_tool(*args: str) -> tuple[int, dict]:
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    try:
        payload = json.loads(proc.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        payload = {"parse_error": proc.stdout[:200],
                   "stderr": proc.stderr[:200]}
    return proc.returncode, payload


def put(root: Path, rel: str, text: str):
    p = root / "main" / "java" / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def main() -> int:
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        buggy = Path(tmp) / "buggy"
        put(buggy, "com/example/lms/service/routing/RouterPolicy.java",
            BUGGY_POLICY)
        put(buggy, "com/example/lms/service/rag/QueryComplexityGate.java", GATE)
        put(buggy, "com/example/lms/service/rag/"
                   "ModelBasedQueryComplexityClassifier.java", BUGGY_CLASSIFIER)
        put(buggy, "com/example/lms/assist/Choice.java", CONF_FILE)

        code, out = run_tool("--root", str(buggy))
        ids = {f["id"] for f in out.get("findings", [])}
        cases.append(("buggy-tree-all-rules-fired", code == 2
                      and "R-GATE-DIVERGENCE" in ids
                      and "R-TOKEN-CAP-PROMO" in ids
                      and "R-MODEL-FILE-FLIP" in ids
                      and "R-CONFIDENCE-NAME" in ids, out))

        gate_hits = [f for f in out.get("findings", [])
                     if f["id"] == "R-GATE-DIVERGENCE"]
        cases.append(("gate-divergence-file-line",
                      len(gate_hits) == 1
                      and gate_hits[0]["file"].endswith("RouterPolicy.java")
                      and gate_hits[0]["line"] == 4, out))

        clean = Path(tmp) / "clean"
        put(clean, "com/example/lms/service/routing/RouterPolicy.java",
            CLEAN_POLICY)
        put(clean, "com/example/lms/service/rag/QueryComplexityGate.java", GATE)
        code, out = run_tool("--root", str(clean))
        cases.append(("clean-tree-exit0", code == 0
                      and out.get("violationCount") == 0, out))

        code, out = run_tool("--root", str(clean), "--mock-only")
        mock = out.get("mock", {})
        cases.append(("mock-rerank-contract", code == 0
                      and mock.get("handlesSeparated") is True
                      and mock.get("naiveViolatesContract") is True
                      and mock.get("guardedPreservesContract") is True
                      and "c3" in mock.get("naiveDroppedProtected", [])
                      and "c4" in mock.get("naiveDroppedProtected", []), out))

        # accepted-config behaviour: exact id+file match downgrades a finding
        # to severity "accepted" (out of the exit count); a file mismatch keeps
        # the violation; a missing config file changes nothing.
        acc_root = Path(tmp) / "accepted"
        put(acc_root, "com/example/lms/service/routing/RouterPolicy.java",
            TOKEN_ONLY_POLICY)
        put(acc_root, "com/example/lms/service/rag/QueryComplexityGate.java",
            GATE)
        acc_cfg = acc_root / "configs" / "probe-front-router-accepted.json"
        acc_cfg.parent.mkdir(parents=True, exist_ok=True)
        acc_cfg.write_text(json.dumps([{
            "id": "R-TOKEN-CAP-PROMO",
            "file": "main/java/com/example/lms/service/routing/"
                    "RouterPolicy.java",
            "reason": "test-accepted", "evidence": "t",
            "verifiedAt": "t"}]), encoding="utf-8")
        code, out = run_tool("--root", str(acc_root))
        acc_hits = [f for f in out.get("findings", [])
                    if f.get("severity") == "accepted"]
        cases.append(("accepted-match-exit0", code == 0
                      and out.get("violationCount") == 0
                      and len(acc_hits) == 1
                      and acc_hits[0]["id"] == "R-TOKEN-CAP-PROMO", out))

        acc_cfg.write_text(json.dumps([{
            "id": "R-TOKEN-CAP-PROMO",
            "file": "main/java/com/example/lms/service/other/Other.java",
            "reason": "wrong-file", "evidence": "t",
            "verifiedAt": "t"}]), encoding="utf-8")
        code, out = run_tool("--root", str(acc_root))
        cases.append(("accepted-file-mismatch-stays-violation", code == 2
                      and any(f["id"] == "R-TOKEN-CAP-PROMO"
                              and f["severity"] == "violation"
                              for f in out.get("findings", [])), out))

        acc_cfg.unlink()
        code, out = run_tool("--root", str(acc_root))
        cases.append(("accepted-config-absent-unchanged", code == 2
                      and out.get("violationCount") == 1, out))

        # live tree sanity: the tool runs and reports JSON; post-fix the two
        # repaired rules stay absent and the token-cap finding is either gone
        # (FIX) or severity "accepted" (KEEP) — never a live violation.
        code, out = run_tool("--root", str(ROOT))
        cases.append(("live-tree-json-report", code in (0, 2)
                      and out.get("schemaVersion") == SCHEMA, out))
        live_findings = out.get("findings", [])
        live_ids = {f["id"] for f in live_findings}
        cases.append(("live-tree-fixed-rules-absent",
                      "R-GATE-DIVERGENCE" not in live_ids
                      and "R-MODEL-FILE-FLIP" not in live_ids, out))
        cases.append(("live-tree-token-cap-not-violation",
                      all(f["severity"] != "violation"
                          for f in live_findings
                          if f["id"] == "R-TOKEN-CAP-PROMO"), out))

    failed = [n for n, ok, _ in cases if not ok]
    for name, ok, out in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: "
              f"{json.dumps(out, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
