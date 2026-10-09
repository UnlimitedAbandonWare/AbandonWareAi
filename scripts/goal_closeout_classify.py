#!/usr/bin/env python3
"""awx.goal-closeout.v1 - 3-way closeout classification for goal sessions.

Splits a session's remaining/unverified acceptance items into:

  AGENT_BLOCKING  the agent could have done it and did not, or it failed:
                  test FAIL / compile failure / this-session regression /
                  skipped in-scope verification / unresolved patch work.
  USER_ONLY       only the human user can produce the evidence: wearing the
                  lens, real Fold6 mic/camera/ASR, physical device checks,
                  user-account login.
  EXTERNAL        unrelated to this session: another session's live lease,
                  pre-existing failures, PROTO_OPEN / DEFERRED_SECURITY policy,
                  launcher environment failure (project-settings-load-failed),
                  diagnostic-URL 401/403, server-down not caused by this work.

Verdict rule:
  AGENT_BLOCKING == 0            -> 'complete' (report first line:
                                  "DONE . USER_CONFIRM N left" + lists)
  AGENT_BLOCKING > 0, some PASS  -> 'partial'
  AGENT_BLOCKING > 0, no PASS    -> 'blocked'

A NOT_RUN/DEFERRED/HOLD item is never promoted to PASS - classification only
changes *who/what* still owes evidence, never its observed status. Repeating
the same unverifiable check for 3 consecutive goal turns must close through
this classifier instead of a fourth audit.

Usage:
  python -B scripts/goal_closeout_classify.py --report <md|txt>
  python -B scripts/goal_closeout_classify.py --rollout <session.jsonl>
  python -B scripts/goal_closeout_classify.py --items <items.json>
  ... --json   single-line JSON (default: human lines)
Exit 0 on successful classification; 1 on input/usage error.
"""
import argparse
import io
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.goal-closeout.v1"

# --- status detection ------------------------------------------------------
_PASS_RE = re.compile(r"통과|\bpass(?:ed|es)?\b|완료로 닫|verified|HTTP ?[12]\d\d\b", re.I)
_OPEN_RE = re.compile(
    r"NOT_RUN|NOT_OBSERVED|DEFERRED|HOLD|blocked|PARTIAL|skipped|미검증|미확인|"
    r"미관측|못 ?했|못함|받지 ?못|확보하지 ?못|확인하지 ?못|관찰하지 ?못|없음|거절|\bFAIL(?:ED|URE)?\b|"
    r"실패|재시도 ?0|남았|남은|unresolved|unproven",
    re.I,
)

# --- cause classification ---------------------------------------------------
# USER_ONLY: evidence only the human operator can produce.
_USER_RE = re.compile(
    r"안경|렌즈|착용|실기기|실제 기기|실제 Fold|Fold6|폴드|마이크|카메라|\bASR\b|"
    r"발화|물리|on.?glasses|사용자 ?(계정|로그인|확인)|관리자 로그인|계정 로그인|"
    r"실제 착용|실제 디바이스|실기기",
    re.I,
)
# EXTERNAL: failure/block whose cause lives outside this session's scope.
_EXTERNAL_RE = re.compile(
    r"lease|다른 세션|다른 실행자|다른.{0,12}채팅|기존 실패|기존 JS|pre.?existing|"
    r"PROTO_OPEN|DEFERRED_SECURITY|auth.?blocked|403|401|project-settings-load-failed|"
    r"런처|launcher|리스너.{0,6}없|수신 프로세스 없|서버.{0,4}(다운|없음|응답|교체)|"
    r"공유 ?서버|공용 ?서버|1818\d|LISTEN|응답.{0,6}못|"
    r"cloudflare|git.?operation|외부 API|429|키 교체|exit ?6|"
    r"범위 밖|수정 범위 밖|범위를 유지|이 세션과 무관|GLM|타임아웃|세션 불가|SESSION_UNAVAILABLE",
    re.I,
)
# AGENT_BLOCKING: hard failure signal inside this session's own scope.
_AGENT_FAIL_RE = re.compile(
    r"실패|\bFAIL(?:ED|URE)?\b|빌드 실패|회귀.{0,4}실패|regression.{0,4}fail|미해결|"
    r"unresolved|revert|rollback|적용 실패|패치 실패",
    re.I,
)
# Scope-escape marker: the failure is explicitly outside this session's changes.
_SCOPE_OUT_RE = re.compile(r"범위 밖|수정 범위 밖|이 세션과 무관|다른 세션|다른 채팅|기존|pre.?existing|외부|레거시 그대로", re.I)

_TABLE_ROW_RE = re.compile(r"^\s*\|(?P<cell>.+?)\|(?P<rest>.+?)\|?\s*$")

# "X 없음" where X is a failure noun = verified absence, not an open item.
_ABSENCE_OK_RE = re.compile(
    r"(?i)(HOLD|FAIL(?:ED|URE)?|backend_unavailable|오류|에러|미해결|잠금|"
    r"exception|skipped|DEFERRED|4\d\d|5\d\d)[`\"')\]]*\s*없음|"
    r"(HOLD|backend_unavailable|FAIL(?:ED|URE)?)(?:이|가)?\s*관측되지 ?않")
# "실패하면/실패 시" is a conditional instruction, not an observed failure.
_CONDITIONAL_FAIL_RE = re.compile(r"실패 ?(하면|시\b|시에|한다면)|(?:if|when)\s+\S+\s+fails?", re.I)
# A check whose fixture is wrong/invalid input and whose observed result is the
# expected rejection is a PASS (e.g. "잘못된 계정 로그인 | 302, 로그인 실패 관측").
_NEGATIVE_TEST_RE = re.compile(r"잘못된|무효|invalid|wrong.?|비정상|허용되지", re.I)
_NEGATIVE_PASS_RE = re.compile(
    r"(차단|거절|실패|reject|denied)\s*(관측|확인|됨)|\b(302|401|403)\b|redirect|리다이렉트", re.I)


def _preprocess(text):
    return _CONDITIONAL_FAIL_RE.sub(" ", _ABSENCE_OK_RE.sub(" ", _norm(text)))


def _norm(text):
    return re.sub(r"\s+", " ", str(text or "")).strip()


_STATUS_SEVERITY = [
    ("NOT_RUN", re.compile(r"NOT_RUN|미검증|미확인", re.I)),
    ("NOT_OBSERVED", re.compile(r"NOT_OBSERVED|미관측", re.I)),
    ("DEFERRED", re.compile(r"DEFERRED", re.I)),
    ("HOLD", re.compile(r"\bHOLD\b|보류", re.I)),
    ("FAIL", re.compile(r"실패|FAIL(?:ED|URE)?|거절", re.I)),
    ("BLOCKED", re.compile(r"blocked|PARTIAL", re.I)),
    ("SKIPPED", re.compile(r"skipped", re.I)),
    ("PASS", re.compile(r"통과|\bPASS(?:ED)?\b|완료", re.I)),
]


def _status_token(joined):
    for label, rx in _STATUS_SEVERITY:
        if rx.search(joined):
            return label
    return "OPEN"


def classify_item(text, status_hint=""):
    """Return (klass, status, reason) for one open/closed item string.

    A fail signal beats a pass token ("24/27 통과 — 3개 실패" is not PASS).
    A fail attributed to an out-of-scope/external cause lands EXTERNAL;
    unknown open items stay AGENT_BLOCKING (never silently promoted)."""
    joined = _preprocess(_norm(text) + " | " + _norm(status_hint))
    status = _status_token(joined)
    has_fail = bool(_AGENT_FAIL_RE.search(joined))
    # negative check: wrong/invalid input was correctly rejected -> PASS
    if _NEGATIVE_TEST_RE.search(joined) and _NEGATIVE_PASS_RE.search(joined):
        return ("PASS", "PASS", "negative-check-verified")
    if _PASS_RE.search(joined) and not has_fail and not _OPEN_RE.search(joined):
        return ("PASS", "PASS", "verified")
    if has_fail:
        if _SCOPE_OUT_RE.search(joined) or _EXTERNAL_RE.search(joined):
            return ("EXTERNAL", status, "failure-outside-session-scope")
        return ("AGENT_BLOCKING", status, "failure-in-session-scope")
    if _USER_RE.search(joined):
        return ("USER_ONLY", status, "user-only-evidence")
    if _EXTERNAL_RE.search(joined):
        return ("EXTERNAL", status, "outside-session-control")
    return ("AGENT_BLOCKING", status, "unclassified-open-item")


def extract_items(text):
    """Pull candidate closeout items from report text: md table rows and
    bullet/line items carrying a status marker."""
    items = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        tm = _TABLE_ROW_RE.match(line)
        if tm and not re.match(r"^[-:|\s]+$", tm.group("cell")):
            cell = _norm(tm.group("cell")).strip("* ")
            rest = _norm(tm.group("rest")).strip()
            if re.match(r"^(항목|item|check|name|-+)$", cell, re.I):
                continue
            probe = _preprocess(rest)
            if _OPEN_RE.search(probe) or _PASS_RE.search(probe) or _EXTERNAL_RE.search(probe):
                items.append({"text": cell, "detail": rest})
                continue
        probe = _preprocess(line)
        if _OPEN_RE.search(probe) or _PASS_RE.search(probe) or _EXTERNAL_RE.search(probe):
            items.append({"text": line.lstrip("-*#| ").strip()})
    return items


def last_agent_message(rollout_path):
    last = ""
    with io.open(rollout_path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                obj = json.loads(line)
            except Exception:
                continue
            pl = obj.get("payload") or {}
            pt = pl.get("type") or obj.get("type") or ""
            if pt == "agent_message":
                msg = pl.get("message") or ""
                if isinstance(msg, str) and len(msg) > len(last):
                    last = msg
            elif pt == "message" and pl.get("role") == "assistant":
                c = pl.get("content")
                txt = ""
                if isinstance(c, list):
                    txt = " ".join(str(x.get("text", "")) for x in c if isinstance(x, dict))
                elif isinstance(c, str):
                    txt = c
                if len(txt) > len(last):
                    last = txt
    return last


def classify_text(text):
    items = []
    for it in extract_items(text):
        klass, status, reason = classify_item(it["text"], it.get("detail", ""))
        items.append({"text": it["text"], "status": status, "class": klass, "reason": reason})
    counts = {"PASS": 0, "AGENT_BLOCKING": 0, "USER_ONLY": 0, "EXTERNAL": 0}
    for it in items:
        counts[it["class"]] += 1
    if counts["AGENT_BLOCKING"] == 0:
        verdict = "complete"
    elif counts["PASS"] > 0:
        verdict = "partial"
    else:
        verdict = "blocked"
    user = [i["text"] for i in items if i["class"] == "USER_ONLY"]
    ext = [i["text"] for i in items if i["class"] == "EXTERNAL"]
    headline = "DONE · 사용자 확인 %d개 남음" % len(user) if verdict == "complete" else (
        "PARTIAL · 범위 안 미해결 %d개" % counts["AGENT_BLOCKING"] if verdict == "partial"
        else "BLOCKED · 범위 안 미해결 %d개" % counts["AGENT_BLOCKING"])
    return {
        "schema": SCHEMA,
        "verdict": verdict,
        "headline": headline,
        "counts": counts,
        "userOnly": user,
        "external": ext,
        "agentBlocking": [i["text"] for i in items if i["class"] == "AGENT_BLOCKING"],
        "items": items,
        "note": "classification changes who owes evidence, never an item's observed status",
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--report", help="report/markdown text file (utf-8)")
    src.add_argument("--rollout", help="Codex rollout jsonl; uses last agent message")
    src.add_argument("--items", help="JSON list of {text,status} or plain strings")
    ap.add_argument("--json", action="store_true", help="single-line JSON output")
    args = ap.parse_args()
    try:
        if args.report:
            text = Path(args.report).read_text(encoding="utf-8-sig", errors="replace")
        elif args.rollout:
            text = last_agent_message(args.rollout)
            if not text:
                print(json.dumps({"schema": SCHEMA, "error": "no-agent-message"}))
                return 1
        else:
            raw = json.loads(Path(args.items).read_text(encoding="utf-8-sig"))
            items = []
            for it in raw:
                if isinstance(it, dict):
                    items.append({"text": str(it.get("text", "")), "detail": str(it.get("status", ""))})
                else:
                    items.append({"text": str(it), "detail": ""})
            res = classify_text("")
            res["items"] = []
            res["counts"] = {"PASS": 0, "AGENT_BLOCKING": 0, "USER_ONLY": 0, "EXTERNAL": 0}
            for it in items:
                klass, status, reason = classify_item(it["text"], it["detail"])
                res["items"].append({"text": it["text"], "status": status, "class": klass, "reason": reason})
                res["counts"][klass] += 1
            if res["counts"]["AGENT_BLOCKING"] == 0:
                res["verdict"] = "complete"
            elif res["counts"]["PASS"] > 0:
                res["verdict"] = "partial"
            else:
                res["verdict"] = "blocked"
            res["userOnly"] = [i["text"] for i in res["items"] if i["class"] == "USER_ONLY"]
            res["external"] = [i["text"] for i in res["items"] if i["class"] == "EXTERNAL"]
            res["agentBlocking"] = [i["text"] for i in res["items"] if i["class"] == "AGENT_BLOCKING"]
            res["headline"] = "DONE · 사용자 확인 %d개 남음" % len(res["userOnly"]) if res["verdict"] == "complete" else (
                "PARTIAL · 범위 안 미해결 %d개" % res["counts"]["AGENT_BLOCKING"] if res["verdict"] == "partial"
                else "BLOCKED · 범위 안 미해결 %d개" % res["counts"]["AGENT_BLOCKING"])
            out = res
            print(json.dumps(out, ensure_ascii=False) if args.json else _human(out))
            return 0
    except (OSError, ValueError) as exc:
        print(json.dumps({"schema": SCHEMA, "error": type(exc).__name__}))
        return 1
    out = classify_text(text)
    if args.json:
        print(json.dumps(out, ensure_ascii=False))
    else:
        print(_human(out))
    return 0


def _human(res):
    lines = [res["headline"], "verdict=%s counts=%s" % (res["verdict"], json.dumps(res["counts"], ensure_ascii=False))]
    for it in res["items"]:
        lines.append("- [%s|%s] %s (%s)" % (it["class"], it["status"], it["text"][:120], it["reason"]))
    return "\n".join(lines)


if __name__ == "__main__":
    sys.exit(main())
