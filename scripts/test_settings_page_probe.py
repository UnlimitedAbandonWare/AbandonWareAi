"""Offline tests for settings_page_probe.py.

Every HTTP call is faked by a dict-driven fetch; no sockets, no network.
Run: pytest scripts/test_settings_page_probe.py
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "settings_page_probe", ROOT / "scripts" / "settings_page_probe.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

CHAT_HTML = (
    "<html><head><title>AbandonWare AI</title></head><body>"
    '<div id="chatWindow"></div><form id="chatForm"></form>'
    '<a href="/settings">settings</a>'
    '<script src="/js/chat.js"></script>'
    '<script src="/js/chat-settings-bridge.js"></script>'
    "</body></html>")
SETTINGS_HTML = (
    "<html><head><title>Settings</title></head><body>"
    '<div id="settingsRoot"></div>'
    '<script src="/js/settings-page.js"></script>'
    "</body></html>")


def fake_fetch(table):
    calls = []

    def fetch(url, method="GET", body=None):
        calls.append((method, url))
        if url in table:
            return table[url]
        return None, "URLError: no route"

    fetch.calls = calls
    return fetch


def test_classify_settings():
    assert probe.classify_settings(200, SETTINGS_HTML)["verdict"] == "OK"
    assert probe.classify_settings(404, "")["verdict"] == "MISSING"
    assert probe.classify_settings(200, "<title>x</title>")["verdict"] == "WEAK"
    assert probe.classify_settings(None, "")["verdict"] == "UNREACHABLE"
    assert probe.classify_settings(502, "")["verdict"] == "HTTP_502"


def test_classify_chat_extra_markers():
    mod = probe._load_main_probe(ROOT)
    row = probe.classify_chat(200, CHAT_HTML, mod)
    assert row["verdict"] == "MAIN_OK"
    assert row["settingsLink"] is True and row["bridgeScript"] is True
    stripped = CHAT_HTML.replace('href="/settings"', 'href="/x"')
    row2 = probe.classify_chat(200, stripped, mod)
    assert row2["settingsLink"] is False


def test_run_probe_settings_ok(tmp_path):
    fetch = fake_fetch({
        probe.LOCAL + "/chat": (200, CHAT_HTML),
        probe.LOCAL + "/settings": (200, SETTINGS_HTML),
        probe.LOCAL + "/api/settings": (200, '{"A":1,"B":2}'),
        probe.PUBLIC + "/chat": (200, CHAT_HTML),
        probe.PUBLIC + "/settings": (200, SETTINGS_HTML),
    })
    rep = probe.run_probe(ROOT, fetch_fn=fetch, baseline={
        "apiSettings": {"keys": ["A", "C"]}}, do_pure_posts=False)
    assert rep["verdict"] == "SETTINGS_OK"
    api = [r for r in rep["rows"] if r["target"] == "local-api-settings"][0]
    assert api["api"]["keys"] == ["A", "B"]
    assert api["api"]["keySetDrift"] == {"added": ["B"], "removed": ["C"]}
    # GET only — save must never appear in calls
    assert all("/save" not in u for _, u in fetch.calls)


def test_run_probe_settings_missing(tmp_path):
    fetch = fake_fetch({
        probe.LOCAL + "/chat": (200, CHAT_HTML),
        probe.LOCAL + "/settings": (404, "not found"),
        probe.LOCAL + "/api/settings": (200, "{}"),
        probe.PUBLIC + "/chat": (200, CHAT_HTML),
        probe.PUBLIC + "/settings": (404, "x"),
    })
    rep = probe.run_probe(ROOT, fetch_fn=fetch, do_pure_posts=False)
    assert rep["verdict"] == "SETTINGS_MISSING"


def test_run_probe_main_broken_and_unreachable(tmp_path):
    interview = CHAT_HTML.replace("<title>AbandonWare AI</title>",
                                  "<title>INTERVIEW DEMO</title>") \
        + "INTERVIEW DEMO"
    fetch = fake_fetch({
        probe.LOCAL + "/chat": (200, interview),
        probe.LOCAL + "/settings": (200, SETTINGS_HTML),
        probe.LOCAL + "/api/settings": (200, "{}"),
        probe.PUBLIC + "/chat": (200, interview),
        probe.PUBLIC + "/settings": (200, SETTINGS_HTML),
    })
    rep = probe.run_probe(ROOT, fetch_fn=fetch, do_pure_posts=False)
    assert rep["verdict"] == "MAIN_BROKEN"

    rep2 = probe.run_probe(ROOT, fetch_fn=fake_fetch({}),
                           local_only=True, do_pure_posts=False)
    assert rep2["verdict"] == "UNREACHABLE"


def test_routing_pure_posts_only_read_preview(tmp_path):
    ctrl = tmp_path / "main/java/com/example/lms/api/RoutingSettingsController.java"
    ctrl.parent.mkdir(parents=True)
    ctrl.write_text(
        '@RequestMapping("/api/settings/routing")\n'
        "class RoutingSettingsController {\n"
        '  @PostMapping("/read")\n'
        "  public Object read() { return service.read(); }\n    }\n"
        '  @PostMapping("/preview")\n'
        "  public Object preview() { return service.preview(); }\n    }\n"
        '  @PostMapping("/save")\n'
        "  public Object save() { repository.save(x); return ok(); }\n    }\n"
        "}\n", encoding="utf-8")
    cands = probe.routing_pure_posts(tmp_path)
    callable_paths = {c["path"] for c in cands if c["callable"]}
    assert callable_paths == {"/api/settings/routing/read",
                              "/api/settings/routing/preview"}
    assert all("/save" not in c["path"] or not c["callable"] for c in cands)


def test_pure_post_attempt_once_and_never_save(tmp_path):
    # same controller as above, plus fake fetch answering 200 everywhere
    ctrl = tmp_path / "main/java/com/example/lms/api/RoutingSettingsController.java"
    ctrl.parent.mkdir(parents=True, exist_ok=True)
    ctrl.write_text(
        '@RequestMapping("/api/settings/routing")\n'
        "class RoutingSettingsController {\n"
        '  @PostMapping("/read")\n'
        "  public Object read() { return service.read(); }\n    }\n"
        '  @PostMapping("/save")\n'
        "  public Object save() { repository.save(x); return ok(); }\n    }\n"
        "}\n", encoding="utf-8")
    table = {
        probe.LOCAL + "/chat": (200, CHAT_HTML),
        probe.LOCAL + "/settings": (200, SETTINGS_HTML),
        probe.LOCAL + "/api/settings": (200, "{}"),
        probe.LOCAL + "/api/settings/routing/read": (200, '{"revision":0}'),
    }
    fetch = fake_fetch(table)
    rep = probe.run_probe(tmp_path, fetch_fn=fetch, local_only=True,
                          do_pure_posts=True)
    posts = [u for m, u in fetch.calls if m == "POST"]
    assert posts == [probe.LOCAL + "/api/settings/routing/read"]
    assert rep["routingPosts"]["attempts"][0]["bodyKeys"] == ["revision"]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
