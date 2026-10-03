"""Pinecone — free check GET /indexes (Api-Key header + API version)."""
try:
    from .. import common
except ImportError:
    import common

# https://docs.pinecone.io/reference/api/2025-10/control-plane/list_indexes
# — verified 2026-09-29
SPEC = {
    "name": "pinecone",
    "key_envs": ["PINECONE_API_KEY"],
    "hosts": ["api.pinecone.io"],
    "fix_url": "https://app.pinecone.io/",
    "doc_url": "https://docs.pinecone.io/reference/api/2025-10/control-plane/list_indexes",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "message"), ("message",)],
    "code_map": {},
}


def check(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="indexes", auth="api-key",
        url="https://api.pinecone.io/indexes",
        headers={"X-Pinecone-Api-Version": "2025-10"},
        ok=lambda p, _r: (common.OK, "indexes=%d" % len((p or {}).get("indexes", [])))))
    return rows
