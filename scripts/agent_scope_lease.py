"""Agent work-scope coordinator for parallel agents on this checkout.

One entry point answers "is anyone already editing these paths / features?"
and reserves ownership per work unit, so Codex, Devin, Grok and Notebook
agents do not collide on the same files or logic. It composes existing
machinery instead of replacing it:

  - __patch_drop__/source_edit_session.ps1: target-scoped source-edit lease
    (real enforcement; expired leases still block overlapping targets until
    recover proves a dead owner).
  - scripts/work_journal.py: per-task journal with declared plannedScope
    (visibility, not a lock).
  - data/agent-handoff/codex-autonomy/<taskId>/scope-claim.json: this tool's
    claim record (agent, ownerId, topic, fingerprint, targets, feature and
    region labels, released state).

Git is never required. Path overlap is exact or prefix either direction.
Feature/region labels are advisory only — enforcement stays at path level.
All output is JSON on stdout. Exit codes: 0 ok, 2 usage, 3 owner/identity,
6 evidence or session error, 7 scope conflict.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

SCHEMA = "awx.agent-scope-lease.v1"
CLAIM_SCHEMA = "awx.agent-scope-claim.v1"
BASE = "data/agent-handoff/codex-autonomy"
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_DIR.parent
SESSION_PS1 = DEFAULT_ROOT / "__patch_drop__" / "source_edit_session.ps1"
JOURNAL_PY = SCRIPT_DIR / "work_journal.py"
AUTOFLOW_PY = SCRIPT_DIR / "lease_conflict_autoflow.py"
MAX_ITEMS = 64
MAX_TEXT = 400

_BAD_CHARS = re.compile(r'[:<>"|?*\x00-\x1f]')
_RESERVED_NAME = re.compile(r'(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)')
_SAFE_NAME = re.compile(r'^[A-Za-z0-9_.-]{1,120}$')


class ScopeError(Exception):
    pass


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def canon(path_text: str) -> str:
    """Mirror ConvertTo-AwxCanonicalTargetPath: relative, lowercase, '/'-joined."""
    text = str(path_text or "").strip()
    if not text or os.path.isabs(text) or _BAD_CHARS.search(text):
        raise ScopeError("target-path-invalid")
    parts = [part for part in text.replace("\\", "/").split("/") if part != "."]
    if not parts:
        raise ScopeError("target-path-invalid")
    for part in parts:
        if not part or part in ("..", ".git") or part[-1] in ". " or _RESERVED_NAME.match(part):
            raise ScopeError("target-path-invalid")
    return "/".join(parts).lower()


def canon_or_none(path_text):
    try:
        return canon(path_text)
    except ScopeError:
        return None


def overlap(a: str, b: str) -> bool:
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")


def build_manifest(root: Path, paths, reserve_paths, require_file=True) -> dict:
    targets, reserves, seen = [], [], set()
    for raw in list(paths or []):
        c = canon(raw)
        if c in seen:
            raise ScopeError("target-path-invalid")
        seen.add(c)
        full = root / c
        if full.is_dir():
            reserves.append(c)  # directories are prefix reservations, not files
            continue
        sha = hashlib.sha256(full.read_bytes()).hexdigest() if full.is_file() else None
        targets.append({"path": c, "sha256": sha})
    for raw in list(reserve_paths or []):
        c = canon(raw)
        if c in seen:
            raise ScopeError("target-path-invalid")
        seen.add(c)
        reserves.append(c)
    if require_file and not targets:
        # The lease contract needs >=1 file target; directories can only be
        # prefix-reserved alongside real files.
        raise ScopeError("claim-needs-file-target")
    doc = {"targets": targets}
    if reserves:
        doc["reservePaths"] = reserves
    return doc


def parse_json_lines(text: str):
    for line in str(text or "").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return None


def run_ps(root: Path, action: str, topic=None, owner=None, manifest=None,
           fingerprint=None, ttl=None, task_id=None, want_json=False, timeout=75):
    session = os.environ.get("AWX_SCOPE_SESSION_PS1") or str(SESSION_PS1)
    if not Path(session).is_file():
        raise ScopeError("source-edit-session-missing")
    cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
           "-File", session, "-Action", action, "-Root", str(root)]
    if topic:
        cmd += ["-Topic", str(topic)]
    if owner:
        cmd += ["-OwnerId", str(owner)]
    if manifest:
        cmd += ["-TargetManifest", str(manifest)]
    if fingerprint:
        cmd += ["-LeaseFingerprint", str(fingerprint)]
    if ttl:
        cmd += ["-TtlMinutes", str(int(ttl))]
    if task_id:
        cmd += ["-TaskId", str(task_id)]
    if want_json:
        cmd += ["-Json"]
    try:
        return subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        raise ScopeError("session-timeout")


def run_journal(root: Path, *args, timeout=40):
    if not JOURNAL_PY.is_file():
        raise ScopeError("work-journal-missing")
    cmd = [sys.executable, "-B", str(JOURNAL_PY), *args, "--root", str(root)]
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def journal_note(root: Path, task_id: str, kind: str, text: str, refs=()):
    args = ["note", "--task", task_id, "--kind", kind, "--text", text[:MAX_TEXT]]
    for ref in refs:
        args += ["--ref", ref]
    proc = run_journal(root, *args)
    return proc.returncode == 0


def autoflow_plan(root: Path, goal_paths, task_id=None, execute=False, mark=True):
    """lease 겹침 감지 시 자동 분기 호출 ($demo1-lease-conflict-autoflow).
    execute=False/mark=False이면 순수 읽기 전용 프리뷰다."""
    if not AUTOFLOW_PY.is_file():
        return {"status": "unavailable", "reason": "autoflow-tool-missing"}
    cmd = [sys.executable, "-B", str(AUTOFLOW_PY), "--root", str(root),
           "plan", "--goal-files", *goal_paths]
    if task_id:
        cmd += ["--task", task_id]
    if execute:
        cmd += ["--execute"]
    if not mark:
        cmd += ["--no-mark"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=90)
    except (OSError, subprocess.TimeoutExpired):
        return {"status": "unavailable", "reason": "autoflow-call-failed"}
    row = parse_json_lines(proc.stdout)
    if not isinstance(row, dict):
        return {"status": "error", "reason": "autoflow-json-missing",
                "exitCode": proc.returncode}
    return row


def claim_file(root: Path, task_id: str, topic: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9_.-]", "-", str(topic)).strip("-") or "default"
    return root / BASE / task_id / f"scope-claim-{safe}.json"


def claim_ref(task_id: str, topic: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]", "-", str(topic)).strip("-") or "default"
    return f"{BASE}/{task_id}/scope-claim-{safe}.json"


def load_claim(root: Path, task_id: str, topic: str) -> dict:
    path = claim_file(root, task_id, topic)
    if not path.is_file():
        raise ScopeError("scope-claim-missing")
    try:
        claim = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        raise ScopeError("scope-claim-invalid")
    if claim.get("schemaVersion") != CLAIM_SCHEMA or claim.get("taskId") != task_id:
        raise ScopeError("scope-claim-invalid")
    return claim


def task_claims(root: Path, task_id: str) -> list:
    folder = root / BASE / task_id
    out = []
    if not folder.is_dir():
        return out
    for path in sorted(folder.glob("scope-claim-*.json")):
        try:
            doc = json.loads(path.read_bytes())
        except (OSError, ValueError):
            continue
        if doc.get("schemaVersion") == CLAIM_SCHEMA and doc.get("taskId") == task_id:
            out.append(doc)
    return out


def pick_claim(root: Path, task_id: str, topic=None) -> dict:
    claims = [c for c in task_claims(root, task_id) if not c.get("released")]
    if not claims:
        raise ScopeError("scope-claim-missing")
    if topic:
        for claim in claims:
            if claim.get("topic") == topic:
                return claim
        raise ScopeError("scope-claim-missing")
    if len(claims) > 1:
        raise ScopeError("claim-topic-required:" +
                         ",".join(str(c.get("topic")) for c in claims))
    return claims[0]


def save_claim(root: Path, claim: dict) -> None:
    path = claim_file(root, claim["taskId"], claim["topic"])
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(claim, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def iter_task_docs(root: Path, name: str):
    base = root / BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob(f"*/{name}"))[:512]:
        try:
            yield json.loads(path.read_bytes())
        except (OSError, ValueError):
            continue


def iter_journals(root: Path):
    for doc in iter_task_docs(root, "journal.json"):
        if isinstance(doc, dict) and doc.get("status") == "in_progress":
            yield doc


def iter_claims(root: Path):
    base = root / BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob("*/scope-claim-*.json"))[:512]:
        try:
            doc = json.loads(path.read_bytes())
        except (OSError, ValueError):
            continue
        if isinstance(doc, dict) and doc.get("schemaVersion") == CLAIM_SCHEMA:
            yield doc


def advisories_for(root: Path, paths, features, exclude_task=None):
    candidates = []
    for raw in paths or []:
        c = canon_or_none(raw)
        if c:
            candidates.append(c)
    feats = {str(f).strip().casefold() for f in (features or []) if str(f).strip()}
    out = []
    for claim in iter_claims(root):
        if claim.get("released") or claim.get("taskId") == exclude_task:
            continue
        owned = [t.get("path") for t in claim.get("targets", []) if isinstance(t, dict)]
        owned += list(claim.get("reservePaths") or [])
        hits = sorted({x for x in candidates for y in owned if x and y and overlap(x, y)})
        hit_feats = sorted(feats & {str(f).casefold() for f in claim.get("features", [])})
        regions = [r for r in claim.get("regions", [])
                   if isinstance(r, dict) and r.get("path") in candidates]
        if hits or hit_feats or regions:
            out.append({"kind": "claim-overlap", "taskId": claim.get("taskId"),
                        "agent": claim.get("agent"), "topic": claim.get("topic"),
                        "ownerId": claim.get("ownerId"), "paths": hits,
                        "features": hit_feats, "regions": regions,
                        "purpose": claim.get("purpose")})
    for journal in iter_journals(root):
        if journal.get("taskId") == exclude_task:
            continue
        scope = [canon_or_none(s) for s in journal.get("plannedScope", [])]
        hits = sorted({x for x in candidates for y in scope if x and y and overlap(x, y)})
        if hits:
            out.append({"kind": "journal-scope-overlap", "taskId": journal.get("taskId"),
                        "agent": journal.get("agent"), "paths": hits,
                        "purpose": str(journal.get("purpose"))[:200]})
    return out


def lease_summary(root: Path) -> dict:
    proc = run_ps(root, "status", want_json=True)
    summary = parse_json_lines(proc.stdout)
    if summary is None:
        raise ScopeError("lease-evidence-unavailable")
    return summary


def cmd_who(root: Path, args) -> int:
    summary = lease_summary(root)
    leases = summary.get("sourceLeases") or []
    claims = []
    for claim in iter_claims(root):
        row = next((l for l in leases if l.get("topic") == claim.get("topic")), None)
        claims.append({
            "taskId": claim.get("taskId"), "agent": claim.get("agent"),
            "ownerId": claim.get("ownerId"), "topic": claim.get("topic"),
            "targets": [t.get("path") for t in claim.get("targets", [])],
            "reservePaths": claim.get("reservePaths") or [],
            "features": claim.get("features") or [],
            "regions": claim.get("regions") or [],
            "released": bool(claim.get("released")),
            "claimedAtUtc": claim.get("claimedAtUtc"),
            "releasedAtUtc": claim.get("releasedAtUtc"),
            "releaseReason": claim.get("releaseReason"),
            "leaseStatus": (row or {}).get("status") or "absent",
            "leaseExpiresAtUtc": (row or {}).get("expiresAtUtc"),
        })
    journals = [{"taskId": j.get("taskId"), "agent": j.get("agent"),
                 "purpose": j.get("purpose"), "plannedScope": j.get("plannedScope"),
                 "updatedAtUtc": j.get("updatedAtUtc")} for j in iter_journals(root)]
    print(json.dumps({"schemaVersion": SCHEMA, "action": "who",
                      "leaseCounts": {"active": summary.get("sourceLeaseActiveCount"),
                                      "expired": summary.get("sourceLeaseExpiredCount"),
                                      "corrupt": summary.get("sourceLeaseCorruptCount"),
                                      "blocking": summary.get("sourceLeaseBlockingCount")},
                      "leases": leases, "claims": claims, "activeJournals": journals},
                     ensure_ascii=True))
    return 0


def cmd_check(root: Path, args) -> int:
    if not args.path and not args.reserve_path and not args.feature:
        raise ScopeError("check-needs-path-or-feature")
    result = {"schemaVersion": SCHEMA, "action": "check", "allowed": True,
              "leaseConflict": None, "advisories": []}
    exit_code = 0
    goal_paths = []
    if args.path or args.reserve_path:
        doc = build_manifest(root, args.path or [], args.reserve_path or [],
                             require_file=False)
        goal_paths = [t["path"] for t in doc["targets"]] + list(doc.get("reservePaths") or [])
        if doc["targets"]:
            fd, manifest_path = tempfile.mkstemp(suffix=".json",
                                                 prefix="awx-scope-check-")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    json.dump(doc, handle)
                proc = run_ps(root, "status", manifest=manifest_path, want_json=True)
            finally:
                try:
                    os.unlink(manifest_path)
                except OSError:
                    pass
            summary = parse_json_lines(proc.stdout)
            if summary is None:
                raise ScopeError("check-evidence-unavailable:session-json-missing")
            conflict = summary.get("targetConflict") or {}
            if proc.returncode == 7 or conflict.get("allowed") is False:
                result["allowed"] = False
                exit_code = 7
            elif proc.returncode != 0:
                raise ScopeError("check-evidence-unavailable:" +
                                 str(summary.get("reason") or proc.returncode))
            result["leaseConflict"] = {
                "allowed": conflict.get("allowed", True),
                "conflictingPaths": conflict.get("conflictingPaths") or [],
                "conflictingLeaseCount": conflict.get("conflictingLeaseCount"),
                "unrelatedLeaseCount": conflict.get("unrelatedLeaseCount"),
                "reason": conflict.get("reason") or "",
            }
        else:
            # Reserve-only scope: the contract needs a file target in the
            # manifest, so compute the same exact/prefix overlap locally.
            reserves = doc.get("reservePaths") or []
            summary = lease_summary(root)
            leases = summary.get("sourceLeases") or []
            hits = sorted({r for r in reserves for row in leases
                           for t in (row.get("targetPaths") or [])
                           if overlap(r, t)})
            blocked = sum(1 for row in leases
                          if any(overlap(r, t) for r in reserves
                                 for t in (row.get("targetPaths") or []))
                          or row.get("status") == "corrupt")
            if blocked:
                result["allowed"] = False
                exit_code = 7
            result["leaseConflict"] = {
                "allowed": not blocked, "conflictingPaths": hits,
                "conflictingLeaseCount": blocked,
                "unrelatedLeaseCount": len(leases) - blocked,
                "reason": "source-target-overlap" if hits else "",
                "mode": "local-reserve-overlap",
            }
    result["advisories"] = advisories_for(root, args.path or [], args.feature or [],
                                         exclude_task=args.task)
    if result["advisories"] and args.strict:
        result["allowed"] = False
        exit_code = 7
    if exit_code == 7 and goal_paths:
        # 읽기 전용 프리뷰: 마커/요청 문서는 남기지 않는다
        result["leaseConflictAutoflow"] = autoflow_plan(
            root, goal_paths, task_id=args.task, execute=False, mark=False)
    print(json.dumps(result, ensure_ascii=True))
    return exit_code


def parse_regions(values):
    regions = []
    for raw in values or []:
        text = str(raw)
        path_text, _, note = text.partition(":")
        regions.append({"path": canon(path_text), "note": note.strip()[:200]})
    return regions


def cmd_claim(root: Path, args) -> int:
    agent = str(args.agent or "").strip()
    if not agent or not _SAFE_NAME.match(agent):
        raise ScopeError("agent-required")
    if not args.path and not args.reserve_path:
        raise ScopeError("claim-needs-targets")
    if not 1 <= int(args.ttl) <= 540:
        raise ScopeError("ttl-out-of-range")
    doc = build_manifest(root, args.path or [], args.reserve_path or [])
    if len(doc["targets"]) + len(doc.get("reservePaths", [])) > MAX_ITEMS:
        raise ScopeError("claim-too-many-targets")
    regions = parse_regions(args.region)
    features = [str(f).strip()[:80] for f in (args.feature or []) if str(f).strip()]

    task_id = args.task
    if task_id:
        if not (root / BASE / task_id / "journal.json").is_file():
            raise ScopeError("task-journal-missing")
    else:
        slug = args.slug or re.sub(r"[^a-z0-9-]+", "-", (args.topic or agent).lower()).strip("-") or "scope"
        scope_args = []
        for t in doc["targets"]:
            scope_args += ["--scope", t["path"]]
        for r in doc.get("reservePaths", []):
            scope_args += ["--scope", r]
        purpose = args.purpose or f"scope claim by {agent} on {len(doc['targets'])} target(s)"
        proc = run_journal(root, "open", "--task", slug, "--agent", agent,
                           "--purpose", purpose[:400], *scope_args)
        opened = parse_json_lines(proc.stdout) or {}
        task_id = opened.get("taskId")
        if proc.returncode != 0 or not task_id:
            raise ScopeError("journal-open-failed:" + str(opened.get("reason") or proc.returncode))

    topic = re.sub(r"[^A-Za-z0-9_.-]", "-", str(args.topic or task_id)).strip("-") or "default"
    manifest_rel = f"{BASE}/{task_id}/scope-targets-{topic}.json"
    manifest_path = root / manifest_rel
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(doc, ensure_ascii=True), encoding="utf-8")

    owner = str(args.owner or f"{agent}-{task_id}")
    proc = run_ps(root, "begin", topic=topic, owner=owner, manifest=manifest_path,
                  ttl=args.ttl, task_id=task_id, want_json=True)
    receipt = parse_json_lines(proc.stdout) or {}
    if proc.returncode != 0 or receipt.get("acquired") is not True:
        journal_note(root, task_id, "hold",
                     f"scope claim blocked: topic={topic} exit={proc.returncode}")
        flow = None
        if proc.returncode == 7:
            # 충돌 자동 처리: 비겹침 파일은 계속, 소유자에게 1회 해제 요청
            flow = autoflow_plan(
                root, [t["path"] for t in doc["targets"]] +
                      list(doc.get("reservePaths") or []),
                task_id=task_id, execute=True)
        print(json.dumps({"schemaVersion": SCHEMA, "action": "claim", "acquired": False,
                          "taskId": task_id, "topic": topic, "exitCode": proc.returncode,
                          "leaseConflictAutoflow": flow,
                          "detail": (proc.stdout + proc.stderr).strip()[-MAX_TEXT:]},
                         ensure_ascii=True))
        return proc.returncode if proc.returncode else 6

    claim = {
        "schemaVersion": CLAIM_SCHEMA, "taskId": task_id, "agent": agent,
        "ownerId": owner, "topic": topic,
        "leaseName": receipt.get("leaseName"),
        "fingerprint": receipt.get("fingerprint"),
        "manifestHash": receipt.get("manifestHash"),
        "manifestPath": manifest_rel,
        "targets": doc["targets"], "reservePaths": doc.get("reservePaths", []),
        "features": features, "regions": regions,
        "purpose": args.purpose or "", "ttlMinutes": args.ttl,
        "claimedAtUtc": utcnow(), "released": False,
        "releasedAtUtc": None, "releaseReason": None,
    }
    save_claim(root, claim)
    journal_note(root, task_id, "plan",
                 f"claimed scope lease topic={topic} targets={len(doc['targets'])} "
                 f"features={','.join(features) or '-'}", [claim_ref(task_id, topic)])
    print(json.dumps({"schemaVersion": SCHEMA, "action": "claim", "acquired": True,
                      "taskId": task_id, "topic": topic,
                      "leaseName": receipt.get("leaseName"),
                      "fingerprint": receipt.get("fingerprint"),
                      "manifestHash": receipt.get("manifestHash"),
                      "targets": doc["targets"],
                      "reservePaths": doc.get("reservePaths", []),
                      "features": features, "regions": regions,
                      "ttlMinutes": args.ttl}, ensure_ascii=True))
    return 0


def cmd_verify(root: Path, args) -> int:
    claim = pick_claim(root, args.task, getattr(args, "topic", None))
    if claim.get("released"):
        raise ScopeError("scope-already-released")
    proc = run_ps(root, "verify", topic=claim["topic"], owner=claim["ownerId"],
                  manifest=root / claim["manifestPath"],
                  fingerprint=claim["fingerprint"])
    print(json.dumps({"schemaVersion": SCHEMA, "action": "verify", "taskId": args.task,
                      "topic": claim["topic"], "verified": proc.returncode == 0,
                      "exitCode": proc.returncode,
                      "detail": (proc.stdout + proc.stderr).strip()[-MAX_TEXT:]},
                     ensure_ascii=True))
    return proc.returncode


def cmd_heartbeat(root: Path, args) -> int:
    claim = pick_claim(root, args.task, getattr(args, "topic", None))
    if claim.get("released"):
        raise ScopeError("scope-already-released")
    proc = run_ps(root, "heartbeat", topic=claim["topic"], owner=claim["ownerId"],
                  fingerprint=claim["fingerprint"], ttl=args.ttl or claim.get("ttlMinutes"))
    row = parse_json_lines(proc.stdout)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "heartbeat", "taskId": args.task,
                      "topic": claim["topic"], "renewed": proc.returncode == 0,
                      "exitCode": proc.returncode, "heartbeat": row}, ensure_ascii=True))
    return proc.returncode


def cmd_release(root: Path, args, reason: str) -> int:
    wanted = getattr(args, "topic", None)
    claims = [c for c in task_claims(root, args.task)
              if not c.get("released") and (not wanted or c.get("topic") == wanted)]
    if not claims:
        print(json.dumps({"schemaVersion": SCHEMA, "action": reason, "taskId": args.task,
                          "released": True, "alreadyReleased": True,
                          "topics": []}, ensure_ascii=True))
        return 0
    results, released_topics = [], []
    for claim in claims:
        proc = run_ps(root, "end", topic=claim["topic"], owner=claim["ownerId"],
                      fingerprint=claim["fingerprint"])
        ok = proc.returncode == 0
        results.append({"topic": claim["topic"], "released": ok,
                        "exitCode": proc.returncode,
                        "detail": (proc.stdout + proc.stderr).strip()[-200:]})
        if ok:
            claim["released"] = True
            claim["releasedAtUtc"] = utcnow()
            claim["releaseReason"] = reason
            save_claim(root, claim)
            released_topics.append(claim["topic"])
    if released_topics:
        kind = "hold" if reason == "abort" else "change"
        note = args.note or f"scope lease(s) released ({reason}): {','.join(released_topics)}"
        journal_note(root, args.task, kind, note,
                     [claim_ref(args.task, t) for t in released_topics])
        if args.close_result:
            run_journal(root, "close", "--task", args.task, "--result", args.close_result,
                        "--summary", (args.summary or f"scope {reason}")[:400])
    failed = [r for r in results if not r["released"]]
    print(json.dumps({"schemaVersion": SCHEMA, "action": reason, "taskId": args.task,
                      "released": not failed, "topics": released_topics,
                      "results": results}, ensure_ascii=True))
    return 0 if not failed else 6


def cmd_recover(root: Path, args) -> int:
    proc = run_ps(root, "recover")
    row = parse_json_lines(proc.stdout) or {}
    print(json.dumps({"schemaVersion": SCHEMA, "action": "recover",
                      "exitCode": proc.returncode, "result": row}, ensure_ascii=True))
    return proc.returncode


def cmd_show(root: Path, args) -> int:
    summary = lease_summary(root)
    leases = summary.get("sourceLeases") or []
    claims = []
    for claim in task_claims(root, args.task):
        row = next((l for l in leases if l.get("topic") == claim.get("topic")), None)
        claims.append({"claim": claim, "leaseStatus": (row or {}).get("status") or "absent",
                       "leaseExpiresAtUtc": (row or {}).get("expiresAtUtc")})
    journal = next((j for j in iter_task_docs(root, "journal.json")
                    if isinstance(j, dict) and j.get("taskId") == args.task), None)
    print(json.dumps({"schemaVersion": SCHEMA, "action": "show", "taskId": args.task,
                      "claims": claims,
                      "journal": None if journal is None else {
                          "status": journal.get("status"), "result": journal.get("result"),
                          "agent": journal.get("agent"),
                          "eventCount": len(journal.get("events", []))}},
                     ensure_ascii=True))
    return 0 if claims or journal else 6


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(DEFAULT_ROOT),
                        help="repo root (default: this checkout)")
    sub = parser.add_subparsers(dest="action", required=True)

    p = sub.add_parser("who", help="merged view: leases + claims + active journals")
    p.set_defaults(func=cmd_who)

    p = sub.add_parser("check", help="may I touch these paths/features? exit 7 on conflict")
    p.add_argument("--path", action="append", default=[])
    p.add_argument("--reserve-path", action="append", default=[])
    p.add_argument("--feature", action="append", default=[])
    p.add_argument("--task", help="exclude this task's own claim from advisories")
    p.add_argument("--strict", action="store_true",
                   help="treat journal/feature advisories as conflicts")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser("claim", help="open/attach journal + begin source lease + record claim")
    p.add_argument("--agent", required=True, help="agent name, e.g. devin, codex, grok")
    p.add_argument("--task", help="existing journal taskId to attach (else opens one)")
    p.add_argument("--slug", help="journal slug when opening a new task")
    p.add_argument("--topic", help="lease topic (default: taskId)")
    p.add_argument("--owner", help="lease ownerId (default: <agent>-<taskId>)")
    p.add_argument("--path", action="append", default=[])
    p.add_argument("--reserve-path", action="append", default=[],
                   help="directory prefix reservation (blocks children, not siblings)")
    p.add_argument("--feature", action="append", default=[],
                   help="advisory feature/logic label other agents can match")
    p.add_argument("--region", action="append", default=[],
                   help="'path:note' sub-file region hint (advisory)")
    p.add_argument("--purpose")
    p.add_argument("--ttl", type=int, default=180, help="lease TTL minutes (1-540)")
    p.set_defaults(func=cmd_claim)

    p = sub.add_parser("verify", help="re-validate claimed targets before editing")
    p.add_argument("--task", required=True)
    p.add_argument("--topic", help="which claim when the task holds several")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("heartbeat", help="renew the claimed lease TTL")
    p.add_argument("--task", required=True)
    p.add_argument("--topic", help="which claim when the task holds several")
    p.add_argument("--ttl", type=int)
    p.set_defaults(func=cmd_heartbeat)

    p = sub.add_parser("done", help="release lease(s) on completion (+journal note)")
    p.add_argument("--task", required=True)
    p.add_argument("--topic", help="release only this claim (default: all unreleased)")
    p.add_argument("--note")
    p.add_argument("--close-result", choices=("verified", "partial", "blocked", "superseded"))
    p.add_argument("--summary")
    p.set_defaults(func=lambda r, a: cmd_release(r, a, "done"))

    p = sub.add_parser("abort", help="release lease(s) on abandon (+journal hold note)")
    p.add_argument("--task", required=True)
    p.add_argument("--topic", help="release only this claim (default: all unreleased)")
    p.add_argument("--note")
    p.add_argument("--close-result", choices=("abandoned", "blocked", "partial"))
    p.add_argument("--summary")
    p.set_defaults(func=lambda r, a: cmd_release(r, a, "abort"))

    p = sub.add_parser("recover", help="reclaim leases with proven-dead owners only")
    p.set_defaults(func=cmd_recover)

    p = sub.add_parser("show", help="claim + lease + journal state for one taskId")
    p.add_argument("--task", required=True)
    p.set_defaults(func=cmd_show)
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        return args.func(root, args)
    except ScopeError as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": str(error)}, ensure_ascii=True))
        reason = str(error)
        if "owner" in reason or "released" in reason:
            return 3
        return 6 if "conflict" not in reason else 7
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"scope-io-or-evidence-error:{error}"},
                         ensure_ascii=True))
        return 6


if __name__ == "__main__":
    sys.exit(main())
