"""demo-1 vibe skill router: resolve one user ask to ONE primary skill (+<=1 optional).

SSOT is .agents/skills-intent-index.yaml (intents, match patterns, forbid families).
When every precision `match` pattern misses, per-intent `soft` cues (paraphrase/
typo words) get one fuzzy pass; a total miss falls back to `fallback.primary_skill`
(default demo1-vibe-max-agency) instead of stopping on null.
Default forbid families (counter-evidence, macsrc-patchdrop, triad) stay off the
default path unless the user text itself matches a family's `unlock` pattern;
a family declaring `veto: soft` keeps its skills with a `veto_relaxed` warning
instead of dropping them — `veto: hard` (macsrc-patchdrop) still removes.
Index `auto_promote` adds difficulty tiering: tier1/2 signals promote the
result to high-performance skills and unlock their gated families; the default
tier0_light emits no extra output fields (baseline contract preserved).

Usage:
    python -B scripts/demo1_vibe_skill_router.py resolve "<user text>"
    python -B scripts/demo1_vibe_skill_router.py resolve --root . --index .agents/skills-intent-index.yaml "<user text>"
    python -B scripts/demo1_vibe_skill_router.py resolve --text-file <utf8 path>   # "-" = stdin
    python -B scripts/demo1_vibe_skill_router.py --list-intents

    --text-file reads the ask as UTF-8 from a file (or stdin for "-"); use it for
    Korean/non-ASCII asks on PS 5.1 where argv may be codepage-mangled.

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


# --- difficulty auto-promote (index `auto_promote` block) ---------------------
# tier0_light = 기본(출력 계약 불변) < tier1_tactical < tier2_strategic.
# 승격된 티어의 primary/optional_skill과 unlock_families는 인덱스에서 읽는다.

_SUBSYSTEM_BOUNDARY = r"(?<![a-z0-9]){}(?![a-z0-9])"


def _subsystem_name_hits(names, text):
    """Count distinct canonical subsystem names mentioned (extremez/overdrive/
    cfvm/moe). `extreme-z` and `extremez` canonicalize to one hit."""
    canon = set()
    for name in names or []:
        if not isinstance(name, str) or not name:
            continue
        norm = re.sub(r"[^a-z0-9]", "", name.lower())
        if norm and re.search(_SUBSYSTEM_BOUNDARY.format(re.escape(name.lower())), text):
            canon.add(norm)
    return len(canon)


def _auto_promote(index, text):
    """Return (tier, matched_signals, tier_cfg). tier2 is evaluated before
    tier1 so strategic signals always win; tier0_light means no promotion."""
    promote = index.get("auto_promote") or {}
    names = promote.get("subsystem_names") or []
    multi = _subsystem_name_hits(names, text) >= 2
    for tier in ("tier2_strategic", "tier1_tactical"):
        cfg = promote.get(tier) or {}
        hits = []
        for pat in cfg.get("signals") or []:
            if pat == "multi_subsystem":
                if multi:
                    hits.append(pat)
            elif _match_count([pat], text):
                hits.append(pat)
        if hits:
            return tier, hits, cfg
    return "tier0_light", [], {}


def _invariant_guards(index):
    """승격 시 항상 함께 적용되는 불변 가드 스킬 목록(index `invariant_guards`)."""
    return list((index.get("auto_promote") or {}).get("invariant_guards") or [])


def resolve(index, user_text, root="."):
    text = _norm(user_text)
    families = index.get("families") or {}
    defaults = index.get("default_forbid_families") or []
    tier, tier_hits, tier_cfg = _auto_promote(index, text)
    tier_unlocks = set(tier_cfg.get("unlock_families") or [])

    unlocked = sorted(
        name for name, fam in families.items()
        if _match_count((fam or {}).get("unlock"), text) > 0
    )
    unlocked = sorted(set(unlocked) | tier_unlocks)

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
    score = max((s for s, _ in scored), default=0)
    soft_score = 0

    if best is None:
        # Soft/fuzzy pass: low-precision `soft` cues (paraphrase/typo words)
        # rescue an ask that missed every `match` pattern. Only fires on a
        # total precision miss — it never reorders a scored result.
        soft_scored = []
        for entry in intents:
            if not isinstance(entry, dict):
                continue
            hits = _match_count(entry.get("soft"), text)
            if hits:
                soft_scored.append((hits, entry))
        soft_pool = [pair for pair in soft_scored if pair[1].get("explicit")] or soft_scored
        if soft_pool:
            soft_score, best = max(soft_pool, key=lambda pair: pair[0])

    if best is None:
        fallback = index.get("fallback") or {}
        signals = fallback.get("development_signals")
        use_fallback = signals is None or _match_count(signals, text) > 0
        primary = fallback.get("primary_skill") if use_fallback else None
        result = {
            "schemaVersion": SCHEMA,
            "intent": None,
            "primary": primary,
            "optional": None,
            "forbidden_skipped": sorted(set(defaults) - set(unlocked)),
            "unlocked_families": unlocked,
            "score": 0,
            "via": "fallback" if use_fallback else "none",
            "notes": fallback.get("notes") if use_fallback else None,
        }
        if tier != "tier0_light":
            result["autoPromotedFrom"] = {
                "intent": None, "primary": primary, "optional": None}
            result["primary"] = tier_cfg.get("primary_skill") or primary
            result["optional"] = tier_cfg.get("optional_skill")
            result["tier"] = tier
            result["tierSignals"] = tier_hits
            guards = _invariant_guards(index)
            if guards:
                result["guards"] = guards
            missing = [name for name in (result["primary"], result["optional"])
                       if name and not _skill_exists(root, name)]
            if missing:
                return _missing_skill_error(root, result, missing)
        return result

    forbidden = (set(defaults) | set(best.get("forbid_families") or [])) - set(unlocked)
    # `veto: hard` (default) drops the family's skills; `veto: soft` keeps them
    # and only reports `veto_relaxed` — advisory/deliberation skills may stay
    # routed, destructive pipelines (macsrc-patchdrop) stay hard.
    hard_fams = {
        fam for fam in forbidden
        if (families.get(fam) or {}).get("veto", "hard") != "soft"
    }
    soft_fams = forbidden - hard_fams

    def _family_skills(fams):
        return {
            skill for fam in fams
            for skill in ((families.get(fam) or {}).get("skills") or [])
        }

    hard_skills, soft_skills = _family_skills(hard_fams), _family_skills(soft_fams)
    primary, optional = best.get("primary_skill"), best.get("optional_skill")
    vetoed, veto_relaxed = [], []
    if primary in hard_skills:
        vetoed.append(primary)
        primary = None
    elif primary in soft_skills:
        veto_relaxed.append(primary)
    if optional in hard_skills:
        vetoed.append(optional)
        optional = None
    elif optional in soft_skills:
        veto_relaxed.append(optional)

    # Difficulty promotion: tier2 overrides primary+optional with the tier's
    # configured high-performance skills (already unlocked above so the veto
    # pass cannot strip them); tier1 keeps the resolved primary and only fills
    # an empty optional slot. The pre-promotion routing is preserved for audit.
    promoted_from = None
    if tier != "tier0_light":
        promoted_from = {
            "intent": best.get("intent"),
            "primary": primary,
            "optional": optional,
        }
        if tier_cfg.get("primary_skill"):
            primary = tier_cfg["primary_skill"]
        if optional is None or tier_cfg.get("optional_skill"):
            optional = tier_cfg.get("optional_skill") or optional

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
        "score": score,
        "soft": soft_score > 0,
        "softScore": soft_score,
        "vetoed": vetoed,
        "veto_relaxed": veto_relaxed,
        "redirects": redirects,
        "notes": best.get("notes"),
    }
    if tier != "tier0_light":
        result["tier"] = tier
        result["tierSignals"] = tier_hits
        result["autoPromotedFrom"] = promoted_from
        guards = _invariant_guards(index)
        if guards:
            result["guards"] = guards
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
    parser.add_argument("--text-file", default=None,
                        help="Read the ask from a UTF-8 file ('-' = stdin); "
                             "takes precedence over positional text")
    args = parser.parse_args()
    try:
        index = load_index(args.root, args.index)
        if args.list_intents:
            result = _list_intents(index)
        else:
            text = args.text
            if args.text_file is not None:
                if args.text_file == "-":
                    text = sys.stdin.read()
                else:
                    text = Path(args.text_file).read_text(encoding="utf-8")
            result = resolve(index, text, args.root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"schemaVersion": SCHEMA, "status": "error",
                  "reason": str(error) or "index-load-failed"}
        print(json.dumps(result, ensure_ascii=True))
        return 2
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result.get("status") != "error" else 2


if __name__ == "__main__":
    sys.exit(main())
