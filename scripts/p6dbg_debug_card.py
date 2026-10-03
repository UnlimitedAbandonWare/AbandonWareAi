#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D9: 1-minute debug card — failing test -> failure, stack, source context,
known D1/D3 findings, hypothesis template.

Reused: reads build/test-results/*.xml (Gradle output; never runs Gradle).
Added: stack->source excerpter, cross-ref to devin-p6 claim-drift + success-mask
results, card writer. Read-only on repo files; writes only devin-p6/cards/.

Usage:
  python -B scripts/p6dbg_debug_card.py <TestClassName>
  python -B scripts/p6dbg_debug_card.py --xml path/to/TEST-x.xml
Exit 0 card written / 1 parse failure / 2 input missing.
"""
from __future__ import annotations
import argparse, hashlib, json, re, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path

DEVIN_P6 = Path("data/agent-handoff/devin-p6")
STACK_RE = re.compile(r"\bat\s+[\w.$]+\.(\w+)\(([\w$]+\.java):(\d+)\)")

def find_xml(root: Path, name: str) -> list[Path]:
    hits = []
    for p in root.glob("build*/**/TEST-*.xml"):
        if p.is_file() and (name in p.name or name in p.stem):
            hits.append(p)
    return sorted(hits, key=lambda p: p.stat().st_mtime, reverse=True)

def parse_failures(xml_path: Path) -> list[dict]:
    tree = ET.parse(xml_path)
    out = []
    for tc in tree.iter("testcase"):
        for f in list(tc):
            if f.tag in ("failure", "error", "rerunFailure"):
                out.append({"class": tc.get("classname"), "test": tc.get("name"),
                            "kind": f.tag, "message": (f.get("message") or "")[:500],
                            "text": (f.text or "")[:4000]})
    return out

def stack_java_refs(text: str) -> list[tuple[str, int]]:
    refs = []
    for m in STACK_RE.finditer(text):
        cls, ln = m.group(2), int(m.group(3))
        refs.append((cls, ln))
    seen, uniq = set(), []
    for r in refs:
        if r not in seen:
            seen.add(r)
            uniq.append(r)
    return uniq[:8]

def find_source(root: Path, cls_file: str) -> Path | None:
    hits = list(root.glob(f"main/java/**/{cls_file}"))
    return hits[0] if hits else None

def excerpt(path: Path, line: int, ctx: int = 15) -> dict:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    lo, hi = max(0, line - 1 - ctx), min(len(lines), line + ctx)
    return {"file": str(path), "line": line,
            "range": [lo + 1, hi],
            "text": "\n".join(f"{lo+1+i}| {l}" for i, l in enumerate(lines[lo:hi]))}

def cross_refs(root: Path, files: list[str]) -> dict:
    out = {"drift": [], "mask": []}
    drift = root / DEVIN_P6 / "claim-drift-baseline.json"
    if drift.exists():
        d = json.loads(drift.read_text(encoding="utf-8"))
        for a in d.get("anchors", []):
            if any(a.get("path", "").endswith(f) for f in files):
                out["drift"].append({"anchor": a.get("anchor"), "verdict": a.get("verdict")})
    mask = root / DEVIN_P6 / "success-mask-scan.json"
    if mask.exists():
        d = json.loads(mask.read_text(encoding="utf-8"))
        for fnd in d.get("findings", []):
            if any(fnd.get("file", "").endswith(f) for f in files):
                out["mask"].append({"pattern": fnd["pattern"], "line": fnd["line"],
                                    "risk": fnd["risk"], "detail": fnd["detail"][:80]})
    return out

def card_md(name: str, xml: Path, fails: list[dict], ctx: list[dict],
            xrefs: dict, stack_lines: list[str]) -> str:
    L = [f"# Debug card — {name}",
         f"xml: `{xml}`  | failures: {len(fails)}", ""]
    for f in fails[:3]:
        L += [f"## {f['kind']}: {f['class']}.{f['test']}",
              f"```\n{f['message']}\n```",
              "stack top:", "```"] + stack_lines[:10] + ["```", ""]
    L.append("## Source context (±15 lines)")
    for c in ctx:
        L += [f"### {c['file']}:{c['line']}", "```java", c["text"], "```"]
    if xrefs["drift"]:
        L += ["", "## D1 claim-drift hits"]
        for d in xrefs["drift"][:6]:
            L.append(f"- `{d['anchor']}` → {d['verdict']}")
    if xrefs["mask"]:
        L += ["", "## D3 success-mask hits"]
        for m in xrefs["mask"][:6]:
            L.append(f"- `{m['pattern']}` {m['risk']} line {m['line']}: {m['detail']}")
    L += ["", "## Hypotheses (fill in)",
          "- H+ (affirm): ____",
          "- H- (counter): ____",
          "- H0 (neutral/risk): ____",
          "- Next check: ____",
          ""]
    return "\n".join(L)

def main() -> int:
    ap = argparse.ArgumentParser(description="Debug card generator")
    ap.add_argument("name", nargs="?", help="test class name (e.g. TrainRagIngestServiceTest)")
    ap.add_argument("--xml", help="explicit JUnit XML path")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    root = Path(a.root).resolve()

    xmls = [Path(a.xml)] if a.xml else (find_xml(root, a.name) if a.name else [])
    if not xmls or not xmls[0].exists():
        print(json.dumps({"status": "FAIL", "reason": "no XML found",
                          "hint": "run a focused test first or pass --xml"}))
        return 2
    xml = xmls[0]
    try:
        fails = parse_failures(xml)
    except ET.ParseError as e:
        print(json.dumps({"status": "FAIL", "reason": f"xml parse: {e}"}))
        return 1
    if not fails:
        print(json.dumps({"status": "PASS", "failures": 0, "note": "suite green — no card"}))
        return 0

    text = "\n".join(f["text"] for f in fails)
    stack_lines = [l.strip() for l in text.splitlines() if l.strip().startswith("at ")][:10]
    refs = stack_java_refs(text)
    files = [c for c, _ in refs]
    ctx = []
    for cls, ln in refs[:4]:
        src = find_source(root, cls)
        if src:
            ctx.append(excerpt(src, ln))
    xrefs = cross_refs(root, files)

    cards = root / DEVIN_P6 / "cards"
    cards.mkdir(parents=True, exist_ok=True)
    name = a.name or xml.stem
    ts = time.strftime("%H%M%S")
    card = cards / f"{name}-{ts}.md"
    card.write_text(card_md(name, xml, fails, ctx, xrefs, stack_lines), encoding="utf-8")
    print(json.dumps({"status": "PASS", "failures": len(fails),
                      "sources_cited": len(ctx), "drift_hits": len(xrefs["drift"]),
                      "mask_hits": len(xrefs["mask"]), "card": str(card)}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
