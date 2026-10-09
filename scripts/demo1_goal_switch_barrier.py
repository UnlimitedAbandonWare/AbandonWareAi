#!/usr/bin/env python3
"""demo1_goal_switch_barrier.py — 바이브 목표 전환 장벽 (goal-switch barrier).

목표가 로테이트/재라우팅될 때 한 곳에서 처리한다:

  check            소유 in_progress journal + 소유 lease 요약, switch 필요 여부 JSON
  check-goal       Codex 목표 등록 전 안전 판정 (advisory, 항상 exit 0) —
                   활성 목표/P7 흔적이 감지되면 action=UPDATE_EXISTING, 호출자가
                   활성 목표 없음을 보고할 때만 CREATE_NEW + get_goal 회복 스니펫
  switch           소유 in_progress를 superseded|abandoned로 닫고, 소유 lease를
                   end 하며, allowedOpen 신호 + 새 ask의 router resolve를 출력
  reject-complete  "Read ... before continuing" 같은 지시문/도구 서문이 완료
                   문장으로 오인되는 것을 차단 (exit != 0). 단 구체적 검증 증거
                   (exit 0, 테스트 통과 수치, 파일 변경)가 함께 있으면 수용.

소유 판정: journal.agent == --agent 이거나, lease/claim의 ownerId가 --agent 또는
--agent 소유 taskId 와 일치할 때만. foreign in_progress/lease는 절대 변경하지
않는다 — source_edit_session.ps1 의 ownerId 검사(exit 3 owner-mismatch)가 물리적
경계다. 보호: 최근 --protect-minutes 이내 갱신 journal 또는 활성 소유 lease를
가진 task는 명시적 --old-task / --include-recent 없이 건드리지 않는다 (살아있는
형제 세션 보호).

Exit codes: 0 ok / 2 usage / 5 instructional-not-acceptance / 6 evidence-io /
7 blocked (switch required 또는 switch 후에도 잔여 소유 목표).
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.goal-switch-barrier.v1"
SCRIPTS = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPTS.parent
BASE = "data/agent-handoff/codex-autonomy"
LOCKS_REL = "__patch_drop__/source-edit-locks"
JOURNAL_SCHEMA = "awx.work_journal.v1"
CLAIM_SCHEMA = "awx.agent-scope-claim.v1"
JOURNAL_PY = SCRIPTS / "work_journal.py"
ROUTER_PY = SCRIPTS / "demo1_vibe_skill_router.py"
SESSION_PS1 = DEFAULT_ROOT / "__patch_drop__" / "source_edit_session.ps1"

STALE_HOURS = 24          # journal idle 기준 stale 표시
PROTECT_MINUTES = 30      # 기본 보호 창: 이 시간 내 갱신된 소유 journal은 skip
MAX_PURPOSE = 400
MAX_SCAN = 512

# 지시문/메타 패턴 — 완료 문장으로 오인 금지 (brief의 최소셋)
INSTRUCTIONAL = [
    ("before-continuing", re.compile(r"\bbefore\s+(?:you\s+)?continu(?:e|ing)\b", re.I)),
    ("read-before", re.compile(r"\b(?:re-?)?read\b[^\n]{0,200}?\bbefore\b", re.I)),
    ("re-read-before", re.compile(r"\bre-?read\b", re.I)),
    ("use-skill", re.compile(r"\b(?:use|invoke|load|apply|follow)\s+\$[A-Za-z0-9][\w.-]*", re.I)),
    ("before-proceeding", re.compile(r"\bbefore\s+(?:proceeding|you\s+proceed|continuing)\b", re.I)),
    # goal 파일 "읽기"를 Done으로 주장 — intake는 acceptance가 아니다.
    # 등록 목표 제목/완료 문장이 읽기 행위면 항상 reject (구현 증거 문장만 통과).
    ("goal-read-done", re.compile(
        r"(?:goal[-\s]?objective(?:\.md)?|목표\s*(?:파일|objective))"
        r"[^\n]{0,120}?(?:읽|read|완료|done|completed?)", re.I)),
    ("read-goal-done", re.compile(
        r"\b(?:read|reading)\s+(?:the\s+)?goal(?:[-\s]?objective)?(?:\.md)?\b"
        r"|목표\s*파일\s*읽기", re.I)),
    ("registered-goal-read-done", re.compile(
        r"등록된\s*목표[^\n]{0,120}?(?:읽|read)[^\n]{0,120}?(?:완료|done|completed?)", re.I)),
    ("intake-only-done", re.compile(
        r"\b(?:intake|preflight)[-\s]?only\b[^\n]{0,80}?"
        r"(?:done|complete|finished|완료|끝)"
        r"|문서만\s*확인[^\n]{0,80}?(?:완료|done|끝|했습)", re.I)),
]
# 순수 tool preamble: 텍스트 전체가 명령어 한 줄 (수락 문장이 아님)
TOOL_PREAMBLE = re.compile(
    r"^\s*(?:python(?:\.exe)?\b|py\b|pytest\b|powershell(?:\.exe)?\b|pwsh\b|"
    r"npm\b|npx\b|node\b|gradlew(?:\.bat)?\b|\.\.?[\\/]|curl\b|git\b)[^\n]*$",
    re.I,
)

# 구체적 검증 증거 — 지시 어휘가 함께 있어도 완료 보고를 수용하는 최소셋.
# "사람 얼굴이 감지되면 소프트하게" 열어주는 모델과 같이, 실제 테스트 통과/
# 파일 변경 증거가 명시된 보고는 어휘 매칭만으로 reject하지 않는다.
EVIDENCE = [
    ("exit-zero", re.compile(
        r"\bexit\s*(?:code)?\s*[:=]?\s*0(?!\d)\b|\bexitcode\s*[:=]?\s*0\b", re.I)),
    ("build-successful", re.compile(r"\bBUILD\s+SUCCESSFUL\b", re.I)),
    ("test-ratio", re.compile(
        r"\b\d+\s*/\s*\d+\b[^\n]{0,40}?(?:pass(?:ed)?|tests?|cases?|통과)"
        r"|(?:tests?|cases?)\s*[=:]?\s*\d+\s*/\s*\d+", re.I)),
    ("tests-passed-count", re.compile(
        r"\b\d+\s+tests?\s+pass(?:ed)?\b|\ball\s+tests?\s+pass(?:ed)?\b"
        r"|failures\s*[=:\"]?\s*0\b", re.I)),
    ("file-change", re.compile(
        r"(?:modified|patched|edited|updated|created|changed|수정|변경|패치|생성)"
        r"[^\n]{0,60}?(?:\.java|\.py|\.ya?ml|\.md|\.ps1|\.kts|scripts/|main/java/|src/test/)",
        re.I)),
    ("verified-marker", re.compile(
        r"\bverified\b[^\n]{0,60}?(?:exit|pass|통과)|(?:통과|pass(?:ed)?)[^\n]{0,40}?verified",
        re.I)),
]

# P7 goal-conflict 방어 — Codex 플랫폼 목표 등록 안전 프로토콜.
# action 판정은 UPDATE_EXISTING vs CREATE_NEW 둘뿐이며, 목표 상태가 미확인이면
# 충돌 가능으로 보아 보수적으로 UPDATE_EXISTING을 고른다 (blind create_goal 금지).
GOAL_CONFLICT_MARKERS = ("unfinished goal", "has an active goal")
GOAL_SESSION_SCAN_BYTES = 2_000_000
P7_NEXT_ACTION = ("Call update_goal or close previous active goal first, "
                  "never retry create_goal")
P7_REMEDY_CMD = "python -B scripts/demo1_goal_switch_barrier.py check-goal"
P7_RECOVERY_SNIPPET = (
    'const g = await tools.get_goal(); '
    'if (g && g.id) { await tools.update_goal({status: "completed"}); } '
    'await tools.create_goal({objective: <new objective>});'
)
SAFE_REGISTRATION_PROTOCOL = [
    "create_goal 호출 전 반드시 get_goal로 현재 스레드의 활성/미완료 목표를 확인한다",
    "활성 목표가 있으면 update_goal로 목표를 덮어쓰거나 status=completed 처리 후 create_goal한다",
    "'unfinished goal'(P7) 거절 관측 시 create_goal 재시도 금지 — 즉시 update_goal로 전환한다",
]


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_ts(value):
    try:
        stamp = datetime.fromisoformat(str(value or "").replace("Z", "+00:00"))
    except ValueError:
        return None
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=timezone.utc)
    return stamp


def idle_minutes(value, now):
    stamp = parse_ts(value)
    if stamp is None:
        return None
    return round((now - stamp).total_seconds() / 60, 1)


def _safe_id(value) -> bool:
    return isinstance(value, str) and bool(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}", value))


def _read_json(path: Path):
    try:
        return json.loads(path.read_bytes())
    except (OSError, ValueError):
        return None


def iter_journals(root: Path):
    base = root / BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob("*/journal.json"))[:MAX_SCAN]:
        doc = _read_json(path)
        if isinstance(doc, dict) and doc.get("schemaVersion") == JOURNAL_SCHEMA:
            yield doc


def iter_claims(root: Path):
    base = root / BASE
    if not base.is_dir():
        return
    for path in sorted(base.glob("*/scope-claim-*.json"))[:MAX_SCAN]:
        doc = _read_json(path)
        if isinstance(doc, dict) and doc.get("schemaVersion") == CLAIM_SCHEMA:
            doc["_path"] = path
            yield doc


def read_leases(root: Path):
    """lease.json 파일에서 ownerId/expiresAtUtc를 직접 읽는다 (status -Json은
    ownerHash만 준다). status 필드는 로컬 만료 계산; ps1 병합 시 덮어쓴다."""
    lock_root = root / LOCKS_REL
    out = {}
    if not lock_root.is_dir():
        return out
    for lock_dir in sorted(lock_root.glob("*.lock"))[:MAX_SCAN]:
        doc = _read_json(lock_dir / "lease.json")
        if not isinstance(doc, dict):
            out[lock_dir.name[:-5]] = {"topic": lock_dir.name[:-5], "status": "corrupt"}
            continue
        topic = str(doc.get("topic") or lock_dir.name[:-5])
        out[topic] = {
            "topic": topic,
            "ownerId": doc.get("ownerId"),
            "expiresAtUtc": doc.get("expiresAtUtc") or doc.get("expiresAt"),
            "generatedAt": doc.get("generatedAt"),
            "status": "active",
            "targetCount": doc.get("targetCount"),
            "lockPath": str(lock_dir.relative_to(root)).replace("\\", "/"),
        }
    return out


def run_ps(root: Path, *ps_args, timeout=90):
    if not SESSION_PS1.is_file():
        return None, "session-script-missing"
    cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
           "Bypass", "-File", str(SESSION_PS1), "-Root", str(root), *ps_args]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        return None, f"session-call-failed:{error}"
    for line in str(proc.stdout or "").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line), proc.returncode
            except ValueError:
                continue
    return None, proc.returncode


def merge_lease_status(root: Path, leases: dict):
    """ps1 status -Json의 status(ownerState 포함)를 topic 기준으로 병합한다."""
    data, code = run_ps(root, "-Action", "status", "-Json")
    if not isinstance(data, dict):
        return "unavailable"
    for row in data.get("sourceLeases") or []:
        topic = row.get("topic")
        if topic in leases:
            leases[topic]["status"] = row.get("status") or leases[topic]["status"]
            leases[topic]["expiresAtUtc"] = row.get("expiresAtUtc") or leases[topic]["expiresAtUtc"]
            leases[topic]["ownerState"] = row.get("ownerState")
        elif isinstance(row, dict):
            leases[topic] = {"topic": topic, "status": row.get("status"),
                             "expiresAtUtc": row.get("expiresAtUtc"),
                             "ownerState": row.get("ownerState")}
    return "ok"


def local_lease_status(leases: dict, now):
    for lease in leases.values():
        if lease.get("status") == "corrupt":
            continue
        expires = parse_ts(lease.get("expiresAtUtc"))
        lease["status"] = "expired" if expires and expires <= now else "active"


def task_agents(journals):
    return {j.get("taskId"): j.get("agent") for j in journals if j.get("taskId")}


def is_owned_lease(lease, agent, agents):
    """ownerId==agent 이거나 ownerId가 agent 소유 journal의 taskId일 때만 소유.
    'devin-desktop'이 'devin'에 매칭되지 않도록 prefix 매칭은 쓰지 않는다."""
    owner = str(lease.get("ownerId") or "")
    if not owner:
        return False
    return owner == agent or agents.get(owner) == agent


def claim_is_owned(claim, agent):
    return claim.get("agent") == agent or str(claim.get("ownerId") or "") == agent


def task_lease_topics(task_id, claims, leases):
    """task 디렉터리의 unreleased claim topic + ownerId==taskId인 lease topic."""
    topics = {c.get("topic") for c in claims
              if c.get("taskId") == task_id and not c.get("released")}
    topics |= {t for t, l in leases.items() if str(l.get("ownerId") or "") == task_id}
    return {t for t in topics if t}


def collect(root: Path, agent: str, skip_lease_scan: bool, protect_minutes: int):
    now = datetime.now(timezone.utc)
    journals = list(iter_journals(root))
    claims = list(iter_claims(root))
    leases = read_leases(root)
    lease_scan = "skipped"
    if not skip_lease_scan:
        lease_scan = merge_lease_status(root, leases)
    if lease_scan != "ok":
        local_lease_status(leases, now)
    agents = task_agents(journals)
    owned_open = []
    foreign_open = 0
    for journal in journals:
        if journal.get("status") != "in_progress":
            continue
        if journal.get("agent") != agent:
            foreign_open += 1
            continue
        idle = idle_minutes(journal.get("updatedAtUtc"), now)
        linked = task_lease_topics(journal.get("taskId"), claims, leases)
        active_lease = any(leases.get(t, {}).get("status") == "active" for t in linked)
        recent = idle is not None and idle < protect_minutes
        owned_open.append({
            "taskId": journal.get("taskId"),
            "idleMinutes": idle,
            "stale": bool(idle is not None and idle > STALE_HOURS * 60),
            "protected": bool(recent or active_lease),
            "protectReason": "+".join(
                x for x, ok in (("recent", recent), ("active-lease", active_lease)) if ok)
                or None,
            "updatedAtUtc": journal.get("updatedAtUtc"),
            "purpose": str(journal.get("purpose") or "")[:120],
        })
    owned_claims = [{"taskId": c.get("taskId"), "topic": c.get("topic"),
                     "targets": len(c.get("targets") or [])}
                    for c in claims if not c.get("released") and claim_is_owned(c, agent)]
    owned_lease_rows, foreign_lease_count = [], 0
    claim_topics = {c["topic"] for c in owned_claims}
    for topic, lease in leases.items():
        if is_owned_lease(lease, agent, agents) or topic in claim_topics:
            owned_lease_rows.append({
                "topic": topic, "ownerId": lease.get("ownerId"),
                "status": lease.get("status"),
                "expiresAtUtc": lease.get("expiresAtUtc"),
            })
        else:
            foreign_lease_count += 1
    return {
        "now": now, "journals": owned_open, "foreignInProgress": foreign_open,
        "claims": owned_claims, "leases": owned_lease_rows,
        "foreignLeases": foreign_lease_count, "leaseScan": lease_scan,
        "rawClaims": claims, "rawLeases": leases,
    }


def cmd_check(root: Path, args) -> int:
    keep = set(args.keep_task or [])
    state = collect(root, args.agent, args.skip_lease_scan, args.protect_minutes)
    blockers = [j for j in state["journals"]
                if not j["protected"] and j["taskId"] not in keep]
    residue = bool(state["claims"]) or any(
        l.get("status") == "expired" for l in state["leases"])
    switch_required = bool(blockers) or residue
    out = {
        "schemaVersion": SCHEMA, "action": "check", "agent": args.agent,
        "root": str(root),
        "ownedInProgress": state["journals"],
        "ownedInProgressCount": len(state["journals"]),
        "foreignInProgressCount": state["foreignInProgress"],
        "ownedClaims": state["claims"],
        "ownedLeases": state["leases"],
        "foreignLeaseCount": state["foreignLeases"],
        "leaseScan": state["leaseScan"],
        "keepTask": sorted(keep),
        "switchRequired": switch_required,
        "allowedOpen": not switch_required,
        "recommendedNext": "switch" if switch_required else "open",
        "protectMinutes": args.protect_minutes,
        "staleHours": STALE_HOURS,
    }
    print(json.dumps(out, ensure_ascii=True))
    return 7 if switch_required else 0


def save_claim(claim: dict) -> None:
    path = claim["_path"]
    body = {k: v for k, v in claim.items() if not k.startswith("_")}
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(body, ensure_ascii=True, indent=2) + "\n",
                   encoding="utf-8")
    os.replace(tmp, path)


def end_lease(root: Path, topic: str, owner_id: str):
    """ps1 end는 ownerId 불일치 시 exit 3으로 거부 — foreign lease는 물리적으로
    해제되지 않는다. fingerprint는 넘기지 않는다(heartbeat 갱신을 허용)."""
    data, code = run_ps(root, "-Action", "end", "-Topic", str(topic),
                        "-OwnerId", str(owner_id))
    return code == 0, code


def run_journal_close(root: Path, task_id: str, result: str, summary: str):
    cmd = [sys.executable, "-B", str(JOURNAL_PY), "close", "--root", str(root),
           "--task", task_id, "--result", result, "--summary", summary[:MAX_PURPOSE]]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=40)
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, f"journal-close-failed:{error}"
    return proc.returncode == 0, proc.returncode


def router_resolve(root: Path, ask: str):
    if not ROUTER_PY.is_file():
        return {"reResolveRequired": True, "reason": "router-missing"}
    # router argparse: positional text는 옵션보다 먼저 와야 한다
    cmd = [sys.executable, "-B", str(ROUTER_PY), "resolve", ask,
           "--root", str(root)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=40)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"reResolveRequired": True, "reason": f"router-call-failed:{error}"}
    for line in str(proc.stdout or "").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                row = json.loads(line)
                return {"reResolveRequired": False, "router": row}
            except ValueError:
                continue
    return {"reResolveRequired": True, "reason": f"router-json-missing(exit={proc.returncode})"}


def cmd_switch(root: Path, args) -> int:
    keep = set(args.keep_task or [])
    result = "abandoned" if args.abandoned else args.result
    state = collect(root, args.agent, args.skip_lease_scan, args.protect_minutes)
    by_id = {j["taskId"]: j for j in state["journals"]}

    if args.old_task:
        invalid = [t for t in args.old_task if t not in by_id]
        if invalid:
            print(json.dumps({
                "schemaVersion": SCHEMA, "action": "switch", "status": "error",
                "reason": "old-task-not-owned-or-closed", "invalid": invalid,
            }, ensure_ascii=True))
            return 6
        selected = [by_id[t] for t in args.old_task]
    else:
        selected = [j for j in state["journals"] if j["taskId"] not in keep]

    closed, skipped, claims_released, leases_ended, failures = [], [], [], [], []
    for journal in selected:
        task_id = journal["taskId"]
        explicit = bool(args.old_task) or args.include_recent
        if journal["protected"] and not explicit:
            skipped.append({"taskId": task_id, "reason": journal["protectReason"]})
            continue
        # 1) 이 task의 소유 claim/lease 해제 (ps1 end, ownerId 일치 필요)
        for claim in state["rawClaims"]:
            if claim.get("taskId") != task_id or claim.get("released"):
                continue
            if not claim_is_owned(claim, args.agent):
                continue
            topic = claim.get("topic")
            if args.skip_lease_scan or not SESSION_PS1.is_file():
                ok, code = True, "skipped"
            else:
                ok, code = end_lease(root, topic, claim.get("ownerId"))
            if ok:
                claim["released"] = True
                claim["releasedAtUtc"] = utcnow()
                claim["releaseReason"] = "goal-switch"
                save_claim(claim)
                claims_released.append({"taskId": task_id, "topic": topic})
            else:
                failures.append({"taskId": task_id, "stage": "lease-end",
                                 "topic": topic, "exitCode": code})
        # 2) claim이 없어도 ownerId==taskId로 직결된 소유 lease 해제
        released_topics = {c["topic"] for c in claims_released}
        for topic, lease in state["rawLeases"].items():
            if str(lease.get("ownerId") or "") != task_id:
                continue
            if topic in released_topics or lease.get("status") != "active":
                continue
            if args.skip_lease_scan or not SESSION_PS1.is_file():
                ok, code = True, "skipped"
            else:
                ok, code = end_lease(root, topic, str(lease.get("ownerId")))
            if ok:
                leases_ended.append({"topic": topic, "ownerId": lease.get("ownerId")})
            else:
                failures.append({"taskId": task_id, "stage": "lease-end",
                                 "topic": topic, "exitCode": code})
        # 3) journal close
        ok, code = run_journal_close(
            root, task_id, result,
            f"goal-switch barrier: {result} by {args.agent}; "
            f"new-purpose='{str(args.new_purpose or '')[:160]}'")
        if ok:
            closed.append({"taskId": task_id, "result": result})
        else:
            failures.append({"taskId": task_id, "stage": "journal-close",
                             "exitCode": code})

    # 4) 닫힌 task와 무관한 '소유' orphan lease: 만료된 것만 end (활성은 보고만)
    closed_ids = {c["taskId"] for c in closed}
    ended_topics = {c["topic"] for c in claims_released} | {l["topic"] for l in leases_ended}
    orphan_reported = []
    for lease in state["leases"]:
        topic = lease.get("topic")
        if topic in ended_topics or str(lease.get("ownerId") or "") in closed_ids:
            continue
        if lease.get("status") == "expired" or args.include_recent:
            if args.skip_lease_scan or not SESSION_PS1.is_file():
                leases_ended.append({"topic": topic, "note": "scan-skipped"})
            else:
                ok, code = end_lease(root, topic, str(lease.get("ownerId")))
                if ok:
                    leases_ended.append({"topic": topic})
                else:
                    failures.append({"stage": "orphan-lease-end",
                                     "topic": topic, "exitCode": code})
        else:
            orphan_reported.append({"topic": topic, "status": lease.get("status"),
                                    "note": "active-unlinked-owned-lease"})

    # 잔여 소유 in_progress 재계산 (보호된 것/keep은 차단 아님)
    remaining = [j for j in state["journals"]
                 if j["taskId"] not in closed_ids and j["taskId"] not in keep]
    unprotected_remaining = [j["taskId"] for j in remaining if not j["protected"]]
    allowed_open = not unprotected_remaining and not failures

    out = {
        "schemaVersion": SCHEMA, "action": "switch", "agent": args.agent,
        "newPurpose": args.new_purpose, "closeResult": result,
        "closed": closed, "skipped": skipped,
        "claimsReleased": claims_released, "leasesEnded": leases_ended,
        "unlinkedOwnedActiveLeases": orphan_reported,
        "failures": failures,
        "foreignUntouched": True,
        "remainingOwnedInProgress": [j["taskId"] for j in remaining],
        "unprotectedRemaining": unprotected_remaining,
        "allowedOpen": allowed_open,
        "recommendedOpenPurpose": args.new_purpose,
        "leaseScan": state["leaseScan"],
    }
    if args.new_purpose:
        out["routerResolve"] = router_resolve(root, args.new_purpose)
    else:
        out["reResolveRequired"] = True
    print(json.dumps(out, ensure_ascii=True))
    if failures:
        return 6
    return 0 if allowed_open else 7


def cmd_reject_complete(args) -> int:
    text = str(args.text or "")
    matched = [name for name, pattern in INSTRUCTIONAL if pattern.search(text)]
    tool_only = bool(TOOL_PREAMBLE.match(text.strip()))
    evidence = [name for name, pattern in EVIDENCE if pattern.search(text)]
    # 증거 기반 완화: 명령어 한 줄(tool preamble)은 항상 reject하지만,
    # 지시 어휘가 섞인 완료 보고는 구체적 검증 증거(exit 0, 테스트 통과 수치,
    # 파일 변경)가 있으면 수용한다. 증거 없는 지시문은 기존대로 reject.
    rejected = tool_only or (bool(matched) and not evidence)
    if tool_only or (matched and not evidence):
        reason = "instructional-not-acceptance"
    elif matched and evidence:
        reason = "instructional-with-evidence"
    else:
        reason = "not-instructional"
    out = {
        "schemaVersion": SCHEMA, "action": "reject-complete",
        "rejected": rejected,
        "reason": reason,
        "matched": matched + (["tool-preamble"] if tool_only else []),
        "evidence": evidence,
        "evidenceAccepted": bool(matched) and bool(evidence) and not rejected,
    }
    if getattr(args, "task", None) and not _safe_id(args.task):
        rejected = True
        out.update(rejected=True, reason="invalid-task-id", evidenceAccepted=False)
    elif getattr(args, "task", None):
        from checkpoint_doctor import check_continuity
        state_path = Path(args.root) / BASE / args.task / "state.md"
        continuity = check_continuity(state_path, expected_task=args.task,
            latest_ref=args.latest_instruction_ref, expected_revision=args.expected_revision,
            complete=True, environment=args.environment)
        out["continuity"] = continuity
        if not continuity["allowed"]:
            rejected = True
            out.update(rejected=True, reason="continuity-incomplete", evidenceAccepted=False)
    print(json.dumps(out, ensure_ascii=True))
    return 5 if rejected else 0


def scan_session_goal_conflict(session: Path) -> dict:
    """session/rollout 파일 끝부분(GOAL_SESSION_SCAN_BYTES)에서 P7 흔적을 센다.
    읽기 실패는 치명이 아니라 io-error로만 기록한다 (advisory 도구)."""
    try:
        size = session.stat().st_size
        with session.open("rb") as handle:
            if size > GOAL_SESSION_SCAN_BYTES:
                handle.seek(-GOAL_SESSION_SCAN_BYTES, os.SEEK_END)
            data = handle.read().decode("utf-8", errors="replace")
    except OSError as error:
        return {"status": "io-error", "reason": str(error)[:160]}
    markers = sum(data.count(marker) for marker in GOAL_CONFLICT_MARKERS)
    return {"status": "ok", "file": str(session), "scannedBytes": len(data),
            "goalConflictMarkers": markers}


def cmd_check_goal(root: Path, args) -> int:
    scan = None
    if args.session:
        session = Path(args.session)
        if not session.is_absolute():
            session = root / session
        scan = scan_session_goal_conflict(session)
    p7_seen = bool(args.p7_observed) or bool(
        scan and scan.get("goalConflictMarkers"))
    has_active = bool(args.has_active_goal or args.active_goal_id)
    if p7_seen or has_active:
        action, goal_state = "UPDATE_EXISTING", "active"
        basis = "p7-rejection-observed" if p7_seen else "active-goal-reported"
    elif args.no_active_goal:
        action, goal_state = "CREATE_NEW", "none"
        basis = "no-active-goal-reported"
    else:
        action, goal_state = "UPDATE_EXISTING", "unknown"
        basis = "goal-state-unknown-conservative"
    out = {
        "schemaVersion": SCHEMA, "command": "check-goal",
        "action": action, "goalState": goal_state, "decisionBasis": basis,
        "safeToCreate": action == "CREATE_NEW",
        "conflictingSignals": bool(has_active and args.no_active_goal) or None,
        "activeGoalId": args.active_goal_id,
        "threadId": args.thread_id,
        "objective": args.objective,
        "p7Observed": p7_seen,
        "safeRegistrationProtocol": SAFE_REGISTRATION_PROTOCOL,
        "recoverySnippet": P7_RECOVERY_SNIPPET,
        "nextAction": P7_NEXT_ACTION if action == "UPDATE_EXISTING" else None,
        "remedyCmd": P7_REMEDY_CMD,
        "protocolDoc": "docs/agents-rules/DEMO1-CODEX-GOAL-INTAKE-CONTINUE.md",
    }
    if scan is not None:
        out["sessionScan"] = scan
    if args.agent:
        state = collect(root, args.agent, True, PROTECT_MINUTES)
        out["localOwnedInProgress"] = [j["taskId"] for j in state["journals"]]
        out["localOwnedLeaseCount"] = len(state["leases"])
    print(json.dumps(out, ensure_ascii=True))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)

    def common(p):
        p.add_argument("--root", default=str(DEFAULT_ROOT))
        p.add_argument("--agent", required=True, help="caller agent name (owner match 기준)")
        p.add_argument("--keep-task", action="append", default=[],
                       help="새/현재 목표 taskId — 차단 계산에서 제외")
        p.add_argument("--protect-minutes", type=int, default=PROTECT_MINUTES,
                       help="이 시간(분) 내 갱신된 소유 journal은 보호")
        p.add_argument("--skip-lease-scan", action="store_true",
                       help="PowerShell lease 호출 생략 (테스트/오프라인)")

    p = sub.add_parser("check", help="소유 in_progress/lease 요약 + switch 필요 여부")
    common(p)
    p.set_defaults(func=lambda r, a: cmd_check(r, a))

    p = sub.add_parser("switch", help="소유 목표 superseded/abandoned + lease 해제")
    common(p)
    p.add_argument("--old-task", action="append", default=[],
                   help="닫을 소유 taskId (미지정 시 비보호 전부)")
    p.add_argument("--new-purpose", help="새 목표 한 줄 — router 재resolve 대상")
    p.add_argument("--result", choices=("superseded", "abandoned"),
                   default="superseded")
    p.add_argument("--abandoned", action="store_true",
                   help="명시적 폐기 표시 (--result abandoned와 동일)")
    p.add_argument("--include-recent", action="store_true",
                   help="보호 창/활성 lease 보호를 무시 (운영자 확정 시에만)")
    p.set_defaults(func=lambda r, a: cmd_switch(r, a))

    p = sub.add_parser("check-goal",
                       help="create_goal 전 안전 등록 판정 — UPDATE_EXISTING vs "
                            "CREATE_NEW + P7 회복 스니펫 (advisory, exit 0)")
    p.add_argument("--root", default=str(DEFAULT_ROOT))
    p.add_argument("--agent",
                   help="로컬 소유 journal/lease 요약을 붙일 에이전트명 (선택)")
    p.add_argument("--has-active-goal", action="store_true",
                   help="get_goal이 활성/미완료 목표를 반환했다고 호출자가 보고")
    p.add_argument("--no-active-goal", action="store_true",
                   help="get_goal이 활성 목표 없음을 반환했다고 호출자가 보고")
    p.add_argument("--active-goal-id",
                   help="활성 목표 id — has-active-goal과 동일 효과")
    p.add_argument("--thread-id", help="현재 스레드/세션 id (출력 echo)")
    p.add_argument("--p7-observed", action="store_true",
                   help="이 스레드에서 'unfinished goal' 거절을 이미 관측")
    p.add_argument("--session",
                   help="session/rollout 파일 경로 — 끝부분을 스캔해 P7 흔적 감지")
    p.add_argument("--objective", help="등록하려는 새 목표 한 줄 (출력 echo)")
    p.set_defaults(func=lambda r, a: cmd_check_goal(r, a))

    p = sub.add_parser("reject-complete",
                       help="증거 없는 지시문/도구 서문이면 exit!=0 (instructional-not-acceptance)")
    p.add_argument("--root", default=str(DEFAULT_ROOT))
    p.add_argument("--text", required=True, help="완료로 주장하려는 문장")
    p.add_argument("--task", help="Exact task ID; checks its existing state.md delivery contract")
    p.add_argument("--latest-instruction-ref")
    p.add_argument("--expected-revision", type=int)
    p.add_argument("--environment")
    p.set_defaults(func=lambda r, a: cmd_reject_complete(a))
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        return args.func(root, args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"evidence-io:{error}"}, ensure_ascii=True))
        return 6


if __name__ == "__main__":
    sys.exit(main())
