#!/usr/bin/env python3
"""Read-only acceptance checker for Codex R5.1 (oauth-benefit wiring follow-up).

Scope per DEVIN R3 directive (2026-10-01):
  1. Sum tests/failures/errors/skipped over new JUnit XML under the task dir;
     newest ChatGptOAuthRedTeamContractTest XML must have skipped=0.
  2. RoutingBenefitLifecycleTest: the four new WP1 test methods must not inject
     accountRef via ReflectionTestUtils (A1).
  3. ChatGptOAuthRedTeamContractTest: no @Disabled left on the two pinned tests.
  4. Changed-file evidence (*-targets.json, *-changes.json, manifest.json,
     *.diff/*.patch under the task dir) must stay inside the R5.1 scope; new
     *.sql/schema/migration artifacts and chat.js / chat-stream-boundaries
     changes are failures.
  5. FallbackAwareChatModel.java sha256 must equal the R5 recorded postimage
     (F4 is report-only).

Input: --root <project root> [--task <taskId>]. Without --task, the newest
directory under data/agent-handoff/codex-autonomy is used.

Output: JSON {taskId, checks{name:{verdict,detail,evidence[]}}, overall, allPass}
Exit: 0 = every check PASS; 1 = any FAIL or NOT_RUN; 2 = missing input.
Pure stdlib. No network. No writes.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

AUTONOMY = Path("data/agent-handoff/codex-autonomy")
R5_BASELINE_TASK = "oauth-adaptive-r5-1001-9f522366"

NEW_TEST_METHODS = [
    "blankAccountRefUsesCatalogFingerprintAndPersistsExhaustion",
    "exhaustionSurvivesRestartWithDerivedKey",
    "noAccountKeyIsExplicitUnboundNotSilent",
    "explicitAccountRefWinsOverCatalog",
]
REDTEAM_METHODS = [
    "bareJwtNeverSurvivesRedaction",
    "bareRefreshTokenNeverSurvivesRedaction",
]
REDTEAM_SUITE = "ChatGptOAuthRedTeamContractTest"

LIFECYCLE = "src/test/java/com/example/lms/service/RoutingBenefitLifecycleTest.java"
REDTEAM = "src/test/java/ai/abandonware/nova/orch/llm/ChatGptOAuthRedTeamContractTest.java"
FALLBACK = "main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java"
PROTECTED = [
    "main/resources/static/js/chat.js",
    "src/test/js/chat-stream-boundaries.test.cjs",
]

ALLOWED_EXACT = {
    "main/java/com/example/lms/llm/chatgptoauthregistration.java",
    "main/java/com/example/lms/assist/conversateapicueservice.java",
    "main/java/com/example/lms/assist/conversatecueroutingpolicy.java",
    "main/java/com/example/lms/debug/promptmasker.java",
    "src/test/java/com/example/lms/service/routingbenefitlifecycletest.java",
    "src/test/java/ai/abandonware/nova/orch/llm/chatgptoauthredteamcontracttest.java",
    "docs/architecture/adaptive-model-benefit-routing.md",
    "docs/project_status.md",
}
ALLOWED_PREFIXES = ("src/test/", "src/test/java/")  # 관련 테스트 확장 허용 범위
MACHINERY_PREFIXES = ("__patch_drop__/",)  # lease/lock bookkeeping, not product changes
SCHEMA_RE = re.compile(r"\.sql$|(?:schema|migrat|ddl)", re.IGNORECASE)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def norm(p):
    p = str(p).strip().replace("\\", "/")
    for pre in ("./", "a/", "b/"):
        if p.startswith(pre):
            p = p[len(pre):]
    return p.strip("/").lower()


def read_text(path):
    return Path(path).read_text(encoding="utf-8", errors="replace")


def result(verdict, detail, evidence=None):
    return {"verdict": verdict, "detail": detail, "evidence": evidence or []}


def journal_start_epoch(task_dir):
    jf = task_dir / "journal.json"
    if not jf.is_file():
        return None
    try:
        doc = json.loads(read_text(jf))
        raw = doc.get("startedAtUtc") or doc.get("claimedAtUtc") or ""
        return datetime.fromisoformat(raw.replace("Z", "+00:00")).timestamp()
    except Exception:
        return None


def collect_changed_paths(task_dir):
    """Union of ledger evidence paths recorded under the task dir."""
    found = set()
    notes = []

    def harvest(obj):
        if isinstance(obj, dict):
            for k, v in obj.items():
                if k == "path" and isinstance(v, str) and "/" in v:
                    found.add(norm(v))
                else:
                    harvest(v)
        elif isinstance(obj, list):
            for item in obj:
                harvest(item)

    for jf in sorted(task_dir.rglob("*.json")):
        name = jf.name.lower()
        if not (name.endswith("-targets.json") or "changes" in name or name == "manifest.json"):
            continue
        try:
            harvest(json.loads(read_text(jf)))
        except Exception:
            notes.append("unreadable:" + jf.name)
    for df in sorted(list(task_dir.rglob("*.diff")) + list(task_dir.rglob("*.patch"))):
        try:
            text = read_text(df)
        except Exception:
            continue
        for m in re.finditer(r"^\+\+\+ b/(\S+)", text, re.M):
            found.add(norm(m.group(1)))
        for m in re.finditer(r"^diff --git a/\S+ b/(\S+)", text, re.M):
            found.add(norm(m.group(1)))
    return found, notes


def check_junit(task_dir):
    xmls = [p for p in task_dir.rglob("TEST-*.xml") if p.is_file()]
    if not xmls:
        return result("NOT_RUN", "no TEST-*.xml under task dir", [str(task_dir)])
    latest = {}
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    per_suite = {}
    for xf in xmls:
        try:
            root = ET.parse(xf).getroot()
        except Exception:
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for s in suites:
            name = s.get("name") or xf.stem
            mtime = xf.stat().st_mtime
            if name not in latest or mtime > latest[name][0]:
                latest[name] = (mtime, xf, s)
    for name, (mtime, xf, s) in latest.items():
        row = {k: int(s.get(k, "0") or 0) for k in totals}
        per_suite[name] = {**row, "file": xf.name}
        for k in totals:
            totals[k] += row[k]
    rt = per_suite.get(REDTEAM_SUITE) or next(
        (v for k, v in per_suite.items() if REDTEAM_SUITE in k), None)
    evidence = [f"{n}:t={r['tests']},f={r['failures']},e={r['errors']},s={r['skipped']} @{r['file']}"
                for n, r in sorted(per_suite.items())]
    bad = [n for n, r in per_suite.items() if r["failures"] or r["errors"]]
    if rt is None:
        return result("FAIL", f"{REDTEAM_SUITE} XML absent in latest set; totals={totals}", evidence)
    if rt["skipped"] != 0:
        return result("FAIL", f"{REDTEAM_SUITE} skipped={rt['skipped']} (expected 0)", evidence)
    if bad:
        return result("FAIL", f"latest suites with failures/errors: {bad}; totals={totals}", evidence)
    return result("PASS", f"suites={len(per_suite)} totals={totals} redteam_skipped=0", evidence)


def method_span(src, name):
    m = re.search(r"\bvoid\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    i = src.find("{", m.end())
    if i < 0:
        return None
    depth = 0
    for j in range(i, len(src)):
        c = src[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    return src[i:]


def check_no_injection(root):
    f = root / LIFECYCLE
    if not f.is_file():
        return result("FAIL", f"{LIFECYCLE} missing", [LIFECYCLE])
    src = read_text(f)
    missing, injected = [], []
    for name in NEW_TEST_METHODS:
        body = method_span(src, name)
        if body is None:
            missing.append(name)
            continue
        if re.search(r'ReflectionTestUtils\.\w+\([^)]*"accountRef"', body):
            injected.append(name)
    detail = []
    if missing:
        detail.append("missing:" + ",".join(missing))
    if injected:
        detail.append("accountRef injected in:" + ",".join(injected))
    verdict = "PASS" if not missing and not injected else "FAIL"
    if verdict == "PASS":
        detail.append(f"4/4 new tests present, no ReflectionTestUtils accountRef")
    return result(verdict, "; ".join(detail), [LIFECYCLE])


def check_redteam_unpinned(root):
    f = root / REDTEAM
    if not f.is_file():
        return result("FAIL", f"{REDTEAM} missing", [REDTEAM])
    src = read_text(f)
    still, missing = [], []
    for name in REDTEAM_METHODS:
        m = re.search(r"\bvoid\s+" + re.escape(name) + r"\s*\(", src)
        if not m:
            missing.append(name)
            continue
        window = src[max(0, m.start() - 800):m.start()]
        for hit in re.finditer(r"@Disabled\b", window):
            line_start = window.rfind("\n", 0, hit.start()) + 1
            prefix = window[line_start:hit.start()]
            if "//" in prefix or "/*" in prefix:
                continue
            still.append(name)
            break
    detail = []
    if missing:
        detail.append("missing:" + ",".join(missing))
    if still:
        detail.append("@Disabled still on:" + ",".join(still))
    verdict = "PASS" if not missing and not still else "FAIL"
    if verdict == "PASS":
        detail.append("both pinned tests present without @Disabled")
    return result(verdict, "; ".join(detail), [REDTEAM])


def check_scope(root, task_dir):
    changed, notes = collect_changed_paths(task_dir)
    if not changed:
        return result("NOT_RUN", "no changed-path evidence under task dir",
                      [str(task_dir)] + notes)
    task_prefix = norm(str(task_dir.relative_to(root))) + "/"
    out, schema = [], []
    for p in sorted(changed):
        base = p.rsplit("/", 1)[-1]
        if (p in ALLOWED_EXACT or p.startswith(task_prefix)
                or p.startswith("src/test/") or p.startswith(MACHINERY_PREFIXES)):
            continue
        if SCHEMA_RE.search(base):
            schema.append(p)
        else:
            out.append(p)
    protected_hit = [p for p in sorted(changed) if p in PROTECTED]
    verdict = "PASS" if not out and not schema and not protected_hit else "FAIL"
    detail = (f"changed={len(changed)} out_of_scope={out} schema_artifacts={schema} "
              f"protected={protected_hit}")
    evidence = sorted(changed) + notes
    return result(verdict, detail, evidence)


def git_head_sha(root, rel):
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "show", f"HEAD:{rel}"],
            capture_output=True, timeout=30, check=True).stdout
        return hashlib.sha256(out).hexdigest()
    except Exception:
        return None


def check_protected(root, task_dir):
    changed, _ = collect_changed_paths(task_dir)
    started = journal_start_epoch(task_dir)
    detail, evidence, fail = [], [], []
    for rel in PROTECTED:
        p = root / rel
        if not p.is_file():
            evidence.append(f"{rel}: missing-worktree")
            continue
        cur = sha256_file(p)
        head = git_head_sha(root, rel)
        mtime = datetime.fromtimestamp(p.stat().st_mtime, tz=timezone.utc).isoformat()
        in_window = started is not None and p.stat().st_mtime > started
        entry = f"{rel}: sha256={cur[:16]} head={head[:16] if head else 'unreadable'} mtime={mtime}"
        if rel.lower() in changed or rel in changed:
            fail.append(rel)
            entry += " IN-LEDGER"
        if in_window:
            entry += " MTIME-IN-WINDOW"
        if head and head != cur:
            entry += " differs-from-HEAD(pre-existing-or-new)"
        evidence.append(entry)
        detail.append(f"{rel.split('/')[-1]} mtime={mtime}")
    verdict = "FAIL" if fail else "PASS"
    if fail:
        detail.append("ledger evidence contains protected files: " + ",".join(fail))
    return result(verdict, "; ".join(detail) or "protected files not in ledger evidence", evidence)


def check_fallback(root):
    baseline = None
    for name in ("final-owned-changes.json", "owned-changes.json"):
        jf = root / AUTONOMY / R5_BASELINE_TASK / name
        if not jf.is_file():
            continue
        try:
            for row in json.loads(read_text(jf)):
                if isinstance(row, dict) and norm(row.get("path", "")) == FALLBACK.lower():
                    baseline = row.get("sha256")
                    break
        except Exception:
            continue
        if baseline:
            break
    f = root / FALLBACK
    if not baseline:
        return result("NOT_RUN", "no R5 postimage hash for FallbackAwareChatModel",
                      [str(root / AUTONOMY / R5_BASELINE_TASK)])
    if not f.is_file():
        return result("FAIL", f"{FALLBACK} missing in worktree", [FALLBACK])
    cur = sha256_file(f)
    if cur == baseline:
        return result("PASS", f"sha256 unchanged since R5 postimage ({cur[:16]})",
                      [FALLBACK])
    return result("FAIL",
                  f"FallbackAwareChatModel changed after R5: r5={baseline[:16]} now={cur[:16]}",
                  [FALLBACK])


def newest_task(root):
    aut = root / AUTONOMY
    if not aut.is_dir():
        return None
    dirs = [d for d in aut.iterdir() if d.is_dir()]
    if not dirs:
        return None
    return max(dirs, key=lambda d: d.stat().st_mtime)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--task", default=None)
    args = ap.parse_args()
    root = Path(args.root).resolve()
    task_dir = root / AUTONOMY / args.task if args.task else newest_task(root)
    if task_dir is None or not task_dir.is_dir():
        print(json.dumps({"error": "no task input", "autonomy": str(root / AUTONOMY)},
                         indent=2))
        return 2
    checks = {
        "junit_totals": check_junit(task_dir),
        "a1_no_reflection_injection": check_no_injection(root),
        "a4_redteam_unpinned": check_redteam_unpinned(root),
        "scope_containment": check_scope(root, task_dir),
        "protected_files": check_protected(root, task_dir),
        "f4_fallback_unchanged": check_fallback(root),
    }
    all_pass = all(c["verdict"] == "PASS" for c in checks.values())
    any_fail = any(c["verdict"] == "FAIL" for c in checks.values())
    overall = "PASS" if all_pass else ("FAIL" if any_fail else "INCOMPLETE")
    print(json.dumps({"taskId": task_dir.name, "checks": checks,
                      "overall": overall, "allPass": all_pass,
                      "exitMeaning": "0=all PASS; 1=FAIL or NOT_RUN present; 2=no input"},
                     indent=2, ensure_ascii=False))
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
