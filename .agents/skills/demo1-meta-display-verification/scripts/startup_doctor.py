"""Read-only startup evidence. Never starts services or contacts external APIs."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


MAX_BODY = 16384
UPSTASH_NAMES = ("UPSTASH_REDIS_REST_URL", "UPSTASH_REDIS_REST_TOKEN")


def endpoint_plan(env):
    """Source defaults are a hypothesis; Spring's effective config is separate."""
    result = {"reason": "endpoint-invalid", "coherent": False,
              "scope": "invalid", "probeUrl": None}
    host = env.get("OLLAMA_HOST", "127.0.0.1:11435").strip()
    health = env.get("LOCAL_LLM_HEALTH_CHECK_URL",
                     "http://127.0.0.1:11435/api/version").strip()
    host_url = host if "://" in host else "http://" + host
    try:
        parsed = [urllib.parse.urlsplit(value) for value in (host_url, health)]
        for raw, url, path in zip((host_url, health), parsed, ("", "/api/version")):
            if (any(ord(char) < 33 or ord(char) == 127 for char in raw)
                    or "?" in raw or "#" in raw or "\\" in raw
                    or url.scheme != "http" or url.username is not None
                    or url.password is not None or url.path != path
                    or not url.hostname or not url.port or not 1 <= url.port <= 65535):
                return result
        if any(url.hostname not in ("127.0.0.1", "localhost", "::1") for url in parsed):
            return dict(result, reason="external-endpoint", scope="external")
        identities = [("127.0.0.1" if url.hostname == "localhost" else url.hostname,
                       url.port) for url in parsed]
        if identities[0] != identities[1]:
            return dict(result, reason="endpoint-mismatch", scope="loopback")
        name, port = identities[1]
        address = "[::1]" if name == "::1" else name
        return {"reason": "endpoint-coherent", "coherent": True, "scope": "loopback",
                "probeUrl": f"http://{address}:{port}/api/version"}
    except (ValueError, TypeError):
        return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def _probe_version_http(url, timeout=2):
    # Validate even when called without endpoint_plan. Do not honor proxy envs.
    try:
        parsed = urllib.parse.urlsplit(url)
        plan = endpoint_plan({"OLLAMA_HOST": f"{parsed.scheme}://{parsed.netloc}",
                              "LOCAL_LLM_HEALTH_CHECK_URL": url})
        if not plan["coherent"]:
            return {"status": "not-probed", "reason": plan["reason"]}
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(plan["probeUrl"], timeout=max(0.1, min(float(timeout), 2))) as response:
            if response.status != 200:
                return {"status": "unhealthy", "reason": "invalid-version-response"}
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                return {"status": "unhealthy", "reason": "oversized-version-response"}
            data = json.loads(body)
            version = data.get("version") if isinstance(data, dict) else None
            if not isinstance(version, str) or not version.strip():
                return {"status": "unhealthy", "reason": "invalid-version-response"}
            return {"status": "healthy", "reason": "version-response-valid"}
    except urllib.error.HTTPError:
        return {"status": "unhealthy", "reason": "http-error-or-redirect"}
    except (ValueError, UnicodeError):
        return {"status": "unhealthy", "reason": "invalid-version-response"}
    except (OSError, urllib.error.URLError):
        return {"status": "unavailable", "reason": "ollama-unavailable"}


def probe_version(url, timeout=2):
    """A disposable owned worker enforces a total deadline, including slow headers/body."""
    parsed = urllib.parse.urlsplit(url)
    plan = endpoint_plan({"OLLAMA_HOST": f"{parsed.scheme}://{parsed.netloc}",
                          "LOCAL_LLM_HEALTH_CHECK_URL": url})
    if not plan["coherent"]:
        return {"status": "not-probed", "reason": plan["reason"]}
    try:
        result = subprocess.run(
            [sys.executable, "-B", str(Path(__file__).absolute()), "--version-worker"],
            input=plan["probeUrl"].encode("ascii"), capture_output=True, check=False,
            timeout=max(0.1, min(float(timeout), 2)),
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode != 0 or len(result.stdout) > 1024:
            raise ValueError("worker-result")
        data = json.loads(result.stdout)
        allowed = {
            ("healthy", "version-response-valid"),
            ("unhealthy", "invalid-version-response"),
            ("unhealthy", "oversized-version-response"),
            ("unhealthy", "http-error-or-redirect"),
            ("unavailable", "ollama-unavailable"),
        }
        if (not isinstance(data, dict) or set(data) != {"status", "reason"}
                or (data.get("status"), data.get("reason")) not in allowed):
            raise ValueError("worker-result")
        return {"status": data["status"], "reason": data["reason"]}
    except subprocess.TimeoutExpired:
        return {"status": "unavailable", "reason": "version-probe-deadline"}
    except (OSError, ValueError, TypeError):
        return {"status": "unavailable", "reason": "version-probe-failed"}


def find_executable(name, env):
    candidates = []
    if name == "java" and env.get("JAVA_HOME"):
        candidates.append(Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    if name == "ollama" and os.name == "nt" and env.get("LOCALAPPDATA"):
        candidates.append(Path(env["LOCALAPPDATA"]) / "Programs" / "Ollama" / "ollama.exe")
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return shutil.which(name, path=env.get("PATH", ""))


def java_probe(env):
    executable = find_executable("java", env)
    if executable is None:
        return {"java17": False, "reason": "java-unavailable"}
    try:
        completed = subprocess.run([executable, "-version"], capture_output=True,
                                   timeout=3, check=False,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        output = (completed.stdout + completed.stderr)[:16384].decode("utf-8", "replace")
        match = re.search(r'\bversion\s+"(\d+)(?:[.\-"]|$)', output)
        valid = completed.returncode == 0 and match is not None and match.group(1) == "17"
        return {"java17": valid, "reason": "java17-confirmed" if valid else "java-not-17"}
    except (OSError, subprocess.TimeoutExpired):
        return {"java17": False, "reason": "java-version-probe-failed"}


def flag(env, name):
    value = env.get(name, "").strip().lower()
    return True if value == "true" else False if value == "false" else None


def persistent_presence(name):
    result = {"user": None, "machine": None}
    if os.name != "nt":
        return result
    import winreg
    for scope, hive, path in (
        ("user", winreg.HKEY_CURRENT_USER, "Environment"),
        ("machine", winreg.HKEY_LOCAL_MACHINE,
         r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"),
    ):
        try:
            with winreg.OpenKey(hive, path, 0, winreg.KEY_READ) as key:
                value, _ = winreg.QueryValueEx(key, name)
                result[scope] = bool(str(value).strip())
        except FileNotFoundError:
            result[scope] = False
        except OSError:
            pass
    return result


def inspect_environment(env, probe=False):
    plan = endpoint_plan(env)
    health = ({"status": "not-probed", "reason": "probe-not-requested"} if not probe else
              probe_version(plan["probeUrl"]) if plan["coherent"] else
              {"status": "not-probed", "reason": plan["reason"]})
    enabled, autostart = flag(env, "LOCAL_LLM_ENABLED"), flag(env, "LOCAL_LLM_AUTOSTART")
    upstash = {name: dict(persistent_presence(name), process=bool(env.get(name, "").strip()))
               for name in UPSTASH_NAMES}
    missing = ["effective-spring-configuration", "model-readiness", "live-sync",
               "provider-lineage", "desktop-final-proof"]
    if not all(row["process"] for row in upstash.values()):
        missing.append("upstash-environment-evidence-missing")
    return {"schemaVersion": 1, "executionOwner": "current-host-supporting",
            "scope": "current-host-only", "mutationPerformed": False,
            "diagnosticCompleted": True, "serviceReadinessProven": False,
            "java": java_probe(env), "ollamaExecutablePresent": find_executable("ollama", env) is not None,
            "localLlmEnabled": enabled, "localLlmAutostart": autostart,
            "autostartReason": "autostart-explicitly-disabled" if False in (enabled, autostart)
            else "confirm-effective-spring-configuration",
            "endpoint": {key: value for key, value in plan.items() if key != "probeUrl"},
            "localHealth": health, "upstashPresenceOnly": upstash,
            "credentialValidityProven": False, "externalNetworkCalls": 0,
            "evidence_needed": missing}


def main():
    # Private subprocess entry: stdin only, no URL/credential in process arguments.
    if sys.argv[1:] == ["--version-worker"]:
        try:
            url = sys.stdin.buffer.read(512).decode("ascii")
            print(json.dumps(_probe_version_http(url)))
            return 0
        except Exception:
            return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", action="store_true", help="GET only coherent loopback /api/version")
    args = parser.parse_args()
    try:
        result = json.dumps(inspect_environment(os.environ, probe=args.probe), separators=(",", ":"))
        if len(result.encode("utf-8")) >= 8192:
            raise ValueError("output-bound")
        print(result)
        return 0
    except Exception:
        print('{"diagnosticCompleted":false,"reason":"startup-diagnostic-failed","mutationPerformed":false}')
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
