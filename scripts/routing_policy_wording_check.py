#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Retired-phrase checker for demo-1 routing/fallback wording.

Scans rule/skill/docs surfaces for wording that was retired by the user
decision of 2026-10-02 (product main chat = API/OAuth-first, local Ollama last)
and related housekeeping retirements. Phrase inventory lives in
configs/retired-phrases.json — add entries there, never contort phrasing to
slip past the scan.

Exit codes: 0 = clean, 3 = retired phrase found, 4 = config/input error.
Lines that merely explain a retirement (allow markers like 폐기/retired/금지)
are skipped by rule, not by evasion.
"""
import argparse
import fnmatch
import glob
import json
import os
import re
import sys

SCHEMA = "awx.routing-wording-check.v1"

# Default scan surface (relative POSIX globs; ** requires recursive glob).
DEFAULT_TARGET_GLOBS = [
    "AGENTS.md",
    "docs/agents-rules/*.md",
    ".agents/skills/**/SKILL.md",
    ".agents/skills/**/skill.md",
    ".agents/skills/**/references/*.md",
    ".windsurf/rules/*.md",
    ".devin/rules/*.md",
    ".grok/rules/*.md",
]

# Path segments that mark a file as backup/archive residue — excluded from the
# primary scan and listed separately under --include-backups.
BACKUP_DIR_PARTS = {
    "archive", "archives", "_archive", "__archive__",
    "backup", "backups", "_backup", "_quarantine", "quarantine",
}


def norm_rel(path):
    return str(path).replace(os.sep, "/")


def is_backup_path(rel):
    parts = [p for p in norm_rel(rel).split("/") if p]
    if not parts:
        return False
    name = parts[-1].lower()
    if ".bak-" in name or name.startswith("bak-"):
        return True
    return any(p.lower() in BACKUP_DIR_PARTS for p in parts[:-1])


def scope_match(rel, globs):
    rel_l = norm_rel(rel).lower()
    base = rel_l.split("/")[-1]
    for g in globs:
        g_l = g.lower()
        # "**/" prefix = "any depth including none": also try the stripped form.
        stripped = g_l[3:] if g_l.startswith("**/") else g_l
        if (fnmatch.fnmatch(rel_l, g_l) or fnmatch.fnmatch(rel_l, stripped)
                or fnmatch.fnmatch(base, g_l)
                or fnmatch.fnmatch(base, stripped)):
            return True
    return False


def load_config(path):
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            raw = fh.read()
    except OSError:
        return None, "config-unreadable:%s" % path
    try:
        cfg = json.loads(raw)
    except ValueError:
        return None, "config-invalid-json:%s" % path
    phrases = cfg.get("phrases") if isinstance(cfg, dict) else None
    if not isinstance(phrases, list) or not phrases:
        return None, "config-empty-phrases:%s" % path
    for i, p in enumerate(phrases):
        if not isinstance(p, dict) or not isinstance(p.get("pattern"), str) \
                or not p["pattern"]:
            return None, "config-bad-entry:%s#%d" % (path, i)
        if p.get("type", "literal") not in ("literal", "regex"):
            return None, "config-bad-type:%s#%d" % (path, i)
        if p.get("type") == "regex":
            try:
                re.compile(p["pattern"])
            except re.error:
                return None, "config-bad-regex:%s#%d" % (path, i)
    return cfg, None


def backup_glob(g):
    """Variant of a target glob that also reaches backup residue: descend into
    subdirs (archive/) and accept extension-suffixed names (*.md.bak-*)."""
    head, sep, base = g.rpartition("/")
    if not sep:
        return base + "*"
    if "**" in base:
        return head + "/" + base + "*"
    return head + "/**/" + base + "*"


def collect_files(root, target_globs):
    seen, files = set(), []
    for g in target_globs + [backup_glob(g) for g in target_globs]:
        for hit in glob.glob(g, root_dir=root, recursive=True):
            full = os.path.join(root, hit)
            if not os.path.isfile(full):
                continue
            key = os.path.normcase(os.path.normpath(hit))
            if key in seen:
                continue
            seen.add(key)
            files.append(norm_rel(os.path.normpath(hit)))
    files.sort(key=str.lower)
    return files


def iter_findings(path, rel, phrases, default_allow):
    try:
        with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return
    lines = text.splitlines()
    for idx, line in enumerate(lines):
        lineno = idx + 1
        for p in phrases:
            if not scope_match(rel, p.get("scope_globs") or ["**/*"]):
                continue
            pat = p["pattern"]
            if p.get("type", "literal") == "regex":
                try:
                    hit = re.search(pat, line) is not None
                except re.error:
                    continue
            elif p.get("case_insensitive"):
                hit = pat.lower() in line.lower()
            else:
                hit = pat in line
            if not hit:
                continue
            # Allow markers suppress a finding when they appear in the same
            # hard-wrapped sentence: the match line plus the adjacent line on
            # either side (docs wrap mid-sentence, so "...은 폐기" often lands
            # on the next visual line).
            window = "\n".join(lines[max(0, idx - 1):idx + 2]).lower()
            allow = default_allow + (p.get("allow_if_line_contains") or [])
            if any(a.lower() in window for a in allow if a):
                continue
            yield {"file": rel, "line": lineno, "id": p.get("id", "?"),
                   "pattern": pat,
                   "hint": p.get("replacement_hint", "")}


def main(argv=None):
    ap = argparse.ArgumentParser(description="Retired-phrase wording check "
                                 "(routing/fallback policy).")
    ap.add_argument("--root", default=".")
    ap.add_argument("--config", default="configs/retired-phrases.json")
    ap.add_argument("--target-glob", action="append", default=None,
                    help="override scan globs (repeatable)")
    ap.add_argument("--include-backups", action="store_true",
                    help="list *.bak-*/archive-dir hits separately (never "
                         "affects the exit code)")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args(argv)

    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except Exception:
        pass

    root = args.root
    if not os.path.isdir(root):
        print("ERROR root-not-a-directory:%s" % root)
        return 4
    cfg_path = args.config
    if not os.path.isabs(cfg_path):
        cfg_path = os.path.join(root, cfg_path)
    cfg, err = load_config(cfg_path)
    if err:
        print("ERROR %s" % err)
        return 4

    phrases = cfg["phrases"]
    default_allow = [a for a in cfg.get("default_allow_if_line_contains", [])
                     if isinstance(a, str)]
    globs = args.target_glob or DEFAULT_TARGET_GLOBS
    files = collect_files(root, globs)

    findings, backup_findings, backup_skipped = [], [], 0
    for rel in files:
        if is_backup_path(rel):
            backup_skipped += 1
            if args.include_backups:
                backup_findings.extend(
                    iter_findings(os.path.join(root, rel), rel, phrases,
                                  default_allow))
            continue
        findings.extend(iter_findings(os.path.join(root, rel), rel, phrases,
                                      default_allow))

    report = {
        "schemaVersion": SCHEMA,
        "root": os.path.abspath(root),
        "config": norm_rel(cfg_path),
        "filesScanned": len(files) - backup_skipped,
        "backupFilesExcluded": backup_skipped,
        "findingCount": len(findings),
        "findings": findings,
        "backupFindings": backup_findings if args.include_backups else [],
    }
    if args.as_json:
        print(json.dumps(report, ensure_ascii=False, indent=1))
    else:
        for f in findings:
            print("%s:%d: %s [%s]" % (f["file"], f["line"], f["id"],
                                      f["pattern"]))
            if f["hint"]:
                print("  hint: %s" % f["hint"])
        if args.include_backups:
            for f in backup_findings:
                print("BACKUP %s:%d: %s [%s]" % (f["file"], f["line"],
                                               f["id"], f["pattern"]))
        print("SUMMARY files_scanned=%d findings=%d backups_excluded=%d"
              % (report["filesScanned"], len(findings), backup_skipped))
    return 3 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
