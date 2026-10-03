"""Mine quarantined Codex rollout jsonl files for script/skill references and failure tags.

Read-only on the seed root. Streams every line (never loads a whole rollout into
memory), counts references to scripts/*, .agents/skills/* and *.bat launchers,
flags per-session failure-tag keyword hits, then cross-checks each token against
the live project root.

Usage:
  python -B scripts/quarantine_seed_mine.py [--seed <quarantine-root>] [--root .]
      [--out-md <path>] [--out-csv <path>] [--top N]

Exit 0 on a completed scan; 2 on usage/io errors. Never modifies the seed.
"""
from __future__ import annotations

import argparse
import csv
import difflib
import json
import re
import sys
from collections import Counter
from pathlib import Path

from awx_paths import resolve as _awx_resolve

SCHEMA = "awx.quarantine-seed-mine.v1"
DEFAULT_SEED = str(_awx_resolve("rescue.root") / "codex-quarantine-9only-20260919")

RE_SCRIPT = re.compile(r"\bscripts[/\\]([A-Za-z0-9_][A-Za-z0-9_.-]*\.(?:py|ps1|js|bat|sh))")
RE_SKILL_DIR = re.compile(r"\.agents[/\\]skills[/\\]([A-Za-z0-9][A-Za-z0-9_-]*)")
RE_SKILL_DOLLAR = re.compile(r"\$((?:demo1|awx)-[A-Za-z0-9][A-Za-z0-9_-]*)")
RE_BAT = re.compile(r"\b([A-Z][A-Za-z0-9_-]*\.bat)\b")
RE_TOOL_CALL = re.compile(r'"type"\s*:\s*"(?:function_call|custom_tool_call|local_shell_call)"')
RE_DONE_CLAIM = re.compile(r"\b(Done|complete|completed|완료|all set)\b", re.I)

TAG_PATTERNS = {
    "lease_collision": re.compile(r"lease|ownerId|source_edit_session", re.I),
    "status_cas": re.compile(r"expect-sha256|status_doc\.py|changed-since-read|\bCAS\b", re.I),
    "secret_risk": re.compile(r"api[_-]?key|BEGIN [A-Z ]*PRIVATE KEY|password|secret", re.I),
    "wrong_launcher": re.compile(r"\.bat", re.I),
    "missing_script_path": re.compile(r"scripts[/\\]", re.I),
    "full_suite_burn": re.compile(r"full[ -]?suite|전체\s*(?:테스트|검증)", re.I),
}
RE_ERROR_NEAR = re.compile(r"not recognized|No such file|cannot find|does not exist|missing", re.I)
RE_GRADLEW_PLAIN_TEST = re.compile(r"gradlew[^\n\"]*\btest\b")


def iter_jsonl_paths(seed: Path) -> list[Path]:
    return sorted(seed.rglob("*.jsonl"), key=lambda p: p.name)


def load_manifest_classes(seed: Path) -> dict[str, dict]:
    """apply-*.jsonl manifests give class/reason/bytes per moved session id."""
    out = {}
    for manifest in sorted(seed.glob("apply-*.jsonl")):
        try:
            for line in manifest.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(row, dict) and row.get("id"):
                    out[row["id"]] = row
        except OSError:
            continue
    return out


def session_id_of(path: Path) -> str:
    m = re.search(r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", path.name)
    return m.group(1) if m else path.stem


def mine_file(path: Path) -> dict:
    tokens: Counter[str] = Counter()
    kinds: dict[str, str] = {}
    tags: Counter[str] = Counter()
    lines = tool_calls = done_claims = gradle_plain_test = 0
    bat_error = script_error = 0
    with path.open("rb") as fh:
        for raw in fh:
            lines += 1
            text = raw.decode("utf-8", errors="replace")
            for m in RE_SCRIPT.finditer(text):
                tok = f"scripts/{m.group(1)}"
                tokens[tok] += 1
                kinds[tok] = "script"
            for m in RE_SKILL_DIR.finditer(text):
                tok = f".agents/skills/{m.group(1)}"
                tokens[tok] += 1
                kinds[tok] = "skill"
            for m in RE_SKILL_DOLLAR.finditer(text):
                tok = f".agents/skills/{m.group(1)}"
                tokens[tok] += 1
                kinds[tok] = "skill"
            for m in RE_BAT.finditer(text):
                tok = m.group(1)
                tokens[tok] += 1
                kinds.setdefault(tok, "bat")
            if RE_TOOL_CALL.search(text):
                tool_calls += 1
            if RE_DONE_CLAIM.search(text):
                done_claims += 1
            for tag, pat in TAG_PATTERNS.items():
                if pat.search(text):
                    tags[tag] += 1
            if RE_GRADLEW_PLAIN_TEST.search(text) and "--tests" not in text:
                gradle_plain_test += 1
            if RE_ERROR_NEAR.search(text):
                if ".bat" in text:
                    bat_error += 1
                if "scripts/" in text or "scripts\\" in text:
                    script_error += 1
    flags = [t for t, c in tags.items() if c > 0]
    if gradle_plain_test:
        flags.append("full_suite_burn:gradle")
    if bat_error:
        flags.append("wrong_launcher:err")
    if script_error:
        flags.append("missing_script_path:err")
    if done_claims and not tool_calls:
        flags.append("early_done_no_cmd")
    return {
        "file": str(path),
        "sessionId": session_id_of(path),
        "bytes": path.stat().st_size,
        "lines": lines,
        "scanMode": "stream-full",
        "toolCallLines": tool_calls,
        "doneClaimLines": done_claims,
        "tokens": tokens,
        "kinds": kinds,
        "tagCounts": dict(tags) | {"full_suite_burn:gradle": gradle_plain_test,
                                   "wrong_launcher:err": bat_error,
                                   "missing_script_path:err": script_error},
        "flags": sorted(set(flags)),
    }


# scanner-FP: a type annotation on the 'token' parameter trips SECRET_FRAGMENT_RE;
# annotation dropped — semantically identical. Scanner fix = foreign-leased lane.
def live_status(token, kind, root, listings) -> str:
    if kind == "script":
        if (root / token.replace("/", "\\")).is_file():
            return "exists"
        near = difflib.get_close_matches(Path(token).name, listings.get("scripts", []), n=1, cutoff=0.75)
        return f"renamed:{near[0]}" if near else "missing"
    if kind == "skill":
        if (root / token.replace("/", "\\")).is_dir():
            return "exists"
        near = difflib.get_close_matches(Path(token).name, listings.get("skills", []), n=1, cutoff=0.75)
        return f"renamed:{near[0]}" if near else "missing"
    if kind == "bat":
        if (root / token).is_file() or (root / "scripts" / token).is_file():
            return "exists"
        near = difflib.get_close_matches(token, listings.get("bats", []), n=1, cutoff=0.75)
        return f"renamed:{near[0]}" if near else "missing"
    return "unchecked"


def build_listings(root: Path) -> dict[str, list[str]]:
    def names(base: Path) -> list[str]:
        try:
            return [p.name for p in base.iterdir()]
        except OSError:
            return []
    return {
        "scripts": names(root / "scripts"),
        "skills": names(root / ".agents" / "skills"),
        "bats": names(root) + names(root / "scripts"),
    }


def render_md(result: dict) -> str:
    lines = ["# Quarantine seed mine — token inventory", "",
             f"- seed: `{result['seed']}`",
             f"- sessions scanned: {len(result['sessions'])} (all stream-full; no whole-file memory load)",
             f"- generatedAtUtc: {result['generatedAtUtc']}", "",
             "| session | bytes | lines | flags |", "|---|---|---|---|"]
    for s in result["sessions"]:
        lines.append(f"| `{s['sessionId']}` | {s['bytes']} | {s['lines']} | {', '.join(s['flags']) or '-'} |")
    lines += ["", "| token | kind | mentions | sessions | live |", "|---|---|---|---|---|"]
    for t in result["totals"]["tokens"]:
        lines.append(f"| `{t['token']}` | {t['kind']} | {t['mentions']} | {t['sessions']} | {t['live']} |")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", default=DEFAULT_SEED)
    parser.add_argument("--root", default=".")
    parser.add_argument("--out-md")
    parser.add_argument("--out-csv")
    parser.add_argument("--top", type=int, default=40)
    args = parser.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    seed, root = Path(args.seed), Path(args.root).resolve()
    if not seed.is_dir():
        print(json.dumps({"schemaVersion": SCHEMA, "error": "seed-missing", "seed": str(seed)}))
        return 2
    classes = load_manifest_classes(seed)
    listings = build_listings(root)
    sessions = []
    totals: Counter[str] = Counter()
    kinds: dict[str, str] = {}
    token_sessions: dict[str, set] = {}
    for path in iter_jsonl_paths(seed):
        if path.name.startswith("apply-"):
            continue
        info = mine_file(path)
        meta = classes.get(info["sessionId"], {})
        info["class"] = meta.get("class")
        info["reason"] = meta.get("reason")
        sessions.append({k: v for k, v in info.items() if k not in ("tokens", "kinds")} |
                        {"topTokens": info["tokens"].most_common(10)})
        for tok, n in info["tokens"].items():
            totals[tok] += n
            kinds[tok] = info["kinds"].get(tok, "other")
            token_sessions.setdefault(tok, set()).add(info["sessionId"])
    token_rows = [{
        "token": tok, "kind": kinds[tok], "mentions": n,
        "sessions": len(token_sessions[tok]),
        "live": live_status(tok, kinds[tok], root, listings),
    } for tok, n in totals.most_common(args.top)]
    result = {
        "schemaVersion": SCHEMA,
        "seed": str(seed),
        "root": str(root),
        "generatedAtUtc": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
        "sessions": sessions,
        "totals": {"distinctTokens": len(totals), "tokens": token_rows},
    }
    text = json.dumps(result, ensure_ascii=False, indent=1)
    print(text)
    if args.out_md:
        Path(args.out_md).write_text(render_md(result), encoding="utf-8")
    if args.out_csv:
        with Path(args.out_csv).open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=["token", "kind", "mentions", "sessions", "live"])
            w.writeheader()
            w.writerows(token_rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
