#!/usr/bin/env python3
"""Self-test for p6dbg_route_divergence_probe.py.

Builds a 20+ row mini corpus, runs the probe end-to-end (javac + java under a
temp work dir), and asserts the divergence machinery fires on known cases.

Run: python -B scripts/test_p6dbg_route_divergence_probe.py  (exit 0 = PASS)
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "p6dbg_route_divergence_probe.py"


def main() -> int:
    questions = [
        {"id": "Q01", "text": "날씨", "expected": "SIMPLE", "reason": "t", "trap": False},
        {"id": "Q02", "text": "두 방식 비교", "expected": "COMPLEX", "reason": "t", "trap": True},
        {"id": "Q03", "text": "왜 이래", "expected": "COMPLEX", "reason": "t", "trap": False},
        {"id": "Q04", "text": "스프링 부트에서 JPA 설정하는 방법", "expected": "AMBIGUOUS", "reason": "t", "trap": False},
        {"id": "Q05", "text": "x" * 200, "expected": "SIMPLE", "reason": "long", "trap": True},
    ]
    while len(questions) < 22:
        questions.append({"id": f"Q{len(questions)+1:02d}", "text": "테스트 질문입니다",
                          "expected": "AMBIGUOUS", "reason": "pad", "trap": False})

    with tempfile.TemporaryDirectory(prefix="p6dbg_route_") as td:
        base = Path(td)
        corpus = base / "corpus.jsonl"
        corpus.write_text("".join(json.dumps(q, ensure_ascii=False) + "\n" for q in questions),
                          encoding="utf-8")
        out_json = base / "out.json"
        out_md = base / "out.md"
        p = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--root", str(ROOT),
             "--corpus", str(corpus), "--work-dir", str(base / "work"),
             "--out-json", str(out_json), "--out-md", str(out_md)],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        assert p.returncode == 0, f"exit={p.returncode} out={p.stdout} err={p.stderr}"
        report = json.loads(out_json.read_text(encoding="utf-8"))
        assert report["questions"] == 22
        assert report["source_hashes_unchanged"] is True
        assert report["compile_exit"] == 0
        m = {r["id"]: r for r in report["matrix"]}
        # known divergence: modelUnavailable makes g2 never return SIMPLE
        assert m["Q01"]["g1_rules_only"] == "SIMPLE", m["Q01"]
        assert m["Q01"]["g2_model_nopath"] == "AMBIGUOUS", m["Q01"]
        assert m["Q01"]["gate_divergent"] is True
        # "두 방식 비교": rules say COMPLEX, model-empty-file says SIMPLE
        assert m["Q02"]["g1_rules_only"] == "COMPLEX", m["Q02"]
        assert m["Q02"]["g3_model_emptyfile"] == "SIMPLE", m["Q02"]
        # classifier throwing -> exception propagates (no fallback today)
        assert str(m["Q03"]["g4_classifier_throws"]).startswith("EXCEPTION"), m["Q03"]
        # tokens=800 promotes regardless of complexity
        assert m["Q01"]["r3_promote_tok_raised"] is True
        assert m["Q01"]["promoted_with_low_complexity"] is True
        assert m["Q01"]["r2_promote_tok_default"] is False
        assert len(report["gate_divergent_questions"]) >= 1
        assert len(report["low_complexity_promoted"]) >= 1
        assert "DIVERGENT" in out_md.read_text(encoding="utf-8")
    print(json.dumps({"status": "PASS", "questions": 22}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
