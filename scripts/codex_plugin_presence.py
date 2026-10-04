#!/usr/bin/env python3
"""codex_plugin_presence.py — per-lane plugin exposure check (names only).

Answers, for each demo1-codex-plugin-roles lane, whether the plugin is
ENABLED (config.toml), installed-but-app-side (cache only), or already
VISIBLE_IN_LAST_SESSION (mounted into the latest demo-1 Codex rollout's
skill catalog / observed as a codex_apps call).

Never prints config values — only section names and boolean flags.
All output passes through scripts/log_redact.py redact_text.

Usage:
    python -B scripts/codex_plugin_presence.py [--root .] [--json]
    python -B scripts/codex_plugin_presence.py --codex-home <dir> --config <toml>
        --session-file <rollout.jsonl> --skill <SKILL.md>
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys

SCHEMA = "awx.codex-plugin-presence.v1"

PLUGIN_SECTION_RE = re.compile(r'^\s*\[\s*plugins\."([^"]+)"\s*\]\s*$')
MCP_SECTION_RE = re.compile(
    r'^\s*\[\s*mcp_servers\.(?:"([^"]+)"|([\w.-]+?))(?:\.env)?\s*\]\s*$')
ENABLED_RE = re.compile(r"^\s*enabled\s*=\s*(true|false)\s*$", re.IGNORECASE)
SKILL_LANE_RE = re.compile(r"^-\s+\*\*(.+?)\*\*")

# lane -> cache/plugin id candidates (names only; never values)
LANES = [
    ("Exa", ["app-69ea4ed2cf7c8191b742ef3622479ddd", "exa"]),
    ("Superpowers", ["superpowers"]),
    ("Browser", ["browser", "chrome"]),
    ("Computer", ["computer-use", "unified-computer-use"]),
    ("GitHub", ["github"]),
    ("Vercel", ["vercel"]),
    ("Supabase", ["supabase"]),
    ("Sites", ["sites"]),
    ("Plugin Management", ["plugin-management"]),
    ("Data", ["data-analytics"]),
    ("Visualize", ["visualize"]),
    ("Ads Manager", ["ads-manager"]),
    ("Meta Wearables", ["meta-wearables-webapp"]),
    ("AWX Control Tower", ["dev-6aa5e6bf1a0881919e7aa85c05fa85f6",
                           "dev-6aa64684a5e4819185ede50838dadd97"]),
    ("GLM assist", []),  # MCP lane, not a plugin
]

# lane -> MCP server names that already cover the lane locally
LANE_MCP = {"GLM assist": ["glm_agent"],
            "AWX Control Tower": ["awx-control-tower", "awx-shared"]}

# skill lane-header label -> declared lane (for split headers like
# "Exa / web research")
LANE_LABEL_ALIAS = {"web research": "exa"}


def parse_config(config_path: Path) -> tuple[dict, set]:
    """Return ({plugin_id@market: enabled}, {mcp_server_names}) — names only."""
    plugins: dict[str, bool] = {}
    mcps: set[str] = set()
    if not config_path.is_file():
        return plugins, mcps
    section = ""
    for raw in config_path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = PLUGIN_SECTION_RE.match(raw)
        if m:
            section = "plugin:" + m.group(1)
            continue
        m = MCP_SECTION_RE.match(raw)
        if m:
            name = m.group(1) or m.group(2)
            section = "mcp:" + name
            mcps.add(name)
            continue
        if raw.strip().startswith("["):
            section = ""
            continue
        if section.startswith("plugin:"):
            em = ENABLED_RE.match(raw)
            if em:
                plugins[section[7:]] = em.group(1).lower() == "true"
    return plugins, mcps


def list_cache(codex_home: Path) -> set:
    cache = codex_home / "plugins" / "cache"
    out = set()
    if not cache.is_dir():
        return out
    for market in cache.iterdir():
        if not market.is_dir():
            continue
        for plug in market.iterdir():
            if plug.is_dir():
                out.add(f"{plug.name}@{market.name}")
    return out


def find_latest_session(codex_home: Path, root_marker: str,
                        limit: int = 400) -> Path | None:
    base = codex_home / "sessions"
    if not base.is_dir():
        return None
    files = sorted(base.rglob("rollout-*.jsonl"),
                   key=lambda p: p.stat().st_mtime, reverse=True)[:limit]
    marker = root_marker.replace("/", "\\").lower()
    for f in files:
        try:
            with f.open(encoding="utf-8", errors="replace") as fh:
                head = fh.readline()
            meta = json.loads(head).get("payload", {})
            cwd = str(meta.get("cwd", ""))
            if marker in cwd.replace("/", "\\").lower():
                return f
        except (OSError, json.JSONDecodeError, AttributeError):
            continue
    return None


def session_plugins(session_path: Path) -> dict:
    """Names only: skill-catalog plugin ids + codex_apps appNames + disabled ids."""
    roots: set[str] = set()
    apps: set[str] = set()
    disabled: set[str] = set()
    host_skills_seen = False
    try:
        fh = session_path.open(encoding="utf-8", errors="replace")
    except OSError:
        return {"roots": [], "apps": [], "disabled": [], "hostSkills": False}
    with fh:
        for line in fh:
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            p = d.get("payload")
            if not isinstance(p, dict):
                continue
            t = d.get("type")
            if t == "world_state":
                s = p.get("state")
                if not isinstance(s, dict):
                    continue
                hs = s.get("host_skills")
                body = hs.get("body") if isinstance(hs, dict) else None
                if isinstance(body, str):
                    host_skills_seen = True
                    root_map: dict[str, str] = {}
                    for m in re.finditer(r"`(r\d+)`\s*=\s*`([^`]+)`", body):
                        rpath = m.group(2)
                        mm = re.search(r"plugins/cache/([^/]+)/([^/]+)/",
                                       rpath)
                        if mm:
                            root_map[m.group(1)] = mm.group(2) + "@" + mm.group(1)
                            roots.add(root_map[m.group(1)])
                            continue
                        mm = re.search(r"plugins/cache/([^/]+)/?`*$", rpath)
                        if mm:
                            root_map[m.group(1)] = "@" + mm.group(1)
                    for m in re.finditer(r"\(file: (r\d+)/([^)\s]+)", body):
                        base = root_map.get(m.group(1))
                        if base and base.startswith("@"):
                            roots.add(m.group(2).split("/")[0] + base)
            elif t == "turn_context":
                for x in (p.get("disabled_plugin_ids") or []):
                    disabled.add(str(x))
            elif t == "event_msg":
                it = p.get("item")
                if isinstance(it, dict) and it.get("server") == "codex_apps":
                    if it.get("appName"):
                        apps.add(str(it["appName"]))
    return {"roots": sorted(roots), "apps": sorted(apps),
            "disabled": sorted(disabled), "hostSkills": host_skills_seen}


def skill_lanes(skill_path: Path) -> list:
    if not skill_path.is_file():
        return []
    out = []
    for line in skill_path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = SKILL_LANE_RE.match(line)
        if m:
            out.extend(x.strip() for x in m.group(1).split("/") if x.strip())
    return out


def lane_status(lane_ids: list, plugins: dict, cache: set,
                sess_names: set) -> tuple[str, list]:
    matched = [pid for pid in lane_ids
               if any(pid in key for key in plugins)
               or any(pid in c for c in cache)]
    visible = [pid for pid in lane_ids if any(pid in s for s in sess_names)]
    if visible:
        return "VISIBLE_IN_LAST_SESSION", matched
    if any(plugins.get(k) for k in plugins
           if any(pid in k for pid in lane_ids)):
        return "ENABLED", matched
    enabled_keys = [k for k in plugins if any(pid in k for pid in lane_ids)]
    if enabled_keys and not any(plugins[k] for k in enabled_keys):
        return "NOT_ENABLED", matched
    if any(pid in c for c in cache for pid in lane_ids):
        return "UNKNOWN", matched  # installed; remote enable state is app-side
    return "NOT_ENABLED", matched


def build_report(root: Path, codex_home: Path, config: Path,
                 skill_path: Path, session_file: Path | None) -> dict:
    plugins, mcps = parse_config(config)
    cache = list_cache(codex_home)
    if session_file is None:
        session_file = find_latest_session(codex_home, str(root))
    sess = {"file": None, "roots": [], "apps": [], "disabled": [],
            "hostSkills": False}
    if session_file and session_file.is_file():
        sess = session_plugins(session_file)
        sess["file"] = session_file.name
    sess_names = set(sess["roots"]) | {a.lower() for a in sess["apps"]}
    sess_ids = set(sess["roots"])
    rows = []
    for lane, ids in LANES:
        names = sess_ids | {i for i in sess_names}
        status, matched = lane_status(ids, plugins, cache, names)
        # connector apps surface under appName, not plugin id
        if status != "VISIBLE_IN_LAST_SESSION":
            app_hits = [a for a in sess["apps"]
                        if any(pid.split("-")[0] in a.lower() or
                               (lane.split()[0].lower() in a.lower())
                               for pid in ids)]
            if app_hits:
                status = "VISIBLE_IN_LAST_SESSION"
        rows.append({"lane": lane, "status": status,
                     "configEnabled": sorted(k for k in plugins
                                             if any(pid in k for pid in ids)
                                             and plugins[k]),
                     "cacheHit": sorted(c for c in cache
                                        if any(pid in c for pid in ids))})
    for row in rows:
        for mcp_name in LANE_MCP.get(row["lane"], []):
            if mcp_name in mcps:
                if row["status"] in ("UNKNOWN", "NOT_ENABLED"):
                    row["status"] = "ENABLED"
                row["cacheHit"].append(f"mcp:{mcp_name}")
    declared = {l for l, _ in LANES}
    missing = []
    for s in skill_lanes(skill_path):
        s_al = LANE_LABEL_ALIAS.get(s.lower(), s)
        if not any(s_al.split(" ")[0].lower().startswith(d.split(" ")[0].lower())
                   or d.lower() in s_al.lower() for d in declared):
            missing.append(s)
    return {"schemaVersion": SCHEMA, "configPlugins": plugins,
            "mcpServers": sorted(mcps), "cacheCount": len(cache),
            "latestSession": sess, "lanes": rows,
            "skillLanesMissing": missing}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--codex-home",
                    default=os.path.join(os.environ.get("USERPROFILE", ""),
                                         ".codex"))
    ap.add_argument("--config", default=None)
    ap.add_argument("--session-file", default=None)
    ap.add_argument("--skill",
                    default=".agents/skills/demo1-codex-plugin-roles/SKILL.md")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    try:
        from log_redact import redact_text
    except ImportError:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from log_redact import redact_text

    root = Path(args.root).resolve()
    codex_home = Path(args.codex_home)
    config = Path(args.config) if args.config else codex_home / "config.toml"
    skill_path = root / args.skill
    session_file = Path(args.session_file) if args.session_file else None

    rep = build_report(root, codex_home, config, skill_path, session_file)
    if args.json:
        out = json.dumps(rep, ensure_ascii=False, indent=1)
    else:
        lines = [f"latestSession: {rep['latestSession']['file'] or 'NONE'}"
                 f" hostSkills={rep['latestSession']['hostSkills']}",
                 "lane\tstatus\tconfigEnabled\tcacheHit"]
        for r in rep["lanes"]:
            lines.append("{0}\t{1}\t{2}\t{3}".format(
                r["lane"], r["status"],
                ",".join(r["configEnabled"]) or "-",
                ",".join(r["cacheHit"]) or "-"))
        if rep["skillLanesMissing"]:
            lines.append("skillLanesMissing: "
                         + ",".join(rep["skillLanesMissing"]))
        out = "\n".join(lines)
    redacted, counts = redact_text(out)
    print(redacted)
    if counts:
        print(f"[redacted: {sorted(counts)}]", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
