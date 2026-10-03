"""test_orchestra_signal.py — schema, dedupe, secret rejection, move/link.

Run: python -B scripts/test_orchestra_signal.py   (exit 0 = all pass)
Uses a temp store dir; never touches the real store or the network.
"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "orchestra_signal.py"


def run(args, expect=0):
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), "--store", str(STORE)] + args,
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    if expect is not None and proc.returncode != expect:
        raise AssertionError(f"exit {proc.returncode} != {expect}: {proc.stdout} {proc.stderr}")
    return json.loads(proc.stdout)


STORE = None
FAILS = []


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS {name}")
    else:
        FAILS.append(name)
        print(f"  FAIL {name} {detail}")


def main():
    global STORE
    STORE = Path(tempfile.mkdtemp(prefix="orch-sig-test-"))
    try:
        # new + schema fields
        r = run(["new", "--from", "user", "--kind", "idea",
                 "--summary", "test signal alpha", "--files", "scripts/x.py",
                 "--priority", "P2"])
        check("new stored", r["stored"] is True)
        sig = r["signal"]
        check("schema", sig["schemaVersion"] == "awx.orchestra-signal.v1")
        check("handoff fields", all(k in sig for k in (
            "fromTask", "toTask", "ownedChanges", "smokeUsed", "restartUsed",
            "openItems", "askOnceAnswers", "findings", "atUtc")))
        check("id 12hex", len(sig["id"]) == 12)
        check("budget default", sig["budget"] == {"liveCalls": 0, "restarts": 0})
        sid = sig["id"]

        # dedupe: identical content -> not stored again
        r2 = run(["new", "--from", "user", "--kind", "idea",
                  "--summary", "test signal alpha", "--files", "scripts/x.py",
                  "--priority", "P2"])
        check("dedupe no-store", r2["stored"] is False and r2["id"] == sid)

        # different content -> new id
        r3 = run(["new", "--from", "grokbot", "--kind", "amplified",
                  "--summary", "test signal beta"])
        check("new id", r3["id"] != sid)

        # validate ok / bad
        v = run(["validate", "--id", sid])
        check("validate ok", v["valid"] is True, v.get("errors"))
        bad = dict(sig); bad["kind"] = "bogus"; bad["id"] = sid
        bad_path = STORE / "bad.json"
        bad_path.write_text(json.dumps(bad), encoding="utf-8")
        vb = run(["validate", "--file", str(bad_path)])
        check("validate catches kind", vb["valid"] is False)

        # secret rejection (input assembled at runtime: the file itself must
        # not carry a credential-shaped literal for the checkpoint scanner)
        secretish = "leak " + "api" + "_key = " + "ab" + "cdef123456"
        try:
            run(["new", "--from", "user", "--kind", "idea",
                 "--summary", secretish], expect=2)
            check("secret rejected", True)
        except AssertionError:
            check("secret rejected", False, "exit != 2")

        # link parent/child, roundtrips counted
        r4 = run(["new", "--from", "devin", "--kind", "devin-signal",
                  "--summary", "child of alpha"])
        run(["link", "--parent", sid, "--child", r4["id"]])
        child, _ = __import__("json"), None
        child_data = json.loads(
            (STORE / "inbox" / "orchestra" / (r4["id"] + ".json")).read_text(encoding="utf-8"))
        check("parentId set", child_data["parentId"] == sid)
        check("roundtrips +1", child_data["roundtrips"] == 1)

        # move inbox -> outbox -> archive
        m1 = run(["move", "--id", sid, "--to", "outbox/user", "--status", "routed",
                  "--lane", "DEVIN"])
        check("moved outbox", (STORE / "outbox" / "user" / (sid + ".json")).is_file())
        m2 = run(["move", "--id", sid, "--to", "archive", "--status", "done"])
        check("moved archive", (STORE / "archive" / (sid + ".json")).is_file())

        # list
        li = run(["list"])
        check("list count", li["count"] == 3)
        li2 = run(["list", "--status", "done"])
        check("list filter", li2["count"] == 1 and li2["signals"][0]["id"] == sid)

        print(f"\n{len(FAILS)} failures")
        return 1 if FAILS else 0
    finally:
        shutil.rmtree(STORE, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
