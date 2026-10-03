"""Tests for scripts/devin_cwd_doctor.ps1 (Windows PowerShell 5.1).

Every test builds a throwaway APPDATA/HOME tree under a temp dir and invokes
the doctor with explicit -AppData/-HomeDir/-UserSettingsPath/-GlobalRulesPath/
-CodeWorkspacePath parameters, so the real %APPDATA% profile is never touched.

Run:  python -B scripts/test_devin_cwd_doctor.py   (unittest, exits nonzero on fail)
      or: pytest scripts/test_devin_cwd_doctor.py
"""

import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "devin_cwd_doctor.ps1"
SRC = str(ROOT)  # C:\AbandonWare\demo-1\demo-1\src
SRC_FWD = SRC.replace("\\", "/")

POWERSHELL = "powershell"


def run_doctor(env_root: Path, *args: str):
    appdata = env_root / "appdata"
    home = env_root / "home"
    cmd = [
        POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", str(SCRIPT),
        "-AppData", str(appdata),
        "-HomeDir", str(home),
        "-SrcRoot", SRC,
        "-UserSettingsPath", str(appdata / "devin" / "User" / "settings.json"),
        "-GlobalRulesPath", str(home / ".codeium" / "windsurf" / "memories" / "global_rules.md"),
        "-CodeWorkspacePath", str(env_root / "demo1-src.code-workspace"),
        "-Json",
        *args,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    out = proc.stdout.strip()
    obj = json.loads(out) if out.startswith("{") else None
    return proc.returncode, obj, proc.stdout + proc.stderr


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def make_workspace(appdata: Path, ws_id: str, folder_paths, mtime_age_sec: float = 3600) -> Path:
    ws_dir = appdata / "Devin" / "Workspaces" / ws_id
    ws_dir.mkdir(parents=True, exist_ok=True)
    wj = ws_dir / "workspace.json"
    write_json(wj, {"folders": [{"path": p} for p in folder_paths],
                    "workbenchMode": "windsurf-agent-window"})
    ts = time.time() - mtime_age_sec
    os.utime(wj, (ts, ts))
    return wj


def make_ok_env(env: Path) -> Path:
    """A fully healthy fake environment (ws '111' mtime = 30 min ago)."""
    appdata, home = env / "appdata", env / "home"
    make_workspace(appdata, "111", [SRC], mtime_age_sec=1800)
    write_json(appdata / "devin" / "User" / "settings.json",
               {"terminal.integrated.cwd": SRC, "workbench.colorTheme": "Default Dark"})
    gr = home / ".codeium" / "windsurf" / "memories" / "global_rules.md"
    gr.parent.mkdir(parents=True, exist_ok=True)
    gr.write_text("<!-- DEMO1-CWD-GUARD -->\nx\n<!-- /DEMO1-CWD-GUARD -->\n", encoding="utf-8")
    write_json(env / "demo1-src.code-workspace",
               {"folders": [{"name": "src", "path": SRC_FWD}],
                "settings": {"terminal.integrated.cwd": SRC}})
    return env


def c1_item(obj: dict, ws_id: str) -> dict:
    c1 = next(c for c in obj["checks"] if c["id"] == "C1")
    return next(i for i in c1["items"] if i["id"] == ws_id)


def check(obj: dict, cid: str) -> dict:
    return next(c for c in obj["checks"] if c["id"] == cid)


class DoctorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="cwd-doctor-test-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # C1 detection ------------------------------------------------------
    def test_c1_detects_relative_path_to_home(self):
        env = make_ok_env(self.tmp / "env")
        ws_dir = env / "appdata" / "Devin" / "Workspaces" / "bad1"
        ws_dir.mkdir(parents=True)
        home = (env / "home").resolve()
        rel = os.path.relpath(home, ws_dir.resolve())
        wj = ws_dir / "workspace.json"
        write_json(wj, {"folders": [{"path": rel}]})
        old = time.time() - 7200  # older than ws 111 -> plain BAD, fixable
        os.utime(wj, (old, old))
        code, obj, _ = run_doctor(env, "-Check")
        self.assertEqual(code, 2)
        it = c1_item(obj, "bad1")
        self.assertEqual(it["status"], "BAD")
        self.assertIn("HOME", it["detail"])

    def test_c1_detects_empty_folders(self):
        env = make_ok_env(self.tmp / "env")
        make_workspace(env / "appdata", "empty1", [], mtime_age_sec=7200)
        code, obj, _ = run_doctor(env, "-Check")
        self.assertEqual(code, 2)
        it = c1_item(obj, "empty1")
        self.assertEqual(it["status"], "BAD")
        self.assertIn("empty", it["detail"])

    def test_all_ok_exit0(self):
        env = make_ok_env(self.tmp / "env")
        code, obj, out = run_doctor(env, "-Check")
        self.assertEqual(code, 0, out)
        self.assertEqual(obj["exitCode"], 0)

    # Fix ---------------------------------------------------------------
    def test_fix_then_check_green(self):
        env = make_ok_env(self.tmp / "env")
        make_workspace(env / "appdata", "badold", [str((env / "home").resolve())], mtime_age_sec=7200)
        (env / "appdata" / "devin" / "User" / "settings.json").write_text("{}", encoding="utf-8")
        (env / "home" / ".codeium" / "windsurf" / "memories" / "global_rules.md").write_text("", encoding="utf-8")
        (env / "demo1-src.code-workspace").unlink()
        run_doctor(env, "-Fix")
        code2, obj2, out2 = run_doctor(env, "-Check")
        for cid in ("C2", "C3", "C4"):
            self.assertEqual(check(obj2, cid)["status"], "OK", f"{cid} not OK: {out2}")
        self.assertEqual(c1_item(obj2, "badold")["status"], "OK")
        self.assertEqual(code2, 0, out2)

    def test_fix_idempotent(self):
        env = make_ok_env(self.tmp / "env")
        make_workspace(env / "appdata", "badold", [str((env / "home").resolve())], mtime_age_sec=7200)
        code1, obj1, out1 = run_doctor(env, "-Fix")
        self.assertEqual(obj1["exitCode"], 0, out1)
        files1 = {p: p.read_bytes() for p in env.rglob("*") if p.is_file()}
        code2, obj2, out2 = run_doctor(env, "-Fix")
        self.assertEqual(obj2["exitCode"], 0, out2)
        self.assertEqual(obj2["fixed"], [])
        self.assertEqual(obj2["backups"], [])
        files2 = {p: p.read_bytes() for p in env.rglob("*") if p.is_file()}
        self.assertEqual(set(files1), set(files2), "second -Fix created files")
        for p, b in files1.items():
            self.assertEqual(files2[p], b, f"{p} changed on second -Fix")

    def test_recent_mtime_workspace_staged(self):
        env = make_ok_env(self.tmp / "env")
        wj = make_workspace(env / "appdata", "live1", [str((env / "home").resolve())], mtime_age_sec=5)
        code, obj, _ = run_doctor(env, "-Fix")
        self.assertEqual(code, 2)
        self.assertIn("live1", obj["staged"])
        data = json.loads(wj.read_text())
        self.assertNotEqual(data["folders"], [{"name": "src", "path": SRC_FWD}])

    def test_newest_workspace_staged_reason(self):
        env = make_ok_env(self.tmp / "env")
        # newer than ws 111 (1800s) but NOT within the 120s active window
        make_workspace(env / "appdata", "newbad", [str((env / "home").resolve())], mtime_age_sec=600)
        code, obj, _ = run_doctor(env, "-Fix")
        it = c1_item(obj, "newbad")
        self.assertEqual(it["status"], "STAGED")
        self.assertEqual(it["stagedReason"], "newest-workspace")
        self.assertIn("newbad", obj["staged"])

    def test_open_window_workspace_staged(self):
        env = make_ok_env(self.tmp / "env")
        appdata = env / "appdata"
        # workspace.json mtime is old and it is not the newest workspace, but a
        # recently-touched workspaceStorage entry maps to it -> open window.
        wj = make_workspace(appdata, "7777777777", [str((env / "home").resolve())], mtime_age_sec=7200)
        storage_dir = appdata / "Devin" / "User" / "workspaceStorage" / ("deadbeef" * 4)
        write_json(storage_dir / "workspace.json",
                   {"workspace": "file:///c%3A/fake/Devin/Workspaces/7777777777/workspace.json"})
        code, obj, out = run_doctor(env, "-Fix")
        it = c1_item(obj, "7777777777")
        self.assertEqual(it["status"], "STAGED", out)
        self.assertEqual(it["stagedReason"], "open-window")
        self.assertIn("7777777777", obj["staged"])
        self.assertNotEqual(json.loads(wj.read_text())["folders"],
                            [{"name": "src", "path": SRC_FWD}])

    def test_include_active_forces_fix(self):
        env = make_ok_env(self.tmp / "env")
        wj = make_workspace(env / "appdata", "live1", [str((env / "home").resolve())], mtime_age_sec=5)
        code, obj, out = run_doctor(env, "-Fix", "-IncludeActive")
        self.assertEqual(code, 0, out)
        data = json.loads(wj.read_text())
        self.assertEqual(data["folders"], [{"name": "src", "path": SRC_FWD}])
        self.assertEqual(data["workbenchMode"], "windsurf-agent-window")  # other keys kept

    def test_backup_and_restore(self):
        env = make_ok_env(self.tmp / "env")
        wj = make_workspace(env / "appdata", "badold", [str((env / "home").resolve())], mtime_age_sec=7200)
        original = wj.read_bytes()
        code, obj, _ = run_doctor(env, "-Fix")
        self.assertEqual(obj["exitCode"], 0)
        baks = list(wj.parent.glob("workspace.json.bak-*"))
        self.assertEqual(len(baks), 1)
        self.assertEqual(baks[0].read_bytes(), original)
        code2, obj2, out2 = run_doctor(env, "-Restore", str(baks[0]))
        self.assertEqual(code2, 0, out2)
        self.assertEqual(wj.read_bytes(), original)

    def test_settings_keys_preserved_and_parses(self):
        env = self.tmp / "env"
        appdata, home = env / "appdata", env / "home"
        make_workspace(appdata, "111", [SRC])
        settings = appdata / "devin" / "User" / "settings.json"
        write_json(settings, {"workbench.colorTheme": "Default Dark",
                              "devin.acp.enabledAgents": ["a", "b"]})
        gr = home / ".codeium" / "windsurf" / "memories" / "global_rules.md"
        gr.parent.mkdir(parents=True, exist_ok=True)
        gr.write_text("x", encoding="utf-8")
        write_json(env / "demo1-src.code-workspace",
                   {"folders": [{"name": "src", "path": SRC_FWD}]})
        code, obj, out = run_doctor(env, "-Fix")
        self.assertEqual(obj["exitCode"], 0, out)
        data = json.loads(settings.read_text(encoding="utf-8-sig"))
        self.assertEqual(data["terminal.integrated.cwd"], SRC)
        self.assertEqual(data["workbench.colorTheme"], "Default Dark")
        self.assertEqual(data["devin.acp.enabledAgents"], ["a", "b"])

    def test_corrupt_workspace_reported_not_rewritten(self):
        env = make_ok_env(self.tmp / "env")
        ws_dir = env / "appdata" / "Devin" / "Workspaces" / "corrupt1"
        ws_dir.mkdir(parents=True)
        wj = ws_dir / "workspace.json"
        wj.write_text("{not json", encoding="utf-8")
        old = time.time() - 7200
        os.utime(wj, (old, old))
        code, obj, _ = run_doctor(env, "-Fix")
        self.assertEqual(code, 2)
        self.assertEqual(wj.read_text(), "{not json")
        self.assertEqual(c1_item(obj, "corrupt1")["status"], "CORRUPT")

    def test_real_appdata_untouched(self):
        real_settings = Path(os.environ["APPDATA"]) / "devin" / "User" / "settings.json"
        before = real_settings.read_bytes() if real_settings.exists() else None
        env = self.tmp / "env"
        run_doctor(env, "-Fix")
        after = real_settings.read_bytes() if real_settings.exists() else None
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main(verbosity=2)
