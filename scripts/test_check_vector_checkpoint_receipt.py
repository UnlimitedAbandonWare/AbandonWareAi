"""Self-check for check_vector_checkpoint_receipt.py — contaminated snapshot fixtures.

Builds synthetic checkpoint/JSONL/receipts snapshots and synthetic Java source
roots in a temp dir; verifies each of the three violation rules fires exactly
when it should and that a clean snapshot exits 0.

Run: python -B scripts/test_check_vector_checkpoint_receipt.py
Exit 0 = all cases behaved; 1 = a case disagreed.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "check_vector_checkpoint_receipt.py"

JAVA_INGEST = """package x;
class TrainRagIngestService {
    private boolean upsertSegments(java.util.List<Object> batch) {
        vectorStoreService.flush();
        return true;
    }
    private void saveState(java.nio.file.Path statePath) {
        try {
            java.nio.file.Files.move(tmp, statePath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignore) {
            log.debug("skipped");
        }
    }
    private static String readUtf8Line(java.io.RandomAccessFile raf) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int b; boolean gotAny = false;
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
"""

JAVA_STORE = """package x;
class VectorStoreService {
    record VectorFlushOutcome(boolean durable, int succeededCount,
                              int pendingCount, String reasonCode) {}
}
"""


def run_tool(*args: str, cwd: Path) -> tuple[int, dict]:
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(cwd))
    try:
        payload = json.loads(proc.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        payload = {"parse_error": proc.stdout[:200],
                   "stderr": proc.stderr[:200]}
    return proc.returncode, payload


def codes(payload: dict) -> set:
    return {v.get("code") for v in payload.get("violations", [])}


def main() -> int:
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        # ---- clean baseline ------------------------------------------------
        jsonl = root / "samples.jsonl"
        line1 = b'{"id":"r1","text":"a"}\n'
        line2 = b'{"id":"r2","text":"b"}\n'
        jsonl.write_bytes(line1 + line2)
        clean_cp = root / "state_clean.json"
        clean_cp.write_text(json.dumps({"offset": len(line1 + line2),
                                        "file": "samples.jsonl"}),
                            encoding="utf-8")
        receipts_ok = root / "receipts_ok.jsonl"
        receipts_ok.write_text(json.dumps(
            {"batchId": "b1", "durable": True, "reasonCode": "complete",
             "offsetStart": 0, "offsetEnd": len(line1)}) + "\n", encoding="utf-8")
        code, out = run_tool("--checkpoint", str(clean_cp), "--jsonl",
                             str(jsonl), "--receipts", str(receipts_ok),
                             cwd=root)
        cases.append(("clean-snapshot-no-violations",
                      code == 0 and out.get("ok") is True
                      and out.get("violations") == [], out))

        # ---- R1: durable=false batch yet checkpoint advanced ---------------
        receipts_bad = root / "receipts_bad.jsonl"
        receipts_bad.write_text(
            json.dumps({"batchId": "b1", "durable": False,
                        "reasonCode": "store_failure",
                        "offsetStart": 0, "offsetEnd": len(line1)}) + "\n"
            + json.dumps({"batchId": "b2", "durable": False,
                          "reasonCode": "backoff",
                          "offsetStart": len(line1),
                          "offsetEnd": len(line1) + len(line2)}) + "\n",
            encoding="utf-8")
        code, out = run_tool("--checkpoint", str(clean_cp), "--jsonl",
                             str(jsonl), "--receipts", str(receipts_bad),
                             cwd=root)
        cases.append(("r1-nondurable-checkpoint-advance",
                      code == 2
                      and "nondurable-checkpoint-advance" in codes(out)
                      and len([v for v in out["violations"]
                               if v["code"] == "nondurable-checkpoint-advance"])
                      == 2, out))

        # durable=false receipt but checkpoint NOT advanced -> clean for R1
        early_cp = root / "state_early.json"
        early_cp.write_text(json.dumps({"offset": 0}), encoding="utf-8")
        code, out = run_tool("--checkpoint", str(early_cp), "--jsonl",
                             str(jsonl), "--receipts", str(receipts_bad),
                             cwd=root)
        cases.append(("r1-not-committed-clean", code == 0
                      and "nondurable-checkpoint-advance" not in codes(out),
                      out))

        # ---- R2: atomic-move residue + corrupt checkpoint ------------------
        dirty = root / "dirty"
        dirty.mkdir()
        cp = dirty / "ingest-state.json"
        cp.write_text(json.dumps({"offset": len(line1)}), encoding="utf-8")
        (dirty / "ingest-state.json.tmp").write_text("{}", encoding="utf-8")
        code, out = run_tool("--checkpoint", str(cp), "--state-dir",
                             str(dirty), cwd=root)
        cases.append(("r2-atomic-move-residue", code == 2
                      and "atomic-move-residue" in codes(out), out))

        corrupt = root / "corrupt.json"
        corrupt.write_bytes(b"{not-json")
        code, out = run_tool("--checkpoint", str(corrupt), cwd=root)
        cases.append(("r2-checkpoint-unreadable", code == 2
                      and "checkpoint-unreadable" in codes(out), out))

        # ---- R3: unterminated tail committed / beyond EOF ------------------
        tail_jsonl = root / "tail.jsonl"
        tail_jsonl.write_bytes(line1 + b'{"id":"r3","text":"trun')
        tail_cp = root / "state_tail.json"
        tail_cp.write_text(json.dumps({"offset": len(line1) + 5}),
                           encoding="utf-8")
        code, out = run_tool("--checkpoint", str(tail_cp), "--jsonl",
                             str(tail_jsonl), cwd=root)
        cases.append(("r3-unterminated-tail-committed", code == 2
                      and "unterminated-tail-committed" in codes(out), out))

        beyond_cp = root / "state_beyond.json"
        beyond_cp.write_text(json.dumps({"offset": 9999}), encoding="utf-8")
        code, out = run_tool("--checkpoint", str(beyond_cp), "--jsonl",
                             str(tail_jsonl), cwd=root)
        cases.append(("r3-committed-beyond-eof", code == 2
                      and "committed-beyond-eof" in codes(out), out))

        # unterminated tail but checkpoint stopped at last complete boundary
        ok_tail_cp = root / "state_oktail.json"
        ok_tail_cp.write_text(json.dumps({"offset": len(line1)}),
                              encoding="utf-8")
        code, out = run_tool("--checkpoint", str(ok_tail_cp), "--jsonl",
                             str(tail_jsonl), cwd=root)
        cases.append(("r3-tail-not-committed-clean", code == 0, out))

        # ---- static source signatures ---------------------------------------
        src_root = root / "srctree"
        ing = src_root / ("main/java/com/example/lms/uaw/autolearn/ingest/"
                          "TrainRagIngestService.java")
        ing.parent.mkdir(parents=True)
        ing.write_text(JAVA_INGEST, encoding="utf-8")
        vsf = src_root / "main/java/com/example/lms/service/VectorStoreService.java"
        vsf.parent.mkdir(parents=True)
        vsf.write_text(JAVA_STORE, encoding="utf-8")
        code, out = run_tool("--checkpoint", str(clean_cp),
                             "--source-root", str(src_root), cwd=root)
        findings = {f["id"]: f["present"]
                    for f in out.get("staticFindings", [])}
        cases.append(("static-defect-signatures-detected",
                      code == 0
                      and findings.get("src-flush-outcome-ignored") is True
                      and findings.get("src-checkpoint-swallowed") is True
                      and findings.get("src-unterminated-tail-read") is True
                      and findings.get("src-durable-contract-present") is True,
                      out))
        code, out = run_tool("--checkpoint", str(clean_cp),
                             "--source-root", str(src_root),
                             "--fail-on-static", cwd=root)
        cases.append(("fail-on-static-exit2", code == 2
                      and any(v.get("rule") == "STATIC"
                              for v in out.get("violations", [])), out))

        # fixed source -> findings false, still exit 0
        ing.write_text(JAVA_INGEST.replace("        vectorStoreService.flush();",
                                         "        return vectorStoreService.flush().durable();")
                       .replace("} catch (Exception ignore) {",
                                "} catch (Exception e) { throw new IllegalStateException(e); }"),
                       encoding="utf-8")
        code, out = run_tool("--checkpoint", str(clean_cp),
                             "--source-root", str(src_root), cwd=root)
        findings = {f["id"]: f["present"]
                    for f in out.get("staticFindings", [])}
        cases.append(("static-fixed-no-flag", code == 0
                      and findings.get("src-flush-outcome-ignored") is False
                      and findings.get("src-checkpoint-swallowed") is False,
                      out))

    failed = [n for n, ok, _ in cases if not ok]
    for name, ok, out in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: "
              f"{json.dumps(out, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
