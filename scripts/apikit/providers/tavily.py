"""Tavily — free check: smallest POST /search (max_results=1, basic). Wraps
nothing; the existing scripts/test_tavily_serpapi_keys.ps1 stays the
interactive deep smoke."""
import json

try:
    from .. import common
except ImportError:
    import common

# https://docs.tavily.com/documentation/api-reference/endpoint/search — verified 2026-09-29
SPEC = {
    "name": "tavily",
    "key_envs": ["TAVILY_API_KEY"],
    "hosts": ["api.tavily.com"],
    "fix_url": "https://app.tavily.com/home",
    "doc_url": "https://docs.tavily.com/documentation/api-reference/endpoint/search",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("detail",), ("error",)],
    "code_map": {
        "invalid_api_key": common.KEY_INVALID,
        "usage_limit_exceeded": common.QUOTA,
        "rate_limit_exceeded": common.RATE_LIMIT,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    body = json.dumps({"query": "test", "max_results": 1,
                       "search_depth": "basic"}).encode("utf-8")
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="search-min", method="POST", auth="bearer",
        url="https://api.tavily.com/search",
        headers={"Content-Type": "application/json"}, body=body,
        ok=lambda p, _r: (common.OK, "results=%d" % len((p or {}).get("results", [])))))
    return rows
