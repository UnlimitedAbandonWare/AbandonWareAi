"""OpenRouter — free check GET /api/v1/auth/key (key metadata, no charge)."""
try:
    from .. import common
except ImportError:
    import common

# https://openrouter.ai/docs/api-reference/get-key — verified 2026-09-29
SPEC = {
    "name": "openrouter",
    "key_envs": ["OPENROUTER_API_KEY"],
    "hosts": ["openrouter.ai"],
    "fix_url": "https://openrouter.ai/settings/keys",
    "doc_url": "https://openrouter.ai/docs/api-reference/get-key",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "message")],
    "code_map": {},
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))

    def _ok(p, _r):
        d = (p or {}).get("data") or {}
        return common.OK, "limit=%s usage=%s" % (d.get("limit"), d.get("usage"))

    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="auth-key", auth="bearer",
        url="https://openrouter.ai/api/v1/auth/key", ok=_ok))
    return rows
