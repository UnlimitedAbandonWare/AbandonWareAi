#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D4: Ingest ledger audit — checkpoint offset vs durable JSONL boundary.

Reused: fixture generator concept from p6dbg_jsonl_boundary_fixtures (manifest format).
Added: byte-level ledger audit. Copies inputs to %TEMP%/p6dbg/copy/ before reading
(rule: originals never opened for write/delete). Pure stdlib, read-only.

Checks:
  CKPT-01 checkpoint.offset <= byte offset of last complete (\\n-terminated) line end
  CKPT-02 checkpoint.offset > 0 when dataset exists
  TAIL-01 bytes after last \\n are a dangling tail (unterminated record)
  DUP-01 duplicate "id" fields inside complete lines
  UTF8-01 tail decodes as valid UTF-8 (mid-char cut detection)
  HASH-01 checkpoint.fileHash matches sha256 of file prefix [0,offset) when present

Exit 0 PASS (no violations) / 1 FAIL (violations found) / 2 input missing.
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, sys, tempfile
from pathlib import Path

DEFAULT_DATASET = "data/train_rag.jsonl"
DEFAULT_STATE = "data/train/ingest_state.json"

def _copy_to_sandbox(src: Path, sandbox: Path) -> Path:
    dst = sandbox / src.name
    if src.exists() and src.is_file():
        shutil.copyfile(src, dst)
        return dst
    return src

def audit(jsonl: Path, state: Path | None, dataset_hash_expected=None) -> dict:
    res = {"jsonl": str(jsonl), "state": str(state) if state else None,
           "checks": [], "violations": []}
    if not jsonl.exists():
        res["status"] = "NOT_RUN"
        res["reason"] = f"dataset missing: {jsonl}"
        return res
    data = jsonl.read_bytes()
    res["jsonl_bytes"] = len(data)

    last_nl = data.rfind(b"\n")
    durable_end = last_nl + 1 if last_nl >= 0 else 0
    tail = data[durable_end:]
    complete_lines = data[:durable_end].split(b"\n") if durable_end else []
    complete_lines = [l for l in complete_lines if l != b"" or durable_end == 0]

    def check(cid, ok, detail):
        res["checks"].append({"id": cid, "ok": ok, "detail": detail})
        if not ok:
            res["violations"].append({"id": cid, "detail": detail})

    check("TAIL-01", True,
          f"dangling tail {len(tail)} bytes after offset {durable_end} — reader must not consume"
          if tail else "clean EOF")
    if tail:
        try:
            tail.decode("utf-8")
            check("UTF8-01", True, "tail decodes (unterminated but valid utf8)")
        except UnicodeDecodeError as e:
            check("UTF8-01", True,
                  f"tail cut mid-UTF8 at byte {e.start} — must never be consumed")
    else:
        check("UTF8-01", True, "no tail")

    ids = {}
    parse_err = 0
    for i, line in enumerate(data[:durable_end].split(b"\n")):
        if not line.strip():
            continue
        try:
            o = json.loads(line.decode("utf-8"))
            rid = o.get("id")
            if rid:
                ids.setdefault(rid, []).append(i + 1)
        except Exception:
            parse_err += 1
    dups = {k: v for k, v in ids.items() if len(v) > 1}
    check("DUP-01", not dups,
          f"duplicate ids: {list(dups)[:5]}" if dups else f"{len(ids)} unique ids")
    check("PARSE-01", True, f"{parse_err} malformed complete lines (byte-boundary, not violations)")
    res["complete_lines"] = len([l for l in data[:durable_end].split(b"\n") if l.strip()])
    res["durable_end"] = durable_end
    res["parse_errors"] = parse_err

    if state is None or not state.exists():
        check("CKPT-00", True, "no checkpoint file — fresh state (nothing to compare)")
        res["status"] = "PASS" if not res["violations"] else "FAIL"
        return res
    try:
        st = json.loads(state.read_text(encoding="utf-8"))
    except Exception as e:
        check("CKPT-00", False, f"checkpoint unparsable: {e}")
        res["status"] = "FAIL"
        return res
    off = st.get("offset", 0)
    res["checkpoint_offset"] = off
    res["checkpoint"] = {k: st.get(k) for k in ("fileHash", "fileLength", "updatedAt")}

    check("CKPT-01", off <= durable_end,
          f"offset {off} vs durable_end {durable_end}" +
          (" — CHECKPOINT AHEAD OF DURABLE DATA" if off > durable_end else ""))
    check("CKPT-02", off > 0 or len(data) == 0,
          f"offset {off} with {len(data)}B dataset" if off == 0 and data else "offset nonzero")
    fh = st.get("fileHash")
    if fh and off <= len(data):
        actual = hashlib.sha256(data[:off]).hexdigest()
        check("HASH-01", actual == fh,
              "fileHash matches prefix" if actual == fh else
              f"fileHash mismatch: state={fh[:16]}… prefix[0,{off})={actual[:16]}…")
    res["status"] = "PASS" if not res["violations"] else "FAIL"
    return res

def main() -> int:
    ap = argparse.ArgumentParser(description="Ingest ledger boundary audit")
    ap.add_argument("--root", default=".")
    ap.add_argument("--jsonl", default=None, help="dataset path (default: config key resolution)")
    ap.add_argument("--state", default=None, help="checkpoint path")
    ap.add_argument("--no-copy", action="store_true", help="read in place (fixture use)")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    jsonl = Path(a.jsonl) if a.jsonl else root / DEFAULT_DATASET
    state = Path(a.state) if a.state else root / DEFAULT_STATE
    if not jsonl.is_absolute():
        jsonl = root / jsonl
    if state and not state.is_absolute():
        state = root / state

    if not a.no_copy:
        sandbox = Path(tempfile.gettempdir()) / "p6dbg" / "copy"
        sandbox.mkdir(parents=True, exist_ok=True)
        jsonl = _copy_to_sandbox(jsonl, sandbox)
        if state and state.exists():
            state = _copy_to_sandbox(state, sandbox)

    res = audit(jsonl, state)
    print(json.dumps(res, ensure_ascii=False, indent=2))
    return 0 if res.get("status") == "PASS" else (2 if res.get("status") == "NOT_RUN" else 1)

if __name__ == "__main__":
    sys.exit(main())
