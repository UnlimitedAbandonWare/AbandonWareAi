#!/usr/bin/env python3
# scripts/grokbot_skill_catalog.py
"""
GrokBot recipe / skill catalog (read-only, $0 local).

Indexes the live GrokBot handover pack under
`.agents/skills/demo1-agy-directive-writer/references/grokbot-current/`
(HANDOVER.md + recipe files) so an agent can pull just the one recipe that
matches an intent instead of loading the whole pack.

Usage:
  python -B scripts/grokbot_skill_catalog.py list            # table of recipes
  python -B scripts/grokbot_skill_catalog.py match "<kw>"    # best recipe + core steps
  python -B scripts/grokbot_skill_catalog.py show <name>     # one recipe, fuller view
  python -B scripts/grokbot_skill_catalog.py --json list|match ...
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PACK_DIR = os.path.join(ROOT, ".agents", "skills", "demo1-agy-directive-writer",
                        "references", "grokbot-current")
HANDOVER = os.path.join(PACK_DIR, "HANDOVER.md")


def _read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    except Exception:
        return ""


def _frontmatter(text):
    """Parse `---\\nkey: value\\n---` frontmatter; returns (dict, body)."""
    m = re.match(r"\A\s*---\s*\n(.*?)\n---\s*\n", text, re.S)
    if not m:
        return {}, text
    fm = {}
    for line in m.group(1).splitlines():
        kv = re.match(r"([A-Za-z_-]+)\s*:\s*(.*)", line)
        if kv:
            fm[kv.group(1).strip()] = kv.group(2).strip()
    return fm, text[m.end():]


def _sections(body):
    """Split markdown body on `## ` headings -> [{heading, preview, lines}].
    Headings inside ``` fences (brief templates) are not sections."""
    secs, cur, in_fence = [], None, False
    for line in body.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            if cur is not None:
                cur["lines"].append(line)
            continue
        h = re.match(r"^##\s+(.+?)\s*$", line) if not in_fence else None
        if h:
            if cur:
                secs.append(cur)
            cur = {"heading": h.group(1), "lines": []}
        elif cur is not None:
            cur["lines"].append(line)
    if cur:
        secs.append(cur)
    for s in secs:
        preview = next((l.strip() for l in s["lines"]
                        if l.strip() and not l.strip().startswith(("|", "```"))), "")
        s["preview"] = re.sub(r"\*\*", "", preview)[:160]
        del s["lines"]
    return secs


def _triggers(handover_text):
    """HANDOVER.md request-type table: `| "user says" | action | recipe.md |`."""
    trig = {}
    for line in handover_text.splitlines():
        if not line.strip().startswith("|") or ".md" not in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3:
            continue
        target = cells[-1]
        for fname in re.findall(r"[\w.-]+\.md", target):
            trig.setdefault(fname, [])
            for q in re.findall(r"[\"“]([^\"”]+)[\"”]", cells[0]):
                trig[fname].append(q)
            if not trig[fname] and cells[0] and not cells[0].startswith("---"):
                trig[fname].append(cells[0])
    return trig


def parse_recipe(path, triggers=None):
    text = _read(path)
    if not text:
        return None
    fm, body = _frontmatter(text)
    fname = os.path.basename(path)
    title = ""
    for line in body.splitlines():
        if line.startswith("# "):
            title = line[2:].strip()
            break
    return {
        "name": fm.get("name") or fname.replace(".md", ""),
        "file": fname,
        "path": path,
        "title": title,
        "description": fm.get("description") or "",
        "triggers": (triggers or {}).get(fname, []),
        "sections": _sections(body),
    }


def load_catalog(pack_dir=None):
    """Load every recipe (*.md except HANDOVER.md) in the pack; >=4 expected."""
    pack = pack_dir or PACK_DIR
    triggers = _triggers(_read(os.path.join(pack, "HANDOVER.md")))
    recipes = []
    if not os.path.isdir(pack):
        return recipes
    for fname in sorted(os.listdir(pack)):
        if not fname.endswith(".md") or fname == "HANDOVER.md":
            continue
        rec = parse_recipe(os.path.join(pack, fname), triggers)
        if rec:
            recipes.append(rec)
    return recipes


def match(query, catalog=None, limit=1):
    """Score recipes for a free-text intent; returns ranked list (best first)."""
    catalog = catalog if catalog is not None else load_catalog()
    q_terms = [t for t in re.split(r"\s+", (query or "").lower()) if t]
    scored = []
    for rec in catalog:
        hay_name = (rec["name"] + " " + rec["title"]).lower()
        hay_trig = " ".join(rec["triggers"]).lower()
        hay_desc = rec["description"].lower()
        hay_head = " ".join(s["heading"] for s in rec["sections"]).lower()
        score = 0
        ql = (query or "").lower().strip()
        if ql and ql in hay_trig:
            score += 6
        for t in q_terms:
            if t in hay_name:
                score += 3
            if t in hay_trig:
                score += 2
            if t in hay_desc:
                score += 2
            if t in hay_head:
                score += 1
        if score:
            scored.append((score, rec))
    scored.sort(key=lambda x: (-x[0], x[1]["name"]))
    return [r for _, r in scored[:limit]]


def format_recipe(rec, full=False):
    """Compact core-procedure view (or fuller view when full=True)."""
    lines = [f"### {rec['name']}  (`{rec['file']}`)", rec["title"] or ""]
    if rec["description"]:
        lines.append(f"use: {rec['description']}")
    if rec["triggers"]:
        lines.append("user says: " + " | ".join(rec["triggers"][:3]))
    lines.append("steps:")
    for s in rec["sections"][: (12 if full else 6)]:
        lines.append(f"  - {s['heading']}" + (f" — {s['preview'][:100]}" if s["preview"] else ""))
    return "\n".join(lines)


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except Exception:
            pass
    parser = argparse.ArgumentParser(description="GrokBot recipe/skill catalog")
    parser.add_argument("command", choices=("list", "match", "show"))
    parser.add_argument("arg", nargs="?", default="", help="keyword (match) or name (show)")
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args(argv)

    catalog = load_catalog()
    if args.command == "list":
        if args.as_json:
            print(json.dumps([{k: r[k] for k in ("name", "file", "title", "triggers")}
                              for r in catalog], ensure_ascii=False, indent=1))
            return 0
        print(f"# GrokBot recipe catalog ({len(catalog)} recipes)")
        for r in catalog:
            trig = r["triggers"][0][:42] if r["triggers"] else ""
            print(f"- {r['name']:<32} {trig:<44} {r['file']}")
        return 0

    if args.command == "match":
        hits = match(args.arg, catalog)
        if args.as_json:
            print(json.dumps(hits, ensure_ascii=False, indent=1))
            return 0
        if not hits:
            print(f"(no recipe matched '{args.arg}') — run `list` to see all")
            return 2
        print(format_recipe(hits[0]))
        return 0

    # show
    for r in catalog:
        if args.arg and (args.arg in r["name"] or args.arg in r["file"]):
            print(format_recipe(r, full=True))
            return 0
    print(f"(recipe '{args.arg}' not found)")
    return 2


if __name__ == "__main__":
    sys.exit(main())
