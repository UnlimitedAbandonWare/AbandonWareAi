"""Self-check for agent_signal_digest.py — read-only, $0, no network.

Run: python -B scripts/test_agent_signal_digest.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "agent_signal_digest.py"
SCHEMA = "awx.agent-signal-digest.v1"
REQUIRED = ("leases", "journals", "handoffs", "git", "grok", "events", "errors")


def run_tool(*args: str) -> tuple[int, str, str]:
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(ROOT), timeout=30)
    return proc.returncode, proc.stdout, proc.stderr


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    cases = []

    # 1. --json on the live root: schema + required fields + bound counts.
    code, out, err = run_tool("--json")
    try:
        d = json.loads(out)
    except ValueError:
        d = None
    cases.append(("json-exit0", code == 0, {"exit": code, "stderr": err[:160]}))
    cases.append(("json-schema-keys",
                  isinstance(d, dict)
                  and d.get("schemaVersion") == SCHEMA
                  and all(k in d for k in REQUIRED),
                  {"keys": sorted(d.keys()) if isinstance(d, dict) else out[:160]}))
    if isinstance(d, dict):
        cases.append(("bounded-slices",
                      len(d.get("journals") or []) <= 3
                      and len((d.get("handoffs") or {}).get("recent") or []) <= 3
                      and len((d.get("grok") or {}).get("prompts") or []) <= 3,
                      {"j": len(d.get("journals") or []),
                       "h": len((d.get("handoffs") or {}).get("recent") or []),
                       "g": len((d.get("grok") or {}).get("prompts") or [])}))
        cases.append(("shapes",
                      isinstance(d.get("leases"), dict)
                      and isinstance(d.get("errors"), list)
                      and isinstance(d.get("git"), dict)
                      and isinstance(d.get("events"), dict),
                      {}))
        cases.append(("duration-recorded",
                      isinstance(d.get("durationMs"), int) and d["durationMs"] >= 0,
                      {"durationMs": d.get("durationMs")}))
    else:
        for name in ("bounded-slices", "shapes", "duration-recorded"):
            cases.append((name, False, {"stdout": out[:160]}))

    # 2. Default markdown on the live root: compact <=20 lines, section labels.
    code, out, err = run_tool()
    lines = [l for l in out.splitlines() if l.strip()]
    cases.append(("markdown-exit0", code == 0, {"exit": code, "stderr": err[:160]}))
    cases.append(("markdown-compact",
                  1 <= len(lines) <= 20
                  and lines[0].startswith("# Agent Signal Digest")
                  and any("leases" in l for l in lines)
                  and any("git" in l for l in lines),
                  {"lines": len(lines)}))

    # 3. Empty temp root: graceful degradation, still exit 0 with valid JSON.
    with tempfile.TemporaryDirectory() as tmp:
        code, out, err = run_tool("--json", "--root", tmp)
        try:
            d2 = json.loads(out)
        except ValueError:
            d2 = None
        cases.append(("empty-root-exit0", code == 0 and isinstance(d2, dict),
                      {"exit": code, "stderr": err[:160]}))
        if isinstance(d2, dict):
            degraded = (d2["git"].get("ok") is not True
                        or any("git" in e for e in d2["errors"]))
            cases.append(("empty-root-degrades",
                          all(k in d2 for k in REQUIRED)
                          and degraded
                          and "Traceback" not in err,
                          {"errors": d2.get("errors"),
                           "gitOk": d2["git"].get("ok")}))

    failed = [n for n, ok, _ in cases if not ok]
    for n, ok, o in cases:
        print(f"{'PASS' if ok else 'FAIL'} {n} :: "
              f"{json.dumps(o, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
