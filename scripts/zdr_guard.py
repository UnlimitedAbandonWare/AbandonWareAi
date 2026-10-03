#!/usr/bin/env python3
"""zdr_guard.py - DEMO1-DEVIN-JEV-NO-ZDR-STANDING-20260929 (B-4 recurrence guard).

Fails when product code, config, scripts, or agent docs hardcode a Zero Data
Retention request flag with a literal `true` - i.e. ZDR sent without a config
gate. Rule SSOT: docs/API_ROUTING_SPEC.md "Vercel AI Gateway - Jev" section
(ZDR default OFF; Hobby plan rejects with 403 plan_gate).

stdlib only; no Java/Gradle needed. Escaped literals (e.g. an assertion on the
wire string `\"zeroDataRetention\":true` inside a Java string) describe wire
content - they do not construct a request and do not count.

Usage: python -B scripts/zdr_guard.py [--vocab] [--strict] [--json] [--root <dir>]
Exit: 0 clean, 1 --vocab violation(s) under --strict, 2 usage/spec-parse error,
3 hardcoded-true hit(s). --vocab without --strict is report-only (exit stays
governed by the ZDR scan).

--vocab additionally guards the retired Jev reason/config names
(DEMO1-DEVIN-JEV-VOCAB-ALIGN-ASSIST-20260929). The *correct* vocabulary is
parsed from docs/API_ROUTING_SPEC.md section 6 ("Reason vocabulary" line and
the `config (\`key\`)` token) — never hardcoded — so doc edits move the guard.
Scope is Jev-related files only (path contains "jev", or content references
JevGatewayClient/JevDecisionAdvisor/jevReason/jev.gateway/demo.jev/DEMO_JEV);
a bare "forbidden" is a shared word, so only quoted "forbidden"/'forbidden'
literals count. Lines marked `jev-vocab: legacy-alias` and files marked
`jev-vocab: allow-file` (first 30 lines) are tolerated for the transition.
Excluded: docs/PROJECT_STATUS.md, agent-prompts/, toss/, data/, and record
docs (docs/diagnostics|codex|superpowers).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

SELF = Path(__file__).resolve()
DEFAULT_ROOT = SELF.parent.parent

# Product code + config + scripts + agent directives (the surfaces the ZDR
# rule applies to). Secrets, git internals and build output are never scanned.
SCAN_DIRS = ("main", "src", "configs", "scripts", "agent-prompts",
             "docs", ".agents", ".grok")
SUFFIXES = {".java", ".kt", ".kts", ".mjs", ".js", ".ts", ".py", ".ps1",
            ".yml", ".yaml", ".properties", ".json", ".toml", ".md"}
SKIP_PARTS = {".git", ".secrets", "build", "bin", "node_modules",
              "__pycache__", "out", "var", "data", "__patch_drop__"}

_NAME = r"zeroDataRetention|zero_data_retention|zero-data-retention"
# name immediately associated with a literal `true` via ':', '=' or ',' -
# optionally wrapped/separated by quotes (JS colon, YAML/properties equals,
# Java Map.of/put comma pair, JSON pair). `\"` before the name means the text
# is inside an escaped string literal (wire description, not construction).
LITERAL_TRUE = re.compile(
    r'(?<!\\")(?P<name>' + _NAME + r')["\'`]?\s*[:,=]\s*["\'`]?true\b',
    re.IGNORECASE)
ANY_MENTION = re.compile(_NAME, re.IGNORECASE)
# Explicit exemption: file-level marker in the header block (loopback fixtures,
# wire-format docs/tests). Must name the reason next to the marker.
ALLOW_FILE = re.compile(r"zdr-guard:\s*allow-file", re.IGNORECASE)
ALLOW_LINE = re.compile(r"zdr-guard:\s*allow\b", re.IGNORECASE)

# ---- Jev vocabulary drift guard (--vocab) ----------------------------------
# The denylist is the retired names themselves; the *expected* names come from
# the spec parse below, so editing the SSOT moves the guard automatically.
SPEC_REL = Path("docs") / "API_ROUTING_SPEC.md"
VOCAB_REASON_RE = re.compile(r"→\s*`([^`\s]+)`")
VOCAB_CONFIG_RE = re.compile(r"config\s*\(\s*`([^`]+)`")
VOCAB_HEADING_RE = re.compile(r"^#{1,4}\s")
VOCAB_RULE_RE = re.compile(r"^---\s*$")
# quoted dotted keys ending in zero-data-retention are checked against the
# spec key; a bare "zeroDataRetention" wire field has no dot and never counts.
VOCAB_QUOTED_ZDR_KEY = re.compile(
    r"(['\"])([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+\.zero-data-retention)\1")
LEGACY_RES = (
    ("key_invalid_or_expired", re.compile(r"\bkey_invalid_or_expired\b")),
    ("forbidden(quoted)", re.compile(r"(['\"])forbidden\1")),
    ("demo.jev.zero-data-retention",
     re.compile(r"\bdemo\.jev\.zero-data-retention\b")),
)
JEV_PATH_RE = re.compile(r"jev", re.IGNORECASE)
JEV_CONTENT_RE = re.compile(
    r"JevGatewayClient|JevDecisionAdvisor|jevReason|jev\.gateway|demo\.jev"
    r"|DEMO_JEV")
VOCAB_SKIP_PARTS = SKIP_PARTS | {"agent-prompts", "toss"}
VOCAB_SKIP_PREFIXES = ("docs/diagnostics/", "docs/codex/", "docs/superpowers/")
VOCAB_SKIP_FILES = {"docs/project_status.md"}
VOCAB_ALIAS_LINE = re.compile(r"jev-vocab:\s*legacy-alias", re.IGNORECASE)
VOCAB_ALLOW_FILE = re.compile(r"jev-vocab:\s*allow-file", re.IGNORECASE)
YAML_KEY_RE = re.compile(r"^(\s*)([A-Za-z0-9_-]+)\s*:")


class VocabSpecError(Exception):
    """docs/API_ROUTING_SPEC.md §6 parse failure -> exit 2 (tool error)."""


def _vocab_section(text):
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if VOCAB_HEADING_RE.match(line) and "vercel ai gateway" in line.lower():
            start = i
            break
    if start is None:
        return None
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if VOCAB_HEADING_RE.match(lines[i]) or VOCAB_RULE_RE.match(lines[i]):
            end = i
            break
    return lines[start:end]


def parse_vocab_spec(root):
    spec = root / SPEC_REL
    try:
        text = spec.read_text(encoding="utf-8")
    except OSError as exc:
        raise VocabSpecError("spec-missing:%s" % exc)
    section = _vocab_section(text)
    if not section:
        raise VocabSpecError("spec-section-missing")
    vocab_idx = next(
        (i for i, l in enumerate(section)
         if "reason vocabulary" in l.lower()), None)
    reasons = []
    if vocab_idx is not None:
        # collect the bullet plus its indented continuation lines
        collected = [section[vocab_idx]]
        for l in section[vocab_idx + 1:]:
            if not l.strip() or VOCAB_HEADING_RE.match(l) \
                    or VOCAB_RULE_RE.match(l) or re.match(r"^\s*-\s", l):
                break
            collected.append(l)
        reasons = VOCAB_REASON_RE.findall("\n".join(collected))
    if len(reasons) < 3:
        raise VocabSpecError("reason-vocabulary-parse")
    match = VOCAB_CONFIG_RE.search("\n".join(section))
    key = match.group(1) if match else None
    if not key or not key.endswith("zero-data-retention") \
            or key.startswith("demo.jev"):
        raise VocabSpecError("config-key-parse")
    return {"reasons": reasons, "configKey": key,
            "spec": str(spec.relative_to(root)).replace("\\", "/")}


def _is_jev_file(rel, text):
    return bool(JEV_PATH_RE.search(rel) or JEV_CONTENT_RE.search(text))


def _iter_vocab_files(root):
    for top in SCAN_DIRS:
        base = root / top
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in SUFFIXES:
                continue
            rel = str(path.relative_to(root)).replace("\\", "/")
            if set(path.relative_to(root).parts) & VOCAB_SKIP_PARTS:
                continue
            if rel.lower() in VOCAB_SKIP_FILES:
                continue
            if rel.startswith(VOCAB_SKIP_PREFIXES):
                continue
            yield path, rel


def scan_vocab(root, config_key):
    hits, jev_files, scanned = [], [], 0
    for path, rel in _iter_vocab_files(root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        scanned += 1
        if VOCAB_ALLOW_FILE.search("\n".join(text.splitlines()[:30])):
            continue
        if not _is_jev_file(rel, text):
            continue
        jev_files.append(rel)
        is_yaml = path.suffix.lower() in (".yml", ".yaml")
        stack = []  # (indent, key) nesting for YAML dotted-path checks
        for lineno, line in enumerate(text.splitlines(), 1):
            if is_yaml:
                m = YAML_KEY_RE.match(line)
                if m and not line.lstrip().startswith("-"):
                    indent, key = len(m.group(1)), m.group(2)
                    while stack and stack[-1][0] >= indent:
                        stack.pop()
                    dotted = ".".join(k for _, k in stack + [(indent, key)])
                    stack.append((indent, key))
                    if key == "zero-data-retention" and dotted != config_key \
                            and not VOCAB_ALIAS_LINE.search(line):
                        hits.append({"file": rel, "line": lineno,
                                     "name": "yaml:%s" % dotted,
                                     "snippet": line.strip()[:140]})
            if VOCAB_ALIAS_LINE.search(line):
                continue
            for name, rx in LEGACY_RES:
                for _ in rx.finditer(line):
                    hits.append({"file": rel, "line": lineno, "name": name,
                                 "snippet": line.strip()[:140]})
            for m in VOCAB_QUOTED_ZDR_KEY.finditer(line):
                if m.group(2) != config_key \
                        and m.group(2) != "demo.jev.zero-data-retention":
                    hits.append({"file": rel, "line": lineno,
                                 "name": "zdr-key:%s" % m.group(2),
                                 "snippet": line.strip()[:140]})
    return {"hits": hits, "jevFiles": sorted(jev_files),
            "scannedFiles": scanned}
# A literal-true pair is NOT a violation when a config gate sits on the same or
# previous non-empty line, e.g. `if(zeroDataRetention) gatewayOptions.put(
# "zeroDataRetention",true)` - the flag exists; only unconditional sends fail.
GATED = re.compile(r"if\s*\(|&&|\?\s*[^:]", re.IGNORECASE)


def iter_files(root: Path):
    for top in SCAN_DIRS:
        base = root / top
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in SUFFIXES:
                continue
            if set(path.parts) & SKIP_PARTS:
                continue
            if path.resolve() == SELF:
                continue
            yield path


def scan(root: Path):
    hits, mentions, scanned, allowed = [], 0, 0, []
    for path in iter_files(root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        scanned += 1
        rel = str(path.relative_to(root)).replace("\\", "/")
        head = "\n".join(text.splitlines()[:30])
        if ALLOW_FILE.search(head):
            if ANY_MENTION.search(text):
                allowed.append(rel)
            continue
        lines = text.splitlines()
        for lineno, line in enumerate(lines, 1):
            if not ANY_MENTION.search(line):
                continue
            mentions += 1
            if ALLOW_LINE.search(line):
                continue
            prev = lines[lineno - 2].strip() if lineno > 1 else ""
            for m in LITERAL_TRUE.finditer(line):
                if GATED.search(line[:m.start()]) or GATED.search(prev):
                    continue
                hits.append({
                    "file": rel,
                    "line": lineno,
                    "snippet": line.strip()[:140],
                })
    return hits, mentions, scanned, allowed


def main():
    ap = argparse.ArgumentParser(description="ZDR hardcoded-true guard")
    ap.add_argument("--json", action="store_true", help="JSON output only")
    ap.add_argument("--vocab", action="store_true",
                    help="also scan Jev-scoped files for retired reason/config names")
    ap.add_argument("--strict", action="store_true",
                    help="--vocab violations exit 1 (default: report only)")
    ap.add_argument("--root", default=str(DEFAULT_ROOT))
    args = ap.parse_args()
    root = Path(args.root).resolve()
    if not root.is_dir():
        print("[AWX][zdr-guard] error=root-missing root=%s" % root)
        return 2
    hits, mentions, scanned, allowed = scan(root)
    verdict = "FAIL" if hits else "PASS"
    payload = {
        "schemaVersion": "awx.zdr-guard.v1",
        "contract": "DEMO1-DEVIN-JEV-NO-ZDR-STANDING-20260929",
        "zdrGuard": verdict,
        "zdr": "hardcoded" if hits else "clean",
        "hits": hits,
        "allowedFiles": allowed,
        "mentionLines": mentions,
        "scannedFiles": scanned,
        "root": str(root),
    }
    vocab_error = None
    if args.vocab:
        try:
            spec_vocab = parse_vocab_spec(root)
        except VocabSpecError as exc:
            spec_vocab, vocab_error = None, str(exc)
        if vocab_error is not None:
            payload["vocab"] = {"error": vocab_error}
            if not args.json:
                print("[AWX][zdr-guard] vocab=tool-error error=%s" % vocab_error)
        else:
            vres = scan_vocab(root, spec_vocab["configKey"])
            payload["vocab"] = {
                "expectedReasons": spec_vocab["reasons"],
                "configKey": spec_vocab["configKey"],
                "spec": spec_vocab["spec"],
                **vres}
            if not args.json:
                print("[AWX][zdr-guard] vocab=%s expected=%s configKey=%s "
                      "jevFiles=%d hits=%d"
                      % ("FAIL" if vres["hits"] else "PASS",
                         ",".join(spec_vocab["reasons"]),
                         spec_vocab["configKey"], len(vres["jevFiles"]),
                         len(vres["hits"])))
                for h in vres["hits"]:
                    print("VOCAB-FAIL %s:%d %s | %s"
                          % (h["file"], h["line"], h["name"], h["snippet"]))
        if vocab_error is not None:
            if args.json:
                print(json.dumps(payload, ensure_ascii=False, indent=2))
            return 2
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print("[AWX][zdr-guard] zdr=%s scanned=%d mentionLines=%d hits=%d allowed=%d"
              % (payload["zdr"], scanned, mentions, len(hits), len(allowed)))
        for h in hits:
            print("FAIL %s:%d %s" % (h["file"], h["line"], h["snippet"]))
    if hits:
        return 3
    vocab_hits = ((payload.get("vocab") or {}).get("hits") or [])
    if vocab_hits and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
