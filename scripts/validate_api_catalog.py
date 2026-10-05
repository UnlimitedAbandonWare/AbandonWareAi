#!/usr/bin/env python3
"""Structural validator for docs/api-spec-catalog (stdlib only, zero network).

Checks, per the 90-day Living API Spec & Edge-Case Catalog contract:
  1. required catalog files exist (README.md + 00..04 track docs)
  2. each file is non-empty and decodes as UTF-8
  3. required section headings are present
  4. at least one ```mermaid fenced block per track doc (00..04)
  5. relative markdown links resolve: target file exists, and any
     #anchor matches a GitHub-slugified heading in the target file

exit 0 = all checks pass; 1 = validation failures; 2 = runtime error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CATALOG_DIR = Path("docs") / "api-spec-catalog"

REQUIRED_FILES = [
    "README.md",
    "00_ROADMAP_AND_GAP_MATRIX.md",
    "01_AI_AGENT_PROTOCOLS.md",
    "02_ZERO_TRUST_AUTH.md",
    "03_STREAMING_TRANSPORT.md",
    "04_RATELIMIT_IDEMPOTENCY.md",
]

# Heading substrings that must appear in each file's heading lines.
REQUIRED_HEADINGS = {
    "README.md": ["문서 내비게이션", "핵심 엣지케이스 치트시트", "역할별 가이드", "검증"],
    "00_ROADMAP_AND_GAP_MATRIX.md": ["목적과 범위", "90일 로드맵", "The Gap Matrix",
                                     "Phase별 검증 게이트", "운영 규칙"],
    "01_AI_AGENT_PROTOCOLS.md": ["공식 레퍼런스", "MCP", "OpenAI Realtime",
                                 "Anthropic Messages", "엣지케이스 카탈로그"],
    "02_ZERO_TRUST_AUTH.md": ["공식 레퍼런스", "DPoP", "DPoP-Nonce", "jti",
                              "Token Exchange", "엣지케이스 카탈로그"],
    "03_STREAMING_TRANSPORT.md": ["공식 레퍼런스", "SSE", "WebSocket", "gRPC-Web",
                                  "엣지케이스 카탈로그"],
    "04_RATELIMIT_IDEMPOTENCY.md": ["공식 레퍼런스", "Rate Limit 헤더", "Retry-After",
                                    "Idempotency-Key", "엣지케이스 카탈로그"],
}

MERMAID_REQUIRED = [name for name in REQUIRED_FILES if name != "README.md"]

HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
LINK_RE = re.compile(r"\]\(([^)\s]+)\)")
MERMAID_RE = re.compile(r"^```mermaid\b")


def strip_fenced_code(text: str) -> str:
    """Return the document with ``` fenced code blocks removed."""
    out, in_fence = [], False
    for line in text.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence:
            out.append(line)
    return "\n".join(out)


def heading_lines(text: str):
    body = strip_fenced_code(text)
    for line in body.splitlines():
        m = HEADING_RE.match(line)
        if m:
            yield m.group(2)


def github_slug(heading: str) -> str:
    """Approximate GitHub's heading-anchor slugification."""
    text = re.sub(r"<[^>]+>", "", heading).strip().lower()
    kept = [ch for ch in text if ch.isalnum() or ch in (" ", "-", "_")]
    return "".join(kept).replace(" ", "-")


def mermaid_count(text: str) -> int:
    return sum(1 for line in text.splitlines() if MERMAID_RE.match(line.strip()))


def check_links(name: str, text: str, doc_dir: Path, read_cache: dict, problems: list) -> int:
    body = strip_fenced_code(text)
    count = 0
    for match in LINK_RE.finditer(body):
        target = match.group(1)
        count += 1
        if target.startswith(("http://", "https://", "mailto:")):
            continue  # external links are never fetched (zero-network rule)
        path_part, _, anchor = target.partition("#")
        if not path_part:  # in-page anchor
            slugs = {github_slug(h) for h in heading_lines(text)}
            if anchor and github_slug(anchor) != anchor:
                problems.append(f"{name}: malformed anchor #{anchor}")
            elif anchor and anchor not in slugs:
                problems.append(f"{name}: missing in-page anchor #{anchor}")
            continue
        resolved = (doc_dir / path_part).resolve()
        if not resolved.is_file():
            problems.append(f"{name}: broken relative link -> {target}")
            continue
        if anchor:
            if str(resolved) not in read_cache:
                try:
                    read_cache[str(resolved)] = resolved.read_text(encoding="utf-8")
                except (OSError, UnicodeDecodeError) as exc:
                    problems.append(f"{name}: unreadable link target {target} ({exc})")
                    continue
            slugs = {github_slug(h) for h in heading_lines(read_cache[str(resolved)])}
            if anchor not in slugs:
                problems.append(f"{name}: link {target} -> missing anchor #{anchor}")
    return count


def validate(root: Path) -> dict:
    doc_dir = root / CATALOG_DIR
    problems, warnings, files_checked, links_checked = [], [], 0, 0
    read_cache = {}

    if not doc_dir.is_dir():
        return {"status": "fail", "exitCode": 1,
                "problems": [f"catalog directory missing: {CATALOG_DIR}"],
                "filesChecked": 0, "linksChecked": 0}

    texts = {}
    for name in REQUIRED_FILES:
        path = doc_dir / name
        if not path.is_file():
            problems.append(f"missing required file: {name}")
            continue
        try:
            raw = path.read_bytes()
            text = raw.decode("utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            problems.append(f"{name}: unreadable as UTF-8 ({exc})")
            continue
        if not text.strip():
            problems.append(f"{name}: file is empty")
            continue
        texts[name] = text
        files_checked += 1

    for name, text in texts.items():
        heads = list(heading_lines(text))
        for required in REQUIRED_HEADINGS.get(name, []):
            if not any(required in h for h in heads):
                problems.append(f"{name}: missing required section heading '{required}'")
        if name in MERMAID_REQUIRED and mermaid_count(text) < 1:
            problems.append(f"{name}: no ```mermaid diagram block found")
        links_checked += check_links(name, text, doc_dir, read_cache, problems)

    extra = sorted(p.name for p in doc_dir.glob("*.md") if p.name not in REQUIRED_FILES)
    if extra:
        warnings.append(f"extra markdown files not in catalog contract: {extra}")

    status = "pass" if not problems else "fail"
    return {"status": status, "exitCode": 0 if status == "pass" else 1,
            "filesChecked": files_checked, "linksChecked": links_checked,
            "problems": problems, "warnings": warnings,
            "requiredFiles": REQUIRED_FILES}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Validate docs/api-spec-catalog structure.")
    parser.add_argument("--root", default=None,
                        help="repo root (default: parent of this script's directory)")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[1]
    try:
        report = validate(root)
    except Exception as exc:  # noqa: BLE001 - report runtime failure distinctly
        print(json.dumps({"status": "error", "exitCode": 2, "error": str(exc)},
                         ensure_ascii=False))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return report["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
