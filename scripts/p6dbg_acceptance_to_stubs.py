#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D6: JEV_ACCEPTANCE_CASES.jsonl (30) -> JUnit stub table for Codex WP8/WP9.

Reused: none (converter). Added: scope mapping JEV WP-01..05 -> P6 scope, JUnit
method-name stubs, per-case related sources via JEV_SOURCE_MAP keyword match.
Does NOT generate Java test files (directive: stubs only).

Scope rule (auditable, not heuristic):
  IN_SCOPE  : JEV WP-01 (feature-off/scope/account), WP-02 (candidate lifecycle:
              schema/stale/timeout/consent/dedup), WP-05 ids C27,C28,C29
              (failure masking, OFF/ON shadow, verification masking)
  OUT_OF_SCOPE: WP-03 (evidence/context shaping), WP-04 (provider wire/SSE/
              billing — not P6 scope), WP-05 C30 (display metrics)

Exit 0 PASS / 1 FAIL / 2 input missing.
"""
from __future__ import annotations
import argparse, json, re, sys
from pathlib import Path

_P6_REF = Path.home() / "Downloads" / "P6_REF_20261001"
DEFAULT_CASES = _P6_REF / "JEV_ACCEPTANCE_CASES.jsonl"
DEFAULT_MAP = _P6_REF / "JEV_SOURCE_MAP.json"
DEFAULT_OUT = "data/agent-handoff/devin-p6/jev-acceptance-stubs.md"

IN_SCOPE_WP = {"WP-01", "WP-02"}
IN_SCOPE_IDS = {"C27", "C28", "C29"}  # WP-05 subset: masking/verification topics
CASE_KEYWORDS = {
    "C01": ["Enabled", "Runtime"], "C02": ["Scope", "Credential"], "C03": ["Catalog", "Credential"],
    "C05": ["Choice", "Advisor"], "C06": ["Choice", "Gateway"], "C07": ["WebNeed", "Advisor"],
    "C08": ["Snapshot", "Runtime"], "C09": ["Runtime", "Slot"], "C10": ["Timeout", "Budget", "Runtime"],
    "C11": ["Scope", "Consent", "Credential"], "C12": ["Dedup", "Slot", "Runtime"],
    "C13": ["Evidence"], "C22": ["Stream", "Sse"], "C27": ["Persist", "Memory"],
    "C28": ["Shadow", "Runtime"], "C29": ["Verif"], "C24": ["Credential"],
}

def slug(name: str, cid: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").lower()
    return f"case{cid.lower()}_{s}" if s else f"case{cid.lower()}"

def classify(case: dict) -> tuple[str, str]:
    cid, wp = case["id"], case["work_package"]
    if cid in IN_SCOPE_IDS:
        return "IN_SCOPE", "WP-05 masking subset (P6 Phase A overlap)"
    if wp in IN_SCOPE_WP:
        return "IN_SCOPE", f"JEV {wp} maps to Codex WP8/WP9 candidate-selection path"
    return "OUT_OF_SCOPE", f"JEV {wp} — outside Codex P6 candidate path"

def related_sources(case: dict, files: list[dict]) -> list[str]:
    kws = CASE_KEYWORDS.get(case["id"], [])
    out = []
    for f in files:
        base = Path(f["path"]).name
        if any(k.lower() in base.lower() for k in kws):
            out.append(f["path"])
    return out

def main() -> int:
    ap = argparse.ArgumentParser(description="Jev acceptance cases -> test stub table")
    ap.add_argument("--cases", default=str(DEFAULT_CASES))
    ap.add_argument("--source-map", default=str(DEFAULT_MAP))
    ap.add_argument("--root", default=".")
    ap.add_argument("--out", default=DEFAULT_OUT)
    a = ap.parse_args()

    cp, mp = Path(a.cases), Path(a.source_map)
    if not cp.exists() or not mp.exists():
        print(json.dumps({"status": "FAIL", "reason": "missing input",
                          "cases": str(cp), "map": str(mp)}))
        return 2
    cases = [json.loads(l) for l in cp.read_text(encoding="utf-8").splitlines() if l.strip()]
    smap = json.loads(mp.read_text(encoding="utf-8"))
    files = smap.get("files", [])

    rows, n_in = [], 0
    for c in cases:
        scope, why = classify(c)
        n_in += scope == "IN_SCOPE"
        rel = related_sources(c, files)
        rows.append({
            "id": c["id"], "wp": c["work_package"], "name": c["name"],
            "scope": scope, "scope_reason": why,
            "junit_stub": f"@Test void {slug(c['name'], c['id'])}()",
            "setup": c["setup"], "expected": c["expected"],
            "related_sources": rel,
            "verification_status": c.get("verification_status"),
        })

    root = Path(a.root).resolve()
    out = root / a.out
    out.parent.mkdir(parents=True, exist_ok=True)
    L = ["# D6 — Jev acceptance stubs (30 cases classified)",
         "",
         f"IN_SCOPE {n_in} / OUT_OF_SCOPE {len(cases) - n_in}. "
         "Java test files are NOT generated — this is a conversion table for Codex.",
         "",
         "| case | JEV WP | scope | junit stub | expected (요약) | related sources |",
         "|---|---|---|---|---|---|"]
    for r in rows:
        rel = "<br>".join(Path(p).name for p in r["related_sources"]) or "—"
        L.append(f"| {r['id']} | {r['wp']} | {r['scope']} | `{r['junit_stub']}` | "
                 f"{r['expected'][:60]} | {rel} |")
    L += ["", "## Scope mapping rule",
          "- IN_SCOPE: JEV WP-01 (feature-off/scope/account), WP-02 (candidate lifecycle), "
          "WP-05 ids C27/C28/C29 (failure·verification masking)",
          "- OUT_OF_SCOPE: WP-03 (evidence/context shaping), WP-04 (provider wire/SSE/billing), "
          "WP-05 C30 (display metrics)",
          "", "## Source map (12 reviewed files)", ""]
    for f in files:
        L.append(f"- `{f['path']}` ({f['total_lines']} lines, evidence={f['evidence_level']})")
    out.write_text("\n".join(L) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS", "cases": len(cases), "in_scope": n_in,
                      "out_of_scope": len(cases) - n_in, "out": str(out)}, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    sys.exit(main())
