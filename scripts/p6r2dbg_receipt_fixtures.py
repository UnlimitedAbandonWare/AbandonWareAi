#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""p6r2dbg_receipt_fixtures.py — deterministic receipt fixtures + 7-class judge.

For Codex P6-R2 WP2: original-batch/record-granularity storage receipts and the
failure vs no-work distinction. Models the LIVE contracts (no reimplementation):
VectorFlushOutcome(durable, succeededCount, pendingSize, reasonCode in
{source_rejected, backoff, store_failure, empty}) and
IngestOutcome(storedDocs, checkpointConfirmedDocs, unconfirmedDocs,
checkpointOffset, failureStage).

Fixtures are pure JSON — no JVM, network, provider, or DB. Generation is
deterministic (fixed bytes; re-runs are byte-identical).

Judge classes (failure/no-work taxonomy + one explicit success class):
  durable | no_work | partial | store_failure | unknown |
  checkpoint_failure | policy_excluded | cancelled

Violations (each proven by a counterexample test):
  V1 checkpoint-advanced-without-receipt
  V2 timeout-checkpoint-advanced
  V3 failure-backoff-reset
  V4 no-work-or-cancel-counted-as-failure

Actions:
  generate   -> data/fixtures/p6r2_receipts/*.json + manifest.json
               + data/agent-handoff/devin-p6-r2/receipt-fixtures.md
  judge-all  -> classify every manifest fixture, print verdicts
  judge FILE -> classify one fixture JSON
Exit 0 ok · 2 input missing · 3 judge found violations or class mismatch.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCHEMA = "p6r2-receipt-fixture.v1"
FIXTURE_DIR = "data/fixtures/p6r2_receipts"
REPORT_MD = "data/agent-handoff/devin-p6-r2/receipt-fixtures.md"

CLASSES = ("durable", "no_work", "partial", "store_failure", "unknown",
           "checkpoint_failure", "policy_excluded", "cancelled")


def _outcome(durable, succeeded, pending, reason, stage=None, total=None,
             response_lost=False, cancelled=False, timeout=False,
             checkpoint_before=0, checkpoint_after=0,
             checkpoint_write_failed=False, backoff_before=0, backoff_after=0,
             failed_count_recorded=0):
    return {"durable": durable, "succeededCount": succeeded,
            "pendingSize": pending, "reasonCode": reason,
            "failureStage": stage, "totalRecords": total,
            "responseLost": response_lost, "cancelled": cancelled,
            "timeout": timeout, "checkpointWriteFailed": checkpoint_write_failed,
            "checkpoint": {"before": checkpoint_before, "after": checkpoint_after},
            "backoff": {"before": backoff_before, "after": backoff_after},
            "failedCountRecorded": failed_count_recorded}


def _rec(rid, status, final_id=None, chunk=None):
    r = {"recordId": rid, "status": status}
    if final_id is not None:
        r["finalId"] = final_id
    if chunk is not None:
        r["chunkId"] = chunk
    return r


# ------------------------------------------------------------- scenarios --
# Exactly the seven R2 scenarios from the directive. Each fixture carries the
# outcome record the flush produced, per-original-record receipts, and the
# checkpoint pair — the three things WP2 must keep at original granularity.
SCENARIOS = [
    {"fixtureId": "f01_auto_flush_already_stored",
     "title": "auto-flush stored the batch before the explicit call",
     "junitName": "autoFlushAlreadyStored_explicitFlushIsNoWork",
     "batchId": "b-1001",
     "records": [_rec("r-1", "stored"), _rec("r-2", "stored"),
                 _rec("r-3", "stored")],
     "priorReceipt": {"outcome": _outcome(True, 3, 0, "durable",
                                          checkpoint_before=40,
                                          checkpoint_after=43)},
     "outcome": _outcome(True, 0, 0, "empty", total=3,
                         checkpoint_before=43, checkpoint_after=43),
     "expectedClass": "no_work",
     "rationale": "explicit flush saw pendingSize=0 -> reasonCode=empty; "
                  "records were already durable. no_work must never be "
                  "counted as failure."},
    {"fixtureId": "f02_partial_save",
     "title": "only part of the batch landed durably",
     "junitName": "partialSave_onlyStoredRecordsAdvanceCheckpoint",
     "batchId": "b-1002",
     "records": [_rec("r-1", "stored"), _rec("r-2", "stored"),
                 _rec("r-3", "pending"), _rec("r-4", "pending"),
                 _rec("r-5", "pending")],
     "outcome": _outcome(False, 2, 3, "store_failure",
                         stage="store-write", total=5,
                         checkpoint_before=50, checkpoint_after=52),
     "expectedClass": "partial",
     "rationale": "succeededCount=2 < total=5 with pendingSize=3: checkpoint "
                  "may only cover the two confirmed records."},
    {"fixtureId": "f03_other_batch_only_succeeded",
     "title": "batch X failed while sibling batch Y succeeded",
     "junitName": "otherBatchSucceeded_keepsFailureAttribution",
     "batchId": "b-1003",
     "records": [_rec("r-1", "pending"), _rec("r-2", "pending")],
     "outcome": _outcome(False, 0, 2, "store_failure",
                         stage="store-write", total=2,
                         checkpoint_before=60, checkpoint_after=60),
     "siblingOutcomes": [{"batchId": "b-1004",
                          "outcome": _outcome(True, 4, 0, "durable",
                                              checkpoint_before=60,
                                              checkpoint_after=64)}],
     "expectedClass": "store_failure",
     "rationale": "sibling success must not reclassify X: receipts stay "
                  "per-original-batch, X keeps store_failure + pending=2."},
    {"fixtureId": "f04_entrance_rejected",
     "title": "source rejected the batch before any store attempt",
     "junitName": "entranceRejected_isPolicyExcludedNotStoreFailure",
     "batchId": "b-1005",
     "records": [_rec("r-1", "excluded"), _rec("r-2", "excluded")],
     "outcome": _outcome(False, 0, 0, "source_rejected",
                         stage="admission", total=2,
                         checkpoint_before=70, checkpoint_after=70),
     "expectedClass": "policy_excluded",
     "rationale": "reasonCode=source_rejected means the source refused the "
                  "batch — no store attempt happened. A backoff deferral "
                  "(reasonCode=backoff) is the same class: work existed, "
                  "policy deferred it, nothing failed."},
    {"fixtureId": "f05_split_into_chunks",
     "title": "batch split into 3 chunks; every original record lands",
     "junitName": "splitIntoChunks_originalRecordGranularitySurvives",
     "batchId": "b-1006",
     "records": [_rec("r-1", "stored", chunk="c-1"),
                 _rec("r-2", "stored", chunk="c-1"),
                 _rec("r-3", "stored", chunk="c-2"),
                 _rec("r-4", "stored", chunk="c-2"),
                 _rec("r-5", "stored", chunk="c-3")],
     "chunkOutcomes": [{"chunkId": "c-1", "succeededCount": 2},
                       {"chunkId": "c-2", "succeededCount": 2},
                       {"chunkId": "c-3", "succeededCount": 1}],
     "outcome": _outcome(True, 5, 0, "durable", total=5,
                         checkpoint_before=80, checkpoint_after=85),
     "expectedClass": "durable",
     "rationale": "receipts aggregate per ORIGINAL record id across chunk "
                  "boundaries — chunk ids are evidence, not the key."},
    {"fixtureId": "f06_shadow_quarantine_id",
     "title": "record stored under a quarantine/shadow id",
     "junitName": "shadowQuarantineId_inputIdAndFinalIdDiverge",
     "batchId": "b-1007",
     "records": [_rec("r-1", "stored"),
                 _rec("doc-7", "excluded", final_id="q-88")],
     "outcome": _outcome(True, 1, 0, "durable", total=2,
                         checkpoint_before=90, checkpoint_after=91),
     "expectedClass": "durable",
     "rationale": "doc-7 landed in quarantine as q-88 — input id != final id. "
                  "The batch-level receipt is durable for r-1 plus an "
                  "excluded receipt for doc-7 pointing at shadow id q-88."},
    {"fixtureId": "f07_unknown_commit",
     "title": "store request sent, response lost — commit unknown",
     "junitName": "unknownCommit_responseLost_keepsCheckpointStill",
     "batchId": "b-1008",
     "records": [_rec("r-1", "unknown"), _rec("r-2", "unknown")],
     "outcome": _outcome(None, 0, 2, "unknown_commit",
                         stage="post-store-response", total=2,
                         response_lost=True, timeout=True,
                         checkpoint_before=95, checkpoint_after=95),
     "expectedClass": "unknown",
     "rationale": "UNKNOWN_COMMIT: the store may or may not have persisted. "
                  "checkpoint MUST NOT advance on a timeout and a retry must "
                  "not double-count r-1/r-2."},
]

VIOLATIONS = {
    "V1": "checkpoint-advanced-without-receipt",
    "V2": "timeout-checkpoint-advanced",
    "V3": "failure-backoff-reset",
    "V4": "no-work-or-cancel-counted-as-failure",
}

# TrainRag-outside result-discard sites found by p6dbg_success_mask_scan —
# next-round candidates ONLY (report table; no fix suggested here).
RESULT_DISCARD_CANDIDATES = [
    "VectorStoreBufferScheduler", "TrainingService",
    "VectorQuarantineDlqService", "DefaultKnowledgeBaseService",
    "HybridRetrievalExecutor",
]


# ---------------------------------------------------------------- judge ---
def classify(outcome: dict) -> str:
    """Deterministic classification of one flush outcome (ordered rules)."""
    rc = outcome.get("reasonCode")
    succ = outcome.get("succeededCount") or 0
    pend = outcome.get("pendingSize") or 0
    total = outcome.get("totalRecords")
    durable = outcome.get("durable")

    if outcome.get("cancelled") or rc == "cancelled":
        return "cancelled"
    if rc in ("source_rejected", "backoff", "policy_excluded", "quarantined"):
        return "policy_excluded"
    if durable is True and outcome.get("checkpointWriteFailed"):
        return "checkpoint_failure"
    if outcome.get("responseLost") or rc in ("unknown_commit", "unknown"):
        return "unknown"
    if durable is True and total is not None and succ == total and pend == 0:
        return "durable"
    if durable is True and rc == "durable":
        return "durable"
    if rc == "empty" or total == 0 or (succ == 0 and pend == 0
                                     and outcome.get("failureStage") is None):
        return "no_work"
    if succ > 0 and pend > 0:
        return "partial"
    if durable is False and (rc == "store_failure" or pend > 0):
        return "store_failure"
    return "unknown"


def check_violations(fixture: dict) -> list[dict]:
    """Four receipt-invariant violations; returns [] when clean."""
    out = fixture["outcome"]
    cp = out.get("checkpoint") or {}
    before, after = cp.get("before", 0), cp.get("after", 0)
    advanced = after > before
    v = []

    if advanced:
        covered = sum(1 for r in fixture.get("records", [])
                      if r.get("status") == "stored")
        # checkpoint may cover at most the stored records
        if covered < (after - before):
            v.append({"violationId": "V1",
                      "detail": f"checkpoint {before}->{after} covers "
                                f"{after - before} records but only "
                                f"{covered} stored receipts exist"})
    if advanced and (out.get("timeout") or out.get("responseLost")):
        v.append({"violationId": "V2",
                  "detail": f"timeout/responseLost outcome advanced "
                            f"checkpoint {before}->{after}"})
    bo = out.get("backoff") or {}
    failed = (out.get("durable") is False
              and classify(out) in ("store_failure", "partial", "unknown"))
    if failed and bo.get("before", 0) > 0 and bo.get("after", 0) == 0:
        v.append({"violationId": "V3",
                  "detail": f"failure outcome reset backoff "
                            f"{bo['before']}->{bo['after']}"})
    cls = classify(out)
    if cls in ("no_work", "cancelled") and out.get("failedCountRecorded", 0) > 0:
        v.append({"violationId": "V4",
                  "detail": f"class={cls} but failedCountRecorded="
                            f"{out['failedCountRecorded']}"})
    return v


def judge(fixture: dict) -> dict:
    cls = classify(fixture["outcome"])
    viol = check_violations(fixture)
    expected = fixture.get("expectedClass")
    return {"fixtureId": fixture.get("fixtureId"),
            "class": cls, "expectedClass": expected,
            "classMatch": (expected is None or cls == expected),
            "violations": viol,
            "verdict": "PASS" if not viol and (expected is None or cls == expected)
                       else "FAIL"}


# ------------------------------------------------------------- generate ---
def build_fixture(spec: dict) -> dict:
    fx = {"schema": SCHEMA, "fixtureId": spec["fixtureId"],
          "title": spec["title"], "batchId": spec["batchId"],
          "junitName": spec["junitName"],
          "records": spec["records"], "outcome": spec["outcome"],
          "expectedClass": spec["expectedClass"],
          "rationale": spec["rationale"]}
    for k in ("priorReceipt", "siblingOutcomes", "chunkOutcomes"):
        if k in spec:
            fx[k] = spec[k]
    return fx


def _dump(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def generate(root: Path) -> dict:
    fdir = root / FIXTURE_DIR
    fdir.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": SCHEMA + ".manifest", "fixtures": []}
    for spec in SCENARIOS:
        fx = build_fixture(spec)
        name = spec["fixtureId"] + ".json"
        (fdir / name).write_text(_dump(fx), encoding="utf-8")
        manifest["fixtures"].append({
            "file": name, "fixtureId": spec["fixtureId"],
            "junitName": spec["junitName"],
            "expectedClass": spec["expectedClass"],
            "records": len(spec["records"])})
    (fdir / "manifest.json").write_text(_dump(manifest), encoding="utf-8")
    md = root / REPORT_MD
    md.parent.mkdir(parents=True, exist_ok=True)
    md.write_text(render_fixtures_md(manifest), encoding="utf-8")
    return {"fixtures": len(manifest["fixtures"]),
            "dir": str(fdir), "manifest": str(fdir / "manifest.json"),
            "md": str(md)}


def render_fixtures_md(manifest: dict) -> str:
    L = ["# P6-R2 receipt fixtures (WP2)", "",
         "Deterministic JSON fixtures for original-batch/record-granularity "
         "receipts. No JVM / network / provider / DB.", "",
         "## JUnit case <-> fixture", "",
         "| fixture | JUnit test name (VectorBatchReceiptTest) | "
         "expected class | records |", "|---|---|---|---|"]
    for f in manifest["fixtures"]:
        L.append(f"| `{f['file']}` | `{f['junitName']}` | "
                 f"`{f['expectedClass']}` | {f['records']} |")
    L += ["", "## Judge classes", "",
          "`durable` | `no_work` | `partial` | `store_failure` | `unknown` | "
          "`checkpoint_failure` | `policy_excluded` | `cancelled`", "",
          "Class map: `source_rejected`/`backoff` -> `policy_excluded`; "
          "`empty` -> `no_work`; `unknown_commit`/responseLost -> `unknown`; "
          "durable + checkpointWriteFailed -> `checkpoint_failure`.", "",
          "## Violation invariants", "",
          "| id | rule |", "|---|---|"]
    for vid, name in VIOLATIONS.items():
        L.append(f"| {vid} | {name} |")
    L += ["", "## Next-round candidates (report only — no fix proposed)", "",
          "Result-discard sites OUTSIDE TrainRag from "
          "`p6dbg_success_mask_scan` (11 findings, these 5 are off-scope "
          "for R2):", ""]
    for n in RESULT_DISCARD_CANDIDATES:
        L.append(f"- `{n}`")
    return "\n".join(L) + "\n"


def judge_all(root: Path) -> int:
    mpath = root / FIXTURE_DIR / "manifest.json"
    if not mpath.exists():
        print(json.dumps({"status": "FAIL", "reason": f"{mpath} missing — "
                          "run generate first"}))
        return 2
    manifest = json.loads(mpath.read_text(encoding="utf-8"))
    results, bad = [], 0
    for entry in manifest["fixtures"]:
        fx = json.loads((mpath.parent / entry["file"]).read_text(encoding="utf-8"))
        r = judge(fx)
        results.append(r)
        if r["verdict"] != "PASS":
            bad += 1
    print(json.dumps({"status": "PASS" if bad == 0 else "FAIL",
                      "mismatches": bad, "results": results},
                     ensure_ascii=False, indent=2))
    return 0 if bad == 0 else 3


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("action", choices=["generate", "judge-all", "judge"])
    ap.add_argument("file", nargs="?")
    ap.add_argument("--root", default=".")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()

    if args.action == "generate":
        print(json.dumps({"status": "PASS", **generate(root)},
                         ensure_ascii=False))
        return 0
    if args.action == "judge-all":
        return judge_all(root)
    if not args.file:
        print(json.dumps({"status": "FAIL", "reason": "judge needs a file"}))
        return 2
    fx = json.loads(Path(args.file).read_text(encoding="utf-8"))
    r = judge(fx)
    print(json.dumps(r, ensure_ascii=False, indent=2))
    return 0 if r["verdict"] == "PASS" else 3


if __name__ == "__main__":
    sys.exit(main())
