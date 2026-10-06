#!/usr/bin/env python3
"""codex_quick_doc.py — zero-search cheatsheet lookup over docs/tri-agent-context.

stdlib only, zero network. Run from anywhere; the repo root resolves from
__file__, not the caller's cwd.

    python -B scripts/codex_quick_doc.py --list
    python -B scripts/codex_quick_doc.py --get <key>
    python -B scripts/codex_quick_doc.py --search <keyword>

--list prints key/file/title rows; --get prints the full markdown sheet to
stdout (markdown is the default format, for terminal + LLM context injection);
--search prints each matching heading section across all sheets.

exit 0 ok | 1 unknown key / usage error | 2 catalog file missing/unreadable.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "docs" / "tri-agent-context"

# key -> (file, one-line title). Order is the --list order.
DOCS = {
    "arch": ("01_ARCHITECTURAL_INVARIANTS.md",
             "Project Root SSOT, PROTO_OPEN, Git local-first, lmsdb H2, shell contract, verify-vs-verdict"),
    "roles": ("02_TRI_AGENT_ROLES_AND_HANDOFF.md",
              "Tri-agent roles, single-seam lease+journal, FOR_<owner> handoff packets"),
    "registry": ("03_LIVE_LLM_RAG_REGISTRY_90D.md",
                 "Live llm.yaml defaults, active seams, SelfAsk/RRF contract, auth-first models, Ollama lock (90d)"),
    "tooling": ("04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md",
                "Devin directive format, MCP stdio/HTTP framing, CP949 defense, Grok memory bridge, agy entry (90d)"),
    "edge": ("05_RECENT_DISCOVERED_EDGE_CASES_90D.md",
             "DPoP/Realtime/SSE edges, self-ask bypass, receipt freshness (90d)"),
    "langchain4j": ("06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md",
                    "Spring Boot 3.3.4 + LangChain4j 1.0.1 purity gate, ChatModel API, LlmConfig beans, anti-patterns"),
    "sse": ("07_SPRING_WEBCLIENT_SSE_RESILIENCE_CHEATSHEET.md",
            "WebClient SSE patterns, required headers, timeout isolation, Resilience4j 2.2.0 operators"),
    "h2": ("08_H2_DBAGENT_AND_LOCK_RECOVERY_CHEATSHEET.md",
           "H2 single-owner embedded lock, db_agent CLI/exits, AUTO_SERVER ban, recovery order"),
    "ps-gpu": ("09_POWERSHELL_AND_GPU_ENDPOINTS_CHEATSHEET.md",
               "PowerShell 5.1 syntax, port lease, RTX3090=11434/RTX3060=11435 lanes, ChatGPT OAuth /v1/responses"),
}

HEADING_RE = re.compile(r"^#{1,6}\s+.+$")


def read_sheet(key: str) -> str:
    """Return the sheet's full markdown text. Raises KeyError/LookupError."""
    entry = DOCS.get(key)
    if entry is None:
        raise KeyError(key)
    path = CATALOG / entry[0]
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise LookupError(f"{entry[0]}: unreadable ({exc})") from exc


def cmd_list() -> int:
    print(f"{'key':<12} {'file':<58} title")
    for key, (fname, title) in DOCS.items():
        print(f"{key:<12} {fname:<58} {title}")
    return 0


def cmd_get(key: str) -> int:
    try:
        sys.stdout.write(read_sheet(key))
        return 0
    except KeyError:
        print(f"unknown key: {key!r} (try --list)", file=sys.stderr)
        return 1
    except LookupError as exc:
        print(f"catalog read failed: {exc}", file=sys.stderr)
        return 2


def _sections(text: str):
    """Yield (heading_line, section_text) split on markdown headings."""
    heading, buf = "(frontmatter/intro)", []
    for line in text.splitlines():
        if HEADING_RE.match(line):
            if buf:
                yield heading, "\n".join(buf)
            heading, buf = line.strip(), []
        else:
            buf.append(line)
    if buf:
        yield heading, "\n".join(buf)


def cmd_search(keyword: str) -> int:
    needle = keyword.casefold()
    hits = 0
    for key in DOCS:
        try:
            text = read_sheet(key)
        except LookupError as exc:
            print(f"catalog read failed: {exc}", file=sys.stderr)
            return 2
        for heading, body in _sections(text):
            section = f"{heading}\n{body}"
            if needle in section.casefold():
                hits += 1
                print(f"--- [{key}] {heading} ({DOCS[key][0]})")
                print(section.strip())
                print()
    if hits == 0:
        print(f"no matches for {keyword!r} in {len(DOCS)} sheets")
    return 0


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    parser = argparse.ArgumentParser(
        description="Zero-search cheatsheet lookup over docs/tri-agent-context.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--list", action="store_true", help="list sheet keys")
    group.add_argument("--get", metavar="KEY", help="print one sheet")
    group.add_argument("--search", metavar="KEYWORD",
                       help="print matching sections across sheets")
    args = parser.parse_args(argv)
    if args.list:
        return cmd_list()
    if args.get is not None:
        return cmd_get(args.get)
    return cmd_search(args.search)


if __name__ == "__main__":
    sys.exit(main())
