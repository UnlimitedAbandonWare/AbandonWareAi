#!/usr/bin/env python3
"""Lifecycle validator for docs/tri-agent-context (stdlib only, zero network).

Contract (directive devin-agent-context-docs-90d-20261005):
  every markdown file in the catalog must carry a YAML frontmatter block with
  the Context-Freshness header fields:
      doc_id, title, created_at, expires_at, ttl_days, lifecycle, validity_basis
  - lifecycle: INVARIANT  -> expires_at == "PERPETUAL" and ttl_days is null
  - lifecycle: VOLATILE   -> ttl_days == 90 and expires_at == created_at + 90 days
  - doc_id values are unique across the catalog
  - relative markdown links resolve (file exists; optional #anchor must match a
    GitHub-slugified heading in the target file)

exit 0 = all checks pass; 1 = validation failures; 2 = runtime error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path

CATALOG_DIR = Path("docs") / "tri-agent-context"

REQUIRED_FILES = [
    "README.md",
    "01_ARCHITECTURAL_INVARIANTS.md",
    "02_TRI_AGENT_ROLES_AND_HANDOFF.md",
    "03_LIVE_LLM_RAG_REGISTRY_90D.md",
    "04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md",
    "05_RECENT_DISCOVERED_EDGE_CASES_90D.md",
    "06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md",
]

REQUIRED_FRONTMATTER_KEYS = [
    "doc_id", "title", "created_at", "expires_at", "ttl_days",
    "lifecycle", "validity_basis",
]

VOLATILE_TTL_DAYS = 90
LIFECYCLES = ("INVARIANT", "VOLATILE")

HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
LINK_RE = re.compile(r"\]\(([^)\s]+)\)")
FM_KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.*)$")


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


def parse_scalar(raw: str):
    """Minimal flat frontmatter scalar parser (no YAML dependency)."""
    value = raw.strip()
    if not value:
        return None
    # strip trailing inline comment when the value itself is unquoted
    if not value.startswith(('"', "'")):
        value = re.split(r"\s+#", value, maxsplit=1)[0].strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
        return value[1:-1]
    low = value.lower()
    if low in ("null", "~", "none"):
        return None
    if low in ("true", "false"):
        return low == "true"
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    return value


def parse_frontmatter(text: str, name: str, problems: list):
    """Return the flat frontmatter dict, or None (problem recorded)."""
    if not text.startswith("---"):
        problems.append(f"{name}: missing YAML frontmatter block at file start")
        return None
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        problems.append(f"{name}: frontmatter must open with a bare --- line")
        return None
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        problems.append(f"{name}: frontmatter closing --- not found")
        return None
    meta = {}
    for lineno, raw in enumerate(lines[1:end], start=2):
        if not raw.strip() or raw.strip().startswith("#"):
            continue
        if raw[0] in (" ", "\t"):
            problems.append(f"{name}: nested/indented frontmatter line {lineno} "
                            "is not supported (keep fields flat)")
            continue
        m = FM_KEY_RE.match(raw)
        if not m:
            problems.append(f"{name}: unparseable frontmatter line {lineno}: {raw!r}")
            continue
        meta[m.group(1)] = parse_scalar(m.group(2))
    return meta


def parse_dt(value):
    """ISO-8601 datetime; returns datetime or None."""
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def check_lifecycle(name: str, meta: dict, problems: list):
    for key in REQUIRED_FRONTMATTER_KEYS:
        if key not in meta:
            problems.append(f"{name}: frontmatter missing required key '{key}'")
    lifecycle = meta.get("lifecycle")
    if lifecycle not in LIFECYCLES:
        problems.append(f"{name}: lifecycle must be one of {LIFECYCLES}, got {lifecycle!r}")
        return
    expires_at = meta.get("expires_at")
    ttl_days = meta.get("ttl_days")
    created_at = parse_dt(meta.get("created_at"))
    if created_at is None:
        problems.append(f"{name}: created_at is not a valid ISO datetime: "
                        f"{meta.get('created_at')!r}")
    if lifecycle == "INVARIANT":
        if expires_at != "PERPETUAL":
            problems.append(f"{name}: INVARIANT doc requires expires_at == 'PERPETUAL', "
                            f"got {expires_at!r}")
        if ttl_days is not None:
            problems.append(f"{name}: INVARIANT doc requires ttl_days null, got {ttl_days!r}")
        return
    # VOLATILE
    if ttl_days != VOLATILE_TTL_DAYS:
        problems.append(f"{name}: VOLATILE doc requires ttl_days == {VOLATILE_TTL_DAYS}, "
                        f"got {ttl_days!r}")
    exp_dt = parse_dt(expires_at)
    if exp_dt is None:
        problems.append(f"{name}: VOLATILE doc requires a real expires_at datetime, "
                        f"got {expires_at!r}")
    elif created_at is not None:
        expected = created_at + timedelta(days=VOLATILE_TTL_DAYS)
        if exp_dt != expected:
            problems.append(f"{name}: expires_at {expires_at!r} != created_at + "
                            f"{VOLATILE_TTL_DAYS}d ({expected.isoformat()})")


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
    seen_ids = {}

    if not doc_dir.is_dir():
        return {"status": "fail", "exitCode": 1,
                "problems": [f"catalog directory missing: {CATALOG_DIR}"],
                "filesChecked": 0, "linksChecked": 0}

    texts = {}
    metas = {}
    for name in REQUIRED_FILES:
        path = doc_dir / name
        if not path.is_file():
            problems.append(f"missing required file: {name}")
            continue
        try:
            raw = path.read_bytes()
        except OSError as exc:
            problems.append(f"{name}: unreadable ({exc})")
            continue
        if raw.startswith(b"\xef\xbb\xbf"):
            problems.append(f"{name}: UTF-8 BOM detected — catalog requires no-BOM")
            continue
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            problems.append(f"{name}: not UTF-8 decodable ({exc})")
            continue
        if not text.strip():
            problems.append(f"{name}: file is empty")
            continue
        texts[name] = text
        files_checked += 1

    for name, text in texts.items():
        meta = parse_frontmatter(text, name, problems)
        if meta is None:
            continue
        metas[name] = meta
        check_lifecycle(name, meta, problems)
        doc_id = meta.get("doc_id")
        if isinstance(doc_id, str) and doc_id:
            if doc_id in seen_ids:
                problems.append(f"{name}: duplicate doc_id {doc_id!r} also in "
                                f"{seen_ids[doc_id]}")
            else:
                seen_ids[doc_id] = name
        links_checked += check_links(name, text, doc_dir, read_cache, problems)

    extra = sorted(p.name for p in doc_dir.glob("*.md") if p.name not in REQUIRED_FILES)
    if extra:
        warnings.append(f"extra markdown files not in catalog contract: {extra}")

    status = "pass" if not problems else "fail"
    return {"status": status, "exitCode": 0 if status == "pass" else 1,
            "filesChecked": files_checked, "linksChecked": links_checked,
            "docIds": seen_ids,
            "problems": problems, "warnings": warnings,
            "requiredFiles": REQUIRED_FILES}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate docs/tri-agent-context frontmatter lifecycle + links.")
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
