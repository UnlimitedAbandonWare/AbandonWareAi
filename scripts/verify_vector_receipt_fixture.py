#!/usr/bin/env python3
"""verify_vector_receipt_fixture.py — durable-receipt fixture self-check (WP4).

Runs check_vector_checkpoint_receipt.py against the golden fixture under
var/diagnostics/receipt-fixture/ plus three negative variants in a temp dir,
proving each violation rule actually fires:

  golden  all batches durable, committed offset == last complete JSONL offset
  R1      a durable=false receipt (store_failure) covered by the committed offset
  R2      a stale `<checkpoint>.tmp` sibling (ATOMIC_MOVE residue)
  R3      JSONL tail missing its LF while committed moved past the boundary

  python -B scripts/verify_vector_receipt_fixture.py

Exit 0 = all four expectations met · 1 = a case mismatched.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKER = ROOT / "scripts" / "check_vector_checkpoint_receipt.py"
FIX = ROOT / "var" / "diagnostics" / "receipt-fixture"


def run_checker(cp: Path, jl: Path, rc: Path) -> tuple[int, str]:
    cmd = [sys.executable, "-B", str(CHECKER),
           "--checkpoint", str(cp), "--jsonl", str(jl), "--receipts", str(rc)]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout


def copy_fixture(dst: Path, state: dict | None = None,
                 jsonl: bytes | None = None, receipts: list | None = None,
                 make_tmp: bool = False) -> tuple[Path, Path, Path]:
    dst.mkdir(parents=True, exist_ok=True)
    cp, jl, rc = dst / "state.json", dst / "samples.jsonl", dst / "receipts.jsonl"
    cp.write_text(json.dumps(state if state is not None else
                             json.loads((FIX / "state.json")
                                        .read_text(encoding="utf-8")),
                             indent=2) + "\n", encoding="utf-8")
    jl.write_bytes(jsonl if jsonl is not None
                   else (FIX / "samples.jsonl").read_bytes())
    if receipts is None:
        shutil.copyfile(FIX / "receipts.jsonl", rc)
    else:
        rc.write_text("".join(json.dumps(r) + "\n" for r in receipts),
                      encoding="utf-8")
    if make_tmp:
        (dst / "state.json.tmp").write_bytes(b'{"offset": 0')
    return cp, jl, rc


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    results = []

    code, out = run_checker(FIX / "state.json", FIX / "samples.jsonl",
                            FIX / "receipts.jsonl")
    results.append(("golden", code == 0 and '"violations": []' in out, out))

    with tempfile.TemporaryDirectory(prefix="receipt-fixture-neg-") as td:
        base = Path(td)

        # R1: nondurable receipt whose covered range sits below committed=1024
        recs = [{"batchId": "b0", "durable": False, "reasonCode": "store_failure",
                 "offsetStart": 0, "offsetEnd": 512}]
        cp, jl, rc = copy_fixture(base / "r1", receipts=recs)
        code, out = run_checker(cp, jl, rc)
        results.append(("R1 nondurable-checkpoint-advance",
                        code == 2 and '"rule": "R1"' in out, out))

        # R2: stale .tmp sibling next to the checkpoint file
        cp, jl, rc = copy_fixture(base / "r2", make_tmp=True)
        code, out = run_checker(cp, jl, rc)
        results.append(("R2 atomic-move-residue",
                        code == 2 and '"rule": "R2"' in out, out))

        # R3: unterminated tail — drop the final LF so committed(1024) > lastComplete
        raw = (FIX / "samples.jsonl").read_bytes()
        assert raw.endswith(b"\n")
        cp, jl, rc = copy_fixture(base / "r3", jsonl=raw[:-1])
        code, out = run_checker(cp, jl, rc)
        results.append(("R3 unterminated-tail-committed",
                        code == 2 and '"rule": "R3"' in out, out))

    ok = True
    for name, passed, _ in results:
        print(f"{'PASS' if passed else 'FAIL'} {name}")
        ok = ok and passed
    if not ok:
        for name, passed, out in results:
            if not passed:
                print(f"--- {name} output ---\n{out[:1200]}")
    print(f"cases={len(results)} passed={sum(1 for _, p, _ in results if p)}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
