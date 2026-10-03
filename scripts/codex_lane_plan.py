#!/usr/bin/env python3
"""Codex parallel-lane planner for one shared checkout.

Contract DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002 (DV7). Splits one goal into
lanes whose write scopes never overlap: work packages that write the same file
merge into one lane, verification-only packages become read-only VERIFIER
lanes, and exactly one INTEGRATOR lane owns restart/smoke/final integration.
Nothing here edits source; it writes a plan under
data/agent-handoff/parallel-lanes/<planId>/ (plan.json + lane-<id>.txt paste
headers + PLAN_KO.md). Exit codes: 0 ok, 2 usage, 5 SERIAL_REQUIRED-only.

  python -B scripts/codex_lane_plan.py --brief agent-prompts/x/BRIEF.txt
  python -B scripts/codex_lane_plan.py --wp "failover=a.java,b.java" --wp "warmup=c.java" --lanes 3
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_parallel_lib as lib

WP_RE = re.compile(r"(?m)^\s*(?:#{1,4}\s*|\*\*)?\s*(WP|DV)\s*([0-9]+[A-Za-z-]*)"
                   r"\b[\s:—–-]*(.*)$")
PATH_RE = re.compile(r"(?<![\w/.-])((?:main|src|scripts|docs|configs|\.agents|"
                     r"\.windsurf|\.clinerules|\.grok|\.devin|agent-prompts|tools|"
                     r"app|data/agent-handoff)[\w./\\-]*?\.(?:java|py|js|cjs|mjs|"
                     r"ps1|bat|md|yaml|yml|json|kts|html|css|properties|txt|gradle)"
                     r"|(?:main|src|scripts|docs|configs|\.agents|\.windsurf|"
                     r"\.clinerules|agent-prompts|tools|app|data/agent-handoff)"
                     r"[\w./\\-]*[\w-]/)(?![\w/.-])")
VERIFY_WP_RE = re.compile(r"(?i)verif|검증|review|리허설|rehearsal|smoke|integrat|통합|회귀")
BARE_NAME_RE = re.compile(
    r"(?<![\w/.-])([A-Za-z_][\w.-]*\.(?:java|py|js|cjs|mjs|ps1|bat|md|yaml|"
    r"yml|json|kts|html|css|properties|gradle))(?![\w/.-])")
LANE_IDS = "ABCDEFGH"
# Aggregator files only the INTEGRATOR lane may write; stripped from owner
# write scopes so two lanes can never both list them.
RESERVED_INTEGRATOR = {"docs/project_status.md"}

_INDEX = {}


def repo_file_index(root):
    """basename.lower() -> repo-relative path when exactly one tracked file
    carries that name. Briefs cite `Foo.java` bare; resolving against the tree
    keeps two WPs that name the same class in one lane."""
    root = str(root)
    if root in _INDEX:
        return _INDEX[root]
    found = {}
    for top in lib.EDIT_SCAN_ROOTS:
        base = Path(root) / top
        if not base.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in lib.EDIT_SKIP_DIRS]
            for name in filenames:
                rel = (Path(dirpath) / name).relative_to(root).as_posix()
                key = name.lower()
                found.setdefault(key, []).append(rel)
    _INDEX[root] = {k: v[0] for k, v in found.items() if len(v) == 1}
    return _INDEX[root]


def die(reason, code=2):
    print(json.dumps({"schemaVersion": lib.SCHEMA, "ok": False, "reason": reason},
                     ensure_ascii=True))
    sys.exit(code)


def parse_brief(text, root="."):
    """WP/DV section header -> repo-relative paths collected until the next
    header. Header names keep the trailing text (e.g. 'WP1 failover fix').
    Bare filenames (`Foo.java`) resolve through the repo index; unresolved
    names are kept as `basename:foo.java` hints so shared classes still merge
    WPs into one lane."""
    index = repo_file_index(root)

    def section_paths(section):
        paths = {m.group(1) for m in PATH_RE.finditer(section)}
        for m in BARE_NAME_RE.finditer(section):
            name = m.group(1)
            if any(name in p for p in paths):
                continue  # already part of a spelled-out path
            hit = index.get(name.lower())
            paths.add(hit if hit else "basename:" + name.lower())
        return sorted(paths)

    matches = list(WP_RE.finditer(text))
    if not matches:
        paths = section_paths(text)
        return [{"name": "main", "paths": paths}] if paths else []
    wps = []
    for i, match in enumerate(matches):
        name = f"{match.group(1).upper()}{match.group(2)}{match.group(3).strip()}"
        name = re.sub(r"\s+", " ", name).strip(" :-—–*") or f"{match.group(1)}{match.group(2)}"
        section = text[match.end():matches[i + 1].start() if i + 1 < len(matches) else len(text)]
        wps.append({"name": name, "paths": section_paths(section)})
    return wps


def parse_wp_args(values):
    wps = []
    for raw in values or []:
        name, sep, rest = str(raw).partition("=")
        if not sep or not name.strip():
            die("wp-format: expected name=path1,path2")
        wps.append({"name": name.strip(),
                    "paths": [p.strip() for p in rest.split(",") if p.strip()]})
    return wps


def group_wps(wps):
    """Union-find merge over write-scope overlap. Returns (groups, shared_files).
    groups: list of {wps:[names], scope:set}. Two WPs writing the same path end
    in one group; a file shared by >1 WP is recorded in shared_files."""
    parent = list(range(len(wps)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i, j):
        parent[find(i)] = find(j)

    shared_files = {}
    owner = {}
    scopes, merge_keys = [], []
    for wp in wps:
        canon_paths = [c for c in (lib.canon(p) for p in wp["paths"]) if c]
        real = [c for c in canon_paths if not c.startswith("basename:")]
        scopes.append(real)
        merge_keys.append(real + [c for c in canon_paths
                                  if c.startswith("basename:")])
    for i, keys in enumerate(merge_keys):
        for path in keys:
            if path in owner:
                union(i, owner[path])
                shared_files.setdefault(path, set()).update(
                    [wps[i]["name"], wps[owner[path]]["name"]])
            else:
                owner[path] = i
    groups = {}
    for i, scope in enumerate(scopes):
        g = groups.setdefault(find(i), {"wps": [], "scope": set()})
        g["wps"].append(wps[i]["name"])
        g["scope"].update(scope)
    return list(groups.values()), {k: sorted(v) for k, v in shared_files.items()}


def lane_quota(role):
    if role == "INTEGRATOR":
        return {"serverRestart": "exclusive", "chatSmoke": 2,
                "gradle": 2, "ollamaGpuLoad": "exclusive",
                "projectStatusAppend": True}
    if role == "VERIFIER":
        return {"serverRestart": "no", "chatSmoke": 0, "gradle": 1,
                "ollamaGpuLoad": "no", "projectStatusAppend": False}
    return {"serverRestart": "no", "chatSmoke": 0, "gradle": 1,
            "ollamaGpuLoad": "no", "projectStatusAppend": False}


def build_plan(root, wps, requested_lanes, goal_key_text):
    groups, shared = group_wps(wps)
    # A group whose every WP name reads like verification becomes VERIFIER.
    verify_groups = [g for g in groups
                     if all(VERIFY_WP_RE.search(n) for n in g["wps"])]
    write_groups = [g for g in groups
                    if g["scope"] and g not in verify_groups]
    chore_wps = sorted({n for g in groups
                        if not g["scope"] and g not in verify_groups
                        for n in g["wps"]})

    serial_required, serial_reason = False, ""
    if requested_lanes and requested_lanes > 1 and len(write_groups) <= 1 \
            and len(wps) > 1:
        serial_required = True
        serial_reason = ("all write scopes overlap into a single lane; "
                         "parallel lanes impossible, run WPs serially")
    if requested_lanes and 0 < requested_lanes < len(write_groups):
        # Merge smallest disjoint groups until the cap holds - safe because
        # union members stay disjoint from every other lane.
        write_groups.sort(key=lambda g: len(g["scope"]))
        while len(write_groups) > requested_lanes:
            target = write_groups.pop(0)
            write_groups[0]["wps"] += target["wps"]
            write_groups[0]["scope"] |= target["scope"]

    seed = json.dumps({"key": goal_key_text, "wps": [w["name"] for w in wps],
                       "at": lib.utcnow()}, sort_keys=True)
    plan_id = "plan-" + hashlib.sha256(seed.encode()).hexdigest()[:8]
    lanes, owners = [], []
    reserved_taken = sorted(RESERVED_INTEGRATOR &
                            {p for g in write_groups for p in g["scope"]})
    real_writes, drained = [], []
    for group in write_groups:
        scope = sorted(group["scope"] - RESERVED_INTEGRATOR)
        if scope:
            group["scope"] = set(scope)
            real_writes.append(group)
        else:
            drained.extend(group["wps"])
    write_groups = real_writes
    chore_wps = sorted(set(chore_wps) | set(drained))
    for i, group in enumerate(write_groups):
        lane_id = LANE_IDS[i] if i < len(LANE_IDS) else f"L{i}"
        owners.append(lane_id)
        lanes.append({"laneId": lane_id, "role": "OWNER",
                      "wps": sorted(group["wps"]),
                      "writeScope": sorted(group["scope"]),
                      "readScope": sorted(group["scope"]),
                      "buildHostId": f"codex-lane-{lane_id.lower()}",
                      "quota": lane_quota("OWNER"), "dependsOn": []})
    for i, group in enumerate(verify_groups):
        lane_id = LANE_IDS[len(lanes)] if len(lanes) < len(LANE_IDS) else f"L{len(lanes)}"
        lanes.append({"laneId": lane_id, "role": "VERIFIER",
                      "wps": sorted(group["wps"]),
                      "writeScope": [],
                      "readScope": sorted(group["scope"]),
                      "buildHostId": f"codex-lane-{lane_id.lower()}",
                      "quota": lane_quota("VERIFIER"), "dependsOn": []})
    all_write = sorted({p for l in lanes for p in l["writeScope"]})
    lanes.append({"laneId": "INT", "role": "INTEGRATOR",
                  "wps": ["integration"] + chore_wps,
                  "writeScope": sorted(RESERVED_INTEGRATOR),
                  "readScope": all_write + sorted(RESERVED_INTEGRATOR),
                  "buildHostId": "codex-lane-int",
                  "quota": lane_quota("INTEGRATOR"),
                  "dependsOn": owners})
    return {"schemaVersion": lib.PLAN_SCHEMA, "planId": plan_id,
            "goalKey": goal_key_text, "createdAtUtc": lib.utcnow(),
            "lanes": lanes, "sharedFiles": shared,
            "reservedToIntegrator": reserved_taken,
            "serialRequired": serial_required, "serialReason": serial_reason,
            "quotaPolicy": {"chatSmokePlanTotal": 2, "gradleConcurrent": 2,
                            "serverRestart": "mutex-integrator",
                            "ollamaGpuLoad": "mutex",
                            "projectStatusAppend": "integrator-only"}}


def lane_header(plan, lane):
    write = ",".join(lane["writeScope"]) or "-"
    smoke = lane["quota"].get("chatSmoke", 0)
    restart = "yes" if lane["quota"].get("serverRestart") == "exclusive" else "no"
    total = len(plan["lanes"])
    return (f"[LANE: {plan['planId']}/{lane['laneId']} of {total} "
            f"role={lane['role']} buildHostId={lane['buildHostId']} "
            f"write={write} smoke={smoke} restart={restart}]\n"
            "규칙: write 범위 밖 파일은 수정 금지. 필요한 out-of-scope 변경은 "
            "INTEGRATOR 레인에 인계. 첫 수정 전 `python -B scripts/"
            f"codex_parallel_preflight.py --lane {plan['planId']}/{lane['laneId']}` 실행.\n")


def write_outputs(root, plan):
    out_dir = Path(root) / lib.PLAN_BASE / plan["planId"]
    out_dir.mkdir(parents=True, exist_ok=True)
    plan_path = out_dir / "plan.json"
    plan_path.write_text(json.dumps(plan, ensure_ascii=True, indent=2) + "\n",
                         encoding="utf-8")
    for lane in plan["lanes"]:
        (out_dir / f"lane-{lane['laneId']}.txt").write_text(
            lane_header(plan, lane), encoding="utf-8")
    lines = [f"# 병렬 레인 계획 {plan['planId']}",
             f"- goalKey: `{plan['goalKey']}` · 생성 {plan['createdAtUtc']}",
             f"- serialRequired: {plan['serialRequired']}"
             + (f" ({plan['serialReason']})" if plan['serialReason'] else ""),
             "", "| 레인 | 역할 | WP | write 범위 | smoke | restart |", "|---|---|---|---|---|---|"]
    for lane in plan["lanes"]:
        q = lane["quota"]
        lines.append(
            f"| {lane['laneId']} | {lane['role']} | {', '.join(lane['wps'])} | "
            f"{', '.join(lane['writeScope']) or '-'} | {q.get('chatSmoke', 0)} | "
            f"{'yes' if q.get('serverRestart') == 'exclusive' else 'no'} |")
    if plan["sharedFiles"]:
        lines += ["", "공유 파일(같은 레인으로 묶음):"]
        for path, owners in plan["sharedFiles"].items():
            lines.append(f"- `{path}` ← {', '.join(owners)}")
    lines += ["", "각 채팅 맨 위에 `lane-<id>.txt` 머리말을 붙여 넣는다."]
    (out_dir / "PLAN_KO.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return plan_path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=".")
    parser.add_argument("--brief", help="지시서 파일 경로")
    parser.add_argument("--wp", action="append",
                        help='"name=path1,path2" 반복 인자')
    parser.add_argument("--lanes", type=int, default=0, help="레인 상한 (기본 자동)")
    parser.add_argument("--goal-key", default="", help="목표 키 (기본: Contract 추출)")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    wps, source = [], ""
    if args.brief:
        brief = Path(args.brief)
        if not brief.is_file():
            die(f"brief-missing:{args.brief}")
        text = brief.read_text(encoding="utf-8", errors="replace")
        wps = parse_brief(text, root)
        source = str(args.brief)
    elif args.wp:
        wps = parse_wp_args(args.wp)
        source = "--wp"
    else:
        die("input-required: pass --brief or --wp")
    if not wps:
        die("no-wp-parsed")

    key = lib.normalize_key(args.goal_key)
    if not key:
        purpose_text = Path(args.brief).read_text(encoding="utf-8", errors="replace") \
            if args.brief else " ".join(w["name"] for w in wps)
        key = lib.goal_key("", purpose_text)
    plan = build_plan(root, wps, args.lanes, key)
    plan["source"] = source
    plan_path = write_outputs(root, plan)
    print(json.dumps({"schemaVersion": lib.SCHEMA, "ok": True,
                      "planId": plan["planId"], "planPath": str(plan_path),
                      "serialRequired": plan["serialRequired"],
                      "lanes": [{"laneId": l["laneId"], "role": l["role"],
                                 "wps": l["wps"],
                                 "writeScope": l["writeScope"]}
                                for l in plan["lanes"]]}, ensure_ascii=True))
    return 5 if plan["serialRequired"] else 0


if __name__ == "__main__":
    sys.exit(main())
