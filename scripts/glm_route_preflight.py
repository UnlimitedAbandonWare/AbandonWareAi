"""GLM assist route preflight for Codex sessions.

Classifies which GLM lane (if any) is usable *right now* without exposing any
secret values. Prints one JSON object plus a single verdict line.

Routes (exit code in parentheses):
  MCP_READY (0)                      glm_agent MCP configured + readiness env
                                     flags present -> use glm_agent tools.
  MCP_DISABLED_FLAGS (1)             glm_agent configured but the readiness env
                                     flags are absent -> tools answer
                                     BLOCKED_EXTERNAL / WAITING_*.
  MCP_NOT_CONFIGURED (2)             glm_agent MCP entry missing/disabled.
  NATIVE_UNSUPPORTED_CHATGPT_LOGIN (3) no top-level model_provider in
                                     config.toml (ChatGPT account path) and no
                                     usable MCP lane -> native glm_worker
                                     spawn will hit HTTP 400; do not retry.
  KEY_MISSING (4)                    AI_GATEWAY_API_KEY absent -> zero GLM calls.

--live-status additionally attaches to the configured glm_agent MCP server
over stdio and calls glm_agent_status exactly once (no generation call).
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import subprocess
import sys
import threading
import tomllib
from pathlib import Path

ROUTES = {
    "MCP_READY": 0,
    "MCP_DISABLED_FLAGS": 1,
    "MCP_NOT_CONFIGURED": 2,
    "NATIVE_UNSUPPORTED_CHATGPT_LOGIN": 3,
    "KEY_MISSING": 4,
}

READINESS_FLAGS = ("AGENT_SUBAGENT_GLM_ENABLED", "GLM_EXTERNAL_READY")
KEY_ENV = "AI_GATEWAY_API_KEY"
LIVE_STATUS_TIMEOUT_SEC = 60.0


def _env_present(environ, name):
    value = environ.get(name)
    return value is not None and str(value).strip() != ""


def load_codex_config(config_path):
    """Return parsed config.toml dict, or None when unreadable/absent."""
    path = Path(config_path)
    if not path.is_file():
        return None
    try:
        with open(path, "rb") as handle:
            return tomllib.load(handle)
    except (OSError, tomllib.TOMLDecodeError):
        return None


def inspect(config, environ):
    """Build the fact map used for classification. Booleans/summaries only."""
    servers = (config or {}).get("mcp_servers") or {}
    glm = servers.get("glm_agent") or {}
    providers = (config or {}).get("model_providers") or {}
    top_level_provider = bool((config or {}).get("model_provider"))
    enabled_tools = glm.get("enabled_tools")
    # Flags may live in the real environment or in the server's own `env` map
    # (Codex injects that map into the MCP child process only).
    glm_env = glm.get("env") or {}
    config_flags = {name for name in glm_env
                    if str(glm_env.get(name)).strip().lower() in ("true", "1", "yes")}
    return {
        "keyPresent": _env_present(environ, KEY_ENV),
        "flags": {name: _env_present(environ, name) or name in config_flags
                  for name in READINESS_FLAGS},
        "flagsFromGlmAgentEnv": sorted(n for n in READINESS_FLAGS if n in config_flags),
        "configReadable": config is not None,
        "topLevelModelProviderPresent": top_level_provider,
        "glmAgentPresent": bool(glm),
        "glmAgentEnabled": bool(glm.get("enabled", True)) if glm else False,
        "glmAgentEnabledTools": [str(t) for t in enabled_tools] if isinstance(enabled_tools, list) else [],
        "vercelProviderPresent": "vercel" in providers,
        # ChatGPT account path = no top-level model_provider override.
        "nativeUnsupportedChatgptLogin": not top_level_provider,
    }


def classify(facts):
    if not facts["keyPresent"]:
        return "KEY_MISSING"
    glm_usable = facts["glmAgentPresent"] and facts["glmAgentEnabled"]
    flags_ok = all(facts["flags"].values())
    if glm_usable and flags_ok:
        return "MCP_READY"
    if glm_usable:
        return "MCP_DISABLED_FLAGS"
    if facts["nativeUnsupportedChatgptLogin"]:
        return "NATIVE_UNSUPPORTED_CHATGPT_LOGIN"
    return "MCP_NOT_CONFIGURED"


def _mcp_server_spec(config):
    glm = ((config or {}).get("mcp_servers") or {}).get("glm_agent") or {}
    command = glm.get("command")
    args = glm.get("args")
    if not command or not isinstance(args, list):
        return None
    env = os.environ.copy()
    for name in glm.get("env_vars") or []:
        if name not in env:
            pass  # absent names stay absent; nothing is fabricated
    return {"command": str(command), "args": [str(a) for a in args],
            "cwd": str(glm.get("cwd")) if glm.get("cwd") else None}


def live_status(config, environ, timeout=LIVE_STATUS_TIMEOUT_SEC):
    """One glm_agent_status call over MCP stdio. No provider/generation call."""
    spec = _mcp_server_spec(config)
    if spec is None:
        return {"ok": False, "reason": "glm_agent command not configured"}
    env = environ.copy()
    try:
        proc = subprocess.Popen(
            [spec["command"]] + spec["args"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, cwd=spec["cwd"], env=env)
    except OSError as error:
        return {"ok": False, "reason": "spawn_failed", "detail": type(error).__name__}
    replies = queue.Queue()

    def reader():
        try:
            for raw in proc.stdout:
                line = raw.strip()
                if line:
                    replies.put(line.decode("utf-8", "replace"))
        except (OSError, ValueError):
            pass
        finally:
            replies.put(None)

    def send(message):
        proc.stdin.write(json.dumps(message).encode() + b"\n")
        proc.stdin.flush()

    threading.Thread(target=reader, daemon=True).start()
    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "glm-route-preflight", "version": "0"}}})
        deadline_ok = _wait_for_id(replies, 1, timeout)
        if not deadline_ok:
            return {"ok": False, "reason": "initialize_timeout"}
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        send({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
              "params": {"name": "glm_agent_status", "arguments": {}}})
        response = _wait_for_id(replies, 2, timeout)
        if response is None:
            return {"ok": False, "reason": "status_timeout"}
        return {"ok": True, "status": response}
    except (OSError, BrokenPipeError) as error:
        return {"ok": False, "reason": "stdio_failed", "detail": type(error).__name__}
    finally:
        try:
            proc.terminate()
        except OSError:
            pass


def _wait_for_id(replies, wanted, timeout):
    """Return the JSON-RPC result/error for id==wanted, else None/False."""
    import time
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            line = replies.get(timeout=max(0.1, end - time.monotonic()))
        except queue.Empty:
            break
        if line is None:
            return False if wanted == 1 else None
        try:
            message = json.loads(line)
        except ValueError:
            continue
        if message.get("id") == wanted:
            return message.get("result", message.get("error"))
    return False if wanted == 1 else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--config", default=str(Path.home() / ".codex" / "config.toml"))
    parser.add_argument("--live-status", action="store_true",
                        help="one glm_agent_status call over MCP stdio")
    args = parser.parse_args(argv)

    config = load_codex_config(args.config)
    facts = inspect(config, os.environ)
    route = classify(facts)
    report = {"route": route, "exitCode": ROUTES[route], **facts}
    if args.live_status:
        report["liveStatus"] = live_status(config, os.environ) if config else {
            "ok": False, "reason": "config_unreadable"}
    print(json.dumps(report, ensure_ascii=False))
    print(f"route={route} nativeUnsupportedChatgptLogin="
          f"{str(facts['nativeUnsupportedChatgptLogin']).lower()} "
          f"keyPresent={str(facts['keyPresent']).lower()} "
          f"flags={','.join(k for k, v in facts['flags'].items() if v) or 'none'}")
    return ROUTES[route]


if __name__ == "__main__":
    raise SystemExit(main())
