"""source-edit lease 충돌 자동 처리 (awx.lease-conflict-autoflow.v1).

다른 작업이 예약한 파일과 내 목표가 겹칠 때, 강제 해제 없이
  scan -> plan -> (비겹침 부분 진행 + 소유자에게 1회 정상 종료 요청)
까지 한 번에 처리한다. 기존 머시너리를 조합할 뿐 새 잠금/VCS가 아니다:

  - __patch_drop__/source_edit_session.ps1 status -Json: lease 상태,
    소유자 프로세스 생존, heartbeat의 권위 있는 관찰자(읽기 전용).
  - __patch_drop__/source-edit-locks/*.lock/lease.json 직접 읽기:
    status 출력에 없는 ownerId/startedAtUtc/lockDir 보강.
  - data/agent-handoff/codex-autonomy/<taskId>/ 의 scope-claim-*.json 과
    journal.json: taskIdHash -> ownerTaskId 해석.
  - request-release: 소유자 task dir에 LEASE_RELEASE_REQUEST.md 하나만 둔다.
    잠금 삭제/강제 종료가 아니라 정상 종료 요청이며, 동일 충돌 지문
    (정렬된 path+ownerTaskId 해시)당 사용자 프롬프트/요청은 1회만 발생한다.

출력은 항상 JSON 한 줄. 종료 코드: 0 ok, 2 usage, 3 소유/신원 오류,
6 증거/세션 오류, 7 = plan이 모든 목표 파일을 차단으로 판정(부분 진행
가능하면 0). 어떤 서브커맨드도 lease 파일/디렉터리를 삭제·이동하지 않는다.
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
import uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_scope_lease as scope  # canon/overlap/run_ps/claim 읽기 재사용

SCHEMA = "awx.lease-conflict-autoflow.v1"
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_DIR.parent
BASE = "data/agent-handoff/codex-autonomy"
AUTOFLOW_BASE = "data/agent-handoff/lease-conflict-autoflow"
PROMPT_DIR = AUTOFLOW_BASE + "/prompted"
REQUEST_FALLBACK_DIR = AUTOFLOW_BASE + "/release-requests"
RELEASE_DOC = "LEASE_RELEASE_REQUEST.md"
LOCKS_DIR = "__patch_drop__/source-edit-locks"
HEARTBEAT_DIR = "__patch_drop__/source-edit-heartbeats"
BUS_PY = SCRIPT_DIR / "awx_device_bus.py"
JOURNAL_PY = SCRIPT_DIR / "work_journal.py"
MAX_TARGETS = 64
MAX_LEASES = 512

USER_PROMPT_TEMPLATE = (
    "작업 {owner}가 {files}를 예약 중입니다. 끝났으면 그 작업에서 "
    "source-edit lease를 정상 종료해 주세요. 강제 해제는 하지 않습니다. "
    "현재 작업은 겹치지 않는 파일만 계속합니다."
)


class AutoflowError(ValueError):
    pass


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.isoformat()


def parse_time(value):
    # ISO 텍스트를 aware datetime으로; 실패 시 None (관찰 불가는 추측이 아니다)
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def sha_text(text: str) -> str:
    return hashlib.sha256(str(text).encode("utf-8")).hexdigest()


def rel(root: Path, path: Path) -> str:
    return str(path.relative_to(root)).replace("\\", "/")


def flatten(values):
    # argparse append+nargs 조합을 펼친다
    out = []
    for group in values or []:
        out.extend(group if isinstance(group, list) else [group])
    return out


def atomic_write(path: Path, text: str) -> bool:
    # 같은 내용이면 다시 쓰지 않는다(멱등). 바이트가 다를 때만 교체.
    data = (text + "\n").encode("utf-8")
    try:
        if path.is_file() and path.read_bytes() == data:
            return False
    except OSError:
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with tmp.open("xb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()
    return True


def read_heartbeat(root: Path, lease: dict, lease_bytes, now: datetime):
    # 계약과 동일한 heartbeat 유효성 검사(로컬 판독). 유효하면 유효 만료를 연장한다.
    result = {"state": "absent", "renewedAtUtc": None, "expiresAtUtc": None}
    lease_id = str(lease.get("leaseId") or "")
    if not re.fullmatch(r"[a-f0-9]{32}", lease_id):
        return result
    hb_path = root / HEARTBEAT_DIR / (lease_id + ".json")
    try:
        raw = hb_path.read_bytes()
    except OSError:
        return result
    result["state"] = "invalid"
    try:
        hb = json.loads(raw)
        renewed = parse_time(hb.get("renewedAtUtc"))
        until = parse_time(hb.get("expiresAtUtc"))
        fingerprint = hashlib.sha256(lease_bytes).hexdigest()
        valid = (hb.get("leaseId") == lease_id and renewed and until and
                 str(hb.get("leaseFingerprint", "")).lower() == fingerprint and
                 renewed <= now + timedelta(seconds=30) and until > renewed and
                 until <= renewed + timedelta(minutes=540))
        if not valid:
            return result
        result.update(state="valid", renewedAtUtc=iso(renewed),
                      expiresAtUtc=iso(until),
                      ageSeconds=max(0, int((now - renewed).total_seconds())))
        return result
    except (ValueError, OSError):
        return result


def iter_lock_leases(root: Path):
    # lock 디렉터리를 직접 열거해 lease.json 원문 필드를 읽는다(삭제/변경 없음).
    locks = root / LOCKS_DIR
    if not locks.is_dir():
        return
    try:
        dirs = sorted(locks.glob("*.lock"))
    except OSError:
        dirs = []
    for lock_dir in dirs[:MAX_LEASES]:
        row = {"topic": lock_dir.name[:-5] if lock_dir.name.lower().endswith(".lock")
               else lock_dir.name,
               "lockDir": lock_dir.name,
               "leasePath": rel(root, lock_dir / "lease.json"),
               "rawStatus": "corrupt", "reason": "lease-json-missing",
               "targetPaths": [], "scopeUnknown": True}
        lease_path = lock_dir / "lease.json"
        try:
            raw = lease_path.read_bytes()
            lease = json.loads(raw)
        except (OSError, ValueError):
            if lease_path.is_file():
                row["reason"] = "lease-json-invalid"
            yield lock_dir, row, None, None
            continue
        if not isinstance(lease, dict):
            yield lock_dir, row, None, None
            continue
        targets = [str(p).replace("\\", "/").lower() for p in
                   (lease.get("targetPaths") or []) if isinstance(p, str)]
        row.update({
            "rawStatus": "ok", "reason": "",
            "leaseId": lease.get("leaseId"),
            "taskIdHash": lease.get("taskIdHash"),
            "ownerHash": lease.get("ownerHash"),
            "ownerId": lease.get("ownerId"),
            "role": lease.get("role"),
            "startedAtUtc": lease.get("startedAtUtc") or lease.get("generatedAt"),
            "expiresAtUtc": lease.get("expiresAtUtc") or lease.get("expiresAt"),
            "targetManifestHash": lease.get("targetManifestHash"),
            "targetPaths": sorted(set(targets)),
            # targetPaths 없고 manifest hash만 있으면 범위 미해석 -> 전부 겹침 취급
            "scopeUnknown": not targets,
        })
        yield lock_dir, row, lease, raw


def session_status_rows(root: Path):
    # 권위 있는 status -Json (ownerState/heartbeatState/바운드 스코프 해석 포함).
    try:
        proc = scope.run_ps(root, "status", want_json=True, timeout=60)
    except scope.ScopeError:
        return None
    summary = scope.parse_json_lines(proc.stdout)
    if not isinstance(summary, dict):
        return None
    return summary.get("sourceLeases") or []


def owner_index(root: Path):
    # taskIdHash -> taskId, topic/leaseName -> claim, taskId -> journal 상태.
    claims = list(scope.iter_claims(root))
    journals = {}
    for doc in scope.iter_task_docs(root, "journal.json"):
        if isinstance(doc, dict) and isinstance(doc.get("taskId"), str):
            journals[doc["taskId"]] = {"status": doc.get("status"),
                                       "agent": doc.get("agent"),
                                       "result": doc.get("result")}
    hash_map = {}
    for task_id in journals:
        hash_map[sha_text(task_id)] = task_id
    for claim in claims:
        task_id = claim.get("taskId")
        if isinstance(task_id, str):
            hash_map.setdefault(sha_text(task_id), task_id)
    return claims, journals, hash_map


def resolve_owner(row: dict, claims, journals, hash_map):
    # lease -> ownerTaskId. 근거를 basis에 명시(추측 금지).
    for claim in claims:
        if claim.get("leaseName") and claim.get("leaseName") == row.get("lockDir"):
            return claim.get("taskId"), "claim-leaseName", claim
    for claim in claims:
        if claim.get("topic") == row.get("topic") and not claim.get("released"):
            return claim.get("taskId"), "claim-topic", claim
    task_hash = str(row.get("taskIdHash") or "")
    if task_hash and task_hash in hash_map:
        return hash_map[task_hash], "task-id-hash", None
    topic = str(row.get("topic") or "")
    if task_hash and topic and sha_text(topic) == task_hash:
        return topic, "topic", None
    return None, "unresolved", None


def classify(row: dict, owner_journal_status, release_pending: bool) -> str:
    # active|expired_unknown|finishing. expired/corrupt도 겹침을 계속 막는다.
    if row.get("rawStatus") not in ("active", "expired"):
        return "expired_unknown"
    if owner_journal_status == "closed" or release_pending:
        return "finishing"
    return "active" if row["rawStatus"] == "active" else "expired_unknown"


def collect_leases(root: Path, my_task=None):
    # ps1 status 행(권위) + 직접 읽은 lease.json(보강 필드)을 leaseId로 병합.
    now = utcnow()
    session_rows = session_status_rows(root) or []
    by_lease_id = {r.get("leaseId"): r for r in session_rows if r.get("leaseId")}
    by_topic = {}
    for r in session_rows:
        by_topic.setdefault(str(r.get("topic") or ""), []).append(r)
    claims, journals, hash_map = owner_index(root)
    my_claims = {c.get("leaseName") for c in scope.task_claims(root, my_task)} if my_task else set()

    rows = []
    for lock_dir, row, lease, raw in iter_lock_leases(root):
        session = by_lease_id.get(row.get("leaseId") or "")
        if session is None:
            candidates = by_topic.get(row["topic"], [])
            session = candidates[0] if len(candidates) == 1 else None
        if session:
            row["rawStatus"] = session.get("status") or row["rawStatus"]
            row["reason"] = session.get("reason") or row["reason"]
            row["ownerState"] = session.get("ownerState") or "unknown"
            row["recoveryReason"] = session.get("recoveryReason") or "owner-evidence-needed"
            row["heartbeatState"] = session.get("heartbeatState") or "absent"
            row["heartbeatAgeSeconds"] = session.get("heartbeatAgeSeconds")
            row["expiresAtUtc"] = session.get("expiresAtUtc") or row.get("expiresAtUtc")
            if not row["targetPaths"] and session.get("targetPaths"):
                row["targetPaths"] = sorted(session["targetPaths"])
                row["scopeUnknown"] = False
            row["lastHeartbeatUtc"] = None  # ps1 행에는 갱신 시각이 없다
            hb = read_heartbeat(root, lease or {}, raw or b"", now) if lease else {"state": "absent"}
            if hb.get("renewedAtUtc"):
                row["lastHeartbeatUtc"] = hb["renewedAtUtc"]
        else:
            row["ownerState"] = "unknown"
            row["recoveryReason"] = "owner-evidence-needed"
            hb = read_heartbeat(root, lease, raw, now) if lease else {"state": "absent"}
            row["heartbeatState"] = hb["state"]
            row["heartbeatAgeSeconds"] = hb.get("ageSeconds")
            row["lastHeartbeatUtc"] = hb.get("renewedAtUtc")
            expiry = parse_time(row.get("expiresAtUtc"))
            if hb.get("expiresAtUtc"):
                hb_until = parse_time(hb["expiresAtUtc"])
                if hb_until and (not expiry or hb_until > expiry):
                    expiry = hb_until
                    row["expiresAtUtc"] = iso(expiry)
            if row["rawStatus"] == "ok":
                row["rawStatus"] = ("expired" if expiry and expiry <= now
                                    else "active") if expiry else "corrupt"
                if not expiry:
                    row["reason"] = "expiry-missing"
        started = parse_time(row.get("startedAtUtc"))
        row["ageSeconds"] = int((now - started).total_seconds()) if started else None
        row["ownerTaskId"], basis, claim = resolve_owner(row, claims, journals, hash_map)
        row["ownerTaskIdBasis"] = basis
        owner_journal = journals.get(row["ownerTaskId"]) or {}
        row["ownerJournalStatus"] = owner_journal.get("status") or "missing"
        row["releasePending"] = bool(
            row["ownerTaskId"] and
            (root / BASE / row["ownerTaskId"] / RELEASE_DOC).is_file())
        row["status"] = classify(row, row["ownerJournalStatus"], row["releasePending"])
        row["ownLease"] = row["lockDir"] in my_claims
        rows.append(row)
    return rows, now


def overlapping_targets(row: dict, targets):
    if row.get("scopeUnknown"):
        return list(targets)  # 범위 미해석 예약은 배제 불가 -> 전부 겹침
    owned = row.get("targetPaths") or []
    return sorted({t for t in targets for o in owned if scope.overlap(t, o)})


def scan_report(root: Path, targets, my_task=None):
    leases, now = collect_leases(root, my_task=my_task)
    report_rows, blocked = [], set()
    for row in leases:
        hits = overlapping_targets(row, targets) if targets else []
        if hits and not row.get("ownLease"):
            blocked.update(hits)
        entry = {"topic": row["topic"], "leaseId": row.get("leaseId"),
                 "lockDir": row["lockDir"], "status": row["status"],
                 "rawStatus": row["rawStatus"], "reason": row.get("reason") or "",
                 "ownerTaskId": row.get("ownerTaskId"),
                 "ownerTaskIdBasis": row.get("ownerTaskIdBasis"),
                 "ownerId": row.get("ownerId"), "role": row.get("role"),
                 "ownerState": row.get("ownerState"),
                 "recoveryReason": row.get("recoveryReason"),
                 "ownerJournalStatus": row.get("ownerJournalStatus"),
                 "heartbeatState": row.get("heartbeatState"),
                 "lastHeartbeatUtc": row.get("lastHeartbeatUtc"),
                 "heartbeatAgeSeconds": row.get("heartbeatAgeSeconds"),
                 "ageSeconds": row.get("ageSeconds"),
                 "expiresAtUtc": row.get("expiresAtUtc"),
                 "releasePending": row.get("releasePending"),
                 "ownLease": row.get("ownLease"),
                 "scopeUnknown": row.get("scopeUnknown"),
                 "overlappingTargets": hits,
                 "targetPaths": row.get("targetPaths") or []}
        if hits or not targets:
            report_rows.append(entry)
    foreign = [r for r in report_rows if not r.get("ownLease")]
    return {"schemaVersion": SCHEMA, "action": "scan", "generatedAtUtc": iso(now),
            "targets": list(targets), "blockedTargets": sorted(blocked),
            "freeTargets": sorted(set(targets) - blocked),
            "overlappingLeases": [r for r in foreign if r["overlappingTargets"]],
            "leases": report_rows,
            "counts": {"leases": len(leases), "overlapping": len(foreign),
                       "blockedTargets": len(blocked)}}


def conflict_fingerprint(blocked_pairs):
    # 지문 = 정렬된 "path|ownerKey" 목록의 해시 (ownerKey=taskId 또는 leaseId)
    lines = sorted(f"{path}|{owner}" for path, owner in blocked_pairs)
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()[:16]


def marker_path(root: Path, fingerprint: str) -> Path:
    return root / PROMPT_DIR / (fingerprint + ".json")


def build_user_prompt(blocked_by_owner):
    # 소유자별 표준 문구 1건씩, owner 정렬로 결정적 출력
    lines = []
    for owner in sorted(blocked_by_owner):
        files = ", ".join(sorted(blocked_by_owner[owner]))
        lines.append(USER_PROMPT_TEMPLATE.format(owner=owner, files=files))
    return "\n".join(lines)


def bus_event_ref(root: Path, fingerprint: str, doc_rel: str):
    # notification-only 이벤트 참조. 실패해도 요청 문서 자체는 유효.
    if not BUS_PY.is_file():
        return None
    task_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, "lease-release:" + fingerprint))
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(BUS_PY), "--root", str(root), "emit",
             "--task-id", task_uuid, "--event-type", "proposal_created",
             "--status", "proposed", "--file", doc_rel],
            capture_output=True, text=True, timeout=40)
    except (OSError, subprocess.TimeoutExpired):
        return None
    row = scope.parse_json_lines(proc.stdout)
    if isinstance(row, dict) and row.get("status") == "queued":
        return row.get("eventRef")
    return None


def release_doc_text(owner_task, files, requester, fingerprint, leases):
    lease_lines = []
    for row in leases:
        end_hint = ("powershell -NoProfile -ExecutionPolicy Bypass -File "
                    "__patch_drop__/source_edit_session.ps1 -Action end "
                    f"-Topic {row.get('topic')} -OwnerId <ownerId> "
                    "-LeaseFingerprint <lease.json sha256>")
        lease_lines.append(
            f"- topic `{row.get('topic')}` leaseId `{row.get('leaseId')}` "
            f"status `{row.get('status')}` expiresAtUtc `{row.get('expiresAtUtc')}`\n"
            f"  정상 종료: `python -B scripts/agent_scope_lease.py done --task {owner_task}` "
            f"또는 `{end_hint}`")
    return (
        "# LEASE_RELEASE_REQUEST\n\n"
        f"- schemaVersion: `awx.lease-release-request.v1`\n"
        f"- requestedAtUtc: `{iso(utcnow())}`\n"
        f"- fingerprint: `{fingerprint}`\n"
        f"- requestedBy: `{requester or 'unknown'}`\n"
        f"- forceRelease: `false` (강제 해제/lock 삭제 금지)\n\n"
        + USER_PROMPT_TEMPLATE.format(owner=owner_task,
                                    files=", ".join(sorted(files)))
        + "\n\n## 예약 중인 lease\n\n" + "\n".join(lease_lines) + "\n")


def request_release(root: Path, owner_task: str, files, requester=None,
                    fingerprint=None, leases=None):
    # 소유자 task dir에 요청 문서 1개만 둔다. 타 task journal에는 쓰지 않는다.
    owner_dir = root / BASE / owner_task
    unresolved = not owner_dir.is_dir()
    if unresolved:
        safe = re.sub(r"[^A-Za-z0-9_.-]", "-", str(owner_task)).strip("-") or "unknown"
        doc_path = root / REQUEST_FALLBACK_DIR / (safe + ".md")
    else:
        doc_path = owner_dir / RELEASE_DOC
    fingerprint = fingerprint or conflict_fingerprint(
        [(f, owner_task) for f in files])
    existing_fp = None
    try:
        prior = doc_path.read_text(encoding="utf-8", errors="replace")
        found = re.search(r"fingerprint: `([a-f0-9]{8,64})`", prior)
        existing_fp = found.group(1) if found else None
    except OSError:
        pass
    if existing_fp == fingerprint:
        written = False  # 동일 지문 요청은 다시 쓰지 않는다(1회 전달)
    else:
        text = release_doc_text(owner_task, files, requester, fingerprint,
                                leases or [])
        written = atomic_write(doc_path, text)
    doc_rel = rel(root, doc_path)
    ref = bus_event_ref(root, fingerprint, doc_rel) if written else None
    return {"task": owner_task, "path": doc_rel, "written": written,
            "alreadyPresent": not written, "unresolvedOwnerDir": unresolved,
            "busEventRef": ref, "fingerprint": fingerprint}


def journal_lease_conflict(root: Path, task_id: str, owners, files, action, refs):
    if not task_id:
        return {"applied": False, "reason": "no-task"}
    payload = {"owners": sorted(owners), "files": sorted(files), "action": action}
    text = "lease_conflict: " + json.dumps(payload, ensure_ascii=True)
    ok = scope.journal_note(root, task_id, "lease_conflict", text, refs)
    return {"applied": ok, "task": task_id}


def cmd_scan(root: Path, args) -> int:
    targets = [scope.canon(t) for t in flatten(args.targets)]
    if len(targets) > MAX_TARGETS:
        raise AutoflowError("too-many-targets")
    print(json.dumps(scan_report(root, targets, my_task=args.task),
                     ensure_ascii=True))
    return 0


def cmd_plan(root: Path, args) -> int:
    goals = [scope.canon(t) for t in flatten(args.goal_files)]
    if not goals or len(goals) > MAX_TARGETS:
        raise AutoflowError("goal-files-required")
    report = scan_report(root, goals, my_task=args.task)
    proceed = [t for t in goals if t in set(report["freeTargets"])]
    blocked_pairs, blocked_by_owner = [], {}
    blocked_detail = []
    for row in report["overlappingLeases"]:
        owner = row.get("ownerTaskId") or ("topic:" + row["topic"])
        for path in row["overlappingTargets"]:
            blocked_pairs.append((path, owner))
            blocked_by_owner.setdefault(owner, set()).add(path)
        blocked_detail.append({"topic": row["topic"], "leaseId": row["leaseId"],
                               "ownerTaskId": row.get("ownerTaskId"),
                               "status": row["status"],
                               "expiresAtUtc": row.get("expiresAtUtc"),
                               "overlappingTargets": row["overlappingTargets"]})
    fingerprint = conflict_fingerprint(blocked_pairs) if blocked_pairs else None
    marker = marker_path(root, fingerprint) if fingerprint else None
    already = marker.is_file() if marker else False
    prompt_state, user_prompt = "none-required", None
    if blocked_pairs:
        if already:
            prompt_state = "suppressed-already-emitted"
        else:
            prompt_state = "emitted"
            user_prompt = build_user_prompt(blocked_by_owner)
            if not args.no_mark:
                atomic_write(marker, json.dumps(
                    {"schemaVersion": "awx.lease-conflict-prompt.v1",
                     "fingerprint": fingerprint, "emittedAtUtc": iso(utcnow()),
                     "owners": sorted(blocked_by_owner),
                     "blockedPaths": sorted({p for p, _ in blocked_pairs}),
                     "prompt": user_prompt}, ensure_ascii=True, indent=2))

    auto_actions, action_kind = [], "continue_partial" if proceed else "await_release"
    if args.execute and blocked_pairs:
        refs = []
        for owner in sorted(blocked_by_owner):
            owner_leases = [r for r in report["overlappingLeases"]
                            if (r.get("ownerTaskId") or ("topic:" + r["topic"])) == owner]
            outcome = request_release(root, owner, sorted(blocked_by_owner[owner]),
                                      requester=args.task, fingerprint=fingerprint,
                                      leases=owner_leases)
            refs.append(outcome["path"])
            auto_actions.append({"action": "request-release", **outcome})
        if marker:
            refs.append(rel(root, marker))
        note = journal_lease_conflict(root, args.task, blocked_by_owner.keys(),
                                      {p for p, _ in blocked_pairs}, action_kind, refs)
        auto_actions.append({"action": "journal-note", **note})
    elif blocked_pairs:
        for owner in sorted(blocked_by_owner):
            auto_actions.append({"action": "request-release", "task": owner,
                                 "applied": False, "pending": True})
    result = {"schemaVersion": SCHEMA, "action": "plan", "generatedAtUtc": iso(utcnow()),
              "goalFiles": goals,
              "proceed_without": proceed,
              "blocked": sorted({p for p, _ in blocked_pairs}),
              "blockedDetail": blocked_detail,
              "conflictFingerprint": fingerprint,
              "promptState": prompt_state,
              "user_prompt": user_prompt,
              "auto_actions": auto_actions,
              "policy": {"forceRelease": False,
                         "expiredUnknownStillBlocks": True,
                         "promptOncePerFingerprint": True},
              "nextAction": action_kind}
    print(json.dumps(result, ensure_ascii=True))
    return 7 if blocked_pairs and not proceed else 0


def cmd_heartbeat(root: Path, args) -> int:
    claims = [c for c in scope.task_claims(root, args.task) if not c.get("released")]
    if not claims:
        raise AutoflowError("scope-claim-missing")
    results, worst = [], 0
    for claim in claims:
        proc = scope.run_ps(root, "heartbeat", topic=claim["topic"],
                            owner=claim["ownerId"],
                            fingerprint=claim["fingerprint"],
                            ttl=args.ttl or claim.get("ttlMinutes"))
        row = scope.parse_json_lines(proc.stdout)
        results.append({"topic": claim["topic"], "renewed": proc.returncode == 0,
                        "exitCode": proc.returncode, "heartbeat": row})
        if proc.returncode != 0:
            worst = worst or proc.returncode
    print(json.dumps({"schemaVersion": SCHEMA, "action": "heartbeat",
                      "task": args.task, "results": results,
                      "renewed": worst == 0}, ensure_ascii=True))
    return worst


def cmd_request_release(root: Path, args) -> int:
    files = [scope.canon(t) for t in flatten(args.files)]
    if not files:
        raise AutoflowError("files-required")
    owner = str(args.task)
    owner_dir = root / BASE / owner
    leases = []
    if not owner_dir.is_dir():
        # topic으로 넘어온 경우 claim/journal에서 taskId를 해석한다
        report = scan_report(root, files)
        hit = next((r for r in report["overlappingLeases"]
                    if r["topic"] == owner and r.get("ownerTaskId")), None)
        if hit:
            owner = hit["ownerTaskId"]
            owner_dir = root / BASE / owner
    else:
        report = scan_report(root, files)
    leases = [r for r in report["overlappingLeases"]
              if (r.get("ownerTaskId") or ("topic:" + r["topic"])) == owner] or \
             [r for r in report["overlappingLeases"]]
    outcome = request_release(root, owner, files, requester=args.requester,
                              leases=leases)
    marker = marker_path(root, outcome["fingerprint"])
    marked = marker.is_file()
    print(json.dumps({"schemaVersion": SCHEMA, "action": "request-release",
                      **outcome, "promptMarked": marked}, ensure_ascii=True))
    return 0


def add_root(parser):
    parser.add_argument("--root", default=argparse.SUPPRESS,
                        help="repo root (default: this checkout)")


def add_paths(parser, name):
    parser.add_argument(name, action="append", nargs="+", default=[])


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(DEFAULT_ROOT),
                        help="repo root (default: this checkout)")
    sub = parser.add_subparsers(dest="action", required=True)

    p = sub.add_parser("scan", help="overlapping lease inventory for target paths")
    add_root(p)
    add_paths(p, "--targets")
    p.add_argument("--task", help="my taskId: its own leases are marked ownLease")
    p.set_defaults(func=cmd_scan)

    p = sub.add_parser("plan", help="split blocked/proceed + once-only standard request")
    add_root(p)
    add_paths(p, "--goal-files")
    p.add_argument("--task", help="my taskId (journal note + own-lease exclusion)")
    p.add_argument("--execute", action="store_true",
                   help="write release requests + journal note")
    p.add_argument("--no-mark", action="store_true",
                   help="preview only: do not record the prompt fingerprint")
    p.set_defaults(func=cmd_plan)

    p = sub.add_parser("heartbeat", help="renew heartbeat on all my task's leases")
    add_root(p)
    p.add_argument("--task", required=True)
    p.add_argument("--ttl", type=int)
    p.set_defaults(func=cmd_heartbeat)

    p = sub.add_parser("request-release",
                       help="leave one standard release request in the owner task dir")
    add_root(p)
    p.add_argument("--task", required=True, help="owner taskId (or topic)")
    add_paths(p, "--files")
    p.add_argument("--requester", help="requesting taskId recorded in the doc")
    p.set_defaults(func=cmd_request_release)
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(getattr(args, "root", None) or DEFAULT_ROOT).resolve()
    try:
        return args.func(root, args)
    except (AutoflowError, scope.ScopeError) as error:
        reason = str(error)
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": reason}, ensure_ascii=True))
        if "owner" in reason or "claim" in reason:
            return 3
        return 6 if "conflict" not in reason else 7
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"autoflow-io-or-evidence-error:{error}"},
                         ensure_ascii=True))
        return 6


if __name__ == "__main__":
    sys.exit(main())
