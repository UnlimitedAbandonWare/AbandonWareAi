#!/usr/bin/env python3
"""Offline verification for agy account-switch resilience (stdlib only).

Covers D1-D4 of PASTE_DEVIN_AGY_ACCOUNT_SWITCH_RESILIENCE_20261005:
  - agy_presence.py set/get/is-available + stale-SWITCHING collapse
  - account masking + zero secret-like material in JSON artifacts
  - orchestra route-rules.json AGY_RESEARCH fallback
  - agy_session_seed.py --on-exit snapshot + --launcher resume line
  - launcher/auth wiring present in Start-Agy-CLI.bat and agy_auth_switch.ps1

Runs fully offline: the presence state file is redirected to a temp file
via AWX_AGY_PRESENCE_FILE; the only repo paths touched are the generated
var/agy-seed/last_exit_context.json snapshot (a declared deliverable) and
data/agent-handoff/agy-presence.json (restored at the end).

Exits 0 with 'ALL PASS' on success, 1 otherwise.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRESENCE_PY = ROOT / "scripts" / "agy_presence.py"
SEED_PY = ROOT / "scripts" / "agy_session_seed.py"
RULES = ROOT / "scripts" / "fixtures" / "orchestra" / "route-rules.json"
BAT = ROOT / "Start-Agy-CLI.bat"
PS1 = ROOT / "scripts" / "agy_auth_switch.ps1"
SNAP = ROOT / "var" / "agy-seed" / "last_exit_context.json"
REAL_PRESENCE = ROOT / "data" / "agent-handoff" / "agy-presence.json"
KST = timezone(timedelta(hours=9))

SECRETISH = re.compile(
    r"(?i)(api[_-]?key|token|secret|password|passwd|credential|bearer)"
    r"[\s:=\"']+[^\s\"']{4,}")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail)


def run_presence(*args, env=None):
    e = dict(os.environ)
    e.update(env or {})
    return subprocess.run([sys.executable, "-B", str(PRESENCE_PY), *args],
                          capture_output=True, text=True, env=e, cwd=ROOT)


def run_seed(*args):
    return subprocess.run([sys.executable, "-B", str(SEED_PY), *args],
                          capture_output=True, text=True, cwd=ROOT)


def main() -> int:
    tmp = tempfile.NamedTemporaryFile(prefix="agy-presence-", suffix=".json",
                                      delete=False)
    tmp.close()
    env = {"AWX_AGY_PRESENCE_FILE": tmp.name}

    try:
        # --- T1 schema + set/get roundtrip --------------------------------
        r = run_presence("set", "--status", "ONLINE", "--account", "A", env=env)
        check("T1 set exit0", r.returncode == 0)
        doc = json.loads(Path(tmp.name).read_text(encoding="utf-8"))
        check("T1 schema keys",
              set(doc) == {"status", "account", "updatedAtKst"})
        check("T1 status ONLINE", doc["status"] == "ONLINE")
        r = run_presence("get", env=env)
        out = json.loads(r.stdout)
        check("T1 get effectiveStatus ONLINE",
              out["effectiveStatus"] == "ONLINE" and out["account"] == "A")

        # --- T2 transitions ----------------------------------------------
        for st in ("SWITCHING", "OFFLINE"):
            r = run_presence("set", "--status", st, env=env)
            check(f"T2 transition ->{st}", r.returncode == 0)
            doc = json.loads(Path(tmp.name).read_text(encoding="utf-8"))
            check(f"T2 stored {st}", doc["status"] == st)
        check("T2 account preserved without --account",
              doc["account"] == "A")

        # --- T3 is-available matrix ---------------------------------------
        def avail():
            rr = run_presence("is-available", env=env)
            return rr.returncode, json.loads(rr.stdout)

        run_presence("set", "--status", "ONLINE", env=env)
        rc, out = avail()
        check("T3 fresh ONLINE available", rc == 0 and out["available"])

        run_presence("set", "--status", "OFFLINE", env=env)
        rc, out = avail()
        check("T3 OFFLINE unavailable", rc == 1 and not out["available"])

        run_presence("set", "--status", "SWITCHING", env=env)
        rc, out = avail()
        check("T3 fresh SWITCHING unavailable",
              rc == 1 and not out["available"])

        # stale SWITCHING (>30s) collapses to OFFLINE
        old = (datetime.now(KST) - timedelta(seconds=90)).isoformat(
            timespec="seconds")
        Path(tmp.name).write_text(json.dumps(
            {"status": "SWITCHING", "account": "A", "updatedAtKst": old}),
            encoding="utf-8")
        rc, out = avail()
        check("T3 stale SWITCHING -> OFFLINE",
              out["effectiveStatus"] == "OFFLINE" and not out["available"])

        # stale ONLINE stays ONLINE but is flagged stale
        Path(tmp.name).write_text(json.dumps(
            {"status": "ONLINE", "account": "A", "updatedAtKst": old}),
            encoding="utf-8")
        rc, out = avail()
        check("T3 stale ONLINE still available+flagged",
              rc == 0 and out["available"] and out["stale"])

        # missing file -> unavailable
        env2 = {"AWX_AGY_PRESENCE_FILE": tmp.name + ".gone"}
        rr = run_presence("is-available", env=env2)
        check("T3 missing file unavailable", rr.returncode == 1)

        # --- T4 account masking --------------------------------------------
        run_presence("set", "--status", "ONLINE",
                     "--account", "user@gmail.com", env=env)
        doc = json.loads(Path(tmp.name).read_text(encoding="utf-8"))
        check("T4 email masked", doc["account"] == "MASKED")
        run_presence("set", "--status", "ONLINE", "--account", "B", env=env)
        doc = json.loads(Path(tmp.name).read_text(encoding="utf-8"))
        check("T4 alias kept", doc["account"] == "B")

        # --- T5 zero secret-like material ----------------------------------
        blob = Path(tmp.name).read_text(encoding="utf-8")
        check("T5 presence.json no secretish",
              not SECRETISH.search(blob) and not EMAIL.search(blob))

        # --- T6 route-rules fallback ----------------------------------------
        rules = json.loads(RULES.read_text(encoding="utf-8"))
        check("T6 AGY_RESEARCH fallback present",
              "fallback" in rules["lanes"]["AGY_RESEARCH"])
        check("T6 fallback value",
              rules["lanes"]["AGY_RESEARCH"]["fallback"]
              == "DEVIN_AUTONOMOUS_OR_CACHED_SEED")

        # --- T7 exit snapshot + resume line ---------------------------------
        r = run_seed("--on-exit")
        check("T7 --on-exit exit0", r.returncode == 0)
        check("T7 snapshot written", SNAP.is_file())
        snap = json.loads(SNAP.read_text(encoding="utf-8"))
        check("T7 snapshot keys",
              {"exitedAtKst", "lastWork"} <= set(snap))
        blob = SNAP.read_text(encoding="utf-8")
        check("T7 snapshot no secretish/email",
              not SECRETISH.search(blob) and not EMAIL.search(blob))
        r = run_seed("--launcher")
        check("T7 launcher exit0", r.returncode == 0)
        check("T7 resume line printed",
              "[Start-Agy-CLI] resume:" in r.stdout)

        # --- T8 launcher/auth wiring ----------------------------------------
        bat = BAT.read_text(encoding="utf-8")
        check("T8 Start-Agy-CLI presence ONLINE",
              "agy_presence" in bat and "ONLINE" in bat)
        check("T8 Start-Agy-CLI presence OFFLINE+on-exit",
              "OFFLINE" in bat and "--on-exit" in bat)
        ps1 = PS1.read_text(encoding="utf-8")
        check("T8 agy_auth_switch presence wiring",
              "agy_presence" in ps1 and "SWITCHING" in ps1)

        # --- leave the real SSOT reflecting the live agy.exe state --------
        try:
            tl = subprocess.run(["tasklist", "/FI", "IMAGENAME eq agy.exe",
                                 "/FO", "CSV", "/NH"],
                                capture_output=True, text=True).stdout
            live = "agy.exe" in tl
        except Exception:
            live = False
        real = "ONLINE" if live else "OFFLINE"
        r = run_presence("set", "--status", real)
        check(f"T9 real presence.json written ({real})",
              r.returncode == 0 and REAL_PRESENCE.is_file())
        blob = REAL_PRESENCE.read_text(encoding="utf-8")
        check("T9 real presence no secretish/email",
              not SECRETISH.search(blob) and not EMAIL.search(blob))
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass

    failed = [n for n, ok, _ in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    if failed:
        print("FAILED:", ", ".join(failed))
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
