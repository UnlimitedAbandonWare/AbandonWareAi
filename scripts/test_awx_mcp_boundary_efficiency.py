"""Permission, discovery freshness and response-budget regressions; no providers."""
import importlib
import json
import secrets
import sys
from pathlib import Path
from unittest.mock import Mock

import pytest
from starlette.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent))
import awx_mcp_stdio_server as stdio
from test_awx_mcp_http_server import result, rpc


def test_shared_read_discovery_matches_call_gate_and_recovers(monkeypatch):
    monkeypatch.delenv("AWX_MCP_SOURCE_ACCESS", raising=False)
    all_names = {tool["name"] for tool in stdio.list_tools()}
    registrations = stdio.registry()["tools"]
    expected = {row["name"] for row in registrations if row["readOnly"] is True}
    denied = next(row for row in registrations if row["readOnly"] is False)
    handler = Mock()
    monkeypatch.setitem(stdio.HANDLERS, denied["name"], handler)
    monkeypatch.setenv("AWX_MCP_SOURCE_ACCESS", "shared-read")
    visible = {tool["name"] for tool in stdio.list_tools()}
    assert visible == expected
    for name in [denied["name"], *denied.get("aliases", [])]:
        with pytest.raises(stdio.ProtocolError, match="shared_read_mutation_denied"):
            stdio.validate_tool_call({"name": name, "arguments": {}})
    handler.assert_not_called()
    monkeypatch.delenv("AWX_MCP_SOURCE_ACCESS")
    assert {tool["name"] for tool in stdio.list_tools()} == all_names


def test_http_catalog_refreshes_manifest_and_blocks_removed_tool(tmp_path, monkeypatch):
    remote = importlib.import_module("awx_mcp_http_server")
    manifest = json.loads(stdio.MANIFEST_PATH.read_text(encoding="utf-8"))
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    monkeypatch.setattr(stdio, "MANIFEST_PATH", path)
    settings = remote.Settings(api_key=secrets.token_urlsafe(32),
                               allowed_tools=("build_error_mine", "boot_verify"))
    app = remote.create_app(settings)
    dispatches = []

    async def record_call(name, arguments):
        dispatches.append(name)
        return remote.tool_error("synthetic_dispatch")

    monkeypatch.setattr(app.app.state.bridge, "call", record_call)
    with TestClient(app, base_url=settings.public_url) as client:
        catalog = lambda: result(rpc(client, settings.api_key, "tools/list", {}))["result"]["tools"]
        before = catalog()
        assert {row["name"] for row in before} == set(settings.allowed_tools)
        assert catalog() == before
        manifest["tools"] = [row for row in manifest["tools"] if row["name"] != "boot_verify"]
        next(row for row in manifest["tools"] if row["name"] == "build_error_mine")["description"] = "Updated synthetic description"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        after = catalog()
        assert [row["name"] for row in after] == ["build_error_mine"]
        assert after[0]["description"] == "Updated synthetic description"
        rejected = result(rpc(client, settings.api_key, "tools/call", {
            "name": "boot_verify", "arguments": {"nodeRole": "desktop"}}))["result"]
        assert rejected["isError"] is True
        assert dispatches == []


def test_http_discovery_cache_is_local_to_allowlist_and_access_mode(monkeypatch):
    remote = importlib.import_module("awx_mcp_http_server")
    monkeypatch.delenv("AWX_MCP_SOURCE_ACCESS", raising=False)
    denied = next(row["name"] for row in stdio.registry()["tools"] if row["readOnly"] is False)
    broad = remote.Settings(api_key=secrets.token_urlsafe(32), allowed_tools=("boot_verify", denied))
    narrow = remote.Settings(api_key=secrets.token_urlsafe(32), allowed_tools=("build_error_mine",))
    with TestClient(remote.create_app(broad), base_url=broad.public_url) as first, \
         TestClient(remote.create_app(narrow), base_url=narrow.public_url) as second:
        def names(client, settings):
            return {row["name"] for row in result(rpc(client, settings.api_key, "tools/list", {}))["result"]["tools"]}
        assert names(first, broad) == set(broad.allowed_tools)
        assert names(second, narrow) == set(narrow.allowed_tools)
        monkeypatch.setenv("AWX_MCP_SOURCE_ACCESS", "shared-read")
        assert names(first, broad) == {"boot_verify"}
        assert names(second, narrow) == {"build_error_mine"}
        monkeypatch.delenv("AWX_MCP_SOURCE_ACCESS")
        assert names(first, broad) == set(broad.allowed_tools)
        assert rpc(first, narrow.api_key, "tools/list", {}).status_code == 401


def synthetic_tool_result(monkeypatch, payload):
    handler = Mock(return_value={"commands": [payload], "executeSupported": False})
    monkeypatch.setitem(stdio.HANDLERS, "boot_verify", handler)
    monkeypatch.setattr(stdio.toolbox, "finalize", lambda *args: None)
    response = stdio.call_tool({"name": "boot_verify", "arguments": {"nodeRole": "desktop"}})
    handler.assert_called_once()
    return response


@pytest.mark.parametrize("payload", ["x" * 300000, "x" * 150000, "한" * 30000, '\\"' * 40000],
                         ids=["large", "duplicated", "unicode", "escaped"])
def test_complete_tool_result_budget_counts_both_representations(monkeypatch, payload):
    response = synthetic_tool_result(monkeypatch, payload)
    encoded = json.dumps(response, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    assert len(encoded) <= 262144
    assert response["isError"] is True
    failure = response["structuredContent"]
    assert failure["reason"] == "tool_result_size_limit"
    assert failure["toolInvoked"] is True
    assert failure["automaticRetryAllowed"] is False
    assert json.loads(response["content"][0]["text"]) == failure
    assert payload[:100] not in response["content"][0]["text"]


def test_small_tool_result_remains_complete_and_redacted(monkeypatch):
    response = synthetic_tool_result(monkeypatch, "synthetic harmless command")
    assert response["isError"] is False
    assert response["structuredContent"]["commands"] == ["synthetic harmless command"]
    assert json.loads(response["content"][0]["text"]) == response["structuredContent"]


def test_result_budget_is_inclusive_at_complete_envelope_boundary(monkeypatch):
    response = synthetic_tool_result(monkeypatch, "x")
    overhead = len(json.dumps(response, ensure_ascii=True, separators=(",", ":")).encode("utf-8")) - 2
    payload_size = (262144 - overhead) // 2
    at_limit = synthetic_tool_result(monkeypatch, "x" * payload_size)
    assert at_limit["isError"] is False
    assert len(json.dumps(at_limit, ensure_ascii=True, separators=(",", ":")).encode("utf-8")) <= 262144
    over_limit = synthetic_tool_result(monkeypatch, "x" * (payload_size + 1))
    assert over_limit["isError"] is True
