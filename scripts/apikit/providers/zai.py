"""Z.ai (GLM) — free check GET /api/paas/v4/models."""
try:
    from .. import common
except ImportError:
    import common

# https://docs.z.ai/devpack/api — verified 2026-09-29
SPEC = {
    "name": "zai",
    "key_envs": ["ZAI_API_KEY"],
    "hosts": ["api.z.ai"],
    "fix_url": "https://z.ai/manage-apikey/apikey-list",
    "doc_url": "https://docs.z.ai/devpack/api",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "message")],
    "code_map": {
        "invalid_api_key": common.KEY_INVALID,
        "insufficient_quota": common.QUOTA,
        "rate_limit_exceeded": common.RATE_LIMIT,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    def _ok(p, _r):
        items = (p or {}).get("data", (p or {}).get("models", []))
        return common.OK, "models=%d" % len(items or [])

    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="bearer",
        url="https://api.z.ai/api/paas/v4/models", ok=_ok))
    return rows
