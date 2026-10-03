#!/usr/bin/env python3
"""agent_failure_mode_advise.py — symptom -> failure-mode -> rail advisor.

Contract DEMO1-DEVIN-QUARANTINE-AGENT-GRAFT-20260929 §C items 3+4. Local
heuristic over the curated catalog (configs/agent-failure-mode-catalog.yaml);
no paid API, no raw rollout reads — the catalog MD/YAML is the only corpus.

  --symptom "<what you are seeing>"   top matching modes + their rails
  --list                              catalog ids in order
  --mode <id>                         one mode's full entry
  judge --action "..." [--paths a,b] [--agent devin]
      thin-CLI graft for demo1-vibe-selfask-judge-auto: runs
      agent_vibe_auto_decision.decide() first, then layers catalog `vibe`
      priors on top. Priors may add ASK_ONCE gates (effect: ask_once) or
      advisory notes (effect: note); they NEVER downgrade a HOLD/ASK_ONCE.

Exits: advise/list/mode 0 ok, 3 no-match, 4 catalog unreadable/missing;
judge mirrors decide: 0 AUTO, 3 ASK_ONCE, 4 HOLD. Usage errors = 2.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

SCHEMA = "awx.agent-failure-mode-advise.v1"
DEFAULT_CATALOG = "configs/agent-failure-mode-catalog.yaml"


def load_catalog(root: Path, catalog: str | None) -> dict:
    path = Path(catalog) if catalog else root / DEFAULT_CATALOG
    try:
        import yaml
        doc = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError:
        raise SystemExit(_err("catalog-missing", str(path), 4))
    except Exception as exc:
        raise SystemExit(_err("catalog-unreadable",
                              f"{path}: {type(exc).__name__}", 4))
    if not isinstance(doc, dict) or not doc.get("modes"):
        raise SystemExit(_err("catalog-empty", str(path), 4))
    return doc


def _err(reason: str, detail: str, code: int) -> int:
    print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                      "reason": reason, "detail": detail}))
    return code


def score_mode(symptom: str, mode: dict) -> int:
    low = symptom.casefold()
    score = 0
    for kw in mode.get("symptoms") or []:
        kw = str(kw).strip().casefold()
        if not kw:
            continue
        if kw in low:
            score += 3 + min(len(kw) // 8, 3)
            continue
        parts = [w for w in re.split(r"\W+", kw) if len(w) >= 4]
        hit = sum(1 for w in parts if w in low)
        if hit == len(parts) and parts:
            score += 2
        elif hit:
            score += 1
    for sp in mode.get("stop_patterns") or []:
        if str(sp).casefold() in low:
            score += 2
    return score


def advise(symptom: str, modes: list[dict], top: int) -> dict:
    ranked = []
    for m in modes:
        s = score_mode(symptom, m)
        if s:
            ranked.append((s, m))
    ranked.sort(key=lambda x: (-x[0], str(x[1].get("id"))))
    out = [{
        "id": m.get("id"), "score": s, "severity": m.get("severity"),
        "summary": m.get("summary"),
        "recommended_rail": m.get("graft"), "invoke": m.get("invoke"),
        "agent_hint": m.get("agent_hint"),
    } for s, m in ranked[:top]]
    return {"matches": out,
            "matched": len(out), "catalogModes": len(modes)}


def judge(args, modes: list[dict], root: Path) -> tuple[dict, int]:
    try:
        import agent_vibe_auto_decision as vibe
    except ImportError as exc:
        return {"error": "vibe-decision-unavailable",
                "detail": type(exc).__name__}, 2
    paths = [p.strip() for p in (args.paths or "").split(",") if p.strip()]
    base = vibe.decide(args.action, paths, args.agent, root)
    priors = []
    extra_hits = []
    for m in modes:
        vb = m.get("vibe") or {}
        pat = vb.get("pattern")
        if not pat:
            continue
        try:
            if not re.search(str(pat), args.action or "", re.I):
                continue
        except re.error:
            continue
        if vb.get("effect") == "ask_once":
            extra_hits.append({"code": f"catalog:{m.get('id')}",
                               "question": vb.get("question")
                               or f"Catalog mode {m.get('id')} matched — "
                                  "check its rail before proceeding?"})
            priors.append({"mode": m.get("id"), "effect": "ask_once"})
        else:
            priors.append({"mode": m.get("id"), "effect": "note",
                           "hint": m.get("agent_hint")})
    verdict = base["verdict"]
    gates = list(base.get("gates") or [])
    reason = base["reason"]
    question = base.get("question")
    # Priors only tighten: AUTO -> ASK_ONCE when a catalog gate fires.
    # HOLD stays HOLD, existing ASK_ONCE keeps precedence.
    if extra_hits and verdict == "AUTO":
        verdict = "ASK_ONCE"
        gates += [h["code"] for h in extra_hits]
        reason = ("catalog prior gate: "
                  + ",".join(h["code"] for h in extra_hits))
        question = extra_hits[0]["question"]
    elif extra_hits:
        gates += [h["code"] for h in extra_hits]
    payload = {"schemaVersion": SCHEMA + ".judge", "base": base["verdict"],
                   "verdict": verdict, "reason": reason,
                   "question": question if verdict == "ASK_ONCE" else None,
                   "resume": base.get("resume"), "gates": gates,
                   "counterexamples": base.get("counterexamples"),
                   "catalogPriors": priors}
    line = (f"SELFASK_JUDGE {verdict} (catalog-priors) | {reason} | "
            f"{','.join(paths) or '-'}")
    return {"line": line, "payload": payload}, {
        "AUTO": 0, "ASK_ONCE": 3, "HOLD": 4}[verdict]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--catalog", default=None)
    ap.add_argument("--symptom")
    ap.add_argument("--mode")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--top", type=int, default=3)
    ap.add_argument("--json", action="store_true")
    sub = ap.add_subparsers(dest="sub")
    jp = sub.add_parser("judge", help="self-ask judge + catalog priors")
    jp.add_argument("--action", required=True)
    jp.add_argument("--paths", default="")
    jp.add_argument("--agent", default="devin")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    root = Path(args.root).resolve()
    doc = load_catalog(root, args.catalog)
    modes = doc.get("modes") or []

    if args.sub == "judge":
        res, code = judge(args, modes, root)
        if args.json:
            print(json.dumps(res.get("payload", res), ensure_ascii=False))
        else:
            print(res.get("line") or json.dumps(res))
            pl = res.get("payload") or {}
            if pl.get("verdict") == "ASK_ONCE" and pl.get("question"):
                print("ASK_ONCE question:", pl["question"])
            for pr in pl.get("catalogPriors") or []:
                if pr.get("effect") == "note" and pr.get("hint"):
                    print(f"note[{pr['mode']}]: {pr['hint'][:140]}")
        return code

    if args.list:
        print(json.dumps({"schemaVersion": SCHEMA,
                          "modes": [m.get("id") for m in modes]},
                         ensure_ascii=False))
        return 0
    if args.mode:
        for m in modes:
            if m.get("id") == args.mode:
                print(json.dumps({"schemaVersion": SCHEMA, "mode": m},
                                 ensure_ascii=False, indent=1))
                return 0
        return _err("mode-not-found", args.mode, 3)
    if args.symptom:
        res = advise(args.symptom, modes, max(1, args.top))
        res["schemaVersion"] = SCHEMA
        res["symptom"] = args.symptom[:160]
        if args.json:
            print(json.dumps(res, ensure_ascii=False))
        else:
            if not res["matches"]:
                print("no catalog match — record a new mode candidate")
            for m in res["matches"]:
                print(f"[{m['severity']}] {m['id']} (score {m['score']})")
                print(f"    rail: {m['recommended_rail']}")
                if m.get("invoke"):
                    print(f"    run:  {m['invoke']}")
                if m.get("agent_hint"):
                    print(f"    hint: {str(m['agent_hint'])[:140]}")
        return 0 if res["matches"] else 3
    return _err("no-action", "pass --symptom|--list|--mode|judge", 2)


if __name__ == "__main__":
    raise SystemExit(main())
