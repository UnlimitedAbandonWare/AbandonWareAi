"""Kakao Local — free check GET /v2/local/search/keyword.json (size=1). REST key
usually lives in .secrets/providers.json (KAKAO_REST_API_KEY), not env.
KakaoPlacesClient reads the KAKAO_REST_KEY env name — kept as an alias."""
try:
    from .. import common
except ImportError:
    import common

# https://developers.kakao.com/docs/latest/ko/local/dev-guide#search-by-keyword
# — verified 2026-09-29
SPEC = {
    "name": "kakao",
    "key_envs": ["KAKAO_REST_API_KEY", "KAKAO_REST_KEY", "KAKAO_API_KEY"],
    "hosts": ["dapi.kakao.com"],
    "fix_url": "https://developers.kakao.com/console/app",
    "doc_url": "https://developers.kakao.com/docs/latest/ko/local/dev-guide#search-by-keyword",
    "doc_checked": "2026-09-29",
    "code_paths": [("errorType",), ("error", "type"), ("message",)],
    "code_map": {
        "AccessDeniedError": common.FORBIDDEN,
        "InvalidApiKeyError": common.KEY_INVALID,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="keyword", auth="kakao",
        url="https://dapi.kakao.com/v2/local/search/keyword.json?query=test&size=1",
        ok=lambda p, _r: (common.OK, "documents=%d" % len((p or {}).get("documents", [])))))
    return rows
