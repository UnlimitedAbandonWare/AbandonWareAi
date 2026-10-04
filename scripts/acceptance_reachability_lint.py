#!/usr/bin/env python3
"""Acceptance-reachability lint for agent directives (read-only).

Checks a pasted directive/goal-objective file for Acceptance items that are
unreachable inside the directive's own scope — the exact pattern that drove
five Codex goal sessions into a 3-turn blocked loop on 2026-10-03.

Usage:
    python -B scripts/acceptance_reachability_lint.py <directive.txt|md> [...]
    options: --json  --live-leases  --locks-dir PATH  --root PATH

Rules:
    R1 FAIL  acceptance item needs a path outside the allowed-edit list that
             is currently leased by another session (needs --live-leases).
             WARN instead when the path sits in the directive's own
             forbidden list, or when --live-leases is off and the path is
             simply outside the allowed list.
    R2 WARN  acceptance item demands evidence that cannot be measured and the
             directive names no measurement tool/script.
    R3 FAIL  an item told "report PARTIAL / do not build" while completion
             still requires every item PASS and no HOLD-exclusion phrase.
    R4 WARN  same item appears in the HOLD section and as a PASS-required
             acceptance item.
    R5 INFO  directive lacks a "out-of-scope HOLD items are excluded from
             completion" escape clause.
    R6 FAIL  acceptance/forbid lines use "보호 파일/보호 대상/protected"
             but the directive never enumerates the protected set — no
             "변경 금지(보호 대상, ...)" line and no parenthesized list.

Structural checks already covered by scripts/brief_lint.py (ANTI-STOP etc.)
are intentionally NOT repeated here.
"""
import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

# ---------------------------------------------------------------------------
PATH_RE = re.compile(
    r"(?:[A-Za-z]:[\\/][^\"'\s|,)]+|"
    r"(?:main|src|scripts|frontend|docs|configs|\.agents|__patch_drop__|data|agent-prompts|var)"
    r"(?:[\\/][A-Za-z0-9_.@~+\-]+)+)")
READONLY_ZONE_RE = re.compile(
    r"\.codex|attachments|Downloads|%USERPROFILE%|AppData|TEMP|TMP", re.I)
ALL_PASS_RE = re.compile(
    r"전부\s*PASS|모두\s*PASS|모든\s*항목.{0,6}PASS|all\s+(acceptance\s+)?items?.{0,12}PASS|전부\s*통과", re.I)
EXCLUSION_RE = re.compile(
    r"HOLD.{0,24}(제외|빼고|빼)|범위\s*밖.{0,16}제외|완료\s*조건에서.{0,12}(빼|제외)|"
    r"exclud\w+.{0,24}hold|hold.{0,24}exclud\w+", re.I)
PARTIAL_RE = re.compile(
    r"PARTIAL로\s*(보고|처리|표시)|PARTIAL이\s*정답|report.{0,12}PARTIAL|mark.{0,8}PARTIAL", re.I)
FORBID_BUILD_RE = re.compile(
    r"만들지\s*말|새\s*경로.{0,10}(만들지|금지|추가하지)|do not (add|create|build)|forbid", re.I)
UNMEASURABLE_RE = re.compile(
    r"전체 호출 수|모든 .{0,8}호출|숨은 호출|실제\s*Codex.{0,10}선택|자동 선택 증거|"
    r"요청별.{0,8}호출|actual.{0,12}(count|calls|dispatch)|every call|all calls|"
    r"호출 수를|wire.{0,6}count", re.I)
TOOL_REF_RE = re.compile(r"scripts[\\/][A-Za-z0-9_.\-]+\.(py|bat|ps1)|[A-Za-z0-9_.\-]+\.py")
PROTECTED_WORD_RE = re.compile(r"보호\s*(파일|대상|범위)|protected", re.I)
PROTECT_ENUM_HEADER_RE = re.compile(r"변경\s*금지\s*\(\s*보호\s*대상")
PROTECT_ENUM_PAREN_RE = re.compile(
    r"(?:보호\s*(?:파일|대상|범위)|protected)[^()\n]{0,40}"
    r"\([^()\n]*[\\/·,][^()\n]*\)", re.I)
ACCEPT_LINE_RE = re.compile(r"^\s*(?:[-*•|]|\|)?\s*(A\d{1,2})(?![0-9A-Za-z])[\s:：.\-—는은이가을를의]*")
HEADER_KW = {
    "allowed": re.compile(r"수정 허용|허용 목록|편집 허용|작성 가능|쓰기 허용|allowed.{0,10}(edit|paths|scope)", re.I),
    "forbidden": re.compile(r"변경 금지|수정 금지|건드리지|do not (touch|edit|modify)|절대 금지", re.I),
    "acceptance": re.compile(r"^#*\s*(Acceptance|수용 기준|완료 조건|Acceptance\s*\()", re.I),
    "hold": re.compile(r"^#*\s*HOLD\b", re.I),
}
BREAK_RE = re.compile(r"^={3,}\s*$|^#{1,6}\s|^\s*[A-Z가-힣][A-Za-z가-힣0-9 ()·\-]{1,40}\s*$|^\s*[-*]?\s*(ASK_ONCE|HOLD|절대 금지|변경 금지|공통 규칙|작업 단계|보고 형식|Acceptance|수정 허용)")


def redact(text):
    for rx in (re.compile(r"sk-[A-Za-z0-9_\-]{8,}"),
               re.compile(r"vck_[A-Za-z0-9_\-]{8,}"),
               re.compile(r"Bearer\s+[A-Za-z0-9._\-]{6,}", re.I),
               re.compile(r"api[_-]?key\s*[=:]\s*['\"]?[A-Za-z0-9._\-]{8,}", re.I),
               re.compile(r"(?<![\w/.-])[A-Za-z0-9+/]{40,}={0,2}(?![\w/.-])")):
        text = rx.sub("[REDACTED]", text)
    return text


def parse_doc(text):
    """Return dict: allowed[], forbidden[], acceptance[{id,text}], hold_text,
    all_pass, has_exclusion, tool_refs[], lines."""
    allowed, forbidden, acceptance, tool_refs = set(), set(), [], set()
    hold_lines, forbid_lines = [], []
    section = None
    for raw in text.splitlines():
        line = raw.strip()
        is_break = bool(BREAK_RE.match(line))
        if is_break or any(k.search(line) for k in HEADER_KW.values()):
            section = None
            for name, kw in HEADER_KW.items():
                if kw.search(line):
                    section = name
            if re.match(r"^={3,}\s*$", line):
                section = None
            if section in ("allowed", "forbidden"):
                bucket = allowed if section == "allowed" else forbidden
                for m in PATH_RE.finditer(line):
                    bucket.add(m.group(0).replace("\\", "/").lower())
            if section == "forbidden":
                forbid_lines.append(line)
            continue
        for m in TOOL_REF_RE.finditer(line):
            tool_refs.add(m.group(0).replace("\\", "/").lower())
        if section == "allowed":
            for m in PATH_RE.finditer(line):
                allowed.add(m.group(0).replace("\\", "/").lower())
        elif section == "forbidden":
            for m in PATH_RE.finditer(line):
                forbidden.add(m.group(0).replace("\\", "/").lower())
            forbid_lines.append(line)
        elif section == "acceptance":
            am = ACCEPT_LINE_RE.match(line)
            if am:
                acceptance.append({"id": am.group(1), "text": line})
            elif acceptance:
                acceptance[-1]["text"] += " " + line
        elif section == "hold":
            hold_lines.append(line)
        else:
            am = ACCEPT_LINE_RE.match(line)
            if am:
                acceptance.append({"id": am.group(1), "text": line})
    return {
        "allowed": sorted(allowed),
        "forbidden": sorted(forbidden),
        "acceptance": acceptance,
        "hold_text": "\n".join(hold_lines),
        "all_pass": bool(ALL_PASS_RE.search(text)),
        "has_exclusion": bool(EXCLUSION_RE.search(text)),
        "tool_refs": sorted(tool_refs),
        "partial_lines": [l.strip() for l in text.splitlines()
                          if PARTIAL_RE.search(l) or FORBID_BUILD_RE.search(l)],
        "hold_lines": [l.strip() for l in text.splitlines()
                       if re.match(r"^\s*[-*]?\s*HOLD", l, re.I)],
        "forbid_lines": forbid_lines,
        "raw_text": text,
    }


def load_leases(locks_dir):
    held = {}
    d = Path(locks_dir)
    if not d.is_dir():
        return held
    now = datetime.now(timezone.utc)
    for lock in sorted(d.glob("*.lock")):
        jf = lock / "lease.json"
        try:
            j = json.loads(jf.read_text(encoding="utf-8", errors="replace"))
        except (OSError, ValueError):
            continue
        if j.get("status", "active") != "active":
            continue
        try:
            exp = datetime.fromisoformat(str(j.get("expiresAtUtc", "")).replace("Z", "+00:00"))
            if exp.tzinfo is None:
                exp = exp.replace(tzinfo=timezone.utc)
            if exp < now:
                continue
        except ValueError:
            pass
        held[lock.stem] = [str(t).lower().replace("\\", "/")
                           for t in (j.get("targetPaths") or j.get("targets") or [])]
    return held


def _norm(p):
    return p.lower().replace("\\", "/").lstrip("./").rstrip(".,;:)\"'")


def lint_file(path, doc, leases, live_leases):
    findings = []
    allowed = {_norm(p) for p in doc["allowed"]}
    forbidden = {_norm(p) for p in doc["forbidden"]}
    hold_text = doc["hold_text"].lower()

    def finding(rule, sev, item, evidence, fix):
        findings.append({"file": str(path), "rule": rule, "severity": sev,
                         "item": item, "evidence": redact(evidence.strip())[:160],
                         "fix": fix})

    for item in doc["acceptance"]:
        itext = item["text"]
        iid = item["id"]
        # R1: paths the item must touch vs allowed list and live leases
        for m in PATH_RE.finditer(itext):
            tok = _norm(m.group(0))
            if READONLY_ZONE_RE.search(tok):
                continue
            if tok in allowed or any(tok.startswith(a + "/") or a.endswith("/" + tok)
                                     for a in allowed):
                continue
            owner = None
            if live_leases:
                for topic, targets in leases.items():
                    if tok in targets or tok.rsplit("/", 1)[-1] in (
                            t.rsplit("/", 1)[-1] for t in targets):
                        owner = topic
                        break
            if owner:
                finding("R1", "FAIL", iid,
                        "%s needs edit on %s but live lease=%s" % (iid, tok, owner),
                        "%s → 수정 허용 목록에 추가하거나 해당 항목을 HOLD로 이동" % iid)
            elif tok in forbidden:
                finding("R1", "WARN", iid,
                        "%s references forbidden path %s" % (iid, tok),
                        "%s → 수정 허용 목록에 넣거나 항목을 HOLD로 이동" % iid)
            elif not live_leases:
                continue
            else:
                finding("R1", "WARN", iid,
                        "%s path %s outside allowed list (no live lease)" % (iid, tok),
                        "%s → 수정 허용 목록에 추가 또는 항목을 HOLD로 이동" % iid)
        # R2: unmeasurable acceptance phrasing with no measurement tool
        if UNMEASURABLE_RE.search(itext) and not doc["tool_refs"]:
            finding("R2", "WARN", iid,
                    "%s needs unmeasurable evidence: %s" % (iid, itext[:100]),
                    "측정 도구 경로를 지시서에 추가하거나 항목을 NOT_RUN 허용으로 완화")
        # R3: told to report PARTIAL / not build, but completion wants all PASS
        if (PARTIAL_RE.search(itext) or FORBID_BUILD_RE.search(itext)) \
                and doc["all_pass"] and not doc["has_exclusion"]:
            finding("R3", "FAIL", iid,
                    "%s: report PARTIAL/not-build but completion=all PASS, "
                    "no HOLD exclusion" % iid,
                    "%s → HOLD-EXT로 이동, 재개 조건을 HOLD 섹션에 명시" % iid)
        # R4: item also parked in HOLD section
        if iid.lower() in hold_text or (
                len(itext) > 24 and itext[:24].lower() in hold_text):
            finding("R4", "WARN", iid,
                    "%s appears both in HOLD section and as PASS-required" % iid,
                    "HOLD 섹션의 %s을 Acceptance에서 제외 표시" % iid)

    # doc-level R3 fallback: PARTIAL-report instruction lives outside the
    # acceptance item lines (e.g. "A3는 PARTIAL로 보고하라" in the body)
    if doc["all_pass"] and not doc["has_exclusion"] and \
            not any(f["rule"] == "R3" for f in findings):
        aid_re = re.compile(r"\bA\d{1,2}\b")
        hold_set = set(doc["hold_lines"])
        strong = [l for l in doc["partial_lines"]
                  if PARTIAL_RE.search(l) or aid_re.search(l)]
        weak = [l for l in doc["partial_lines"] if l in hold_set]
        if strong:
            finding("R3", "FAIL", "-",
                    "directive body orders PARTIAL/not-build on an item while "
                    "completion requires all PASS: %s" % strong[0][:90],
                    "해당 항목 → HOLD-EXT로 이동, 재개 조건 명시")
        elif weak:
            finding("R3", "WARN", "-",
                    "HOLD item has a not-build order but completion=all PASS, "
                    "no exclusion: %s" % weak[0][:80],
                    "HOLD 항목 제외 문구 추가")
    # R6: protected-scope words without an enumerated protected set
    r6_hits = [i for i in doc["acceptance"]
               if PROTECTED_WORD_RE.search(i["text"])]
    r6_forbid = [l for l in doc["forbid_lines"]
                 if PROTECTED_WORD_RE.search(l)]
    if (r6_hits or r6_forbid) and not (
            PROTECT_ENUM_HEADER_RE.search(doc["raw_text"])
            or PROTECT_ENUM_PAREN_RE.search(doc["raw_text"])):
        iid = r6_hits[0]["id"] if r6_hits else "-"
        ev = (r6_hits[0]["text"] if r6_hits else r6_forbid[0])[:90]
        finding("R6", "FAIL", iid,
                "protected-scope word but protected set not enumerated: %s"
                % ev,
                "'변경 금지(보호 대상, ...)' 줄이나 Acceptance 괄호에 보호 대상을 열거")
    if not doc["has_exclusion"]:
        finding("R5", "INFO", "-",
                "no 'out-of-scope HOLD items excluded from completion' clause",
                "'범위 밖 HOLD 항목은 완료 조건에서 제외' 한 줄 추가")
    return findings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+", help="directive files (txt/md)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    ap.add_argument("--live-leases", action="store_true",
                    help="check acceptance paths against current source-edit locks")
    ap.add_argument("--locks-dir", default="__patch_drop__/source-edit-locks")
    ap.add_argument("--root", default=".")
    args = ap.parse_args(argv)

    leases = load_leases(Path(args.root) / args.locks_dir) if args.live_leases else {}
    all_findings = []
    for f in args.files:
        p = Path(f)
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            all_findings.append({"file": str(p), "rule": "R0", "severity": "FAIL",
                                 "item": "-", "evidence": "unreadable: %s" % e,
                                 "fix": "check path"})
            continue
        doc = parse_doc(text)
        all_findings.extend(lint_file(str(p), doc, leases, args.live_leases))

    fails = [f for f in all_findings if f["severity"] == "FAIL"]
    if args.as_json:
        print(json.dumps({"files": args.files, "live_leases": args.live_leases,
                          "findings": all_findings,
                          "counts": {"FAIL": len(fails),
                                     "WARN": sum(1 for f in all_findings
                                                 if f["severity"] == "WARN"),
                                     "INFO": sum(1 for f in all_findings
                                                 if f["severity"] == "INFO")}},
                         ensure_ascii=False, indent=2))
    else:
        print("== acceptance_reachability_lint ==")
        for f in all_findings:
            print("%s %-4s %s %s | %s\n    fix: %s" % (
                f["severity"], f["rule"], f["item"],
                Path(f["file"]).name[:40], f["evidence"], f["fix"]))
        print("files=%d FAIL=%d WARN=%d INFO=%d" % (
            len(args.files), len(fails),
            sum(1 for f in all_findings if f["severity"] == "WARN"),
            sum(1 for f in all_findings if f["severity"] == "INFO")))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
