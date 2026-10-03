"""codex_assist.py — delegation packet/collect/status for Codex assist subagents.

Stdlib only, no network. Ledger layout (created on demand):
  <ledger>/assist/packets/<role>-<marker8>.json   delegation packet (paste-ready)
  <ledger>/assist/results.jsonl                   one verdict row per collect
The subagent result body is never persisted — verdict, reasons and sha256 only.

Commands:
  packet  --role ROLE --objective TEXT --ledger DIR [--scenario S] [--budget-sends N]
  collect --packet FILE --result FILE        exit 0 ACCEPT / 3 REJECT / 2 usage
  status  --ledger DIR                       exit 0, JSON summary on stdout
"""
import argparse
import hashlib
import json
import os
import re
import secrets
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_PACKET = "awx.codex-assist-packet.v1"
SCHEMA_RESULT = "awx.codex-assist-result.v1"

ROLES = {
    "assist_browser_verifier": {"writes": True, "default_budget": 10},
    "assist_rag_trace_analyst": {"writes": False, "default_budget": 0},
    "assist_routing_mapper": {"writes": False, "default_budget": 0},
    "assist_patch_reviewer": {"writes": False, "default_budget": 0},
}

SECTIONS = ("finding", "evidence", "uncertainty", "recommended next check")
SECRET_PATTERNS = ("sk-", "AIza", "ghp_", "xox", "-----BEGIN")
PASSWORD_RE = re.compile(r"password\s*=", re.I)
MAX_RESULT_BYTES = 1024 * 1024
MARKER_RE = re.compile(r"task_received=([0-9a-fA-F]{8})")
FILES_RE = re.compile(r"(?m)^\s*files_written\s*=\s*(.+?)\s*$")
SENDS_RE = re.compile(r"(?m)^\s*browser_sends\s*=\s*(\d+)\s*$")


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def err(msg: str, code: int = 2) -> int:
    print(json.dumps({"status": "error", "reason": msg}))
    return code


def ledger_of(packet_path: Path) -> Path:
    # <ledger>/assist/packets/<file>.json -> <ledger>
    if packet_path.parent.name == "packets" and packet_path.parent.parent.name == "assist":
        return packet_path.parent.parent.parent
    return packet_path.parent


def rel_or_abs(path: Path, base: Path) -> str:
    try:
        return path.resolve().relative_to(base.resolve()).as_posix()
    except ValueError:
        return str(path.resolve())


def cmd_packet(args) -> int:
    if args.role not in ROLES:
        return err(f"unknown-role:{args.role} (allowed: {', '.join(ROLES)})")
    objective = (args.objective or "").strip()
    if not objective:
        return err("objective-empty")
    ledger = Path(args.ledger)
    packets_dir = ledger / "assist" / "packets"
    packets_dir.mkdir(parents=True, exist_ok=True)

    marker = secrets.token_hex(4)
    cwd = Path.cwd()
    spec = ROLES[args.role]
    budget = args.budget_sends if args.budget_sends is not None else spec["default_budget"]
    allowed = []
    if spec["writes"]:
        allowed = [rel_or_abs(ledger / "browser", cwd)]

    forbidden = [
        "edit any source/config file outside allowed_write_paths",
        "commit/push/deploy/git mutations, server restarts",
        "spawn nested subagents; final judgment stays with the parent",
        "print or store secrets, raw prompts, keys, tokens, auth headers",
        "public URL sends (127.0.0.1:18180 only), paid provider calls",
    ]
    packet = {
        "schemaVersion": SCHEMA_PACKET,
        "packetId": f"{args.role}-{marker}",
        "createdAtUtc": utcnow(),
        "issuedBy": "devin-assist-subagents",
        "role": args.role,
        "objective": objective,
        "scenario": args.scenario,
        "deliveryMarker": marker,
        "allowedWritePaths": allowed,
        "limits": {
            "browserSends": budget,
            "paidCalls": 0,
            "serverRestarts": 0,
            "publicUrlSends": 0,
            "nestedSpawn": False,
        },
        "forbidden": forbidden,
    }
    delegate = (
        f"Use the {args.role} custom agent for this delegation packet.\n"
        f"objective: {objective}\n"
        + (f"scenario: {args.scenario}\n" if args.scenario else "")
        + f"deliveryMarker: {marker}\n"
        + f"allowed_write_paths: {json.dumps(allowed)}\n"
        + f"limits: browser_sends<={budget}, paid_calls=0, server_restarts=0, "
        + "public_url_sends=0, nested_spawn=false\n"
        + f"ledger: {ledger.resolve()}\n"
        + "Return exactly sections: finding, evidence, uncertainty, recommended next check; "
        + "first line under finding must be task_received=<deliveryMarker>; "
        + "append a final line files_written=<comma list or none>; "
        + "if you sent browser messages append browser_sends=<n>."
    )
    packet["delegateText"] = delegate
    pfile = packets_dir / f"{args.role}-{marker}.json"
    pfile.write_text(json.dumps(packet, indent=2, ensure_ascii=False), encoding="utf-8")
    print(delegate)
    return 0


def find_sections(text: str) -> set:
    found = set()
    for line in text.splitlines():
        norm = re.sub(r"^[\s#\d.)\-]+", "", line).strip().lower()
        norm = re.sub(r"\s+", " ", norm)
        if norm in SECTIONS:
            found.add(norm)
    return found


def path_under(path: Path, roots: list) -> bool:
    try:
        rp = path.resolve()
    except OSError:
        return False
    for root in roots:
        try:
            root_r = Path(root).resolve()
            if rp == root_r or root_r in rp.parents:
                return True
        except OSError:
            continue
    return False


def cmd_collect(args) -> int:
    packet_path = Path(args.packet)
    result_path = Path(args.result)
    if not packet_path.is_file():
        return err("packet-missing")
    if not result_path.is_file():
        return err("result-missing")
    try:
        packet = json.loads(packet_path.read_text(encoding="utf-8"))
        text = result_path.read_text(encoding="utf-8", errors="replace")
    except (OSError, json.JSONDecodeError) as exc:
        return err(f"read-failed:{exc}")
    if len(text.encode("utf-8", "replace")) > MAX_RESULT_BYTES:
        return err("result-oversize")

    reasons = []
    marker = packet.get("deliveryMarker", "")
    got = MARKER_RE.search(text)
    if not got:
        reasons.append("marker-missing")
    elif got.group(1).lower() != str(marker).lower():
        reasons.append(f"marker-mismatch:{got.group(1)}")

    missing = [s for s in SECTIONS if s not in find_sections(text)]
    if missing:
        reasons.append("sections-missing:" + ",".join(missing))

    fm = FILES_RE.findall(text)
    if not fm:
        reasons.append("files-written-line-missing")
        files = ["<unparsed>"]
    else:
        raw = fm[-1].strip()
        files = [] if raw.lower() in ("none", "[]", "") else [
            f.strip().strip('"').strip("'") for f in raw.split(",") if f.strip()]
    allowed = packet.get("allowedWritePaths") or []
    cwd = Path.cwd()
    allowed_roots = [a[:-3] if a.endswith("/**") else a for a in allowed]
    allowed_resolved = [(cwd / a).resolve() if not os.path.isabs(a) else Path(a).resolve()
                        for a in allowed_roots]
    for f in files:
        if f == "<unparsed>":
            reasons.append("files-written-unparsed")
            continue
        fpath = Path(f)
        if not fpath.is_absolute():
            fpath = (cwd / fpath)
        if not path_under(fpath, allowed_resolved):
            reasons.append(f"files-outside-allowed:{f}")

    for pat in SECRET_PATTERNS:
        if pat in text:
            reasons.append(f"secret-pattern:{pat}")
            break
    if PASSWORD_RE.search(text):
        reasons.append("secret-pattern:password-assign")

    sends = SENDS_RE.findall(text)
    sends_n = int(sends[-1]) if sends else 0
    budget = (packet.get("limits") or {}).get("browserSends", 0)
    if sends_n > budget:
        reasons.append(f"budget-exceeded:{sends_n}>{budget}")

    verdict = "ACCEPT" if not reasons else "REJECT"
    ledger = ledger_of(packet_path)
    results_file = ledger / "assist" / "results.jsonl"
    results_file.parent.mkdir(parents=True, exist_ok=True)
    record = {
        "schemaVersion": SCHEMA_RESULT,
        "atUtc": utcnow(),
        "packetId": packet.get("packetId", packet_path.stem),
        "role": packet.get("role"),
        "verdict": verdict,
        "reasons": reasons,
        "resultSha256": hashlib.sha256(text.encode("utf-8", "replace")).hexdigest(),
        "resultBytes": len(text.encode("utf-8", "replace")),
        "browserSends": sends_n,
    }
    with open(results_file, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    out = {"verdict": verdict, "packetId": record["packetId"], "role": record["role"],
           "reasons": reasons, "resultsFile": str(results_file)}
    print(json.dumps(out, ensure_ascii=False))
    return 0 if verdict == "ACCEPT" else 3


def cmd_status(args) -> int:
    ledger = Path(args.ledger)
    packets_dir = ledger / "assist" / "packets"
    results_file = ledger / "assist" / "results.jsonl"
    declared = 0
    packets = []
    if packets_dir.is_dir():
        for p in sorted(packets_dir.glob("*.json")):
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            packets.append(data)
            declared += int((data.get("limits") or {}).get("browserSends", 0) or 0)
    per_role = {}
    observed = 0
    delegations = 0
    if results_file.is_file():
        for line in results_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            delegations += 1
            role = rec.get("role") or "unknown"
            slot = per_role.setdefault(role, {"ACCEPT": 0, "REJECT": 0})
            slot[rec.get("verdict", "REJECT")] = slot.get(rec.get("verdict", "REJECT"), 0) + 1
            observed += int(rec.get("browserSends") or 0)
    out = {
        "schemaVersion": "awx.codex-assist-status.v1",
        "ledger": str(ledger),
        "packets": len(packets),
        "delegations": delegations,
        "perRole": per_role,
        "browserSends": {"declared": declared, "observed": observed,
                         "remaining": max(declared - observed, 0)},
    }
    print(json.dumps(out, ensure_ascii=False))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Codex assist subagent delegation kit")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("packet")
    p.add_argument("--role", required=True)
    p.add_argument("--objective", required=True)
    p.add_argument("--ledger", required=True)
    p.add_argument("--scenario")
    p.add_argument("--budget-sends", type=int, default=None)
    c = sub.add_parser("collect")
    c.add_argument("--packet", required=True)
    c.add_argument("--result", required=True)
    s = sub.add_parser("status")
    s.add_argument("--ledger", required=True)
    args = ap.parse_args()
    if args.cmd == "packet":
        return cmd_packet(args)
    if args.cmd == "collect":
        return cmd_collect(args)
    return cmd_status(args)


if __name__ == "__main__":
    sys.exit(main())
