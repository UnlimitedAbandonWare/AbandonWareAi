"""Upstash — free checks: Vector /info (UPSTASH_VECTOR_URL + key|token) and
Redis REST /ping (UPSTASH_REDIS_REST_URL + token). URL envs are also resolved
so a missing URL reports KEY_MISSING instead of guessing an endpoint."""
try:
    from .. import common
except ImportError:
    import common

# https://upstash.com/docs/vector/api/endpoints#info +
# https://upstash.com/docs/redis/features/restapi — verified 2026-09-29
SPEC = {
    "name": "upstash",
    "key_envs": ["UPSTASH_VECTOR_API_KEY", "UPSTASH_VECTOR_TOKEN",
                 "UPSTASH_REDIS_REST_TOKEN"],
    "hosts": [],  # host comes from env URL — replay host allowlist = any env URL host
    "fix_url": "https://console.upstash.com/",
    "doc_url": "https://upstash.com/docs/vector/api/endpoints",
    "doc_checked": "2026-09-29",
    "code_paths": [("error",), ("error", "message"), ("message",)],
    "code_map": {},
}


def _pair(ctx, url_env, key_envs):
    url_ki = common.resolve_key([url_env], ctx["secrets"], ctx["scopes"])
    key_ki = common.resolve_key(key_envs, ctx["secrets"], ctx["scopes"])
    return url_ki, key_ki


def check(ctx):
    rows = []
    url_ki, key_ki = _pair(ctx, "UPSTASH_VECTOR_URL",
                           ["UPSTASH_VECTOR_API_KEY", "UPSTASH_VECTOR_TOKEN"])
    merged = dict(key_ki)
    merged["env"] = "UPSTASH_VECTOR_URL+KEY"
    merged["srcs"] = sorted(set(url_ki["srcs"]) | set(key_ki["srcs"]))
    merged["mismatch"] = url_ki["mismatch"] or key_ki["mismatch"]
    if not url_ki["value"] or not key_ki["value"]:
        rows.append(common.key_row(
            SPEC, merged, step="vector-info", cls=common.KEY_MISSING,
            detail="need UPSTASH_VECTOR_URL + UPSTASH_VECTOR_API_KEY|TOKEN"))
    else:
        result = common.http_request(
            "GET", url_ki["value"].rstrip("/") + "/info",
            {"Authorization": "Bearer %s" % key_ki["value"]}, timeout=ctx["timeout"])
        cls, code, detail = common.classify(SPEC, result)
        row = common.key_row(SPEC, merged, step="vector-info", cls=cls,
                             detail=detail, ms=result.get("ms"))
        row["http"] = result.get("status")
        row["code"] = code
        rows.append(row)

    rurl_ki, rkey_ki = _pair(ctx, "UPSTASH_REDIS_REST_URL",
                             ["UPSTASH_REDIS_REST_TOKEN"])
    rmerged = dict(rkey_ki)
    rmerged["env"] = "UPSTASH_REDIS_REST_URL+TOKEN"
    rmerged["srcs"] = sorted(set(rurl_ki["srcs"]) | set(rkey_ki["srcs"]))
    rmerged["mismatch"] = rurl_ki["mismatch"] or rkey_ki["mismatch"]
    if not rurl_ki["value"] or not rkey_ki["value"]:
        rows.append(common.key_row(
            SPEC, rmerged, step="redis-ping", cls=common.KEY_MISSING,
            detail="need UPSTASH_REDIS_REST_URL + UPSTASH_REDIS_REST_TOKEN"))
    else:
        result = common.http_request(
            "GET", rurl_ki["value"].rstrip("/") + "/ping",
            {"Authorization": "Bearer %s" % rkey_ki["value"]}, timeout=ctx["timeout"])
        cls, code, detail = common.classify(SPEC, result)
        if cls == common.OK and result.get("text"):
            detail = "result=%s" % result["text"][:40]
        row = common.key_row(SPEC, rmerged, step="redis-ping", cls=cls,
                             detail=detail, ms=result.get("ms"))
        row["http"] = result.get("status")
        row["code"] = code
        rows.append(row)
    return rows
