"""Authenticated HTTP/SSE adapter preserving AWX's existing stdio workers.

Run with environment credentials; see docs/awx-mcp-remote.md. Never put keys in
command arguments. The HTTP transport is stateless: it has no shared session ID
that could transfer one authenticated client's requests into another session.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time
import uuid
from collections import deque
from contextlib import asynccontextmanager
from dataclasses import dataclass
from urllib.parse import parse_qs, urlencode, urlsplit

import uvicorn
from mcp import types
from mcp.server.auth.middleware.auth_context import AuthContextMiddleware
from mcp.server.auth.middleware.bearer_auth import BearerAuthBackend, RequireAuthMiddleware
from mcp.server.auth.provider import ProviderTokenVerifier
from mcp.server.auth.routes import create_auth_routes, create_protected_resource_routes
from mcp.server.auth.settings import ClientRegistrationOptions, RevocationOptions
from mcp.server.lowlevel import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import AnyHttpUrl
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.authentication import AuthenticationMiddleware
from starlette.responses import JSONResponse
from starlette.routing import Route

import awx_mcp_stdio_server as stdio
from awx_mcp_http_auth import OAuthProvider, SCOPE


@dataclass(frozen=True)
class Settings:
    public_url: str = "http://127.0.0.1:8765"
    api_key: str = ""
    owner_key: str = ""
    allowed_tools: tuple[str, ...] = ("build_error_mine",)
    redirect_uris: tuple[str, ...] = ()
    oauth_state_path: str = ""

    def __post_init__(self):
        url = urlsplit(self.public_url)
        if (url.scheme not in ("https", "http") or not url.hostname
                or url.username or url.password or url.query or url.fragment or url.path
                or (url.scheme != "https" and url.hostname not in ("127.0.0.1", "localhost", "::1"))):
            raise ValueError("public_url_requires_https_origin_or_loopback")
        keys = [key for key in (self.api_key, self.owner_key) if key]
        if not keys or any(len(key) < 32 or len(key) > 256 or not key.isascii()
                           or any(c.isspace() for c in key) for key in keys):
            raise ValueError("strong_key_required_minimum_32_characters")
        if self.api_key and self.api_key == self.owner_key:
            raise ValueError("api_and_owner_keys_must_differ")
        names = {tool["name"] for tool in stdio.list_tools()}
        if not self.allowed_tools or not set(self.allowed_tools) <= names:
            raise ValueError("allowed_tools_must_name_existing_tools")
        for uri in self.redirect_uris:
            parsed = urlsplit(uri)
            if (parsed.scheme != "https" or parsed.username or parsed.password
                    or not parsed.hostname or parsed.fragment or parsed.query):
                raise ValueError("redirect_uri_requires_exact_https_url")


class WorkerBridge(stdio.StdioSession):
    def __init__(self):
        super().__init__(request_timeout=120, queue_limit=8)
        self.pending = {}
        self.loop = None

    def start(self):
        self.loop = asyncio.get_running_loop()
        self.worker.start()

    def emit(self, reply):
        if reply is not None and self.loop is not None and not self.loop.is_closed():
            self.loop.call_soon_threadsafe(self.deliver, reply)

    def deliver(self, reply):
        future = self.pending.get(reply.get("id"))
        if future is not None and not future.done():
            future.set_result(reply)

    async def call(self, name, arguments):
        request_id = uuid.uuid4().hex
        future = self.loop.create_future()
        self.pending[request_id] = future
        try:
            self.receive({"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                          "params": {"name": name, "arguments": arguments}})
            response = await asyncio.wait_for(future, timeout=125)
            if "error" in response:
                return tool_error(response["error"].get("message", "worker_failed"))
            return types.CallToolResult.model_validate(response["result"])
        except asyncio.TimeoutError:
            return tool_error("request_timeout")
        finally:
            self.pending.pop(request_id, None)
            self.receive({"jsonrpc": "2.0", "method": "notifications/cancelled",
                          "params": {"requestId": request_id}})

    def close(self):
        self.closing.set()
        with self.lock:
            for state in self.active.values():
                state["cancel"].set()
        self.worker.join(timeout=8)
        if self.worker.is_alive() and self.current_owned is not None:
            self.current_owned.close()
            self.worker.join(timeout=3)
        if self.worker.is_alive():
            raise RuntimeError("worker_shutdown_failed")


def tool_error(reason):
    return types.CallToolResult(isError=True, content=[types.TextContent(type="text", text=reason)])


class RequestBoundary:
    """Apply origin/host/body/concurrency limits before SDK parsing or auth."""
    def __init__(self, app, settings):
        self.app, self.settings = app, settings
        self.auth_requests = deque()
        self.inflight = 0

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = {key.decode("latin1").lower(): value.decode("latin1") for key, value in scope["headers"]}
        path = scope["path"]
        async def reject(status, reason):
            await JSONResponse({"error": reason}, status_code=status,
                               headers={"Cache-Control": "no-store"})(scope, receive, send)
        if headers.get("host") != urlsplit(self.settings.public_url).netloc:
            return await reject(421, "host_not_allowed")
        if path in ("/mcp", "/oauth/consent") and headers.get("origin") not in (None, self.settings.public_url):
            return await reject(403, "origin_not_allowed")
        if sum(key.lower() == b"authorization" for key, _ in scope["headers"]) > 1:
            return await reject(400, "ambiguous_authorization")
        if len(headers.get("authorization", "")) > 1024:
            return await reject(400, "authorization_too_large")
        if path in ("/register", "/authorize", "/token", "/revoke", "/oauth/consent"):
            now = time.monotonic()
            while self.auth_requests and self.auth_requests[0] < now - 60:
                self.auth_requests.popleft()
            if len(self.auth_requests) >= 60:
                return await reject(429, "auth_rate_limited")
            self.auth_requests.append(now)
        if self.inflight >= 32:
            return await reject(503, "request_capacity_exceeded")
        self.inflight += 1
        try:
            # Bound chunked as well as Content-Length bodies. Preserve disconnect
            # observation after replaying the buffered body to the SDK.
            body = bytearray()
            limit = 1024 * 1024 if path == "/mcp" else 16384
            while True:
                event = await receive()
                if event["type"] == "http.disconnect":
                    return
                body.extend(event.get("body", b""))
                if len(body) > limit:
                    return await reject(413, "body_too_large")
                if not event.get("more_body"):
                    break
            if path in ("/token", "/revoke") and scope["method"] == "POST":
                try:
                    form = parse_qs(body.decode("utf-8"), keep_blank_values=True, max_num_fields=20)
                except (UnicodeError, ValueError):
                    return await reject(400, "invalid_request")
                if path == "/token" and form.get("resource") != [self.settings.public_url + "/mcp"]:
                    return await reject(400, "invalid_target")
                if any(len(values) != 1 for values in form.values()):
                    return await reject(400, "invalid_request")
                if path == "/revoke" and "client_secret" not in form:
                    # mcp 1.26.0 RevocationRequest makes this nullable field
                    # required, including for public PKCE clients. Client auth
                    # still runs in the SDK before the revocation handler.
                    form["client_secret"] = [""]
                    body = bytearray(urlencode(form, doseq=True).encode("utf-8"))
            delivered = False
            async def replay():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": bytes(body), "more_body": False}
                return await receive()
            await self.app(scope, replay, send)
        finally:
            self.inflight -= 1


def create_app(settings):
    provider = OAuthProvider(settings)
    bridge = WorkerBridge()
    server = Server("awx-remote", version="1.0.0")
    cached_registry = None
    cached_access = None
    tools = []

    def exposed_tools():
        nonlocal cached_registry, cached_access, tools
        manifest = stdio.registry()
        access = (settings.allowed_tools, os.environ.get("AWX_MCP_SOURCE_ACCESS"))
        # Cache parsed schemas within this app's permission boundary. The
        # existing registry invalidates on manifest/handler/alias changes.
        if manifest is not cached_registry or access != cached_access:
            refreshed = [types.Tool.model_validate(tool) for tool in stdio.list_tools()
                         if tool["name"] in settings.allowed_tools]
            tools = refreshed
            cached_registry, cached_access = manifest, access
        return tools

    @server.list_tools()
    async def list_tools():
        return exposed_tools()

    @server.call_tool(validate_input=False)
    async def call_tool(name, arguments):
        if name not in {tool.name for tool in exposed_tools()}:
            return tool_error("tool_not_exposed")
        try:
            return await bridge.call(name, arguments)
        except Exception:
            return tool_error("worker_failed")

    manager = StreamableHTTPSessionManager(
        server, stateless=True, json_response=False,
        security_settings=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=[urlsplit(settings.public_url).netloc],
            allowed_origins=[settings.public_url]))
    resource = AnyHttpUrl(settings.public_url + "/mcp")
    issuer = AnyHttpUrl(settings.public_url)
    metadata = AnyHttpUrl(settings.public_url + "/.well-known/oauth-protected-resource/mcp")
    routes = [Route("/mcp", endpoint=RequireAuthMiddleware(manager.handle_request, [SCOPE], metadata),
                    methods=["GET", "POST", "DELETE"])]
    if settings.owner_key:
        routes.extend(create_protected_resource_routes(resource, [issuer], scopes_supported=[SCOPE]))
        routes.extend(create_auth_routes(
            provider, issuer,
            client_registration_options=ClientRegistrationOptions(
                enabled=True, valid_scopes=[SCOPE], default_scopes=[SCOPE]),
            revocation_options=RevocationOptions(enabled=True)))
        routes.append(Route("/oauth/consent", provider.consent, methods=["GET", "POST"]))
    else:
        # OAuth authorization_servers is optional for a static API-key resource;
        # the SDK helper requires a non-empty list, so do not invent an issuer.
        async def api_key_metadata(request):
            return JSONResponse({"resource": str(resource), "scopes_supported": [SCOPE],
                                 "bearer_methods_supported": ["header"]})
        routes.append(Route("/.well-known/oauth-protected-resource/mcp", api_key_metadata))

    @asynccontextmanager
    async def lifespan(app):
        bridge.start()
        try:
            async with manager.run():
                yield
        finally:
            try:
                await asyncio.to_thread(bridge.close)
            finally:
                provider.close()

    app = Starlette(routes=routes, lifespan=lifespan, middleware=[
        Middleware(AuthenticationMiddleware, backend=BearerAuthBackend(ProviderTokenVerifier(provider))),
        Middleware(AuthContextMiddleware)])
    app.state.provider = provider
    app.state.bridge = bridge
    return RequestBoundary(app, settings)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--public-url", default=os.environ.get("AWX_MCP_PUBLIC_URL", "http://127.0.0.1:8765"))
    args = parser.parse_args()
    try:
        settings = Settings(
            public_url=args.public_url,
            api_key=os.environ.get("AWX_MCP_API_KEY", ""),
            owner_key=os.environ.get("AWX_MCP_OWNER_KEY", ""),
            allowed_tools=tuple(filter(None, (name.strip() for name in os.environ.get(
                "AWX_MCP_ALLOWED_TOOLS", "build_error_mine").split(",")))),
            redirect_uris=tuple(filter(None, os.environ.get("AWX_MCP_REDIRECT_URIS", "").split(","))),
            oauth_state_path=os.environ.get("AWX_MCP_OAUTH_STATE_PATH", ""))
        app = create_app(settings)
    except ValueError:
        print(json.dumps({"ok": False, "reason": "invalid_remote_configuration"}))
        return 2
    # A tunnel connects to loopback. Never trust arbitrary forwarded headers or
    # put query strings, authorization codes, credentials in HTTP access logs.
    uvicorn.run(app, host="127.0.0.1", port=args.port,
                access_log=False, proxy_headers=False, log_level="warning",
                timeout_keep_alive=5, timeout_graceful_shutdown=15)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
