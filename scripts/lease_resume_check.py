#!/usr/bin/env python3
"""lease_resume_check.py — lease 대기 후 재개 판정 (읽기 전용, stdlib만).

lease-wait(--wait-dir)가 남긴 before.json을 기준으로 대상 파일의 drift를
판정하고 다음 행동을 종료 코드로 돌려준다. 사람에게 묻지 않는다.

판정:
  UNCHANGED         — sha256와 파일 identity 동일. 현재 계획/검사 gate 확인.
  CHANGED_DISJOINT  — 바뀌었지만 --hunks로 선언한 내 줄 범위 밖만 변경.
                      관련 입력을 다시 읽고 새 계획과 검사에 바인딩.
  CHANGED_OVERLAP   — 내 범위와 겹치는 변경. 파일을 다시 읽고 재계획한 뒤
                      자동 재개. 상대가 같은 일을 이미 끝냈으면
                      SKIP_ALREADY_DONE으로 기록하고 다음 일로.
  GONE              — 파일이 없음. 새 경로를 git/ledger로 찾아 재계획.
                      못 찾으면 그 파일만 PENDING으로 보류하고 나머지 계속.
  RENAMED           — git status가 R(rename)으로 새 경로를 보고함.
                      새 경로를 기준으로 재계획.

종료 코드: 0 = 전부 UNCHANGED, 10 = 변경 포함(재계획),
20 = GONE/RENAMED/RECREATED/NEWLY_CREATED(경로 재확인), 30 = UNKNOWN.
--gate는 fresh acquisition + 현재 계획의 bound RED(APPLY)/GREEN(SKIP)를
검사한다. 로컬 artifact 인증이나 비협력 writer의 원자적 차단을 보장하지 않는다.

Usage:
  python -B scripts/lease_resume_check.py --before <before.json|wait dir>
      [--hunks <path:start-end> ...] [--root .] [--show-diff]
"""
import argparse
import base64
import difflib
import hashlib
import json
import re
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.lease-resume-check.v1"
DIFF_MAX_LINES = 120


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def input_state(root, rel):
    """Read one declared non-secret input; read failure is never absence."""
    root = Path(root).resolve()
    rel = str(rel).replace("\\", "/")
    parts = rel.split("/")
    row = {"path": rel, "state": "UNKNOWN", "exists": None, "type": "unknown"}
    if (not rel or ":" in rel or any(p in ("", ".", "..") for p in parts)
            or any(p.casefold() in (".secrets", ".git", ".codex", ".aws")
                   or p.casefold().startswith(".env") for p in parts)
            or Path(rel).suffix.casefold() in (".pem", ".key", ".p12", ".pfx", ".jks")):
        return {**row, "reason": "unsafe-input-path"}, None
    path = root / rel
    try:
        for ancestor in (path, *path.parents):
            if ancestor == root:
                break
            try:
                meta = ancestor.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(meta.st_mode) or getattr(meta, "st_file_attributes", 0) & 0x400:
                return {**row, "reason": "reparse-traversal"}, None
        try:
            before = path.stat()
        except FileNotFoundError:
            return {**row, "state": "ABSENT", "exists": False, "type": "absent",
                    "identity": None, "sha256": None}, None
        row.update(exists=True, type="file" if stat.S_ISREG(before.st_mode) else "other",
                   identity={"device": before.st_dev, "inode": before.st_ino})
        if not stat.S_ISREG(before.st_mode):
            return {**row, "reason": "nonregular-input"}, None
        data = path.read_bytes()
        after = path.stat()
        if ((before.st_dev, before.st_ino, before.st_mtime_ns, before.st_ctime_ns, before.st_size)
                != (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns, after.st_size)):
            return {**row, "reason": "input-changed-during-read"}, None
        row.update(state="PRESENT", sha256=_sha256(data), size=len(data),
                   mtimeUtc=datetime.fromtimestamp(after.st_mtime, timezone.utc).isoformat())
        return row, data
    except OSError:
        return {**row, "reason": "input-read-failed"}, None


def _norm(p):
    return str(p).replace("\\", "/").lstrip("/").casefold()


def _git_rename_candidates(root, rel):
    """git status --porcelain의 R 항목에서 old->new 경로를 찾는다.
    git이 없거나 실패하면 빈 목록 — 그때는 ledger/수동 재확인 힌트만 남긴다."""
    try:
        proc = subprocess.run(
            ["git", "status", "--porcelain", "-z"],
            cwd=str(root), capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return []
    if proc.returncode != 0:
        return []
    out = []
    # -z 포맷: "R  new\0old\0" (R 라인은 new 다음 old가 NUL로 이어진다)
    parts = proc.stdout.split("\0")
    i = 0
    rel_cf = rel.casefold()
    while i < len(parts):
        entry = parts[i]
        if entry.startswith("R"):
            new_path = entry[3:].strip() if len(entry) > 3 else ""
            old_path = parts[i + 1] if i + 1 < len(parts) else ""
            if old_path.replace("\\", "/").casefold() == rel_cf:
                out.append(new_path.replace("\\", "/"))
            i += 2
            continue
        i += 1
    return out


def _changed_before_ranges(before_text, after_text):
    """before 파일 기준(1-based, inclusive)으로 바뀐 줄 범위 목록."""
    a = before_text.splitlines()
    b = after_text.splitlines()
    ranges = []
    for tag, i1, i2, _j1, _j2 in difflib.SequenceMatcher(
            None, a, b, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        if tag == "insert":
            # 삽입점: before의 i1번 줄(0-based) 뒤 — 경계 두 줄을 건드린 것으로 본다
            lo = max(1, i1)
            hi = i1 + 1
        else:  # replace / delete
            lo = i1 + 1
            hi = i2
        ranges.append([lo, hi])
    return ranges


def _overlaps(a, b):
    return a[0] <= b[1] and b[0] <= a[1]


def _parse_hunks(items):
    """--hunks path:start-end -> {norm(path): [[s,e], ...]}"""
    hunks = {}
    for raw in items or []:
        if ":" not in raw:
            continue
        path, _, span = raw.rpartition(":")
        if "-" not in span:
            continue
        s, _, e = span.partition("-")
        try:
            rng = [int(s), int(e)]
        except ValueError:
            continue
        hunks.setdefault(_norm(path), []).append(rng)
    return hunks


def check(before_path, hunks=None, root=".", show_diff=False):
    before_path = Path(before_path)
    if before_path.is_dir():
        before_path = before_path / "before.json"
    doc = json.loads(before_path.read_text(encoding="utf-8", errors="replace"))
    base = before_path.parent
    root = Path(doc.get("root") or root).resolve() \
        if doc.get("root") else Path(root).resolve()
    hmap = _parse_hunks(hunks)
    results, worst = [], 0
    for row in doc.get("targets", []) + doc.get("inputs", []):
        rel = row.get("path", "")
        fp = root / rel
        entry = {"path": rel, "beforeSha256": row.get("sha256"),
                 "beforeExists": bool(row.get("exists"))}
        current, cur = input_state(root, rel)
        entry["current"] = current
        if row.get("state") == "UNKNOWN" or current["state"] == "UNKNOWN":
            entry.update(verdict="UNKNOWN", nextAction="hold-file-evidence-needed")
            results.append(entry)
            worst = max(worst, 30)
            continue
        if current["state"] == "ABSENT" and not row.get("exists"):
            entry.update(verdict="UNCHANGED", nextAction="continue")
            results.append(entry)
            continue
        if current["state"] == "ABSENT":
            entry["verdict"] = "GONE"
            cands = _git_rename_candidates(root, rel)
            if cands:
                entry["verdict"] = "RENAMED"
                entry["renameCandidates"] = cands
            entry["nextAction"] = ("replan-on-new-path" if cands
                                   else "locate-then-replan-or-hold-file")
            results.append(entry)
            worst = max(worst, 20)
            continue
        if not row.get("exists"):
            entry.update(verdict="NEWLY_CREATED", nextAction="reevaluate-path-with-new-lease")
            results.append(entry)
            worst = max(worst, 20)
            continue
        if row.get("identity") and row["identity"] != current["identity"]:
            entry.update(verdict="RECREATED", nextAction="reevaluate-path-with-new-lease")
            results.append(entry)
            worst = max(worst, 20)
            continue
        after_sha = _sha256(cur)
        entry["afterSha256"] = after_sha
        if row.get("sha256") and after_sha == row["sha256"]:
            entry["verdict"] = "UNCHANGED"
            entry["nextAction"] = "continue"
            results.append(entry)
            continue
        snap_rel = row.get("snapshot")
        snap_bytes = None
        if snap_rel:
            _, snap_bytes = input_state(base, snap_rel)
            if snap_bytes is not None and _sha256(snap_bytes) != row.get("sha256"):
                entry.update(verdict="UNKNOWN", nextAction="hold-file-evidence-needed",
                             note="snapshot-hash-mismatch")
                results.append(entry)
                worst = max(worst, 30)
                continue
        if snap_bytes is None:
            entry["verdict"] = "CHANGED_OVERLAP"
            entry["changedBeforeRanges"] = None
            entry["note"] = "snapshot-missing-cannot-prove-disjoint"
            entry["nextAction"] = "reread-replan-auto-resume"
            results.append(entry)
            worst = max(worst, 10)
            continue
        try:
            before_text = snap_bytes.decode("utf-8")
            after_text = cur.decode("utf-8")
        except UnicodeDecodeError:
            entry.update(verdict="CHANGED_OVERLAP", nextAction="reread-replan-auto-resume",
                         note="binary-change-no-line-proof")
            results.append(entry)
            worst = max(worst, 10)
            continue
        ranges = _changed_before_ranges(before_text, after_text)
        entry["changedBeforeRanges"] = ranges
        mine = hmap.get(_norm(rel))
        if not mine:
            # 내 범위 미선언 — 겹침을 증명할 수 없으므로 보수적으로 OVERLAP
            entry["verdict"] = "CHANGED_OVERLAP"
            entry["note"] = "no-hunks-declared"
        else:
            hit = [h for h in mine
                   if any(_overlaps(h, r) for r in ranges)]
            if hit:
                entry["verdict"] = "CHANGED_OVERLAP"
                entry["overlapHunks"] = hit
            else:
                entry["verdict"] = "CHANGED_DISJOINT"
        entry["nextAction"] = "reread-replan-auto-resume"
        if show_diff:
            diff = list(difflib.unified_diff(
                before_text.splitlines(), after_text.splitlines(),
                fromfile="before/" + rel, tofile="after/" + rel,
                lineterm=""))
            entry["diff"] = diff[:DIFF_MAX_LINES]
            entry["diffTruncated"] = len(diff) > DIFF_MAX_LINES
        results.append(entry)
        worst = max(worst, 10)
    counts = {}
    for e in results:
        counts[e["verdict"]] = counts.get(e["verdict"], 0) + 1
    return {"schemaVersion": SCHEMA,
            "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
            "waitId": doc.get("waitId"), "task": doc.get("task"),
            "before": str(before_path),
            "results": results, "counts": counts,
            "exitCode": worst,
            "nextAction": {0: "continue", 10: "reread-replan-auto-resume",
                           20: "relocate-or-hold-file", 30: "hold-file-evidence-needed"}[worst]}


def check_gate(before_path, before_sha256, root, task, goal_revision,
               plan_revision, receipt, patch_sha256, decision=None,
               expected_input_digest=None):
    """Consume a fresh acquisition and current bound verification, before mutation.

    This is a cooperative gate, not authentication of locally authored artifacts.
    APPLY consumes fresh RED; already-done consumes fresh GREEN and writes zero.
    """
    out = {"status": "BLOCKED", "resumeAllowed": False, "reason": "baseline-invalid"}
    try:
        root = Path(root).resolve()
        before_path = Path(before_path).resolve()
        before_rel = before_path.relative_to(root).as_posix()
        before_state, raw = input_state(root, before_rel)
        if raw is None or len(raw) > 512 * 1024 or _sha256(raw) != before_sha256:
            return {**out, "reason": "baseline-hash-mismatch"}
        doc = json.loads(raw)
        context = doc["context"]
        metadata = root.stat()
        if (doc["schemaVersion"] != "awx.lease-wait-before.v1"
                or doc["root"] != str(root) or doc["task"] != task
                or doc["rootIdentity"] != {"device": metadata.st_dev, "inode": metadata.st_ino}
                or not goal_revision or str(context["goalRevision"]) != str(goal_revision)
                or not context.get("planRevision") or not plan_revision
                or context.get("inputPaths", []) != [r["path"] for r in doc["inputs"]]):
            return {**out, "reason": "baseline-context-mismatch"}
        rows = doc["targets"] + doc["inputs"]
        names = [r["path"] for r in rows]
        if (not doc["targets"] or len(set(p.casefold() for p in names)) != len(names)
                or any(r.get("state") not in ("PRESENT", "ABSENT") for r in rows)):
            return {**out, "reason": "baseline-input-unproven"}
        for row in rows:
            if row["state"] == "PRESENT":
                identity = row.get("identity")
                if (row.get("exists") is not True or row.get("type") != "file"
                        or not re.fullmatch(r"[a-f0-9]{64}", row.get("sha256", ""))
                        or not isinstance(identity, dict) or set(identity) != {"device", "inode"}
                        or any(type(v) is not int or v < 0 for v in identity.values())):
                    return {**out, "reason": "baseline-input-unproven"}
            elif (row.get("exists") is not False or row.get("type") != "absent"
                  or row.get("sha256") is not None or row.get("identity") is not None):
                return {**out, "reason": "baseline-input-unproven"}
        # Receipt is supplied only by the wrapper's own successful begin call.
        targets = sorted(r["path"].casefold() for r in doc["targets"])
        if (receipt.get("schema") != "awx.source-edit-acquired.v1"
                or receipt.get("acquired") is not True or receipt.get("taskId") != task
                or Path(receipt.get("root", "")).resolve() != root
                or not re.fullmatch(r"[a-f0-9]{32}", receipt.get("leaseId", ""))
                or any(not re.fullmatch(r"[a-f0-9]{64}", receipt.get(k, "")) for k in ("fingerprint", "manifestHash"))
                or sorted(p.casefold() for p in receipt["writePaths"]) != targets):
            return {**out, "reason": "fresh-acquisition-required"}
        report = check(before_path, root=root)
        states = [r["current"] for r in report["results"]]
        digest_rows = [{k: row.get(k) for k in ("path", "state", "type", "identity", "sha256")}
                       for row in states]
        digest = _sha256(json.dumps(digest_rows, sort_keys=True, separators=(",", ":")).encode())
        out.update(inputDigest=digest, changedPaths=[r["path"] for r in report["results"]
                                                     if r["verdict"] != "UNCHANGED"])
        if expected_input_digest and digest != expected_input_digest:
            return {**out, "reason": "input-changed-before-mutation"}
        if report["exitCode"] >= 20:
            return {**out, "reason": "path-reevaluation-required"}
        if not isinstance(decision, dict):
            return {**out, "status": "REPLAN_REQUIRED", "reason": "current-plan-evidence-required"}
        if (str(decision.get("goalRevision")) != str(goal_revision)
                or str(decision.get("planRevision")) != str(plan_revision)
                or decision.get("inputDigest") != digest
                or decision.get("patchSha256") != patch_sha256
                or not patch_sha256 or len(patch_sha256) != 64):
            return {**out, "reason": "decision-input-mismatch"}
        if report["exitCode"] == 10 and str(plan_revision) == str(context["planRevision"]):
            return {**out, "status": "REPLAN_REQUIRED", "reason": "changed-input-requires-new-plan"}
        if not set(names).issubset(set(decision.get("rereadPaths", []))):
            return {**out, "status": "REPLAN_REQUIRED", "reason": "related-input-reread-required"}
        phase = {"APPLY": "RED", "SKIP_ALREADY_DONE": "GREEN"}.get(decision.get("action"))
        if phase is None:
            return {**out, "reason": "decision-action-invalid"}
        binding = decision["verificationBinding"]
        required_binding = {"taskId", "revision", "contractHash", "stageId", "commandId"}
        if (not isinstance(binding, dict) or not required_binding.issubset(binding)
                or binding["taskId"] != task or str(binding["revision"]) != str(plan_revision)):
            return {**out, "reason": "verification-plan-mismatch"}
        output = root / decision["verificationOutput"]
        output_rel = output.relative_to(root).as_posix()
        _, run_bytes = input_state(root, output_rel + "/run.json")
        if run_bytes is None:
            return {**out, "reason": "verification-receipt-unavailable"}
        run = json.loads(run_bytes)
        # Reuse the existing validator; unbound passed/zero-test records cannot authorize.
        import run_verified_command as verifier
        verified = verifier.validate_bound_receipt(output, binding=binding,
                                                   expected_phase=phase, expected_root=root)
        if not verified["ok"]:
            return {**out, "reason": "verification-receipt-invalid"}
        plan_time = datetime.fromisoformat(decision["plannedAtUtc"])
        baseline_time = datetime.fromisoformat(doc["createdAtUtc"])
        if (not plan_time.tzinfo or plan_time < baseline_time
                or datetime.fromisoformat(run["startedAt"]) < plan_time):
            return {**out, "reason": "verification-predates-current-plan"}
        pinned = {r["path"]: r for r in run["sourceIdentity"]}
        required = {r["path"] for r in states if r["state"] == "PRESENT"}
        if not required.issubset(pinned) or any(pinned[r["path"]]["sha256"] != r["sha256"]
                                              for r in states if r["state"] == "PRESENT"):
            return {**out, "reason": "verification-input-coverage-mismatch"}
        for row in run["sourceStabilityEnd"]:
            path = root / row["path"]
            meta = path.stat()
            if (meta.st_mtime_ns, meta.st_ctime_ns, meta.st_dev, meta.st_ino, meta.st_size) != (
                    row["mtimeNs"], row["ctimeNs"], row["device"], row["inode"], row["bytes"]):
                return {**out, "reason": "verification-input-identity-changed"}
        current_before, current_raw = input_state(root, before_rel)
        if current_raw != raw or current_before.get("identity") != before_state.get("identity"):
            return {**out, "reason": "baseline-changed-during-comparison"}
        return {**out, "status": decision["action"], "resumeAllowed": True, "reason": "",
                "leaseId": receipt["leaseId"], "fingerprint": receipt["fingerprint"]}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return out


def main(argv=None):
    # cp949 콘솔 대비: 비-CP949 문자는 대체 출력(크래시 방지)
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--before", required=True,
                    help="before.json 경로 또는 var/lease-wait/<waitId> 폴더")
    ap.add_argument("--hunks", action="append", nargs="+", default=[],
                    help="내가 고칠 줄 범위 'path:start-end' (반복 가능, 1-based)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--show-diff", action="store_true",
                    help="변경 파일의 unified diff를 출력에 포함(기본 끔)")
    ap.add_argument("--gate", action="store_true", help="fresh owned resume mutation gate")
    ap.add_argument("--before-sha256")
    ap.add_argument("--task")
    ap.add_argument("--goal-revision")
    ap.add_argument("--plan-revision")
    receipt_args = ap.add_mutually_exclusive_group()
    receipt_args.add_argument("--receipt-json")
    receipt_args.add_argument("--receipt-base64", help="UTF-8 JSON encoded for native Windows argument transport")
    ap.add_argument("--patch-sha256")
    decision_args = ap.add_mutually_exclusive_group()
    decision_args.add_argument("--decision-json")
    decision_args.add_argument("--decision-base64", help="UTF-8 JSON encoded for native Windows argument transport")
    ap.add_argument("--expected-input-digest")
    args = ap.parse_args(argv)
    if args.gate:
        try:
            receipt = json.loads(base64.b64decode(args.receipt_base64, validate=True).decode("utf-8")
                                 if args.receipt_base64 else args.receipt_json or "null")
            decision = json.loads(base64.b64decode(args.decision_base64, validate=True).decode("utf-8")
                                  if args.decision_base64 else args.decision_json or "null")
        except (ValueError, UnicodeError):
            receipt, decision = None, None
        out = check_gate(args.before, args.before_sha256, args.root, args.task,
                         args.goal_revision, args.plan_revision, receipt,
                         args.patch_sha256, decision, args.expected_input_digest)
        print(json.dumps(out, ensure_ascii=True))
        return 0 if out["resumeAllowed"] else 30
    flat = [x for group in args.hunks for x in
            (group if isinstance(group, list) else [group])]
    out = check(args.before, hunks=flat, root=args.root,
                show_diff=args.show_diff)
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return out["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
