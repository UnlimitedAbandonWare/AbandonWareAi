#!/usr/bin/env python3
"""apikit CLI — `check` (free only, default), `call <provider> --paid`,
`replay <provider> --from req.json [--paid]`. Exit 0 ok / 3 failure / 2 error."""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import json
import os
import sys
import urllib.parse
from pathlib import Path

try:
    from . import common
    from .providers import PROVIDERS
except ImportError:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import common
    from providers import PROVIDERS


def _ctx(args, paid=False):
    secrets = common.load_secrets()
    scopes = common.scope_envs()
    secret_values = set(secrets.values())
    cred_markers = ("KEY", "TOKEN", "SECRET", "PASSWORD")
    for scope in scopes.values():
        for name, value in scope.items():
            if any(m in name for m in cred_markers) and value:
                secret_values.add(str(value))
    secret_values = {v for v in secret_values if len(v) >= 6}
    return {
        "timeout": getattr(args, "timeout", common.DEFAULT_TIMEOUT_S),
        "paid": paid,
        "secrets": secrets,
        "scopes": scopes,
        "secret_values": tuple(secret_values),
        "stamp": datetime.datetime.now().strftime("%Y%m%d-%H%M%S"),
    }


def _select(args):
    names = list(getattr(args, "providers", None) or [])
    only = getattr(args, "only", None)
    if only:
        names += [n.strip() for n in only.split(",") if n.strip()]
    names = [n.lower() for n in names] or list(PROVIDERS)
    bad = [n for n in names if n not in PROVIDERS]
    if bad:
        print("unknown provider(s): %s — known: %s"
              % (", ".join(bad), ",".join(PROVIDERS)), file=sys.stderr)
        raise SystemExit(2)
    return names


def _safe_call(fn, ctx, spec, step):
    try:
        return fn(ctx)
    except Exception as exc:  # probe bug — never leak internals with secrets
        detail = common.mask("probe-error %s: %s" % (type(exc).__name__, exc),
                             ctx.get("secret_values", ()))
        ki = common.resolve_key(spec.get("key_envs", ()), ctx["secrets"],
                                ctx["scopes"])
        return [common.key_row(spec, ki, step=step, cls=common.UNKNOWN,
                               detail=detail)]


def cmd_check(args):
    ctx = _ctx(args)
    names = _select(args)
    # Free probes are independent GETs — run them in parallel, keep registry
    # order in the output. Rows are collected per provider, then flattened.
    results = [None] * len(names)
    with concurrent.futures.ThreadPoolExecutor(
            max_workers=min(8, max(1, len(names)))) as pool:
        futures = {}
        for i, name in enumerate(names):
            mod = PROVIDERS[name]
            futures[pool.submit(_safe_call, mod.check, ctx, mod.SPEC,
                                "check")] = i
        for fut in concurrent.futures.as_completed(futures):
            results[futures[fut]] = fut.result()
    rows = []
    for i, name in enumerate(names):
        rows += results[i] or []
        if name == "jev" and getattr(args, "node_xcheck", False):
            rows += _node_xcheck(ctx, PROVIDERS[name].SPEC)
    return rows, ctx


def _node_xcheck(ctx, spec):
    """`check jev --node-xcheck`: prove the Node delegate wiring (dry-run, 0원)."""
    import subprocess
    ki = common.resolve_key(spec["key_envs"], ctx["secrets"], ctx["scopes"])
    script = common.ROOT / "scripts" / "jev_api_smoke.py"
    try:
        proc = subprocess.run([sys.executable, "-B", str(script)],
                              capture_output=True, text=True, timeout=30,
                              cwd=str(common.ROOT))
        cls = common.OK if proc.returncode == 0 else common.UNKNOWN
        detail = "jev_api_smoke dry-run rc=%d" % proc.returncode
    except (OSError, subprocess.TimeoutExpired) as exc:
        cls, detail = common.UNKNOWN, "xcheck spawn: %s" % type(exc).__name__
    return [common.key_row(spec, ki, step="node-xcheck", cls=cls, detail=detail)]


def cmd_call(args):
    ctx = _ctx(args)
    name = args.provider.lower()
    if name not in PROVIDERS:
        print("unknown provider: %s" % name, file=sys.stderr)
        raise SystemExit(2)
    mod = PROVIDERS[name]
    paid = bool(args.paid)
    if common.paid_provider(name) and not paid:
        print("call %s needs explicit --paid (spend guard); check is free"
              % name, file=sys.stderr)
        raise SystemExit(2)
    ctx["paid"] = paid
    if not hasattr(mod, "call"):
        ki = common.resolve_key(mod.SPEC.get("key_envs", ()), ctx["secrets"],
                                ctx["scopes"])
        return [common.key_row(mod.SPEC, ki, step="call", cls=common.UNKNOWN,
                               detail="no paid step implemented")], ctx
    return _safe_call(mod.call, ctx, mod.SPEC, "call"), ctx


def _replay_auth(mod):
    spec = mod.SPEC
    envs = spec.get("replay_envs") or spec.get("key_envs") or []
    style = spec.get("replay_auth")
    if style is None and envs:
        style = "bearer"  # project default: most providers are Bearer
    return style, envs


def cmd_replay(args):
    ctx = _ctx(args, paid=bool(args.paid))
    name = args.provider.lower()
    if name not in PROVIDERS:
        print("unknown provider: %s" % name, file=sys.stderr)
        raise SystemExit(2)
    mod = PROVIDERS[name]
    spec = mod.SPEC
    try:
        req_data = json.loads(Path(args.request_file).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print("cannot read --from file: %s" % exc, file=sys.stderr)
        raise SystemExit(2)
    url = req_data.get("url")
    if not url:
        print("--from JSON needs 'url'", file=sys.stderr)
        raise SystemExit(2)
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname or ""
    allowed = set(spec.get("hosts") or [])
    if name == "ollama":
        allowed |= {"127.0.0.1", "localhost", "::1"}
    if not allowed:  # e.g. upstash — host comes from env URLs
        for env in ("UPSTASH_VECTOR_URL", "UPSTASH_REDIS_REST_URL"):
            v = ctx["scopes"]["Process"].get(env) or ctx["scopes"]["User"].get(env)
            if v:
                allowed.add(urllib.parse.urlparse(v).hostname or "")
    if host not in allowed:
        print("replay host %r not in %s allowlist %s"
              % (host, name, sorted(allowed)), file=sys.stderr)
        raise SystemExit(2)
    method = (req_data.get("method") or "POST").upper()
    in_headers = {k: v for k, v in (req_data.get("headers") or {}).items()
                  if k.lower() not in common.CREDENTIAL_HEADERS}
    body = req_data.get("body", req_data.get("json"))
    if isinstance(body, (dict, list)):
        body = json.dumps(body).encode("utf-8")
        in_headers.setdefault("Content-Type", "application/json")
    elif isinstance(body, str):
        body = body.encode("utf-8")
    ki = common.resolve_key(_replay_auth(mod)[1], ctx["secrets"], ctx["scopes"])
    style = _replay_auth(mod)[0]
    out_headers = dict(in_headers)
    if style and ki["value"]:
        out_headers.update(common.AUTH_STYLES[style](ki["value"]))
    if not args.paid:  # dry-run: URL + header names + body size only
        plan = {
            "dryRun": True, "method": method,
            "url": common.mask(url, ctx["secret_values"]),
            "headerNames": sorted(out_headers),
            "bodyBytes": len(body or b""),
            "authInjected": bool(style and ki["value"]),
        }
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        row = common.key_row(spec, ki, step="replay-dry-run", cls=common.OK,
                             detail="dry-run only; pass --paid to send once")
        return [row], ctx
    if not ki["value"] and style:
        return [common.key_row(spec, ki, step="replay", cls=common.KEY_MISSING,
                               detail="no key to inject")], ctx
    result = common.http_request(method, url, out_headers, body,
                                 timeout=ctx["timeout"])
    cls, code, detail = common.classify(spec, result)
    detail = common.mask(detail, ctx["secret_values"])
    row = common.key_row(spec, ki, step="replay", cls=cls, detail=detail,
                         ms=result.get("ms"))
    row["http"] = result.get("status")
    row["code"] = code
    if cls == common.UNKNOWN and result.get("text"):
        row["response_snippet"] = common.mask(
            result["text"], ctx["secret_values"])[:common.SNIPPET_CHARS]
    common.spend_line(ctx, name, "(replayed)", "replay", row["http"])
    return [row], ctx


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="apikit",
        description="demo-1 lite API test kit (stdlib, no Java). "
                    "check is free; call/replay sends need --paid.")
    sub = ap.add_subparsers(dest="cmd")

    def _shared(p):
        p.add_argument("--timeout", type=float, default=common.DEFAULT_TIMEOUT_S)
        p.add_argument("--json", action="store_true")
        p.add_argument("--out", default=None)

    pc = sub.add_parser("check", help="free checks only (0원)")
    pc.add_argument("providers", nargs="*")
    pc.add_argument("--only", default=None)
    pc.add_argument("--node-xcheck", action="store_true",
                    help="jev only: run jev_api_smoke.py dry-run to prove Node wiring")
    _shared(pc)
    pc.set_defaults(timeout=8.0)  # free probes stay short; --timeout overrides

    pl = sub.add_parser("call", help="one minimal paid call per provider")
    pl.add_argument("provider")
    pl.add_argument("--paid", action="store_true",
                    help="required for cloud providers (not needed for ollama)")
    _shared(pl)

    pr = sub.add_parser("replay", help="replay an app-shaped request JSON")
    pr.add_argument("provider")
    pr.add_argument("--from", dest="request_file", required=True)
    pr.add_argument("--paid", action="store_true",
                    help="actually send once (default: dry-run)")
    _shared(pr)

    args = ap.parse_args(argv)
    if args.cmd is None:
        args.cmd = "check"
        args.providers, args.only, args.node_xcheck = [], None, False
        args.timeout, args.json, args.out = 8.0, False, None

    try:
        if args.cmd == "check":
            rows, ctx = cmd_check(args)
        elif args.cmd == "call":
            rows, ctx = cmd_call(args)
        else:
            rows, ctx = cmd_replay(args)
    except SystemExit:
        raise
    except Exception as exc:
        print("apikit error: %s" % common.mask(str(exc)), file=sys.stderr)
        return 2

    all_ok = all(r["cls"] == common.OK for r in rows)
    payload = {
        "stamp": ctx["stamp"], "cmd": args.cmd,
        "argv": [a for a in (argv if argv is not None else sys.argv[1:])],
        "rows": rows, "allOk": all_ok,
    }
    saved = common.save_result(payload, getattr(args, "out", None))
    if getattr(args, "json", False):
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        common.print_table(rows)
        print("saved=%s allOk=%s" % (saved, all_ok))
    return 0 if all_ok else 3


if __name__ == "__main__":
    sys.exit(main())
