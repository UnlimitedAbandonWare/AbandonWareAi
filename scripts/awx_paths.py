#!/usr/bin/env python3
"""AWX path registry resolver (stdlib only).

Registry: configs/agent-paths.yaml (restricted YAML subset).
Resolution order for `where <key>`:
  1. environment variable named by the entry's `env` field
  2. registry `path` (relative -> joined to repo root; %USERPROFILE% expands)
  3. first existing entry in `old_paths` (emits [AWX][path-alias] on stderr)
Special case: git.exe falls back to PATH lookup after the registry path.

Usage:
  python -B scripts/awx_paths.py where <key> [--must-exist]
  python -B scripts/awx_paths.py list [--json]
  python -B scripts/awx_paths.py check [--baseline <file>] [--write-baseline]
  python -B scripts/awx_paths.py moved [--last N]
  python -B scripts/awx_paths.py aliases --expired --plan

Library use:
  from awx_paths import ROOT, resolve, load_registry
"""
import json
import os
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "configs" / "agent-paths.yaml"
MOVED_LOG = ROOT / "data" / "agent-handoff" / "_path-moves" / "moved.jsonl"
DEFAULT_BASELINE = ROOT / "data" / "agent-handoff" / "_path-moves" / "hardcode-baseline.json"

_ENTRY_RE = re.compile(r"^  ([\w.\-]+):\s*$")
_FIELD_RE = re.compile(r"^    (\w+):\s*(.*)$")


def _parse_scalar(v):
    v = v.strip()
    if v in ("", "null", "~"):
        return None
    if v.startswith("[") and v.endswith("]"):
        inner = v[1:-1].strip()
        if not inner:
            return []
        out = []
        for part in inner.split(","):
            part = part.strip().strip('"').strip("'")
            out.append(part)
        return out
    if v.startswith('"') and v.endswith('"') and len(v) >= 2:
        return v[1:-1]
    if v.startswith("'") and v.endswith("'") and len(v) >= 2:
        return v[1:-1]
    return v


def load_registry(registry_path=None):
    """Parse the restricted-subset YAML registry. Returns {key: {field: val}}."""
    path = Path(registry_path) if registry_path else REGISTRY
    entries = {}
    in_paths = False
    current = None
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        if raw.strip().startswith("#") or not raw.strip():
            continue
        if not raw.startswith(" "):
            in_paths = raw.strip() == "paths:"
            current = None
            continue
        if not in_paths:
            continue
        m = _ENTRY_RE.match(raw)
        if m:
            current = m.group(1)
            entries[current] = {}
            continue
        m = _FIELD_RE.match(raw)
        if m and current is not None:
            entries[current][m.group(1)] = _parse_scalar(m.group(2))
    return entries


def _expand(p):
    if p is None:
        return None
    p = str(p)
    home = os.environ.get("USERPROFILE") or str(Path.home())
    p = p.replace("%USERPROFILE%", home)
    p = os.path.expandvars(p)
    q = Path(p)
    if not q.is_absolute():
        q = ROOT / p
    return q


def resolve(key, must_exist=False, registry=None):
    """Resolve a registry key to a Path. Emits alias log on stderr."""
    reg = registry if registry is not None else load_registry()
    entry = reg.get(key)
    if key == "repo.root":
        env = os.environ.get("AWX_ROOT")
        return Path(env) if env else ROOT
    if entry is None:
        raise KeyError(f"unknown path key: {key}")
    env_name = entry.get("env")
    if env_name and os.environ.get(env_name):
        return Path(os.environ[env_name])
    reg_path = _expand(entry.get("path"))
    if reg_path is not None and reg_path.exists():
        return reg_path
    for old in entry.get("old_paths") or []:
        cand = _expand(old)
        if cand is not None and cand.exists():
            print(f"[AWX][path-alias] key={key} old={old}", file=sys.stderr)
            return cand
    if key == "git.exe":
        on_path = shutil.which("git")
        if on_path:
            return Path(on_path)
    result = reg_path
    if must_exist and (result is None or not result.exists()):
        raise FileNotFoundError(f"{key}: {result}")
    return result


def _git_tracked():
    try:
        import subprocess
        git = resolve("git.exe")
        r = subprocess.run([str(git), "-C", str(ROOT), "ls-files"],
                           capture_output=True, text=True, timeout=60)
        return {l.strip().lower() for l in r.stdout.splitlines() if l.strip()}
    except Exception:
        return set()


ABS_LIT_RE = re.compile(
    r"C:[\\/]+AbandonWare|C:[\\/]+Users[\\/]+nninn|F:[\\/]+git", re.I)
CHECK_DIRS = ["scripts", "__patch_drop__", ".codex", ".grok"]
CHECK_EXT = {".py", ".ps1", ".psm1", ".sh"}
ALLOW_FILES = {"scripts/awx_paths.py"}  # resolver holds its own anchors


def check(scan_dirs=None, baseline_path=None, write_baseline=False):
    """Find hardcoded absolute literals in tool code. Warn-only; exit 0.

    With a baseline file, only new files or increased counts are flagged.
    """
    scan_dirs = scan_dirs or CHECK_DIRS
    hits = {}
    for d in scan_dirs:
        base = ROOT / d
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if not p.is_file() or p.suffix.lower() not in CHECK_EXT:
                continue
            rel = p.relative_to(ROOT).as_posix()
            if rel in ALLOW_FILES:
                continue
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            n = len(ABS_LIT_RE.findall(text))
            if n:
                hits[rel] = n
    if write_baseline:
        bpath = Path(baseline_path) if baseline_path else DEFAULT_BASELINE
        bpath.parent.mkdir(parents=True, exist_ok=True)
        bpath.write_text(json.dumps(hits, indent=2, sort_keys=True),
                         encoding="utf-8")
        print(f"baseline written: {bpath} ({len(hits)} files)")
        return 0
    baseline = {}
    bpath = Path(baseline_path) if baseline_path else DEFAULT_BASELINE
    if bpath.exists():
        baseline = json.loads(bpath.read_text(encoding="utf-8"))
    new_or_grown = {f: (c, baseline.get(f, 0)) for f, c in hits.items()
                    if c > baseline.get(f, 0)}
    shrunk = {f: (baseline[f], hits.get(f, 0)) for f in baseline
              if hits.get(f, 0) < baseline[f]}
    print(f"check: {len(hits)} files contain absolute literals "
          f"(baseline {len(baseline)})")
    for f, (now, before) in sorted(new_or_grown.items()):
        print(f"[AWX][check][new-hardcode] {f}: {before} -> {now}")
    for f, (before, now) in sorted(shrunk.items()):
        print(f"[AWX][check][reduced] {f}: {before} -> {now}")
    return 0


def moved(last=20, moves_file=None):
    path = Path(moves_file) if moves_file else MOVED_LOG
    if not path.exists():
        print("moved: no moves recorded (moved.jsonl absent)")
        return 0
    lines = [l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    for l in lines[-last:]:
        print(l)
    print(f"moved: {len(lines)} total record(s)")
    return 0


def aliases(expired=False, plan=False, today=None, moves_file=None):
    """List alias entries from moved.jsonl. --expired filters past `expires`;
    --plan prints what would be cleaned (never deletes)."""
    import datetime
    path = Path(moves_file) if moves_file else MOVED_LOG
    if not path.exists():
        print("aliases: none")
        return 0
    today = today or datetime.date.today().isoformat()
    shown = 0
    for l in path.read_text(encoding="utf-8").splitlines():
        if not l.strip():
            continue
        try:
            rec = json.loads(l)
        except json.JSONDecodeError:
            continue
        if expired and (not rec.get("expires") or rec["expires"] >= today):
            continue
        action = "WOULD-REMOVE" if plan else "alias"
        print(f"{action} {rec.get('alias','?'):8} {rec.get('old')} -> "
              f"{rec.get('new')} expires={rec.get('expires','-')}")
        shown += 1
    print(f"aliases: {shown} shown")
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        print(__doc__)
        return 2
    cmd = argv.pop(0)
    if cmd == "where":
        if not argv:
            print("where <key>", file=sys.stderr)
            return 2
        key = argv.pop(0)
        must = "--must-exist" in argv
        try:
            print(resolve(key, must_exist=must))
            return 0
        except (KeyError, FileNotFoundError) as e:
            print(str(e), file=sys.stderr)
            return 2
    if cmd == "list":
        reg = load_registry()
        if "--json" in argv:
            print(json.dumps(reg, indent=2, ensure_ascii=False))
        else:
            for k in sorted(reg):
                e = reg[k]
                print(f"{k}\t{e.get('status','?')}\t{e.get('path')}"
                      f"\tenv={e.get('env') or '-'}")
        return 0
    if cmd == "check":
        bp = argv[argv.index("--baseline") + 1] if "--baseline" in argv else None
        return check(baseline_path=bp, write_baseline="--write-baseline" in argv)
    if cmd == "moved":
        n = 20
        if "--last" in argv:
            n = int(argv[argv.index("--last") + 1])
        return moved(last=n)
    if cmd == "aliases":
        return aliases(expired="--expired" in argv, plan="--plan" in argv)
    print(f"unknown command: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
