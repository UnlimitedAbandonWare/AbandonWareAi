"""Single-owner OAuth provider for the AWX remote MCP adapter.

The MCP SDK owns OAuth wire validation and PKCE. This provider owns consent,
resource binding, bounded credentials and single-use exchanges. Optional Windows
DPAPI storage preserves established grants; incomplete logins remain ephemeral.
"""
from __future__ import annotations

import hashlib
import hmac
import html
import secrets
import time
from urllib.parse import urlencode, urlsplit

from mcp.server.auth.provider import (
    AccessToken, AuthorizationCode, AuthorizeError, RefreshToken,
    RegistrationError, TokenError,
)
from mcp.shared.auth import OAuthToken, OAuthClientInformationFull
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse

SCOPE = "awx:tools"
ACCESS_TTL = 3600
REFRESH_TTL = 86400
STATE_TTL = 300
CAPACITY = 256


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def equal(left: str, right: str) -> bool:
    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))


class OAuthProvider:
    def __init__(self, settings):
        self.settings = settings
        self.resource = settings.public_url + "/mcp"
        self.clients = {}
        self.pending = {}
        self.codes = {}
        self.access = {}
        self.refresh = {}
        self.store = None
        if getattr(settings, "oauth_state_path", ""):
            from awx_mcp_oauth_store import OAuthStore
            try:
                self.store = OAuthStore(settings.oauth_state_path, self.resource, settings.owner_key)
                saved = self.store.load()
                if saved is not None:
                    if saved.get("version") != 1 or saved.get("resource") != self.resource:
                        raise ValueError()
                    for name, model in (("clients", OAuthClientInformationFull), ("access", AccessToken)):
                        rows = saved[name]
                        if not isinstance(rows, dict) or len(rows) > CAPACITY:
                            raise ValueError()
                        setattr(self, name, {key: (expiry, model.model_validate(value))
                                            for key, (expiry, value) in rows.items()})
                    if not isinstance(saved["refresh"], dict) or len(saved["refresh"]) > CAPACITY:
                        raise ValueError()
                    self.refresh = {key: (expiry, (RefreshToken.model_validate(value), access_hash))
                                    for key, (expiry, value, access_hash) in saved["refresh"].items()}
                    self.prune()
            except Exception:
                self.close()
                raise ValueError("oauth_state_unavailable") from None

    def close(self):
        if self.store is not None:
            self.store.close()

    def persist(self):
        if self.store is None:
            return
        try:
            self.store.save({"version": 1, "resource": self.resource,
                "clients": {key: (expiry, value.model_dump(mode="json"))
                            for key, (expiry, value) in self.clients.items()},
                "access": {key: (expiry, value.model_dump(mode="json"))
                           for key, (expiry, value) in self.access.items()},
                "refresh": {key: (expiry, value.model_dump(mode="json"), access_hash)
                            for key, (expiry, (value, access_hash)) in self.refresh.items()}})
        except ValueError:
            raise TokenError("invalid_request", "oauth_state_unavailable") from None

    def prune(self):
        now = time.time()
        for table in (self.clients, self.pending, self.codes, self.access, self.refresh):
            for key, (expiry, _) in list(table.items()):
                if expiry <= now:
                    del table[key]

    def put(self, table, key, value, ttl):
        self.prune()
        if len(table) >= CAPACITY:
            raise TokenError("invalid_request", "capacity_exceeded")
        table[key] = (time.time() + ttl, value)

    def get(self, table, key):
        self.prune()
        entry = table.get(key)
        return entry[1] if entry else None

    def redirect_allowed(self, uri):
        text = str(uri)
        if text in self.settings.redirect_uris:
            return True
        # DCR can use the connector-specific callback assigned by ChatGPT.
        # No arbitrary host, query, fragment, credentials or network fetch.
        parsed = urlsplit(text)
        prefix = "/connector/oauth/"
        suffix = parsed.path.removeprefix(prefix)
        return (parsed.scheme == "https" and parsed.netloc == "chatgpt.com"
                and not parsed.query and not parsed.fragment
                and parsed.path.startswith(prefix) and 1 <= len(suffix) <= 128
                and all(c.isascii() and (c.isalnum() or c in "-_") for c in suffix))

    async def get_client(self, client_id):
        return self.get(self.clients, client_id)

    async def register_client(self, client_info):
        if (not client_info.redirect_uris or len(client_info.redirect_uris) > 4
                or not all(self.redirect_allowed(uri) for uri in client_info.redirect_uris)):
            raise RegistrationError("invalid_redirect_uri", "redirect_not_allowed")
        if client_info.token_endpoint_auth_method not in ("none", "client_secret_post", "client_secret_basic"):
            raise RegistrationError("invalid_client_metadata", "auth_method_not_supported")
        try:
            self.put(self.clients, client_info.client_id, client_info, REFRESH_TTL)
            self.persist()
        except TokenError:
            raise RegistrationError("invalid_client_metadata", "registration_unavailable") from None

    async def authorize(self, client, params):
        if (params.resource != self.resource or params.scopes != [SCOPE]
                or not self.redirect_allowed(params.redirect_uri)):
            raise AuthorizeError("invalid_request", "resource_scope_or_redirect_invalid")
        transaction = secrets.token_urlsafe(32)
        state = {"client": client.client_id, "params": params,
                 "csrf": secrets.token_urlsafe(32), "attempts": 0}
        try:
            self.put(self.pending, digest(transaction), state, STATE_TTL)
        except TokenError:
            raise AuthorizeError("temporarily_unavailable", "capacity_exceeded") from None
        return self.settings.public_url + "/oauth/consent?" + urlencode({"transaction": transaction})

    async def consent(self, request):
        headers = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
                   "Content-Security-Policy": "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"}
        if request.method == "GET":
            transaction = request.query_params.get("transaction", "")
            state = self.get(self.pending, digest(transaction))
            if state is None:
                return JSONResponse({"error": "expired_transaction"}, status_code=400, headers=headers)
            response = HTMLResponse(
                '<!doctype html><html lang="en"><meta charset="utf-8"><title>Connect AWX</title>'
                '<h1>Connect ChatGPT to AWX</h1><p>Authorize access to the configured AWX tools. '
                'Enter the AWX owner key set on your server. This is not your ChatGPT password.</p>'
                '<form method="post" action="/oauth/consent">'
                f'<input type="hidden" name="transaction" value="{html.escape(transaction, quote=True)}">'
                f'<input type="hidden" name="csrf" value="{state["csrf"]}">'
                '<label>AWX owner key <input name="owner_key" type="password" autocomplete="off" required></label>'
                '<button type="submit">Authorize AWX tools</button></form></html>', headers=headers)
            response.set_cookie("awx_consent", state["csrf"], httponly=True,
                                secure=self.settings.public_url.startswith("https:"),
                                samesite="lax", max_age=STATE_TTL, path="/oauth/consent")
            return response
        form = await request.form()
        transaction = str(form.get("transaction", ""))
        state = self.get(self.pending, digest(transaction))
        csrf = str(form.get("csrf", ""))
        if (state is None or request.headers.get("origin") != self.settings.public_url
                or not csrf or not equal(csrf, state["csrf"])
                or not equal(csrf, request.cookies.get("awx_consent", ""))):
            return JSONResponse({"error": "consent_rejected"}, status_code=403, headers=headers)
        state["attempts"] += 1
        if not equal(str(form.get("owner_key", "")), self.settings.owner_key):
            if state["attempts"] >= 5:
                self.pending.pop(digest(transaction), None)
            return JSONResponse({"error": "consent_rejected"}, status_code=403, headers=headers)
        self.pending.pop(digest(transaction), None)
        params = state["params"]
        code = AuthorizationCode(
            code=secrets.token_urlsafe(32), scopes=[SCOPE], expires_at=time.time() + 60,
            client_id=state["client"], code_challenge=params.code_challenge,
            redirect_uri=params.redirect_uri,
            redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly,
            resource=self.resource)
        try:
            self.put(self.codes, digest(code.code), code, 60)
        except TokenError:
            return JSONResponse({"error": "capacity_exceeded"}, status_code=503, headers=headers)
        query = {"code": code.code}
        if params.state is not None:
            query["state"] = params.state
        redirect = str(params.redirect_uri)
        response = RedirectResponse(redirect + ("&" if "?" in redirect else "?") + urlencode(query),
                                    status_code=303, headers=headers)
        response.delete_cookie("awx_consent", path="/oauth/consent")
        return response

    async def load_authorization_code(self, client, authorization_code):
        code = self.get(self.codes, digest(authorization_code))
        return code if code and code.client_id == client.client_id else None

    def issue(self, client_id, scopes):
        self.prune()
        if len(self.access) >= CAPACITY or len(self.refresh) >= CAPACITY:
            raise TokenError("invalid_request", "capacity_exceeded")
        now = int(time.time())
        access = AccessToken(token=secrets.token_urlsafe(32), client_id=client_id,
                             scopes=scopes, expires_at=now + ACCESS_TTL, resource=self.resource)
        refresh = RefreshToken(token=secrets.token_urlsafe(32), client_id=client_id,
                               scopes=scopes, expires_at=now + REFRESH_TTL)
        self.put(self.access, digest(access.token), access, ACCESS_TTL)
        self.put(self.refresh, digest(refresh.token), (refresh, digest(access.token)), REFRESH_TTL)
        # A still-active rotating grant must not outlive its registered client.
        if client_id in self.clients:
            self.clients[client_id] = (now + REFRESH_TTL, self.clients[client_id][1])
        self.persist()
        return OAuthToken(access_token=access.token, token_type="Bearer", expires_in=ACCESS_TTL,
                          refresh_token=refresh.token, scope=" ".join(scopes))

    async def exchange_authorization_code(self, client, authorization_code):
        # No await between consumption and issuance: concurrent replays lose.
        saved = self.codes.pop(digest(authorization_code.code), None)
        if not saved or authorization_code.resource != self.resource:
            raise TokenError("invalid_grant", "code_unavailable")
        return self.issue(client.client_id, authorization_code.scopes)

    async def load_refresh_token(self, client, refresh_token):
        pair = self.get(self.refresh, digest(refresh_token))
        return pair[0] if pair and pair[0].client_id == client.client_id else None

    async def exchange_refresh_token(self, client, refresh_token, scopes):
        saved = self.refresh.pop(digest(refresh_token.token), None)
        if not saved:
            raise TokenError("invalid_grant", "refresh_unavailable")
        self.access.pop(saved[1][1], None)
        return self.issue(client.client_id, scopes)

    async def load_access_token(self, token):
        if self.settings.api_key and equal(token, self.settings.api_key):
            return AccessToken(token=token, client_id="awx-api-key", scopes=[SCOPE], resource=self.resource)
        access = self.get(self.access, digest(token))
        return access if access and access.resource == self.resource else None

    async def revoke_token(self, token):
        token_hash = digest(token.token)
        self.access.pop(token_hash, None)
        for key, (_, pair) in list(self.refresh.items()):
            if key == token_hash or pair[1] == token_hash:
                self.access.pop(pair[1], None)
                del self.refresh[key]
        self.persist()
