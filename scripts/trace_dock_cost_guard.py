#!/usr/bin/env python3
"""trace_dock_cost_guard.py — Trace dock always-on F09 비용 함정 정적 회귀 가드.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.5.

스캔 대상 (읽기 전용 — JS 수정 금지):
  - chat-trace-ui.js: enabled() / withDebugQuery — visible ON이 debug=true 를
    강제하는지 (분리 불가 결합)
  - chat.js: chatTraceRequestUrl 경유로 생성 요청 URL(/api/chat/stream|sync)이
    withDebugQuery 를 타는지 + always-on dock 후보가 markChatDiagnosticNode 로
    라우팅되는지

FAIL (exit 2):
  - withDebugQuery 본문이 enabled() 를 호출하고 debug=true 를 붙임
    (UI visible ON → debug=true 결합 = F09 함정 회귀)
  - 생성 요청 URL 조립이 chatTraceRequestUrl/withDebugQuery 를 경유
  - trace-dock-* 셀렉터가 markChatDiagnosticNode 에 전달됨

PASS (exit 0): 위 결합 부재 — 분리 증거(별 함수/가드/주석 앵커).
PARTIAL (exit 3): 필수 소스 읽기 불가.
--baseline-allow-fail: FAIL 을 expected_pre_patch 로 기록하고 exit 0.
--dock-contract checks the poll contract. It misses dynamic calls, concatenated or eval URLs, and files outside main/resources js/html/css.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
SCHEMA = "awx.trace-dock-cost-guard.v1"
DEFAULT_OUT_DIR = "data/diagnostics/f01b-trace-access-0929"
DEFAULT_JS_TRACE = "main/resources/static/js/chat-trace-ui.js"
DEFAULT_JS_CHAT = "main/resources/static/js/chat.js"

GENERATION_URL = re.compile(r"/api/chat/(?:stream|sync)")
DOCK_SELECTOR = re.compile(r"trace-dock-[a-z-]+")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def extract_function_body(src: str, name: str):
    """`function name(...) { ... }` 본문을 중괄호 깊이 추적으로 추출."""
    m = re.search(r"function\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    start = src.find("{", m.end() - 1)
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(src)):
        ch = src[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1], m.start(), i + 1
    return None


def line_of(src: str, offset: int) -> int:
    return src.count("\n", 0, offset) + 1


def mask_snippet(line: str) -> str:
    """한 줄 스니펫 — 문자열 리터럴 내용은 … 로 마스킹 (코드만 기록)."""
    text = line.strip()[:160]
    return re.sub(r"(['\"`])(?:\\.|(?!\1).)*\1", '"…"', text)


def scan(root: Path, js_trace: str, js_chat: str) -> dict:
    findings = []
    coupling = []
    missing = []

    trace_path = root / js_trace
    chat_path = root / js_chat
    trace_src = chat_src = None
    for path, label in ((trace_path, js_trace), (chat_path, js_chat)):
        if not path.is_file():
            missing.append(label)
    if trace_path.is_file():
        trace_src = trace_path.read_text(encoding="utf-8", errors="replace")
    if chat_path.is_file():
        chat_src = chat_path.read_text(encoding="utf-8", errors="replace")

    # 1) withDebugQuery 가 enabled() 를 게이트로 debug=true 를 붙이는가
    if trace_src is not None:
        wq = extract_function_body(trace_src, "withDebugQuery")
        en = extract_function_body(trace_src, "enabled")
        if wq:
            body, fn_start, _ = wq
            calls_enabled = bool(re.search(r"\benabled\s*\(", body))
            appends_debug = "debug=true" in body or "debug=" in body
            enabled_reads_visible = bool(
                en and re.search(r"chat-trace-toggle|checked", en[0]))
            if calls_enabled and appends_debug and enabled_reads_visible:
                findings.append({
                    "kind": "VISIBLE_ON_FORCES_DEBUG_TRUE",
                    "file": js_trace,
                    "line": line_of(trace_src, fn_start),
                    "detail": "withDebugQuery gates debug=true on enabled() "
                              "which reads the visible toggle",
                })
                coupling.append({"file": js_trace,
                                 "line": line_of(trace_src, fn_start),
                                 "snippetMasked": mask_snippet(
                                     trace_src.splitlines()[
                                         line_of(trace_src, fn_start) - 1])})
        else:
            findings.append({"kind": "WITH_DEBUG_QUERY_ABSENT",
                             "file": js_trace, "detail": "function not found"})

    if chat_src is not None:
        lines = chat_src.splitlines()
        # 2) 생성 요청 URL 조립의 debug 경유
        for i, line in enumerate(lines, 1):
            if ("chatTraceRequestUrl(" in line or "withDebugQuery(" in line):
                window = "\n".join(lines[i - 1:i + 4])
                if GENERATION_URL.search(window) or GENERATION_URL.search(line):
                    findings.append({
                        "kind": "GENERATION_URL_VIA_DEBUG_QUERY",
                        "file": js_chat, "line": i,
                        "detail": "generation request URL assembled through "
                                  "chatTraceRequestUrl/withDebugQuery",
                    })
                    coupling.append({"file": js_chat, "line": i,
                                     "snippetMasked": mask_snippet(line)})
        # 3) always-on dock 후보가 markChatDiagnosticNode 로 라우팅되는가
        for i, line in enumerate(lines, 1):
            if "markChatDiagnosticNode(" not in line:
                continue
            # 호출 인자가 앞줄의 변수에 담기는 경우까지 커버 (±2줄 문맥)
            context = "\n".join(lines[max(0, i - 3):i + 1])
            if DOCK_SELECTOR.search(context):
                findings.append({
                    "kind": "DOCK_ROUTED_VIA_DIAGNOSTIC_NODE",
                    "file": js_chat, "line": i,
                    "detail": "trace-dock selector passed to "
                              "markChatDiagnosticNode (aria-hidden trap)",
                })
                coupling.append({"file": js_chat, "line": i,
                                 "snippetMasked": mask_snippet(line)})

    return {"findings": findings, "couplingSites": coupling,
            "missing": missing}



DOCK_TESTIDS = (
    "trace-dock-toggle",
    "trace-dock-current",
    "trace-dock-history",
)
DOCK_RESOURCE_SUFFIXES = {".js", ".html", ".css"}
DOCK_PROXIMITY = 8
DOCK_BLIND_SPOTS = (
    "dynamic calls",
    "concatenated or eval URLs",
    "files outside main/resources js/html/css",
)
DOCK_HASH_LITERAL = "hash:[0-9a-f]{12}"


def iter_dock_resources(root: Path):
    base = root / "main" / "resources"
    if not base.is_dir():
        return
    for path in base.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in DOCK_RESOURCE_SUFFIXES:
            continue
        parts = {part.lower() for part in path.parts}
        if "node_modules" in parts or "build" in parts:
            continue
        yield path


def scan_dock_contract(root: Path) -> dict:
    present = {name: [] for name in DOCK_TESTIDS}
    texts = []
    for path in iter_dock_resources(root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        rel = path.relative_to(root).as_posix()
        texts.append((rel, text))
        for name in DOCK_TESTIDS:
            if name in text:
                present[name].append(rel)
    counts = {name: len(paths) for name, paths in present.items()}
    base = {
        "present": counts,
        "mode": "dock-contract",
        "blindSpots": list(DOCK_BLIND_SPOTS),
    }
    if not any(counts.values()):
        return {**base, "verdict": "PARTIAL", "exitCode": 3,
                "reason": "SELECTORS_ABSENT_PRE_PATCH", "findings": []}
    findings = []
    missing = [name for name, count in counts.items() if count == 0]
    if missing:
        findings.append({"kind": "TESTID_INCOMPLETE", "missing": missing})
    forbidden = (
        ("EVENT_SOURCE", re.compile(r"\bEventSource\b")),
        ("DEBUG_EVENTS_STREAM", re.compile(r"/debug/events/stream|/events/stream")),
        ("CHAT_STREAM", re.compile(r"/api/chat/stream")),
        ("CHAT_SYNC", re.compile(r"/api/chat/sync")),
        ("DEBUG_TRUE", re.compile(r"debug=true")),
    )
    scoped = []
    for rel, text in texts:
        if "trace-dock" not in text:
            continue
        lines = text.splitlines()
        if rel.endswith("/js/chat.js"):
            anchors = [i for i, line in enumerate(lines) if "trace-dock" in line]
            chosen = set()
            for anchor in anchors:
                start = max(0, anchor - DOCK_PROXIMITY)
                end = min(len(lines), anchor + DOCK_PROXIMITY + 1)
                chosen.update(range(start, end))
            indexed = [(i, lines[i]) for i in sorted(chosen)]
        else:
            indexed = list(enumerate(lines))
        scoped.append("\n".join(line for _, line in indexed))
        for index, line in indexed:
            for kind, pattern in forbidden:
                if pattern.search(line):
                    findings.append({"kind": kind, "file": rel, "line": index + 1})
    blob = "\n".join(scoped)
    requirements = (
        ("events_page", re.compile(r"/events/page")),
        ("limit_50", re.compile(r"limit\s*[=:]\s*50")),
        ("status_410", re.compile(r"\b410\b")),
        ("visibilitychange", re.compile(r"visibilitychange")),
    )
    for name, pattern in requirements:
        if pattern.search(blob) is None:
            findings.append({"kind": "REQUIREMENT_ABSENT", "requirement": name})
    if DOCK_HASH_LITERAL not in blob:
        findings.append({"kind": "REQUIREMENT_ABSENT", "requirement": "hash_pattern"})
    code = 2 if findings else 0
    return {**base, "verdict": "FAIL" if code else "PASS", "exitCode": code,
            "reason": None, "findings": findings}


def emit_dock_contract(root: Path, args) -> int:
    res = scan_dock_contract(root)
    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "track": "TRACE",
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "mode": "dock-contract",
        "verdict": res["verdict"],
        "exitCode": res["exitCode"],
        "reason": res["reason"],
        "findings": res["findings"],
        "present": res["present"],
        "blindSpots": res["blindSpots"],
        "note": "literal scan only; misses dynamic calls, concatenated or eval "
                "URLs, and files outside main/resources js/html/css",
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"dock_contract verdict={res['verdict']} exit={res['exitCode']} "
              f"findings={len(res['findings'])} json={out_path}")
    return res["exitCode"]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Static F09 cost-trap guard for trace dock visible-vs-debug coupling. --dock-contract misses dynamic calls, concatenated or eval URLs, and files outside main/resources js/html/css.")
    ap.add_argument("--root", default=".")
    ap.add_argument("--js-trace", default=DEFAULT_JS_TRACE)
    ap.add_argument("--js-chat", default=DEFAULT_JS_CHAT)
    ap.add_argument("--baseline-allow-fail", action="store_true",
                    help="FAIL 을 expected_pre_patch 로 기록하고 exit 0")
    ap.add_argument("--json-out",
                    default=f"{DEFAULT_OUT_DIR}/trace_dock_cost_guard.json")
    ap.add_argument("--json", action="store_true")
    ap.add_argument(
        "--dock-contract", action="store_true",
        help="Static poll contract for dock resources. Misses dynamic calls, concatenated or eval URLs, and files outside main/resources js/html/css. Exit 3 when the three dock testids are absent.")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if args.dock_contract:
        return emit_dock_contract(root, args)
    res = scan(root, args.js_trace, args.js_chat)

    if res["missing"]:
        verdict, code = "PARTIAL", 3
    elif res["findings"]:
        hard = [f for f in res["findings"]
                if f["kind"] != "WITH_DEBUG_QUERY_ABSENT"]
        if hard:
            verdict = "FAIL"
            code = 0 if args.baseline_allow_fail else 2
        else:
            verdict, code = "PARTIAL", 3
    else:
        verdict, code = "PASS", 0

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "track": "TRACE",
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "verdict": verdict,
        "exitCode": code,
        "baselineMode": bool(args.baseline_allow_fail),
        "baselineNote": ("expected_pre_patch" if
                         (args.baseline_allow_fail and verdict == "FAIL")
                         else None),
        "findings": res["findings"],
        "couplingSites": res["couplingSites"],
        "missing": res["missing"],
        "note": "static scan only; never edits JS. FAIL pre-patch is the "
                "expected baseline until Codex decouples visible ON from "
                "debug=true",
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"cost_guard verdict={verdict} exit={code} "
              f"findings={len(res['findings'])} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
