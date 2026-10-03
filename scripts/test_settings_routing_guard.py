"""Offline tests for settings_routing_guard.py — PASS and FAIL sides.

All git output is faked (FakeGit) and all files live in a tmp dir; no real
git, no network. Run: pytest scripts/test_settings_routing_guard.py
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "settings_routing_guard", ROOT / "scripts" / "settings_routing_guard.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

CHAT_UI = "main/resources/templates/chat-ui.html"
CHAT_JS = "main/resources/static/js/chat.js"


class FakeGit:
    """Canned git outputs; mutate between snapshot and check to fake drift."""

    def __init__(self):
        self.diff_all = ""
        self.numstat = {}
        self.porcelain_rows = []
        self.blobs = {}

    def __call__(self, args, binary=False):
        if args[:2] == ["status", "--porcelain"]:
            out = "\n".join(self.porcelain_rows)
            return out + ("\n" if out else "")
        if args[:3] == ["diff", "HEAD", "--numstat"]:
            path = args[-1]
            ns = self.numstat.get(path, "")
            return f"{ns}\t{path}\n" if ns else ""
        if args[:3] == ["diff", "HEAD", "-U0"]:
            return self.diff_all
        if args[0] == "show":
            return self.blobs.get(args[1].split(":", 1)[1], b"")
        return ""


def ui_diff(added_lines, removed_lines):
    body = "".join(f"+{l}\n" for l in added_lines)
    body += "".join(f"-{l}\n" for l in removed_lines)
    return (f"diff --git a/{CHAT_UI} b/{CHAT_UI}\n"
            f"--- a/{CHAT_UI}\n+++ b/{CHAT_UI}\n"
            f"@@ -1,0 +1,{len(added_lines)} @@\n{body}")


def make_root(tmp_path: Path):
    (tmp_path / "main/resources/static/js").mkdir(parents=True)
    (tmp_path / "main/resources/templates").mkdir(parents=True)
    (tmp_path / CHAT_JS).write_text("chat-js-body\n", encoding="utf-8")
    (tmp_path / CHAT_UI).write_text("<html>v</html>\n", encoding="utf-8")
    return tmp_path


def make_baseline(tmp_path, git):
    base, _ = guard.snapshot(tmp_path, git, fetch_fn=None,
                             out_dir=tmp_path / "out")
    return base


def test_snapshot_records_mfiles_and_forbidden(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    git.diff_all = ui_diff(["<a>foreign-hunk</a>"], ["<a>old</a>"])
    git.numstat[CHAT_UI] = "1\t1"
    git.numstat[CHAT_JS] = "104\t12"
    git.porcelain_rows = [" M " + CHAT_UI, " M " + CHAT_JS]
    base = make_baseline(root, git)
    assert base["schema"] == guard.SCHEMA
    assert CHAT_UI in base["mFiles"]
    assert base["mFiles"][CHAT_UI]["addedLines"] == ["<a>foreign-hunk</a>"]
    assert CHAT_JS in base["forbidden"]          # chat.js is fingerprinted
    assert base["chatJsNumstat"] == "104\t12"
    assert base["baselineAfterCodexStart"] is False


def test_check_all_pass_when_no_drift(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    git.porcelain_rows = [" M " + CHAT_UI]
    base = make_baseline(root, git)
    res = guard.check(root, base, git)
    assert res["verdict"] == "PASS"
    assert all(i["verdict"] == "PASS" for i in res["items"])


def test_g1_g4_fail_on_chatjs_drift(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    base = make_baseline(root, git)
    # foreign drift after baseline: chat.js changed
    (root / CHAT_JS).write_text("chat-js-body-EDITED\n", encoding="utf-8")
    git.numstat[CHAT_JS] = "105\t12"
    git.porcelain_rows = [" M " + CHAT_JS]
    res = guard.check(root, base, git)
    by_id = {i["id"]: i for i in res["items"]}
    assert res["verdict"] == "FAIL"
    assert by_id["G1"]["verdict"] == "FAIL"
    assert by_id["G4"]["verdict"] == "FAIL"   # chat.js is a forbidden file


def test_g2_two_new_lines_pass_three_fail(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    foreign = ["<a>menu-old</a>", "<a>menu-old2</a>"]
    git.diff_all = ui_diff(foreign, [])
    git.porcelain_rows = [" M " + CHAT_UI]
    base = make_baseline(root, git)

    # Codex adds exactly 2 lines — PASS
    (root / CHAT_UI).write_text(
        "<html>v</html>\n" + "\n".join(foreign)
        + '\n<a href="/settings">S</a>\n<script src="b.js"></script>\n',
        encoding="utf-8")
    git.diff_all = ui_diff(
        foreign + ['<a href="/settings">S</a>', '<script src="b.js"></script>'],
        [])
    res = guard.check(root, base, git)
    by_id = {i["id"]: i for i in res["items"]}
    assert by_id["G2"]["verdict"] == "PASS"
    assert by_id["G2"]["detail"]["newAddedLines"] == 2

    # a third new line — FAIL
    git.diff_all = ui_diff(
        foreign + ['<a href="/settings">S</a>', '<script src="b.js"></script>',
                   "<div>extra</div>"], [])
    res = guard.check(root, base, git)
    by_id = {i["id"]: i for i in res["items"]}
    assert by_id["G2"]["verdict"] == "FAIL"
    assert by_id["G2"]["detail"]["newAddedLines"] == 3


def test_g2_foreign_added_line_removed_fails(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    foreign = ["<a>foreign-keep-me</a>"]
    git.diff_all = ui_diff(foreign, [])
    base = make_baseline(root, git)
    # Codex removed the foreign-added line (file no longer contains it)
    (root / CHAT_UI).write_text("<html>v</html>\n", encoding="utf-8")
    git.diff_all = ""     # current diff empty: foreign line gone
    res = guard.check(root, base, git)
    by_id = {i["id"]: i for i in res["items"]}
    assert by_id["G2"]["verdict"] == "FAIL"
    assert by_id["G2"]["detail"]["foreignAddedPreserved"] is False


def test_g5_flag_true_fails_absent_passes(tmp_path):
    root = make_root(tmp_path)
    (tmp_path / "main/resources").mkdir(parents=True, exist_ok=True)
    (tmp_path / "main/resources/application.properties").write_text(
        "server.port=18180\n", encoding="utf-8")
    git = FakeGit()
    base = make_baseline(root, git)
    res = guard.check(root, base, git)
    assert {i["id"] for i in res["items"]} >= {"G5"}
    assert {i["id"]: i for i in res["items"]}["G5"]["verdict"] == "PASS"
    assert {i["id"]: i for i in res["items"]
            }["G5"]["detail"]["state"] == "absent"

    (tmp_path / "main/resources/application.properties").write_text(
        "chat.settings.routing.enabled=true\n", encoding="utf-8")
    res = guard.check(root, base, git)
    assert {i["id"]: i for i in res["items"]}["G5"]["verdict"] == "FAIL"

    (tmp_path / "main/resources/application.properties").write_text(
        "chat.settings.routing.enabled=false\n", encoding="utf-8")
    res = guard.check(root, base, git)
    assert {i["id"]: i for i in res["items"]}["G5"]["verdict"] == "PASS"
    assert {i["id"]: i for i in res["items"]
            }["G5"]["detail"]["state"] == "false-only"


def test_g6_forbidden_mapping_and_allowed(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    git.porcelain_rows = [" M " + CHAT_UI]
    base = make_baseline(root, git)
    # new file added after baseline with a forbidden /api/settings mapping
    evil = root / "main/java/com/example/lms/web/EvilController.java"
    evil.parent.mkdir(parents=True, exist_ok=True)
    evil.write_text(
        'class Evil { @GetMapping("/api/settings") Object x(){return null;} }\n'
        'class Ok { @GetMapping("/settings") String s(){return "settings";} }\n'
        'class Ok2 { @PostMapping("/api/settings/routing/read") Object r(){'
        'return null;} }\n', encoding="utf-8")
    git.porcelain_rows.append("?? main/java/com/example/lms/web/EvilController.java")
    res = guard.check(root, base, git)
    g6 = {i["id"]: i for i in res["items"]}["G6"]
    assert g6["verdict"] == "FAIL"
    assert g6["detail"]["forbidden"][0]["path"] == "/api/settings"
    allowed = {a["path"] for a in g6["detail"]["allowed"]}
    assert "/settings" in allowed and "/api/settings/routing/read" in allowed


def test_g7_secret_and_placeholder(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    git.porcelain_rows = [" M " + CHAT_UI]
    base = make_baseline(root, git)
    new = root / "main/resources/static/js/leak.js"
    new.write_text(
        'const k = "sk-ABCDEFGHIJKLMNOPQRSTUVWX";\n'
        'const ok = "apiKey=${ENV_NAME}";\n', encoding="utf-8")
    git.porcelain_rows.append("?? main/resources/static/js/leak.js")
    res = guard.check(root, base, git)
    g7 = {i["id"]: i for i in res["items"]}["G7"]
    assert g7["verdict"] == "FAIL"
    pats = [h["pattern"] for h in g7["detail"]["hits"]]
    assert "openai-sk" in pats
    # the ${ENV_NAME} placeholder line must not be reported
    assert all(h["line"] != 2 or h["pattern"] != "key-assign"
               for h in g7["detail"]["hits"])
    # values are never emitted — only file/line/pattern
    for h in g7["detail"]["hits"]:
        assert set(h.keys()) <= {"file", "line", "pattern"}


def test_g8_entity_and_sql(tmp_path):
    root = make_root(tmp_path)
    git = FakeGit()
    git.porcelain_rows = [" M " + CHAT_UI]
    base = make_baseline(root, git)
    sql = root / "main/resources/db/migration/V99999__new.sql"
    sql.parent.mkdir(parents=True, exist_ok=True)
    sql.write_text("CREATE TABLE t(x int);\n", encoding="utf-8")
    ent = root / "main/java/com/example/lms/web/NewThing.java"
    ent.parent.mkdir(parents=True, exist_ok=True)
    ent.write_text("@Entity\nclass NewThing {}\n", encoding="utf-8")
    git.porcelain_rows += [
        "?? main/resources/db/migration/V99999__new.sql",
        "?? main/java/com/example/lms/web/NewThing.java",
    ]
    res = guard.check(root, base, git)
    g8 = {i["id"]: i for i in res["items"]}["G8"]
    assert g8["verdict"] == "FAIL"
    kinds = {h["kind"] for h in g8["detail"]["hits"]}
    assert "new-sql-file" in kinds and "@Entity" in kinds \
        and "CREATE TABLE" in kinds


def test_g3_foreign_hunk_removed_in_other_mfile(tmp_path):
    root = make_root(tmp_path)
    target = "main/java/com/example/lms/service/ChatWorkflow.java"
    (root / "main/java/com/example/lms/service").mkdir(parents=True)
    git = FakeGit()
    git.diff_all = (f"diff --git a/{target} b/{target}\n--- a/{target}\n"
                    f"+++ b/{target}\n@@ -1,0 +1,1 @@\n+foreign-line\n")
    git.porcelain_rows = [" M " + target]
    (root / target).write_text("foreign-line\n", encoding="utf-8")
    base = make_baseline(root, git)
    # later: foreign line deleted, Codex adds his own elsewhere
    (root / target).write_text("codex-new-line\n", encoding="utf-8")
    git.diff_all = (f"diff --git a/{target} b/{target}\n--- a/{target}\n"
                    f"+++ b/{target}\n@@ -1,0 +1,1 @@\n+codex-new-line\n")
    res = guard.check(root, base, git)
    g3 = {i["id"]: i for i in res["items"]}["G3"]
    assert g3["verdict"] == "FAIL"
    assert g3["detail"]["violations"][0]["path"] == target


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
