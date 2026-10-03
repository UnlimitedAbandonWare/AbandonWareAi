#!/usr/bin/env python3
"""test_model_policy — resolve/check/record/budget for the agent test-model
policy in configs/agent-test-model-policy.yaml (DEMO1-DEVIN-TEST-MODEL-API-
POLICY-20261002). Standard library only; network limited to the catalog GET
on a 127.0.0.1/localhost base unless --catalog supplies a fixture.

  resolve --purpose quality [--now ISO] [--catalog f.json] [--base URL]
  resolve --purpose auto --prompt-file f.txt [--has-attachment]  (classify first)
  plan    --prompts-file f.json|f.txt                          (auto per prompt)
  check   --selected ID --observed ID [--now ISO]
  record  --agent A --purpose P --model ID --code C [--run R] [--kind K]
  budget  [--run R]

Exit codes: resolve 0 RESOLVED / 6 BLOCKED_API|NO_CANDIDATE / 2 usage or
UNKNOWN_PURPOSE; check 0 OK / 1 SILENT_FALLBACK|LOCAL_BEFORE_CUTOFF;
budget 0 OK / 6 BUDGET_EXCEEDED; record 0. Every command prints one JSON
object on stdout.
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_POLICY = ROOT / "configs" / "agent-test-model-policy.yaml"
DEFAULT_BASE = "http://127.0.0.1:18180"
KST = timezone(timedelta(hours=9), "KST")
OK_STATUSES = {"configured", "installed"}
AUTH_OR_QUOTA_CODES = {"401", "403", "429"}
TEST_MESSAGE = "[devin-test] model-policy probe — reply with the single word OK."


# ---------- strict-subset YAML reader (see header note in the policy file) --

def _scalar(v):
    if v.startswith("[") and v.endswith("]"):
        inner = v[1:-1].strip()
        return [_scalar(x.strip()) for x in inner.split(",")] if inner else []
    if len(v) >= 2 and v[0] == v[-1] and v[0] in ("'", '"'):
        return v[1:-1]
    if v in ("null", "~", "None"):
        return None
    if v.lower() == "true":
        return True
    if v.lower() == "false":
        return False
    try:
        return int(v)
    except ValueError:
        try:
            return float(v)
        except ValueError:
            return v


def load_policy(path=None):
    """Parse the policy file's fixed YAML subset into dicts/lists/scalars."""
    path = Path(path) if path else DEFAULT_POLICY
    items = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if "#" in raw:  # this file never uses '#' inside quotes
            raw = raw.split("#", 1)[0]
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip())
        items.append((indent, raw.strip()))
    pos = [0]

    def parse(indent):
        out = None
        while pos[0] < len(items):
            ind, content = items[pos[0]]
            if ind < indent:
                break
            if ind > indent:
                raise ValueError(f"unexpected indent at {content!r}")
            if content.startswith("- "):
                if out is None:
                    out = []
                out.append(_scalar(content[2:].strip()))
                pos[0] += 1
                continue
            if out is None:
                out = {}
            key, _, val = content.partition(":")
            key = key.strip()
            val = val.strip()
            pos[0] += 1
            if val == "":
                if pos[0] < len(items) and items[pos[0]][0] > ind:
                    out[key] = parse(items[pos[0]][0])
                else:
                    out[key] = None
            else:
                out[key] = _scalar(val)
        return out

    return parse(items[0][0]) if items else {}


# ---------- catalog / time / lane helpers ----------------------------------

def fetch_catalog(base=DEFAULT_BASE, timeout=15):
    url = base.rstrip("/") + "/api/chat/models"
    req = urllib.request.Request(url, method="GET",
                                 headers={"User-Agent": "devin-test-model-policy/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read(2 * 1024 * 1024).decode("utf-8"))


def load_catalog(path=None, base=DEFAULT_BASE):
    if path:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    return fetch_catalog(base)


def parse_now(text):
    if not text:
        return datetime.now(KST)
    dt = datetime.fromisoformat(text)
    return dt if dt.tzinfo else dt.replace(tzinfo=KST)


def mode_at(policy, now):
    gate = (policy.get("dateGate") or {})
    until = gate.get("apiFirstUntil", "2026-12-30")
    end = datetime.fromisoformat(str(until) + "T23:59:59.999999").replace(tzinfo=KST)
    return "api_first" if now <= end else (gate.get("afterMode") or "dynamic")


def lane_of(model_id, policy):
    for p in (policy.get("apiPrefixes") or []):
        if str(model_id).startswith(p):
            return "api"
    return "local"


def remote_prefix_stripped(model_id):
    s = str(model_id or "")
    return s.split(":", 1)[1] if s.startswith("chatgpt-oauth:") else s


# ---------- prompt classification (auto purpose) -----------------------------

def load_prompts(path):
    """prompts file: .json -> array of strings or {text|prompt|question,
    attachment|hasAttachment} objects; any other suffix -> one prompt per
    non-blank line. Returns list of {text, attachment}."""
    p = Path(path)
    raw = p.read_text(encoding="utf-8")
    items = []
    if p.suffix.lower() == ".json":
        data = json.loads(raw)
        if not isinstance(data, list):
            raise ValueError("prompts json must be an array")
        for row in data:
            if isinstance(row, str):
                items.append({"text": row, "attachment": False})
            elif isinstance(row, dict):
                text = row.get("text") or row.get("prompt") or row.get("question") or ""
                items.append({"text": str(text),
                              "attachment": bool(row.get("attachment") or row.get("hasAttachment"))})
    else:
        for line in raw.splitlines():
            if line.strip():
                items.append({"text": line.strip(), "attachment": False})
    return items


def classify_prompt(text, has_attachment, policy):
    """First-match order documented under autoClassify in the policy file:
    evidence keyword -> quality; attachment -> practice_reasoning;
    code marker or long prompt -> practice_reasoning; else practice_chat.
    Keyword/length heuristics only — never a model call."""
    ac = policy.get("autoClassify") or {}
    ev_kw = ac.get("evidenceKeywords") or []
    code_m = ac.get("codeMarkers") or []
    long_n = int(ac.get("longPromptChars") or 800)
    low = str(text).lower()
    ev = next((k for k in ev_kw if str(k).lower() in low), None)
    if ev is not None:
        suffix = " + attachment" if has_attachment else ""
        return "quality", f"evidence keyword '{ev}'{suffix}"
    if has_attachment:
        return "practice_reasoning", "attachment present"
    code = next((m for m in code_m if str(m).lower() in low), None)
    reasons = []
    if code is not None:
        reasons.append(f"code marker '{code.strip()}'")
    if len(text) >= long_n:
        reasons.append(f"{len(text)} chars")
    if reasons:
        return "practice_reasoning", " + ".join(reasons)
    return "practice_chat", "short conversational prompt"


# ---------- usage log -------------------------------------------------------

def usage_path(policy, override=None):
    p = override or (policy.get("usageLog") if policy else None)
    p = Path(p) if p else ROOT / "data/agent-handoff/test-model-policy/usage.jsonl"
    return p if p.is_absolute() else ROOT / p


def read_usage(path):
    rows = []
    if path and Path(path).exists():
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def excluded_models(usage_rows, purpose):
    bad = set()
    for r in usage_rows:
        code = str(r.get("code", ""))
        if r.get("purpose") == purpose and (
                code in AUTH_OR_QUOTA_CODES or code.startswith(("quota", "payment"))):
            bad.add(r.get("model"))
    return bad


# ---------- commands --------------------------------------------------------

def resolve(purpose, *, catalog=None, policy=None, now=None, usage_log=None):
    policy = policy if policy is not None else load_policy()
    now = now or datetime.now(KST)
    mode = mode_at(policy, now)
    purposes = policy.get("purposes") or {}
    if purpose not in purposes:
        return {"verdict": "UNKNOWN_PURPOSE", "purpose": purpose,
                "known": sorted(purposes)}
    prefer = list((purposes.get(purpose) or {}).get("prefer") or [])
    never = set(policy.get("neverSelect") or [])
    rows = {c.get("id"): c for c in (catalog or [])}
    usage = read_usage(usage_path(policy, usage_log))
    excluded = excluded_models(usage, purpose)

    order = list(prefer)
    if mode == "dynamic" and purpose != "local_fallback":
        local_pref = ((purposes.get("local_fallback") or {}).get("prefer") or [])
        order += [m for m in local_pref if m not in order]
        order += [c.get("id") for c in (catalog or [])
                  if lane_of(c.get("id"), policy) == "local" and c.get("id") not in order]

    candidates = []
    for rank, mid in enumerate(order):
        if mid in never:
            candidates.append({"rank": rank, "id": mid, "lane": lane_of(mid, policy),
                               "available": False, "excluded": "never_select"})
            continue
        row = rows.get(mid)
        if row is None:
            candidates.append({"rank": rank, "id": mid, "lane": lane_of(mid, policy),
                               "available": False, "excluded": "not_in_catalog"})
            continue
        ok = bool(row.get("selectable")) and row.get("status") in OK_STATUSES
        excl = None
        if mid in excluded:
            excl, ok = "auth_or_quota_recorded", False
        elif not row.get("selectable"):
            excl, ok = row.get("reason") or "not_selectable", False
        elif row.get("status") not in OK_STATUSES:
            excl, ok = f"status:{row.get('status')}", False
        if mode == "api_first" and lane_of(mid, policy) == "local" and purpose != "local_fallback":
            excl, ok = "local_before_cutoff", False
        candidates.append({"rank": rank, "id": mid, "modelId": row.get("modelId"),
                           "provider": row.get("provider"), "lane": lane_of(mid, policy),
                           "status": row.get("status"), "selectable": row.get("selectable"),
                           "available": ok, "excluded": excl})

    selected = next((c["id"] for c in candidates if c["available"]), None)
    if selected is not None:
        for c in candidates:
            c["chosen"] = (c["id"] == selected)
        return {"verdict": "RESOLVED", "mode": mode, "purpose": purpose,
                "selected": selected,
                "alternates": [c["id"] for c in candidates
                               if c["available"] and c["id"] != selected],
                "candidates": candidates}
    verdict = "BLOCKED_API" if (mode == "api_first" and purpose != "local_fallback") \
        else "NO_CANDIDATE"
    return {"verdict": verdict, "mode": mode, "purpose": purpose,
            "selected": None, "candidates": candidates}


def canonical_id(model_id, policy=None, catalog=None):
    s = str(model_id or "")
    if catalog:
        ids = {c.get("id") for c in catalog}
        if s in ids:
            return s
        by_model = {c.get("modelId"): c.get("id") for c in catalog}
        if s in by_model:
            return by_model[s]
    policy = policy or {}
    known = {remote_prefix_stripped(m): m
             for spec in (policy.get("purposes") or {}).values()
             for m in (spec.get("prefer") or [])}
    return known.get(s, s)


def same_model(selected, observed, catalog=None):
    if selected == observed:
        return True
    if remote_prefix_stripped(selected) == remote_prefix_stripped(observed):
        if str(selected).startswith("chatgpt-oauth:") or str(observed).startswith("chatgpt-oauth:"):
            return True
    if catalog:
        by_model = {c.get("modelId"): c.get("id") for c in catalog}
        sel_id = by_model.get(selected, selected)
        obs_id = by_model.get(observed, observed)
        if sel_id and sel_id == obs_id:
            return True
    return False


def check(selected, observed, *, policy=None, now=None, catalog=None):
    policy = policy if policy is not None else load_policy()
    now = now or datetime.now(KST)
    mode = mode_at(policy, now)
    out = {"selected": selected, "observed": observed, "mode": mode}
    if not observed or str(observed).strip().lower() in ("null", "none", "-",
                                                         "unknown", ""):
        out["verdict"] = "UNOBSERVED"  # no model answered; not a fallback
        return out
    if same_model(selected, observed, catalog):
        out["verdict"] = "OK"
        return out
    obs_id = canonical_id(observed, policy, catalog)
    if (lane_of(obs_id, policy) == "local"
            and lane_of(selected, policy) == "api" and mode == "api_first"):
        out["verdict"] = "LOCAL_BEFORE_CUTOFF"
        return out
    out["verdict"] = "SILENT_FALLBACK"
    return out


def record(agent, purpose, model, code, *, run=None, kind="generation",
           usage_log=None, policy=None):
    policy = policy if policy is not None else load_policy()
    path = usage_path(policy, usage_log)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {"ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
           "agent": agent, "purpose": purpose, "model": model,
           "code": str(code), "run": run, "kind": kind, "tool": "test_model_policy"}
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    return {"recorded": True, "path": str(path), "row": row}


def budget(run=None, *, usage_log=None, policy=None):
    policy = policy if policy is not None else load_policy()
    limit = int((policy.get("limits") or {}).get("maxApiGenerationsPerRun", 25))
    rows = [r for r in read_usage(usage_path(policy, usage_log))
            if (r.get("kind") or "generation") == "generation"
            and (run is None or r.get("run") == run)]
    used = len(rows)
    return {"verdict": "BUDGET_EXCEEDED" if used >= limit else "OK",
            "limit": limit, "used": used, "remaining": max(0, limit - used),
            "run": run}


def plan(prompts, *, catalog=None, policy=None, now=None, usage_log=None):
    """Per-question auto classification + resolve; a question whose model
    differs from the previous selected model carries newConversation=true."""
    policy = policy if policy is not None else load_policy()
    now = now or datetime.now(KST)
    items = []
    prev_selected = None
    for i, row in enumerate(prompts):
        purpose, why = classify_prompt(row.get("text", ""), row.get("attachment"), policy)
        res = resolve(purpose, catalog=catalog, policy=policy, now=now,
                      usage_log=usage_log)
        selected = res.get("selected")
        items.append({
            "index": i,
            "promptSummary": str(row.get("text", ""))[:40],
            "purpose": purpose,
            "autoReason": f"auto→{purpose}: {why}",
            "verdict": res.get("verdict"),
            "selected": selected,
            "newConversation": i == 0 or selected != prev_selected,
        })
        if selected:
            prev_selected = selected
    ok = any(it["verdict"] == "RESOLVED" for it in items)
    verdict = "PLANNED" if ok else ("BLOCKED_API" if items else "NO_PROMPTS")
    return {"verdict": verdict, "mode": mode_at(policy, now), "count": len(items),
            "apiCallsPlanned": sum(1 for it in items if it["verdict"] == "RESOLVED"),
            "items": items}


# ---------- probe-facing step (local send only) ------------------------------

def model_policy_step(target, origin, purpose, *, catalog_path=None,
                      usage_log=None, send=True, run=None, timeout=None,
                      now=None, agent="devin-probe"):
    if timeout is None:
        # 답 대기 상한: chat.run.max-duration-seconds + 20s (env 없으면 620초).
        try:
            timeout = max(1, int(os.environ.get("CHAT_RUN_MAX_DURATION_SECONDS", "600"))) + 20
        except ValueError:
            timeout = 620
    policy = load_policy()
    try:
        catalog = load_catalog(catalog_path, base=origin)
    except (OSError, ValueError, urllib.error.URLError) as exc:
        return {"purpose": purpose, "verdict": "CATALOG_UNAVAILABLE",
                "error": type(exc).__name__}
    res = resolve(purpose, catalog=catalog, policy=policy, now=now,
                  usage_log=usage_log)
    out = {"purpose": purpose, "mode": res["mode"], "verdict": res["verdict"],
           "selected": res.get("selected")}
    if res["verdict"] != "RESOLVED":
        return out
    if not send:
        out["send"] = "skipped(--no-send)"
        return out
    if target != "local":
        out["send"] = "skipped(non-local target)"  # AUTO_DECISION: public sends
        return out                                # are never implicit
    cap = budget(run=run, usage_log=usage_log, policy=policy)
    if cap["verdict"] == "BUDGET_EXCEEDED":
        out["send"] = "blocked"
        out["budget"] = cap
        return out
    body = json.dumps({
        "message": TEST_MESSAGE, "question": TEST_MESSAGE,
        "model": res["selected"], "strictModelSelection": True,
        "useRag": False, "useWebSearch": False, "searchMode": "OFF",
        "maxTokens": 64,
    }).encode("utf-8")
    url = origin.rstrip("/") + "/api/chat/sync"
    code, observed = None, None
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json", "Accept": "application/json",
        "User-Agent": "devin-test-model-policy/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            code = resp.status
            observed = resp.headers.get("x-model-used")
            data = json.loads(resp.read(4 * 1024 * 1024).decode("utf-8"))
            observed = data.get("modelUsed") or observed
    except urllib.error.HTTPError as exc:
        code = exc.code
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
        code = type(exc).__name__
    record(agent, purpose, res["selected"], code if code is not None else "error",
           run=run, usage_log=usage_log, policy=policy)
    chk = check(res["selected"], observed, policy=policy, now=now,
                catalog=catalog)
    out.update({"send": "sent", "http": code, "observedModel": observed,
                "checkVerdict": chk["verdict"]})
    return out


# ---------- CLI ---------------------------------------------------------------

def _common(parser):
    parser.add_argument("--catalog", help="offline catalog JSON fixture")
    parser.add_argument("--usage-log", help="usage.jsonl override")
    parser.add_argument("--policy", help="policy yaml override")
    parser.add_argument("--now", help="ISO datetime override (KST if naive)")


def _emit(payload):
    print(json.dumps(payload, ensure_ascii=False))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("resolve")
    p.add_argument("--purpose", required=True,
                   help="policy purpose, or 'auto' to classify --prompt-file")
    p.add_argument("--prompt-file", help="prompt text file (required for --purpose auto)")
    p.add_argument("--has-attachment", action="store_true",
                   help="auto classification: the prompt carries an attachment")
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--run")
    _common(p)
    p = sub.add_parser("plan")
    p.add_argument("--prompts-file", required=True,
                   help=".json array (string or {text,attachment}) or one prompt per line")
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--run")
    _common(p)
    p = sub.add_parser("check")
    p.add_argument("--selected", required=True)
    p.add_argument("--observed", required=True)
    _common(p)
    p = sub.add_parser("record")
    p.add_argument("--agent", required=True)
    p.add_argument("--purpose", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--code", required=True)
    p.add_argument("--run")
    p.add_argument("--kind", default="generation",
                   choices=["generation", "resolve", "check"])
    _common(p)
    p = sub.add_parser("budget")
    p.add_argument("--run")
    _common(p)
    args = ap.parse_args(argv)

    policy = load_policy(args.policy)
    now = parse_now(args.now)

    if args.cmd == "resolve":
        purpose = args.purpose
        auto_reason = None
        if purpose == "auto":
            if not args.prompt_file:
                _emit({"verdict": "USAGE",
                       "error": "--purpose auto requires --prompt-file"})
                return 2
            try:
                text = Path(args.prompt_file).read_text(encoding="utf-8")
            except OSError as exc:
                _emit({"verdict": "USAGE", "error": str(exc)[:200]})
                return 2
            purpose, why = classify_prompt(text, args.has_attachment, policy)
            auto_reason = f"auto→{purpose}: {why}"
        try:
            catalog = load_catalog(args.catalog, base=args.base)
        except (OSError, ValueError, urllib.error.URLError) as exc:
            _emit({"verdict": "CATALOG_UNAVAILABLE", "error": str(exc)[:200]})
            return 2
        out = resolve(purpose, catalog=catalog, policy=policy, now=now,
                      usage_log=args.usage_log)
        if auto_reason:
            out["classifiedPurpose"] = purpose
            out["autoReason"] = auto_reason
        out["at"] = now.isoformat()
        _emit(out)
        return {"RESOLVED": 0, "BLOCKED_API": 6, "NO_CANDIDATE": 6}.get(
            out["verdict"], 2)
    if args.cmd == "plan":
        try:
            prompts = load_prompts(args.prompts_file)
        except (OSError, ValueError) as exc:
            _emit({"verdict": "USAGE", "error": str(exc)[:200]})
            return 2
        try:
            catalog = load_catalog(args.catalog, base=args.base)
        except (OSError, ValueError, urllib.error.URLError) as exc:
            _emit({"verdict": "CATALOG_UNAVAILABLE", "error": str(exc)[:200]})
            return 2
        out = plan(prompts, catalog=catalog, policy=policy, now=now,
                   usage_log=args.usage_log)
        out["at"] = now.isoformat()
        _emit(out)
        return 0 if out["verdict"] == "PLANNED" else 6
    if args.cmd == "check":
        catalog = None
        if args.catalog:
            catalog = load_catalog(args.catalog)
        out = check(args.selected, args.observed, policy=policy, now=now,
                    catalog=catalog)
        _emit(out)
        return 0 if out["verdict"] == "OK" else 1
    if args.cmd == "record":
        _emit(record(args.agent, args.purpose, args.model, args.code,
                     run=args.run, kind=args.kind,
                     usage_log=args.usage_log, policy=policy))
        return 0
    if args.cmd == "budget":
        out = budget(run=args.run, usage_log=args.usage_log, policy=policy)
        _emit(out)
        return 6 if out["verdict"] == "BUDGET_EXCEEDED" else 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
