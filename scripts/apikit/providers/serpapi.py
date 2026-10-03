"""SerpAPI — free check GET /account.json with the key as a query param
(metadata, no search quota). Masking scrubs it from all output."""
try:
    from .. import common
except ImportError:
    import common

# https://serpapi.com/account-api — verified 2026-09-29
SPEC = {
    "name": "serpapi",
    "key_envs": ["SERPAPI_API_KEY"],
    "hosts": ["serpapi.com"],
    "fix_url": "https://serpapi.com/manage-api-key",
    "doc_url": "https://serpapi.com/account-api",
    "doc_checked": "2026-09-29",
    "code_paths": [("error",), ("error", "message")],
    "code_map": {},
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))

    def _ok(p, _r):
        return common.OK, "plan=%s searches_left=%s" % (
            (p or {}).get("plan_name"), (p or {}).get("plan_searches_left"))

    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="account", auth="query:api_key",
        url="https://serpapi.com/account.json", ok=_ok))
    return rows
