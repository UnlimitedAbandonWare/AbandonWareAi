#!/usr/bin/env python3
"""settings_defaults_static_check -- live-tree static checks S1..S12 for the
Codex "settings defaults" directive (Devin assist rail, read-only).

    baseline --out FILE [--root DIR]
        D0 baseline: HEAD, git status map, Codex allowed-file digests,
        chat.js sha256, DDL inventory, ddl-auto lines, codex ledger paths.
    check --baseline FILE [--root DIR] [--out FILE]
        S1..S12 -> PASS / FAIL / PENDING / HEURISTIC (+ FOREIGN for S11).

Everything below is a read of the live tree or of the baseline file - no
build, no server call, no writes outside --out. A missing file makes a check
PENDING, never invented.

S1  factory-default literals (temperature 0.2/0.3, topP 1.0, maxTokens 2048,
    local-model fallback) must live only in application-llm.yaml chat.defaults
S2  ChatRequestDto topP/penalties/maxTokens pre-defaults removed;
    isSearchModeExplicit preserved
S3  merge path: save/saveAllSettings/changeCurrentModel call sites == 0
S4  PATCH /api/settings/preferences owner comes from cookie/principal, not
    the request body (PENDING while the endpoint does not exist)
S5  new DDL / ddl-auto / schema-migration changes vs baseline == 0
S6  chat.js sha256 == baseline
S7  localStorage v2 key present; no v1-key removal before ACK (HEURISTIC)
S8  llmrouter.auto logical route; OAuth excluded from auto (HEURISTIC)
S9  searchMode -> useWebSearch derivation in exactly one site
S10 start_rag_stack.ps1 explicitly sets AWX_OPTIONAL_HTTPS_ENABLED=false
    for the child process
S11 changed paths outside the allowed set since baseline -> FOREIGN marks
S12 new focused test files appeared; no unfiltered test/check run evidence
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from awx_paths import resolve as _awx_resolve

SCHEMA = "awx.settings-defaults-static-check.v1"
CHAT_JS = "main/resources/static/js/chat.js"
EXPECTED_CHAT_JS_PREFIX = "4225D9447524"

CODEX_FILES = [
    "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
    "main/java/com/example/lms/dto/ChatRequestDto.java",
    "main/java/com/example/lms/service/SettingsService.java",
    "main/java/com/example/lms/api/ChatSessionMetaMerger.java",
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/web/PageController.java",
    "main/java/com/example/lms/service/ChatModelCatalogService.java",
    "main/resources/static/js/chat-settings-bridge.js",
    "main/resources/static/js/settings-page.js",
    "main/resources/static/js/chat-model-picker.js",
    "main/resources/static/js/model-strategy.js",
    "main/resources/templates/settings.html",
    "main/resources/templates/chat-ui.html",
    "main/resources/application-llm.yaml",
    "main/resources/application.yml",
    "scripts/start_rag_stack.ps1",
]
ENTITY_UPP = "main/java/com/example/lms/domain/UserPreferenceProfile.java"

# paths that are legitimately Codex's (scope from its journal) or this
# assist lane's own new files - everything else changed since baseline is
# reported FOREIGN by S11, never FAIL.
CODEX_SCOPE_GLOBS = [
    "main/java/com/example/lms/api/**",
    "main/java/com/example/lms/dto/ChatRequestDto.java",
    "main/java/com/example/lms/service/ChatPreferenceService.java",
    "main/java/com/example/lms/config/ChatDefaultsProperties.java",
    "main/java/com/example/lms/repository/**",
    "src/chatUiTest/**",
    "src/test/**",
]
MY_OWN_GLOBS = [
    "scripts/settings_defaults_*.py",
    "scripts/settings_defaults_*.ps1",
    "scripts/test_settings_defaults_*.py",
    "var/settings-defaults-assist/**",
    "data/agent-handoff/devin-settings-defaults-assist-*/**",
    "data/agent-handoff/codex-autonomy/devin-settings-defaults-assist-*/**",
]

S1_SCAN_FILES = [
    "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
    "main/java/com/example/lms/dto/ChatRequestDto.java",
    "main/java/com/example/lms/service/SettingsService.java",
    "main/resources/templates/settings.html",
    "main/resources/templates/chat-ui.html",
    "main/resources/static/js/chat-settings-bridge.js",
    "main/resources/static/js/settings-page.js",
    "main/resources/static/js/chat-model-picker.js",
    "main/resources/static/js/model-strategy.js",
]
S1_PATTERNS = [
    ("temp0.2/0.3", re.compile(
        r"temperature[^\n]{0,50}\b0\.[23]\b|\b0\.[23]\b[^\n]{0,50}temperature",
        re.IGNORECASE)),
    ("topP1.0", re.compile(
        r"top_?p[^\n]{0,40}\b1\.0\b|\b1\.0\b[^\n]{0,40}top_?p",
        re.IGNORECASE)),
    ("maxTokens2048", re.compile(
        r"(?:max_?tokens|maxOutputTokens)[^\n]{0,40}\b2048\b|"
        r"\b2048\b[^\n]{0,40}(?:max_?tokens|maxOutputTokens)",
        re.IGNORECASE)),
    ("valueFallback", re.compile(
        r"@Value\([^)]*(0\.[23]|2048|1\.0)\)", re.IGNORECASE)),
    ("localModelDefault", re.compile(
        r"(?:default|fallback)[\w]{0,24}\s*[:=]\s*[\"']"
        r"(?:qwen|gemma|llama|smtek|ollama|mistral)[^\"']*",
        re.IGNORECASE)),
]
SAVE_CALL_RE = re.compile(
    r"\.(save|saveAllSettings|changeCurrentModel)\s*\(")
SETTINGS_ENDPOINT_RE = re.compile(r"settings/preferences", re.IGNORECASE)
PRINCIPAL_RE = re.compile(
    r"Principal|Authentication|@AuthenticationPrincipal|CookieValue|"
    r"HttpSession|SecurityContext", re.IGNORECASE)
BODY_OWNER_RE = re.compile(
    r"@(RequestBody|RequestParam|ModelAttribute)[^\n]{0,120}owner|"
    r"\bbody\b[^\n]{0,80}\bowner\b|getOwner\s*\(", re.IGNORECASE)
V2_KEY = "awx.settings.v2.preferences"
V1_REMOVE_RE = re.compile(
    r"localStorage\.removeItem\s*\(\s*[\"']([^\"']*(?:settings|pref)[^\"']*)",
    re.IGNORECASE)
LLMAUTO_RE = re.compile(r"llmrouter\.auto")
OAUTH_RE = re.compile(r"chatgpt-oauth|oauth", re.IGNORECASE)
USE_WEB_SEARCH_RE = re.compile(r"useWebSearch")
# a real derivation site assigns useWebSearch and references searchMode+OFF
# on the same line - comments and map.put("useWebSearch", ...) packing don't
DERIVE_ASSIGN_RE = re.compile(r"useWebSearch\s*[:=]|\.useWebSearch\s*\(")
SEARCH_MODE_OFF_RE = re.compile(r"searchMode", re.IGNORECASE)
OFF_WORD_RE = re.compile(r"OFF")
COMMENT_LINE_RE = re.compile(r"^\s*(//|\*|/\*)")
HTTPS_ENV_RE = re.compile(r"AWX_OPTIONAL_HTTPS_ENABLED")
HTTPS_SET_FALSE_RE = re.compile(
    r"(?:env:AWX_OPTIONAL_HTTPS_ENABLED|AWX_OPTIONAL_HTTPS_ENABLED)\s*"
    r"[,=:]\s*['\"]?false", re.IGNORECASE)

GIT_CANDIDATES = ["git", str(_awx_resolve("git.exe"))]


# ------------------------------------------------------------------ helpers

def _now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _sha256(path) -> str | None:
    try:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest().upper()
    except OSError:
        return None


def _git(root, *gargs) -> tuple[int, str]:
    for exe in GIT_CANDIDATES:
        try:
            proc = subprocess.run([exe, *gargs], cwd=root, capture_output=True,
                                  text=True, timeout=60)
            return proc.returncode, proc.stdout or ""
        except OSError:
            continue
    return -1, ""


def _git_status(root) -> dict:
    code, out = _git(root, "status", "--short")
    status = {}
    if code != 0:
        return status
    for line in out.splitlines():
        if len(line) < 4:
            continue
        st, path = line[:2], line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        status[path] = st.strip() or "M"
    return status


def _glob(root, pattern) -> list:
    return [os.path.join(root, p) for p in
            glob.glob(pattern, root_dir=root, recursive=True)]


def _rel(root, path) -> str:
    return os.path.relpath(path, root).replace("\\", "/")


def _read_lines(path) -> list | None:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return None


def _hit_lines(root, rel: str, regex) -> list:
    """-> [{file,line,text}] for every line matching regex in rel."""
    lines = _read_lines(os.path.join(root, rel))
    if lines is None:
        return []
    return [{"file": rel, "line": i + 1, "text": l.strip()[:180]}
            for i, l in enumerate(lines) if regex.search(l)]


def _covered(path: str, allowed_files: list, extra_globs: list) -> bool:
    path = path.replace("\\", "/")
    if path in allowed_files:
        return True
    for g in extra_globs:
        if glob.fnmatch.fnmatch(path, g) or glob.fnmatch.fnmatch(
                "/" + path, "*/" + g):
            return True
        if g.endswith("/**") and path.startswith(g[:-3].rstrip("/") + "/"):
            return True
    return False


# ------------------------------------------------------------------ baseline

def cmd_baseline(args) -> int:
    root = os.path.abspath(args.root)
    code, head = _git(root, "rev-parse", "HEAD")
    status_map = _git_status(root)
    files = {}
    for rel in CODEX_FILES + [ENTITY_UPP]:
        full = os.path.join(root, rel)
        exists = os.path.isfile(full)
        files[rel] = {"exists": exists,
                      "size": os.path.getsize(full) if exists else None,
                      "sha256": _sha256(full) if exists else None}
    chat_sha = _sha256(os.path.join(root, CHAT_JS))
    ledgers = []
    for g in ("data/agent-handoff/codex-settings-defaults-*",
              "data/agent-handoff/codex-autonomy/codex-settings-defaults-*"):
        for d in _glob(root, g):
            if os.path.isdir(d):
                ledgers.append({
                    "dir": _rel(root, d),
                    "lastWriteUtc": datetime.fromtimestamp(
                        os.path.getmtime(d), timezone.utc).isoformat(
                            timespec="seconds")})
    ddl_files = {}
    for g in ("main/resources/db/migration/**/*",
              "main/resources/schema*.sql", "main/resources/db/**/*.sql"):
        for f in _glob(root, g):
            if os.path.isfile(f):
                ddl_files[_rel(root, f)] = _sha256(f)
    ddl_auto = []
    for g in ("main/resources/application*.yml",
              "main/resources/application*.yaml",
              "main/resources/application*.properties"):
        for f in _glob(root, g):
            ddl_auto += _hit_lines(root, _rel(root, f),
                                   re.compile(r"ddl-auto|ddl_auto|hbm2ddl",
                                              re.IGNORECASE))
    baseline = {
        "schema": "awx.settings-defaults-baseline.v1",
        "generatedAtUtc": _now_utc(), "root": root,
        "head": head.strip() if code == 0 else None,
        "gitStatus": status_map,
        "codexFiles": files,
        "chatJs": {"path": CHAT_JS, "sha256": chat_sha,
                   "expectedPrefix": EXPECTED_CHAT_JS_PREFIX,
                   "prefixOk": bool(chat_sha and chat_sha.startswith(
                       EXPECTED_CHAT_JS_PREFIX))},
        "codexLedgers": sorted(ledgers, key=lambda x: x["dir"]),
        "ddlFiles": ddl_files,
        "ddlAutoLines": ddl_auto,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(baseline, indent=2, ensure_ascii=False),
                   encoding="utf-8")
    print(json.dumps({"baseline": str(out), "head": baseline["head"],
                      "gitStatusCount": len(status_map),
                      "chatJsPrefixOk": baseline["chatJs"]["prefixOk"],
                      "codexLedgers": len(ledgers),
                      "ddlFiles": len(ddl_files)},
                     ensure_ascii=False))
    return 0


# ------------------------------------------------------------------ checks

def s1(root) -> dict:
    hits = []
    for rel in S1_SCAN_FILES:
        for name, rx in S1_PATTERNS:
            for h in _hit_lines(root, rel, rx):
                h["pattern"] = name
                hits.append(h)
    anchor = _hit_lines(root, "main/resources/application-llm.yaml",
                        re.compile(r"^\s*defaults:|chat:", re.IGNORECASE))[:5]
    return {"status": "FAIL" if hits else "PASS",
            "detail": f"{len(hits)} factory-default literal sites outside "
                      "application-llm.yaml chat.defaults",
            "defaultsAnchor": anchor, "hits": hits[:60]}


def s2(root) -> dict:
    rel = "main/java/com/example/lms/dto/ChatRequestDto.java"
    lines = _read_lines(os.path.join(root, rel))
    if lines is None:
        return {"status": "PENDING", "detail": f"{rel} missing"}
    fields = ("topP", "top_p", "presencePenalty", "frequencyPenalty",
              "maxTokens", "max_tokens")
    defaults = []
    for i, l in enumerate(lines):
        for f in fields:
            if f in l and re.search(r"=\s*[\d\"']|defaultValue\s*=" , l):
                defaults.append({"line": i + 1, "field": f,
                                 "text": l.strip()[:160]})
                break
    explicit = any("isSearchModeExplicit" in l for l in lines)
    return {"status": "PASS" if (not defaults and explicit) else "FAIL",
            "detail": f"default-initializers={len(defaults)} "
                      f"isSearchModeExplicit={'present' if explicit else 'ABSENT'}",
            "hits": defaults}


def s3(root) -> dict:
    hits = []
    for rel in ("main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
                "main/java/com/example/lms/api/ChatSessionMetaMerger.java"):
        hits += _hit_lines(root, rel, SAVE_CALL_RE)
    if not any(os.path.isfile(os.path.join(
            root, r)) for r in (
            "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
            "main/java/com/example/lms/api/ChatSessionMetaMerger.java")):
        return {"status": "PENDING", "detail": "merge files missing"}
    return {"status": "PASS" if not hits else "FAIL",
            "detail": f"{len(hits)} persistence call sites in merge path",
            "hits": hits}


def s4(root) -> dict:
    found = []
    for f in _glob(root, "main/java/**/*.java"):
        hits = _hit_lines(root, _rel(root, f), SETTINGS_ENDPOINT_RE)
        found += hits
    if not found:
        return {"status": "PENDING",
                "detail": "no /api/settings/preferences endpoint in tree"}
    files = sorted({h["file"] for h in found})
    ev = []
    for rel in files:
        ev += _hit_lines(root, rel, PRINCIPAL_RE)[:8]
        ev += _hit_lines(root, rel, BODY_OWNER_RE)[:8]
    principal = [h for h in ev if PRINCIPAL_RE.search(h["text"])]
    body_owner = [h for h in ev if BODY_OWNER_RE.search(h["text"])]
    if body_owner:
        status = "FAIL"
    elif principal:
        status = "PASS"
    else:
        status = "HEURISTIC"
    return {"status": status,
            "detail": f"endpoint files={files} principalEvidence="
                      f"{len(principal)} bodyOwnerEvidence={len(body_owner)}",
            "evidence": ev[:40]}


def s5(root, baseline) -> dict:
    cur_files = {}
    for g in ("main/resources/db/migration/**/*",
              "main/resources/schema*.sql", "main/resources/db/**/*.sql"):
        for f in _glob(root, g):
            if os.path.isfile(f):
                cur_files[_rel(root, f)] = _sha256(f)
    old_files = (baseline or {}).get("ddlFiles") or {}
    added = sorted(set(cur_files) - set(old_files))
    removed = sorted(set(old_files) - set(cur_files))
    changed = sorted(f for f in set(cur_files) & set(old_files)
                     if cur_files[f] != old_files[f])
    cur_ddl = []
    for g in ("main/resources/application*.yml",
              "main/resources/application*.yaml",
              "main/resources/application*.properties"):
        for f in _glob(root, g):
            cur_ddl += _hit_lines(root, _rel(root, f),
                                  re.compile(r"ddl-auto|ddl_auto|hbm2ddl",
                                             re.IGNORECASE))
    old_ddl = {(h["file"], h["line"]) for h in
               (baseline or {}).get("ddlAutoLines") or []}
    new_ddl = [h for h in cur_ddl if (h["file"], h["line"]) not in old_ddl]
    ok = not (added or removed or changed or new_ddl)
    return {"status": "PASS" if ok else "FAIL",
            "detail": f"ddl added={len(added)} removed={len(removed)} "
                      f"changed={len(changed)} newDdlAutoLines={len(new_ddl)}",
            "added": added, "removed": removed, "changed": changed,
            "newDdlAutoLines": new_ddl[:20],
            "baselineMissing": not bool(baseline)}


def s6(root, baseline) -> dict:
    expected = ((baseline or {}).get("chatJs") or {}).get("sha256") or \
        EXPECTED_CHAT_JS_PREFIX
    actual = _sha256(os.path.join(root, CHAT_JS))
    if actual is None:
        return {"status": "PENDING", "detail": f"{CHAT_JS} missing"}
    ok = actual == expected if len(str(expected)) == 64 \
        else actual.startswith(expected)
    return {"status": "PASS" if ok else "FAIL",
            "detail": f"chat.js sha256 {actual[:12]}... vs expected "
                      f"{str(expected)[:12]}..."}


def s7(root) -> dict:
    files = ["main/resources/static/js/chat-settings-bridge.js",
             "main/resources/static/js/settings-page.js",
             "main/resources/static/js/chat-model-picker.js",
             "main/resources/static/js/model-strategy.js",
             "main/resources/static/js/chat.js"]
    v2_hits, removals = [], []
    for rel in files:
        v2_hits += _hit_lines(root, rel, re.compile(re.escape(V2_KEY)))
        removals += _hit_lines(root, rel, V1_REMOVE_RE)
    return {"status": "HEURISTIC",
            "detail": f"v2KeySites={len(v2_hits)} "
                      f"v1RemovalSites={len(removals)}",
            "v2KeyEvidence": v2_hits[:20],
            "v1RemovalEvidence": removals[:20],
            "note": "manual review: v1 key may only be removed after a "
                    "server ACK - read the call context, not just the regex"}


def s8(root) -> dict:
    auto_hits, oauth_hits = [], []
    for g in ("main/resources/static/js/*.js",
              "main/java/**/*.java", "main/resources/application*.y*ml"):
        for f in _glob(root, g):
            rel = _rel(root, f)
            auto_hits += _hit_lines(root, rel, LLMAUTO_RE)
            oauth_hits += _hit_lines(root, rel, OAUTH_RE)
    return {"status": "HEURISTIC",
            "detail": f"llmrouter.auto sites={len(auto_hits)} "
                      f"oauth refs={len(oauth_hits)}",
            "autoEvidence": auto_hits[:25],
            "oauthEvidence": oauth_hits[:25],
            "note": "manual review: confirm auto is a logical route and "
                    "chatgpt-oauth ids are filtered out of auto candidates"}


def s9(root) -> dict:
    sites = []
    for g in ("main/resources/static/js/*.js", "main/java/**/*.java"):
        for f in _glob(root, g):
            rel = _rel(root, f)
            for h in _hit_lines(root, rel, USE_WEB_SEARCH_RE):
                t = h["text"]
                if COMMENT_LINE_RE.match(t):
                    continue
                if DERIVE_ASSIGN_RE.search(t) and \
                        SEARCH_MODE_OFF_RE.search(t) and \
                        OFF_WORD_RE.search(t):
                    sites.append(h)
    uniq = sorted({(h["file"], h["line"]) for h in sites})
    if not uniq:
        status, detail = "PENDING", "no searchMode->useWebSearch derivation found"
    elif len(uniq) == 1:
        status, detail = "PASS", "single derivation site"
    else:
        status, detail = "FAIL", f"{len(uniq)} derivation sites"
    return {"status": status, "detail": detail, "sites": sites[:40]}


def s10(root) -> dict:
    rel = "scripts/start_rag_stack.ps1"
    env_lines = _hit_lines(root, rel, HTTPS_ENV_RE)
    false_sets = [h for h in env_lines if HTTPS_SET_FALSE_RE.search(h["text"])]
    if not env_lines and not os.path.isfile(os.path.join(root, rel)):
        return {"status": "PENDING", "detail": f"{rel} missing"}
    return {"status": "PASS" if false_sets else "FAIL",
            "detail": f"explicit child AWX_OPTIONAL_HTTPS_ENABLED=false "
                      f"sites={len(false_sets)}",
            "evidence": env_lines[:30]}


def s11(root, baseline) -> dict:
    old_status = (baseline or {}).get("gitStatus")
    if old_status is None:
        return {"status": "PENDING", "detail": "baseline has no gitStatus"}
    new_status = _git_status(root)
    allowed = list(CODEX_FILES) + [ENTITY_UPP]
    foreign, changed_allowed = [], []
    for path, st in new_status.items():
        prev = old_status.get(path)
        if prev == st:
            continue
        if _covered(path, allowed, CODEX_SCOPE_GLOBS + MY_OWN_GLOBS):
            changed_allowed.append({"path": path, "was": prev, "now": st})
        else:
            foreign.append({"path": path, "was": prev, "now": st})
    return {"status": "PASS" if not foreign else "FOREIGN",
            "detail": f"foreign-changes-since-baseline={len(foreign)} "
                      f"allowed-changes={len(changed_allowed)}",
            "foreign": foreign[:60],
            "allowedChanges": changed_allowed[:60]}


def s12(root, baseline) -> dict:
    old_status = (baseline or {}).get("gitStatus") or {}
    new_status = _git_status(root)
    new_tests = []
    for path, st in new_status.items():
        if old_status.get(path) == st:
            continue
        if re.search(r"^(src/test|src/chatUiTest|scripts/test_|test/)", path) \
                and not _covered(path, [], MY_OWN_GLOBS):
            new_tests.append({"path": path, "status": st})
    unfiltered = []
    baseline_ts = None
    if baseline:
        try:
            baseline_ts = datetime.fromisoformat(
                baseline["generatedAtUtc"]).timestamp()
        except (KeyError, ValueError):
            baseline_ts = None
    tr_dir = os.path.join(root, "build", "test-results", "test")
    if baseline_ts and os.path.isdir(tr_dir):
        try:
            if os.path.getmtime(tr_dir) > baseline_ts:
                unfiltered.append({"path": "build/test-results/test",
                                   "note": "modified after baseline"})
        except OSError:
            pass
    if not new_tests:
        status = "PENDING"
    elif unfiltered:
        status = "FAIL"
    else:
        status = "PASS"
    return {"status": status,
            "detail": f"newTestFiles={len(new_tests)} "
                      f"unfilteredRunEvidence={len(unfiltered)}",
            "newTests": new_tests[:60], "unfilteredEvidence": unfiltered,
            "heuristic": "absence of a run is inferred from test-results "
                         "dir mtimes only"}


def cmd_check(args) -> int:
    root = os.path.abspath(args.root)
    baseline = None
    if args.baseline:
        try:
            baseline = json.loads(Path(args.baseline).read_text(
                encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(json.dumps({"error": f"baseline-unreadable:{e}"}))
            return 2
    checks = {"S1": s1(root), "S2": s2(root), "S3": s3(root), "S4": s4(root),
              "S5": s5(root, baseline), "S6": s6(root, baseline),
              "S7": s7(root), "S8": s8(root), "S9": s9(root),
              "S10": s10(root), "S11": s11(root, baseline),
              "S12": s12(root, baseline)}
    summary = {}
    for c in checks.values():
        summary[c["status"]] = summary.get(c["status"], 0) + 1
    report = {"schema": SCHEMA, "generatedAtUtc": _now_utc(),
              "baseline": args.baseline, "checks": checks,
              "summary": summary}
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2, ensure_ascii=False),
                       encoding="utf-8")
    print(json.dumps({"checks": {k: v["status"] for k, v in checks.items()},
                      "summary": summary}, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("baseline")
    p.add_argument("--out", required=True)
    p.add_argument("--root", default=".")
    p = sub.add_parser("check")
    p.add_argument("--baseline")
    p.add_argument("--root", default=".")
    p.add_argument("--out")
    args = ap.parse_args(argv)
    return {"baseline": cmd_baseline, "check": cmd_check}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
