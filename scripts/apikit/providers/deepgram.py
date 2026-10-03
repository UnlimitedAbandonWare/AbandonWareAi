"""Deepgram — free check GET /v1/projects (Token auth header). Tries the
primary key, then the secondary if the first is absent."""
try:
    from .. import common
except ImportError:
    import common

# https://developers.deepgram.com/reference/get-projects — verified 2026-09-29
SPEC = {
    "name": "deepgram",
    "key_envs": ["DEEPGRAM_API_KEY", "DEEPGRAM_API_KEY_SECONDARY"],
    "hosts": ["api.deepgram.com"],
    "fix_url": "https://console.deepgram.com/",
    "doc_url": "https://developers.deepgram.com/reference/get-projects",
    "doc_checked": "2026-09-29",
    "code_paths": [("err_code",), ("err_msg",)],
    "code_map": {
        "INVALID_AUTH": common.KEY_INVALID,
        "INSUFFICIENT_PERMISSIONS": common.FORBIDDEN,
        "ASR_PAYMENT_REQUIRED": common.QUOTA,
    },
}


def check(ctx):
    rows = []
    for env in SPEC["key_envs"]:
        ki = common.resolve_key([env], ctx["secrets"], ctx["scopes"])
        rows.append(common.run_step(
            SPEC, ctx, ki=ki, step="projects:%s" % env.replace("DEEPGRAM_API_KEY", "key").lower(),
            auth="token", url="https://api.deepgram.com/v1/projects",
            ok=lambda p, _r: (common.OK, "projects=%d" % len((p or {}).get("projects", [])))))
    return rows
