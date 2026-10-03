"""chatgpt_oauth_flow offline contract tests; synthetic fixtures only, no real
network (loopback listener excepted), no secrets."""
import argparse
import contextlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
import urllib.request
from pathlib import Path

SCRIPT = Path(__file__).with_name("chatgpt_oauth_flow.py")
SPEC = importlib.util.spec_from_file_location("chatgpt_oauth_flow", SCRIPT) if SCRIPT.exists() else None
F = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    SPEC.loader.exec_module(F)

REPO_ROOT = Path(__file__).resolve().parents[1]
YML = REPO_ROOT / "main" / "resources" / "application-meta-display.yml"

FAKE_ACCESS = "fake-access-token-9f8e7d6c5b4a"
FAKE_ACCESS_2 = "fake-access-token-rotated-1122334455"
FAKE_REFRESH = "fake-refresh-token-aabbccddeeff"
FAKE_ID = "fake-id-token-zzz"
FAKE_CODE = "fake-auth-code-12345"
ISSUED_CLIENT = "issued-client-abc123"
SECRET_STRINGS = [FAKE_ACCESS, FAKE_ACCESS_2, FAKE_REFRESH, FAKE_ID, FAKE_CODE]


def ns(**kw):
    return argparse.Namespace(**kw)


def fake_creds(now, expires_in=3600, refresh=FAKE_REFRESH):
    return {"schemaVersion": "awx.chatgpt-oauth.v1", "client_id": ISSUED_CLIENT,
            "access_token": FAKE_ACCESS, "refresh_token": refresh,
            "id_token": FAKE_ID, "token_type": "Bearer",
            "scope": "openid profile email", "obtained_at": now - 10,
            "expires_at": now + expires_in}


def make_root(tmp):
    root = Path(tmp)
    (root / ".secrets").mkdir(parents=True, exist_ok=True)
    (root / "data" / "agent-handoff" / "chatgpt-oauth").mkdir(parents=True, exist_ok=True)
    return root


def write_creds(root, creds):
    (root / ".secrets").mkdir(parents=True, exist_ok=True)
    (root / ".secrets" / "chatgpt_oauth_credentials.json").write_text(
        json.dumps(creds), encoding="utf-8")


FAKE_VERIFIER = "v" * 64
FAKE_STATE = "state-fixture-abc"


def fake_jwt(sub):
    """Unsigned fake JWT carrying only a 'sub' claim (offline fixtures)."""
    import base64
    payload = base64.urlsafe_b64encode(
        json.dumps({"sub": sub}).encode()).rstrip(b"=").decode()
    return f"fakeHeader.{payload}.fakeSig"


def write_pending(root, state=FAKE_STATE, verifier=FAKE_VERIFIER,
                  redirect="http://127.0.0.1:1455/auth/callback",
                  client=ISSUED_CLIENT, issued_at=0):
    doc = {"schemaVersion": "awx.chatgpt-oauth-pending.v1", "state": state,
           "code_verifier": verifier, "redirect_uri": redirect,
           "client_id": client, "issued_at": issued_at}
    (root / ".secrets").mkdir(parents=True, exist_ok=True)
    (root / ".secrets" / "chatgpt_oauth_pending.json").write_text(
        json.dumps(doc), encoding="utf-8")
    return doc


def token_http(tok=None, status=200):
    """http_fn fake: POST token -> tok; GET models -> models list."""
    tok = tok or {"access_token": FAKE_ACCESS, "refresh_token": FAKE_REFRESH,
                  "expires_in": 3600, "token_type": "Bearer",
                  "scope": "openid profile email"}
    calls = []

    def fn(method, url, headers=None, form=None, json_body=None, timeout=20):
        calls.append({"method": method, "url": url, "form": form})
        if "oauth/token" in url:
            return status, {}, dict(tok)
        if url.endswith("/models"):
            return 200, {}, {"models": [{"slug": "gpt-5"}, {"slug": "gpt-5-mini"}]}
        return 500, {}, {"error": "unexpected"}
    fn.calls = calls
    return fn


def smoke_stream(events):
    chunks = []
    for ev, payload in events:
        chunks.append(f"event: {ev}\ndata: {json.dumps(payload)}\n\n".encode())
    return lambda url, headers, json_body, timeout=180: (200, {}, iter(chunks))


class FlowContractTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(F, "chatgpt_oauth_flow helper is not implemented")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = make_root(self.tmp.name)
        self.now = 1759200000.0

    def capture(self, fn, *a, **k):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = fn(*a, out=buf.write, **k)
        return rc, buf.getvalue()

    def test_pkce_s256(self):
        verifier, challenge = F.pkce_pair()
        self.assertEqual(len(verifier), 64)
        self.assertEqual(len(challenge), 43)
        self.assertNotIn("=", challenge)
        import base64, hashlib
        expect = base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        self.assertEqual(challenge, expect)

    def test_host_id_urn_and_stable(self):
        hid = F.host_id(self.root)
        self.assertTrue(hid.startswith("urn:uuid:"), hid)
        self.assertEqual(F.host_id(self.root), hid)
        self.assertTrue((self.root / ".secrets" / "chatgpt_oauth_hostid.txt").exists())

    def test_authorize_url_dynamic_registration(self):
        authz = F.build_authorize_url(self.root, 1455)
        q = urllib.parse.parse_qs(
            urllib.parse.urlparse(authz["url"]).query)
        self.assertEqual(q["client_id"], [F.DYNAMIC_CLIENT_ID])
        self.assertEqual(q["code_challenge_method"], ["S256"])
        self.assertEqual(q["redirect_uri"], ["http://127.0.0.1:1455/auth/callback"])
        self.assertTrue(q["ext_agent_host_id"][0].startswith("urn:uuid:"))
        self.assertIn("agent_name_hint", q)
        self.assertIn("offline_access", q["scope"][0])
        self.assertIn("chatgpt.tokens.use.direct", q["scope"][0])
        self.assertEqual(q["resource"], [F.RESOURCE])
        self.assertNotIn(FAKE_CODE, authz["url"])

    def test_login_offline(self):
        seen = {}

        def browser(url):
            seen["url"] = url
            q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
            state = q["state"][0]
            redirect = q["redirect_uri"][0]

            def hit():
                time.sleep(0.2)
                cb = redirect + f"?code={FAKE_CODE}&state={state}&client_id={ISSUED_CLIENT}"
                urllib.request.urlopen(cb, timeout=5).read()
            threading.Thread(target=hit, daemon=True).start()

        http_fn = token_http()
        args = ns(root=str(self.root), port=0, wait=15, client_id=None,
                  no_browser=False)
        rc, out = self.capture(F.cmd_login, args,
                               http_fn=http_fn, browser_fn=browser,
                               now=self.now)
        self.assertEqual(rc, 0, out)
        self.assertTrue("dynamic_agent_client" in seen["url"])
        # RFC 8707 resource indicator must be repeated at token exchange.
        self.assertEqual(http_fn.calls[0]["form"]["resource"], F.RESOURCE)
        self.assertEqual(http_fn.calls[0]["form"]["code_verifier"].__len__(), 64)
        creds = json.loads(
            (self.root / ".secrets" / "chatgpt_oauth_credentials.json")
            .read_text())
        self.assertEqual(creds["access_token"], FAKE_ACCESS)
        self.assertEqual(creds["client_id"], ISSUED_CLIENT)
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)

    def test_status_masks_and_reports(self):
        write_creds(self.root, fake_creds(self.now))
        rc, out = self.capture(F.cmd_status, ns(root=str(self.root)), now=self.now)
        self.assertEqual(rc, 0)
        self.assertIn("expires_at", out)
        self.assertIn("remaining", out)
        self.assertIn("openid profile email", out)
        self.assertIn("status: valid", out)
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)

    def test_get_token_fresh(self):
        write_creds(self.root, fake_creds(self.now))
        rc, out = self.capture(F.cmd_get_token,
                               ns(root=str(self.root), buffer=300),
                               http_fn=token_http(), now=self.now)
        self.assertEqual(rc, 0)
        self.assertEqual(out.strip(), FAKE_ACCESS)

    def test_get_token_auto_refresh(self):
        write_creds(self.root, fake_creds(self.now, expires_in=100))
        new = {"access_token": FAKE_ACCESS_2, "expires_in": 3600}
        rc, out = self.capture(F.cmd_get_token,
                               ns(root=str(self.root), buffer=300),
                               http_fn=token_http(tok=new), now=self.now)
        self.assertEqual(rc, 0)
        self.assertEqual(out.strip(), FAKE_ACCESS_2)
        creds = json.loads(
            (self.root / ".secrets" / "chatgpt_oauth_credentials.json")
            .read_text())
        self.assertEqual(creds["access_token"], FAKE_ACCESS_2)
        self.assertEqual(creds["refresh_token"], FAKE_REFRESH)  # non-rotating kept

    def test_refresh_requires_refresh_token(self):
        write_creds(self.root, fake_creds(self.now, refresh=None))
        with self.assertRaises(F.FlowError) as cm:
            F.cmd_refresh(ns(root=str(self.root)), http_fn=token_http(),
                          out=lambda *a: None, now=self.now)
        self.assertEqual(cm.exception.code, 3)

    def test_login_offline_clears_pending(self):
        """A listener-mode login that completes consumes its pending state."""
        def browser(url):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
            state, redirect = q["state"][0], q["redirect_uri"][0]

            def hit():
                time.sleep(0.2)
                cb = redirect + f"?code={FAKE_CODE}&state={state}"
                urllib.request.urlopen(cb, timeout=5).read()
            threading.Thread(target=hit, daemon=True).start()

        args = ns(root=str(self.root), port=0, wait=15, client_id=None,
                  no_browser=False, manual_url=None)
        rc, _ = self.capture(F.cmd_login, args, http_fn=token_http(),
                             browser_fn=browser, now=self.now)
        self.assertEqual(rc, 0)
        self.assertFalse(
            (self.root / ".secrets" / "chatgpt_oauth_pending.json").exists())

    def test_login_timeout_keeps_pending_for_manual_url(self):
        """Safety-net contract: a timed-out listener leaves pending state so
        --manual-url can still finish the same authorize request."""
        args = ns(root=str(self.root), port=0, wait=1, client_id=None,
                  no_browser=True, manual_url=None)
        with self.assertRaises(F.FlowError) as cm:
            F.cmd_login(args, http_fn=token_http(),
                        browser_fn=lambda u: None,
                        out=lambda *a: None, now=self.now)
        self.assertEqual(cm.exception.code, 5)
        pending = json.loads(
            (self.root / ".secrets" / "chatgpt_oauth_pending.json").read_text())
        self.assertEqual(pending["code_verifier"].__len__(), 64)
        self.assertTrue(pending["state"])

    def test_login_manual_url_offline(self):
        write_pending(self.root)
        http_fn = token_http()
        url = ("http://127.0.0.1:1455/auth/callback"
               f"?code={FAKE_CODE}&state={FAKE_STATE}&client_id={ISSUED_CLIENT}")
        args = ns(root=str(self.root), port=0, wait=15, client_id=None,
                  no_browser=True, manual_url=url)
        rc, out = self.capture(F.cmd_login, args, http_fn=http_fn,
                               browser_fn=lambda u: None, now=self.now)
        self.assertEqual(rc, 0, out)
        self.assertFalse(
            (self.root / ".secrets" / "chatgpt_oauth_pending.json").exists())
        creds = json.loads(
            (self.root / ".secrets" / "chatgpt_oauth_credentials.json")
            .read_text())
        self.assertEqual(creds["access_token"], FAKE_ACCESS)
        self.assertEqual(creds["client_id"], ISSUED_CLIENT)
        self.assertEqual(http_fn.calls[0]["form"]["code_verifier"], FAKE_VERIFIER)
        self.assertEqual(http_fn.calls[0]["form"]["code"], FAKE_CODE)
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)

    def test_login_manual_url_state_mismatch(self):
        write_pending(self.root, state="state-current-run")
        url = ("http://127.0.0.1:1455/auth/callback"
               f"?code={FAKE_CODE}&state=state-foreign-run")
        args = ns(root=str(self.root), port=0, wait=15, client_id=None,
                  no_browser=True, manual_url=url)
        with self.assertRaises(F.FlowError) as cm:
            F.cmd_login(args, http_fn=token_http(),
                        browser_fn=lambda u: None,
                        out=lambda *a: None, now=self.now)
        self.assertEqual(cm.exception.code, 5)
        self.assertFalse(
            (self.root / ".secrets" / "chatgpt_oauth_credentials.json").exists())
        # Rejected paste must not consume the real pending state.
        self.assertTrue(
            (self.root / ".secrets" / "chatgpt_oauth_pending.json").exists())

    def test_login_manual_url_requires_pending(self):
        url = ("http://127.0.0.1:1455/auth/callback"
               f"?code={FAKE_CODE}&state={FAKE_STATE}")
        args = ns(root=str(self.root), port=0, wait=15, client_id=None,
                  no_browser=True, manual_url=url)
        with self.assertRaises(F.FlowError) as cm:
            F.cmd_login(args, http_fn=token_http(),
                        browser_fn=lambda u: None,
                        out=lambda *a: None, now=self.now)
        self.assertEqual(cm.exception.code, 5)

    def test_login_manual_url_error_param(self):
        write_pending(self.root)
        url = ("http://127.0.0.1:1455/auth/callback"
               f"?error=access_denied&state={FAKE_STATE}")
        args = ns(root=str(self.root), port=0, wait=15, client_id=None,
                  no_browser=True, manual_url=url)
        with self.assertRaises(F.FlowError) as cm:
            F.cmd_login(args, http_fn=token_http(),
                        browser_fn=lambda u: None,
                        out=lambda *a: None, now=self.now)
        self.assertEqual(cm.exception.code, 5)

    def test_watch_token_once_refresh(self):
        write_creds(self.root, fake_creds(self.now, expires_in=100))
        new = {"access_token": FAKE_ACCESS_2, "expires_in": 3600}
        args = ns(root=str(self.root), buffer=300, interval=60, lead=600,
                  max_runs=0, once=True)
        rc, out = self.capture(F.cmd_watch_token, args,
                               http_fn=token_http(tok=new), now=self.now)
        self.assertEqual(rc, 0, out)
        self.assertIn("refreshed", out)
        creds = json.loads(
            (self.root / ".secrets" / "chatgpt_oauth_credentials.json")
            .read_text())
        self.assertEqual(creds["access_token"], FAKE_ACCESS_2)
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)

    def test_watch_token_once_fresh_noop(self):
        write_creds(self.root, fake_creds(self.now, expires_in=3600))
        http_fn = token_http()
        args = ns(root=str(self.root), buffer=300, interval=60, lead=600,
                  max_runs=0, once=True)
        rc, out = self.capture(F.cmd_watch_token, args, http_fn=http_fn,
                               now=self.now)
        self.assertEqual(rc, 0, out)
        self.assertIn("ok remaining", out)
        self.assertEqual(http_fn.calls, [])

    def test_watch_token_requires_credentials(self):
        args = ns(root=str(self.root), buffer=300, interval=60, lead=600,
                  max_runs=0, once=True)
        with self.assertRaises(F.FlowError) as cm:
            F.cmd_watch_token(args, http_fn=token_http(),
                              out=lambda *a: None, now=self.now)
        self.assertEqual(cm.exception.code, 2)

    def test_watch_token_max_runs_with_failure_retry(self):
        """A transient refresh failure retries next cycle instead of dying."""
        write_creds(self.root, fake_creds(self.now, expires_in=100))
        attempts = {"n": 0}

        def flaky(method, url, headers=None, form=None, json_body=None,
                  timeout=20):
            attempts["n"] += 1
            if attempts["n"] == 1:
                return 500, {}, {"error": "transient"}
            return 200, {}, {"access_token": FAKE_ACCESS_2, "expires_in": 3600}

        args = ns(root=str(self.root), buffer=300, interval=60, lead=600,
                  max_runs=2, once=False)
        rc, out = self.capture(F.cmd_watch_token, args, http_fn=flaky,
                               now=self.now, sleep_fn=lambda s: None)
        self.assertEqual(rc, 0, out)
        self.assertEqual(attempts["n"], 2)
        self.assertIn("refresh failed (1/3)", out)

    def test_http_parses_body_larger_than_8k(self):
        """Regression: real http() must not truncate JSON bodies at 8192 bytes
        (live /v1/models catalog exceeded the cap -> _snippet -> count 0)."""
        import http.server as httpd
        big = json.dumps({"models": [{"slug": f"m-{i}", "pad": "x" * 200}
                                     for i in range(60)]}).encode()
        self.assertGreater(len(big), 8192)

        class H(httpd.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(big)

            def log_message(self, *a):
                pass

        srv = httpd.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.handle_request, daemon=True).start()
        try:
            st, _, body = F.http(
                "GET", f"http://127.0.0.1:{srv.server_address[1]}/models")
        finally:
            srv.server_close()
        self.assertEqual(st, 200)
        self.assertNotIn("_snippet", body)
        self.assertEqual(len(body["models"]), 60)

    def test_sync_models_offline(self):
        write_creds(self.root, fake_creds(self.now))
        rc, out = self.capture(F.cmd_sync_models,
                               ns(root=str(self.root), buffer=300),
                               http_fn=token_http(), now=self.now)
        self.assertEqual(rc, 0, out)
        doc = json.loads(
            (self.root / "data" / "agent-handoff" / "chatgpt-oauth" / "models.json")
            .read_text())
        self.assertEqual(doc["status"], "synced")
        self.assertEqual(doc["count"], 2)
        self.assertEqual(doc["models"], ["gpt-5", "gpt-5-mini"])
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)
            self.assertNotIn(s, json.dumps(doc))

    def test_smoke_offline_pass(self):
        write_creds(self.root, fake_creds(self.now))
        stream = smoke_stream([
            ("response.output_text.delta", {"type": "response.output_text.delta", "delta": "OK"}),
            ("response.completed", {"type": "response.completed",
                "response": {"usage": {"input_tokens": 7, "output_tokens": 1}}})])
        rc, out = self.capture(
            F.cmd_test_smoke,
            ns(root=str(self.root), buffer=300, model="gpt-5", prompt="x"),
            http_fn=token_http(), stream_fn=stream, now=self.now)
        self.assertEqual(rc, 0, out)
        doc = json.loads(
            (self.root / "data" / "agent-handoff" / "chatgpt-oauth" /
             "smoke_evidence.json").read_text())
        self.assertEqual(doc["status"], "pass")
        self.assertTrue(doc["completed"])
        self.assertEqual(doc["usage"]["input_tokens"], 7)
        self.assertEqual(doc["outputTextChars"], 2)
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)
            self.assertNotIn(s, json.dumps(doc))

    def test_smoke_offline_terminal_fail(self):
        write_creds(self.root, fake_creds(self.now))
        stream = smoke_stream([
            ("response.failed", {"type": "response.failed",
                "response": {"error": {"code": "subscription_sharing_usage_limit_exceeded"}}})])
        rc, _ = self.capture(
            F.cmd_test_smoke,
            ns(root=str(self.root), buffer=300, model="gpt-5", prompt="x"),
            http_fn=token_http(), stream_fn=stream, now=self.now)
        self.assertEqual(rc, 4)

    def test_account_fingerprint_irreversible_and_stable(self):
        creds = fake_creds(self.now)
        creds["id_token"] = fake_jwt("account-subject-AAA")
        fp = F.account_fingerprint(creds)
        self.assertRegex(fp, r"^[0-9a-f]{12}$")
        self.assertNotIn("account-subject-AAA", fp)
        self.assertEqual(fp, F.account_fingerprint(dict(creds)))
        # Token rotation under the same sub must not move the fingerprint.
        rotated = dict(creds, access_token=FAKE_ACCESS_2,
                       refresh_token="fake-refresh-rotated-99")
        self.assertEqual(fp, F.account_fingerprint(rotated))

    def test_account_fingerprint_changes_with_account(self):
        a = fake_creds(self.now)
        a["id_token"] = fake_jwt("acct-A")
        b = fake_creds(self.now)
        b["id_token"] = fake_jwt("acct-B")
        self.assertNotEqual(F.account_fingerprint(a), F.account_fingerprint(b))
        # Without id_token the refresh_token is the identity source.
        c = fake_creds(self.now)
        c.update(id_token=None, refresh_token="rt-account-A")
        d = fake_creds(self.now)
        d.update(id_token=None, refresh_token="rt-account-B")
        self.assertNotEqual(F.account_fingerprint(c), F.account_fingerprint(d))
        self.assertIsNone(F.account_fingerprint({}))

    def test_status_shows_fingerprint_without_secrets(self):
        creds = fake_creds(self.now)
        creds["id_token"] = fake_jwt("acct-A")
        write_creds(self.root, creds)
        rc, out = self.capture(F.cmd_status, ns(root=str(self.root)),
                               now=self.now)
        self.assertEqual(rc, 0)
        self.assertIn("account_fingerprint:", out)
        self.assertIn("catalog_account_match:", out)
        self.assertNotIn("acct-A", out)
        for s in SECRET_STRINGS:
            self.assertNotIn(s, out)

    def test_sync_models_writes_catalog_account_fingerprint(self):
        creds = fake_creds(self.now)
        creds["id_token"] = fake_jwt("acct-A")
        write_creds(self.root, creds)
        rc, out = self.capture(F.cmd_sync_models,
                               ns(root=str(self.root), buffer=300),
                               http_fn=token_http(), now=self.now)
        self.assertEqual(rc, 0, out)
        doc = json.loads(
            (self.root / "data" / "agent-handoff" / "chatgpt-oauth" /
             "models.json").read_text())
        self.assertEqual(doc["catalog_account_fingerprint"],
                         F.account_fingerprint(creds))
        self.assertTrue(doc["catalog_fetched_at"])
        self.assertEqual(doc["catalog_fetched_at"], doc["syncedAtUtc"])
        for s in SECRET_STRINGS + ["acct-A"]:
            self.assertNotIn(s, json.dumps(doc))
            self.assertNotIn(s, out)

    def test_status_catalog_match_flags_foreign_catalog(self):
        # Catalog fetched under account A.
        creds_a = fake_creds(self.now)
        creds_a["id_token"] = fake_jwt("acct-A")
        write_creds(self.root, creds_a)
        rc, _ = self.capture(F.cmd_sync_models,
                             ns(root=str(self.root), buffer=300),
                             http_fn=token_http(), now=self.now)
        self.assertEqual(rc, 0)
        # Same account -> match yes.
        rc, out = self.capture(F.cmd_status, ns(root=str(self.root)),
                               now=self.now)
        self.assertIn("catalog_account_match: yes", out)
        # Credentials replaced by account B -> must flag mismatch.
        creds_b = fake_creds(self.now)
        creds_b["id_token"] = fake_jwt("acct-B")
        write_creds(self.root, creds_b)
        rc, out = self.capture(F.cmd_status, ns(root=str(self.root)),
                               now=self.now)
        self.assertEqual(rc, 0)
        self.assertIn("catalog_account_match: no", out)

    def test_secrets_redaction_and_permissions(self):
        """Every output/artifact channel is free of raw token material."""
        outputs = []
        write_creds(self.root, fake_creds(self.now))
        _, st = self.capture(F.cmd_status, ns(root=str(self.root)), now=self.now)
        outputs.append(st)
        _, gt = self.capture(F.cmd_get_token,
                             ns(root=str(self.root), buffer=300),
                             http_fn=token_http(), now=self.now)
        outputs.append(gt.replace(FAKE_ACCESS, ""))  # sole raw channel
        _, rf = self.capture(F.cmd_refresh, ns(root=str(self.root)),
                             http_fn=token_http(), now=self.now)
        outputs.append(rf)
        _, sm = self.capture(F.cmd_sync_models,
                             ns(root=str(self.root), buffer=300),
                             http_fn=token_http(), now=self.now)
        outputs.append(sm)
        stream = smoke_stream([("response.completed", {"response": {"usage": {}}})])
        _, sk = self.capture(
            F.cmd_test_smoke,
            ns(root=str(self.root), buffer=300, model="gpt-5", prompt="x"),
            http_fn=token_http(), stream_fn=stream, now=self.now)
        outputs.append(sk)
        all_text = "\n".join(outputs)
        for s in [FAKE_REFRESH, FAKE_ID, FAKE_CODE]:
            self.assertNotIn(s, all_text)
        # Artifacts outside the creds file hold no token material either.
        for p in (self.root / "data").rglob("*.json"):
            body = p.read_text(encoding="utf-8")
            for s in SECRET_STRINGS:
                self.assertNotIn(s, body, str(p))
        # Credentials land under gitignored .secrets only.
        cp = self.root / ".secrets" / "chatgpt_oauth_credentials.json"
        self.assertTrue(cp.exists())
        gitignore = (REPO_ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("/.secrets/", gitignore)

    def test_yml_configuration_bridge(self):
        body = YML.read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r"(?m)^chatgpt:", body)), 1)
        block = re.search(r"(?ms)^chatgpt:\n((?:  .*\n?)*)", body)
        self.assertIsNotNone(block)
        sect = block.group(1)
        for key in ("enabled: ${CHATGPT_OAUTH_ENABLED:",
                    "credentials-file: ${CHATGPT_OAUTH_CREDENTIALS:",
                    "models-file: ${CHATGPT_OAUTH_MODELS:",
                    "token-endpoint: https://auth.openai.com/api/accounts/oauth/token",
                    "refresh-buffer-seconds: 300"):
            self.assertIn(key, sect, key)
        # No other top-level section may claim these names.
        for stray in ("chatgpt.tokens.use.direct", "BEGIN PRIVATE", "access_token:"):
            self.assertNotIn(stray, body)

    def test_sse_parser_contract(self):
        # 1) split UTF-8 + heartbeat + delta + completed
        payload = json.dumps(
            {"type": "response.output_text.delta", "delta": "한"}).encode()
        comp = json.dumps({"type": "response.completed",
                           "response": {"usage": {}}}).encode()
        raw = (b": heartbeat\n\nevent: response.output_text.delta\ndata: " +
               payload + b"\n\nevent: response.completed\ndata: " + comp + b"\n\n")
        cut = len(b": heartbeat\n\nevent: response.output_text.delta\ndata: ") + \
            len(payload) // 2
        evs = list(F.sse_events([raw[:cut], raw[cut:]]))
        self.assertEqual(len(evs), 2)
        self.assertTrue(any(e == "response.completed" for e, _ in evs))
        # 2) failed-after-200 is terminal-fail, never success
        raw2 = (b'data: {"type":"response.failed","response":{"error":{"code":"x"}}}\n\n')
        evs2 = list(F.sse_events([raw2]))
        self.assertTrue(any(json.loads(d)["type"] == "response.failed"
                            for _, d in evs2))
        # 3) [DONE] sentinel/EOF alone is not completion
        evs3 = list(F.sse_events([b"data: [DONE]\n\n"]))
        self.assertFalse(any(e == "response.completed" for e, _ in evs3))

    def test_cli_status_subprocess(self):
        r = subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--root", str(self.root), "status"],
            capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("not_configured", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
