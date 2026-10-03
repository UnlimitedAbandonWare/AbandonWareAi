#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D4: JSONL boundary fixtures for TrainRagIngestService/readUtf8Line tests.

Reused: none (generator). Added: deterministic fixture set + manifest with expected
reader behavior per file. Pure stdlib, read-only for repo files, writes only to
data/agent-handoff/devin-p6/fixtures/jsonl/.

Each manifest entry carries:
  - bytes, sha256
  - complete_lines: count of \n-terminated records a byte-honest reader returns
  - tail: "none"|"unterminated"|"mid_utf8"|"cr_only" — bytes left after last \n
  - expect: {consumed_records, tail_must_not_advance_offset, parse_ok}
"""
from __future__ import annotations
import argparse, hashlib, json, sys
from pathlib import Path

OUT_SUB = Path("data/agent-handoff/devin-p6/fixtures/jsonl")

def _rec(i, **kw):
    base = {"id": f"fx-{i:02d}", "question": f"fixture question {i}", "answer": f"answer {i}"}
    base.update(kw)
    return json.dumps(base, ensure_ascii=False).encode("utf-8")

def build_fixtures() -> list[dict]:
    fx = []
    def add(name, payload: bytes, complete, tail, expect, note):
        fx.append({"file": name, "payload": payload, "complete_lines": complete,
                   "tail": tail, "expect": expect, "note": note})

    add("valid-3.jsonl",
        b"\n".join([_rec(1), _rec(2), _rec(3)]) + b"\n", 3, "none",
        {"consumed": 3, "tail_consumed": False, "parse_errors": 0},
        "baseline: every line terminated")

    add("no-trailing-newline.jsonl",
        _rec(1) + b"\n" + _rec(2) + b"\n" + _rec(3), 2, "unterminated",
        {"consumed": 2, "tail_consumed": False, "parse_errors": 0},
        "T03: last line lacks \\n — reader must NOT consume it; offset stays at byte after line2 \\n")

    add("utf8-mid-char-cut.jsonl",
        _rec(1) + b"\n" + _rec(2, answer="잘린 응답 한")[:-1] , 1, "mid_utf8",
        {"consumed": 1, "tail_consumed": False, "parse_errors": 0},
        "tail ends mid-UTF8-sequence; strict decode must fail OR tail must be left unread")

    add("blank-lines.jsonl",
        _rec(1) + b"\n\n\n" + _rec(2) + b"\n", 2, "none",
        {"consumed": 2, "tail_consumed": False, "parse_errors": 0},
        "blank lines are complete lines; reader should skip them without error")

    add("broken-json-mid.jsonl",
        _rec(1) + b"\n" + b'{"id":"fx-zz","question":' + b"\n" + _rec(3) + b"\n", 3, "none",
        {"consumed": 3, "tail_consumed": False, "parse_errors": 1},
        "one malformed record mid-file; it is still a *terminated* line (byte boundary) — policy decides skip vs abort")

    add("long-line.jsonl",
        _rec(1) + b"\n" + _rec(2, answer="x" * 200_000) + b"\n", 2, "none",
        {"consumed": 2, "tail_consumed": False, "parse_errors": 0},
        "200KB single line — no silent truncation")

    add("bom.jsonl",
        b"\xef\xbb\xbf" + _rec(1) + b"\n" + _rec(2) + b"\n", 2, "none",
        {"consumed": 2, "tail_consumed": False, "parse_errors": 0},
        "UTF-8 BOM — first record id must not absorb BOM bytes")

    add("crlf-mixed.jsonl",
        _rec(1) + b"\r\n" + _rec(2) + b"\n" + _rec(3) + b"\r\n", 3, "none",
        {"consumed": 3, "tail_consumed": False, "parse_errors": 0},
        "mixed CRLF/LF — \\r belongs to the line; parsers must strip it")

    add("empty.jsonl", b"", 0, "none",
        {"consumed": 0, "tail_consumed": False, "parse_errors": 0},
        "zero bytes — offset must remain unchanged")

    add("duplicate-ids.jsonl",
        _rec(1, id="dup-1") + b"\n" + _rec(2, id="dup-1") + b"\n" + _rec(3) + b"\n", 3, "none",
        {"consumed": 3, "tail_consumed": False, "parse_errors": 0},
        "same id twice — ledger audit must flag duplicate ids")

    add("trailing-cr-only.jsonl",
        _rec(1) + b"\n" + _rec(2) + b"\r", 1, "cr_only",
        {"consumed": 1, "tail_consumed": False, "parse_errors": 0},
        "file ends with bare \\r (no \\n) — tail must not be consumed")

    add("checkpoint-ahead.jsonl",
        _rec(1) + b"\n" + _rec(2) + b"\n" + _rec(3), 2, "unterminated",
        {"consumed": 2, "tail_consumed": False, "parse_errors": 0},
        "paired with state-ahead manifest entry: durable end < checkpoint offset")

    return fx

def main() -> int:
    ap = argparse.ArgumentParser(description="Generate JSONL boundary fixtures")
    ap.add_argument("--root", default=".", help="repo root")
    ap.add_argument("--out", default=None, help="override output dir")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    out = Path(a.out).resolve() if a.out else root / OUT_SUB
    out.mkdir(parents=True, exist_ok=True)

    entries = []
    for f in build_fixtures():
        p = out / f["file"]
        p.write_bytes(f["payload"])
        entries.append({
            "file": f["file"], "bytes": len(f["payload"]),
            "sha256": hashlib.sha256(f["payload"]).hexdigest(),
            "complete_lines": f["complete_lines"], "tail": f["tail"],
            "expect": f["expect"], "note": f["note"],
        })
    manifest = {"tool": "p6dbg_jsonl_boundary_fixtures", "fixture_dir": str(out),
                "count": len(entries), "entries": entries}
    (out / "fixtures-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    result = {"status": "PASS", "written": len(entries), "out": str(out),
              "manifest": str(out / "fixtures-manifest.json")}
    print(json.dumps(result, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    sys.exit(main())
