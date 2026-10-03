"""Mistral — free check GET /v1/models. No paid step implemented."""
try:
    from .. import common
except ImportError:
    import common

# https://docs.mistral.ai/api/ — verified 2026-09-29
SPEC = {
    "name": "mistral",
    "key_envs": ["MISTRAL_API_KEY"],
    "hosts": ["api.mistral.ai"],
    "fix_url": "https://console.mistral.ai/api-keys",
    "doc_url": "https://docs.mistral.ai/api/",
    "doc_checked": "2026-09-29",
    "code_paths": [("message",), ("error", "code")],
    "code_map": {},
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="bearer",
        url="https://api.mistral.ai/v1/models",
        ok=lambda p, _r: (common.OK, "models=%d" % len((p or {}).get("data", [])))))
    return rows
