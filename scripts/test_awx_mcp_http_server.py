"""Remote transport acceptance tests. All credentials are ephemeral fixtures."""
import base64
import hashlib
import importlib
import json
import re
import secrets
import sys
from pathlib import Path

import pytest
from starlette.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent))


@pytest.fixture
def harness():
    remote = importlib.import_module("awx_mcp_http_server")
    settings = remote.Settings(
        public_url="http://127.0.0.1:8765", api_key=secrets.token_urlsafe(32),
        owner_key=secrets.token_urlsafe(32), allowed_tools=("build_error_mine",),
        redirect_uris=("https://chatgpt.com/connector_platform_oauth_redirect",),
    )
    with TestClient(remote.create_app(settings), base_url=settings.public_url) as client:
        yield settings, client


def rpc(client, token, method="initialize", params=None, request_id=1, **headers):
    h = {"Accept": "application/json, text/event-stream", **headers}
    if token is not None:
        h["Authorization"] = "Bearer " + token
    if params is None:
        params = {"protocolVersion": "2025-06-18", "capabilities": {},
                  "clientInfo": {"name": "awx-test", "version": "1"}}
    return client.post("/mcp", headers=h, json={
        "jsonrpc": "2.0", "id": request_id, "method": method, "params": params})


def result(response):
    assert response.status_code == 200
    if response.headers.get("content-type", "").startswith("text/event-stream"):
        return json.loads(next(line[6:] for line in response.text.splitlines()
                               if line.startswith("data: ")))
    return response.json()


@pytest.mark.parametrize("kind", ["missing", "wrong", "malformed", "query"])
def test_unauthorized_requests_are_rejected(harness, kind, monkeypatch):
    settings, client = harness
    dispatches = []
    monkeypatch.setattr(client.app.app.state.bridge, "receive", lambda request: dispatches.append(request))
    if kind == "malformed":
        response = client.post("/mcp", headers={"Authorization": "Basic irrelevant"})
    elif kind == "query":
        response = client.get("/mcp", params={"access_token": settings.api_key})
    else:
        response = rpc(client, None if kind == "missing" else "invalid", "tools/call",
                       {"name": "build_error_mine", "arguments": {"log_path": "synthetic-never-read.log"}})
    assert response.status_code == 401
    assert "resource_metadata=" in response.headers["www-authenticate"]
    assert settings.api_key not in response.text
    assert dispatches == []


def test_valid_bearer_initializes_and_negotiates_sse(harness):
    settings, client = harness
    response = rpc(client, settings.api_key)
    assert response.headers["content-type"].startswith("text/event-stream")
    assert result(response)["result"]["serverInfo"]["name"] == "awx-remote"


def test_catalog_allowlist_and_existing_worker_execution(harness, tmp_path):
    settings, client = harness
    catalog = result(rpc(client, settings.api_key, "tools/list", {}))["result"]["tools"]
    assert [tool["name"] for tool in catalog] == ["build_error_mine"]
    log = tmp_path / "synthetic-build.log"
    log.write_text("error: cannot find symbol\n", encoding="utf-8")
    reply = result(rpc(client, settings.api_key, "tools/call", {
        "name": "build_error_mine", "arguments": {"log_path": str(log)}}))
    assert reply["result"]["isError"] is False
    assert reply["result"]["structuredContent"]["primaryClass"] == "cannot-find-symbol"
    rejected = result(rpc(client, settings.api_key, "tools/call", {
        "name": "archive_restore", "arguments": {}}))
    assert rejected["result"]["isError"] is True
    assert "tool_not_exposed" in str(rejected)


def test_origin_and_host_are_checked(harness):
    settings, client = harness
    assert rpc(client, settings.api_key, Origin="https://evil.invalid").status_code == 403
    assert rpc(client, settings.api_key, Host="evil.invalid").status_code == 421


def test_resource_discovery_is_public(harness):
    settings, client = harness
    metadata = client.get("/.well-known/oauth-protected-resource/mcp").json()
    assert metadata["resource"] == settings.public_url + "/mcp"
    auth = client.get("/.well-known/oauth-authorization-server").json()
    assert "S256" in auth["code_challenge_methods_supported"]
    assert "authorization_code" in auth["grant_types_supported"]


def authorize(harness):
    settings, client = harness
    redirect = settings.redirect_uris[0]
    registration = client.post("/register", json={
        "redirect_uris": [redirect], "token_endpoint_auth_method": "none",
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"], "scope": "awx:tools"})
    assert registration.status_code == 201
    client_id = registration.json()["client_id"]
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    response = client.get("/authorize", params={
        "client_id": client_id, "redirect_uri": redirect, "response_type": "code",
        "code_challenge": challenge, "code_challenge_method": "S256", "state": "fixture-state",
        "scope": "awx:tools", "resource": settings.public_url + "/mcp"}, follow_redirects=True)
    assert response.status_code == 200
    fields = dict(re.findall(r'name="(transaction|csrf)" value="([^"]+)"', response.text))
    assert set(fields) == {"transaction", "csrf"}
    bad = client.post("/oauth/consent", data={**fields, "owner_key": "wrong"},
                      headers={"Origin": settings.public_url}, follow_redirects=False)
    assert bad.status_code == 403
    response = client.post("/oauth/consent", data={**fields, "owner_key": settings.owner_key},
                           headers={"Origin": settings.public_url}, follow_redirects=False)
    assert response.status_code == 303
    from urllib.parse import parse_qs, urlsplit
    query = parse_qs(urlsplit(response.headers["location"]).query)
    assert query["state"] == ["fixture-state"]
    return {"grant_type": "authorization_code", "client_id": client_id,
            "code": query["code"][0], "redirect_uri": redirect,
            "code_verifier": verifier, "resource": settings.public_url + "/mcp"}


def test_oauth_pkce_single_use_and_refresh_rotation(harness):
    settings, client = harness
    grant = authorize(harness)
    assert client.post("/token", data={**grant, "code_verifier": "x" * 64}).status_code == 400
    assert client.post("/token", data={**grant, "resource": "https://evil.invalid/mcp"}).status_code == 400
    response = client.post("/token", data=grant)
    assert response.status_code == 200
    tokens = response.json()
    assert result(rpc(client, tokens["access_token"]))["result"]
    assert client.post("/token", data=grant).status_code == 400
    refresh = {"grant_type": "refresh_token", "client_id": grant["client_id"],
               "refresh_token": tokens["refresh_token"], "resource": grant["resource"]}
    new = client.post("/token", data=refresh)
    assert new.status_code == 200
    assert new.json()["refresh_token"] != tokens["refresh_token"]
    assert client.post("/token", data=refresh).status_code == 400


def test_registration_rejects_unapproved_redirect(harness):
    _, client = harness
    response = client.post("/register", json={"redirect_uris": ["https://evil.invalid/callback"]})
    assert response.status_code == 400


def test_consent_requires_csrf(harness):
    settings, client = harness
    response = client.post("/oauth/consent", data={"transaction": "missing", "owner_key": settings.owner_key})
    assert response.status_code == 403


def test_startup_fails_closed_without_strong_keys():
    remote = importlib.import_module("awx_mcp_http_server")
    with pytest.raises(ValueError, match="key"):
        remote.Settings(public_url="http://127.0.0.1:8765", api_key="", owner_key="")


@pytest.mark.parametrize("state, expected", [("expired", 401), ("wrong_audience", 401), ("scope", 403)])
def test_oauth_token_expiry_audience_and_scope(harness, state, expected):
    import time
    from awx_mcp_http_auth import digest
    settings, client = harness
    grant = authorize(harness)
    tokens = client.post("/token", data=grant).json()
    provider = client.app.app.state.provider
    key = digest(tokens["access_token"])
    token = provider.access[key][1]
    if state == "expired":
        token.expires_at = int(time.time()) - 1
    elif state == "wrong_audience":
        token.resource = "https://evil.invalid/mcp"
    else:
        token.scopes = []
    assert rpc(client, tokens["access_token"]).status_code == expected


def test_revocation_invalidates_access_and_refresh(harness):
    _, client = harness
    grant = authorize(harness)
    tokens = client.post("/token", data=grant).json()
    response = client.post("/revoke", data={"token": tokens["access_token"], "client_id": grant["client_id"]})
    assert response.status_code == 200
    assert rpc(client, tokens["access_token"]).status_code == 401
    assert client.post("/token", data={"grant_type": "refresh_token", "client_id": grant["client_id"],
        "refresh_token": tokens["refresh_token"], "resource": grant["resource"]}).status_code == 400


def test_large_chunked_requests_and_duplicate_auth_are_rejected(harness):
    settings, client = harness
    response = client.post("/mcp", content=iter([b"x" * 600000, b"x" * 600000]))
    assert response.status_code == 413
    response = client.post("/mcp", headers=[("Authorization", "Bearer " + settings.api_key),
                                           ("Authorization", "Bearer invalid")])
    assert response.status_code == 400


def test_registration_is_bounded(harness):
    _, client = harness
    for _ in range(60):
        response = client.post("/register", json={})
        assert response.status_code == 400
    assert client.post("/register", json={}).status_code == 429


def test_bearer_only_mode_has_no_oauth_consent():
    remote = importlib.import_module("awx_mcp_http_server")
    settings = remote.Settings(api_key=secrets.token_urlsafe(32))
    with TestClient(remote.create_app(settings), base_url=settings.public_url) as client:
        assert result(rpc(client, settings.api_key))["result"]
        assert client.get("/oauth/consent").status_code == 404


def test_real_socket_sdk_clients_preserve_request_isolation(tmp_path):
    import asyncio
    import socket
    import threading
    import time
    import uvicorn
    from mcp import ClientSession
    from mcp.client.streamable_http import streamablehttp_client
    remote = importlib.import_module("awx_mcp_http_server")
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
    settings = remote.Settings(public_url=f"http://127.0.0.1:{port}", api_key=secrets.token_urlsafe(32))
    server = uvicorn.Server(uvicorn.Config(remote.create_app(settings), host="127.0.0.1", port=port,
                                         access_log=False, log_level="critical"))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(.02)
        assert server.started
        async def client_call(text, expected):
            log = tmp_path / (expected + ".log")
            log.write_text(text, encoding="utf-8")
            async with streamablehttp_client(settings.public_url + "/mcp",
                    headers={"Authorization": "Bearer " + settings.api_key}) as (read, write, session_id):
                async with ClientSession(read, write) as session:
                    initialized = await session.initialize()
                    assert initialized.serverInfo.name == "awx-remote"
                    assert session_id() is None
                    assert [t.name for t in (await session.list_tools()).tools] == ["build_error_mine"]
                    reply = await session.call_tool("build_error_mine", {"log_path": str(log)})
                    assert not reply.isError
                    assert reply.structuredContent["primaryClass"] == expected
        async def both():
            await asyncio.gather(client_call("cannot find symbol", "cannot-find-symbol"),
                                 client_call("duplicate class", "duplicate-class-fqcn"))
        asyncio.run(both())
    finally:
        server.should_exit = True
        thread.join(timeout=15)
        listener.close()
    assert not thread.is_alive()
