"""Windows credential persistence regressions; all credentials are synthetic."""
import asyncio
import os
from pathlib import Path
import secrets
from types import SimpleNamespace

import pytest
from mcp.shared.auth import OAuthClientInformationFull

from awx_mcp_http_auth import OAuthProvider, SCOPE
from awx_mcp_http_server import Settings

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows DPAPI contract")


def settings(tmp_path):
    base = Settings(owner_key=secrets.token_urlsafe(32))
    return SimpleNamespace(**{**vars(base), "oauth_state_path": str(tmp_path / "oauth.bin")})


def client():
    return OAuthClientInformationFull(
        client_id="synthetic-client", client_secret=secrets.token_urlsafe(32),
        redirect_uris=["https://chatgpt.com/connector/oauth/synthetic"],
        grant_types=["authorization_code", "refresh_token"],
        token_endpoint_auth_method="client_secret_post", scope=SCOPE)


def test_restart_keeps_access_client_and_refresh_rotation(tmp_path):
    cfg, registered = settings(tmp_path), client()
    first = OAuthProvider(cfg)
    asyncio.run(first.register_client(registered))
    issued = first.issue(registered.client_id, [SCOPE])
    state = Path(cfg.oauth_state_path)
    assert state.is_file(), "OAuth state must survive process exit"
    encrypted = state.read_bytes()
    assert issued.access_token.encode() not in encrypted
    assert issued.refresh_token.encode() not in encrypted
    assert registered.client_secret.encode() not in encrypted
    first.close()
    second = OAuthProvider(cfg)
    try:
        assert asyncio.run(second.get_client(registered.client_id)) is not None
        assert asyncio.run(second.load_access_token(issued.access_token)) is not None
        refresh = asyncio.run(second.load_refresh_token(registered, issued.refresh_token))
        assert refresh is not None
        rotated = asyncio.run(second.exchange_refresh_token(registered, refresh, [SCOPE]))
    finally:
        second.close()
    third = OAuthProvider(cfg)
    try:
        assert asyncio.run(third.load_access_token(issued.access_token)) is None
        assert asyncio.run(third.load_refresh_token(registered, issued.refresh_token)) is None
        access = asyncio.run(third.load_access_token(rotated.access_token))
        assert access is not None
        asyncio.run(third.revoke_token(access))
    finally:
        third.close()
    fourth = OAuthProvider(cfg)
    try:
        assert asyncio.run(fourth.load_access_token(rotated.access_token)) is None
        assert asyncio.run(fourth.load_refresh_token(registered, rotated.refresh_token)) is None
    finally:
        fourth.close()


def test_corrupt_or_wrong_owner_state_never_resets_credentials(tmp_path):
    cfg = settings(tmp_path)
    original = OAuthProvider(cfg)
    original.issue("synthetic", [SCOPE])
    state = Path(cfg.oauth_state_path)
    assert state.is_file(), "OAuth state must be durable"
    original.close()
    wrong = SimpleNamespace(**{**vars(cfg), "owner_key": secrets.token_urlsafe(32)})
    before = state.read_bytes()
    with pytest.raises(ValueError, match="oauth_state_unavailable"):
        OAuthProvider(wrong)
    assert state.read_bytes() == before
    state.write_bytes(b"broken-ciphertext")
    with pytest.raises(ValueError, match="oauth_state_unavailable"):
        OAuthProvider(cfg)
    assert state.read_bytes() == b"broken-ciphertext"


def test_second_state_writer_is_rejected(tmp_path):
    cfg = settings(tmp_path)
    first = OAuthProvider(cfg)
    try:
        with pytest.raises(ValueError, match="oauth_state_unavailable"):
            OAuthProvider(cfg)
    finally:
        if hasattr(first, "close"):
            first.close()


def test_real_process_restart_reuses_oauth_and_executes_tool(tmp_path):
    import httpx
    import socket
    import sys
    import time
    from contextlib import contextmanager
    from awx_mcp_stdio_server import OwnedWorker
    from test_awx_mcp_http_server import authorize, rpc, result

    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    origin = "http://127.0.0.1:" + str(port)
    cfg = SimpleNamespace(public_url=origin, owner_key=secrets.token_urlsafe(32),
                          redirect_uris=("https://chatgpt.com/connector/oauth/synthetic",))
    env = {key: value for key, value in os.environ.items() if not key.startswith("AWX_MCP_")}
    env.update(AWX_MCP_OWNER_KEY=cfg.owner_key, AWX_MCP_PUBLIC_URL=origin,
               AWX_MCP_OAUTH_STATE_PATH=str(tmp_path / "oauth-process.bin"))
    server = Path(__file__).with_name("awx_mcp_http_server.py")
    log = tmp_path / "synthetic.log"
    log.write_text("error: cannot find symbol\n", encoding="utf-8")

    @contextmanager
    def running():
        child = OwnedWorker([sys.executable, "-B", str(server), "--port", str(port)],
                            env=env, capture=False)
        try:
            with httpx.Client(base_url=origin, timeout=10, trust_env=False) as connection:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        response = connection.get("/.well-known/oauth-authorization-server")
                        if response.status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    assert child.process.poll() is None, "Owned server exited"
                    assert time.monotonic() < deadline, "Owned server startup timed out"
                    time.sleep(.05)
                yield connection
        finally:
            assert child.close(), "Owned server cleanup failed"

    with running() as connection:
        grant = authorize((cfg, connection))
        tokens = connection.post("/token", data=grant).json()
        assert result(rpc(connection, tokens["access_token"]))["result"]["serverInfo"]["name"] == "awx-remote"
    with running() as connection:
        catalog = result(rpc(connection, tokens["access_token"], "tools/list", {}))
        assert [tool["name"] for tool in catalog["result"]["tools"]] == ["build_error_mine"]
        call = result(rpc(connection, tokens["access_token"], "tools/call", {
            "name": "build_error_mine", "arguments": {"log_path": str(log),
                "requestId": "process-restart-fixture", "audit_log": str(tmp_path / "audit.jsonl")}}))
        assert call["result"]["structuredContent"]["primaryClass"] == "cannot-find-symbol"
        refresh = {"grant_type": "refresh_token", "client_id": grant["client_id"],
                   "refresh_token": tokens["refresh_token"], "resource": origin + "/mcp"}
        response = connection.post("/token", data=refresh)
        assert response.status_code == 200
        rotated = response.json()
    with running() as connection:
        assert rpc(connection, tokens["access_token"]).status_code == 401
        assert connection.post("/token", data=refresh).status_code == 400
        assert result(rpc(connection, rotated["access_token"]))["result"]["serverInfo"]["name"] == "awx-remote"
