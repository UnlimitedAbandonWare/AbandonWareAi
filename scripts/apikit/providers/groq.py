"""Groq — free check GET /openai/v1/models. No paid step implemented."""
try:
    from .. import common
except ImportError:
    import common

# https://console.groq.com/docs/api-reference#models — verified 2026-09-29
SPEC = {
    "name": "groq",
    "key_envs": ["GROQ_API_KEY"],
    "hosts": ["api.groq.com"],
    "fix_url": "https://console.groq.com/keys",
    "doc_url": "https://console.groq.com/docs/api-reference#models",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "type")],
    "code_map": {
        "invalid_api_key": common.KEY_INVALID,
        "rate_limit_exceeded": common.RATE_LIMIT,
        "model_not_found": common.MODEL_NOT_FOUND,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="bearer",
        url="https://api.groq.com/openai/v1/models",
        ok=lambda p, _r: (common.OK, "models=%d" % len((p or {}).get("data", [])))))
    return rows


def call(ctx):
    return [common.key_row(SPEC, common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"]),
                           step="call", cls=common.UNKNOWN,
                           detail="no paid step implemented for groq")]
