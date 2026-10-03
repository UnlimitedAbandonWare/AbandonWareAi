"""OpenCode Zen — free check GET /zen/v1/models (Bearer)."""
try:
    from .. import common
except ImportError:
    import common

# https://opencode.ai/docs/zen — verified 2026-09-29
SPEC = {
    "name": "opencode",
    "key_envs": ["OPENCODE_API_KEY", "OPENCODE_GO_API_KEY"],
    "hosts": ["opencode.ai"],
    "fix_url": "https://opencode.ai/zen",
    "doc_url": "https://opencode.ai/docs/zen",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "type"), ("error", "message")],
    "code_map": {
        "authentication_error": common.KEY_INVALID,
        "invalid_api_key": common.KEY_INVALID,
        "billing_error": common.QUOTA,
        "rate_limit_error": common.RATE_LIMIT,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="zen-models", auth="bearer",
        url="https://opencode.ai/zen/v1/models",
        ok=lambda p, _r: (common.OK, "models=%d" % len((p or {}).get("data", [])))))
    return rows
