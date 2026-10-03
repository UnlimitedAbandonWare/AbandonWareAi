#!/usr/bin/env python3
"""plan5_core_truth_verify.py - one-shot verdict over Codex PLAN5 P1 evidence.

Combines: (1) junit_owned_summary over the 5 required test classes,
(2) plan5_core_truth_probe (STATIC_HINT items P1-P9), (3) source_nul_scan
(product U+0000 gate; other C0 reported as info), (4) foreign-hunk check
against a pre-Codex snapshot, (5) invariants chat.js sha256 + routing flag.

This runner NEVER executes Gradle, never edits sources, never prints file
bodies. A class missing from XML is MISSING (not PASS). Verdicts:
READY_FOR_REVIEW | NOT_READY(reasons) | INCOMPLETE_EVIDENCE(reasons).

Usage:
    python -B scripts/plan5_core_truth_verify.py --root . \
        --xml-dir build/test-results/test [--xml-dir ...] \
        --snapshot <foreign-snapshot.json> [--out result.json] [--json]

Exit 0=READY_FOR_REVIEW, 3=NOT_READY, 5=INCOMPLETE_EVIDENCE, 2=usage error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET  # noqa: F401  (kept for parity w/ tools)
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import junit_owned_summary  # noqa: E402
import plan5_core_truth_probe as probe  # noqa: E402
import source_nul_scan  # noqa: E402

SCHEMA = "awx.plan5-core-truth-verify.v1"

REQUIRED_CLASSES = [
    "com.example.lms.plan.PlanExecutionSpecTest",
    "com.example.lms.service.rag.overdrive.AngerOverdriveNarrowerTest",
    "com.example.lms.api.ChatStreamSignalBuilderTest",
    "com.example.lms.conversation.archive.ConversationArchiveIngestServiceTest",
    "com.example.lms.api.MetaDisplayDbQueryGateTest",
]

EXPECTED_CHATJS_SHA8 = "4225d944"
CHATJS = "main/resources/static/js/chat.js"
APP_PROPS = "main/resources/application.properties"
ROUTING_RE = re.compile(r"^\s*chat\.settings\.routing\.enabled\s*=\s*(\S+)",
                        re.M)


def junit_step(xml_dirs: list[str], root: Path) -> dict:
    merged: dict[str, dict] = {
        name: {"className": name, "status": "MISSING", "tests": 0,
               "failures": 0, "skipped": 0, "failedMethods": []}
        for name in REQUIRED_CLASSES}
    missing_dirs = []
    for d in xml_dirs:
        xp = (root / d) if not Path(d).is_absolute() else Path(d)
        if not xp.is_dir():
            missing_dirs.append(d)
            continue
        payload = junit_owned_summary.summarize(xp, REQUIRED_CLASSES)
        for row in payload["named"]:
            cur = merged[row["className"]]
            if row["status"] == "MISSING":
                continue
            if cur["status"] == "MISSING":
                merged[row["className"]] = dict(row)
                continue
            cur["tests"] += row["tests"]
            cur["failures"] += row["failures"]
            cur["skipped"] += row["skipped"]
            cur["failedMethods"].extend(row["failedMethods"])
            if row["status"] == "FAIL":
                cur["status"] = "FAIL"
    for row in merged.values():
        if row["status"] != "MISSING":
            row["status"] = "FAIL" if row["failures"] else "PASS"
    return {"classes": list(merged.values()), "missingDirs": missing_dirs,
            "xmlDirs": xml_dirs}


def foreign_step(root: Path, snapshot: str | None) -> dict:
    if not snapshot:
        return {"run": "skipped", "reason": "snapshot-not-provided"}
    tool = Path(__file__).with_name("foreign_hunk_preserve_check.py")
    proc = subprocess.run(
        [sys.executable, "-B", str(tool), "--root", str(root), "check",
         "--snapshot", snapshot, "--json"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=300)
    if proc.returncode == 2:
        return {"run": "error", "exit": 2,
                "stderr": (proc.stderr or "").strip()[:200]}
    try:
        payload = json.loads((proc.stdout or "").strip().splitlines()[-1])
    except (ValueError, IndexError):
        return {"run": "error", "exit": proc.returncode,
                "stderr": "unparsable-output"}
    return {"run": "done", "exit": proc.returncode,
            "lost": payload.get("lost") or [],
            "files": payload.get("files", 0)}


def invariants_step(root: Path,
                    expected_sha8: str = EXPECTED_CHATJS_SHA8) -> dict:
    out = {"expectedChatJsSha8": expected_sha8}
    chatjs = root / CHATJS
    try:
        digest = hashlib.sha256(chatjs.read_bytes()).hexdigest()
        out["chatJsSha8"] = digest[:8]
        out["chatJsOk"] = digest[:8].lower() == expected_sha8.lower()
    except OSError:
        out["chatJsSha8"] = None
        out["chatJsOk"] = False
    props = root / APP_PROPS
    try:
        text = props.read_text(encoding="utf-8", errors="replace")
        m = ROUTING_RE.search(text)
        out["routingFlag"] = m.group(1) if m else "absent"
        out["routingLine"] = text[:m.start()].count("\n") + 1 if m else None
        out["routingOk"] = bool(m) and m.group(1).lower() == "false"
    except OSError:
        out["routingFlag"] = "unreadable"
        out["routingOk"] = False
    return out


def nul_step(root: Path) -> dict:
    res = source_nul_scan.scan_root(root, list(source_nul_scan.DEFAULT_DIRS))
    blocking = [f for f in res["findings"] if f["codepoint"] == "U+0000"]
    other = [f for f in res["findings"] if f["codepoint"] != "U+0000"]
    return {"verdict": res["verdict"], "filesScanned": res["filesScanned"],
            "blockingNul": blocking, "otherControlFindings": other,
            "note": "gate = U+0000 only; other C0 findings are informational"}


def parse_allow_lost_hunk(raw: str) -> tuple[str, str] | None:
    """`--allow-lost-hunk <path>:<header>` → (path, header). 첫 ':' 기준만 나눈다."""
    if not raw or ":" not in raw:
        return None
    path, header = raw.split(":", 1)
    if not path.strip() or not header.strip():
        return None
    return path.strip(), header.strip()


def run_verification(root: Path, xml_dirs: list[str],
                     snapshot: str | None,
                     expected_chatjs_sha8: str = EXPECTED_CHATJS_SHA8,
                     allow_lost_hunks: list[str] | None = None) -> dict:
    incomplete: list[str] = []
    not_ready: list[str] = []

    junit = junit_step(xml_dirs, root)
    for d in junit["missingDirs"]:
        incomplete.append(f"xml-dir-missing:{d}")
    if not xml_dirs:
        incomplete.append("xml-dir-not-provided")
    for row in junit["classes"]:
        if row["status"] == "MISSING":
            incomplete.append(f"class-missing:{row['className']}")
        elif row["status"] == "FAIL":
            not_ready.append(f"class-fail:{row['className']}"
                             f"({','.join(row['failedMethods'][:5])})")

    probe_res = probe.probe_all(root)
    for it in probe_res["items"]:
        if it["status"] == "STILL_PRESENT":
            not_ready.append(f"probe-{it['id']}-still-present")
        elif it["status"] == "UNKNOWN":
            incomplete.append(f"probe-{it['id']}-unknown")

    nul = nul_step(root)
    for f in nul["blockingNul"]:
        not_ready.append(f"nul-found:{f['path']}:{f['line']}")

    foreign = foreign_step(root, snapshot)
    if foreign["run"] == "skipped":
        incomplete.append("foreign-snapshot-not-provided")
    elif foreign["run"] == "error":
        incomplete.append("foreign-check-error")
    elif foreign["lost"]:
        allowed = {pair for pair in
                   (parse_allow_lost_hunk(a) for a in (allow_lost_hunks or []))
                   if pair}
        intended: list[dict] = []
        still_lost: list[dict] = []
        for row in foreign["lost"]:
            if (row.get("path"), row.get("header")) in allowed:
                intended.append(row)
                continue
            still_lost.append(row)
            not_ready.append(f"foreign-hunk-lost:{row['path']}:{row['header']}")
        foreign["lost"] = still_lost
        foreign["intendedReplacements"] = intended

    inv = invariants_step(root, expected_chatjs_sha8)
    if not inv["chatJsOk"]:
        not_ready.append("chatjs-sha-mismatch" if inv["chatJsSha8"]
                         else "chatjs-unreadable")
    if not inv["routingOk"]:
        if inv["routingFlag"] == "unreadable":
            incomplete.append("application.properties-unreadable")
        else:
            not_ready.append(f"routing-flag-not-false:{inv['routingFlag']}")

    if incomplete:
        verdict = "INCOMPLETE_EVIDENCE"
    elif not_ready:
        verdict = "NOT_READY"
    else:
        verdict = "READY_FOR_REVIEW"
    return {"schemaVersion": SCHEMA, "verdict": verdict,
            "incompleteReasons": sorted(incomplete),
            "notReadyReasons": sorted(not_ready),
            "junit": junit, "probe": probe_res["summary"],
            "nulScan": nul, "foreignHunks": foreign, "invariants": inv,
            "note": "STATIC_HINT items never prove GREEN; JUnit GREEN + "
                    "invariants decide READY_FOR_REVIEW"}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--xml-dir", dest="xml_dirs", action="append",
                    default=[], help="JUnit XML dir (repeatable)")
    ap.add_argument("--snapshot", default=None,
                    help="foreign-snapshot.json from DV1")
    ap.add_argument("--out", help="write JSON verdict to this path")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--expect-chatjs-sha8", default=EXPECTED_CHATJS_SHA8,
                    help="expected chat.js sha256 first 8 hex (contract "
                         "default 4225d944)")
    ap.add_argument("--allow-lost-hunk", dest="allow_lost_hunks",
                    action="append", default=[],
                    help="<path>:<hunk header> — 의도된 교체로 간주해 "
                         "foreign-hunk-lost 판정에서 제외(반복 가능, "
                         "기본 동작 불변)")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"INPUT_ERROR root-not-dir:{args.root}", file=sys.stderr)
        return 2
    bad_allow = [a for a in args.allow_lost_hunks
                 if parse_allow_lost_hunk(a) is None]
    if bad_allow:
        print(f"INPUT_ERROR bad --allow-lost-hunk (need <path>:<header>): "
              f"{bad_allow[0]}", file=sys.stderr)
        return 2
    result = run_verification(root, args.xml_dirs, args.snapshot,
                              args.expect_chatjs_sha8,
                              args.allow_lost_hunks)
    if args.out:
        try:
            out = Path(args.out)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(result, ensure_ascii=False, indent=2)
                           + "\n", encoding="utf-8")
        except OSError as exc:
            print(f"OUTPUT_UNWRITABLE {type(exc).__name__}", file=sys.stderr)
            return 2
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print(f"verdict={result['verdict']}")
        for r in result["notReadyReasons"]:
            print(f"  NOT_READY {r}")
        for r in result["incompleteReasons"]:
            print(f"  INCOMPLETE {r}")
        for row in result["junit"]["classes"]:
            print(f"  junit {row['className']}={row['status']} "
                  f"tests={row['tests']} failures={row['failures']} "
                  f"skipped={row['skipped']}")
        for k, v in result["probe"].items():
            print(f"  probe {k}={v}")
        print(f"  nulBlocking={len(result['nulScan']['blockingNul'])} "
              f"otherC0={len(result['nulScan']['otherControlFindings'])}")
        print(f"  foreignHunks={result['foreignHunks']['run']} "
              f"chatJsOk={result['invariants']['chatJsOk']} "
              f"routing={result['invariants']['routingFlag']}")
    return {"READY_FOR_REVIEW": 0, "NOT_READY": 3,
            "INCOMPLETE_EVIDENCE": 5}[result["verdict"]]


if __name__ == "__main__":
    raise SystemExit(main())
