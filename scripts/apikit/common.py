#!/usr/bin/env python3
"""apikit.common - shared HTTP / key-resolution / masking / classification.

Contract DEMO1-DEVIN-LITE-API-TESTKIT-20260929 (absorbs
DEMO1-DEVIN-API-KEY-HEALTH-PROBE-20260929). stdlib only. API key VALUES never
leave this process: outputs carry env name, source, length and sha256[:8] only.
"""
from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = Path(__file__).resolve().parents[2]  # .../src (scripts/apikit/common.py)
SECRETS_PATH = ROOT / ".secrets" / "providers.json"
GUARD_YAML = ROOT / "configs" / "agent-api-spend-guard.yaml"
ROUTING_YAML = ROOT / "configs" / "api-routing.yaml"
EXPIRY_PATH = ROOT / "configs" / "api-key-expiry.json"
OUT_DIR = ROOT / "data" / "agent-handoff" / "apikit"
DEFAULT_TIMEOUT_S = 10.0
EXPIRY_WARN_DAYS = 7
MAX_BODY_BYTES = 4 * 1024 * 1024  # /v1/models catalogs can exceed 256KB
SNIPPET_CHARS = 300
LONG_TOKEN_RE = re.compile(r"[A-Za-z0-9_\-]{24,}")

# Classification vocabulary (spec §3) — keep exact strings.
OK = "OK"
KEY_MISSING = "KEY_MISSING"
KEY_MISMATCH = "KEY_MISMATCH"
KEY_INVALID = "KEY_INVALID_OR_EXPIRED"
PLAN_GATE = "PLAN_GATE"
FORBIDDEN = "FORBIDDEN_REGION_OR_IP"
QUOTA = "QUOTA_OR_BALANCE"
RATE_LIMIT = "RATE_LIMIT"
MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
BAD_SHAPE = "BAD_REQUEST_SHAPE"
NETWORK = "NETWORK"
SERVER_5XX = "SERVER_5XX"
UNKNOWN = "UNKNOWN"
ALL_CLASSES = (OK, KEY_MISSING, KEY_MISMATCH, KEY_INVALID, PLAN_GATE,
               FORBIDDEN, QUOTA, RATE_LIMIT, MODEL_NOT_FOUND, BAD_SHAPE,
               NETWORK, SERVER_5XX, UNKNOWN)

_STATUS_CLASS = {
    400: BAD_SHAPE,
    401: KEY_INVALID,
    402: QUOTA,
    403: FORBIDDEN,
    404: MODEL_NOT_FOUND,
    408: NETWORK,
    429: RATE_LIMIT,
}

# HTTP 403 splits by body evidence, never by status alone:
# plan/paid-feature restriction (Vercel ZDR on Hobby etc.) -> PLAN_GATE;
# permission/region/IP denial -> FORBIDDEN. SSOT: docs/API_ROUTING_SPEC.md
# §external-api-failure-classification.
_PLAN_GATE_CODE = {"plan_gate", "plan_required", "plan_entitlement",
                   "feature_not_on_plan", "upgrade_required"}
_PLAN_GATE_RE = re.compile(
    r"upgrade your plan|"
    r"only available (?:for|on|to) (?:the )?(?:pro|enterprise|paid|higher)|"
    r"current plan|"
    r"requires? (?:a )?(?:pro|enterprise|paid|higher) plan|"
    r"not (?:available|included|enabled) (?:on|in|with|for) your (?:current )?plan|"
    r"plan[\s_-]?gate",
    re.I)


def _plan_gate_signal(code, msg, text):
    if code and str(code).lower() in _PLAN_GATE_CODE:
        return True
    hay = msg if isinstance(msg, str) and msg else (
        text[:2000] if isinstance(text, str) else "")
    return bool(hay) and bool(_PLAN_GATE_RE.search(hay))


def load_expiry_ledger(path=None):
    """{ENV: entry} from configs/api-key-expiry.json. Metadata only —
    the ledger stores env/sha8/last4/expiresAt, never key values."""
    try:
        data = json.loads((path or EXPIRY_PATH).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    out = {}
    for entry in data.get("keys") or []:
        if isinstance(entry, dict) and entry.get("env"):
            out[str(entry["env"]).upper()] = entry
    return out


def expiry_status(spec, ki, ledger=None, now=None):
    """Key-expiry state for a resolved key: ok | warn-d<n> | expired |
    other_key | not_recorded. Absent metadata is reported, never guessed."""
    if ledger is None:
        ledger = load_expiry_ledger()
    entry = ledger.get(str(ki.get("env") or "").upper())
    expires = (entry or {}).get("expiresAt")
    if not entry or not expires:
        return {"state": "not_recorded"}
    if entry.get("sha8") and ki.get("sha8") and entry["sha8"] != ki["sha8"]:
        return {"state": "other_key", "expiresAt": expires}
    try:
        exp = datetime.datetime.fromisoformat(str(expires).replace("Z", "+00:00"))
    except ValueError:
        return {"state": "not_recorded"}
    if exp.tzinfo is None:
        exp = exp.replace(tzinfo=datetime.timezone.utc)
    now = now or datetime.datetime.now(datetime.timezone.utc)
    days = (exp - now).days
    out = {"expiresAt": expires, "daysLeft": days}
    if exp <= now:
        out["state"] = "expired"
    elif days <= EXPIRY_WARN_DAYS:
        out["state"] = "warn-d%d" % days
    else:
        out["state"] = "ok"
    return out

# Auth styles shared by providers. Each returns headers given one secret value.
def _auth_bearer(key):
    return {"Authorization": "Bearer %s" % key}


def _auth_token(key):
    return {"Authorization": "Token %s" % key}


def _auth_kakao(key):
    return {"Authorization": "KakaoAK %s" % key}


AUTH_STYLES = {
    "bearer": _auth_bearer,
    "token": _auth_token,
    "kakao": _auth_kakao,
    "x-goog": lambda k: {"x-goog-api-key": k},
    "x-subscription": lambda k: {"X-Subscription-Token": k},
    "x-api-key": lambda k: {"x-api-key": k},
    "api-key": lambda k: {"Api-Key": k},
}

# Header names that may carry a credential in a replayed request. Stripped from
# --from files and replaced by a freshly resolved key (never echoed back).
CREDENTIAL_HEADERS = {
    "authorization", "x-api-key", "api-key", "x-goog-api-key",
    "x-subscription-token", "x-naver-client-id", "x-naver-client-secret",
    "proxy-authorization",
}


def sha8(value):
    if not value:
        return None
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:8]


def mask(text, secret_values=()):
    """Mask known secret values, then any long unbroken token (env values,
    bearer blobs, JWTs). Never raises."""
    if text is None:
        return None
    out = str(text)
    for v in secret_values:
        if v and len(v) >= 6:
            out = out.replace(v, "<key:%s>" % sha8(v))
    return LONG_TOKEN_RE.sub("<tok>", out)


def _reg_scope(root_key, subkey):
    try:
        import winreg
    except ImportError:
        return {}
    try:
        handle = winreg.OpenKey(root_key, subkey)
    except OSError:
        return {}
    out = {}
    i = 0
    while True:
        try:
            name, value, _t = winreg.EnumValue(handle, i)
        except OSError:
            break
        out[name.upper()] = value
        i += 1
    return out


def scope_envs():
    """{scope: {NAME: value}} for Process/User/Machine. Empty dict off-Windows."""
    import winreg
    return {
        "Process": {k.upper(): v for k, v in os.environ.items()},
        "User": _reg_scope(winreg.HKEY_CURRENT_USER, "Environment"),
        "Machine": _reg_scope(
            winreg.HKEY_LOCAL_MACHINE,
            r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment"),
    }


def load_secrets():
    """{.secrets NAME: value}; returns {} when absent/unreadable."""
    try:
        data = json.loads(SECRETS_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    values = data.get("values") if isinstance(data, dict) else None
    out = {}
    if isinstance(values, dict):
        for name, entry in values.items():
            if isinstance(entry, dict) and isinstance(entry.get("value"), str):
                out[name.upper()] = entry["value"]
            elif isinstance(entry, str):
                out[name.upper()] = entry
    return out


def resolve_key(env_names, secrets=None, scopes=None):
    """Resolve first non-empty value for env_names across Process > User >
    Machine > .secrets. Returns a dict safe for output (value included only for
    internal call use — strip before writing if the whole dict is serialized)."""
    if secrets is None:
        secrets = load_secrets()
    if scopes is None:
        scopes = scope_envs()
    names = [n.upper() for n in env_names]
    present = {}
    for scope_name in ("Process", "User", "Machine"):
        scope = scopes.get(scope_name) or {}
        for n in names:
            v = scope.get(n)
            if v and str(v).strip():
                present.setdefault(n, {})[scope_name] = str(v)
    secrets_hit = {}
    for n in names:
        v = secrets.get(n)
        if v and str(v).strip():
            secrets_hit[n] = str(v)
    chosen_env = None
    chosen_val = None
    chosen_src = None
    for scope_name in ("Process", "User", "Machine"):
        for n in names:
            v = (present.get(n) or {}).get(scope_name)
            if v:
                chosen_env, chosen_val, chosen_src = n, v, scope_name
                break
        if chosen_val:
            break
    if chosen_val is None:
        for n in names:
            if n in secrets_hit:
                chosen_env, chosen_val, chosen_src = n, secrets_hit[n], ".secrets"
                break
    srcs = sorted({s for n in present for s in present[n]}
                  | ({"secrets"} if secrets_hit else set()))
    values = {v for n in present for v in present[n].values()}
    values |= set(secrets_hit.values())
    mismatch = len(values) > 1
    return {
        "env": chosen_env or "+".join(env_names),
        "value": chosen_val,
        "src": chosen_src,
        "srcs": sorted(srcs),
        "len": len(chosen_val) if chosen_val else 0,
        "sha8": sha8(chosen_val),
        "mismatch": mismatch,
    }


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


USER_AGENT = "apikit/1.0 (+demo-1; stdlib)"


def http_request(method, url, headers=None, body=None, timeout=DEFAULT_TIMEOUT_S):
    """One shot, no redirects, bounded body. Returns a plain dict; `text` and
    `error` are already masked of known secrets by the caller via mask().
    The urllib default UA is Cloudflare-1010-blocked (observed 2026-09-29 on
    Groq /models: same key 403 with Python-urllib, 200 with any explicit UA)."""
    req = urllib.request.Request(url, method=method.upper(), data=body)
    req.add_header("User-Agent", USER_AGENT)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    t0 = time.monotonic()
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            raw = resp.read(MAX_BODY_BYTES + 1)
            return {
                "status": resp.status,
                "headers": dict(resp.headers.items()),
                "text": raw[:MAX_BODY_BYTES].decode("utf-8", "replace"),
                "truncated": len(raw) > MAX_BODY_BYTES,
                "ms": int((time.monotonic() - t0) * 1000),
                "error": None, "error_kind": None,
            }
    except urllib.error.HTTPError as exc:
        ms = int((time.monotonic() - t0) * 1000)
        try:
            raw = exc.read(MAX_BODY_BYTES + 1)
            text = raw[:MAX_BODY_BYTES].decode("utf-8", "replace")
        except Exception:
            text = None
        return {"status": exc.code, "headers": dict(exc.headers or {}),
                "text": text, "ms": ms, "error": "HTTPError", "error_kind": None}
    except (socket.timeout, TimeoutError) as exc:
        return {"status": None, "headers": {}, "text": None,
                "ms": int((time.monotonic() - t0) * 1000),
                "error": type(exc).__name__, "error_kind": "timeout"}
    except (urllib.error.URLError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        kind = "timeout" if isinstance(reason, (socket.timeout, TimeoutError)) else "network"
        return {"status": None, "headers": {}, "text": None,
                "ms": int((time.monotonic() - t0) * 1000),
                "error": str(reason)[:200], "error_kind": kind}


def _walk(parsed, path):
    """Walk path of keys/'*' list-items; returns first non-empty scalar."""
    cur = [parsed]
    for elem in path:
        nxt = []
        for item in cur:
            if elem == "*":
                if isinstance(item, list):
                    nxt.extend(item)
            elif isinstance(elem, int):
                if isinstance(item, list) and len(item) > elem:
                    nxt.append(item[elem])
            elif isinstance(item, dict) and elem in item:
                nxt.append(item[elem])
        cur = nxt
        if not cur:
            break
    for item in cur:
        if isinstance(item, (str, int, float)) and item != "":
            return str(item)
    return None


def official_code(spec, parsed):
    for path in spec.get("code_paths", ()):
        code = _walk(parsed, path)
        if code:
            return code
    return None


def classify(spec, result):
    """(cls, official_code, detail). Official error codes are recorded verbatim
    and mapped via spec['code_map']; status fallback table applies otherwise."""
    if result.get("error_kind"):
        detail = "timeout" if result["error_kind"] == "timeout" else result.get("error")
        return NETWORK, None, "network:%s" % (detail or "?")
    status = result.get("status")
    if status is None:
        return UNKNOWN, None, "no-response"
    parsed = None
    text = result.get("text")
    if text:
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = None
    code = official_code(spec, parsed) if parsed is not None else None
    if code and code in spec.get("code_map", {}):
        cls = spec["code_map"][code]
    elif 200 <= status < 300:
        cls = OK
    elif 300 <= status < 400:
        return UNKNOWN, code, "redirect-refused http=%d" % status
    else:
        cls = _STATUS_CLASS.get(status, SERVER_5XX if status >= 500 else UNKNOWN)
    msg = None
    if isinstance(parsed, dict):
        for path in spec.get("message_paths", (("error", "message"), ("message",))):
            msg = _walk(parsed, path)
            if msg:
                break
    if cls == FORBIDDEN and _plan_gate_signal(code, msg, text):
        cls = PLAN_GATE
    detail = "code=%s" % code if code else ("http=%d" % status)
    if msg:
        detail += " msg=%s" % msg[:120]
    return cls, code, detail


def key_row(spec, ki, step="key", cls=None, detail="", fix=None, ms=None):
    src = ki["src"] or "-"
    if ki["mismatch"]:
        src += "!"  # sources disagree — see key_srcs
    exp = (expiry_status(spec, ki) if ki.get("value")
           else {"state": "not_recorded"})
    if exp["state"] not in ("ok", "not_recorded"):
        detail = (detail + " " if detail else "") + "keyexp=" + exp["state"]
    row = {
        "provider": spec["name"], "key_env": ki["env"], "key_src": src,
        "key_srcs": ki["srcs"], "key_len": ki["len"], "key_sha8": ki["sha8"],
        "key_mismatch": ki["mismatch"], "step": step, "http": None,
        "cls": cls or (KEY_MISMATCH if ki["mismatch"] else KEY_MISSING),
        "code": None, "detail": detail, "fix": fix or spec.get("fix_url"),
        "ms": ms, "cost": None, "key_expiry": exp["state"],
    }
    if exp.get("expiresAt"):
        row["key_expires_at"] = exp["expiresAt"]
    return row


def run_step(spec, ctx, *, step, url, method="GET", ki=None, auth=None,
             headers=None, body=None, ok=None, fix=None, paid=False):
    """Send one request (or report a missing key / unpaid paid-step) and return
    a result row. `ok`: optional fn(parsed_json, result)->(cls,detail) override
    for the 2xx case (e.g. 'model absent from list' => MODEL_NOT_FOUND)."""
    if ki is None:
        ki = resolve_key(spec.get("key_envs", ()), ctx["secrets"], ctx["scopes"])
    if paid and not ctx["paid"]:
        return dict(key_row(spec, ki, step=step, cls=UNKNOWN,
                            detail="paid step skipped: pass --paid to enable"),
                    skipped="needs --paid")
    if auth and not ki["value"]:
        row = key_row(spec, ki, step=step, cls=KEY_MISSING,
                      detail="no value in Process/User/Machine/.secrets")
        return row
    send_headers = dict(headers or {})
    send_url = url
    if auth and ki["value"]:
        if auth.startswith("query:"):
            sep = "&" if "?" in send_url else "?"
            send_url = "%s%s%s=%s" % (send_url, sep, auth[6:],
                                      urllib.request.quote(ki["value"], safe=""))
        else:
            send_headers.update(AUTH_STYLES[auth](ki["value"]))
    result = http_request(method, send_url, send_headers, body,
                          timeout=ctx["timeout"])
    cls, code, detail = classify(spec, result)
    if cls == OK and ok is not None and result.get("text"):
        try:
            parsed = json.loads(result["text"])
        except ValueError:
            parsed = None
        override = ok(parsed, result)
        if override:
            cls, detail = override
    snippet = None
    if cls == UNKNOWN and result.get("text"):
        snippet = mask(result["text"], ctx.get("secret_values", ()))[:SNIPPET_CHARS]
    detail = mask(detail, ctx.get("secret_values", ()))
    row = key_row(spec, ki, step=step, cls=cls,
                  detail=detail, fix=fix, ms=result.get("ms"))
    row["http"] = result.get("status")
    row["code"] = code
    if snippet:
        row["response_snippet"] = snippet
    return row


def blocked_models():
    """spend-guard stale auto-model blocklist, read from the SSOT yaml.
    Fallback list mirrors configs/agent-api-spend-guard.yaml 2026-09-17."""
    fallback = ["gpt-4", "gpt-4o", "gpt-5", "gpt-5-mini", "gpt-5-chat-latest"]
    try:
        lines = GUARD_YAML.read_text(encoding="utf-8").splitlines()
    except OSError:
        return fallback
    out, in_block = [], False
    for line in lines:
        s = line.strip()
        if s.startswith("block_stale_auto_models_in_agent_mode"):
            in_block = True
            continue
        if in_block:
            m = re.match(r'-\s*"?([A-Za-z0-9._-]+)"?', s)
            if m:
                out.append(m.group(1))
            elif s and not s.startswith("#"):
                break
    return out or fallback


def spend_line(ctx, provider, model, step, http_status, usage=None, cost=None):
    rec = {
        "session": "apikit-" + ctx.get("stamp", "?"),
        # 애덤덤(2026-09-30) 스키마: agent + gateway + purpose 추적 필드.
        "agent": os.environ.get("AWX_AGENT_NAME") or "unknown",
        "purpose": os.environ.get("AWX_JEV_SPEND_PURPOSE") or "probe",
        "provider": provider,
        "gateway": "vercel-ai-gateway" if provider == "jev" else provider,
        "model": model,
        "tier": "paid_quality", "why": "connectivity_min",
        "caller": os.environ.get("AWX_JEV_CALLER") or "scripts/apikit",
        "httpStatus": http_status,
        "estCostClass": "llm_paid" if paid_provider(provider) else "local0",
    }
    if isinstance(usage, dict):
        for k in ("prompt_tokens", "completion_tokens", "promptTokens",
                  "completionTokens", "totalTokens", "inputTokens", "outputTokens"):
            if k in usage:
                rec[k] = usage[k]
    if isinstance(cost, bool):
        pass
    elif isinstance(cost, (int, float)):
        rec["costUsd"] = float(cost)
    elif isinstance(cost, str) and cost.strip():
        try:
            rec["costUsd"] = float(cost.strip())
        except ValueError:
            pass
    print("[AWX][api-spend] " + json.dumps(rec, ensure_ascii=False))
    return rec


def paid_provider(name):
    return name not in ("ollama",)


def print_table(rows):
    cols = ["provider", "key_env", "key_src", "key_len", "key_sha8", "step",
            "http", "cls", "detail", "fix", "ms", "cost"]
    widths = {c: len(c) for c in cols}
    cells = []
    for r in rows:
        line = {}
        for c in cols:
            v = r.get(c)
            if c == "cost" and v is None:
                v = ""
            v = "" if v is None else str(v)
            line[c] = v
            widths[c] = max(widths[c], len(v))
        cells.append(line)
    header = " | ".join(c.ljust(widths[c]) for c in cols)
    sep = "-+-".join("-" * widths[c] for c in cols)
    print(header)
    print(sep)
    for line in cells:
        print(" | ".join(line[c].ljust(widths[c]) for c in cols))


def scrub_result(value, secret_values=(), field=""):
    """Sanitize diagnostics before either persistence or display; preserve machine codes."""
    if isinstance(value, dict):
        return {key: scrub_result(item, secret_values, key) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [scrub_result(item, secret_values, field) for item in value]
    if isinstance(value, str):
        text = value
        for secret in secret_values:
            if secret:
                text = text.replace(str(secret), "<redacted>")
        # The existing token mask applies to prose, not status/code/identifier fields.
        return mask(text) if field in {"detail", "error", "message", "body", "text"} else text
    return value


def save_result(payload, out=None, secret_values=()):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = Path(out) if out else OUT_DIR / ("apikit-%s.json" % payload["stamp"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(scrub_result(payload, secret_values), ensure_ascii=False, indent=2),
                    encoding="utf-8")
    return str(path)


def routing_yaml_text():
    try:
        return ROUTING_YAML.read_text(encoding="utf-8")
    except OSError:
        return ""


def yaml_list(text, anchor):
    """Extract list items under `anchor:` — block style (`- "item"`) or flow
    style (`anchor: ["a", "b"]`). First match wins."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m_flow = re.match(r"\s*%s:\s*\[(.*)\]" % re.escape(anchor), line)
        if m_flow:
            return [s.strip().strip('"').strip("'")
                    for s in m_flow.group(1).split(",") if s.strip()]
        if re.match(r"\s*%s:\s*$" % re.escape(anchor), line):
            base = len(line) - len(line.lstrip())
            out = []
            for rest in lines[i + 1:]:
                indent = len(rest) - len(rest.lstrip())
                s = rest.strip()
                if not s or s.startswith("#"):
                    continue
                if indent <= base:
                    break
                m = re.match(r'-\s*"?([^"#]+?)"?\s*(#.*)?$', s)
                if m:
                    out.append(m.group(1).strip())
            return out
    return []
