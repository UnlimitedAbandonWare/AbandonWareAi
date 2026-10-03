#!/usr/bin/env python3
"""model_default_probe — report the effective /chat default model chain (read-only).

Answers: "what is the effective default chat model and where does it come from?"
Chain: personal-override (/api/settings/preferences, owner-cookie scope)
     > DB currentModel (live only) > app.ai.ui-default-model > app.ai.default-model
     > chat.defaults.model > llm.chat-model > hardcoded gemma4:26b.

Stdlib only. Never reads .env/.secrets/credential files. No LLM calls.

Usage:
  python -B scripts/model_default_probe.py [--json] [--log <out.log>]
                                          [--base http://127.0.0.1:18180]
                                          [--no-server]

Verdicts:
  AUTH_FIRST_OK              effective default is a chatgpt-oauth: route
  AUTH_DEFAULT_BUT_ROUTE_BROKEN  auth default + recent chatgpt_oauth_* failures in --log
  LOCAL_DEFAULT_VIOLATION    effective default resolves to a local Ollama model
  API_DEFAULT_INTERMEDIATE   effective default is an API (non-oauth, non-local) id
  STATIC_LOCAL_DEFAULT       server down; static chain still lands on local
  STATIC_AUTH_PROJECTED      server down; launcher override will make it auth next start
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL_HINT = re.compile(r"^(?!chatgpt-oauth:|llmrouter\.|gpt-|openai|o\d|o-|claude|gemini|openai/|meta-llama/|llama)(.+)$")
OAUTH_REASON = re.compile(r"reasonCode=(chatgpt_oauth_[a-z0-9_]+)")
ENV_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_.-]+)(?::([^}]*))?\}")

SCAN_FILES = [
    "main/resources/application.yml",
    "main/resources/application-llm.yaml",
    "main/resources/application-local.yml",
    "main/resources/application-local-llm.yml",
    "main/resources/application-dev.yml",
    "main/resources/application-proj-override.yml",
    "main/resources/application-meta-display.yml",
    "main/java/com/example/lms/web/PageController.java",
    "main/java/com/example/lms/web/ChatUiViewConfig.java",
    "main/java/com/example/lms/llm/ModelCapabilities.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
    "main/java/com/example/lms/config/LangChain4jBeans.java",
    "main/java/com/example/lms/config/LangChainConfig.java",
    "main/java/com/example/lms/llm/OpenAiChatModel.java",
    "main/java/com/example/lms/service/EnsembleJudgeService.java",
    "main/java/com/example/lms/config/NovaPropertyAliasEnvironmentPostProcessor.java",
    "configs/agent-test-model-policy.yaml",
]

MAX_HITS_PER_FILE = 40


def read_lines(rel):
    path = ROOT / rel
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None


def prop_default(lines, key):
    """Return the :default inside ${KEY:default} or `key: value` yaml scalar."""
    if lines is None:
        return None
    hit = {"placeholder": None, "scalar": None}
    needle = "${" + key + ":"
    scalar = re.compile(r"^\s*" + re.escape(key.split(".")[-1]) + r"\s*:\s*(\S+)")
    for ln, line in enumerate(lines, 1):
        if needle in line:
            m = re.search(re.escape("${" + key + ":") + r"([^}]*)}", line)
            hit["placeholder"] = {"line": ln, "default": m.group(1) if m else None}
        elif scalar.match(line) and "${" not in line:
            hit["scalar"] = {"line": ln, "value": scalar.match(line).group(1)}
    return hit if hit["placeholder"] or hit["scalar"] else None


def scan_gemma4():
    out = {}
    for rel in SCAN_FILES:
        lines = read_lines(rel)
        if lines is None:
            continue
        hits = [ln for ln, l in enumerate(lines, 1) if "gemma4:26b" in l]
        if hits:
            out[rel] = hits[:MAX_HITS_PER_FILE]
    return out


def launcher_override():
    lines = read_lines("scripts/start_rag_stack.ps1") or []
    found = {}
    for ln, line in enumerate(lines, 1):
        for key in ("APP_AI_UI_DEFAULT_MODEL", "APP_AI_DEFAULT_MODEL"):
            m = re.search(key + r"\s*=\s*'([^']*)'", line)
            if m:
                found[key] = {"line": ln, "value": m.group(1)}
    return {
        "file": "scripts/start_rag_stack.ps1",
        "defaults": found,
        "processEnv": {k: os.environ.get(k) for k in
                       ("APP_AI_UI_DEFAULT_MODEL", "APP_AI_DEFAULT_MODEL")
                       if os.environ.get(k)},
    }


def oauth_models():
    path = ROOT / "data/agent-handoff/chatgpt-oauth/models.json"
    info = {"file": "data/agent-handoff/chatgpt-oauth/models.json", "present": path.is_file()}
    if not path.is_file():
        return info
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        info["error"] = "unreadable"
        return info
    models = [m for m in doc.get("models") or [] if isinstance(m, str)]
    info.update({
        "status": doc.get("status"),
        "syncedAtUtc": doc.get("syncedAtUtc"),
        "count": doc.get("count"),
        "models": models,
        "routeIds": ["chatgpt-oauth:" + m for m in models],
        "recommended": "chatgpt-oauth:gpt-5.5" if "gpt-5.5" in models
                       else ("chatgpt-oauth:" + models[0] if models else None),
    })
    return info


def test_policy():
    lines = read_lines("configs/agent-test-model-policy.yaml") or []
    purposes = {}
    current = None
    for line in lines:
        m = re.match(r"^  ([a-z_]+):\s*$", line)
        if m:
            current = m.group(1)
            continue
        m = re.match(r"^\s*prefer:\s*\[([^\]]*)\]", line)
        if m and current:
            items = [x.strip().strip('"') for x in m.group(1).split(",") if x.strip()]
            purposes[current] = items
            current = None
    gate = None
    for line in lines:
        if "apiFirstUntil" in line:
            gate = line.split(":", 1)[1].strip().strip('"')
    return {"purposesPrefer": purposes, "apiFirstUntil": gate}


def get_json(url, timeout=3.0):
    req = urllib.request.Request(url, headers={"Accept": "application/json",
                                               "Cookie": "ownerKey=devin-model-probe-01"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return e.code, None
    except (OSError, ValueError):
        return None, None


def server_probe(base):
    out = {"base": base, "reachable": False}
    st, models = get_json(base.rstrip("/") + "/api/chat/models")
    if st is None:
        out["modelsEndpoint"] = {"status": "unreachable"}
        return out
    out["reachable"] = True
    if st == 200 and isinstance(models, list):
        auth = [c.get("id") for c in models
                if isinstance(c, dict) and str(c.get("id", "")).startswith("chatgpt-oauth:")]
        local = [c.get("id") for c in models
                 if isinstance(c, dict) and isinstance(c.get("id"), str)
                 and LOCAL_HINT.match(c["id"])]
        out["modelsEndpoint"] = {
            "status": st, "count": len(models),
            "authIds": auth, "localCount": len(local),
        }
    else:
        out["modelsEndpoint"] = {"status": st}
    st2, prefs = get_json(base.rstrip("/") + "/api/settings/preferences")
    if st2 == 200 and isinstance(prefs, dict):
        eff = prefs.get("effective") or {}
        src = prefs.get("sources") or {}
        fac = prefs.get("factoryDefaults") or {}
        ovr = prefs.get("overrides") or {}
        out["preferences"] = {
            "status": st2,
            "effectiveModel": eff.get("model"),
            "modelSource": src.get("model"),
            "factoryModel": fac.get("model"),
            "overrideModel": ovr.get("model"),
            "scope": prefs.get("ownerScope"),
        }
    else:
        out["preferences"] = {"status": st2}
    return out


def log_scan(path):
    if not path:
        latest = sorted((ROOT / "var/rag-launcher").glob(
            "*/chat-ui-vibe-listener-18180.out.log"),
            key=lambda p: p.stat().st_mtime if p.is_file() else 0, reverse=True)
        path = str(latest[0]) if latest else None
    if not path or not Path(path).is_file():
        return {"path": path, "present": False}
    counts = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                for m in OAUTH_REASON.findall(line):
                    counts[m] = counts.get(m, 0) + 1
    except OSError:
        return {"path": path, "present": False}
    return {"path": path, "present": True, "oauthFailureReasonCodes": counts}


def lane_of(model_id):
    s = (model_id or "").strip()
    if s.startswith("chatgpt-oauth:"):
        return "auth"
    if s.startswith("llmrouter.") or s.startswith("gpt-") or s.startswith("openai") \
            or s.startswith("o-") or re.match(r"o\d", s) or "/" in s \
            or s.startswith("gemini") or s.startswith("claude"):
        return "api"
    if not s:
        return "unknown"
    return "local"


def static_chain(cfg, launcher):
    """Best offline guess: env > app.ai.ui-default-model > llm.chat-model > hardcode."""
    env_ui = (launcher["processEnv"].get("APP_AI_UI_DEFAULT_MODEL")
              or (launcher["defaults"].get("APP_AI_UI_DEFAULT_MODEL") or {}).get("value"))
    if env_ui:
        return env_ui, "launcher-env:app.ai.ui-default-model"
    ui = ((cfg.get("app.ai.ui-default-model") or {}).get("placeholder") or {}).get("default")
    if ui and not ui.startswith("${"):
        return ui, "property:app.ai.ui-default-model"
    chat = ((cfg.get("llm.chat-model") or {}).get("placeholder") or {}).get("default")
    if chat and not chat.startswith("${"):
        return chat, "property:llm.chat-model"
    return "gemma4:26b", "hardcode:ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL"


def main():
    import argparse
    ap = argparse.ArgumentParser(description="probe effective /chat default model")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--log", default=None)
    ap.add_argument("--base", default="http://127.0.0.1:18180")
    ap.add_argument("--no-server", action="store_true")
    args = ap.parse_args()

    app_yml = read_lines("main/resources/application.yml")
    llm_yml = read_lines("main/resources/application-llm.yaml")
    cfg = {
        "llm.provider": prop_default(app_yml, "LLM_PROVIDER") or prop_default(app_yml, "llm.provider"),
        "llm.chat-model": prop_default(app_yml, "LLM_CHAT_MODEL") or prop_default(app_yml, "llm.chat-model"),
        "app.ai.ui-default-model": prop_default(app_yml, "APP_AI_UI_DEFAULT_MODEL")
            or prop_default(app_yml, "app.ai.ui-default-model"),
        "app.ai.default-model": prop_default(app_yml, "APP_AI_DEFAULT_MODEL")
            or prop_default(app_yml, "app.ai.default-model"),
        "chat.defaults.model": prop_default(llm_yml, "chat.defaults.model")
            or {"note": "application-llm.yaml:862 -> ${openai.api.model.default:${llm.chat-model:gemma4:26b}}"},
    }
    launcher = launcher_override()
    oauth = oauth_models()
    policy = test_policy()
    logscan = log_scan(args.log)
    server = {"reachable": False, "skipped": "no-server"} if args.no_server \
        else server_probe(args.base)

    if server.get("reachable") and isinstance(server.get("preferences"), dict) \
            and server["preferences"].get("effectiveModel"):
        effective = server["preferences"]["effectiveModel"]
        source = "live:/api/settings/preferences effective.model (%s)" % (
            server["preferences"].get("modelSource"))
        live = True
    else:
        effective, source = static_chain(cfg, launcher)
        live = False

    lane = lane_of(effective)
    broken = bool(logscan.get("oauthFailureReasonCodes"))
    if not live:
        verdict = "STATIC_AUTH_PROJECTED" if lane == "auth" else (
            "STATIC_LOCAL_DEFAULT" if lane == "local" else "STATIC_API_DEFAULT")
    elif lane == "auth":
        verdict = "AUTH_DEFAULT_BUT_ROUTE_BROKEN" if broken else "AUTH_FIRST_OK"
    elif lane == "local":
        verdict = "LOCAL_DEFAULT_VIOLATION"
    elif lane == "api":
        verdict = "API_DEFAULT_INTERMEDIATE"
    else:
        verdict = "UNKNOWN"

    report = {
        "schemaVersion": "awx.model-default-probe.v1",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "root": str(ROOT),
        "verdict": verdict,
        "effectiveDefault": {"model": effective, "lane": lane, "source": source,
                             "live": live,
                             "chain": "personal-override > db-currentModel > "
                                      "app.ai.ui-default-model > app.ai.default-model > "
                                      "chat.defaults.model > llm.chat-model > hardcode"},
        "configChain": cfg,
        "launcherOverride": launcher,
        "oauth": oauth,
        "agentTestPolicy": policy,
        "server": server,
        "logScan": logscan,
        "gemma4_26b_locations": scan_gemma4(),
    }
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print("verdict:", verdict)
        print("effective default:", effective, "(%s, lane=%s)" % (source, lane))
        print("auth route ids:", ", ".join(oauth.get("routeIds") or []) or "none")
        print("server reachable:", server.get("reachable"))
        if logscan.get("oauthFailureReasonCodes"):
            print("recent oauth failures:", logscan["oauthFailureReasonCodes"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
