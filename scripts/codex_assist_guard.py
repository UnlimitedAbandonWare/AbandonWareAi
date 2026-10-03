"""codex_assist_guard.py — prove a delegated subagent touched nothing outside scope.

Read-only git usage: status --porcelain, rev-parse HEAD, diff --stat only.

  snapshot --ledger DIR [--root DIR]
      records HEAD + porcelain entries (path -> {xy, sha256}) into
      <ledger>/assist/guard/snapshot.json. Run immediately before delegation.
  verify --ledger DIR --packet FILE [--root DIR]
      re-scans; every path changed vs snapshot is classified:
        SELF    - inside the ledger dir (own artifacts, ignored)
        ALLOWED - inside packet.allowedWritePaths
        FOREIGN - covered by another session's lease targetPaths
                  (__patch_drop__/source-edit-locks/*/lease.json); never blamed
                  on the subagent
        VIOLATION - anything else
      PASS iff violations is empty. exit 0 PASS / 6 FAIL / 2 usage error.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from awx_paths import resolve as _awx_resolve

SCHEMA_SNAP = "awx.codex-assist-guard-snapshot.v1"
SCHEMA_VERIFY = "awx.codex-assist-guard-verify.v1"
LEASE_GLOB = "__patch_drop__/source-edit-locks/*/lease.json"


def find_git() -> str:
    env = os.environ.get("GIT_EXE")
    if env and Path(env).exists():
        return env
    found = shutil.which("git")
    if found:
        return found
    fallback = str(_awx_resolve("git.exe"))
    return fallback if Path(fallback).exists() else "git"


def run_git(root: Path, *gargs):
    return subprocess.run([find_git(), *gargs], cwd=str(root),
                          capture_output=True, text=True)


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def err(msg: str) -> int:
    print(json.dumps({"status": "error", "reason": msg}))
    return 2


def parse_porcelain(text: str) -> dict:
    entries = {}
    for line in text.splitlines():
        if len(line) < 4:
            continue
        xy = line[:2]
        path = line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ")[-1].strip()
        if path.startswith('"') and path.endswith('"'):
            path = path[1:-1]
        entries[path] = xy
    return entries


MAX_HASH_BYTES = 64 * 1024 * 1024


def file_sig(path: Path):
    try:
        st = path.stat()
        if not path.is_file():
            return None
        if st.st_size > MAX_HASH_BYTES:
            return f"stat:{st.st_size}:{st.st_mtime_ns}"
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def scan(root: Path):
    head = run_git(root, "rev-parse", "HEAD")
    status = run_git(root, "status", "--porcelain", "-uall")
    if status.returncode != 0:
        return None, f"git-status-failed:{status.stderr.strip()[:200]}"
    entries = {}
    for rel, xy in parse_porcelain(status.stdout).items():
        entries[rel] = {"xy": xy, "sha256": file_sig(root / rel)}
    return {"head": head.stdout.strip() if head.returncode == 0 else None,
            "entries": entries}, None


def norm_rel(path: str) -> str:
    return path.replace("\\", "/").lstrip("./").rstrip("/")


def under_prefix(rel: str, prefixes) -> bool:
    for pre in prefixes:
        pre = norm_rel(pre)
        if not pre:
            continue
        if pre.endswith("/**"):
            pre = pre[:-3]
        if rel == pre or rel.startswith(pre + "/"):
            return True
    return False


def load_lease_targets(root: Path):
    foreign = []
    for lease_file in sorted(root.glob(LEASE_GLOB.replace("/", os.sep))):
        try:
            lease = json.loads(lease_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        topic = lease.get("topic") or lease_file.parent.name
        for t in lease.get("targetPaths") or []:
            foreign.append({"prefix": norm_rel(t), "topic": topic,
                            "status": lease.get("status", "unknown"),
                            "expiresAtUtc": lease.get("expiresAtUtc")})
    return foreign


def cmd_snapshot(args) -> int:
    root = Path(args.root).resolve()
    ledger = Path(args.ledger)
    snap, error = scan(root)
    if error:
        return err(error)
    snap["schemaVersion"] = SCHEMA_SNAP
    snap["createdAtUtc"] = utcnow()
    snap["root"] = str(root)
    gdir = ledger / "assist" / "guard"
    gdir.mkdir(parents=True, exist_ok=True)
    out = gdir / "snapshot.json"
    out.write_text(json.dumps(snap, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"status": "ok", "snapshot": str(out),
                      "head": snap["head"], "entries": len(snap["entries"])}))
    return 0


def cmd_verify(args) -> int:
    root = Path(args.root).resolve()
    ledger = Path(args.ledger)
    snap_file = ledger / "assist" / "guard" / "snapshot.json"
    if not snap_file.is_file():
        return err("snapshot-missing")
    packet_file = Path(args.packet)
    if not packet_file.is_file():
        return err("packet-missing")
    try:
        snap = json.loads(snap_file.read_text(encoding="utf-8"))
        packet = json.loads(packet_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return err(f"read-failed:{exc}")

    current, error = scan(root)
    if error:
        return err(error)

    try:
        ledger_rel = norm_rel(str(ledger.resolve().relative_to(root)))
    except ValueError:
        ledger_rel = None

    allowed_prefixes = [norm_rel(a) for a in (packet.get("allowedWritePaths") or [])]
    foreign_leases = load_lease_targets(root)

    changed = []
    for rel, cur in current["entries"].items():
        old = snap["entries"].get(rel)
        if old is None or old["xy"] != cur["xy"] or old["sha256"] != cur["sha256"]:
            changed.append(rel)

    violations, foreign, allowed_changed = [], [], []
    self_ignored = 0
    for rel in changed:
        if under_prefix(rel, allowed_prefixes):
            allowed_changed.append(rel)
            continue
        if ledger_rel and (rel == ledger_rel or rel.startswith(ledger_rel + "/")):
            self_ignored += 1
            continue
        cover = [f for f in foreign_leases
                 if rel == f["prefix"] or rel.startswith(f["prefix"] + "/")]
        if cover:
            foreign.append({"path": rel,
                            "leases": [{"topic": c["topic"], "status": c["status"]}
                                       for c in cover]})
            continue
        violations.append(rel)

    verdict = "PASS" if not violations else "FAIL"
    result = {
        "schemaVersion": SCHEMA_VERIFY,
        "checkedAtUtc": utcnow(),
        "verdict": verdict,
        "violations": violations,
        "foreign": foreign,
        "allowedChanged": allowed_changed,
        "selfIgnored": self_ignored,
        "changedTotal": len(changed),
        "headBefore": snap.get("head"),
        "headNow": current["head"],
        "packetId": packet.get("packetId", packet_file.stem),
    }
    gdir = ledger / "assist" / "guard"
    gdir.mkdir(parents=True, exist_ok=True)
    stamp = utcnow().replace(":", "").replace("+", "Z")[:19]
    out_file = gdir / f"verify-{stamp}.json"
    out_file.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    result["verifyFile"] = str(out_file)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if verdict == "PASS" else 6


def main() -> int:
    ap = argparse.ArgumentParser(description="Delegation write-scope guard (git read-only)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("snapshot", "verify"):
        p = sub.add_parser(name)
        p.add_argument("--ledger", required=True)
        p.add_argument("--root", default=".")
        if name == "verify":
            p.add_argument("--packet", required=True)
    args = ap.parse_args()
    if args.cmd == "snapshot":
        return cmd_snapshot(args)
    return cmd_verify(args)


if __name__ == "__main__":
    sys.exit(main())
