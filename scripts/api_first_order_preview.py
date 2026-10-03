#!/usr/bin/env python3
"""api_first_order_preview -- show the EFFECTIVE chat-route order as configured
(legacy) vs the hypothetical order under llmrouter.api-first (API first, local
standby), computed from config files only. Zero live calls, zero secret reads.

Inputs:
  main/resources/application-llm.yaml   -> llmrouter.models[*]
  main/resources/configs/api-routing.yaml -> policy.order + route tiers

`${VAR:default}` placeholders resolve to their DEFAULT; whether VAR is set in
the current process env is shown as `env: VAR=set|unset` -- the value is never
read beyond existence, never printed. `.env` files are not opened. A
`credential-env` entry reports only env presence (`credential_unset(NAME)`).

Usage:
  python -B scripts/api_first_order_preview.py [--root .] [--json]
      [--role MAIN_DEFAULT|MAIN_FAST|MAIN_HIGH] [--out-json P] [--out-md P]

Role note: the YAML carries `stage` (chat/judge/coder/vision), not role names.
When --role is given and no role mapping exists in config, output marks
`roleInfo: absent` and does not guess; the chat stage list is still shown.

Exit 0 always (read-only preview); 2 on unreadable YAML.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

LLM_YAML = "main/resources/application-llm.yaml"
ROUTING_YAML = "main/resources/configs/api-routing.yaml"

PLACEHOLDER_RE = re.compile(r"\$\{([^{}]+)\}")
LOCAL_PROVIDERS = {"local"}
# opencode = external gateway route that is not a paid cloud; kept visible.
OAUTH_NOTE = ("chatgpt-oauth route (ChatGptOAuthRegistration) is excluded from "
              "auto candidates by RoutingProfileResolver -- kept as manual-only")


def resolve_placeholders(value, props, env_seen, depth=0):
    """Resolve Spring-style ${NAME:default}. Uppercase names are env vars
    (presence recorded, default used as the displayed value). Dotted lowercase
    names are property-path lookups inside the same YAML doc."""
    if not isinstance(value, str) or depth > 8:
        return value

    def repl(m):
        expr = m.group(1)
        if ":" in expr:
            name, default = expr.split(":", 1)
        else:
            name, default = expr, ""
        name = name.strip()
        if name and name.upper() == name and re.match(r"^[A-Z0-9_]+$", name):
            env_seen[name] = name in os.environ
            return resolve_placeholders(default, props, env_seen, depth + 1)
        # property path inside the yaml doc
        node = props
        for part in name.split("."):
            if isinstance(node, dict) and part in node:
                node = node[part]
            else:
                node = None
                break
        if node is not None and not isinstance(node, (dict, list)):
            return resolve_placeholders(str(node), props, env_seen, depth + 1)
        return resolve_placeholders(default, props, env_seen, depth + 1)

    prev = None
    cur = value
    guard = 0
    while prev != cur and guard < 8:
        prev = cur
        cur = PLACEHOLDER_RE.sub(repl, cur)
        guard += 1
    return cur


def as_bool(v):
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in ("true", "1", "yes", "on")


def as_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def provider_routes(routing_doc):
    """routes.llm[] keyed by route id -> {env:[names], tier}."""
    out = {}
    for r in ((routing_doc.get("routes") or {}).get("llm") or []):
        if isinstance(r, dict) and r.get("id"):
            out[r["id"]] = {"env": list(r.get("env") or []),
                            "tier": r.get("tier", "")}
    return out


def collect_routes(models, props, env_seen, prov_routes):
    rows = []
    for key in sorted(models):
        spec = models[key] or {}
        if not isinstance(spec, dict):
            continue
        row = {"id": key}
        for f in ("provider", "stage"):
            row[f] = resolve_placeholders(str(spec.get(f, "")), props, env_seen)
        row["name"] = resolve_placeholders(str(spec.get("name", "")), props, env_seen)
        row["weight"] = as_float(resolve_placeholders(spec.get("weight", 0), props, env_seen))
        row["fallbackOnly"] = as_bool(
            resolve_placeholders(spec.get("fallback-only", spec.get("fallback_only", False)),
                                 props, env_seen))
        row["enabled"] = as_bool(
            resolve_placeholders(spec.get("enabled", True), props, env_seen))
        row["baseUrl"] = resolve_placeholders(str(spec.get("base-url", "")), props, env_seen)
        cred = spec.get("credential-env") or spec.get("credential_env")
        if cred:
            cred_name = resolve_placeholders(str(cred), props, env_seen)
            if cred_name:
                env_seen[cred_name] = cred_name in os.environ
            row["tier"] = (prov_routes.get(row["provider"]) or {}).get("tier", "")
            row["credentialEnv"] = cred_name
            row["credential"] = ("set" if os.environ.get(cred_name) else
                                 f"credential_unset({cred_name})")
        else:
            row["credentialEnv"] = ""
            prov = prov_routes.get(row["provider"])
            if prov and prov["env"]:
                row["tier"] = prov["tier"]
                marks = []
                for env_name in prov["env"]:
                    env_seen[env_name] = env_name in os.environ
                    marks.append(f"{env_name}={'set' if env_seen[env_name] else 'unset'}")
                row["credential"] = "routes.llm env: " + ", ".join(marks)
                if not any(env_seen[e] for e in prov["env"]):
                    row["credential"] += " -> credential_unset"
            else:
                row["tier"] = ""
                row["credential"] = "no credential declared"
        rows.append(row)
    return rows


def legacy_order(rows):
    """Current effective order: enabled chat-stage routes, weight desc, then
    declared id order; fallback-only routes trail as backup tier."""
    chat = [r for r in rows if r["stage"] == "chat" and r["enabled"]]
    primary = sorted([r for r in chat if not r["fallbackOnly"]],
                     key=lambda r: (-r["weight"], r["id"]))
    backup = sorted([r for r in chat if r["fallbackOnly"]], key=lambda r: r["id"])
    return primary + backup


def api_first_order(rows):
    """Hypothetical llmrouter.api-first=true: configured cloud routes first
    (weight desc), local routes demoted to fallback-only/weight-0 standby.
    ChatGPT OAuth stays manual-only (excluded by resolver)."""
    chat = [r for r in rows if r["stage"] == "chat" and r["enabled"]]
    clouds = sorted([r for r in chat if r["provider"] not in LOCAL_PROVIDERS],
                    key=lambda r: (-r["weight"], r["id"]))
    locals_ = [dict(r, effectiveFallbackOnly=True, effectiveWeight=0.0)
               for r in chat if r["provider"] in LOCAL_PROVIDERS]
    out = [dict(r, effectiveFallbackOnly=r["fallbackOnly"], effectiveWeight=r["weight"])
           for r in clouds]
    out += locals_
    return out


def md_table(rows, api_first=False):
    cols = ("order", "id", "provider", "stage", "name", "weight",
            "fallbackOnly", "credential") if not api_first else (
        "order", "id", "provider", "name", "effectiveWeight",
        "effectiveFallbackOnly", "credential")
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for i, r in enumerate(rows, 1):
        cells = [str(r.get(c, "")) for c in cols[1:]]
        lines.append("| %d | %s |" % (i, " | ".join(cells)))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--role", choices=["MAIN_DEFAULT", "MAIN_FAST", "MAIN_HIGH"])
    ap.add_argument("--out-json")
    ap.add_argument("--out-md")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    try:
        llm_doc = yaml.safe_load((root / LLM_YAML).read_text(encoding="utf-8")) or {}
        routing_doc = yaml.safe_load((root / ROUTING_YAML).read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as e:
        print(f"yaml_unreadable: {e}", file=sys.stderr)
        return 2

    props = llm_doc
    env_seen = {}
    models = (llm_doc.get("llmrouter") or {}).get("models") or {}
    rows = collect_routes(models, props, env_seen, provider_routes(routing_doc))

    role_info = "absent"
    if args.role:
        # config has stages, not roles -> do not guess a mapping
        role_info = f"absent (role={args.role}; config carries stage only)"

    legacy = legacy_order(rows)
    api_first = api_first_order(rows)
    policy_order = ((routing_doc.get("policy") or {}).get("order")) or []

    payload = {
        "schema": "awx.api-first-order-preview.v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "policyOrderLegacy": policy_order,
        "roleInfo": role_info,
        "oauthNote": OAUTH_NOTE,
        "envPresence": {k: ("set" if v else "unset") for k, v in sorted(env_seen.items())},
        "legacy": legacy,
        "apiFirst": api_first,
    }

    if args.out_json:
        Path(args.out_json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out_json).write_text(json.dumps(payload, indent=2, ensure_ascii=False),
                                       encoding="utf-8")
    if args.out_md:
        md = ["# api-first order preview", "",
              f"generated: {payload['generatedAt']}",
              f"policy.order (legacy SSOT): {policy_order}",
              f"roleInfo: {role_info}",
              f"note: {OAUTH_NOTE}", "",
              "## legacy effective order (as configured)", "", md_table(legacy),
              "", "## api-first hypothetical (LLMROUTER_API_FIRST=true)", "",
              md_table(api_first, api_first=True), "",
              "## env presence (names only, values never read)", ""]
        md += [f"- {k}: {'set' if v else 'unset'}" for k, v in sorted(env_seen.items())]
        Path(args.out_md).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out_md).write_text("\n".join(md) + "\n", encoding="utf-8")

    if args.json or not (args.out_json or args.out_md):
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(json.dumps({"legacyOrder": [r["id"] for r in legacy],
                          "apiFirstOrder": [r["id"] for r in api_first],
                          "roleInfo": role_info,
                          "envChecked": len(env_seen)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
