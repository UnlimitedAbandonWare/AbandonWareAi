"""Self-check for selfask_triad.py — synthetic branch answers only.

Run: python -B scripts/test_selfask_triad.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed.

Cases: three P6 fixtures judged (ASK_ONCE conflict / AUTO / HOLD), missing
delivery marker FAIL, nonexistent file:line citation FAIL, one branch NOT_RUN,
conflict detection, packet smoke (marker + 3 prompt files).

Scratch files go under data/agent-handoff/selfask-triad/_scratch (tempfile
dirs are unreliable under the desktop sandbox overlay).
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "selfask_triad.py"
FIX = (ROOT / "data" / "agent-handoff" / "selfask-triad" / "fixtures")
SCRATCH = (ROOT / "data" / "agent-handoff" / "selfask-triad" / "_scratch")


def run_tool(*args: str) -> tuple[int, dict, str]:
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(ROOT))
    try:
        payload = json.loads(proc.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        payload = {"stdout": proc.stdout[:300], "stderr": proc.stderr[:300]}
    return proc.returncode, payload, proc.stderr


def judge(d: str, a: str, c: str) -> tuple[int, dict, str]:
    return run_tool("judge", str(FIX / d / "definer.md"),
                    str(FIX / a / "aliaser.md"),
                    str(FIX / c / "challenger.md"), "--root", str(ROOT))


def branch_md(marker: str | None = "M", cite: str | None = None,
              stance: str = "SUPPORT") -> str:
    lines = ["## 1. finding"]
    if marker is not None:
        lines.append(f"task_received={marker}")
    lines += ["x", "## 2. evidence"]
    if cite:
        lines.append(f"- {cite} - ref")
    lines += ["## 3. uncertainty", "- none",
              "## 4. recommended next check", "- none",
              f"stance: {stance}", "evidence_tier: T2"]
    return "\n".join(lines) + "\n"


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    cases = []

    code, out, _ = judge("q1-wrapifenabled", "q1-wrapifenabled",
                         "q1-wrapifenabled")
    cases.append(("fixture-q1-ask-once", code == 3
                  and out.get("verdict") == "ASK_ONCE"
                  and len(out.get("conflicts", [])) >= 1, out))

    code, out, _ = judge("q2-complexmainrequest", "q2-complexmainrequest",
                         "q2-complexmainrequest")
    cases.append(("fixture-q2-auto", code == 0
                  and out.get("verdict") == "AUTO", out))

    code, out, _ = judge("q3-catchthrowable", "q3-catchthrowable",
                         "q3-catchthrowable")
    cases.append(("fixture-q3-hold", code == 4
                  and out.get("verdict") == "HOLD", out))

    # one branch NOT_RUN -> judged with remaining two, axis named
    code, out, _ = run_tool("judge", str(FIX / "q2-complexmainrequest"
                                         / "definer.md"),
                            "NOT_RUN",
                            str(FIX / "q2-complexmainrequest"
                                / "challenger.md"), "--root", str(ROOT))
    cases.append(("not-run-branch", code == 0
                  and out.get("verdict") == "AUTO"
                  and out.get("not_run") == ["aliaser"], out))

    td = SCRATCH
    if td.exists():
        shutil.rmtree(td)
    td.mkdir(parents=True)
    try:
        no_marker = td / "definer.md"
        no_marker.write_text(branch_md(marker=None), encoding="utf-8")
        good = td / "aliaser.md"
        good.write_text(branch_md(cite="scripts/selfask_triad.py:1"),
                        encoding="utf-8")
        good2 = td / "challenger.md"
        good2.write_text(branch_md(cite="scripts/selfask_triad.py:1"),
                         encoding="utf-8")
        code, out, _ = run_tool("judge", str(no_marker), str(good),
                                str(good2), "--root", str(ROOT))
        cases.append(("missing-marker-invalid",
                      code == 4 and out.get("verdict") == "HOLD"
                      and out.get("invalid") == ["definer"], out))

        bad_cite = td / "definer_bad.md"
        bad_cite.write_text(
            branch_md(cite="scripts/no_such_file_xyz.py:1"),
            encoding="utf-8")
        code, out, _ = run_tool("judge", str(bad_cite), str(good),
                                str(good2), "--root", str(ROOT))
        cases.append(("missing-cite-invalid",
                      code == 4 and out.get("verdict") == "HOLD"
                      and out.get("invalid") == ["definer"], out))

        oor = td / "definer_oor.md"
        oor.write_text(
            branch_md(cite="scripts/selfask_triad.py:999999"),
            encoding="utf-8")
        code, out, _ = run_tool("judge", str(oor), str(good), str(good2),
                                "--root", str(ROOT))
        cases.append(("cite-out-of-range-invalid",
                      code == 4 and out.get("verdict") == "HOLD", out))

        wrong_marker = td / "definer_wm.md"
        wrong_marker.write_text(branch_md(marker="OTHER"), encoding="utf-8")
        code, out, _ = run_tool("judge", str(wrong_marker), str(good),
                                str(good2), "--root", str(ROOT),
                                "--marker", "M")
        cases.append(("marker-mismatch-invalid",
                      code == 4 and out.get("verdict") == "HOLD", out))

        conflict = td / "conflict_case.md"
        conflict.write_text(
            branch_md(cite="scripts/selfask_triad.py:1",
                      stance="OPPOSE"), encoding="utf-8")
        code, out, _ = run_tool("judge", str(conflict), str(good),
                                str(good2), "--root", str(ROOT))
        cases.append(("conflict-detect-ask-once",
                      code == 3 and out.get("verdict") == "ASK_ONCE"
                      and any("selfask_triad.py:1" in c.get("anchor", "")
                              for c in out.get("conflicts", [])), out))
    finally:
        shutil.rmtree(td, ignore_errors=True)

    td = SCRATCH / "packet"
    td.mkdir(parents=True, exist_ok=True)
    try:
        code, out, _ = run_tool("packet", "--question",
                                "is x wired?", "--paths", "a/b.java",
                                "--out", td)
        pkt_dir = Path(out.get("packetDir", ""))
        cases.append(("packet-files", code == 0 and pkt_dir.is_dir()
                      and (pkt_dir / "definer.prompt.md").is_file()
                      and (pkt_dir / "aliaser.prompt.md").is_file()
                      and (pkt_dir / "challenger.prompt.md").is_file()
                      and "SELFASK-TRIAD-" in out.get("marker", "")
                      and "task_received=" + out.get("marker", "?")
                          in (pkt_dir / "definer.prompt.md").read_text(
                              encoding="utf-8"), out))
    finally:
        shutil.rmtree(td, ignore_errors=True)

    failed = 0
    for name, ok, detail in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name}")
        if not ok:
            failed += 1
            print("  detail:", json.dumps(detail, ensure_ascii=False)[:400])
    print(f"total={len(cases)} failed={failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
