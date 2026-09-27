"""demo-1 vibe skill router: resolve one user ask to ONE primary skill (+<=1 optional).

SSOT is .agents/skills-intent-index.yaml (intents, match patterns, forbid families).
Default forbid families (counter-evidence, macsrc-patchdrop, triad) stay off the
default path unless the user text itself matches a family's `unlock` pattern.

Usage:
    python -B scripts/demo1_vibe_skill_router.py resolve "<user text>"
    python -B scripts/demo1_vibe_skill_router.py resolve --root . --index .agents/skills-intent-index.yaml "<user text>"
    python -B scripts/demo1_vibe_skill_router.py --list-intents

Output: one JSON object {intent, primary, optional, forbidden_skipped, ...}.
Resolved skill names follow `redirect:` frontmatter on deprecated alias SKILL.md
files; a resolved skill whose `.agents/skills/<name>/SKILL.md` is missing yields
an error JSON with close-name suggestions. Exit 0 on resolve (including
fallback no-match), 2 on index/usage/missing-skill errors.
"""
from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path
import re
import sys

SCHEMA = "awx.vibe-skill-router.v1"
DEFAULT_INDEX = ".agents/skills-intent-index.yaml"
SKILLS_DIR = ".agents/skills"


# --- minimal YAML-subset loader (fallback when PyYAML is absent) -------------
# Supports exactly the index schema: comments, `key: scalar`, `key:` + nested
# block, `- item` scalar lists, `- key: value` maps inside lists, `[a, b]`
# inline lists, null/~, and quoted scalars. Anything else raises ValueError.

def _scalar(text):
    text = text.strip()
    if text in ("", "null", "~", "Null", "NULL"):
        return None
    if text.startswith("[") and text.endswith("]"):
        inner = text[1:-1].strip()
        if not inner:
            return []
        return [_scalar(part) for part in inner.split(",")]
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("'", '"'):
        return text[1:-1]
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    return text


def _strip_comment(line):
    if line.lstrip().startswith("#"):
        return ""
    pos = line.find(" #")
    return line[:pos] if pos >= 0 else line


def _mini_yaml(text):
    lines = []
    for raw in text.splitlines():
        line = _strip_comment(raw.rstrip("\n")).rstrip()
        if line.strip():
            lines.append((len(line) - len(line.lstrip()), line.lstrip()))
    pos = 0

    def parse_block(indent):
        nonlocal pos
        is_list = lines[pos][1].startswith("- ") or lines[pos][1] == "-"
        container = [] if is_list else {}
        while pos < len(lines):
            ind, content = lines[pos]
            if ind < indent:
                break
            if ind > indent:
                raise ValueError(f"bad indent near: {content}")
            if is_list:
                if not (content.startswith("- ") or content == "-"):
                    break
                item_text = content[1:].strip()
                pos += 1
                if re.match(r"^[^\s:]+:(\s|$)", item_text):
                    key, _, val = item_text.partition(":")
                    item = {key.strip(): _scalar(val)}
                    if pos < len(lines) and lines[pos][0] > indent:
                        sub = parse_block(lines[pos][0])
                        if isinstance(sub, dict):
                            item.update(sub)
                    container.append(item)
                else:
                    container.append(_scalar(item_text))
            else:
                if content.startswith("- "):
                    break
                key, sep, val = content.partition(":")
                if not sep:
                    raise ValueError(f"expected key: line: {content}")
                key = key.strip()
                pos += 1
                if val.strip():
                    container[key] = _scalar(val)
                elif pos < len(lines) and lines[pos][0] > indent:
                    container[key] = parse_block(lines[pos][0])
                else:
                    container[key] = None
        return container

    result = parse_block(lines[0][0]) if lines else {}
    if not isinstance(result, dict):
        raise ValueError("index root must be a map")
    return result


def load_index(root, index_rel):
    path = Path(root) / index_rel
    raw = path.read_bytes()
    if len(raw) > 512 * 1024:
        raise ValueError("index-oversize")
    text = raw.decode("utf-8-sig")
    try:
        import yaml  # PyYAML fast path when present
        data = yaml.safe_load(text)
    except ImportError:
        data = _mini_yaml(text)
    if not isinstance(data, dict):
        raise ValueError("index-not-a-map")
    return data


# --- matching ---------------------------------------------------------------

def _norm(text):
    return re.sub(r"\s+", " ", (text or "").lower()).strip()


def _match_count(patterns, text):
    count = 0
    for pat in patterns or []:
        if not isinstance(pat, str):
            continue
        if pat.startswith("re:"):
            try:
                if re.search(pat[3:], text, re.IGNORECASE):
                    count += 1
            except re.error:
                continue
        elif pat.lower() in text:
            count += 1
    return count


# --- skill folder / alias redirect resolution -------------------------------

def _frontmatter_redirect(root, skill_name):
    """`redirect:` target declared in a skill's SKILL.md frontmatter, or None."""
    path = Path(root) / SKILLS_DIR / skill_name / "SKILL.md"
    try:
        head = path.read_bytes()[:8192].decode("utf-8-sig", errors="replace")
    except OSError:
        return None
    lines = head.splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    for line in lines[1:]:
        if line.strip() == "---":
            break
        match = re.match(r"^redirect\s*:\s*(\S+)", line)
        if match:
            return match.group(1).strip().strip("\"'")
    return None


def _follow_redirects(root, name, redirects):
    """Chase alias `redirect:` hops (max 3, cycle-safe); returns final name."""
    seen = {name}
    current = name
    for _ in range(3):
        target = _frontmatter_redirect(root, current)
        if not target or target in seen:
            break
        redirects[current] = target
        seen.add(target)
        current = target
    return current


def _skill_exists(root, name):
    return (Path(root) / SKILLS_DIR / name / "SKILL.md").is_file()


def _missing_skill_error(root, result, missing):
    try:
        known = sorted(p.name for p in (Path(root) / SKILLS_DIR).iterdir() if p.is_dir())
    except OSError:
        known = []
    result.update({
        "status": "error",
        "reason": "skill-folder-missing",
        "missing": sorted(missing),
        "suggestions": {
            name: difflib.get_close_matches(name, known, n=3, cutoff=0.3)
            for name in sorted(missing)
        },
    })
    return result


def resolve(index, user_text, root="."):
    text = _norm(user_text)
    families = index.get("families") or {}
    defaults = index.get("default_forbid_families") or []

    unlocked = sorted(
        name for name, fam in families.items()
        if _match_count((fam or {}).get("unlock"), text) > 0
    )

    intents = index.get("intents") or []
    scored = []
    for entry in intents:
        if not isinstance(entry, dict):
            continue
        score = _match_count(entry.get("match"), text)
        if score:
            scored.append((score, entry))
    # `explicit: true` intents name a gated family/flow in the user's own words;
    # any scored explicit intent outranks generic intents regardless of score
    # (e.g. "PatchDrop" must not lose to source-write's `patch` substring).
    pool = [pair for pair in scored if pair[1].get("explicit")] or scored
    best = max(pool, key=lambda pair: pair[0])[1] if pool else None

    if best is None:
        fallback = index.get("fallback") or {}
        return {
            "schemaVersion": SCHEMA,
            "intent": None,
            "primary": fallback.get("primary_skill"),
            "optional": None,
            "forbidden_skipped": sorted(set(defaults) - set(unlocked)),
            "unlocked_families": unlocked,
            "score": 0,
            "notes": fallback.get("notes"),
        }

    forbidden = (set(defaults) | set(best.get("forbid_families") or [])) - set(unlocked)
    forbidden_skills = {
        skill for fam in forbidden
        for skill in ((families.get(fam) or {}).get("skills") or [])
    }
    primary, optional = best.get("primary_skill"), best.get("optional_skill")
    vetoed = []
    if primary in forbidden_skills:
        vetoed.append(primary)
        primary = None
    if optional in forbidden_skills:
        vetoed.append(optional)
        optional = None

    redirects = {}
    if primary:
        primary = _follow_redirects(root, primary, redirects)
    if optional:
        optional = _follow_redirects(root, optional, redirects)

    result = {
        "schemaVersion": SCHEMA,
        "intent": best.get("intent"),
        "primary": primary,
        "optional": optional,
        "forbidden_skipped": sorted(forbidden),
        "unlocked_families": unlocked,
        "score": max(s for s, _ in scored),
        "vetoed": vetoed,
        "redirects": redirects,
        "notes": best.get("notes"),
    }
    missing = [name for name in (primary, optional) if name and not _skill_exists(root, name)]
    if missing:
        return _missing_skill_error(root, result, missing)
    return result


def _list_intents(index):
    return {
        "schemaVersion": SCHEMA,
        "default_forbid_families": index.get("default_forbid_families") or [],
        "families": sorted((index.get("families") or {}).keys()),
        "intents": [
            {
                "intent": entry.get("intent"),
                "primary": entry.get("primary_skill"),
                "optional": entry.get("optional_skill"),
                "explicit": bool(entry.get("explicit")),
            }
            for entry in (index.get("intents") or [])
            if isinstance(entry, dict)
        ],
        "fallback": index.get("fallback") or {},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", default="resolve", choices=("resolve",))
    parser.add_argument("text", nargs="?", default="", help="User ask to resolve")
    parser.add_argument("--root", default=".")
    parser.add_argument("--index", default=DEFAULT_INDEX)
    parser.add_argument("--list-intents", action="store_true",
                        help="Print the intent table as JSON and exit")
    args = parser.parse_args()
    try:
        index = load_index(args.root, args.index)
        if args.list_intents:
            result = _list_intents(index)
        else:
            result = resolve(index, args.text, args.root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"schemaVersion": SCHEMA, "status": "error",
                  "reason": str(error) or "index-load-failed"}
        print(json.dumps(result, ensure_ascii=True))
        return 2
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result.get("status") != "error" else 2


if __name__ == "__main__":
    sys.exit(main())
