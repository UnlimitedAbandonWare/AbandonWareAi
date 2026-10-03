#!/usr/bin/env python3
"""check_vector_checkpoint_receipt.py — durable-receipt / checkpoint consistency diagnostic.

Read-only diagnostic for the P6 T02/T03 contract: a checkpoint (saveState)
must only advance past source records whose vector write was confirmed
durable. It inspects snapshots — a checkpoint state JSON, the source JSONL,
and a batch receipts log — plus optional static source signatures. It never
modifies product files, never calls a provider, never restarts anything.

Violation rules (P6 §3/§5):
  R1 nondurable-checkpoint-advance — a receipts record with durable=false
     (reasonCode backoff | store_failure | source_rejected) whose covered
     offset range lies at or below the committed checkpoint offset.
  R2 atomic-move-residue           — `<checkpoint>.tmp` sibling left behind,
     or an unreadable/corrupt checkpoint file (loadState would silently reset
     it to empty). The saveState catch-and-ignore makes these the only trace.
  R3 unterminated-tail-committed   — the JSONL tail has no LF terminator but
     the committed offset moved past the last complete record boundary
     (or beyond end-of-file).

With --source-root the tool also reports which defect signatures remain in
the live source (informational `staticFindings`, not violations):
  - flush() return value dropped by TrainRagIngestService.upsertSegments
  - saveState catching `Exception ignore` around ATOMIC_MOVE
  - readUtf8Line returning an unterminated tail record
  - VectorFlushOutcome.durable() contract present in VectorStoreService

  python -B scripts/check_vector_checkpoint_receipt.py \
      --checkpoint var/ingest/state.json --jsonl data/train/samples.jsonl \
      --receipts var/ingest/flush-receipts.jsonl [--source-root .]

Exit 0 = no violations · 2 = violations found · 3 = usage error.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

SCHEMA = "awx.vector-checkpoint-receipt.v1"
NONDURABLE_REASONS = {"backoff", "store_failure", "source_rejected", "unknown"}

RE_FLUSH_DROPPED = re.compile(r"\bvectorStoreService\.flush\s*\(\s*\)\s*;")
RE_FLUSH_KEPT = re.compile(r"(?:=\s*|\breturn\b|\bboolean\s+\w+\s*=\s*)"
                           r"[^;\n]*vectorStoreService\.flush\s*\(")
RE_CATCH_IGNORE = re.compile(r"catch\s*\(\s*Exception\s+ignore\s*\)")
RE_ATOMIC_MOVE = re.compile(r"StandardCopyOption\.ATOMIC_MOVE")
RE_READ_LINE = re.compile(r"readUtf8Line")
RE_OUT_RETURN = re.compile(r"return\s+out\.toString")
RE_DURABLE_CONTRACT = re.compile(r"VectorFlushOutcome")


def sha12(path: Path) -> str | None:
    import hashlib
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    except OSError:
        return None


def last_complete_offset(data: bytes) -> int:
    """Byte offset just past the last LF-terminated record."""
    idx = data.rfind(b"\n")
    return idx + 1 if idx >= 0 else 0


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def scan_jsonl(path: Path, committed: int | None, violations: list) -> dict:
    info = {"path": str(path), "size": None, "lastCompleteOffset": None,
            "tailUnterminated": None}
    try:
        data = path.read_bytes()
    except OSError as exc:
        violations.append({"rule": "R3", "code": "jsonl-unreadable",
                           "detail": str(exc)[:160]})
        return info
    info["size"] = len(data)
    complete = last_complete_offset(data)
    info["lastCompleteOffset"] = complete
    info["tailUnterminated"] = len(data) > 0 and not data.endswith(b"\n")
    if committed is not None:
        if committed > len(data):
            violations.append({"rule": "R3", "code": "committed-beyond-eof",
                               "committedOffset": committed,
                               "fileSize": len(data)})
        elif committed > complete:
            violations.append({
                "rule": "R3", "code": "unterminated-tail-committed",
                "committedOffset": committed,
                "lastCompleteOffset": complete})
    return info


def scan_receipts(path: Path, committed: int | None, violations: list) -> dict:
    """Each receipt: {batchId, durable, reasonCode, offsetStart, offsetEnd}."""
    info = {"path": str(path), "records": 0, "nondurable": 0}
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError as exc:
        violations.append({"rule": "R1", "code": "receipts-unreadable",
                           "detail": str(exc)[:160]})
        return info
    for i, ln in enumerate(lines):
        ln = ln.strip()
        if not ln:
            continue
        try:
            rec = json.loads(ln)
        except json.JSONDecodeError:
            violations.append({"rule": "R1", "code": "receipt-unparseable",
                               "line": i + 1})
            continue
        info["records"] += 1
        durable = rec.get("durable")
        reason = str(rec.get("reasonCode") or "")
        if durable is False or reason in NONDURABLE_REASONS:
            info["nondurable"] += 1
            start = rec.get("offsetStart")
            end = rec.get("offsetEnd", start)
            covered = end if end is not None else start
            if committed is not None and covered is not None \
                    and committed > (start if start is not None else covered):
                violations.append({
                    "rule": "R1", "code": "nondurable-checkpoint-advance",
                    "line": i + 1, "batchId": rec.get("batchId"),
                    "reasonCode": reason or "unknown",
                    "offsetStart": start, "offsetEnd": end,
                    "committedOffset": committed})
    return info


def scan_checkpoint(path: Path, violations: list) -> tuple[int | None, dict]:
    info = {"path": str(path), "exists": path.is_file()}
    committed = None
    if not path.is_file():
        info["note"] = "checkpoint-absent"
    else:
        try:
            state = load_json(path)
            committed = state.get("offset") if isinstance(state, dict) else None
            info["committedOffset"] = committed
            info["updatedAt"] = state.get("updatedAt") \
                if isinstance(state, dict) else None
        except (json.JSONDecodeError, OSError, UnicodeDecodeError) as exc:
            violations.append({"rule": "R2", "code": "checkpoint-unreadable",
                               "detail": str(exc)[:160],
                               "consequence": "loadState resets to empty"})
    tmp = path.parent / (path.name + ".tmp")
    if tmp.is_file():
        violations.append({
            "rule": "R2", "code": "atomic-move-residue",
            "tmp": str(tmp),
            "consequence": "a failed/interrupted ATOMIC_MOVE was swallowed by "
                           "catch (Exception ignore)"})
    return committed, info


def static_scan(root: Path) -> list:
    findings = []
    ing = root / ("main/java/com/example/lms/uaw/autolearn/ingest/"
                  "TrainRagIngestService.java")
    if ing.is_file():
        text = ing.read_text(encoding="utf-8", errors="replace")
        flushed_dropped = bool(RE_FLUSH_DROPPED.search(text)) \
            and not bool(RE_FLUSH_KEPT.search(text))
        findings.append({"id": "src-flush-outcome-ignored",
                         "file": "TrainRagIngestService.java",
                         "present": flushed_dropped,
                         "sha12": sha12(ing)})
        save_body = text[text.find("saveState"):]
        findings.append({
            "id": "src-checkpoint-swallowed",
            "file": "TrainRagIngestService.java",
            "present": bool(RE_CATCH_IGNORE.search(save_body))
            and bool(RE_ATOMIC_MOVE.search(save_body))})
        m = re.search(r"readUtf8Line[^{]*\{(?P<body>.*?)\n\s*\}", text, re.S)
        body = m.group("body") if m else ""
        findings.append({
            "id": "src-unterminated-tail-read",
            "file": "TrainRagIngestService.java",
            "present": bool(RE_OUT_RETURN.search(body))
            and "terminated" not in body})
    vsf = root / "main/java/com/example/lms/service/VectorStoreService.java"
    if vsf.is_file():
        text = vsf.read_text(encoding="utf-8", errors="replace")
        findings.append({"id": "src-durable-contract-present",
                         "file": "VectorStoreService.java",
                         "present": bool(RE_DURABLE_CONTRACT.search(text))
                         and "durable" in text,
                         "sha12": sha12(vsf)})
    return findings


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint")
    parser.add_argument("--jsonl")
    parser.add_argument("--receipts")
    parser.add_argument("--state-dir",
                        help="directory containing the checkpoint file; "
                             "auto-discovers *.json + *.tmp siblings")
    parser.add_argument("--source-root")
    parser.add_argument("--fail-on-static", action="store_true",
                        help="treat present defect signatures as violations")
    args = parser.parse_args(argv)

    root = Path.cwd()
    checkpoint = Path(args.checkpoint) if args.checkpoint else None
    if args.state_dir:
        d = Path(args.state_dir)
        if checkpoint is None:
            candidates = sorted(d.glob("*.json"))
            checkpoint = candidates[0] if candidates else d / "ingest-state.json"
    if checkpoint is None:
        print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                          "violations": [], "errors": ["checkpoint-required"]}))
        return 3

    violations: list = []
    committed, cp_info = scan_checkpoint(checkpoint, violations)
    jsonl_info = receipts_info = None
    if args.jsonl:
        jsonl_info = scan_jsonl(Path(args.jsonl), committed, violations)
    if args.receipts:
        receipts_info = scan_receipts(Path(args.receipts), committed, violations)

    static_findings = static_scan(Path(args.source_root).resolve()
                                  if args.source_root else root) \
        if args.source_root is not None else []
    if args.fail_on_static:
        for f in static_findings:
            if f.get("id") != "src-durable-contract-present" \
                    and f.get("present"):
                violations.append({"rule": "STATIC", "code": f["id"],
                                   "file": f.get("file")})

    payload = {"schemaVersion": SCHEMA,
               "ok": not violations,
               "violations": violations,
               "checkpoint": cp_info,
               "jsonl": jsonl_info,
               "receipts": receipts_info,
               "staticFindings": static_findings}
    print(json.dumps(payload, ensure_ascii=False))
    rules = sorted({v["rule"] for v in violations})
    print(f"violations={len(violations)} rules={','.join(rules) or '-'} "
          f"committedOffset={committed}")
    return 2 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
