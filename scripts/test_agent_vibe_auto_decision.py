"""Self-check for agent_vibe_auto_decision.py — synthetic decisions only.

Run: python -B scripts/test_agent_vibe_auto_decision.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed.
"""
from __future__ import annotations

import datetime as dt
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "agent_vibe_auto_decision.py"


def run_tool(*args: str, env_extra: dict | None = None) -> tuple[int, dict]:
    import os
    env = dict(os.environ)
    env.pop("AWX_AGENT_ALLOW_PAID_MODELS", None)
    env.pop("AWX_CREDIT_BUDGET", None)
    if env_extra:
        env.update(env_extra)
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), *args, "--json"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=env)
    try:
        payload = json.loads(proc.stdout.strip())
    except json.JSONDecodeError:
        payload = {"stdout": proc.stdout[:200], "stderr": proc.stderr[:200]}
    return proc.returncode, payload


def make_lease(root: Path, topic: str, owner: str, targets: list[str]) -> None:
    lock = (root / "__patch_drop__" / "source-edit-locks"
            / f"{topic}.lock")
    lock.mkdir(parents=True, exist_ok=True)
    exp = (dt.datetime.now(dt.timezone.utc)
           + dt.timedelta(minutes=30)).isoformat()
    (lock / "lease.json").write_text(json.dumps({
        "ownerId": owner, "expiresAtUtc": exp,
        "mutationAllowed": True, "coordinationMode": "target-scoped",
        "root": str(root), "targetPaths": targets}), encoding="utf-8")


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    cases = []

    code, out = run_tool(
        "--action", "apply additive local DDL via db_agent dry-run then apply")
    cases.append(("auto-additive-local", code == 0
                  and out.get("verdict") == "AUTO", out))

    code, out = run_tool("--action", "git commit and push the work")
    cases.append(("ask-git-remote", code == 3
                  and out.get("verdict") == "ASK_ONCE"
                  and "git-remote-mutate" in out.get("gates", []), out))

    code, out = run_tool("--action", "run TRUNCATE TABLE users then reseed")
    cases.append(("ask-destructive", code == 3
                  and "destructive-data" in out.get("gates", []), out))

    code, out = run_tool(
        "--action", "print the stored api_key value for debugging")
    cases.append(("ask-secret-emission", code == 3
                  and "secret-emission" in out.get("gates", []), out))

    code, out = run_tool(
        "--action", "enable task_ask callback queue for product")
    cases.append(("ask-banned-feature", code == 3
                  and "banned-feature" in out.get("gates", []), out))

    # Repo guard yaml carries authorized_credit_budget=62500 (2026-09-30
    # user approval) — no env needed for the AUTO verdict.
    code, out = run_tool("--root", str(ROOT),
                         "--action",
                         "fan out provider generation to gemini api")
    cases.append(("paid-auto-via-yaml-budget", code == 0
                  and out.get("verdict") == "AUTO", out))

    code, out = run_tool(
        "--action", "fan out provider generation to gemini api",
        env_extra={"AWX_AGENT_ALLOW_PAID_MODELS": "1"})
    cases.append(("paid-env-allows-auto", code == 0
                  and out.get("verdict") == "AUTO", out))

    # Kill switch: explicit false value blocks paid even with the repo's
    # positive yaml credit budget (2026-10-03 default-ON semantics).
    code, out = run_tool(
        "--root", str(ROOT),
        "--action", "fan out provider generation to gemini api",
        env_extra={"AWX_AGENT_ALLOW_PAID_MODELS": "0"})
    cases.append(("ask-paid-kill-switch", code == 3
                  and "paid-provider" in out.get("gates", []), out))

    code, out = run_tool("--action", "reopen autograde B scanner")
    cases.append(("ask-reopen-locked", code == 3
                  and "reopen-locked-scope" in out.get("gates", []), out))

    # Sole-remote judgement: a non-AbandonWareAi origin warns (ASK_ONCE).
    with tempfile.TemporaryDirectory() as tmp:
        trepo = Path(tmp) / "repo"
        trepo.mkdir()
        subprocess.run(["git", "-C", str(trepo), "init", "-b", "main"],
                       check=True, capture_output=True)
        subprocess.run(["git", "-C", str(trepo), "remote", "add", "origin",
                        "https://github.com/UnlimitedAbandonWare/OldRepo"],
                       check=True, capture_output=True)
        code, out = run_tool("--root", str(trepo),
                             "--action", "narrow docs patch",
                             "--paths", "docs/x.md")
        cases.append(("ask-origin-mismatch", code == 3
                      and "origin-mismatch" in out.get("gates", []), out))

    with tempfile.TemporaryDirectory() as tmp:
        troot = Path(tmp)
        make_lease(troot, "other-topic", "codex", ["scripts/x.py"])
        code, out = run_tool("--root", str(troot),
                             "--action", "narrow patch to scripts/x.py",
                             "--paths", "scripts/x.py")
        cases.append(("hold-foreign-lease", code == 4
                      and out.get("verdict") == "HOLD"
                      and any(c.get("code") == "foreign-lease"
                              for c in out.get("counterexamples", [])),
                      out))
        code, out = run_tool("--root", str(troot),
                             "--action", "patch scripts/other.py",
                             "--paths", "scripts/other.py")
        cases.append(("auto-disjoint-path", code == 0
                      and out.get("verdict") == "AUTO", out))
        # Temp root has no guard yaml -> no authorized budget -> still ASK.
        code, out = run_tool("--root", str(troot),
                             "--action",
                             "fan out provider generation to gemini api")
        cases.append(("ask-paid-provider", code == 3
                      and "paid-provider" in out.get("gates", []), out))
        # Same temp root + session env budget -> AUTO via env path alone.
        code, out = run_tool("--root", str(troot),
                             "--action",
                             "fan out provider generation to gemini api",
                             env_extra={"AWX_CREDIT_BUDGET": "62500"})
        cases.append(("paid-auto-via-env-budget", code == 0
                      and out.get("verdict") == "AUTO", out))

    code, out = run_tool("--action", "t")
    cases.append(("short-action-auto", code == 0, out))

    failed = [n for n, ok, _ in cases if not ok]
    for n, ok, o in cases:
        print(f"{'PASS' if ok else 'FAIL'} {n} :: "
              f"{json.dumps(o, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
