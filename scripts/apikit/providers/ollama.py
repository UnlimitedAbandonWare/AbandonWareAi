"""Ollama — local, free. GET /api/tags on each configured host; required-model
presence comes from configs/api-routing.yaml (SSOT), not hardcoded lists."""
import json
import os
import re

try:
    from .. import common
except ImportError:
    import common

# https://docs.ollama.com/api/tags — verified 2026-09-29
SPEC = {
    "name": "ollama",
    "key_envs": [],  # no key — local
    "hosts": ["127.0.0.1", "localhost", "::1"],
    "fix_url": "ollama serve / ollama pull <model>",
    "doc_url": "https://docs.ollama.com/api/tags",
    "doc_checked": "2026-09-29",
    "code_paths": [("error",)],
    "code_map": {},
}

_KI = {"env": "-", "value": None, "src": "local", "srcs": [],
       "len": 0, "sha8": None, "mismatch": False}


def _hosts(ctx):
    hosts = common.yaml_list(common.routing_yaml_text(), "default_hosts")
    env = os.environ.get("OLLAMA_HOST") or os.environ.get("LLM_BASE_URL")
    if env:
        h = re.sub(r"^https?://", "", env).split("/")[0]
        if h and h not in hosts:
            hosts.insert(0, h)
    return hosts or ["127.0.0.1:11434"]


def _required_models():
    """installed_models.all from every ollama block in the routing SSOT."""
    text = common.routing_yaml_text()
    out = []
    for m in re.finditer(r"installed_models:", text):
        block = "\n".join(text[m.end():].splitlines()[:15])
        out += common.yaml_list(block, "all")
    return out


def check(ctx):
    rows = []
    required = _required_models()
    found = set()
    for host in _hosts(ctx):
        url = "http://%s/api/tags" % host
        holder = {"names": []}

        def _ok(parsed, _r, holder=holder, host=host):
            models = (parsed or {}).get("models") or []
            holder["names"] = [m.get("name") for m in models if isinstance(m, dict)]
            found.update(n for n in holder["names"] if n)
            return common.OK, "host=%s models=%d" % (host, len(holder["names"]))

        rows.append(common.run_step(
            SPEC, ctx, ki=_KI, step="tags", url=url, ok=_ok))
    if required:
        missing = [m for m in required if m not in found]
        rows.append(common.key_row(
            SPEC, _KI, step="required-models",
            cls=common.OK if not missing else common.MODEL_NOT_FOUND,
            detail="missing=%s" % (",".join(missing) if missing else "none")))
    return rows


def call(ctx):
    """Local generate — free on the 3090/3060 lane. --paid not required."""
    fast = common.yaml_list(common.routing_yaml_text(), "fast")
    for host in _hosts(ctx):
        r = common.http_request("GET", "http://%s/api/tags" % host,
                                timeout=ctx["timeout"])
        if r["status"] != 200 or not r["text"]:
            continue
        try:
            names = {m.get("name") for m in json.loads(r["text"]).get("models", [])
                     if isinstance(m, dict)}
        except ValueError:
            names = set()
        model = next((m for m in fast if m in names), None) or \
            (sorted(names)[0] if names else None)
        if not model:
            continue
        body = json.dumps({"model": model, "prompt": "hi", "stream": False,
                           "options": {"num_predict": 8}}).encode("utf-8")
        gen_ctx = dict(ctx, timeout=max(ctx["timeout"], 120.0))  # cold model load
        row = common.run_step(
            SPEC, gen_ctx, ki=_KI, step="generate", method="POST",
            url="http://%s/api/generate" % host,
            headers={"Content-Type": "application/json"}, body=body,
            ok=lambda p, _r: (common.OK, "host=%s model=%s eval_count=%s" % (
                host, model, (p or {}).get("eval_count"))))
        common.spend_line(ctx, "ollama", model, "generate", row["http"])
        return [row]
    return [common.key_row(SPEC, _KI, step="generate", cls=common.NETWORK,
                           detail="no reachable ollama host with a model")]
