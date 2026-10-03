#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Self-test for p6dbg_jev_fault_modes.py — loopback only."""
import json, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts/p6dbg_jev_fault_modes.py"

def run(*args):
    p = subprocess.run([sys.executable, "-B", str(TOOL), *args],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=60)
    try:
        return p.returncode, [json.loads(p.stdout)]
    except json.JSONDecodeError:
        pass
    lines = [l for l in p.stdout.splitlines() if l.strip().startswith("{")]
    return p.returncode, [json.loads(l) for l in lines]

def main():
    fails = []
    rc, out = run("--list")
    if not (rc == 0 and len(out[0]["scenarios"]) >= 6):
        fails.append("list")

    # 401 x3 -> 200 : fire 5, expect [401,401,401,200,200]
    rc, out = run("--scenario", "auth_blocked_recover", "--fire", "5")
    if not (rc == 0 and out[-1]["observed"] == [401, 401, 401, 200, 200]
            and out[-1]["flag_recovery_observable"]):
        fails.append(("auth_blocked_recover", out))

    # plan gate x3 -> 200
    rc, out = run("--scenario", "plan_gate_recover", "--fire", "4")
    if not (out[-1]["observed"] == [403, 403, 403, 200]):
        fails.append(("plan_gate", out))

    # schema violation always 200-with-bad-probs
    rc, out = run("--scenario", "schema_violation", "--fire", "2")
    if not (out[-1]["observed"] == [200, 200]):
        fails.append(("schema", out))

    # timeout_then_ok: 2 hangs then 200 — hang yields client error
    rc, out = run("--scenario", "timeout_then_ok", "--fire", "3")
    obs = out[-1]["observed"]
    if not (obs[0] != 200 and obs[-1] == 200):
        fails.append(("timeout", out))

    if fails:
        print(json.dumps({"status": "FAIL", "fails": fails}, ensure_ascii=False))
        return 1
    print(json.dumps({"status": "PASS", "cases": 5}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
