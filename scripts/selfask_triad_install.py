#!/usr/bin/env python3
"""selfask_triad_install.py — copy staged Codex triad agent TOMLs to a live
agents directory.

Canonical definitions live in `.codex/agents-staged/` (invisible to Codex).
Install copies them to:
  --target user     %USERPROFILE%/.codex/agents/   (default; glm_worker proven)
  --target project  <root>/.codex/agents/          (see W1-placement.md caveat:
                    project-scope spawn is bugged upstream — issue #26408)

`--dry-run` (default) prints the plan only. `--apply` copies, but refuses to
overwrite any existing target file (exit 3) — glm_worker.toml and foreign
agents are never touched. Global config.toml/auth.json are never opened.
Exit: 0 ok/dry-run, 2 usage, 3 existing-target block, 4 error.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import shutil
import sys

SCHEMA = "awx.selfask-triad-install.v1"
STAGED = ".codex/agents-staged"
PATTERN = "selfask_*.toml"
NAME_RE = re.compile(r'(?m)^\s*name\s*=\s*"([A-Za-z0-9_\-]+)"')


def validate(path: Path) -> list[str]:
    problems = []
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    m = NAME_RE.search(text)
    if not m:
        problems.append("missing-name")
    elif m.group(1) != path.stem:
        problems.append(f"name-mismatch:{m.group(1)}")
    if "developer_instructions" not in text:
        problems.append("missing-developer_instructions")
    if re.search(r'(?m)^\s*model(_provider)?\s*=', text):
        problems.append("model-or-provider-set(forbidden)")
    if 'sandbox_mode = "read-only"' not in text:
        problems.append("sandbox_mode-not-read-only")
    return problems


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    ap.add_argument("--target", choices=["user", "project"], default="user")
    ap.add_argument("--apply", action="store_true",
                    help="actually copy; default is dry-run")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    root = Path(args.root).resolve()
    staged = root / STAGED
    dest = (Path.home() / ".codex" / "agents" if args.target == "user"
            else root / ".codex" / "agents")
    payload = {"schemaVersion": SCHEMA, "generatedAtUtc":
               dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
               "mode": "apply" if args.apply else "dry-run",
               "target": args.target, "dest": str(dest), "files": []}

    sources = sorted(staged.glob(PATTERN)) if staged.is_dir() else []
    if not sources:
        payload["error"] = f"no staged files in {staged}"
        print(json.dumps(payload, ensure_ascii=False))
        return 4

    blocked = []
    for src in sources:
        problems = validate(src)
        dst = dest / src.name
        entry = {"src": str(src), "dst": str(dst), "problems": problems,
                 "exists": dst.exists()}
        if args.apply and not problems:
            if dst.exists():
                blocked.append(src.name)
                entry["action"] = "skipped-existing"
            else:
                dest.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, dst)
                entry["action"] = "copied"
        else:
            entry["action"] = ("validate-failed" if problems else
                               ("would-skip-existing" if dst.exists()
                                else "would-copy"))
        payload["files"].append(entry)

    if blocked:
        payload["error"] = ("existing target files not overwritten: "
                            + ",".join(blocked))
        print(json.dumps(payload, ensure_ascii=False))
        return 3
    if args.apply:
        bad = [f for f in payload["files"] if f["problems"]]
        if bad:
            payload["error"] = "validation failed; nothing applied"
            print(json.dumps(payload, ensure_ascii=False))
            return 4
    print(json.dumps(payload, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
