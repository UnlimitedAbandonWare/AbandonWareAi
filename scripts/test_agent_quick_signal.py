"""test_agent_quick_signal.py — emit/inbox/copy/done over a temp store.

Run: python -B scripts/test_agent_quick_signal.py   (exit 0 = all pass)
Uses a temp --store and --outdir; never touches the real orchestra store,
the clipboard, or the network.
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
TOOL = ROOT / "scripts" / "agent_quick_signal.py"

STORE = None
OUTDIR = None
FAILS = []


def run(args, expect=0):
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL),
         "--store", str(STORE), "--outdir", str(OUTDIR), "--json"] + args,
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    if expect is not None and proc.returncode != expect:
        raise AssertionError(
            f"exit {proc.returncode} != {expect}: {proc.stdout} {proc.stderr}")
    return json.loads(proc.stdout)


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS {name}")
    else:
        FAILS.append(name)
        print(f"  FAIL {name} {detail}")


def main():
    global STORE, OUTDIR
    base = Path(tempfile.mkdtemp(prefix="aqs-test-"))
    STORE = base / "store"
    OUTDIR = base / "outbox"
    try:
        # emit: signal stored + routed + PASTE file, single call
        r = run(["emit", "--from", "devin", "--to", "codex",
                 "--summary", "quick alpha handoff",
                 "--files", "scripts/x.py,scripts/y.py", "--no-classify"])
        check("emit ok", r["action"] == "emit" and r["id"], r)
        sid = r["id"]
        check("emit kind default", r["kind"] == "devin-signal")
        sig_path = STORE / "inbox" / "codex" / (sid + ".json")
        check("signal stored inbox/codex", sig_path.is_file())
        sig = json.loads(sig_path.read_text(encoding="utf-8"))
        check("routed status", sig["status"] == "routed" and sig["lane"])
        check("files parsed", sig["files"] == ["scripts/x.py", "scripts/y.py"])
        paste = Path(r["pasteFile"])
        check("paste file under outdir", paste.is_file()
              and str(OUTDIR) in str(paste))
        check("mallow line", (r["mallowLine"] or "").startswith("말로:"))

        # dedupe emit: same content -> same id, not duplicated
        r2 = run(["emit", "--from", "devin", "--to", "codex",
                  "--summary", "quick alpha handoff",
                  "--files", "scripts/x.py,scripts/y.py", "--no-classify"])
        check("emit dedupe", r2["id"] == sid and r2["stored"] is False)

        # inbox: only the waiting signal for that agent
        li = run(["inbox", "--agent", "codex"])
        check("inbox count", li["count"] == 1 and li["signals"][0]["id"] == sid)
        le = run(["inbox", "--agent", "devin"])
        check("inbox filtered", le["count"] == 0)
        lc = run(["counts"])
        check("counts line", lc["waiting"]["codex"] == 1
              and "Codex: 1" in lc["line"])

        # copy --id --print: re-render paste and return body (no clipboard)
        cp = run(["copy", "--id", sid, "--print"])
        check("copy by id", cp["clipboard"] == "printed" and sid in cp["body"])

        # copy --agent --print: latest PASTE_<AGENT>_*.txt
        cp2 = run(["copy", "--agent", "codex", "--print"])
        check("copy by agent", sid in cp2["body"])

        # secret-looking summary refused by the signal layer (exit 2)
        secretish = "leak " + "api" + "_key = " + "ab" + "cdef123456"
        try:
            run(["emit", "--from", "devin", "--to", "codex",
                 "--summary", secretish, "--no-classify"], expect=2)
            check("secret rejected", True)
        except AssertionError:
            check("secret rejected", False, "exit != 2")

        # done: signal moves to archive and leaves the inbox listing
        dn = run(["done", "--id", sid])
        check("done ok", dn["status"] == "done")
        check("archived", (STORE / "archive" / (sid + ".json")).is_file())
        check("inbox empty after done",
              not (STORE / "inbox" / "codex" / (sid + ".json")).exists())
        li2 = run(["inbox", "--agent", "codex"])
        check("inbox count after done", li2["count"] == 0)
        arch = json.loads(
            (STORE / "archive" / (sid + ".json")).read_text(encoding="utf-8"))
        check("archive status done", arch["status"] == "done")

        print(f"\n{len(FAILS)} failures")
        return 1 if FAILS else 0
    finally:
        shutil.rmtree(base, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
