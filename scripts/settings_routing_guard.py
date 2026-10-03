#!/usr/bin/env python3
"""settings_routing_guard — read-only guard for the Codex settings-routing v3 work.

Devin assist tool (lease topic devin-settings-assist). Read-only: it only runs
`git status`/`git diff`/`git show`, reads files, and optionally GETs
/api/settings. It never writes product source, never restarts the server,
never calls paid APIs.

  python -B scripts/settings_routing_guard.py --snapshot
  python -B scripts/settings_routing_guard.py --check [--baseline FILE] [--json]

--snapshot records a baseline JSON under var/settings-guard/baseline-<ts>.json:
  - the 8 foreign-session modified files (worktree sha256, HEAD sha256,
    git diff -U0 added/removed line multisets)
  - a diff index of every file with a `git diff HEAD` delta (added/removed
    line multisets) so later drift is attributable
  - sha256 fingerprints of every file under forbidden paths (security
    package, domain/entity, schema/migration, interview assets, chat.js,
    SettingsController/ModelSettingsController/PageController)
  - the full `git status --porcelain` set (new-file detection)
  - GET /api/settings response status + key NAMES (values never stored)
  - whether Codex's expected new files already exist (baselineAfterCodexStart)

--check evaluates guard items G1..G8 against the baseline and prints a table;
`--json` prints {verdict, items[]}; exit code 1 if any item is FAIL.

  G1 chat.js: no drift since baseline (numstat + sha256).
  G2 chat-ui.html: added lines vs baseline <= 2, foreign added lines preserved.
  G3 other 7 M files: baseline added/removed hunks still present.
  G4 forbidden files unchanged since baseline; no new forbidden-path files.
  G5 chat.settings.routing.enabled never defaults true (absent => PASS/absent).
  G6 new endpoint mappings stay inside /settings + /api/settings/routing/*.
  G7 no secret-looking literals in new/edited lines (file:line only).
  G8 no new @Entity / CREATE TABLE / *.sql files.
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
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9), "KST")
GUARD_DIR = Path("var") / "settings-guard"
SCHEMA = "settings-guard-baseline.v1"

# The 8 files carrying another session's uncommitted hunks (per Devin directive).
CHAT_UI = "main/resources/templates/chat-ui.html"
CHAT_JS = "main/resources/static/js/chat.js"
M_FILES = [
    CHAT_UI,
    "main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/api/ChatStreamSignalBuilder.java",
    "main/java/com/example/lms/service/chat/ChatRunRegistry.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
    "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
    "main/java/com/example/lms/api/ChatApiController.java",
]

# Files Codex is expected to create (per BRIEF.txt). Existing at snapshot time
# => baseline was taken after Codex started (recorded honestly, not hidden).
CODEX_NEW_CANDIDATES = [
    "main/resources/templates/settings.html",
    "main/resources/static/css/settings-page.css",
    "main/resources/static/js/settings-page.js",
    "main/resources/static/js/chat-settings-bridge.js",
    "main/resources/static/js/settings-routing.js",
    "main/java/com/example/lms/web/SettingsPageController.java",
    "main/java/com/example/lms/api/RoutingSettingsController.java",
    "main/java/com/example/lms/web/RoutingSettingsController.java",
]
CODEX_NEW_GLOBS = [
    "main/java/com/example/lms/**/RoutingProfile*.java",
    "main/java/com/example/lms/**/RoutingSettings*.java",
    "main/java/com/example/lms/**/RunRoutingSnapshot*.java",
    "main/java/com/example/lms/**/RoutingOutcome*.java",
]

# G4 forbidden targets.
FORBIDDEN_FILES = {
    "main/java/com/example/lms/api/SettingsController.java",
    "main/java/com/example/lms/api/ModelSettingsController.java",
    "main/java/com/example/lms/web/PageController.java",
    CHAT_JS,
}
FORBIDDEN_PREFIXES = (
    "main/java/com/example/lms/security/",
    "main/resources/db/migration/",
    "main/resources/db/ddl/",
    "main/resources/static/assets/interview/",
)
DOMAIN_ENTITY_RE = re.compile(r"^main/java/.*/(domain|entity)/")
SQL_RE = re.compile(r"\.sql$", re.IGNORECASE)
SCHEMA_NAME_RE = re.compile(r"(^|/)schema[^/]*\.(sql|ddl)$", re.IGNORECASE)

FLAG_KEY = "chat.settings.routing.enabled"
MAPPING_RE = re.compile(
    r"@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\b")
PATH_LIT_RE = re.compile(r'"(/[^"]*)"')
ENTITY_RE = re.compile(r"@Entity\b")
CREATE_TABLE_RE = re.compile(r"\bcreate\s+table\b", re.IGNORECASE)

SECRET_RES = [
    ("openai-sk", re.compile(r"sk-[A-Za-z0-9_\-]{12,}")),
    ("aws-akid", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("bearer", re.compile(r"(?i)bearer\s+[A-Za-z0-9._~\-]{12,}")),
    ("key-assign", re.compile(
        r"(?i)(api[_-]?key|apikey|secret|access[_-]?token|client[_-]?secret)"
        r"\s*[:=]\s*[\"']?([A-Za-z0-9._~/\-]{8,})")),
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
]
SECRET_VALUE_OK = re.compile(
    r"(?i)(\$\{|getenv|env\.|example|sample|dummy|fake|mock|test|your[-_]|"
    r"changeme|placeholder|xxx+|redact|<|>|\*+|none|null|true|false)")


def _rel(p: Path, root: Path) -> str:
    return str(p.relative_to(root)).replace("\\", "/")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str | None:
    try:
        return sha256_bytes(path.read_bytes())
    except OSError:
        return None


def default_git(root: Path):
    def git_fn(args, binary=False):
        proc = subprocess.run(
            ["git"] + args, cwd=str(root), capture_output=True, check=False)
        if binary:
            return proc.stdout
        return proc.stdout.decode("utf-8", errors="replace")
    return git_fn


def default_fetch(url: str, timeout: int = 10):
    req = urllib.request.Request(
        url, method="GET",
        headers={"User-Agent": "devin-settings-guard/1.0 (read-only)"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(1024 * 1024).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read(1024 * 1024).decode("utf-8", "replace")
        except OSError:
            body = ""
        return exc.code, body
    except Exception as exc:  # noqa: BLE001 - fold every transport error
        return None, f"{type(exc).__name__}: {exc}"


def parse_u0_diff(diff_text: str):
    """Return (added, removed) line-text Counters from a `git diff -U0` output."""
    added, removed = Counter(), Counter()
    for line in diff_text.splitlines():
        if line.startswith("+++") or line.startswith("---"):
            continue
        if line.startswith("+"):
            added[line[1:]] += 1
        elif line.startswith("-"):
            removed[line[1:]] += 1
    return added, removed


def diff_index(git_fn, pathspec=None):
    """Per-file {added, removed} multisets for `git diff HEAD -U0`."""
    args = ["diff", "HEAD", "-U0", "--no-color"]
    if pathspec:
        args += ["--"] + list(pathspec)
    text = git_fn(args)
    index = {}
    current = None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            m = re.match(r"diff --git a/(.+?) b/(.+)$", line)
            current = (m.group(2) if m else line).strip()
            index.setdefault(current, {"added": Counter(), "removed": Counter()})
        elif current and line.startswith("+") and not line.startswith("+++"):
            index[current]["added"][line[1:]] += 1
        elif current and line.startswith("-") and not line.startswith("---"):
            index[current]["removed"][line[1:]] += 1
    return index


def numstat_head(git_fn, path):
    out = git_fn(["diff", "HEAD", "--numstat", "--", path])
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 3 and parts[2].strip() == path:
            return f"{parts[0]}\t{parts[1]}"
    return ""


def porcelain(git_fn):
    out = git_fn(["status", "--porcelain"])
    rows = []
    for line in out.splitlines():
        if len(line) >= 4:
            rows.append({"xy": line[:2], "path": line[3:].strip()})
    return rows


def head_sha256(root, git_fn, rel):
    data = git_fn(["show", f"HEAD:{rel}"], binary=True)
    if isinstance(data, str):
        data = data.encode()
    return sha256_bytes(data) if data else None


def is_forbidden_path(rel: str) -> bool:
    rel = rel.replace("\\", "/")
    if rel in FORBIDDEN_FILES:
        return True
    if any(rel.startswith(p) for p in FORBIDDEN_PREFIXES):
        return True
    if DOMAIN_ENTITY_RE.match(rel):
        return True
    if rel.startswith("main/resources/") and (SQL_RE.search(rel) or SCHEMA_NAME_RE.search(rel)):
        return True
    return False


def enumerate_forbidden(root: Path):
    out = {}
    for rel in sorted(FORBIDDEN_FILES):
        p = root / rel
        out[rel] = sha256_file(p) if p.exists() else None
    for base in root.glob("main/**/*"):
        if not base.is_file():
            continue
        rel = _rel(base, root)
        if is_forbidden_path(rel) and rel not in out:
            out[rel] = sha256_file(base)
    return out


def find_codex_new_files(root: Path):
    found = []
    for rel in CODEX_NEW_CANDIDATES:
        p = root / rel
        if p.exists():
            found.append({"path": rel, "mtime": datetime.fromtimestamp(
                p.stat().st_mtime, KST).isoformat(timespec="seconds")})
    for pattern in CODEX_NEW_GLOBS:
        for p in root.glob(pattern):
            if p.is_file():
                rel = _rel(p, root)
                if all(f["path"] != rel for f in found):
                    found.append({"path": rel, "mtime": datetime.fromtimestamp(
                        p.stat().st_mtime, KST).isoformat(timespec="seconds")})
    return sorted(found, key=lambda f: f["path"])


def api_settings_keys(fetch_fn, url="http://127.0.0.1:18180/api/settings"):
    status, body = fetch_fn(url)
    row = {"url": url, "status": status, "keys": None, "error": None}
    if status is None:
        row["error"] = str(body)[:200]
        return row
    try:
        data = json.loads(body)
        if isinstance(data, dict):
            row["keys"] = sorted(data.keys())
        else:
            row["keys"] = f"<{type(data).__name__}>"
    except (ValueError, TypeError):
        row["keys"] = "<non-json>"
    return row


def snapshot(root: Path, git_fn, fetch_fn=default_fetch, out_dir: Path | None = None):
    idx = diff_index(git_fn)
    m_files = {}
    for rel in M_FILES:
        p = root / rel
        entry = idx.get(rel, {"added": Counter(), "removed": Counter()})
        m_files[rel] = {
            "exists": p.exists(),
            "worktreeSha256": sha256_file(p),
            "headSha256": head_sha256(root, git_fn, rel),
            "numstat": numstat_head(git_fn, rel),
            "addedLines": sorted(entry["added"].keys()),
            "removedLines": sorted(entry["removed"].keys()),
            "addedCounts": dict(entry["added"]),
            "removedCounts": dict(entry["removed"]),
        }
    codex_new = find_codex_new_files(root)
    base = {
        "schema": SCHEMA,
        "createdAtKst": datetime.now(KST).isoformat(timespec="seconds"),
        "baselineAfterCodexStart": bool(codex_new),
        "codexNewFilesPresent": codex_new,
        "mFiles": m_files,
        "chatJsNumstat": numstat_head(git_fn, CHAT_JS),
        "chatJsSha256": sha256_file(root / CHAT_JS),
        "diffIndex": {
            path: {"added": dict(v["added"]), "removed": dict(v["removed"])}
            for path, v in sorted(idx.items())
        },
        "forbidden": enumerate_forbidden(root),
        "porcelain": sorted(r["path"] for r in porcelain(git_fn)),
        "apiSettings": api_settings_keys(fetch_fn) if fetch_fn else None,
    }
    out_dir = out_dir or (root / GUARD_DIR)
    out_dir.mkdir(parents=True, exist_ok=True)
    name = "baseline-" + datetime.now(KST).strftime("%Y%m%d-%H%M") + ".json"
    out_path = out_dir / name
    out_path.write_text(json.dumps(base, ensure_ascii=False, indent=1), encoding="utf-8")
    return base, out_path


def _file_lines(root: Path, rel: str):
    try:
        return (root / rel).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []


def _counter_sub(cur: Counter, base: Counter) -> Counter:
    out = Counter()
    for line, n in cur.items():
        extra = n - base.get(line, 0)
        if extra > 0:
            out[line] = extra
    return out


def _hunk_preserved(root, baseline, rel, cur_entry):
    """Baseline added lines still in file; baseline removed still removed."""
    b = baseline["mFiles"].get(rel) or {}
    base_added = Counter(b.get("addedCounts", {}))
    base_removed = Counter(b.get("removedCounts", {}))
    file_counter = Counter(_file_lines(root, rel))
    missing_added = _counter_sub(base_added, file_counter)
    unremoved = _counter_sub(base_removed, cur_entry["removed"])
    return {
        "missingAddedLines": sorted(missing_added.keys()),
        "baselineRemovedLinesGone": sorted(unremoved.keys()),
        "ok": not missing_added and not unremoved,
    }


def check(root: Path, baseline: dict, git_fn, scan_codex_files: bool = False):
    items = []
    idx_now = diff_index(git_fn)

    def item(gid, name, verdict, detail):
        items.append({"id": gid, "name": name, "verdict": verdict, "detail": detail})

    # -- G1 chat.js untouched since baseline ----------------------------------
    cur_sha = sha256_file(root / CHAT_JS)
    cur_numstat = numstat_head(git_fn, CHAT_JS)
    base_sha = baseline.get("chatJsSha256")
    base_numstat = baseline.get("chatJsNumstat", "")
    g1_ok = (cur_sha == base_sha) and (cur_numstat == base_numstat)
    item("G1", "chat.js no drift", "PASS" if g1_ok else "FAIL", {
        "baselineNumstat": base_numstat or "(empty)",
        "currentNumstat": cur_numstat or "(empty)",
        "literalNumstatEmpty": cur_numstat == "",
        "sha256Changed": cur_sha != base_sha,
    })

    # -- G2 chat-ui.html <= 2 new added lines, foreign hunks preserved ---------
    b_ui = baseline["mFiles"].get(CHAT_UI, {})
    cur_ui = idx_now.get(CHAT_UI, {"added": Counter(), "removed": Counter()})
    base_added_ui = Counter(b_ui.get("addedCounts", {}))
    new_added_ui = _counter_sub(cur_ui["added"], base_added_ui)
    preserved_ui = _hunk_preserved(root, baseline, CHAT_UI, cur_ui)
    n_new = sum(new_added_ui.values())
    g2_ok = n_new <= 2 and preserved_ui["ok"]
    item("G2", "chat-ui.html <=2 new lines", "PASS" if g2_ok else "FAIL", {
        "newAddedLines": n_new,
        "newAddedText": sorted(new_added_ui.keys())[:10],
        "foreignAddedPreserved": preserved_ui["ok"],
        "missingForeignAdded": preserved_ui["missingAddedLines"][:5],
    })

    # -- G3 other 7 M files hunks preserved ------------------------------------
    g3_bad = []
    for rel in M_FILES:
        if rel == CHAT_UI:
            continue
        cur_entry = idx_now.get(rel, {"added": Counter(), "removed": Counter()})
        pres = _hunk_preserved(root, baseline, rel, cur_entry)
        if not pres["ok"]:
            g3_bad.append({"path": rel,
                           "missingAdded": pres["missingAddedLines"][:5],
                           "removedLinesGone": pres["baselineRemovedLinesGone"][:5]})
    item("G3", "other M-file hunks preserved", "PASS" if not g3_bad else "FAIL",
         {"violations": g3_bad, "checked": len(M_FILES) - 1})

    # -- G4 forbidden files ----------------------------------------------------
    g4_bad = []
    for rel, old_sha in (baseline.get("forbidden") or {}).items():
        now_sha = sha256_file(root / rel)
        if old_sha is not None and now_sha != old_sha:
            g4_bad.append({"path": rel, "kind": "changed" if now_sha else "deleted"})
    base_paths = set(baseline.get("porcelain") or [])
    for row in porcelain(git_fn):
        p = row["path"]
        if p not in base_paths and is_forbidden_path(p):
            g4_bad.append({"path": p, "kind": "new-forbidden-file"})
    item("G4", "forbidden files unchanged", "PASS" if not g4_bad else "FAIL",
         {"violations": g4_bad, "fingerprinted": len(baseline.get("forbidden") or {})})

    # -- delta added lines per file (drift since baseline) ---------------------
    base_idx = baseline.get("diffIndex") or {}
    delta_added = {}   # path -> Counter of newly added lines
    for path, ent in idx_now.items():
        base_ent = base_idx.get(path, {"added": {}, "removed": {}})
        d = _counter_sub(ent["added"], Counter(base_ent["added"]))
        if d:
            delta_added[path] = d
    base_porcelain = set(baseline.get("porcelain") or [])
    new_paths = [r["path"] for r in porcelain(git_fn) if r["path"] not in base_porcelain]
    new_file_text = {}
    for rel in new_paths:
        p = root / rel
        if p.is_file() and p.stat().st_size <= 2 * 1024 * 1024:
            try:
                new_file_text[rel] = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                pass
    # Baseline taken after Codex started grandfathers his earliest files; the
    # --scan-codex flag re-includes them so G6/G7/G8 still see their content.
    if scan_codex_files:
        for f in baseline.get("codexNewFilesPresent") or []:
            rel = f["path"] if isinstance(f, dict) else f
            if rel in new_file_text:
                continue
            p = root / rel
            if p.is_file() and p.stat().st_size <= 2 * 1024 * 1024:
                try:
                    new_file_text[rel] = p.read_text(encoding="utf-8",
                                                     errors="replace")
                except OSError:
                    pass

    def each_new_line(scope=("main/", "src/", "app/")):
        """yield (path, lineno, text) for post-baseline additions incl. new
        files, restricted to repo subtrees where product/test code lives
        (keeps Devin's own tools/tests/rules and docs out of the scan)."""
        for path, counter in delta_added.items():
            if not path.startswith(scope):
                continue
            for line in counter:
                yield path, None, line
        for path, text in new_file_text.items():
            if not path.startswith(scope):
                continue
            for i, line in enumerate(text.splitlines(), 1):
                yield path, i, line

    # -- G5 flag default -------------------------------------------------------
    g5 = {"properties": [], "valueDefaults": [], "state": "absent"}
    for prop in sorted(root.glob("main/resources/application*.properties")):
        try:
            for i, line in enumerate(prop.read_text(encoding="utf-8", errors="replace")
                                     .splitlines(), 1):
                s = line.strip()
                if s.startswith("#") or FLAG_KEY not in s:
                    continue
                m = re.match(r"[^=]*=\s*(\S+)", s)
                val = (m.group(1) if m else "?").lower()
                g5["properties"].append(
                    {"file": _rel(prop, root), "line": i, "value": val})
        except OSError:
            continue
    flag_true = re.compile(re.escape(FLAG_KEY) + r"[^\"'\n]*[:=]\s*\"?true", re.IGNORECASE)
    for path, i, line in each_new_line(("main/",)):
        if FLAG_KEY in line and flag_true.search(line):
            g5["valueDefaults"].append({"file": path, "line": i})
    g5_fail = (any(p["value"].startswith("true") for p in g5["properties"])
               or bool(g5["valueDefaults"]))
    if not g5["properties"] and not g5["valueDefaults"]:
        g5["state"] = "absent"
    elif g5_fail:
        g5["state"] = "true-found"
    else:
        g5["state"] = "false-only"
    item("G5", "routing flag default not true", "FAIL" if g5_fail else "PASS", g5)

    # -- G6 endpoint scope -------------------------------------------------------
    # Real endpoints exist only under main/; strings in tests/scripts are data.
    # Method-level leaf mappings ("/read") resolve against the file's
    # class-level @RequestMapping base — the base itself may be unchanged
    # code that never appears in the added-lines delta.
    def class_base(path):
        try:
            text = (root / path).read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""
        m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?\s*'
                      r'[{\[]?\s*"(/[^"]*)"', text)
        return m.group(1).rstrip("/") if m else ""

    g6_bad, g6_other, g6_ok_paths = [], [], []
    for path, i, line in each_new_line(("main/",)):
        if not MAPPING_RE.search(line):
            continue
        for lit in PATH_LIT_RE.findall(line):
            eff = lit
            if MAPPING_RE.search(line).group(1) != "RequestMapping" \
                    and lit.startswith("/") and not lit.startswith(
                        ("/api/", "/settings")):
                eff = class_base(path) + lit
            if eff == "/api/settings/routing" or eff.startswith(
                    "/api/settings/routing/"):
                g6_ok_paths.append({"file": path, "line": i, "path": eff})
            elif eff == "/settings" or eff.startswith("/settings/"):
                g6_ok_paths.append({"file": path, "line": i, "path": eff})
            elif eff == "/api/settings" or eff.startswith("/api/settings/"):
                g6_bad.append({"file": path, "line": i, "path": eff})
            else:
                g6_other.append({"file": path, "line": i, "path": eff})
    g6_verdict = "FAIL" if g6_bad else ("WARN" if g6_other else "PASS")
    item("G6", "new mappings inside allowed scope", g6_verdict, {
        "forbidden": g6_bad, "otherMappings": g6_other, "allowed": g6_ok_paths})

    # -- G7 secret-looking literals ---------------------------------------------
    g7_hits = []
    for path, i, line in each_new_line():
        if not path.startswith(("main/", "src/", "app/")):
            continue
        for name, rx in SECRET_RES:
            m = rx.search(line)
            if not m:
                continue
            val = m.group(m.lastindex) if (m.lastindex and name == "key-assign") else m.group(0)
            if SECRET_VALUE_OK.search(val):
                continue
            g7_hits.append({"file": path, "line": i, "pattern": name})
    item("G7", "no secret-looking literals", "PASS" if not g7_hits else "FAIL",
         {"hits": g7_hits})

    # -- G8 new tables / DSL -----------------------------------------------------
    g8_bad = []
    for rel in new_paths:
        if rel.startswith("main/") and SQL_RE.search(rel):
            g8_bad.append({"path": rel, "kind": "new-sql-file"})
    for path, i, line in each_new_line(("main/",)):
        if ENTITY_RE.search(line):
            g8_bad.append({"path": path, "line": i, "kind": "@Entity"})
        if CREATE_TABLE_RE.search(line):
            g8_bad.append({"path": path, "line": i, "kind": "CREATE TABLE"})
    item("G8", "no new @Entity/CREATE TABLE/*.sql", "PASS" if not g8_bad else "FAIL",
         {"hits": g8_bad})

    verdict = "FAIL" if any(i["verdict"] == "FAIL" for i in items) else (
        "WARN" if any(i["verdict"] == "WARN" for i in items) else "PASS")
    return {"verdict": verdict, "items": items,
            "checkedAtKst": datetime.now(KST).isoformat(timespec="seconds"),
            "baselineFile": baseline.get("_file")}


def latest_baseline(out_dir: Path) -> Path | None:
    files = sorted(out_dir.glob("baseline-*.json"))
    return files[-1] if files else None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--snapshot", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--baseline", help="baseline JSON path (default: latest)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    ap.add_argument("--no-live", action="store_true",
                    help="skip the GET /api/settings baseline capture")
    ap.add_argument("--scan-codex", action="store_true",
                    help="also scan files recorded as codexNewFilesPresent "
                         "(baseline was taken after Codex started)")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    git_fn = default_git(root)

    if args.snapshot:
        fetch_fn = None if args.no_live else default_fetch
        base, out_path = snapshot(root, git_fn, fetch_fn)
        print(f"baseline written: {out_path} "
          f"({out_path.stat().st_size} bytes, "
          f"mFiles={len(base['mFiles'])}, "
          f"forbidden={len(base['forbidden'])}, "
          f"baselineAfterCodexStart={base['baselineAfterCodexStart']})")
        return 0

    if args.check:
        b_path = Path(args.baseline) if args.baseline else latest_baseline(root / GUARD_DIR)
        if not b_path or not b_path.exists():
            print("no baseline found — run --snapshot first", file=sys.stderr)
            return 2
        baseline = json.loads(b_path.read_text(encoding="utf-8"))
        baseline["_file"] = str(b_path)
        result = check(root, baseline, git_fn,
                       scan_codex_files=args.scan_codex)
        if args.as_json:
            print(json.dumps(result, ensure_ascii=False, indent=1))
        else:
            print(f"guard verdict: {result['verdict']}  baseline: {b_path.name}")
            for it in result["items"]:
                print(f"  {it['id']:3} {it['verdict']:4}  {it['name']}  {it['detail']}")
        return 1 if result["verdict"] == "FAIL" else 0

    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
