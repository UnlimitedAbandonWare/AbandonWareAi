#!/usr/bin/env python3
"""Read-only companion checks for the Codex P0 prompt-packing brief
(PASTE_CODEX_DEMO_P0_NEXT_FIX_20261007 — "duplicate [출처] URL trailer eats the
512-char selected-body budget inside StandardPromptBuilder.safeSnippet").

Devin assist lane (task devin-p0-packing-assist-97602211):
  hashes      - brief SHA-256/bytes pins vs live tree (SAME/DRIFTED/MISSING)
  pins        - brief file:line anchors vs live tree (SAME/MOVED/ABSENT)
  state       - phase classify: RED_STAGED / FIX_LANDED / PINNED_PRE_BRIEF
  cover       - acceptance slots -> evidence found in live file/diff
  diff-review - forbidden-file scan + forbidden-line cues over `git diff`
  lease       - fresh target-scoped lease status with CURRENT hashes
  status      - combined card (hashes + pins + state + cover summary)

Product source stays with the owning Codex session (codex-p0-packing-*).
OBSERVED_IN_DIFF means the seam exists in the working tree; it is NOT a
product verdict — the owning session's focused Gradle tests decide.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

SCHEMA = "awx.p0-packing-assist.v1"

BUILDER = "main/java/com/example/lms/prompt/StandardPromptBuilder.java"
BUILDER_TEST = "src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java"
ROLE_TEST = "src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java"
GATE_TEST = "src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java"
RETRIEVER = "main/java/com/example/lms/service/rag/WebSearchRetriever.java"
SCRAPER = "main/java/com/example/lms/service/rag/extract/PageContentScraper.java"
WORKFLOW = "main/java/com/example/lms/service/ChatWorkflow.java"
DECISION = "main/java/com/example/lms/gptsearch/decision/SearchDecisionService.java"

# Brief section 6 table: path -> (sha256 prefix as printed, bytes). Full SHA-256
# was printed for builder/retriever/builderTest/workflowRoleTest; SHA12 + size
# only for the rest — prefix compare plus byte-size match is the check.
SNAPSHOT = {
    BUILDER: ("3b1e6f2b90a069886aee9568fee20f6061d5a1fbfb39574ca35223431f3326d5", 60219),
    BUILDER_TEST: ("f8506721e9641b8e7096a641de3e471d9362916189aaa221f1a49b7fb3bf4313", 10623),
    ROLE_TEST: ("c5235b626dc771e4e377849fa506d63944b5b9b2e850b3614e9d8210a16ee383", 52447),
    GATE_TEST: ("3ca2f1c2f862", 145372),
    RETRIEVER: ("ad8bf1a502a9d33ece2cf10d14323098a434db527e9f2e0ede2a0419a80a033e", 78971),
    SCRAPER: ("544b48913c01", 20192),
    WORKFLOW: ("fd8fd72e064b", 768669),
    DECISION: ("284c8138336d", 15246),
}

# Brief anchors: (file, line, regex, label). Line 0 = search whole file
# (used for anchors whose file already drifted since the brief snapshot).
PINS = [
    (BUILDER, 193, r"ctx\.web\(\)", "web list render seam"),
    (BUILDER, 196, r"safeSnippet\(c\)", "web -> safeSnippet call"),
    (BUILDER, 200, r"\[W%d\]", "W marker format"),
    (BUILDER, 439, r"appendCitableEvidenceMetadata", "citable metadata block"),
    (BUILDER, 455, r"### CITABLE EVIDENCE METADATA", "metadata header"),
    (BUILDER, 462, r'appendMeta\(sb, "source"', "metadata source render"),
    (BUILDER, 1007, r"private static String safeSnippet", "safeSnippet def"),
    (BUILDER, 1015, r"truncate\(t, 512\)", "512 body budget"),
    (BUILDER, 1074, r"public static String truncate", "generic truncate def"),
    (BUILDER, 1088, r"int head = max / 2", "head+tail truncate"),
    (RETRIEVER, 630, r"pickByHeuristic\(query\.text\(\), body, 480\)", "480 selected body"),
    (RETRIEVER, 645, r'"\\n\\n\[출처\] " \+ url', "terminal source trailer"),
    (RETRIEVER, 1215, r"private Content toWebContent", "toWebContent def"),
    (RETRIEVER, 1225, r'meta\.put\("url"', "url metadata"),
    (RETRIEVER, 1227, r'meta\.put\("source"', "source metadata"),
    (ROLE_TEST, 168, r"immediateSameOwnerFollowupUsesCurrentRelationAndSourceAtFinalModelBoundary", "A->B final boundary test"),
    (ROLE_TEST, 218, r"semanticWebBodyReachesFinalModelWithCompressionAndFinalFit", "compression/fit capture test"),
    (GATE_TEST, 1856, r"twoTurnEvidenceIdentitySurvivesFinalFit", "two-turn identity gate test"),
    # Staged RED fixtures inside the drifted builder test (search whole file).
    (BUILDER_TEST, 0, r"duplicateCurrentWebLocatorDoesNotConsumeSelectedBodyBudget", "RED test: dup locator budget"),
    (BUILDER_TEST, 0, r"unmatchedOrUnrenderedLocatorsKeepLegacySnippetAndSource", "RED test: legacy fallback kept"),
    (BUILDER_TEST, 0, r"onlyExactTerminalTrailerIsRedundantAndTruncatedMetadataIsInsufficient", "RED test: exact trailer boundary"),
    (BUILDER_TEST, 0, r'chapter-2026-', "long-url fixture"),
    (BUILDER_TEST, 0, r'Cobalt signature weapon is Azure Lantern', "relation fixture"),
    (BUILDER_TEST, 0, r'unofficial community estimate dated 2026-10-01', "qualifier fixture"),
]

DIFF_PATHS = [BUILDER, BUILDER_TEST, ROLE_TEST, GATE_TEST]
FORBIDDEN_PATHS = [RETRIEVER, SCRAPER, WORKFLOW, DECISION,
                   "build.gradle.kts", "settings.gradle"]

# Acceptance slots from brief sections 4-5 -> markers proving the seam exists.
COVER = [
    ("AC1 relation+qualifier survive both URL lengths", [
        (BUILDER_TEST, r"duplicateCurrentWebLocatorDoesNotConsumeSelectedBodyBudget", "RED test present"),
        (BUILDER_TEST, r"assertEquals\(selected, snippet\)|assertTrue\(snippet\.startsWith\(selected\)\)", "post-fix body-preserving expectation"),
        (BUILDER_TEST, r"assertTrue\(snippet\.length\(\) <= 512\)", "budget still capped"),
    ]),
    ("AC2 metadata-less / mismatched fallback keeps trailer", [
        (BUILDER_TEST, r"unmatchedOrUnrenderedLocatorsKeepLegacySnippetAndSource", "fallback matrix test"),
        (BUILDER_TEST, r"assertEquals\(text, webSnippet", "legacy text preserved assert"),
    ]),
    ("AC3 exact identity + suffix boundary only", [
        (BUILDER_TEST, r"onlyExactTerminalTrailerIsRedundantAndTruncatedMetadataIsInsufficient", "boundary test"),
        (BUILDER_TEST, r"oversizedUrl", "truncated-metadata negative case"),
    ]),
    ("AC4 generic truncate / 512 / other seams untouched", [
        (BUILDER, r"public static String truncate", "truncate still generic"),
        (BUILDER, r"truncate\(t, 512\)", "512 budget literal intact"),
    ]),
    ("AC5 A->B final-model boundary regression exists", [
        (ROLE_TEST, r"immediateSameOwnerFollowupUsesCurrentRelationAndSourceAtFinalModelBoundary", "A->B capture seam"),
        (GATE_TEST, r"twoTurnEvidenceIdentitySurvivesFinalFit", "release gate test"),
    ]),
]

FORBIDDEN = [
    (r"(sk-|AIza|eyJ)[A-Za-z0-9_\-]{12,}", "secret-shaped literal"),
    (r"truncate\([^)]*,\s*(?:6\d\d|7\d\d|8\d\d|9\d\d|1\d\d\d)\s*\)", "body budget raise"),
    (r"permitAll", "auth relaxation"),
    (r"class\s+\w*(Orchestrator|Wrapper)\b", "new wrapper/orchestrator cue"),
    (r"System\.exit", "process exit in product code"),
]


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_diff_lines(root: Path, paths):
    try:
        out = subprocess.run(["git", "diff", "--"] + list(paths), cwd=root,
                             capture_output=True, encoding="utf-8",
                             errors="replace", timeout=90).stdout or ""
    except Exception:
        return [], []
    added, touched = [], set()
    cur = None
    for ln in out.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:]
            touched.add(cur)
        elif ln.startswith("diff --git"):
            m = re.search(r" b/(\S+)", ln)
            if m:
                touched.add(m.group(1))
        elif ln.startswith("+") and not ln.startswith("+++"):
            added.append((cur, ln[1:]))
    return added, sorted(touched)


def cmd_hashes(root: Path):
    rows, drifted = [], 0
    for rel, (snap, size) in SNAPSHOT.items():
        p = root / rel
        if not p.exists():
            rows.append({"path": rel, "status": "MISSING", "snapshot": snap[:12]})
            drifted += 1
            continue
        cur = sha256(p)
        cur_size = p.stat().st_size
        same = cur.startswith(snap) and cur_size == size
        if not same:
            drifted += 1
        rows.append({"path": rel, "status": "SAME" if same else "DRIFTED",
                     "snapshot": snap[:12], "current": cur[:12],
                     "bytes": cur_size, "expectedBytes": size})
    print(json.dumps({"schema": SCHEMA, "cmd": "hashes", "drifted": drifted,
                      "total": len(rows), "rows": rows}, indent=2))
    return 0


def _find(lines, idx, pat, window, whole_file):
    rx = re.compile(pat)
    if not whole_file and idx < len(lines) and rx.search(lines[idx]):
        return idx + 1
    lo = 0 if whole_file else max(0, idx - window)
    hi = len(lines) if whole_file else min(len(lines), idx + window + 1)
    return next((i + 1 for i in range(lo, hi) if rx.search(lines[i])), None)


def cmd_pins(root: Path, window: int = 60):
    drift = {}
    for rel, (snap, size) in SNAPSHOT.items():
        p = root / rel
        drift[rel] = p.exists() and not (sha256(p).startswith(snap) and p.stat().st_size == size)
    rows, bad = [], 0
    for rel, line, pat, label in PINS:
        p = root / rel
        rec = {"anchor": f"{rel}:{line or '*'}", "label": label}
        if not p.exists():
            rec["status"] = "MISSING_FILE"
            bad += 1
            rows.append(rec)
            continue
        lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        found = _find(lines, line - 1, pat, window, line == 0)
        if found is None:
            rec["status"] = "ABSENT"
            bad += 1
        elif found == line:
            rec["status"] = "SAME"
        else:
            rec["status"] = "MOVED"
            rec["nowLine"] = found
        rec["fileDrifted"] = drift.get(rel, False)
        rows.append(rec)
    print(json.dumps({"schema": SCHEMA, "cmd": "pins", "anchors": len(rows),
                      "problems": bad, "rows": rows}, indent=2))
    return 1 if bad else 0


def cmd_state(root: Path):
    tests_staged = []
    p = root / BUILDER_TEST
    if p.exists():
        text = p.read_text(encoding="utf-8", errors="replace")
        for m in ("duplicateCurrentWebLocatorDoesNotConsumeSelectedBodyBudget",
                  "unmatchedOrUnrenderedLocatorsKeepLegacySnippetAndSource",
                  "onlyExactTerminalTrailerIsRedundantAndTruncatedMetadataIsInsufficient"):
            if m in text:
                tests_staged.append(m)
    bp = root / BUILDER
    builder_changed = bp.exists() and not sha256(bp).startswith(SNAPSHOT[BUILDER][0])
    if len(tests_staged) == 3 and not builder_changed:
        phase = "RED_STAGED_PRODUCT_UNCHANGED"
    elif len(tests_staged) == 3 and builder_changed:
        phase = "FIX_IN_DIFF"
    elif not tests_staged and not builder_changed:
        phase = "PINNED_PRE_BRIEF"
    else:
        phase = "PARTIAL_STAGE"
    print(json.dumps({"schema": SCHEMA, "cmd": "state", "phase": phase,
                      "redTestsStaged": tests_staged,
                      "builderChanged": builder_changed,
                      "note": "phase is a file-state classify; GREEN verdict needs owning session's Gradle run"},
                     indent=2))
    return 0


def cmd_cover(root: Path):
    added, _ = git_diff_lines(root, DIFF_PATHS)
    items = []
    for name, checks in COVER:
        evid = []
        for rel, pat, label in checks:
            p = root / rel
            in_file = p.exists() and re.search(pat, p.read_text(encoding="utf-8", errors="replace"))
            in_diff = any(f == rel and re.search(pat, ln) for f, ln in added)
            evid.append({"label": label, "file": rel,
                         "inFile": bool(in_file), "inAddedDiff": bool(in_diff)})
        items.append({"acceptance": name, "evidence": evid})
    print(json.dumps({"schema": SCHEMA, "cmd": "cover",
                      "note": "inFile/inAddedDiff are seam-presence cues; focused Gradle tests are the verdict",
                      "items": items}, indent=2))
    return 0


def cmd_diff_review(root: Path):
    added, touched = git_diff_lines(root, DIFF_PATHS + FORBIDDEN_PATHS)
    flags = []
    for rel in touched:
        hit = next((f for f in FORBIDDEN_PATHS
                    if rel == f or rel.endswith(Path(f).name)), None)
        if hit is None:
            continue
        snap = SNAPSHOT.get(hit)
        p = root / hit
        # A no-touch file with a pre-existing (pre-brief) uncommitted diff but a
        # pin-matching hash was not changed by this work — info, not a flag.
        if snap is not None and p.exists() and \
                sha256(p).startswith(snap[0]) and p.stat().st_size == snap[1]:
            flags.append({"file": rel, "line": "<pre-existing diff vs HEAD>",
                          "why": "no-touch file, hash still pinned (pre-brief work)", "info": True})
        else:
            flags.append({"file": rel, "line": "<file touched>",
                          "why": "brief-declared no-touch file changed"})
    for f, ln in added:
        for pat, why in FORBIDDEN:
            if re.search(pat, ln):
                flags.append({"file": f, "line": ln.strip()[:140], "why": why})
    real = [f for f in flags if not f.get("info")]
    verdict = "CLEAN" if not real else "FLAGS"
    print(json.dumps({"schema": SCHEMA, "cmd": "diff-review", "verdict": verdict,
                      "touchedPaths": touched,
                      "note": "flags are review cues, not verdicts; info=pre-brief diff on pin-matching no-touch file",
                      "flags": flags}, indent=2))
    return 1 if real else 0


def cmd_lease(root: Path):
    manifest = {"targets": [{"path": rel,
                             "sha256": (sha256(root / rel) if (root / rel).exists() else None)}
                            for rel in DIFF_PATHS]}
    out_dir = root / "var" / "codex-assist-p0-packing-20261007"
    out_dir.mkdir(parents=True, exist_ok=True)
    mpath = out_dir / "targets-current.json"
    mpath.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    ps1 = root / "__patch_drop__" / "source_edit_session.ps1"
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1),
           "-Action", "status", "-Json", "-TargetManifest", str(mpath)]
    try:
        res = subprocess.run(cmd, cwd=root, capture_output=True, encoding="utf-8",
                             errors="replace", timeout=120)
        print(res.stdout.strip() or res.stderr.strip())
        return res.returncode
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"schema": SCHEMA, "cmd": "lease", "error": str(e)}))
        return 2


def cmd_status(root: Path):
    print("== hashes ==")
    cmd_hashes(root)
    print("== pins ==")
    cmd_pins(root)
    print("== state ==")
    cmd_state(root)
    print("== cover ==")
    cmd_cover(root)
    return 0


def main(argv):
    root = Path(".")
    if "--root" in argv:
        i = argv.index("--root")
        root = Path(argv[i + 1])
        del argv[i:i + 2]
    cmd = argv[0] if argv else "status"
    fn = {"hashes": cmd_hashes, "pins": cmd_pins, "state": cmd_state,
          "cover": cmd_cover, "diff-review": cmd_diff_review,
          "lease": cmd_lease, "status": cmd_status}.get(cmd)
    if fn is None:
        print(__doc__)
        return 2
    return fn(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
