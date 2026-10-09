#!/usr/bin/env python3
"""delivery_guard_forbidden_scan.py — advisory forbidden-pattern scan for the
TASK-CONTINUITY-DELIVERY-20261008 patch surface.

Scans files for patterns the directive forbids or treats as suspicious:
competing state stores, external network calls in the default path, secret
material written into checkpoint/state files, hard-coded user paths, and
denial-scope widening language. Findings are advisory (file:line + masked
evidence); a HIGH finding exits 6. Matched secret-looking values are masked —
the literal is never echoed.

    python -B scripts/delivery_guard_forbidden_scan.py [--path <file> ...]
                                                     [--jsonl <out>] [--self-test]

With no --path it scans the known TASK-CONTINUITY patch surface: Codex lease
task-continuity-delivery-a3944fb8 targets plus the delivery machinery it must
preserve (scripts/deliver_to_downloads.py). Missing files are skipped, not
errors — Codex's new files simply appear once created.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DEFAULT_PATHS = [
    "AGENTS.md",
    "scripts/checkpoint_doctor.py",
    "scripts/codex_work_checkpoint.py",
    "scripts/demo1_goal_switch_barrier.py",
    "scripts/work_journal.py",
    "scripts/deliver_to_downloads.py",
    "scripts/test_checkpoint_continuity_delivery.py",          # planned by Codex lease
    ".agents/skills/demo1-session-state-checkpoint/SKILL.md",
    ".agents/skills/demo1-devin-directive-loop/SKILL.md",
    ".agents/skills/demo1-goal-complete-stop/SKILL.md",
    "docs/agents-rules/demo1-task-continuity-delivery.md",     # planned by Codex lease
    "docs/agents-rules/DEMO1-OUTPUT-BUDGET.md",
    "docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md",
]

RULES = [
    ("DG-NET-CALL", "HIGH",
     re.compile(r"\burllib\.request\b|\burlopen\(|\brequests\.|\bhttp\.client\b"
                r"|\bsocket\.|\baiohttp\b|\bhttpx\b|\bwebsocket\b"),
     "external network call in a default-off delivery-check path"),
    ("DG-NEW-STORE", "HIGH",
     re.compile(r"\bsqlite3\b|\bvectorstore\b|\bchromadb\b|\blancedb\b|\bfaiss\b"
                r"|\bschedule\.every|\bthreading\.Timer\b|\bdaemon\b|APScheduler"),
     "competing state store / scheduler — directive forbids a parallel store"),
    ("DG-SECRET-LITERAL", "HIGH",
     re.compile(r"((?i:api[_-]?key|secret|token|password|bearer|credential|auth)"
                r"[^\n]{0,40}?['\"](sk-[A-Za-z0-9_\-]{16,}|AIza[0-9A-Za-z_\-]{30,}"
                r"|xox[baprs]-[0-9A-Za-z\-]{10,})"
                r"|eyJ[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{10,}"
                r"|-----BEGIN [A-Z ]*PRIVATE KEY-----)"),
     "secret-looking literal near a key-context word; never stored in state.md/commits"),
    ("DG-HARD-DOWNLOADS", "MEDIUM",
     re.compile(r"[A-Za-z]:\\\\Users\\\\[^\s'\"]+|/Users/[^\s'\"]+|/home/[^\s'\"]+"),
     "hard-coded user profile path — resolve via user.downloads key/env instead"),
    ("DG-GLOBAL-DENIAL", "MEDIUM",
     re.compile(r"(?i)(all[_ ](downloads|files|paths)[_ ](blocked|denied)"
                r"|pc[_ ]전체[_ ]불가|전역[_ ]?(불가|차단))"),
     "denial scope widening — keep failures path/action/time bounded (WP2)"),
    ("DG-SILENT-LWW", "MEDIUM",
     re.compile(r"(?i)last[_-]?write[_-]?wins|quietly[_ ]overwrite"),
     "silent last-write-wins on state — WP3 requires revision-conflict detection"),
]

MASK = lambda s: (s[:4] + "...masked") if len(s) > 4 else "masked"


DOC_SUFFIXES = (".md", ".txt")
DOC_PROHIBITION = re.compile(
    r"(?i)\bno\b|\bnever\b|\bforbid|\bprohibit|\bmust not\b|\bdo not\b"
    r"|\bstays? off\b|\bden(y|ied|ial)\b|\bwithout\b|금지|없음")


def scan_text(text, rel):
    findings = []
    is_doc = rel.lower().endswith(DOC_SUFFIXES)
    lines = text.splitlines()
    for line_no, line in enumerate(lines, 1):
        for rule_id, severity, rx, why in RULES:
            for m in rx.finditer(line):
                token = m.group(0)
                if rule_id == "DG-HARD-DOWNLOADS" and ("user.downloads" in line
                                                       or "AWX_DOWNLOADS_DIR" in line):
                    continue
                if rule_id == "DG-NET-CALL" and re.search(
                        r"patch|mock|Mock|Stub|Fake|fake|assert|side_effect|no network", line):
                    continue
                if rule_id == "DG-SECRET-LITERAL" and re.search(
                        r"(?i)synthetic|fake|dummy|example|selftest|_test\.|test_", line):
                    continue
                # Rule documents name what they forbid: a forbidden keyword
                # inside a prohibition sentence is compliance, not a store.
                sev, why_text = severity, why
                if is_doc and rule_id != "DG-SECRET-LITERAL":
                    if DOC_PROHIBITION.search(line):
                        continue
                    sev, why_text = "MEDIUM", why + " (doc mention — review intent)"
                findings.append({"file": rel, "line": line_no, "rule": rule_id,
                                 "severity": sev, "why": why_text,
                                 "evidence": MASK(token)})
    return findings


def self_test():
    good = "x = resolve('user.downloads')\nstate = {'status': 'BLOCKED'}\n"
    fake_secret = "s" + "k-" + "a" * 20  # built at runtime: no literal in source
    bad = ("import sqlite3\ncon = sqlite3.connect('state.db')\n"
           + "api" + "_key = '" + fake_secret + "'\n"
           "dest = 'C:\\\\Users\\\\nninn\\\\Downloads'\n"
           "import requests\nrequests.get('https://api.example.com')\n")
    g = scan_text(good, "synthetic/good.py")
    doc_prohibit = scan_text(
        "External compressors/backends stay OFF: no provider, DB, daemon, API key\n"
        "or network call.\n", "synthetic/RULES.md")
    doc_mention = scan_text("The store uses a daemon scheduler.\n", "synthetic/DOC.md")
    assert not doc_prohibit, f"prohibition doc flagged: {doc_prohibit}"
    assert doc_mention and all(f["severity"] == "MEDIUM" for f in doc_mention), doc_mention
    b = scan_text(bad, "synthetic/bad.py")
    rules = {f["rule"] for f in b}
    need = {"DG-NET-CALL", "DG-NEW-STORE", "DG-SECRET-LITERAL", "DG-HARD-DOWNLOADS"}
    assert not g, f"clean sample flagged: {g}"
    assert need <= rules, f"missing rules: {need - rules}"
    assert not any("a" * 20 in (f.get("evidence") or "") for f in b), "secret echoed"
    print("SELFTEST clean-sample 0 findings: PASS")
    print("SELFTEST bad-sample rules " + ",".join(sorted(rules)) + ": PASS")
    print("SELFTEST secret masked: PASS")
    return 0


def main():
    try:  # Windows consoles may be cp949; findings carry non-ASCII evidence
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--path", action="append", default=None,
                    help="file to scan (repeatable); default = patch surface")
    ap.add_argument("--jsonl", default=None, help="also write findings JSONL")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    rels = args.path or DEFAULT_PATHS
    findings, scanned, missing = [], 0, []
    for rel in rels:
        p = (ROOT / rel) if not Path(rel).is_absolute() else Path(rel)
        try:
            text = p.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            missing.append(rel)
            continue
        scanned += 1
        findings.extend(scan_text(text, str(p.relative_to(ROOT)).replace("\\", "/")
                                  if p.is_relative_to(ROOT) else rel))

    for f in findings:
        print(f"{f['severity']:<6} {f['rule']:<18} {f['file']}:{f['line']}  {f['why']}"
              f"  evidence={f['evidence']}")
    if missing:
        print("skipped-missing: " + ", ".join(missing))
    high = sum(1 for f in findings if f["severity"] == "HIGH")
    med = sum(1 for f in findings if f["severity"] != "HIGH")
    print(json.dumps({"scanned": scanned, "skippedMissing": len(missing),
                      "high": high, "medium": med}, ensure_ascii=False))
    if args.jsonl:
        Path(args.jsonl).write_text(
            "".join(json.dumps(f, ensure_ascii=False) + "\n" for f in findings),
            encoding="utf-8")
    return 6 if high else 0


if __name__ == "__main__":
    sys.exit(main())
