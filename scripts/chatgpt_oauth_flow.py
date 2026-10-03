#!/usr/bin/env python3
"""demo-1 durable ChatGPT OAuth harness: 'Sign in with ChatGPT' -> /v1/responses SSE.

Subcommands:
  login        loopback listener + PKCE S256 + dynamic client registration;
               stores tokens in .secrets/chatgpt_oauth_credentials.json.
               --manual-url <callback-url> completes a pending login without a
               listener: paste the browser address-bar
               "http://127.0.0.1:1455/auth/callback?code=...&state=..." after a
               timed-out/unreachable listener run; the pending PKCE state kept
               in .secrets/chatgpt_oauth_pending.json makes it single-use.
  status       masked token/expiry/scope/models summary (no raw secrets)
  refresh      refresh_token grant; rewrites credentials.json
  get-token    prints one raw access_token line on stdout (auto-refresh when the
               token expires within the buffer); sole raw-token channel, meant
               for Java/tooling consumption
  watch-token  background token watchdog: checks credentials.json every
               --interval seconds and silently refresh_token-renews when the
               token expires within --lead seconds (default 600, so Java readers
               always see a fresh file); --once for a single check
  sync-models  GET /v1/models -> data/agent-handoff/chatgpt-oauth/models.json
  test-smoke   POST /v1/responses (store:false, stream:true), consume SSE until
               response.completed; writes smoke_evidence.json

Mechanics verified by %USERPROFILE%/chatgpt-oauth-probe/probe.py +
chatgpt_oauth_probe_report.json (2026-09-30): dynamic_agent_client registration
inside the authorize request, urn:uuid: ext_agent_host_id, PKCE S256,
models[] (not data[]) on the OAuth path, store:false + stream:true + SSE
response.completed terminal detection.

Token values are masked in every output channel except get-token's single
stdout line. Exit codes: 0 ok, 2 no/expired credentials, 3 refresh failed,
4 terminal SSE failure, 5 transport/usage error.
"""
import argparse
import base64
import hashlib
import http.server as httpd
import json
import os
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from datetime import datetime, timedelta, timezone
from pathlib import Path

UA = {"User-Agent": "demo1-chatgpt-oauth/1.0"}
TIMEOUT = 20
LOGIN_WAIT_SECONDS = 300

AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
API_BASE = "https://api.openai.com/v1"
SCOPE = ("openid profile email offline_access resource.invoke "
         "chatgpt.tokens.use.direct")
RESOURCE = "https://api.openai.com/v1"
DYNAMIC_CLIENT_ID = "dynamic_agent_client"
AGENT_NAME_HINT = "demo1-chatgpt-oauth"
CALLBACK_PATH = "/auth/callback"
DEFAULT_PORT = 1455
REFRESH_BUFFER_SECONDS = 300

# Cloudflare bot rules drop plain-urllib UAs on authorize; browser headers
# reached the real OAuth app in the probe (400 param error / 302 login).
BROWSER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/130.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9"}

KST = timezone(timedelta(hours=9))
SECRET_KEYS = ("access_token", "refresh_token", "id_token", "code",
               "client_secret", "code_verifier")


class FlowError(Exception):
    def __init__(self, message, code=5):
        super().__init__(message)
        self.code = code


def default_root():
    env = os.environ.get("CHATGPT_OAUTH_ROOT")
    return Path(env).resolve() if env else Path(__file__).resolve().parents[1]


def secrets_dir(root):
    return Path(root) / ".secrets"


def cred_file(root):
    return secrets_dir(root) / "chatgpt_oauth_credentials.json"


def hostid_file(root):
    return secrets_dir(root) / "chatgpt_oauth_hostid.txt"


def pending_file(root):
    """Pending authorize-request state (state + PKCE verifier) so a callback URL
    pasted via `login --manual-url` can complete after the listener exited.
    Holds a code_verifier: .secrets-only, owner-restricted, single-use."""
    return secrets_dir(root) / "chatgpt_oauth_pending.json"


def handoff_dir(root):
    return Path(root) / "data" / "agent-handoff" / "chatgpt-oauth"


def models_file(root):
    return handoff_dir(root) / "models.json"


def smoke_file(root):
    return handoff_dir(root) / "smoke_evidence.json"


def mask(value):
    if not isinstance(value, str) or not value:
        return "***"
    return (value[:8] + "...***") if len(value) > 14 else "***"


def scrub(obj, keys=SECRET_KEYS):
    if isinstance(obj, dict):
        return {k: (mask(v) if k in keys and isinstance(v, str) else scrub(v, keys))
                for k, v in obj.items()}
    if isinstance(obj, list):
        return [scrub(v, keys) for v in obj]
    return obj


def kst(ts):
    return datetime.fromtimestamp(ts, KST).strftime("%Y-%m-%d %H:%M:%S KST")


def _jwt_sub(token):
    """Unverified JWT payload 'sub' (the id_token identity claim). Returns None
    when the value is not a decodable JWT. Local fingerprint source only."""
    try:
        parts = str(token).split(".")
        if len(parts) < 2:
            return None
        pad = "=" * (-len(parts[1]) % 4)
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + pad))
        sub = payload.get("sub")
        return sub if isinstance(sub, str) and sub else None
    except Exception:
        return None


def account_fingerprint(creds):
    """Non-reversible account fingerprint: sha256(identity) first 12 hex chars.
    Identity precedence: id_token 'sub' -> refresh_token -> access_token ->
    client_id. access_token outranks client_id on purpose: hourly rotation is
    a false-mismatch (safe: forces catalog re-attribution), while a shared
    client_id is a false-match (unsafe: account B could reuse A's catalog).
    Token, email and raw account ids are never emitted anywhere."""
    if not isinstance(creds, dict):
        return None
    source = (_jwt_sub(creds.get("id_token"))
              or creds.get("refresh_token") or creds.get("access_token")
              or creds.get("client_id"))
    if not source:
        return None
    return hashlib.sha256(str(source).encode("utf-8")).hexdigest()[:12]


def pkce_pair():
    verifier = secrets.token_urlsafe(64)[:64]
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def host_id(root):
    path = hostid_file(root)
    if path.exists():
        return path.read_text(encoding="utf-8").strip()
    import uuid
    hid = "urn:uuid:" + str(uuid.uuid4())
    secrets_dir(root).mkdir(parents=True, exist_ok=True)
    path.write_text(hid, encoding="utf-8")
    _restrict(path)
    return hid


def _restrict(path):
    """Best-effort owner-only permissions (0600 on POSIX; Windows applies the
    writable/ACL subset it supports). File also lives under gitignored .secrets/."""
    try:
        import os, stat
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass


def save_credentials(root, doc):
    path = cred_file(root)
    secrets_dir(root).mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    _restrict(path)
    return path


def load_credentials(root):
    path = cred_file(root)
    if not path.exists():
        raise FlowError(f"no credentials file: {path} (run 'login' once)", code=2)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise FlowError(f"unreadable credentials: {type(e).__name__}", code=2)


def save_pending(root, doc):
    path = pending_file(root)
    secrets_dir(root).mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    _restrict(path)
    return path


def load_pending(root):
    path = pending_file(root)
    if not path.exists():
        raise FlowError(
            f"no pending login state: {path} (run 'login' first)", code=5)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise FlowError(f"unreadable pending state: {type(e).__name__}", code=5)


def clear_pending(root):
    try:
        pending_file(root).unlink()
    except OSError:
        pass


def _no_redirect():
    class NRD(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None
    return urllib.request.build_opener(NRD)


def http(method, url, headers=None, form=None, json_body=None,
         timeout=TIMEOUT, follow=True):
    """Real transport: returns (status, headers, parsed-json-or-snippet-dict)."""
    h = dict(UA)
    h.update(headers or {})
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    elif json_body is not None:
        data = json.dumps(json_body).encode()
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, method=method, headers=h, data=data)
    opener = urllib.request if follow else _no_redirect()
    try:
        r = opener.urlopen(req, timeout=timeout)
        # Full read: the /v1/models catalog exceeds 8KB; a truncated body made
        # _maybe_json fall back to _snippet and sync-models wrote count 0.
        return r.status, dict(r.headers), _maybe_json(r.read())
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), _maybe_json(e.read(8192))
    except Exception as e:
        return -1, {}, {"_error": f"{type(e).__name__}: {e}"}


def _maybe_json(body):
    txt = body.decode("utf-8", "replace")
    try:
        return json.loads(txt)
    except Exception:
        return {"_snippet": txt[:400]}


def post_stream(url, headers, json_body, timeout=120):
    """SSE POST: returns (status, headers, chunk-iterator)."""
    h = dict(UA)
    h.update(headers or {})
    h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, method="POST", headers=h,
                                 data=json.dumps(json_body).encode())
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        return r.status, dict(r.headers), iter(lambda: r.read1(4096), b"")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), iter([e.read(4096)])
    except Exception as e:
        return -1, {}, iter([json.dumps(
            {"_error": f"{type(e).__name__}: {e}"}).encode()])


def sse_events(chunks):
    """Parse byte chunks into (event, data) pairs: CRLF + multi-line data +
    comment lines, split UTF-8 safe."""
    buf = b""
    for c in chunks:
        buf += c
        while True:
            idx = buf.find(b"\n\n")
            ln = 2
            if idx < 0:
                idx = buf.find(b"\r\n\r\n")
                ln = 4
            if idx < 0:
                break
            block, buf = buf[:idx], buf[idx + ln:]
            ev, datas = "message", []
            for line in block.replace(b"\r\n", b"\n").split(b"\n"):
                if line.startswith(b":"):
                    continue
                if line.startswith(b"event:"):
                    ev = line[6:].strip().decode()
                elif line.startswith(b"data:"):
                    datas.append(line[5:].lstrip(b" ").decode("utf-8", "replace"))
            if datas:
                yield ev, "\n".join(datas)


def build_authorize_url(root, port, client_id=None, state=None, verifier=None):
    verifier = verifier or pkce_pair()[0]
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = state or secrets.token_urlsafe(16)
    redirect_uri = f"http://127.0.0.1:{port}{CALLBACK_PATH}"
    params = {"response_type": "code",
              "client_id": client_id or DYNAMIC_CLIENT_ID,
              "redirect_uri": redirect_uri,
              "scope": SCOPE,
              "state": state,
              "nonce": secrets.token_urlsafe(16),
              "code_challenge": challenge,
              "code_challenge_method": "S256",
              "resource": RESOURCE}
    if not client_id:
        params["agent_name_hint"] = AGENT_NAME_HINT
        params["ext_agent_host_id"] = host_id(root)
    return {"url": AUTHORIZE_URL + "?" + urllib.parse.urlencode(params),
            "verifier": verifier, "state": state, "redirect_uri": redirect_uri}


def _exchange(http_fn, form):
    # RFC 8707: the token request repeats the authorize request's resource
    # indicator (official devkit oauth.ts tokenRequest). Omitting it -> 400
    # invalid_grant (observed live 2026-09-30).
    form = dict(form)
    form.setdefault("resource", RESOURCE)
    st, _, body = http_fn("POST", TOKEN_ENDPOINT, form=form)
    if st != 200 or not isinstance(body, dict) or not body.get("access_token"):
        raise FlowError(f"token endpoint HTTP {st}: {scrub(body)}", code=3)
    return body


def _creds_from_token(tok, client_id, now):
    expires_in = tok.get("expires_in", 3600)
    return {"schemaVersion": "awx.chatgpt-oauth.v1",
            "client_id": client_id,
            "access_token": tok["access_token"],
            "refresh_token": tok.get("refresh_token"),
            "id_token": tok.get("id_token"),
            "token_type": tok.get("token_type", "Bearer"),
            "scope": tok.get("scope", ""),
            "obtained_at": now,
            "expires_at": now + int(expires_in)}


def _refresh(root, creds, http_fn, now):
    rt = creds.get("refresh_token")
    if not rt:
        raise FlowError("no refresh_token in credentials (re-run 'login')", code=3)
    body = _exchange(http_fn, {"grant_type": "refresh_token",
                               "refresh_token": rt,
                               "client_id": creds.get("client_id") or DYNAMIC_CLIENT_ID})
    merged = _creds_from_token(body, creds.get("client_id") or DYNAMIC_CLIENT_ID, now)
    if not merged.get("refresh_token"):
        merged["refresh_token"] = rt  # non-rotating servers omit it
    save_credentials(root, merged)
    return merged


def valid_token(root, http_fn, now, buffer_seconds=REFRESH_BUFFER_SECONDS):
    creds = load_credentials(root)
    remaining = creds.get("expires_at", 0) - now
    if remaining <= buffer_seconds:
        creds = _refresh(root, creds, http_fn, now)
    return creds


# ---------- subcommands (all injectable for offline tests) ----------

def _login_report(out, creds, client_id, path, prefix="login OK"):
    out(f"{prefix}: credentials stored")
    out(f"  file: {path}")
    out(f"  client_id: {client_id}")
    out(f"  access_token: {mask(creds['access_token'])}")
    out(f"  refresh_token: {'present' if creds.get('refresh_token') else 'absent'}")
    out(f"  scope: {creds.get('scope')}")
    out(f"  expires_at: {kst(creds['expires_at'])}")
    return 0


def _manual_exchange(args, http_fn, out, now):
    """--manual-url safety net: the user pastes the browser address-bar callback
    URL (code+state) after the loopback listener already exited. The exchange
    uses the pending authorize request's PKCE verifier; the state check binds
    the paste to THIS run's authorize URL, so a foreign callback is rejected."""
    root = Path(args.root)
    pending = load_pending(root)
    url = str(args.manual_url).strip().strip('"').strip("'")
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    if not q and "?" in url:
        q = urllib.parse.parse_qs(url.split("?", 1)[1])
    for k in ("code", "state", "error", "client_id"):
        q.setdefault(k, [None])
    if q["error"][0]:
        raise FlowError(f"authorize error={q['error'][0]}", code=5)
    code = q["code"][0]
    if not code:
        raise FlowError("callback URL has no code= parameter", code=5)
    if q["state"][0] != pending.get("state"):
        raise FlowError(
            "state mismatch vs pending login "
            "(re-run 'login' and paste THIS run's callback URL)", code=5)
    client_id = (q["client_id"][0] or pending.get("client_id")
                 or getattr(args, "client_id", None) or DYNAMIC_CLIENT_ID)
    tok = _exchange(http_fn, {"grant_type": "authorization_code",
                              "code": code,
                              "redirect_uri": pending.get("redirect_uri"),
                              "client_id": client_id,
                              "code_verifier": pending.get("code_verifier")})
    creds = _creds_from_token(tok, client_id, now)
    path = save_credentials(root, creds)
    clear_pending(root)  # single-use: the code is consumed/dead after success
    return _login_report(out, creds, client_id, path,
                         prefix="manual-url exchange OK")


def cmd_login(args, http_fn=http, browser_fn=webbrowser.open, out=print, now=None):
    now = time.time() if now is None else now
    root = Path(args.root)
    if getattr(args, "manual_url", None):
        return _manual_exchange(args, http_fn, out, now)
    got = {}

    class H(httpd.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            for k in ("code", "state", "error", "client_id"):
                got[k] = q.get(k, [None])[0]
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"You can close this tab.")

        def log_message(self, *a):
            pass

    srv = httpd.HTTPServer(("127.0.0.1", args.port), H)
    srv.timeout = 0.5
    port = srv.server_address[1]
    try:
        authz = build_authorize_url(root, port, client_id=args.client_id)
        # Persist before the browser opens: if this listener times out or the
        # port is unreachable, `login --manual-url <address-bar URL>` can still
        # complete the same authorize request.
        save_pending(root, {"schemaVersion": "awx.chatgpt-oauth-pending.v1",
                            "state": authz["state"],
                            "code_verifier": authz["verifier"],
                            "redirect_uri": authz["redirect_uri"],
                            "client_id": args.client_id or DYNAMIC_CLIENT_ID,
                            "issued_at": now})
        out(f"loopback listener: http://127.0.0.1:{port}{CALLBACK_PATH}")
        if not args.no_browser:
            browser_fn(authz["url"])
            out("browser opened for ChatGPT login (one-time interactive step)")
        else:
            out(f"open this URL manually:\n{authz['url']}")
        deadline = time.time() + args.wait
        while "code" not in got and "error" not in got and time.time() < deadline:
            srv.handle_request()
    finally:
        srv.server_close()
    if got.get("error"):
        clear_pending(root)  # denied/error responses kill the pending request
        raise FlowError(f"authorize error={got.get('error')}", code=5)
    if not got.get("code"):
        # Keep pending: the browser may still finish after the listener closes;
        # --manual-url is the documented recovery path.
        raise FlowError(f"no code within {args.wait}s "
                        f"(paste the browser address-bar URL via "
                        f"'login --manual-url' to finish this login)", code=5)
    if got.get("state") != authz["state"]:
        raise FlowError("state mismatch on callback", code=5)
    client_id = got.get("client_id") or args.client_id or DYNAMIC_CLIENT_ID
    tok = _exchange(http_fn, {"grant_type": "authorization_code",
                              "code": got["code"],
                              "redirect_uri": authz["redirect_uri"],
                              "client_id": client_id,
                              "code_verifier": authz["verifier"]})
    creds = _creds_from_token(tok, client_id, now)
    path = save_credentials(root, creds)
    clear_pending(root)
    return _login_report(out, creds, client_id, path)


def cmd_status(args, out=print, now=None):
    now = time.time() if now is None else now
    root = Path(args.root)
    path = cred_file(root)
    if not path.exists():
        out(f"credentials: absent ({path})")
        out("status: not_configured")
        return 0
    creds = load_credentials(root)
    remaining = int(creds.get("expires_at", 0) - now)
    fingerprint = account_fingerprint(creds)
    models = models_file(root)
    model_count = None
    catalog_fp = catalog_at = None
    if models.exists():
        try:
            mdoc = json.loads(models.read_text(encoding="utf-8"))
            model_count = len(mdoc.get("models", []))
            catalog_fp = mdoc.get("catalog_account_fingerprint")
            catalog_at = mdoc.get("catalog_fetched_at") or mdoc.get("syncedAtUtc")
        except Exception:
            model_count = None
    match = ("unknown" if not (fingerprint and catalog_fp)
             else "yes" if fingerprint == catalog_fp else "no")
    out(f"credentials: present ({path})")
    out(f"  client_id: {creds.get('client_id')}")
    out(f"  access_token: {mask(creds.get('access_token'))}")
    out(f"  refresh_token: {'present' if creds.get('refresh_token') else 'absent'}")
    out(f"  expires_at: {kst(creds.get('expires_at', 0))} (remaining {remaining}s)")
    out(f"  scope: {creds.get('scope')}")
    out(f"  account_fingerprint: {fingerprint or 'unknown'}")
    out(f"  models_cached: {model_count if model_count is not None else 'not_synced'}")
    out(f"  catalog_fetched_at: {catalog_at or 'absent'}")
    out(f"  catalog_account_fingerprint: {catalog_fp or 'absent'}")
    out(f"  catalog_account_match: {match}")
    out(f"status: {'valid' if remaining > 0 else 'expired'}")
    return 0


def cmd_refresh(args, http_fn=http, out=print, now=None):
    now = time.time() if now is None else now
    root = Path(args.root)
    creds = _refresh(root, load_credentials(root), http_fn, now)
    out("refresh OK")
    out(f"  access_token: {mask(creds['access_token'])}")
    out(f"  expires_at: {kst(creds['expires_at'])}")
    return 0


def cmd_get_token(args, http_fn=http, out=print, now=None):
    now = time.time() if now is None else now
    creds = valid_token(Path(args.root), http_fn, now, args.buffer)
    out(creds["access_token"])  # sole raw-token channel (contract for tooling)
    return 0


def cmd_watch_token(args, http_fn=http, out=print, now=None,
                    sleep_fn=time.sleep):
    """Token watchdog: silent pre-expiry renewal so Java's direct file read
    (`expires_at - now > refresh_buffer`) always sees a fresh access_token.
    lead = max(--lead, --buffer): default 600s renews ~10 min before expiry."""
    root = Path(args.root)
    lead = max(int(getattr(args, "lead", 0) or 0), int(args.buffer))
    interval = max(1, int(getattr(args, "interval", 60) or 60))
    max_runs = int(getattr(args, "max_runs", 0) or 0)
    runs, failures = 0, 0
    out(f"watch: lead={lead}s interval={interval}s "
        f"max_runs={max_runs or 'unbounded'} (Ctrl+C to stop)")
    while True:
        runs += 1
        t = time.time() if now is None else now
        try:
            creds = load_credentials(root)
            remaining = int(creds.get("expires_at", 0) - t)
            if remaining <= lead:
                creds = _refresh(root, creds, http_fn, t)
                out(f"watch: refreshed (was {remaining}s) "
                    f"-> expires_at {kst(creds['expires_at'])} "
                    f"access_token={mask(creds['access_token'])}")
            else:
                out(f"watch: ok remaining={remaining}s lead={lead}s")
            failures = 0
        except FlowError as e:
            if e.code == 2:
                raise  # no credentials at all: nothing to watch
            failures += 1
            out(f"watch: refresh failed ({failures}/3): {e}")
            if failures >= 3:
                raise FlowError(
                    "watch: 3 consecutive refresh failures", code=3)
        if getattr(args, "once", False) or (max_runs and runs >= max_runs):
            return 0
        try:
            sleep_fn(interval)
        except KeyboardInterrupt:
            out("watch: stopped")
            return 0


def cmd_sync_models(args, http_fn=http, out=print, now=None):
    now = time.time() if now is None else now
    root = Path(args.root)
    creds = valid_token(root, http_fn, now, args.buffer)
    st, _, body = http_fn("GET", f"{API_BASE}/models",
                          headers={"Authorization": f"Bearer {creds['access_token']}"})
    if st != 200 or not isinstance(body, dict):
        raise FlowError(f"GET /v1/models HTTP {st}: {scrub(body)}", code=5)
    # OAuth path returns models[] (not data[] like the API-key shape).
    rows = body.get("models", body.get("data", []))
    slugs = [m.get("slug") or m.get("id") for m in rows if isinstance(m, dict)]
    fetched = datetime.now(timezone.utc).isoformat()
    doc = {"schemaVersion": "awx.chatgpt-oauth-models.v1",
           "status": "synced",
           "syncedAtUtc": fetched,
           "catalog_fetched_at": fetched,
           "catalog_account_fingerprint": account_fingerprint(creds),
           "count": len(slugs),
           "models": slugs}
    mf = models_file(root)
    handoff_dir(root).mkdir(parents=True, exist_ok=True)
    mf.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    out(f"sync-models OK: {len(slugs)} models -> {mf}")
    out(f"  first: {slugs[:8]}")
    return 0


def cmd_test_smoke(args, http_fn=http, stream_fn=post_stream, out=print, now=None):
    now = time.time() if now is None else now
    root = Path(args.root)
    creds = valid_token(root, http_fn, now, args.buffer)
    payload = {"model": args.model,
               "input": [{"role": "user",
                          "content": [{"type": "input_text", "text": args.prompt}]}],
               "store": False, "stream": True}
    st, _, chunks = stream_fn(f"{API_BASE}/responses",
                              {"Authorization": f"Bearer {creds['access_token']}",
                               "Accept": "text/event-stream"},
                              payload, timeout=180)
    evs = list(sse_events(chunks))
    types = [e for e, _ in evs]
    text_parts = []
    for e, d in evs:
        if e != "response.output_text.delta":
            continue
        try:
            text_parts.append(json.loads(d).get("delta", ""))
        except Exception:
            pass
    text = "".join(text_parts)
    usage, terminal = {}, types[-1] if types else None
    for e, d in evs:
        if e in ("response.completed", "response.incomplete", "response.failed"):
            try:
                usage = (json.loads(d).get("response") or {}).get("usage") or {}
            except Exception:
                usage = {}
    completed = "response.completed" in types
    doc = {"schemaVersion": "awx.chatgpt-oauth-smoke.v1",
           "status": "pass" if completed else "fail",
           "ranAtUtc": datetime.now(timezone.utc).isoformat(),
           "model": args.model, "httpStatus": st,
           "eventCount": len(evs), "eventTypesSeen": sorted(set(types)),
           "completed": completed, "terminalType": terminal,
           "usage": usage, "outputTextChars": len(text),
           "outputTextPreview": text[:80]}
    sf = smoke_file(root)
    handoff_dir(root).mkdir(parents=True, exist_ok=True)
    sf.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    out(f"test-smoke {'PASS' if completed else 'FAIL'}: HTTP {st} "
        f"events={len(evs)} completed={completed} terminal={terminal}")
    out(f"  usage={usage} text_chars={len(text)} -> {sf}")
    return 0 if completed else 4


def build_parser():
    p = argparse.ArgumentParser(prog="chatgpt_oauth_flow.py")
    p.add_argument("--root", default=str(default_root()),
                   help="project root (default: repo root; env CHATGPT_OAUTH_ROOT)")
    p.add_argument("--buffer", type=int,
                   default=int(os.environ.get(
                       "CHATGPT_OAUTH_REFRESH_BUFFER", REFRESH_BUFFER_SECONDS)),
                   help="refresh when token expires within N seconds")
    sub = p.add_subparsers(dest="cmd", required=True)
    lp = sub.add_parser("login")
    lp.add_argument("--port", type=int,
                    default=int(os.environ.get(
                        "CHATGPT_OAUTH_PORT", DEFAULT_PORT)))
    lp.add_argument("--wait", type=int, default=LOGIN_WAIT_SECONDS)
    lp.add_argument("--client-id", default=None,
                    help="re-use an issued client_id (skips dynamic registration)")
    lp.add_argument("--no-browser", action="store_true")
    lp.add_argument("--manual-url", default=None,
                    help="skip the listener: paste the browser address-bar "
                         "callback URL (code+state) to finish a pending login")
    lp.set_defaults(fn=cmd_login)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    sub.add_parser("refresh").set_defaults(fn=cmd_refresh)
    sub.add_parser("get-token").set_defaults(fn=cmd_get_token)
    wp = sub.add_parser("watch-token")
    wp.add_argument("--interval", type=int, default=60,
                    help="seconds between credential checks (default 60)")
    wp.add_argument("--lead", type=int, default=600,
                    help="refresh when remaining <= N seconds (default 600; "
                         "effective value is max(--lead, --buffer))")
    wp.add_argument("--max-runs", type=int, default=0,
                    help="stop after N cycles (0 = run until interrupted)")
    wp.add_argument("--once", action="store_true",
                    help="single check cycle, then exit")
    wp.set_defaults(fn=cmd_watch_token)
    sub.add_parser("sync-models").set_defaults(fn=cmd_sync_models)
    sp = sub.add_parser("test-smoke")
    sp.add_argument("--model", default="gpt-5")
    sp.add_argument("--prompt", default="Reply with the word OK only.")
    sp.set_defaults(fn=cmd_test_smoke)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.fn(args)
    except FlowError as e:
        print(f"ERROR exit={e.code}: {e}", file=sys.stderr)
        return e.code


if __name__ == "__main__":
    sys.exit(main())
