"""Brave Search — free check: minimal GET /res/v1/web/search (count=1) on the
free-tier key first, then the base key. ~1 quota unit each; documented as the
smallest free check (no dedicated key-metadata endpoint exists)."""
try:
    from .. import common
except ImportError:
    import common

# https://api-dashboard.search.brave.com/app/documentation/web-search/get-started
# — verified 2026-09-29
SPEC = {
    "name": "brave",
    "key_envs": ["BRAVE_API_KEY_FREE", "BRAVE_API_KEY"],
    "hosts": ["api.search.brave.com"],
    "fix_url": "https://api-dashboard.search.brave.com/app/keys",
    "doc_url": "https://api-dashboard.search.brave.com/app/documentation/web-search/get-started",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "type"), ("error", "detail"), ("message",)],
    "code_map": {
        "INVALID_KEY": common.KEY_INVALID,
        "SUBSCRIPTION_TOKEN_MISSING": common.KEY_MISSING,
        "SUBSCRIPTION_TOKEN_INVALID": common.KEY_INVALID,
        "SUBSCRIPTION_TOKEN_NOT_FOUND": common.KEY_INVALID,
        "RATE_LIMIT_EXCEEDED": common.RATE_LIMIT,
        "PLAN_QUOTA_EXCEEDED": common.QUOTA,
    },
}

_URL = "https://api.search.brave.com/res/v1/web/search?q=test&count=1&result_filter=web"


def check(ctx):
    rows = []
    for env in SPEC["key_envs"]:
        ki = common.resolve_key([env], ctx["secrets"], ctx["scopes"])
        rows.append(common.run_step(
            SPEC, ctx, ki=ki, step="search:%s" % env.replace("BRAVE_API_KEY", "base").lower(),
            auth="x-subscription", url=_URL,
            ok=lambda p, _r: (common.OK, "web results=%d" % len(
                ((p or {}).get("web") or {}).get("results", [])))))
    return rows
