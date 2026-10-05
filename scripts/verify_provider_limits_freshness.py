"""Provider-limits doc freshness checker (awx.provider-limits-freshness.v1).

Scans `docs/provider-limits/*.md` yaml blocks and reports per-document
freshness plus, for expired docs, the official `sourceUrls` and suggested
web-search keywords needed for the 90-day auto-refresh protocol
(docs/provider-limits/README.md "90-day expiry auto-refresh protocol").

Expiry precedence per document:
  1. `expiresAt` (YYYY-MM-DD) present -> authoritative TTL boundary.
  2. else `reviewedAt` or `capturedAt` + `reviewAfterDays` (default 90).
  3. neither -> status NO_TTL (listed, never counted expired).

`--check` always exits 0 on a completed scan — expiry is information for the
refresh protocol, never a CI failure, a login/plan quiz, or an auth gate.
Protocol: web-search the first official sourceUrl once; no change -> bump
`reviewedAt`/`expiresAt` only; changed -> update body then `capturedAt`.
"""
import argparse
import datetime as _dt
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.provider-limits-freshness.v1"
DEFAULT_REVIEW_DAYS = 90
NO_TTL = "NO_TTL"
ACTIVE = "ACTIVE"
EXPIRED = "EXPIRED"
MISSING = "MISSING_DATES"

KEYWORDS = {
    "groq-limits": ["Groq API rate limits console.groq.com docs"],
    "groq-free-limits": ["Groq API rate limits console.groq.com docs"],
    "gemini-limits": ["Gemini API rate limits ai.google.dev", "Gemini API billing ai.google.dev"],
    "openai-api-limits": ["OpenAI API rate limits developers.openai.com", "OpenAI API models pricing"],
    "brave-search-limits": ["Brave Search API pricing brave.com/search/api", "Brave Search API rate limits"],
    "tavily-limits": ["Tavily API credits docs.tavily.com", "Tavily API rate limits"],
    "naver-search-limits": ["네이버 검색 API 하루 호출 한도 developers.naver.com", "Naver Search API daily quota"],
    "vercel-ai-gateway-limits": ["Vercel AI Gateway pricing credits", "Vercel AI Gateway rate limits"],
    "openrouter-desktop-routing": ["OpenRouter API pricing rate limits documentation"],
    "readme": ["provider limits documentation review"],
}

_YAML_BLOCK_RE = re.compile(r"```ya?ml\s*\n(.*?)```", re.S)
_SCALAR_RE = re.compile(r"^([A-Za-z][A-Za-z0-9_-]*):\s*(.*)$")
_LIST_ITEM_RE = re.compile(r"^\s*-\s*(.+?)\s*$")
_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def parse_yaml_block(text):
    """Extract scalar keys + sourceUrls list from the first ```yaml fence."""
    match = _YAML_BLOCK_RE.search(text)
    if not match:
        return None
    scalars, urls = {}, []
    in_list, in_folded = False, False
    for line in match.group(1).splitlines():
        m = _SCALAR_RE.match(line)
        if m:
            key, val = m.group(1), m.group(2).strip()
            in_list = key == "sourceUrls"
            in_folded = val in (">", "|", ">-", "|-", ">+", "|+")
            if not in_list and not in_folded:
                scalars[key] = _unquote(val)
            continue
        if in_folded:
            if line.strip() == "" or line.startswith((" ", "\t")):
                continue
            in_folded = False
        if in_list:
            item = _LIST_ITEM_RE.match(line)
            if item:
                urls.append(_unquote(item.group(1)))
                continue
            in_list = False
    scalars["sourceUrls"] = urls
    return scalars


def _parse_date(value):
    if isinstance(value, str) and _DATE_RE.match(value.strip()):
        return _dt.date.fromisoformat(value.strip())
    return None


def evaluate(path, meta, today):
    """Return per-doc freshness record. `meta` is parse_yaml_block output."""
    stem = path.stem.lower()
    rec = {"file": path.name, "provider": stem, "status": MISSING,
           "capturedAt": meta.get("capturedAt"), "reviewedAt": meta.get("reviewedAt"),
           "reviewAfterDays": None, "expiresAt": None, "daysLeft": None,
           "sourceUrls": meta.get("sourceUrls", []),
           "suggestedKeywords": KEYWORDS.get(
               stem, ["%s API pricing rate limits official documentation" % stem.replace("-", " ")])}
    raw_days = meta.get("reviewAfterDays") or meta.get("ttlDays")
    try:
        rec["reviewAfterDays"] = int(raw_days) if raw_days is not None else DEFAULT_REVIEW_DAYS
    except (TypeError, ValueError):
        rec["reviewAfterDays"] = DEFAULT_REVIEW_DAYS
    expires = _parse_date(meta.get("expiresAt"))
    if expires is not None:
        rec["expiresAt"] = expires.isoformat()
        rec["daysLeft"] = (expires - today).days
        rec["status"] = ACTIVE if rec["daysLeft"] >= 0 else EXPIRED
        return rec
    base = _parse_date(meta.get("reviewedAt")) or _parse_date(meta.get("capturedAt"))
    if base is None:
        if meta.get("expiresAt") or meta.get("ttlDays"):
            rec["status"] = NO_TTL
        else:
            rec["status"] = MISSING
        return rec
    effective = base + _dt.timedelta(days=rec["reviewAfterDays"])
    rec["expiresAt"] = effective.isoformat()
    rec["daysLeft"] = (effective - today).days
    rec["status"] = ACTIVE if rec["daysLeft"] >= 0 else EXPIRED
    return rec


def scan(root, today):
    root = Path(root)
    records = []
    for path in sorted(root.glob("*.md")):
        meta = parse_yaml_block(path.read_text(encoding="utf-8"))
        if meta is None:
            records.append({"file": path.name, "provider": path.stem.lower(),
                            "status": "PARSE_ERROR", "capturedAt": None,
                            "reviewedAt": None, "reviewAfterDays": None,
                            "expiresAt": None, "daysLeft": None,
                            "sourceUrls": [], "suggestedKeywords": []})
            continue
        records.append(evaluate(path, meta, today))
    return {"schema": SCHEMA, "root": str(root), "today": today.isoformat(),
            "documents": records,
            "expired": [r for r in records if r["status"] == EXPIRED],
            "parseErrors": [r["file"] for r in records if r["status"] == "PARSE_ERROR"],
            "protocol": [
                "web-search the first official sourceUrl of each EXPIRED doc once",
                "no change -> bump reviewedAt and extend expiresAt (auto +90d, no quiz)",
                "changed -> update body facts, then capturedAt and reviewedAt",
                "never a login/payment/plan quiz; archive per expiryAction"]}


def _print_text(report):
    print("provider-limits freshness root=%s today=%s" % (report["root"], report["today"]))
    print("%-34s %-11s %-12s %-9s %s" % ("doc", "status", "expiresAt", "daysLeft", "provider"))
    for r in report["documents"]:
        print("%-34s %-11s %-12s %-9s %s" % (
            r["file"], r["status"], r["expiresAt"] or "-",
            r["daysLeft"] if r["daysLeft"] is not None else "-", r["provider"]))
    expired = report["expired"]
    print("EXPIRED: %d" % len(expired))
    for r in expired:
        print("- %s" % r["file"])
        for u in r["sourceUrls"]:
            print("    sourceUrl: %s" % u)
        for k in r["suggestedKeywords"]:
            print("    suggestedKeyword: %s" % k)
    if report["parseErrors"]:
        print("PARSE_ERROR: %s" % ", ".join(report["parseErrors"]))
    if expired:
        print("refresh protocol:")
        for step in report["protocol"]:
            print("  - %s" % step)


def main(argv=None):
    ap = argparse.ArgumentParser(description="provider-limits 90-day freshness checker")
    ap.add_argument("--check", action="store_true", default=True,
                    help="list expired docs, sourceUrls, suggested web-search keywords (exit 0)")
    ap.add_argument("--root", default="docs/provider-limits",
                    help="directory of provider-limits markdown docs")
    ap.add_argument("--today", default=None,
                    help="YYYY-MM-DD override for deterministic checks/tests")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args(argv)

    root = Path(args.root)
    if not root.is_dir():
        out = {"schema": SCHEMA, "error": "root-missing", "root": str(root)}
        print(json.dumps(out) if args.json else "error: root-missing %s" % root)
        return 0  # informational tool: never fail CI on layout drift either
    today = _parse_date(args.today) if args.today else _dt.date.today()
    if today is None:
        print("error: --today must be YYYY-MM-DD")
        return 2
    report = scan(root, today)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        _print_text(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
