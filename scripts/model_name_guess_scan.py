#!/usr/bin/env python3
"""model_name_guess_scan -- locate spots where a model-NAME STRING decides the
provider / local-vs-remote / endpoint, for the Plan9 API-first routing work.

Read-only. Standard library only. Scans main/java and main/resources/static/js
(plus src/test/java when --include-tests is passed) for:

  NAME_PREFIX_REMOTE   startsWith("gpt-"|"o1"|"o3"|"o4"|"openai"), JS
                       .startsWith('gpt-'), /^o\\d/, /^gpt/
  NAME_CONTAINS_LOCAL  contains/includes("qwen"|"gemma"|"llama"|"ollama"|"phi")
  NAME_CONTAINS_REMOTE contains/includes("gemini"|"claude"|"mixtral"|"groq"|
                       "anthropic"|"gpt-")
  COLON_MEANS_LOCAL    indexOf(':') / contains(":") / includes(":") /
                       split(":") on a model-ish identifier
  HARDCODED_MODEL_ID   string literals that look like concrete model ids
                       ("gpt-5.5", "gemma4:26b", "qwen3.5:9b", "o4-mini", ...)

Each hit is classified:
  routing_decision - feeds provider/local/route/fallback/score/selection logic
  endpoint_select  - feeds base-url / endpoint / port / device selection
  display_only     - only labels/filters UI (render*, option*, diagnostic*, ...)
  test_fixture     - under a test source root or *Test.java (only with
                     --include-tests; default roots never contain tests)

Usage:
  python -B scripts/model_name_guess_scan.py [--root .]
      [--out-json PATH] [--out-md PATH] [--include-tests]
      [--baseline BASELINE.json]

Baseline mode compares (file,line,rule) keys: prints added/removed hits and
exits 2 when any NEW hit is classified routing_decision (regression guard for
the Codex patch), else 0. Scan mode exits 0 (hits are reported, not judged).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SCAN_ROOTS = ("main/java", "main/resources/static/js")
TEST_ROOTS = ("src/test/java",)
SKIP_PARTS = {
    ".git", "build", "var", "node_modules", "__patch_drop__", "data",
    "agent-handoff", "agent-prompts", ".gradle", ".secrets", "target",
}

MODEL_FAMILY = (
    "gpt-", "o1", "o3", "o4", "o4-mini", "qwen", "qwq", "gemma", "llama",
    "phi", "gemini", "claude", "mistral", "mixtral", "deepseek", "groq",
    "chatgpt", "smtek", "openai",
)

RULES = [
    (
        "NAME_PREFIX_REMOTE",
        re.compile(
            r"""startsWith\(\s*['"](?:gpt-|o1|o3|o4|openai|o-)"""
            r"""|/\^o\\d/|/\^gpt|\.test\([^)]*\)\s*&&\s*/\^o""",
            re.VERBOSE,
        ),
    ),
    (
        "NAME_CONTAINS_LOCAL",
        re.compile(
            r"""(?:contains|includes)\(\s*['"](?:qwen|gemma|llama|ollama|phi)[\w.\-]*['"]"""
        ),
    ),
    (
        "NAME_CONTAINS_REMOTE",
        re.compile(
            r"""(?:contains|includes)\(\s*['"](?:gemini|claude|mixtral|groq|anthropic|gpt-)[\w.\-]*['"]"""
        ),
    ),
    (
        "COLON_MEANS_LOCAL",
        re.compile(
            r"""indexOf\(\s*['"]:['"]|(?:contains|includes)\(\s*['"]:['"]"""
            r"""|split\(\s*['"]:['"]"""
        ),
    ),
    (
        "HARDCODED_MODEL_ID",
        re.compile(
            r"""['"](?:gpt|o\d|chatgpt|gemini|claude|qwen|qwq|gemma|llama|phi"""
            r"""|deepseek|mistral|mixtral|smtek)[\w.\-]*:[\w.\-]+['"]"""
            r"""|['"](?:gpt-\d[\w.\-]*|o\d-mini|gemma\d[\w.\-]*|qwen\d[\w.\-]*"""
            r"""|qwq[\w.\-]*|llama-?\d[\w.\-]*|deepseek[\w.\-]*|gemini-\d[\w.\-]*"""
            r"""|claude-[\w.\-]+|mistral[\w.\-]*\d[\w.\-]*|mixtral[\w.\-]*)['"]"""
        ),
    ),
]

# context keywords that reclassify a hit
ENDPOINT_HINT = re.compile(
    r"baseurl|base_url|base-url|endpoint|11434|host|port|localurl|device",
    re.IGNORECASE,
)
MODEL_CTX_HINT = re.compile(
    r"model|local|ollama|provider|route|remote|cloud|fallback", re.IGNORECASE
)
DISPLAY_FN_HINT = re.compile(
    r"render|label|option|display|diagnostic|chip|resultcount|favorite"
    r"|activetext|embedding|filteredmodels|appendgroup|populate",
    re.IGNORECASE,
)
JAVA_METHOD_RE = re.compile(
    r"^\s*(?:public|private|protected|static|final|\s)+[\w<>\[\], ?]+\s+(\w+)\s*\("
)
JS_FN_RE = re.compile(
    r"(?:function\s+(\w+)|(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s*)?(?:\(|function))"
)


def iter_files(root: Path, include_tests: bool):
    roots = list(SCAN_ROOTS) + (list(TEST_ROOTS) if include_tests else [])
    for rel in roots:
        base = root / rel
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.suffix not in (".java", ".js"):
                continue
            try:
                rel_parts = {p.lower() for p in path.relative_to(root).parts}
            except ValueError:
                rel_parts = {p.lower() for p in path.parts}
            if rel_parts & SKIP_PARTS:
                continue
            yield path


KEYWORD_BLOCK = {"if", "for", "while", "switch", "return", "catch", "else",
                 "do", "try", "synchronized", "throw", "new", "assert", "case"}


def enclosing_method(lines, idx):
    """Best-effort enclosing Java/JS function name (<=60 lines back)."""
    for j in range(idx, max(-1, idx - 60), -1):
        text = lines[j]
        first = re.match(r"\s*(\w+)", text)
        if first and first.group(1) in KEYWORD_BLOCK:
            continue
        m = JAVA_METHOD_RE.match(text)
        if m and m.group(1) not in KEYWORD_BLOCK:
            return m.group(1)
        m = JS_FN_RE.search(text)
        if m:
            name = next((g for g in m.groups() if g), None)
            if name and name not in KEYWORD_BLOCK:
                return name
    return ""


def classify(path, lines, idx, method):
    rel = path.as_posix().lower()
    name = path.name
    if "/test/" in rel or "/src/test/" in rel or name.endswith("Test.java") or name.endswith("Tests.java"):
        return "test_fixture"
    if method and DISPLAY_FN_HINT.search(method):
        return "display_only"
    ctx = "\n".join(lines[max(0, idx - 2): idx + 3])
    if ENDPOINT_HINT.search(ctx) or (method and ENDPOINT_HINT.search(method)):
        return "endpoint_select"
    return "routing_decision"


def scan(root: Path, include_tests: bool):
    hits = []
    for path in iter_files(root, include_tests):
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for i, line in enumerate(lines):
            for rule_id, rx in RULES:
                if not rx.search(line):
                    continue
                if rule_id == "COLON_MEANS_LOCAL":
                    ctx = "\n".join(lines[max(0, i - 2): i + 3])
                    if not MODEL_CTX_HINT.search(ctx):
                        continue
                method = enclosing_method(lines, i)
                hits.append(
                    {
                        "file": path.relative_to(root).as_posix(),
                        "line": i + 1,
                        "rule": rule_id,
                        "class": classify(path, lines, i, method),
                        "method": method,
                        "text": line.strip()[:240],
                        "before": [l.rstrip()[:200] for l in lines[max(0, i - 2): i]],
                        "after": [l.rstrip()[:200] for l in lines[i + 1: i + 3]],
                    }
                )
    return hits


def hit_key(h):
    return (h["file"], h["line"], h["rule"])


def write_md(hits, out, title, extra=None):
    counts = {}
    for h in hits:
        counts[h["class"]] = counts.get(h["class"], 0) + 1
    lines = [f"# {title}", "", f"generated: {datetime.now(timezone.utc).isoformat(timespec='seconds')}"]
    if extra:
        lines += ["", extra]
    lines += ["", "## counts", ""]
    for k in sorted(counts):
        lines.append(f"- {k}: {counts[k]}")
    lines += ["- total: %d" % len(hits), "", "## hits", "",
              "| file:line | rule | class | method | text |", "|---|---|---|---|---|"]
    for h in sorted(hits, key=lambda x: (x["file"], x["line"], x["rule"])):
        text = h["text"].replace("|", "\\|")
        lines.append(f"| {h['file']}:{h['line']} | {h['rule']} | {h['class']} | {h['method']} | `{text}` |")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--out-json")
    ap.add_argument("--out-md")
    ap.add_argument("--include-tests", action="store_true")
    ap.add_argument("--baseline", help="prior scan JSON for add/remove diff")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    hits = scan(root, args.include_tests)
    payload = {
        "schema": "awx.model-name-guess-scan.v1",
        "root": str(root),
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "hits": hits,
    }

    if args.baseline:
        try:
            base = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"baseline_unreadable: {e}", file=sys.stderr)
            return 2
        old = {hit_key(h): h for h in base.get("hits", [])}
        new = {hit_key(h): h for h in hits}
        added = [new[k] for k in new.keys() - old.keys()]
        removed = [old[k] for k in old.keys() - new.keys()]
        added_routing = [h for h in added if h["class"] == "routing_decision"]
        diff = {
            "baseline": str(args.baseline),
            "added": sorted(added, key=lambda x: (x["file"], x["line"])),
            "removed": sorted(removed, key=lambda x: (x["file"], x["line"])),
            "addedRoutingDecision": len(added_routing),
        }
        payload["baselineDiff"] = diff
        if args.out_json:
            Path(args.out_json).write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        print(json.dumps({"added": len(added), "removed": len(removed),
                          "addedRoutingDecision": len(added_routing)}, ensure_ascii=False))
        for h in added:
            print(f"ADDED [{h['class']}] {h['file']}:{h['line']} {h['rule']} :: {h['text'][:100]}")
        for h in removed:
            print(f"REMOVED [{h['class']}] {h['file']}:{h['line']} {h['rule']}")
        if added_routing:
            print("REGRESSION: new routing_decision name-guess sites detected", file=sys.stderr)
            return 2
        return 0

    if args.out_json:
        Path(args.out_json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out_json).write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    if args.out_md:
        write_md(hits, Path(args.out_md), "model-name-guess scan")
    print(json.dumps({"hits": len(hits),
                      "byClass": {k: sum(1 for h in hits if h["class"] == k)
                                  for k in sorted({h["class"] for h in hits})}},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
