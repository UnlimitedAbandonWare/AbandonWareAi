"""OpenAI — free check GET /v1/models; paid = POST /v1/responses, smallest
non-blocked model, minimal output. Model choice is never hardcoded: env
AWX_APIKIT_OPENAI_MODEL > live /models pick."""
import json
import os

try:
    from .. import common
except ImportError:
    import common

# https://developers.openai.com/api/docs/guides/error-codes — verified 2026-09-29
SPEC = {
    "name": "openai",
    "key_envs": ["OPENAI_API_KEY"],
    "hosts": ["api.openai.com"],
    "fix_url": "https://platform.openai.com/api-keys",
    "doc_url": "https://developers.openai.com/api/docs/guides/error-codes",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "code"), ("error", "type")],
    "code_map": {
        "invalid_api_key": common.KEY_INVALID,
        "incorrect_api_key_provided": common.KEY_INVALID,
        "insufficient_quota": common.QUOTA,
        "model_not_found": common.MODEL_NOT_FOUND,
        "rate_limit_exceeded": common.RATE_LIMIT,
        "invalid_request_error": common.BAD_SHAPE,
    },
}

_SKIP_TOKENS = ("embed", "tts", "whisper", "image", "dall", "moderation",
                "realtime", "audio", "transcribe", "search")
_PREFER_TOKENS = ("nano", "mini", "lite", "small")

# Kill switch (SSOT configs/agent-api-spend-guard.yaml paid_default_on: true):
# paid agent calls are ON unless AWX_AGENT_ALLOW_PAID_MODELS is an explicit
# false value. Unset or any other value keeps the default ON.
_PAID_KILL_VALUES = ("0", "false", "no", "off")


def _paid_kill_switched():
    return os.environ.get("AWX_AGENT_ALLOW_PAID_MODELS", "").strip().lower() \
        in _PAID_KILL_VALUES


def _pick_model(ids):
    blocked = set(common.blocked_models())
    allow_blocked = os.environ.get("AWX_AGENT_ALLOW_PAID_MODELS") in ("1", "true", "True")
    forced = os.environ.get("AWX_APIKIT_OPENAI_MODEL")
    if forced:
        return forced, "env AWX_APIKIT_OPENAI_MODEL"
    usable = [i for i in ids
              if i and not any(t in i.lower() for t in _SKIP_TOKENS)]
    for tok in _PREFER_TOKENS:
        for i in sorted(usable):
            if tok in i.lower() and (allow_blocked or i not in blocked):
                return i, "auto:%s" % tok
    for i in sorted(usable):
        if allow_blocked or i not in blocked:
            return i, "auto:first"
    return None, "no eligible model in /v1/models"


def check(ctx):
    rows = []
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))

    def _ok(parsed, _r):
        ids = [m.get("id") for m in (parsed or {}).get("data", []) if isinstance(m, dict)]
        ctx.setdefault("openai_models", ids)
        return common.OK, "models=%d" % len(ids)

    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="bearer",
        url="https://api.openai.com/v1/models", ok=_ok))
    return rows


def call(ctx):
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    if _paid_kill_switched():
        return [common.key_row(SPEC, ki, step="responses-1tok",
                               cls=common.UNKNOWN,
                               detail="paid step refused: AWX_AGENT_ALLOW_PAID_MODELS "
                                      "kill-switch (=0/false/no/off); unset/=1 allows")]
    if not ki["value"]:
        return [common.key_row(SPEC, ki, step="responses-1tok",
                               cls=common.KEY_MISSING, detail="OPENAI_API_KEY absent")]
    ids = ctx.get("openai_models")
    if ids is None:
        r = common.http_request("GET", "https://api.openai.com/v1/models",
                                common.AUTH_STYLES["bearer"](ki["value"]),
                                timeout=ctx["timeout"])
        try:
            ids = [m.get("id") for m in json.loads(r["text"] or "{}").get("data", [])
                   if isinstance(m, dict)]
        except ValueError:
            ids = []
    model, how = _pick_model(ids)
    if not model:
        return [common.key_row(SPEC, ki, step="responses-1tok", cls=common.MODEL_NOT_FOUND,
                               detail=how)]
    body = json.dumps({
        "model": model, "input": "ping",
        "max_output_tokens": 16, "store": False,
    }).encode("utf-8")
    row = common.run_step(
        SPEC, ctx, ki=ki, step="responses-1tok", method="POST", auth="bearer",
        url="https://api.openai.com/v1/responses",
        headers={"Content-Type": "application/json"}, body=body,
        ok=lambda p, _r: (common.OK, "model=%s (%s) usage=%s" % (
            model, how, (p or {}).get("usage"))))
    row["detail"] = common.mask("model=%s via %s; %s" % (model, how, row["detail"]),
                                ctx.get("secret_values", ()))
    common.spend_line(ctx, "openai", model, "responses-1tok", row["http"], None, row.get("cost"))
    return [row]
