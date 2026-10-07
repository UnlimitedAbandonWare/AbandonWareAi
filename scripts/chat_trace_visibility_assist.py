#!/usr/bin/env python3
"""Read-only companion checks for the Codex CHAT_TRACE_DEFAULT_VISIBILITY_20261007 brief.

Devin assist lane (task devin-chat-trace-visibility-assist-1d9218f4):
  pins        - brief file:line anchors vs live tree (SAME/MOVED/ABSENT + DRIFTED)
  cover       - Acceptance 1-10 -> evidence found in the live diff/file
  diff-review - feature attribution + forbidden-line scan over `git diff`
  hashes      - snapshot SHA-256 table vs current (drift detector)
  lease       - fresh target-scoped lease status with CURRENT hashes
  status      - one combined card (hashes + pins + cover summary)

Product source stays with Codex. OBSERVED_IN_DIFF means the seam exists in the
current working tree; it is NOT a product verdict and not a substitute for the
owning session's tests/browser matrix.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

SCHEMA = "awx.chat-trace-visibility-assist.v1"

# --- Snapshot from the brief (PASTE_CODEX_CHAT_TRACE_DEFAULT_VISIBILITY_20261007) ---
SNAPSHOT = {
    "main/resources/static/js/chat-trace-ui.js": "f09a374e9c8b94e91fb318987bb1918d3847b89efd54e03104a9fec8841d2076",
    "main/resources/static/js/chat.js": "6aaf88a048a59474e40fd7ca2fcf6e2ccba6e997766cc8a4b50b5f5ee6529b43",
    "main/resources/static/js/chat-trace-dock.js": "f99e86cf1c2b85158cbe65420197ee84b33d64503b498373b922ae3f6ddae0c7",
    "main/resources/templates/chat-ui.html": "019dca83e321f99780c29044b3cd95a07ff2db08a8e27944bb44bd84f5981e2e",
    "src/test/js/chat-trace-ui.test.cjs": "890f4c4cc4456651d4fe45371dc5a648e03829f882d76db5551173e63b15edab",
    "src/test/js/chat-trace-restore.test.cjs": "77bf7e294ebcf7750c5af51540962f28451664a5b52833d8c8c6369f6ccd71ef",
    "main/resources/static/js/chat-settings-bridge.js": "f6c564c685cbf17289a9f579860ed784616630503f70c935042aabdfd9c4a47c",
    "main/java/com/example/lms/service/ChatPreferenceService.java": "ff06312d8fb7b3d3cbf04204a5f5caaf37e756d49221f1e05f05b153e62fc609",
    "main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java": "744a8a1c893540b82747239ddc1a7f9de5647e54f175b9560b196598ff27684b",
}

# --- Brief anchors: (file, line, expected substring regex, label) ---
PINS = [
    ("main/resources/templates/chat-ui.html", 74, r'class="answer-tools"', "trace details block"),
    ("main/resources/templates/chat-ui.html", 77, r"data-admin-diagnostics", "chatDiagnosticsEnabled gate"),
    ("main/resources/templates/chat-ui.html", 79, r"data-chat-trace-toggle", "trace checkbox"),
    ("main/resources/static/js/chat-trace-ui.js", 19, r"function enabled\(\)", "enabled() gate"),
    ("main/resources/static/js/chat-trace-ui.js", 6, r"MAX_SNAPSHOT_FETCHES\s*=\s*2", "snapshot fetch cap"),
    ("main/resources/static/js/chat-trace-ui.js", 8, r"12000", "snapshot fetch timeout"),
    ("main/resources/static/js/chat-trace-ui.js", 48, r'createElement\("details"\)', "outer details create"),
    ("main/resources/static/js/chat.js", 1291, r"data-chat-trace-toggle", "toggle change seam"),
    ("main/resources/static/js/chat.js", 1372, r"currentControlSettings", "control settings collect"),
    ("main/resources/static/js/chat.js", 6165, r"observedModel", "observedModel guard"),
    ("main/resources/static/js/chat.js", 3568, r"agentWebSearch", "aggregate search signal"),
    ("main/resources/static/js/chat-settings-bridge.js", 18, r"KEYS", "KEYS table"),
    ("main/resources/static/js/chat-settings-bridge.js", 89, r"PREFERENCE_KEYS", "PREFERENCE_KEYS"),
    ("main/resources/static/js/chat-settings-bridge.js", 236, r"function begin|begin\s*=\s*async|async function begin", "begin()"),
    ("main/resources/static/js/chat-settings-bridge.js", 267, r"unscoped|cache", "unscoped cache rule"),
    ("main/java/com/example/lms/service/ChatPreferenceService.java", 17, r"KEYS\s*=\s*Set\.of", "server KEYS"),
    ("main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java", 98, r"mergeModelMetaField", "projection merge"),
    ("main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java", 189, r"modelUsed|sessionId", "modelUsed/sessionId boundary"),
    ("main/java/com/example/lms/api/ChatStreamSignalBuilder.java", 179, r"agentWebSearch|executionReceipt|receipt", "receipt producer"),
]

# --- Acceptance -> evidence markers searched in ADDED diff lines ---
COVER = [
    ("A1 unset->default ON", [
        ("chat-ui.html", r'data-chat-trace-toggle[^>]*\bchecked\b', "checkbox checked attr added"),
        ("chat-settings-bridge.js", r"trace\.checked\s*=\s*value\s*!==\s*false", "unset->ON semantics"),
    ]),
    ("A2 explicit OFF persists + roundtrip", [
        ("ChatPreferenceService.java", r'"chatTraceEnabled"', "server pref key"),
        ("chat-settings-bridge.js", r"chatTraceEnabled", "bridge pref key"),
        ("chat-settings-bridge.js", r"addEventListener\('change'", "toggle save listener"),
    ]),
    ("A3 owner boundary anonymous/login", [
        ("chat-settings-bridge.js", r"expectedOwnerScopeId", "owner-bound PATCH"),
        ("chat-settings-bridge.js", r"trace\.disabled\s*=\s*!.*ownerScopeId", "anonymous disabled"),
    ]),
    ("A4 existing priorities preserved", [
        ("chat-settings-bridge.js", r"begin", "begin seam retained"),
    ]),
    ("A6 observed-not-requested values", [
        ("chat-trace-ui.js", r"NOT_OBSERVED", "unknown stays NOT_OBSERVED"),
        ("chat-trace-ui.js", r"observedModel", "observed model field"),
        ("ChatSessionDetailResponseBuilder.java", r"prompt\.citableEvidenceCount|orch\.mode", "projection diag scalars"),
    ]),
    ("A7 no extra fetch for summary", [
        ("chat-trace-ui.js", r"upsertSummary", "no-click summary entry"),
        ("chat-trace-ui.js", r"awx-trace-overview", "overview outside details"),
    ]),
]

FORBIDDEN = [
    (r"\binnerHTML\s*=", "raw innerHTML assignment"),
    (r"\bouterHTML\s*=", "outerHTML assignment"),
    (r"\beval\s*\(", "eval("),
    (r"new\s+Function", "new Function"),
    (r"document\.write", "document.write"),
    (r"setInterval", "new polling interval"),
    (r"WEB_TRACE_EXPOSE\s*=\s*true", "trace expose default true"),
    (r"permitAll", "permitAll relaxation"),
    (r'"memoryMode"\s*->', "memoryMode semantic edit"),
    (r"(sk-|AIza|eyJ)[A-Za-z0-9_\-]{12,}", "secret-shaped literal"),
]

FEATURE_PATTERNS = [
    ("TRACE", re.compile(r"chatTrace|trace-ui|upsertSummary|showOverview|observedModel|citableEvidence|orch\.mode|awx-trace", re.I)),
    ("RESCUE", re.compile(r"googleSearchRescue|rescue", re.I)),
    ("EXPORT", re.compile(r"export|ConversationExport|zip", re.I)),
]

DIFF_PATHS = [
    "main/resources/templates/chat-ui.html",
    "main/resources/static/js/chat.js",
    "main/resources/static/js/chat-trace-ui.js",
    "main/resources/static/js/chat-settings-bridge.js",
    "main/java/com/example/lms/service/ChatPreferenceService.java",
    "main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java",
    "main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java",
    "main/java/com/example/lms/api/ChatStreamSignalBuilder.java",
    "src/test/js/chat-trace-ui.test.cjs",
    "src/test/js/chat-trace-restore.test.cjs",
    "src/test/js/settings-core.test.cjs",
    "src/chatUiTest/java/com/example/lms/settings/ChatPreferencePersistenceTest.java",
]


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_diff_added(root: Path, rel: str) -> list:
    try:
        out = subprocess.run(
            ["git", "diff", "--", rel], cwd=root, capture_output=True,
            encoding="utf-8", errors="replace", timeout=60
        ).stdout
    except Exception:
        return []
    if out is None:
        return []
    return [ln[1:] for ln in out.splitlines() if ln.startswith("+") and not ln.startswith("+++")]


def cmd_hashes(root: Path):
    rows = []
    drifted = 0
    for rel, snap in SNAPSHOT.items():
        p = root / rel
        if not p.exists():
            rows.append({"path": rel, "status": "MISSING", "snapshot": snap[:12]})
            continue
        cur = sha256(p)
        same = cur == snap
        if not same:
            drifted += 1
        rows.append({"path": rel, "status": "SAME" if same else "DRIFTED",
                     "snapshot": snap[:12], "current": cur[:12]})
    print(json.dumps({"schema": SCHEMA, "cmd": "hashes", "drifted": drifted,
                      "total": len(rows), "rows": rows}, indent=2))
    return 0


def cmd_pins(root: Path, window: int = 60):
    snap = {r["path"]: r for r in json.loads(json.dumps([]))}  # noqa: F841 (placeholder, kept for symmetry)
    drift = {}
    for rel, snap_sha in SNAPSHOT.items():
        p = root / rel
        drift[rel] = p.exists() and sha256(p) != snap_sha
    rows = []
    bad = 0
    for rel, line, pat, label in PINS:
        p = root / rel
        rec = {"anchor": f"{rel}:{line}", "label": label}
        if not p.exists():
            rec["status"] = "MISSING_FILE"
            bad += 1
            rows.append(rec)
            continue
        lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        rx = re.compile(pat)
        idx = line - 1
        if idx < len(lines) and rx.search(lines[idx]):
            rec["status"] = "SAME"
        else:
            lo, hi = max(0, idx - window), min(len(lines), idx + window + 1)
            found = next((i + 1 for i in range(lo, hi) if rx.search(lines[i])), None)
            if found:
                rec["status"] = "MOVED"
                rec["nowLine"] = found
            else:
                rec["status"] = "ABSENT"
                bad += 1
        rec["fileDrifted"] = drift.get(rel, False)
        rows.append(rec)
    print(json.dumps({"schema": SCHEMA, "cmd": "pins", "anchors": len(rows),
                      "problems": bad, "rows": rows}, indent=2))
    return 1 if bad else 0


def cmd_cover(root: Path):
    items = []
    for name, checks in COVER:
        evid = []
        for fname, pat, label in checks:
            rel = next((p for p in DIFF_PATHS if p.endswith(fname)), None)
            if rel is None:
                rel = next((p for p in SNAPSHOT if p.endswith(fname)), fname)
            p = root / rel
            found_in_file = p.exists() and re.search(pat, p.read_text(encoding="utf-8", errors="replace"))
            found_in_diff = any(re.search(pat, ln) for ln in git_diff_added(root, rel))
            evid.append({"label": label, "file": rel, "inFile": bool(found_in_file),
                         "inAddedDiff": bool(found_in_diff)})
        items.append({"acceptance": name, "evidence": evid})
    print(json.dumps({"schema": SCHEMA, "cmd": "cover",
                      "note": "inAddedDiff = marker in `git diff` added lines; SESSION tests/browser still required",
                      "items": items}, indent=2))
    return 0


def cmd_diff_review(root: Path):
    try:
        diff = subprocess.run(["git", "diff", "--"] + DIFF_PATHS, cwd=root,
                              capture_output=True, encoding="utf-8",
                              errors="replace", timeout=90).stdout or ""
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"schema": SCHEMA, "cmd": "diff-review", "error": str(e)}))
        return 2
    features = {"TRACE": 0, "RESCUE": 0, "EXPORT": 0, "OTHER": 0}
    flags = []
    cur_file = None
    for ln in diff.splitlines():
        if ln.startswith("+++ b/"):
            cur_file = ln[6:]
            continue
        if not (ln.startswith("+") and not ln.startswith("+++")):
            continue
        body = ln[1:]
        tag = "OTHER"
        for name, rx in FEATURE_PATTERNS:
            if rx.search(body):
                tag = name
                break
        features[tag] += 1
        for pat, why in FORBIDDEN:
            if re.search(pat, body):
                flags.append({"file": cur_file, "line": body.strip()[:140], "why": why})
    verdict = "CLEAN" if not flags else "FLAGS"
    print(json.dumps({"schema": SCHEMA, "cmd": "diff-review", "verdict": verdict,
                      "addedLinesByFeature": features,
                      "note": "attribution is heuristic; flags are review cues, not verdicts",
                      "flags": flags}, indent=2))
    return 1 if flags else 0


def cmd_lease(root: Path):
    manifest = {"targets": [{"path": rel, "sha256": (sha256(root / rel) if (root / rel).exists() else None)}
                            for rel in DIFF_PATHS]}
    out_dir = root / "var" / "codex-assist-chat-trace-visibility-20261007"
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
    fn = {"pins": cmd_pins, "cover": cmd_cover, "diff-review": cmd_diff_review,
          "hashes": cmd_hashes, "lease": cmd_lease, "status": cmd_status}.get(cmd)
    if fn is None:
        print(__doc__)
        return 2
    return fn(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
