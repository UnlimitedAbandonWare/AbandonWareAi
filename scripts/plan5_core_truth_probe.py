#!/usr/bin/env python3
"""plan5_core_truth_probe.py - read-only static probe for PLAN5 P1 fixes.

For each item P1..P9 emits FIXED_HINT | STILL_PRESENT | UNKNOWN plus
file:line evidence. This is STATIC_HINT evidence only - it can show a fix
is absent (STILL_PRESENT) or plausibly present (FIXED_HINT); it never
proves tests are GREEN. Never prints file content; only path:line and
symbolic signal names.

Usage:
    python -B scripts/plan5_core_truth_probe.py [--root .] [--json] [--out P]

Exit 0 = probe ran (statuses are data). Exit 2 = usage/io error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.plan5-core-truth-probe.v1"

F_SPEC = "main/java/com/example/lms/plan/PlanExecutionSpec.java"
F_ORCH = "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"
F_NARROW = "main/java/com/example/lms/service/rag/overdrive/AngerOverdriveNarrower.java"
F_SIGNAL = "main/java/com/example/lms/api/ChatStreamSignalBuilder.java"
F_TRACELOG = "main/java/com/example/lms/trace/TraceLogger.java"
F_INGEST = "main/java/com/example/lms/conversation/archive/ConversationArchiveIngestService.java"
F_DBQ = "main/java/com/example/lms/api/MetaDisplayDbQueryController.java"


def _read(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def _line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, max(0, idx)) + 1


def _paren_span(text: str, open_idx: int) -> int:
    """Return index just past the matching close paren, or -1."""
    depth = 0
    for i in range(open_idx, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def _brace_span(text: str, open_idx: int) -> int:
    """Return index just past the matching close brace, or -1."""
    depth = 0
    for i in range(open_idx, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def _method_body(text: str, name: str) -> tuple[int, str] | None:
    """Find `<ret> name(` definition, return (start_idx, body)."""
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", text):
        sig_start = m.start()
        prefix = text[max(0, sig_start - 200):sig_start]
        if not re.search(r"(?:private|public|protected|static|final|synchronized|"
                         r"[\w<>\[\].,\s])\s*$", prefix):
            continue
        # skip call sites: a definition is followed by `{` or `throws ... {`
        pend = _paren_span(text, m.end() - 1)
        if pend < 0:
            continue
        tail = text[pend:pend + 200]
        tm = re.match(r"\s*(?:throws\s+[\w.,\s]+?)?\{", tail)
        if not tm:
            continue
        bstart = pend + tm.end() - 1
        bend = _brace_span(text, bstart)
        if bend < 0:
            continue
        return sig_start, text[sig_start:bend]
    return None


def _evidence(file: str, line: int | None, signal: str) -> dict:
    ev = {"file": file, "signal": signal}
    if line is not None:
        ev["line"] = line
    return ev


def _item(item_id: str, status: str, evidence: list[dict]) -> dict:
    return {"id": item_id, "status": status, "kind": "STATIC_HINT",
            "evidence": evidence}


# ---------------------------------------------------------------- P1
def probe_p1(root: Path) -> dict:
    """RETRIEVAL branch: missing_* handling or SKIPPED_DEPENDENCY present."""
    text = _read(root / F_SPEC)
    if text is None:
        return _item("P1", "UNKNOWN", [_evidence(F_SPEC, None, "file-unreadable")])
    m = re.search(r"case\s+RETRIEVAL\s*->", text)
    if not m:
        return _item("P1", "UNKNOWN", [_evidence(F_SPEC, None, "case-retrieval-absent")])
    nxt = re.search(r"\n\s*case\s+\w+\s*->|\n\s*default\s*->", text[m.end():])
    block_end = m.end() + nxt.start() if nxt else m.end() + 3000
    block = text[m.start():block_end]
    line = _line_of(text, m.start())
    if "SKIPPED_DEPENDENCY" in block:
        return _item("P1", "FIXED_HINT",
                     [_evidence(F_SPEC, line, "skipped-dependency-in-retrieval")])
    if re.search(r"missing_|missing\",|startsWith\(\s*\"missing", block):
        return _item("P1", "FIXED_HINT",
                     [_evidence(F_SPEC, line, "missing-marker-handled-in-retrieval")])
    return _item("P1", "STILL_PRESENT",
                 [_evidence(F_SPEC, line, "retrieval-case-no-missing-handling")])


# ---------------------------------------------------------------- P2
def probe_p2(root: Path) -> dict:
    """BM25 failure must record a 'failed' marker, not collapse to empty."""
    text = _read(root / F_ORCH)
    if text is None:
        return _item("P2", "UNKNOWN", [_evidence(F_ORCH, None, "file-unreadable")])
    hits = []
    for m in re.finditer(r"stage\.bm25[.\w]*", text):
        hits.append(m.start())
    body = _method_body(text, "toDocsOrEmpty")
    fail_in_body = False
    body_line = None
    if body:
        bstart, btext = body
        body_line = _line_of(text, bstart)
        # marker write or quoted fail-token inside the helper (a bare
        # "retrieval failed" warn literal does NOT count - only state writes)
        if re.search(r"(?:put|putIfAbsent)\s*\([^;]{0,160}fail", btext) or \
                re.search(r'"[a-zA-Z_.-]*fail\w*"', btext):
            fail_in_body = True
    fail_marker_lines = []
    for pos in hits:
        region = text[pos:pos + 240]
        if re.search(r"fail", region):
            fail_marker_lines.append(_line_of(text, pos))
    if fail_marker_lines or fail_in_body:
        ev = [_evidence(F_ORCH, ln, "bm25-failed-marker") for ln in fail_marker_lines[:3]]
        if fail_in_body:
            ev.append(_evidence(F_ORCH, body_line, "toDocsOrEmpty-failure-signal"))
        return _item("P2", "FIXED_HINT", ev)
    if hits or body:
        ev = [_evidence(F_ORCH, _line_of(text, hits[0]), "stage-bm25-markers-no-fail")] if hits else []
        if body:
            ev.append(_evidence(F_ORCH, body_line, "toDocsOrEmpty-swallow-to-empty"))
        return _item("P2", "STILL_PRESENT", ev)
    return _item("P2", "UNKNOWN", [_evidence(F_ORCH, None, "bm25-path-not-found")])


# ---------------------------------------------------------------- P3
def probe_p3(root: Path) -> dict:
    """@Autowired ctor ObjectProvider param needs @Qualifier(\"crossEncoderReranker\")."""
    text = _read(root / F_NARROW)
    if text is None:
        return _item("P3", "UNKNOWN", [_evidence(F_NARROW, None, "file-unreadable")])
    am = list(re.finditer(r"@Autowired\b", text))
    ctor = re.search(r"\bAngerOverdriveNarrower\s*\(", text)
    if not am or not ctor:
        return _item("P3", "UNKNOWN", [_evidence(F_NARROW, None, "autowired-ctor-absent")])
    # ctor following an @Autowired annotation
    target = None
    for a in am:
        m2 = re.search(r"[\w<>?,\s@\"().]*?AngerOverdriveNarrower\s*\(",
                       text[a.end():a.end() + 600], re.S)
        if m2:
            paren_at = a.end() + m2.end() - 1
            target = (a.start(), paren_at)
            break
    if not target:
        return _item("P3", "UNKNOWN", [_evidence(F_NARROW, None, "autowired-ctor-absent")])
    line = _line_of(text, target[0])
    pend = _paren_span(text, target[1])
    if pend < 0:
        return _item("P3", "UNKNOWN", [_evidence(F_NARROW, line, "ctor-params-unparsed")])
    params = text[target[1]:pend]
    if "ObjectProvider" not in params:
        return _item("P3", "UNKNOWN",
                     [_evidence(F_NARROW, line, "objectprovider-param-absent")])
    if "@Qualifier" in params:
        return _item("P3", "FIXED_HINT",
                     [_evidence(F_NARROW, line, "qualifier-on-provider-param")])
    return _item("P3", "STILL_PRESENT",
                 [_evidence(F_NARROW, line, "provider-param-no-qualifier")])


# ---------------------------------------------------------------- P4
def probe_p4(root: Path) -> dict:
    """traceNarrow must not write reason into anchor.error on success."""
    text = _read(root / F_NARROW)
    if text is None:
        return _item("P4", "UNKNOWN", [_evidence(F_NARROW, None, "file-unreadable")])
    body = _method_body(text, "traceNarrow")
    if not body:
        return _item("P4", "UNKNOWN", [_evidence(F_NARROW, None, "traceNarrow-absent")])
    bstart, btext = body
    put = re.search(r"anchor\.error\"", btext)
    if not put:
        return _item("P4", "UNKNOWN",
                     [_evidence(F_NARROW, _line_of(text, bstart), "anchor-error-put-absent")])
    line = _line_of(text, bstart + put.start())
    put_stmt = btext[put.start():put.start() + 200]
    stmt_end = put_stmt.find(";")
    put_stmt = put_stmt[:stmt_end + 1] if stmt_end >= 0 else put_stmt
    if "failSoft" in put_stmt:
        return _item("P4", "FIXED_HINT",
                     [_evidence(F_NARROW, line, "anchor-error-gated-by-failsoft")])
    value_part = put_stmt.split(",", 1)[-1] if "," in put_stmt else ""
    if value_part and "reason" not in value_part.lower():
        return _item("P4", "FIXED_HINT",
                     [_evidence(F_NARROW, line, "anchor-error-no-reason-value")])
    before = btext[max(0, put.start() - 300):put.start()]
    if re.search(r"if\s*\(\s*failSoft\s*\)\s*\{[^{}]*$", before, re.S):
        return _item("P4", "FIXED_HINT",
                     [_evidence(F_NARROW, line, "anchor-error-inside-failsoft-if")])
    return _item("P4", "STILL_PRESENT",
                 [_evidence(F_NARROW, line, "anchor-error-unconditional")])


# ---------------------------------------------------------------- P5
def probe_p5(root: Path) -> dict:
    """anchorStatus must consult a failSoft signal."""
    text = _read(root / F_SIGNAL)
    if text is None:
        return _item("P5", "UNKNOWN", [_evidence(F_SIGNAL, None, "file-unreadable")])
    body = _method_body(text, "anchorStatus")
    if not body:
        return _item("P5", "UNKNOWN", [_evidence(F_SIGNAL, None, "anchorStatus-absent")])
    bstart, btext = body
    line = _line_of(text, bstart)
    if re.search(r"failSoft|fail_soft|fail-soft", btext):
        return _item("P5", "FIXED_HINT",
                     [_evidence(F_SIGNAL, line, "anchorStatus-references-failsoft")])
    return _item("P5", "STILL_PRESENT",
                 [_evidence(F_SIGNAL, line, "anchorStatus-no-failsoft")])


# ---------------------------------------------------------------- P6
_TRACE_KEYS = re.compile(r"lms\.trace\.(enabled|sample|preview)")
# Spring-side read of the keys (@Value / Environment.getProperty / Binder /
# ConfigurationProperties). System.getProperty does NOT match: it needs a
# non-"System." receiver or an annotation form.
_SPRING_READ = re.compile(r"@Value\b|@PostConstruct|@ConfigurationProperties|"
                          r"@Bean\b|\bEnvironment\b|\bBinder\b|"
                          r"(?<!System)\.getProperty\(|getProperty\<")
_TRACE_SET = re.compile(r"TraceLogger\.(enabled|sample|PREVIEW)\s*="
                        r"|TraceLogger\.set[A-Z]\w*\(")


def probe_p6(root: Path) -> dict:
    """TraceLogger values must be applied from Spring Environment."""
    text = _read(root / F_TRACELOG)
    if text is None:
        return _item("P6", "UNKNOWN", [_evidence(F_TRACELOG, None, "file-unreadable")])
    ev = [_evidence(F_TRACELOG, _line_of(text, m.start()), "system-property-read")
          for m in re.finditer(r"System\.getProperty\(\s*\"lms\.trace\.", text)]
    # (a) env wiring inside TraceLogger itself
    for m in _SPRING_READ.finditer(text):
        ln = _line_of(text, m.start())
        if ln not in {e["line"] for e in ev}:
            return _item("P6", "FIXED_HINT",
                         [_evidence(F_TRACELOG, ln, "env-wiring-in-tracelogger")])
    # (b) another file reads lms.trace.* via Spring and writes into TraceLogger
    java_root = root / "main/java"
    if java_root.is_dir():
        for jf in sorted(java_root.rglob("*.java")):
            if jf.as_posix().endswith("trace/TraceLogger.java"):
                continue
            try:
                other = jf.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if not _TRACE_KEYS.search(other):
                continue
            sm = _TRACE_SET.search(other)
            if sm and (_SPRING_READ.search(other) or "Environment" in other):
                rel = jf.relative_to(root).as_posix()
                return _item("P6", "FIXED_HINT",
                             [_evidence(rel, _line_of(other, sm.start()),
                                        "external-env-applier")])
    if ev:
        return _item("P6", "STILL_PRESENT", ev[:3])
    return _item("P6", "UNKNOWN", [_evidence(F_TRACELOG, None, "no-lms-trace-reads")])


# ---------------------------------------------------------------- P7
def probe_p7(root: Path) -> dict:
    """Ingest must count via enqueueWithReceipt, not blind ingestedCount++."""
    text = _read(root / F_INGEST)
    if text is None:
        return _item("P7", "UNKNOWN", [_evidence(F_INGEST, None, "file-unreadable")])
    if "enqueueWithReceipt" in text:
        m = re.search(r"enqueueWithReceipt", text)
        return _item("P7", "FIXED_HINT",
                     [_evidence(F_INGEST, _line_of(text, m.start()), "enqueueWithReceipt-used")])
    m = re.search(r"\.enqueue\(", text)
    if m:
        return _item("P7", "STILL_PRESENT",
                     [_evidence(F_INGEST, _line_of(text, m.start()), "bare-enqueue-counted")])
    return _item("P7", "UNKNOWN", [_evidence(F_INGEST, None, "no-enqueue-call")])


# ---------------------------------------------------------------- P8
def probe_p8(root: Path) -> dict:
    """readOnlyConnection must close the connection when setReadOnly fails."""
    text = _read(root / F_DBQ)
    if text is None:
        return _item("P8", "UNKNOWN", [_evidence(F_DBQ, None, "file-unreadable")])
    body = _method_body(text, "readOnlyConnection")
    if not body:
        return _item("P8", "UNKNOWN", [_evidence(F_DBQ, None, "readOnlyConnection-absent")])
    bstart, btext = body
    line = _line_of(text, bstart)
    has_catch = re.search(r"\bcatch\s*\(", btext)
    has_close = re.search(r"\.close\s*\(", btext) or "try (" in btext or "try(" in btext
    if has_catch and has_close:
        return _item("P8", "FIXED_HINT",
                     [_evidence(F_DBQ, line, "catch-close-present")])
    return _item("P8", "STILL_PRESENT",
                 [_evidence(F_DBQ, line, "setReadOnly-unguarded")])


# ---------------------------------------------------------------- P9
def probe_p9(root: Path) -> dict:
    """UnifiedRagOrchestrator must contain zero NUL (0x00) bytes."""
    path = root / F_ORCH
    try:
        data = path.read_bytes()
    except OSError:
        return _item("P9", "UNKNOWN", [_evidence(F_ORCH, None, "file-unreadable")])
    idx = data.find(b"\x00")
    if idx < 0:
        return _item("P9", "FIXED_HINT", [_evidence(F_ORCH, None, "nul-bytes-0")])
    count = data.count(b"\x00")
    line = data.count(b"\n", 0, idx) + 1
    return _item("P9", "STILL_PRESENT",
                 [_evidence(F_ORCH, line, f"nul-bytes-{count}")])


ITEMS = [("P1", probe_p1), ("P2", probe_p2), ("P3", probe_p3),
         ("P4", probe_p4), ("P5", probe_p5), ("P6", probe_p6),
         ("P7", probe_p7), ("P8", probe_p8), ("P9", probe_p9)]


def probe_all(root: Path) -> dict:
    items = [fn(root) for _id, fn in ITEMS]
    summary = {it["id"]: it["status"] for it in items}
    return {"schemaVersion": SCHEMA, "kind": "STATIC_HINT",
            "note": "STATIC_HINT != GREEN: presence/absence of source patterns only",
            "root": str(root), "items": items, "summary": summary}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--out", help="write JSON result to this path")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"INPUT_ERROR root-not-dir:{args.root}", file=sys.stderr)
        return 2
    result = probe_all(root)
    if args.out:
        try:
            out = Path(args.out)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
        except OSError as exc:
            print(f"OUTPUT_UNWRITABLE {type(exc).__name__}", file=sys.stderr)
            return 2
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print("kind=STATIC_HINT (source-pattern evidence, not a test verdict)")
        for it in result["items"]:
            locs = ",".join(f"{e['file']}:{e.get('line','-')}:{e['signal']}"
                            for e in it["evidence"])
            print(f"{it['id']}={it['status']} [{locs}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
