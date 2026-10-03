"""Gemini — free checks: GET /v1beta/models + POST {model}:countTokens.
Paid = POST {model}:generateContent, minimal output. Model picked from the live
models list (flash-lite > flash > first generateContent-capable)."""
import json

try:
    from .. import common
except ImportError:
    import common

# https://ai.google.dev/api/tokens + https://ai.google.dev/gemini-api/docs/generate-content/api-errors
# — verified 2026-09-29
SPEC = {
    "name": "gemini",
    "key_envs": ["GEMINI_API_KEY", "GOOGLE_API_KEY"],
    "hosts": ["generativelanguage.googleapis.com"],
    "fix_url": "https://aistudio.google.com/app/apikey",
    "doc_url": "https://ai.google.dev/gemini-api/docs/generate-content/api-errors",
    "doc_checked": "2026-09-29",
    # error.details[].reason (e.g. API_KEY_INVALID) wins over error.status.
    "code_paths": [("error", "details", "*", "reason"), ("error", "status")],
    "code_map": {
        "API_KEY_INVALID": common.KEY_INVALID,
        "API_KEY_EXPIRED": common.KEY_INVALID,
        "INVALID_API_KEY": common.KEY_INVALID,
        "PERMISSION_DENIED": common.FORBIDDEN,
        "RESOURCE_EXHAUSTED": common.QUOTA,
        "UNAUTHENTICATED": common.KEY_INVALID,
        "INVALID_ARGUMENT": common.BAD_SHAPE,
        "NOT_FOUND": common.MODEL_NOT_FOUND,
    },
}

_BASE = "https://generativelanguage.googleapis.com/v1beta"


def _pick_model(models):
    return (_pick_candidates(models) or [None])[0]


def _pick_candidates(models):
    """models = [{'name': 'models/x', 'supportedGenerationMethods': [...]}] ->
    preferred order: flash-lite > flash > rest (retired entries can 404, so
    callers iterate a bounded few)."""
    usable = []
    for m in models or []:
        name = (m.get("name") or "").replace("models/", "")
        methods = m.get("supportedGenerationMethods") or []
        if "generateContent" in methods and name:
            usable.append(name)
    ordered, rest = [], []
    for tok in ("flash-lite", "flash", "gemini"):
        for name in sorted(usable):
            if tok in name and name not in ordered + rest:
                ordered.append(name)
    for name in sorted(usable):
        if name not in ordered:
            rest.append(name)
    return ordered + rest


def check(ctx):
    rows = []
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))

    def _ok(parsed, _r):
        models = (parsed or {}).get("models") or []
        ctx["gemini_models"] = models
        return common.OK, "models=%d" % len(models)

    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="x-goog",
        url=_BASE + "/models", ok=_ok))
    if rows[-1]["cls"] in (common.KEY_INVALID, common.FORBIDDEN,
                           common.KEY_MISSING):
        rows.append(common.key_row(SPEC, ki, step="countTokens",
                                   cls=common.UNKNOWN,
                                   detail="skipped after auth failure"))
        return rows

    candidates = _pick_candidates(ctx.get("gemini_models"))
    if not candidates:
        rows.append(common.key_row(SPEC, ki, step="countTokens",
                                   cls=common.MODEL_NOT_FOUND,
                                   detail="no generateContent-capable model in list"))
        return rows
    body = json.dumps({"contents": [{"parts": [{"text": "hi"}]}]}).encode("utf-8")
    last = None
    for model in candidates[:3]:  # bounded free retries — retired models 404
        row = common.run_step(
            SPEC, ctx, ki=ki, step="countTokens", method="POST", auth="x-goog",
            url="%s/models/%s:countTokens" % (_BASE, model),
            headers={"Content-Type": "application/json"}, body=body,
            ok=lambda p, _r, m=model: (common.OK, "model=%s totalTokens=%s" % (
                m, (p or {}).get("totalTokens"))))
        last = row
        if row["cls"] == common.OK:
            ctx["gemini_model"] = model
            row["detail"] += " (tried %d/%d)" % (
                candidates.index(model) + 1, len(candidates[:3]))
            break
        if row["cls"] not in (common.MODEL_NOT_FOUND,):
            break  # auth/network/quota — stop, don't burn retries
    rows.append(last)
    return rows


def call(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    if not ki["value"]:
        return [common.key_row(SPEC, ki, step="generateContent-min",
                               cls=common.KEY_MISSING, detail="GEMINI_API_KEY absent")]
    model = ctx.get("gemini_model") or _pick_model(ctx.get("gemini_models"))
    if not model:
        return [common.key_row(SPEC, ki, step="generateContent-min",
                               cls=common.MODEL_NOT_FOUND,
                               detail="run `check` first or no capable model")]
    body = json.dumps({
        "contents": [{"parts": [{"text": "hi"}]}],
        "generationConfig": {"maxOutputTokens": 16},
    }).encode("utf-8")
    row = common.run_step(
        SPEC, ctx, ki=ki, step="generateContent-min", method="POST",
        auth="x-goog",
        url="%s/models/%s:generateContent" % (_BASE, model),
        headers={"Content-Type": "application/json"}, body=body,
        ok=lambda p, _r: (common.OK, "model=%s usage=%s" % (
            model, (p or {}).get("usageMetadata"))))
    common.spend_line(ctx, "gemini", model, "generateContent-min",
                      row["http"], None, row.get("cost"))
    return [row]
