#!/usr/bin/env python3
"""Codex goal-session blocked-loop triage (read-only).

Reads Codex rollout *.jsonl session logs plus any sibling/ledger
blocked-audit*.{json,md} files, classifies why a goal session hit the
"same blocker 3 turns in a row -> blocked" loop, and prints one Korean
paste-ready line per session telling the user (or the next Codex turn)
whether the goal can resume now or needs a scope/directive change.

Usage:
    python -B scripts/goal_block_triage.py --rollout <file.jsonl> [--rollout ...]
    python -B scripts/goal_block_triage.py --date 2026-10-03 [--sessions-dir PATH]
    python -B scripts/goal_block_triage.py --ledger <agent-handoff-dir> [--ledger ...]
    options: --json  --locks-dir PATH  --audit-dir PATH  --attachments-root PATH
             --root PATH (repo root; default cwd)

Classification labels (multi):
    LEASE_HELD, UNMEASURABLE_EVIDENCE, CONTRADICTORY_ACCEPTANCE,
    TOOL_LIMIT, PERMISSION, BUDGET, UNKNOWN

Never prints secret values: message text is truncated and run through
redact() before output. .env/.secrets are never opened.
"""
import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
MSG_MAX = 300

SECRET_RES = [
    re.compile(r"sk-[A-Za-z0-9_\-]{8,}"),
    re.compile(r"vck_[A-Za-z0-9_\-]{8,}"),
    re.compile(r"Bearer\s+[A-Za-z0-9._\-]{6,}", re.IGNORECASE),
    re.compile(r"api[_-]?key\s*[=:]\s*['\"]?[A-Za-z0-9._\-]{8,}", re.IGNORECASE),
    re.compile(r"(?<![\w/.-])[A-Za-z0-9+/]{40,}={0,2}(?![\w/.-])"),
]


def redact(text):
    if not text:
        return text
    for rx in SECRET_RES:
        text = rx.sub("[REDACTED]", text)
    return text


def short(text, n=MSG_MAX):
    text = re.sub(r"\s+", " ", (text or "")).strip()
    return text[:n] + ("..." if len(text) > n else "")


# ---------------------------------------------------------------------------
# rollout parsing
# ---------------------------------------------------------------------------
ATTACH_RE = re.compile(
    r"attachments[\\/]([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,})[\\/]goal-objective\.md")
OBJECTIVE_PATH_RE = re.compile(
    r"[A-Za-z]:[\\/][^\"'\s]*?goal-objective\.md")
BOILERPLATE_RE = re.compile(
    r"^(#\s*AGENTS\.md|<recommended_plugins|<environment_context|<user_instructions|##\s*AGENTS)")
PATH_TOKEN_RE = re.compile(
    r"(?:[A-Za-z]:[\\/][^\"'\s]+?|[.\w-]+(?:[\\/][.\w@-]+)+|[.\w-]+\.(?:java|py|md|yaml|yml|js|ts|json|kts|gradle))")


def _iter_events(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except ValueError:
                continue


def _is_subagent_meta(payload):
    if not isinstance(payload, dict):
        return False
    if payload.get("thread_source") == "subagent":
        return True
    if isinstance(payload.get("source"), dict) and payload["source"].get("subagent"):
        return True
    if payload.get("parent_thread_id"):
        return True
    return False


def _ts_kst(ts):
    try:
        dt = datetime.fromisoformat(str(ts).replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(KST).strftime("%Y-%m-%d %H:%M KST")


def parse_rollout(path):
    info = {
        "file": str(path),
        "subagent": False,
        "meta": None,
        "task_complete_count": 0,
        "turn_aborted_count": 0,
        "completes": [],
        "last_ts": None,
        "last_user_ts": None,
        "goal_summary": "",
        "objective_refs": [],
        "text_blob": [],
        "last_event_type": None,
    }
    for ev in _iter_events(path):
        ts = ev.get("timestamp")
        if ts:
            info["last_ts"] = ts
        typ = ev.get("type")
        info["last_event_type"] = typ
        pay = ev.get("payload") or {}
        if not isinstance(pay, dict):
            continue
        if typ == "session_meta":
            info["meta"] = pay
            info["subagent"] = _is_subagent_meta(pay)
            continue
        ptyp = pay.get("type")
        if typ == "event_msg" and ptyp == "task_complete":
            info["task_complete_count"] += 1
            msg = pay.get("last_agent_message") or ""
            info["completes"].append(msg)
            if len(info["completes"]) > 3:
                info["completes"] = info["completes"][-3:]
            info["text_blob"].append(msg)
        elif typ == "event_msg" and ptyp == "turn_aborted":
            info["turn_aborted_count"] += 1
        elif ptyp == "user_message" or (
            typ == "response_item" and pay.get("type") == "message"
            and pay.get("role") == "user"
        ):
            info["last_user_ts"] = ts
            txt = pay.get("message") or ""
            if not txt:
                parts = pay.get("content") or []
                if isinstance(parts, list):
                    txt = " ".join(
                        str(c.get("text", "")) for c in parts
                        if isinstance(c, dict))
            if not info["goal_summary"] and not BOILERPLATE_RE.match(txt.strip()):
                info["goal_summary"] = short(txt, 60)
        # collect goal-objective attachment references from raw event text
        blob = json.dumps(pay, ensure_ascii=False)
        for m in ATTACH_RE.finditer(blob):
            ref = "attachments/%s/goal-objective.md" % m.group(1)
            if ref not in info["objective_refs"]:
                info["objective_refs"].append(ref)
        for m in OBJECTIVE_PATH_RE.finditer(blob):
            p = m.group(0).replace("\\\\", "\\")
            if p not in info["objective_refs"]:
                info["objective_refs"].append(p)
    return info


# ---------------------------------------------------------------------------
# evidence: blocked-audit files + goal-objective docs
# ---------------------------------------------------------------------------
def load_audits(dirs, limit=500):
    audits = []
    seen = 0
    for d in dirs:
        d = Path(d)
        if not d.is_dir():
            continue
        for pat in ("**/blocked-audit*.json", "**/blocked-audit*.md"):
            for f in sorted(d.glob(pat)):
                if seen >= limit:
                    return audits
                seen += 1
                try:
                    text = f.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue
                entry = {"path": str(f), "text": text, "json": None, "uuid": None}
                try:
                    entry["json"] = json.loads(text)
                except ValueError:
                    pass
                m = ATTACH_RE.search(text)
                if m:
                    entry["uuid"] = m.group(1).lower()
                j = entry["json"] or {}
                gof = j.get("goalObjectiveFile") or ""
                m2 = ATTACH_RE.search(gof)
                if m2:
                    entry["uuid"] = m2.group(1).lower()
                audits.append(entry)
    return audits


def objective_uuid(ref):
    m = re.search(r"attachments[\\/]([0-9a-fA-F-]{8,})[\\/]", ref.replace("\\\\", "/"))
    return m.group(1).lower() if m else None


def read_objective_text(ref, attachments_root):
    p = Path(ref)
    candidates = [p]
    uid = objective_uuid(ref)
    if uid:
        candidates.append(Path(attachments_root) / uid / "goal-objective.md")
    for c in candidates:
        try:
            if c.is_file() and c.stat().st_size < 512 * 1024:
                return str(c), c.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
    return None, None


# ---------------------------------------------------------------------------
# classification
# ---------------------------------------------------------------------------
RULES = {
    "CONTRADICTORY_ACCEPTANCE": [
        re.compile(r"PARTIAL로\s*보고|PARTIAL이\s*정답|requires\s+\w+\s+PARTIAL|report\s+\w*\s*PARTIAL", re.I),
        re.compile(r"모순|contradict", re.I),
    ],
    "LEASE_HELD": [
        re.compile(r"lease|예약|reservation|overlapping-target", re.I),
    ],
    "UNMEASURABLE_EVIDENCE": [
        re.compile(r"입증(되지|할 수 없|하지 못| 못)|집계(할 수 없| 불가| 불가능)|카운트.{0,6}(없|누락|부재)|숨은 호출|측정.{0,6}불가|관측.{0,6}(불가|할 수 없)|증거(가|를) .{0,4}없|증거와|선택 증거|자동 선택|wire 카운트|요청별.{0,8}호출 수|전체 호출 수|실제.{0,20}(호출|선택|관측).{0,20}(수|불가|없|부재)|NOT_PROVEN|unmeasurable"),
    ],
    "TOOL_LIMIT": [
        re.compile(r"writer-cap|MAX_WRITERS|도구 한도|tool.?limit|cap 차단|writer 슬롯"),
    ],
    "PERMISSION": [
        re.compile(r"허용 범위 밖|수정 허용.{0,6}밖|변경 금지 목록|out-of-scope.{0,12}(file|path)|forbidden path", re.I),
    ],
    "BUDGET": [
        re.compile(r"재기동.{0,6}상한 소진|호출 상한 소진|한도.{0,4}소진|예산.{0,4}소진|budget.{0,10}exhaust|rate.?limit.{0,10}exceed", re.I),
    ],
}
ALL_PASS_RE = re.compile(r"전부\s*PASS|모두\s*PASS|all\s+(acceptance\s+)?items?.{0,12}PASS|all.{0,4}PASS", re.I)
PARTIAL_REPORT_RE = RULES["CONTRADICTORY_ACCEPTANCE"][0]
ACCEPT_ID_RE = re.compile(r"\bA\d{1,2}\b")


def classify(evidence_text):
    labels = set()
    for label, rules in RULES.items():
        if label == "CONTRADICTORY_ACCEPTANCE":
            if rules[0].search(evidence_text) and ALL_PASS_RE.search(evidence_text):
                labels.add(label)
            elif rules[1].search(evidence_text) and PARTIAL_REPORT_RE.search(evidence_text):
                labels.add(label)
            continue
        for rx in rules:
            if rx.search(evidence_text):
                labels.add(label)
                break
    if not labels:
        labels.add("UNKNOWN")
    return labels


# ---------------------------------------------------------------------------
# live resumption check: leases + tool state
# ---------------------------------------------------------------------------
def load_leases(locks_dir):
    leases = []
    d = Path(locks_dir)
    if not d.is_dir():
        return leases
    for lock in sorted(d.glob("*.lock")):
        jf = lock / "lease.json"
        try:
            j = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
        except (OSError, ValueError):
            continue
        leases.append({
            "topic": lock.stem,
            "status": j.get("status", "active"),
            "expiresAtUtc": j.get("expiresAtUtc", ""),
            "targets": [str(t).lower().replace("\\", "/")
                        for t in (j.get("targetPaths") or j.get("targets") or [])],
        })
    return leases


def _expired(lease):
    try:
        exp = datetime.fromisoformat(str(lease["expiresAtUtc"]).replace("Z", "+00:00"))
    except ValueError:
        return False
    if exp.tzinfo is None:
        exp = exp.replace(tzinfo=timezone.utc)
    return exp < datetime.now(timezone.utc)


def lease_check(evidence_text, leases):
    """Return (still_held:[{topic,expires_kst}], mentioned_paths:[str])."""
    mentioned = set()
    for m in PATH_TOKEN_RE.finditer(evidence_text):
        tok = m.group(0).replace("\\\\", "/").replace("\\", "/").strip("/.")
        if tok.startswith(("http", "C:/Users/nninn/.codex")):
            continue
        low = tok.lower()
        for marker in ("demo-1/src/", "/src/"):
            if marker in low:
                tok = low.split(marker)[-1]
                break
        if ".codex/" in low or "attachments/" in low or "downloads/" in low:
            continue
        if "/" in tok or tok.endswith(
                (".java", ".py", ".md", ".yaml", ".yml", ".js", ".json")):
            mentioned.add(tok.lower())
    held = []
    for lease in leases:
        if lease["status"] != "active" or _expired(lease):
            continue
        if lease["topic"].lower() in evidence_text.lower():
            held.append(lease)
            continue
        for t in lease["targets"]:
            if _target_mentioned(t, mentioned):
                held.append(lease)
                break
    uniq = {l["topic"]: l for l in held}
    out = [{"topic": l["topic"], "expires_kst": _ts_kst(l["expiresAtUtc"])}
           for l in uniq.values()]
    return out, sorted(mentioned)


def _target_mentioned(target, mentioned):
    """Path-level match only — a shared basename alone never counts."""
    tseg = target.split("/")
    for m in mentioned:
        if "/" not in m:
            if target == m:  # e.g. a root-level AGENTS.md target vs "agents.md"
                return True
            continue
        if m == target or m.endswith("/" + target) or target.endswith("/" + m):
            return True
        mseg = m.split("/")
        if len(mseg) >= 2 and len(tseg) >= 2 and mseg[-2:] == tseg[-2:]:
            return True
    return False


def writer_cap_fixed(root):
    f = Path(root) / "scripts" / "coop_verify.py"
    try:
        text = f.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    return "released-writers" in text and "MAX_WRITERS" in text


# ---------------------------------------------------------------------------
# per-session verdict
# ---------------------------------------------------------------------------
def session_status(info, file_mtime=None):
    if info["task_complete_count"] == 0:
        if file_mtime is not None:
            age = datetime.now(timezone.utc).timestamp() - file_mtime
            return "RUNNING" if age < 7200 else "INCOMPLETE"
        return "RUNNING"
    last = info["completes"][-1] if info["completes"] else ""
    if re.search(r"\bblocked\b|차단 조건이 세|세 번의 연속", last, re.I):
        return "BLOCKED_LOOP" if info["task_complete_count"] >= 3 else "BLOCKED"
    return "COMPLETED"


def acceptance_items_near(text):
    ids = ACCEPT_ID_RE.findall(text)
    seen, out = set(), []
    for i in ids:
        if i not in seen:
            seen.add(i)
            out.append(i)
    return out[:4]


def suggest_message(labels, verdict, items):
    itxt = "+".join(items) if items else "해당 항목"
    if "CONTRADICTORY_ACCEPTANCE" in labels:
        return ("「%s은 지시서대로 PARTIAL이 정답이니 완료 조건에서 빼고 "
                "완료로 보고해줘.」" % itxt)
    if verdict == "RESUMABLE_NOW":
        causes = "+".join(sorted(labels - {"UNKNOWN"})) or "원인"
        return ("「막힘 원인 %s이 풀렸어. 같은 감사 반복하지 말고 "
                "남은 항목부터 바로 이어서 해줘.」" % causes)
    if verdict == "STILL_HELD" or "UNMEASURABLE_EVIDENCE" in labels:
        cause = "다른 세션 lease" if verdict == "STILL_HELD" else "측정 불가 증거"
        return ("「%s은 범위 밖(%s)이라 HOLD로 빼고, 범위 안 항목이 "
                "전부 PASS면 완료로 보고해줘.」" % (itxt, cause))
    return ("「막힘 원인을 한 줄로 적고, 범위 안 항목이 전부 PASS면 "
            "완료로 보고해줘.」")


def triage_session(info, audits, leases, coop_fixed, attachments_root, root):
    if info["subagent"]:
        return {"file": info["file"], "skipped": "subagent"}
    evidence_parts = list(info["completes"])
    block_parts = list(info["completes"])
    uuids = {objective_uuid(r) for r in info["objective_refs"]}
    uuids.discard(None)
    for a in audits:
        if a["uuid"] and a["uuid"] in uuids:
            evidence_parts.append(a["text"])
            block_parts.append(a["text"])
    for ref in info["objective_refs"]:
        _p, text = read_objective_text(ref, attachments_root)
        if text:
            evidence_parts.append(text)
    evidence = "\n".join(evidence_parts)
    block_evidence = "\n".join(block_parts)

    status = session_status(info, Path(info["file"]).stat().st_mtime
                            if Path(info["file"]).exists() else None)
    labels = classify(evidence) if status.startswith("BLOCKED") else set()

    held, _mentioned = lease_check(block_evidence, leases)
    tool_note = None
    if "TOOL_LIMIT" in labels:
        tool_note = ("writer-cap released-writers archive present -> fixed"
                     if coop_fixed else "writer-cap archive NOT found")
    if status == "RUNNING":
        verdict = "RUNNING"
    elif not status.startswith("BLOCKED"):
        verdict = status
    elif "CONTRADICTORY_ACCEPTANCE" in labels:
        verdict = "NEEDS_DIRECTIVE_FIX"
    elif held:
        verdict = "STILL_HELD"
    elif "TOOL_LIMIT" in labels and coop_fixed is not True:
        verdict = "BLOCKED_EXTERNAL"
    elif not labels or labels == {"UNKNOWN"}:
        verdict = "UNKNOWN_CAUSE"
    elif labels & {"UNMEASURABLE_EVIDENCE", "PERMISSION", "BUDGET"}:
        verdict = "BLOCKED_EXTERNAL"
    else:
        verdict = "RESUMABLE_NOW"

    items = acceptance_items_near(evidence)
    return {
        "file": info["file"],
        "skipped": None,
        "status": status,
        "time_kst": _ts_kst(info["last_ts"]),
        "goal_summary": redact(info["goal_summary"]),
        "task_complete_count": info["task_complete_count"],
        "turn_aborted_count": info["turn_aborted_count"],
        "labels": sorted(labels),
        "leases_still_held": held,
        "tool_note": tool_note,
        "verdict": verdict,
        "paste_line": (suggest_message(labels, verdict, items)
                       if status.startswith("BLOCKED") else ""),
        "last_message": redact(short(info["completes"][-1]
                                     if info["completes"] else "")),
    }


def audit_row(audit, leases):
    text = audit["text"]
    labels = classify(text)
    j = audit["json"] or {}
    held, _m = lease_check(text, leases)
    items = acceptance_items_near(text)
    verdict = ("NEEDS_DIRECTIVE_FIX" if "CONTRADICTORY_ACCEPTANCE" in labels
               else "STILL_HELD" if held else
               "RESUMABLE_NOW" if not (labels & {"UNMEASURABLE_EVIDENCE"})
               else "BLOCKED_EXTERNAL")
    return {
        "audit": audit["path"],
        "schema": j.get("schema") or j.get("status") or "unknown",
        "labels": sorted(labels),
        "leases_still_held": held,
        "verdict": verdict,
        "paste_line": suggest_message(labels, verdict, items),
        "resume_conditions": j.get("resumeConditions")
            or j.get("requiredExternalChange") or "",
    }


# ---------------------------------------------------------------------------
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rollout", action="append", default=[],
                    help="rollout jsonl file (repeatable)")
    ap.add_argument("--date", help="YYYY-MM-DD -> scan ~/.codex/sessions/Y/M/D")
    ap.add_argument("--sessions-dir", default=str(Path.home() / ".codex" / "sessions"))
    ap.add_argument("--ledger", action="append", default=[],
                    help="agent-handoff dir containing blocked-audit* files")
    ap.add_argument("--audit-dir", default="data/agent-handoff",
                    help="repo dir scanned for blocked-audit* (default data/agent-handoff; '' to skip)")
    ap.add_argument("--locks-dir", default="__patch_drop__/source-edit-locks")
    ap.add_argument("--attachments-root",
                    default=str(Path.home() / ".codex" / "attachments"))
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)

    root = Path(args.root)
    files = [Path(f) for f in args.rollout]
    if args.date:
        y, m, d = args.date.split("-")
        day_dir = Path(args.sessions_dir) / y / m / d
        files.extend(sorted(day_dir.glob("rollout*.jsonl")))

    audit_dirs = list(args.ledger)
    if args.audit_dir:
        audit_dirs.append(root / args.audit_dir)
    for f in files:
        audit_dirs.append(f.parent)  # sibling audits
    audits = load_audits(audit_dirs)
    leases = load_leases(root / args.locks_dir)
    coop_fixed = writer_cap_fixed(root)

    report = {"sessions": [], "audits": [], "skipped_subagent": 0,
              "locks_dir": str(root / args.locks_dir)}
    for f in files:
        if not f.exists():
            report["sessions"].append({"file": str(f), "error": "missing"})
            continue
        info = parse_rollout(f)
        row = triage_session(info, audits, leases, coop_fixed,
                             args.attachments_root, root)
        if row.get("skipped"):
            report["skipped_subagent"] += 1
            continue
        report["sessions"].append(row)
    for ld in args.ledger:
        for a in load_audits([ld]):
            report["audits"].append(audit_row(a, leases))

    if args.as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
        return 0

    print("== goal_block_triage ==")
    print("locks-dir: %s | skipped_subagent: %d" %
          (report["locks_dir"], report["skipped_subagent"]))
    for s in report["sessions"]:
        if "error" in s:
            print("- %s | ERROR %s" % (s["file"], s["error"]))
            continue
        labels = "+".join(s["labels"]) if s["labels"] else "-"
        print("- %s | %s | completes=%d | %s | %s" % (
            Path(s["file"]).name[:26], s["time_kst"], s["task_complete_count"],
            labels, s["verdict"]))
        if s["goal_summary"]:
            print("    goal: %s" % s["goal_summary"])
        if s.get("status", "").startswith("BLOCKED"):
            for h in s["leases_still_held"]:
                print("    lease-held: %s (expires %s)" % (h["topic"], h["expires_kst"]))
        if s["tool_note"]:
            print("    tool: %s" % s["tool_note"])
        if s["paste_line"]:
            print("    paste: %s" % s["paste_line"])
        if s["last_message"]:
            print("    last: %s" % s["last_message"])
    for a in report["audits"]:
        print("- AUDIT %s | %s | %s | %s" % (
            Path(a["audit"]).name, "+".join(a["labels"]), a["verdict"], a["schema"]))
        if a["paste_line"]:
            print("    paste: %s" % a["paste_line"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
