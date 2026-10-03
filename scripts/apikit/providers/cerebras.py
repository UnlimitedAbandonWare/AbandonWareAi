"""Cerebras — free check GET /v1/models (OpenAI-compatible, Bearer auth).
Product hot path: LlmRouterAspect resolves llm.cerebras.api-key /
CEREBRAS_API_KEY for the cerebras lane."""
try:
    from .. import common
except ImportError:
    import common

# https://inference-docs.cerebras.ai/api-reference/models — verified 2026-09-30
SPEC = {
    "name": "cerebras",
    "key_envs": ["CEREBRAS_API_KEY"],
    "hosts": ["api.cerebras.ai"],
    "fix_url": "https://cloud.cerebras.ai/",
    "doc_url": "https://inference-docs.cerebras.ai/api-reference/models",
    "doc_checked": "2026-09-30",
    "code_paths": [("error", "code"), ("error", "type"), ("code",), ("message",)],
    "code_map": {
        "invalid_api_key": common.KEY_INVALID,
        "wrong_api_key": common.KEY_INVALID,
        "insufficient_quota": common.QUOTA,
        "rate_limit_exceeded": common.RATE_LIMIT,
    },
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(
            SPEC, ki, cls=common.KEY_MISMATCH,
            detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="bearer",
        url="https://api.cerebras.ai/v1/models",
        ok=lambda p, _r: (common.OK, "models=%d" % len(
            (p or {}).get("data", [])))))
    return rows
