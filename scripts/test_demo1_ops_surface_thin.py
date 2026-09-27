import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "demo1_ops_surface_thin.ps1"
MANIFEST_GLOB = "ops-surface-thin-*.json"
DENY_PREFIXES = (
    ".agents/", "scripts/", "main/", "app/", "configs/", "config/", "src/",
    "data/", "var/", ".secrets/", ".git/", "__patch_drop__/", "frontend/",
    "docs/", "build/", "logs/", "agent-prompts/_archive/",
)


def run_ps(root, *args):
    argv = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(SCRIPT), "-Root", str(root), *args]
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=300)
    return proc


def latest_manifest(root, before):
    dbg = Path(root) / "var" / "debug"
    files = sorted(dbg.glob(MANIFEST_GLOB), key=lambda p: p.stat().st_mtime)
    new = [f for f in files if f not in before]
    if not new:
        return None, None
    data = json.loads(new[-1].read_text(encoding="utf-8-sig"))
    return new[-1], data


def manifests(root):
    dbg = Path(root) / "var" / "debug"
    if not dbg.exists():
        return set()
    return set(dbg.glob(MANIFEST_GLOB))


def tree_listing(base):
    out = []
    for p in sorted(Path(base).rglob("*")):
        rel = str(p.relative_to(base))
        if rel.startswith("var"):
            continue  # generated manifest output is allowed
        out.append(rel)
    return out


def make_fixture(root: Path):
    (root / "agent-prompts").mkdir(parents=True)
    (root / "AGENTS.md").write_text(
        "# fixture\nref: agent-prompts/keepref-20260101/brief.md\n",
        encoding="utf-8")
    # targets: targets.json + 3 dated (c oldest, a middle, b newest)
    for name, age in [("targets.json", 40), ("targets-a.json", 20),
                      ("targets-b.json", 10), ("targets-c.json", 30)]:
        p = root / name
        p.write_text("{}", encoding="utf-8")
        old = time.time() - age * 86400
        os.utime(p, (old, old))
    # prompt dirs
    def mk(name, files=None, age_days=30):
        d = root / "agent-prompts" / name
        d.mkdir(parents=True)
        for fn, body in (files or {"brief.md": "x"}).items():
            (d / fn).write_text(body, encoding="utf-8")
        old = time.time() - age_days * 86400
        os.utime(d, (old, old))
        return d
    mk("keepref-20260101")                                   # referenced -> keep
    mk("donemark-20260101", {"DONE.md": "done"})             # marker -> move
    mk("statusdone-20260101", {"brief.md": "x\nSTATUS: done\n"})
    mk("staleold-20260101", age_days=20)                     # old unref -> move
    mk("freshnew-20260926", age_days=1)                      # fresh -> keep
    mk("structlib", {"a.md": "x"})                           # undated -> keep
    mk("pend-20260101", {"ROOM_BLURB.md": "수신: Clean"})    # pending -> keep
    mk("inflight-20260101")                                  # journal -> keep
    mk("journalclosed-20260101")                             # journal closed -> move
    # journals
    jdir = root / "data" / "agent-handoff" / "codex-autonomy"
    for tid, status, purpose in [
            ("inflight-aaaa1111", "in_progress",
             "work on agent-prompts/inflight-20260101"),
            ("journalclosed-bbbb2222", "closed",
             "finished agent-prompts/journalclosed-20260101")]:
        td = jdir / tid
        td.mkdir(parents=True)
        (td / "journal.json").write_text(json.dumps(
            {"taskId": tid, "status": status, "agent": "x",
             "purpose": purpose, "events": []}), encoding="utf-8")
    # empty leftovers
    (root / "ui-debug-x").mkdir()
    d = root / "ui-debug-y"
    d.mkdir()
    (d / "f.txt").write_text("x", encoding="utf-8")
    # protected content
    (root / "scripts").mkdir(exist_ok=True)
    (root / "scripts" / "keep.ps1").write_text("x", encoding="utf-8")
    return root


class OpsSurfaceThinFixtureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ostp-"))
        self.root = self.tmp / "root"
        make_fixture(self.root)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_whatif(self, *extra):
        before = manifests(self.root)
        proc = run_ps(self.root, *extra)
        self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)
        path, data = latest_manifest(self.root, before)
        self.assertIsNotNone(path, "manifest not written")
        return data

    def test_whatif_moves_nothing(self):
        before_tree = tree_listing(self.root)
        self.run_whatif()
        self.assertEqual(tree_listing(self.root), before_tree)

    def test_targets_keep_recent(self):
        data = self.run_whatif()
        moved_from = {e["from"] for e in data["wouldMove"]}
        kept = {e["path"] for e in data["kept"]}
        self.assertIn("targets.json", kept)
        self.assertIn("targets-b.json", kept)   # newest
        self.assertIn("targets-a.json", kept)   # 2nd newest
        self.assertIn("targets-c.json", moved_from)
        self.assertNotIn("targets.json", moved_from)

    def test_dir_classification(self):
        data = self.run_whatif()
        moved = {e["from"]: e for e in data["wouldMove"]}
        kept = {e["path"]: e for e in data["kept"]}
        ap = "agent-prompts/"
        for d in ("donemark-20260101", "statusdone-20260101",
                  "staleold-20260101", "journalclosed-20260101"):
            self.assertIn(ap + d, moved, d)
            self.assertEqual(moved[ap + d]["reason"], "closed-directive")
        self.assertEqual(kept[ap + "keepref-20260101"]["reason"], "referenced")
        self.assertEqual(kept[ap + "inflight-20260101"]["reason"], "in-flight")
        self.assertEqual(kept[ap + "pend-20260101"]["reason"], "pending-recipient")
        self.assertEqual(kept[ap + "freshnew-20260926"]["reason"], "ambiguous")
        self.assertEqual(kept[ap + "structlib"]["reason"], "structural")

    def test_no_deny_path_ever_moved(self):
        for extra in ([], ["-IncludeEmptyLeftovers"], ["-Apply"]):
            before = manifests(self.root)
            proc = run_ps(self.root, *extra)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            _, data = latest_manifest(self.root, before)
            entries = data["wouldMove"] + data["moved"]
            for e in entries:
                for p in DENY_PREFIXES:
                    self.assertFalse(e["from"].startswith(p), e["from"])

    def test_empty_leftovers_opt_in(self):
        data = self.run_whatif()
        self.assertNotIn("ui-debug-x",
                         {e["from"] for e in data["wouldMove"]})
        data = self.run_whatif("-IncludeEmptyLeftovers")
        moved = {e["from"] for e in data["wouldMove"]}
        self.assertIn("ui-debug-x", moved)
        self.assertNotIn("ui-debug-y", moved)

    def test_apply_moves_and_writes_index(self):
        proc = run_ps(self.root, "-Apply")
        self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)
        ap = self.root / "agent-prompts"
        self.assertFalse((ap / "donemark-20260101").exists())
        self.assertTrue((ap / "_archive").is_dir())
        arch = list((ap / "_archive").glob("*/donemark-20260101"))
        self.assertTrue(arch, "moved dir missing under _archive")
        self.assertTrue((ap / "keepref-20260101").exists())
        self.assertTrue((ap / "inflight-20260101").exists())
        self.assertTrue((self.root / "targets-c.json").exists() is False)
        self.assertTrue((self.root / "targets.json").exists())
        idx = ap / "INDEX.md"
        self.assertTrue(idx.exists())
        text = idx.read_text(encoding="utf-8")
        self.assertIn("## Live", text)
        self.assertIn("## Archived", text)
        self.assertIn("donemark-20260101", text)


class OpsSurfaceThinLiveWhatIfTests(unittest.TestCase):
    def test_live_whatif_writes_manifest_and_changes_nothing(self):
        live = ROOT
        before_tree = tree_listing(live / "agent-prompts")
        before_root_targets = sorted(p.name for p in live.glob("targets*.json"))
        before = manifests(live)
        proc = run_ps(live)
        self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)
        path, data = latest_manifest(live, before)
        self.assertIsNotNone(path, "live manifest not written")
        self.assertEqual(data["mode"], "whatif")
        self.assertEqual(tree_listing(live / "agent-prompts"), before_tree)
        self.assertEqual(sorted(p.name for p in live.glob("targets*.json")),
                         before_root_targets)
        # referenced live dirs are never wouldMove
        moved_from = {e["from"] for e in data["wouldMove"]}
        self.assertNotIn("agent-prompts/nova-focus", moved_from)
        self.assertNotIn("agent-prompts/_archive", moved_from)


if __name__ == "__main__":
    unittest.main()
