"""Anthropic — free check GET /v1/models (x-api-key + anthropic-version).
Project keeps this key in .secrets/providers.json only; resolver picks it up."""
try:
    from .. import common
except ImportError:
    import common

# https://platform.claude.com/docs/en/api/models/list — verified 2026-09-29
SPEC = {
    "name": "anthropic",
    "key_envs": ["ANTHROPIC_API_KEY"],
    "hosts": ["api.anthropic.com"],
    "fix_url": "https://console.anthropic.com/settings/keys",
    "doc_url": "https://platform.claude.com/docs/en/api/models/list",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "type"), ("error", "message")],
    "code_map": {
        "authentication_error": common.KEY_INVALID,
        "permission_error": common.FORBIDDEN,
        "rate_limit_error": common.RATE_LIMIT,
        "not_found_error": common.MODEL_NOT_FOUND,
        "invalid_request_error": common.BAD_SHAPE,
        "api_error": common.SERVER_5XX,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="x-api-key",
        url="https://api.anthropic.com/v1/models",
        headers={"anthropic-version": "2023-06-01"},
        ok=lambda p, _r: (common.OK, "models=%d" % len((p or {}).get("data", [])))))
    return rows
