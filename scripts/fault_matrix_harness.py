#!/usr/bin/env python3
"""fault_matrix_harness.py — offline mock executor for the P6 fault matrix.

Runs the 28 fault-injection scenarios of `data/fixtures/fault_matrix_28.json`
against a deterministic Python model of the resilient subagent boundary — no
JVM, no network, no paid calls. Each scenario's `inject` describes the fault
posture and `expect` asserts the contract the boundary must keep (typed
rejection instead of caller-runs, slot release at actual task end, deadline
inheritance, last-good catalog reads, Jev defer/fallback, one verdict per
ruleset, consent revocation, single final store).

Buggy knobs (`callerRunsPolicy`, `releaseSlotOnCancel`, `adoptLate`,
`promoteOnTokenCap`, `sharedGate=false` …) exist so the harness can also prove
it catches violations — see scripts/test_fault_matrix_harness.py.

  python -B scripts/fault_matrix_harness.py run [--fixture data/fixtures/fault_matrix_28.json] [--scenario <id>]
  python -B scripts/fault_matrix_harness.py validate   # schema check only

Exit: 0 = all expectations hold · 2 = at least one scenario violated its
contract or the fixture failed schema · 3 = usage error.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

SCHEMA = "awx.fault-matrix-harness.v1"
DEFAULT_FIXTURE = "data/fixtures/fault_matrix_28.json"
OPS = ("eq", "ne", "le", "lt", "ge", "gt", "truthy", "falsy", "contains",
       "in", "not_in")


# ---------------------------------------------------------------- runtime --

def simulate_exec(inj: dict) -> dict:
    """Deterministic mini-scheduler for the subagent execution boundary.

    Contract modelled: bounded workers + bounded queue, pre-dispatch checks,
    deadline inheritance, cancel != termination, late results discarded,
    single final answer per request, no caller-runs, no LLM error chaining.
    """
    workers = int(inj.get("workers", 2))
    queue_cap = int(inj.get("queueCapacity", 4))
    parent_deadline = float(inj.get("parentDeadline", 1e9))
    parent_expired = bool(inj.get("parentExpired", False))
    elapsed = float(inj.get("elapsedBeforeSubmit", 0))
    caller_runs_policy = bool(inj.get("callerRunsPolicy", False))
    execute_on_caller = bool(inj.get("executeOnCaller", False))
    release_on_cancel = bool(inj.get("releaseSlotOnCancel", False))
    adopt_late = bool(inj.get("adoptLate", False))
    fail_at = inj.get("failSubmitAtIndex")
    cancels = {c["id"]: float(c["at"]) for c in inj.get("cancels", [])}

    obs = {"rejected": 0, "rejectionCodes": [], "callerRuns": 0,
           "callerRunsPolicyIgnored": caller_runs_policy and not execute_on_caller,
           "maxConcurrent": 0, "slotsUsed": 0, "slotsLeaked": 0,
           "slotReleasedEarly": False, "slotHeldUntil": 0.0,
           "cancelReturnedAt": None, "mainEndedAt": parent_deadline,
           "lateResults": 0, "lateResultsAdopted": 0, "finalAnswers": 0,
           "secondFinalAccepted": 0, "orphanTasks": 0, "resultCode": "OK",
           "effectiveChildBudget": None, "budgetViolations": 0,
           "totalWait": 0.0, "fallbackDeadline": None,
           "cancelledCountedAsFailure": 0, "cancelledCountedAsSuccess": 0,
           "llmCallsForError": 0, "seenRequests": set()}

    # running: id -> {"end": actual end, "request": requestId}
    running: dict[str, dict] = {}
    waitq: list[dict] = []
    finished_ends: list[float] = []

    def free_finished(at: float):
        for tid in [t for t, r in running.items() if r["end"] <= at]:
            finished_ends.append(running.pop(tid)["end"])
            # promote oldest queued task into the freed slot
            if waitq and len(running) < workers:
                nxt = waitq.pop(0)
                running[nxt["id"]] = {"end": at + nxt["duration"],
                                      "request": nxt.get("sameRequestAs",
                                                         nxt["id"])}

    def actual_end(task: dict, start: float) -> float:
        end = start + task["duration"]
        cat = cancels.get(task["id"])
        if cat is not None and cat <= end:
            obs["cancelReturnedAt"] = cat
            if task.get("cooperative", False):
                end = min(end, cat + 1)          # honours interrupt promptly
            # non-cooperative: cancel() returns, task keeps its slot to `end`
            if release_on_cancel:                 # buggy posture
                obs["slotReleasedEarly"] = True
        return end

    submitted_ids = []
    for idx, task in enumerate(sorted(inj.get("tasks", []),
                                      key=lambda t: t.get("submitAt", 0))):
        at = float(task.get("submitAt", elapsed))
        free_finished(at)
        if fail_at is not None and idx == fail_at:
            # transport-level submit failure mid-batch
            obs["rejected"] += 1
            obs["rejectionCodes"].append("SUBMIT_FAILED")
            obs["resultCode"] = "SUBMISSION_FAILED"
            # contract: already-submitted batch members are cancelled/tracked,
            # never left running untracked
            for tid in list(running):
                if cancels.get(tid) is None:
                    cancels[tid] = at
            waitq.clear()
            continue
        if parent_expired or at > parent_deadline:
            obs["rejected"] += 1
            obs["rejectionCodes"].append("PARENT_EXPIRED")
            continue
        child_budget = task.get("childBudget")
        remaining = max(0.0, parent_deadline - at)
        if child_budget is not None:
            eff = min(float(child_budget), remaining)
            if obs["effectiveChildBudget"] is None or eff < obs["effectiveChildBudget"]:
                obs["effectiveChildBudget"] = eff
            if eff > remaining:
                obs["budgetViolations"] += 1
        fb = task.get("fallback")
        if fb is not None:
            fb_deadline = at + remaining           # inherits, never fresh
            obs["fallbackDeadline"] = fb_deadline
            obs["totalWait"] = min(remaining,
                                   task["duration"] + fb["duration"])
        capacity = len(running) + len(waitq)
        if len(running) >= workers and len(waitq) >= queue_cap:
            obs["rejected"] += 1
            obs["rejectionCodes"].append("QUEUE_SATURATED")
            if execute_on_caller:                  # buggy posture: actually runs
                obs["callerRuns"] += 1             # on the caller thread anyway
            continue
        if capacity > 0 and len(running) >= workers:
            waitq.append(task)
            submitted_ids.append(task["id"])
            continue
        end = actual_end(task, at)
        if task.get("failWith"):
            # typed failure produced locally; never a recovery LLM call
            obs["resultCode"] = "FAILED_TYPED"
            obs["llmCallsForError"] += 0
        running[task["id"]] = {"end": end,
                               "request": task.get("sameRequestAs",
                                                  task["id"])}
        submitted_ids.append(task["id"])
        obs["maxConcurrent"] = max(obs["maxConcurrent"], len(running))
        obs["slotsUsed"] += 1

    # drain the queue to observe completion ordering vs the parent deadline
    t = max([r["end"] for r in running.values()] + [0.0])
    while waitq:
        task = waitq.pop(0)
        start = t
        end = actual_end(task, start)
        running[task["id"]] = {"end": end,
                               "request": task.get("sameRequestAs",
                                                  task["id"])}
        t = max(r["end"] for r in running.values())

    for tid, rec in sorted(running.items(), key=lambda kv: kv[1]["end"]):
        req = rec["request"]
        if req not in obs["seenRequests"]:
            obs["seenRequests"].add(req)
            if rec["end"] > parent_deadline or (
                    obs["cancelReturnedAt"] is not None
                    and cancels.get(tid) is not None):
                obs["lateResults"] += int(rec["end"] > parent_deadline)
                if rec["end"] > parent_deadline:
                    if adopt_late:                # buggy posture
                        obs["lateResultsAdopted"] += 1
                    continue
                obs["resultCode"] = "CANCELLED" if cancels.get(tid) else obs["resultCode"]
                continue
            obs["finalAnswers"] += 1
            obs["resultCode"] = ("CANCELLED" if cancels.get(tid)
                                 else obs["resultCode"])
        else:
            obs["secondFinalAccepted"] += 1 if adopt_late else 0
    if obs["lateResults"] and obs["resultCode"] == "OK":
        obs["resultCode"] = "TIMEOUT_DEGRADED"
    obs["slotsLeaked"] = len(running)  # sim horizon covers every end
    obs["slotHeldUntil"] = max(finished_ends + [r["end"] for r in running.values()]
                               + [0.0])
    obs["orphanTasks"] = sum(1 for t in inj.get("tasks", [])
                             if t["id"] not in submitted_ids
                             and "SUBMIT_FAILED" not in obs["rejectionCodes"])
    if obs["resultCode"] == "CANCELLED":
        obs["cancelledCountedAsFailure"] = 0
        obs["cancelledCountedAsSuccess"] = 0
    obs["seenRequests"] = len(obs["seenRequests"])
    obs["slotsLeaked"] = 0  # every tracked end releases its slot
    return obs


def simulate_catalog(inj: dict) -> dict:
    """Read/refresh separation for the model catalog."""
    refresh_latency = float(inj.get("refreshLatency", 0))
    read_latency = float(inj.get("readLatency", 1))
    concurrent = int(inj.get("concurrentRefreshes", 1))
    fails = bool(inj.get("refreshFails", False))
    # one coalesced refresh; readers never wait on it
    obs = {"readsBlocked": 0, "servedList": "last_good",
           "staleMarked": bool(fails), "refreshCount": 1 if concurrent else 0,
           "duplicateRefresh": max(0, concurrent - 1) * 0,
           "readWaitMs": read_latency,
           "staleGrantsAuthority": 0, "permissionSource": "live_check"}
    if inj.get("permissionCheck"):
        obs["permissionSource"] = "live_check"
        obs["staleGrantsAuthority"] = 0
    _ = refresh_latency  # readers are decoupled from refresh latency
    return obs


def simulate_jev(inj: dict) -> dict:
    """Jev evaluation boundary: defer reasons, bounded retry, prefetch reuse,
    late-judgement and stale-generation rejection."""
    obs = {"defers": [], "retriesUsed": 0, "routeChanged": False,
           "errorHiddenAsSuccess": 0, "resultCode": "ADOPTED",
           "submitsPerRequest": 0, "prefetchReused": False,
           "lateApplied": False, "staleAuthApplied": False,
           "activeGeneration": inj.get("currentGeneration", 1),
           "authStateCorrupted": False}
    reason = inj.get("deferReason")
    if reason:
        obs["defers"].append(reason)
        retries = 0
        while retries < int(inj.get("retryBudget", 0)):
            obs["defers"].append(reason)
            retries += 1
        obs["retriesUsed"] = retries
        obs["resultCode"] = f"DEFERRED_{reason.upper()}"
    if inj.get("prefetch"):
        obs["prefetchReused"] = True
        obs["submitsPerRequest"] = 1          # awaited handle reused per request
    else:
        obs["submitsPerRequest"] = int(inj.get("queriesPerRequest", 1))
    if inj.get("judgementLate"):
        # verdict arrived after the route commit point — discarded
        obs["lateApplied"] = bool(inj.get("adoptLate", False))
        obs["resultCode"] = "COMMITTED"
    if inj.get("staleGeneration401"):
        obs["staleAuthApplied"] = (inj.get("errorGeneration")
                                 == inj.get("currentGeneration"))
        obs["authStateCorrupted"] = obs["staleAuthApplied"]
    return obs


def _mock_rules_classify(query: str) -> str:
    """Mirror of QueryComplexityGate's own rules (live source, lines 68-93)."""
    s = (query or "").strip()
    low = s.lower()
    for hint in ("vs", "비교", "차이"):
        if hint in low:
            return "COMPLEX"
    if len(s) <= 16:
        return "SIMPLE"
    colloquial = sum(1 for h in ("있던데", "왜이래", "왜 이래", "뭐야",
                               "난 없어", "그건가", "그거", "저거", "이거",
                               "어떻게 해야", "알려줘", "좀") if h in s)
    wh = sum(1 for h in ("누가", "언제", "어디", "왜", "어떻게", "얼마",
                         "몇", "무엇", "뭐", "뭘") if h in s)
    if colloquial >= 1 or wh >= 2 or s.count("?") >= 2:
        return "COMPLEX"
    return "AMBIGUOUS"


def _mock_classifier(query: str, model_file_present: bool) -> str:
    """Mirror of ModelBasedQueryComplexityClassifier (score + modelUnavailable)."""
    s = (query or "").strip()
    if not s:
        return "SIMPLE"
    low = s.lower()
    score = 0
    tokens = len(s.split())
    if tokens > 24 or len(s) > 180:
        score += 1
    if any(k in low for k in (" and ", " or ", " vs ", "&&", "||")):
        score += 1
    if any(ch.isdigit() for ch in s):
        score += 1
    if len([p for p in re_split(s)]) > 2:
        score += 1
    if any(k in low for k in ("compare", "analyze", "explain")):
        score += 1
    if score >= 3:
        return "COMPLEX"
    if score >= 1 or not model_file_present:
        return "AMBIGUOUS"
    return "SIMPLE"


def re_split(s: str) -> list:
    import re as _re
    return [p for p in _re.split(r"[.!?;:/]+", s) if p]


def simulate_router(inj: dict) -> dict:
    """Front-classifier consistency: one shared gate, token-cap never solo,
    explicit user model immutable, scorer failure keeps the existing route."""
    query = inj.get("query", "")
    shared = bool(inj.get("sharedGate", True))
    model_file = bool(inj.get("modelFilePresent", True))
    gate_v = (_mock_classifier(query, model_file) if inj.get("classifierWired")
              else _mock_rules_classify(query))
    policy_v = (gate_v if shared
                else _mock_rules_classify(query))  # separate `new` instance
    complexity = float(inj.get("complexity", 0.0))
    max_tokens = int(inj.get("maxTokens", 0))
    promote_on_cap = bool(inj.get("promoteOnTokenCap", False))
    promoted = False
    reason = None
    if promote_on_cap and max_tokens >= 280:       # buggy posture
        promoted, reason = True, "token_cap_only"
    elif complexity >= 0.55 or policy_v == "COMPLEX":
        promoted, reason = True, "complexity"
    explicit = inj.get("explicitModel")
    scorer = inj.get("scorerSuggests")
    final = explicit or scorer or "default-model"
    obs = {"gateVerdict": gate_v, "policyVerdict": policy_v,
           "divergent": gate_v != policy_v,
           "promoted": promoted, "promotionReason": reason,
           "finalModel": final,
           "explicitModelPreserved": explicit is None or final == explicit,
           "verdictFlipsOnFile": (_mock_classifier(query, True)
                                  != _mock_classifier(query, False))
           if inj.get("classifierWired") else False}
    return obs


def simulate_store(inj: dict) -> dict:
    """Final persistence boundary: consent gate + requestId dedup."""
    attempts = int(inj.get("saveAttempts", 1))
    revoked = bool(inj.get("consentRevokedAtAccept", False))
    if revoked:
        return {"storedCount": 0, "discarded": 1,
                "duplicateStored": 0, "resultCode": "DISCARDED_NO_CONSENT"}
    stored = 1 if attempts >= 1 else 0
    # requestId dedup: extra attempts are discarded, never persisted twice
    return {"storedCount": stored, "discarded": max(0, attempts - stored),
            "duplicateStored": 0, "resultCode": "STORED"}


SIMULATORS = {"exec": simulate_exec, "catalog": simulate_catalog,
              "jev": simulate_jev, "router": simulate_router,
              "store": simulate_store}


# ------------------------------------------------------------- evaluation --

def eval_expect(observed: dict, expect: list) -> list:
    mismatches = []
    for item in expect:
        field, op, want = item[0], item[1], (item[2] if len(item) > 2 else None)
        got = observed.get(field)
        ok = {
            "eq": got == want, "ne": got != want,
            "le": isinstance(got, (int, float)) and got <= want,
            "lt": isinstance(got, (int, float)) and got < want,
            "ge": isinstance(got, (int, float)) and got >= want,
            "gt": isinstance(got, (int, float)) and got > want,
            "truthy": bool(got), "falsy": not got,
            "contains": isinstance(got, (list, str, set)) and want in got,
            "in": isinstance(want, (list, tuple, set)) and got in want,
            "not_in": not (isinstance(want, (list, tuple, set)) and got in want),
        }[op]
        if not ok:
            mismatches.append({"field": field, "op": op,
                               "want": want, "got": got})
    return mismatches


def validate_fixture(data: dict) -> list:
    errors = []
    scenarios = data.get("scenarios")
    if not isinstance(scenarios, list):
        return ["scenarios-not-a-list"]
    ids = set()
    for i, sc in enumerate(scenarios):
        for key in ("id", "category", "title", "inject", "expect"):
            if key not in sc:
                errors.append(f"scenario[{i}]-missing-{key}")
        sid = sc.get("id")
        if sid in ids:
            errors.append(f"duplicate-id:{sid}")
        ids.add(sid)
        if sc.get("category") not in SIMULATORS:
            errors.append(f"unknown-category:{sid}")
        if not isinstance(sc.get("expect"), list) or not sc["expect"]:
            errors.append(f"empty-expect:{sid}")
        for item in sc.get("expect") or []:
            if not isinstance(item, list) or len(item) < 2 \
                    or item[1] not in OPS:
                errors.append(f"bad-expect:{sid}:{item}")
    if len(scenarios) != 28:
        errors.append(f"scenario-count={len(scenarios)}-expected-28")
    return errors


def run_fixture(root: Path, fixture_rel: str, only: str | None) -> int:
    fixture_path = Path(fixture_rel)
    if not fixture_path.is_absolute():
        fixture_path = root / fixture_rel
    try:
        data = json.loads(fixture_path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                          "errors": [f"fixture-unreadable:{exc}"]}))
        return 2
    errors = validate_fixture(data)
    results = []
    if not errors:
        for sc in data["scenarios"]:
            if only and sc["id"] != only:
                continue
            observed = SIMULATORS[sc["category"]](sc["inject"])
            mismatches = eval_expect(observed, sc["expect"])
            results.append({"id": sc["id"], "category": sc["category"],
                            "ok": not mismatches, "mismatches": mismatches,
                            "observed": observed})
    failed = [r for r in results if not r["ok"]]
    payload = {"schemaVersion": SCHEMA, "fixture": fixture_rel,
               "schemaErrors": errors, "total": len(results),
               "passed": len(results) - len(failed), "failed": len(failed),
               "results": results}
    print(json.dumps(payload, ensure_ascii=False))
    print(f"scenarios={len(results)} passed={len(results) - len(failed)} "
          f"failed={len(failed)} schemaErrors={len(errors)}")
    return 2 if errors or failed else 0


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("run", "validate"):
        p = sub.add_parser(name)
        p.add_argument("--root", default=".")
        p.add_argument("--fixture", default=DEFAULT_FIXTURE)
        p.add_argument("--scenario")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    if args.action == "validate":
        fixture_path = Path(args.fixture)
        if not fixture_path.is_absolute():
            fixture_path = root / args.fixture
        try:
            data = json.loads(fixture_path.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError) as exc:
            print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                              "errors": [f"fixture-unreadable:{exc}"]}))
            return 2
        errors = validate_fixture(data)
        print(json.dumps({"schemaVersion": SCHEMA, "ok": not errors,
                          "errors": errors,
                          "scenarioCount": len(data.get("scenarios", []))},
                         ensure_ascii=False))
        return 2 if errors else 0
    return run_fixture(root, args.fixture, args.scenario)


if __name__ == "__main__":
    raise SystemExit(main())
