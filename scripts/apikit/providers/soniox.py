"""Soniox — free check GET /v1/models (Bearer)."""
try:
    from .. import common
except ImportError:
    import common

# https://soniox.com/docs/stt/api-reference — verified 2026-09-29
SPEC = {
    "name": "soniox",
    "key_envs": ["SONIOX_API_KEY"],
    "hosts": ["api.soniox.com"],
    "fix_url": "https://console.soniox.com/",
    "doc_url": "https://soniox.com/docs/stt/api-reference",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error_message",), ("error", "message")],
    "code_map": {
        "invalid_api_key": common.KEY_INVALID,
        "insufficient_balance": common.QUOTA,
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
        url="https://api.soniox.com/v1/models",
        ok=lambda p, _r: (common.OK, "models=%d" % len((p or {}).get("models",
                                                                   (p or {}).get("data", [])))))
    )
    return rows
