#!/usr/bin/env python3
"""Offline cost/latency simulator: Jev Choice factMeta vs baseline highModel.

Estimates - before any live benchmark - what replacing the FACT_META_CHECK
label call in FactVerifierService with `typesafe-ai/jev` would cost, at several
Jev failure rates. Every failure falls back to the baseline highModel call
(serial: Jev attempt + baseline call). All numbers are OFFLINE ESTIMATES:
fixture-derived cost, assumption-flagged latencies; paidAPIbenchmark=NOT_RUN.

Usage:
  python -B scripts/eval_jev_fact_meta_budget.py
    [--checks N] [--failure-rates 0,0.01,0.05,0.10,0.20]
    [--false-mismatch-rate F] [--jev-cost USD] [--baseline-cost USD]
    [--jev-p50 MS] [--jev-p95 MS] [--baseline-p50 MS] [--baseline-p95 MS]
    [--seed N]
Exit 0 = report produced.
"""
import argparse
import json
import random
import sys
from math import exp, log
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "data/agent-handoff/codex-jev-assist/jev_fact_meta_fixtures.json"
Z95 = 1.6448536269514722


def _fixture_jev_cost(default):
    """Mean billedUsd across accepted fixtures; default when unavailable."""
    try:
        data = json.loads(FIXTURES.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default
    costs = [f["expect"]["billedUsd"] for f in data.get("fixtures", [])
             if (f.get("expect") or {}).get("billedUsd")]
    try:
        return sum(float(c) for c in costs) / len(costs) if costs else default
    except (TypeError, ValueError):
        return default


def _lognormal(p50, p95):
    """Lognormal (mu, sigma) calibrated so exp(mu)=p50 and q95=p95."""
    mu = log(p50)
    sigma = max(0.0, (log(p95) - mu) / Z95)
    return mu, sigma


def _percentile(samples, q):
    ordered = sorted(samples)
    if not ordered:
        return 0.0
    idx = min(len(ordered) - 1, max(0, int(round(q * (len(ordered) - 1)))))
    return ordered[idx]


def simulate(rng, checks, failure_rate, false_mismatch_rate,
             jev_dist, base_dist, jev_cost, base_cost):
    latencies, cost, jev_calls, fallback_calls, false_mismatch = [], 0.0, 0, 0, 0
    for _ in range(checks):
        jev_calls += 1
        latency = rng.lognormvariate(*jev_dist)
        cost += jev_cost
        if rng.random() < failure_rate:
            fallback_calls += 1
            latency += rng.lognormvariate(*base_dist)
            cost += base_cost
        elif rng.random() < false_mismatch_rate:
            # Jev returned MISMATCH for a truly-consistent pair: product impact
            # is a wrong "정보 없음" answer (non-futureTech path).
            false_mismatch += 1
        latencies.append(latency)
    return {
        "jevCalls": jev_calls,
        "fallbackCalls": fallback_calls,
        "costUsd": cost,
        "p50Ms": _percentile(latencies, 0.50),
        "p95Ms": _percentile(latencies, 0.95),
        "falseMismatch": false_mismatch,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checks", type=int, default=10000)
    parser.add_argument("--failure-rates", default="0,0.01,0.05,0.10,0.20")
    parser.add_argument("--false-mismatch-rate", type=float, default=0.01)
    parser.add_argument("--jev-cost", type=float, default=None)
    parser.add_argument("--baseline-cost", type=float, default=0.003)
    parser.add_argument("--jev-p50", type=float, default=350.0)
    parser.add_argument("--jev-p95", type=float, default=1200.0)
    parser.add_argument("--baseline-p50", type=float, default=2500.0)
    parser.add_argument("--baseline-p95", type=float, default=8000.0)
    parser.add_argument("--seed", type=int, default=20261007)
    args = parser.parse_args()

    jev_cost = args.jev_cost if args.jev_cost is not None else _fixture_jev_cost(0.0004)
    jev_dist = _lognormal(args.jev_p50, args.jev_p95)
    base_dist = _lognormal(args.baseline_p50, args.baseline_p95)
    rates = [float(x) for x in args.failure_rates.split(",") if x.strip() != ""]

    rng = random.Random(args.seed)
    baseline = {"calls": args.checks, "costUsd": args.checks * args.baseline_cost}
    lat_samples = [rng.lognormvariate(*base_dist) for _ in range(args.checks)]
    baseline["p50Ms"] = _percentile(lat_samples, 0.50)
    baseline["p95Ms"] = _percentile(lat_samples, 0.95)

    rows = [simulate(rng, args.checks, rate, args.false_mismatch_rate,
                     jev_dist, base_dist, jev_cost, args.baseline_cost)
            for rate in rates]

    print("# Jev factMeta offline budget simulation (NOT a live benchmark)")
    print()
    print("## Parameters")
    print("| key | value | source |")
    print("|---|---|---|")
    print(f"| checks per scenario | {args.checks} | CLI |")
    print(f"| jev cost/call | ${jev_cost:.6f} | fixtures mean billedUsd "
          f"({FIXTURES.relative_to(ROOT).as_posix()}) |")
    print(f"| baseline highModel cost/call | ${args.baseline_cost:.6f} | ASSUMPTION |")
    print(f"| jev latency p50/p95 | {args.jev_p50:.0f}/{args.jev_p95:.0f} ms | ASSUMPTION |")
    print(f"| baseline latency p50/p95 | {args.baseline_p50:.0f}/{args.baseline_p95:.0f} ms | ASSUMPTION |")
    print(f"| false-MISMATCH rate (of accepted verdicts) | {args.false_mismatch_rate:.4f} | ASSUMPTION |")
    print(f"| rng seed | {args.seed} | deterministic |")
    print()
    print("## Baseline (highModel only, no Jev)")
    print("| calls | costUsd | p50Ms | p95Ms |")
    print("|---|---|---|---|")
    print(f"| {baseline['calls']} | {baseline['costUsd']:.4f} | "
          f"{baseline['p50Ms']:.0f} | {baseline['p95Ms']:.0f} |")
    print()
    print("## Jev replacement scenarios (failure -> serial fallback to highModel)")
    print("| jevFailRate | jevCalls | fallbackCalls | totalCalls | costUsd | "
          "deltaVsBaselineUsd | p50Ms | p95Ms | falseMismatch |")
    print("|---|---|---|---|---|---|---|---|---|")
    for rate, row in zip(rates, rows):
        delta = row["costUsd"] - baseline["costUsd"]
        print(f"| {rate:.2f} | {row['jevCalls']} | {row['fallbackCalls']} | "
              f"{row['jevCalls'] + row['fallbackCalls']} | {row['costUsd']:.4f} | "
              f"{delta:+.4f} | {row['p50Ms']:.0f} | {row['p95Ms']:.0f} | "
              f"{row['falseMismatch']} |")
    print()
    print("## Notes")
    print("- fallbackToBaseline keeps the existing highModel FACT_META_CHECK call; "
          "every Jev failure still pays the baseline price plus the wasted Jev attempt.")
    print("- falseMismatch counts wrong MISMATCH verdicts on consistent pairs: non-futureTech "
          "answers are replaced by the Korean 'no information' refusal string "
          "(FactVerifierService MISMATCH branch); futureTech keeps "
          "the labeled answer but memory stays fail-closed.")
    print("- paidAPIbenchmark=NOT_RUN: no live calls; free-window ended 2026-09-26 "
          "(JevEvaluationRuntime demo.jev.free-window-end).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
