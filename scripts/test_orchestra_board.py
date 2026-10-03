"""test_orchestra_board.py — <=30 lines, lease-overlap display, cost order.

Run: python -B scripts/test_orchestra_board.py   (exit 0 = all pass)
Board build is called in-process with a fabricated store; digest subprocess
is allowed to fail (recorded as digestError, never fatal).
"""
import json
import shutil
import sys
import tempfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestra_board as board  # noqa: E402

FAILS = []


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS {name}")
    else:
        FAILS.append(name)
        print(f"  FAIL {name} {detail}")


def fake_signal(sid, sender, files, status="in-progress", part="inbox", agent="codex"):
    return {
        "schemaVersion": "awx.orchestra-signal.v1",
        "fromTask": None, "toTask": None, "fromChat": None, "lastCheckpoint": None,
        "ownedChanges": [], "smokeUsed": 0, "restartUsed": 0, "openItems": [],
        "askOnceAnswers": [], "findings": [], "atUtc": "2026-10-03T00:00:00+00:00",
        "id": sid, "parentId": None, "from": sender, "kind": "devin-signal",
        "lane": "DEVIN", "priority": "P1", "evidenceTier": "확인됨",
        "summary": "sig " + sid, "notes": "", "files": files,
        "budget": {"liveCalls": 2, "restarts": 0},
        "budgetUsed": {"liveCalls": 1, "restarts": 0},
        "status": status, "pasteFile": None, "atKst": "2026-10-03 09:00 KST",
        "children": [], "roundtrips": 0,
    }


def main():
    tmp = Path(tempfile.mkdtemp(prefix="orch-board-test-"))
    try:
        store = tmp / "store"
        (store / "inbox" / "codex").mkdir(parents=True)
        sig = fake_signal("abcd1234ef01", "devin", ["scripts/shared.py"])
        (store / "inbox" / "codex" / "abcd1234ef01.json").write_text(
            json.dumps(sig), encoding="utf-8")

        # fabricate an active lease overlapping the signal file
        lock = tmp / "__patch_drop__" / "source-edit-locks" / "foreign-task.lock"
        lock.mkdir(parents=True)
        (lock / "lease.json").write_text(json.dumps({
            "topic": "foreign-task", "status": "active",
            "expiresAtUtc": "2999-01-01T00:00:00+00:00",
            "mutationAllowed": True,
            "targetPaths": ["scripts/shared.py", "scripts/other.py"]}),
            encoding="utf-8")

        # point board at tmp root: patch store arg as relative path
        rel_store = store.relative_to(tmp).as_posix()
        data = board.build(str(tmp), rel_store, 24)
        check("signal counted", data["signalCount"] == 1, data["signalCount"])
        check("lease overlap shown",
              any(o["lease"] == "foreign-task" and o["signal"] == "abcd1234ef01"
                  for o in data["overlaps"]), data["overlaps"])
        check("budget shown", data["budgets"] and
              data["budgets"][0]["liveCalls"] == "1/2", data["budgets"])
        check("cost order", data["costOrder"] ==
              ["codex-credit", "external-paid", "free", "ollama-local"])
        check("agent row", data["agents"]["codex"]["working"] == 1,
              data["agents"]["codex"])

        md = board.render_md(data)
        check("md <=30 lines", len(md) <= 30, len(md))
        check("md has overlap", any("foreign-task" in l for l in md))
        check("md has cost order", any("codex-credit" in l for l in md))
        check("md has paste line", any("next paste" in l for l in md))

        print(f"\n{len(FAILS)} failures")
        return 1 if FAILS else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
