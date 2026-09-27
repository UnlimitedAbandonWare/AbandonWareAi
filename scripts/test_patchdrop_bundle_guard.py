import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "patchdrop_bundle_guard.py"

PATCH = ("diff --git a/main/java/X.java b/main/java/X.java\n"
         "--- a/main/java/X.java\n+++ b/main/java/X.java\n"
         "@@ -1 +1 @@\n-old\n+new\n")


def run(*args):
    proc = subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=60)
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


def make_bundle(drop: Path, slug="topic-a", node="desktop",
                schema="patchdrop-producer-v3", patch_text=PATCH,
                files=None):
    node_dir = drop / node
    node_dir.mkdir(parents=True, exist_ok=True)
    parts = {}
    patch = node_dir / f"{slug}.patch"
    patch.write_text(patch_text, encoding="utf-8")
    parts["patch"] = patch
    for ext, body in ((".report.md", "report"), (".verify.log", "verify")):
        p = node_dir / f"{slug}{ext}"
        p.write_text(body, encoding="utf-8")
        parts[ext] = p
    manifest = {"schemaVersion": schema, "snapshotMode": "worktree",
                "files": files if files is not None
                else [{"path": "main/java/X.java", "headOid": "0" * 40,
                       "worktreeSha256": "1" * 64}],
                "activePatch": f"{slug}.patch", "emptyPatch": False}
    mpath = node_dir / f"{slug}.manifest.json"
    mpath.write_text(json.dumps(manifest), encoding="utf-8")
    sha = node_dir / f"{slug}.sha256.txt"
    sha.write_text("\n".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}"
        for p in (parts["patch"], parts[".report.md"], parts[".verify.log"],
                  mpath)), encoding="utf-8")
    return mpath


def make_inventory(tmp: Path, slug="topic-a", status="READY") -> Path:
    inv = tmp / "janitor-out.txt"
    inv.write_text(f"[janitor][inventory] topic={slug} status={status} "
                   "missing=\n", encoding="utf-8")
    return inv


def make_policy(tmp: Path, **over) -> Path:
    policy = {"schemaVersion": "awx.desktop-patchdrop-auto-intake.policy.v1",
              "allowedPathPrefixes": ["main/java", "main/resources",
                                      "scripts"],
              "allowedTopics": [], "allowedNodes": [],
              "maxPatchBytes": 1048576, "maxChangedFiles": 20,
              "maxHunks": 100}
    policy.update(over)
    path = tmp / "policy.json"
    path.write_text(json.dumps(policy), encoding="utf-8")
    return path


class PatchdropBundleGuardTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.drop = self.tmp / "__patch_drop__"

    def tearDown(self):
        self._tmp.cleanup()

    def test_apply_ok(self):
        bundle = make_bundle(self.drop)
        inv = make_inventory(self.tmp)
        policy = make_policy(self.tmp)
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop),
                        "--policy", str(policy),
                        "--inventory-file", str(inv))
        self.assertEqual(code, 0, out)
        self.assertEqual(out["verdict"], "apply-ok")
        self.assertEqual(out["checks"]["inventory"], "READY")

    def test_inventory_required_blocks_apply(self):
        bundle = make_bundle(self.drop)
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop))
        self.assertEqual(code, 4)
        self.assertEqual(out["verdict"], "hold:inventory-required")

    def test_inventory_not_ready(self):
        bundle = make_bundle(self.drop)
        inv = make_inventory(self.tmp, status="MISSING_META")
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop),
                        "--inventory-file", str(inv))
        self.assertEqual(code, 4)
        self.assertEqual(out["verdict"], "hold:inventory-not-ready")

    def test_integrity_reject_and_move(self):
        bundle = make_bundle(self.drop)
        inv = make_inventory(self.tmp)
        (self.drop / "desktop" / "topic-a.patch").write_text(
            PATCH + "tampered\n", encoding="utf-8")
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop),
                        "--inventory-file", str(inv), "--apply-moves")
        self.assertEqual(code, 5, out)
        self.assertEqual(out["verdict"], "reject:integrity")
        self.assertTrue((self.drop / "rejected" / "topic-a.patch").is_file())
        self.assertFalse((self.drop / "desktop" / "topic-a.patch").exists())

    def test_whitelist_reject(self):
        bundle = make_bundle(
            self.drop, files=[{"path": ".secrets/providers.json"}])
        inv = make_inventory(self.tmp)
        policy = make_policy(self.tmp)
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop),
                        "--policy", str(policy),
                        "--inventory-file", str(inv))
        self.assertEqual(code, 5)
        self.assertEqual(out["verdict"], "reject:whitelist")

    def test_threshold_auto_abort(self):
        bundle = make_bundle(self.drop)
        inv = make_inventory(self.tmp)
        policy = make_policy(self.tmp, maxChangedFiles=0)
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop),
                        "--policy", str(policy),
                        "--inventory-file", str(inv))
        self.assertEqual(code, 4)
        self.assertEqual(out["verdict"], "hold:threshold-exceeded")

    def test_schema_v3_only(self):
        bundle = make_bundle(self.drop, schema="patchdrop-producer-v2")
        inv = make_inventory(self.tmp)
        code, out = run("check", "--bundle", str(bundle),
                        "--patchdrop-root", str(self.drop),
                        "--inventory-file", str(inv))
        self.assertEqual(code, 5)
        self.assertEqual(out["verdict"], "reject:schema-not-v3")

    def test_duplicate_slug_hold(self):
        make_bundle(self.drop)
        other = make_bundle(self.drop, node="macmini")
        inv = make_inventory(self.tmp)
        code, out = run("check", "--bundle", str(other),
                        "--patchdrop-root", str(self.drop),
                        "--inventory-file", str(inv))
        self.assertEqual(code, 4)
        self.assertEqual(out["verdict"], "hold:duplicate-slug")

    def test_queue_classification(self):
        pending = self.drop / "patch-drop-pending"
        pending.mkdir(parents=True)
        (pending / "x.patch").write_text("p", encoding="utf-8")
        (pending / "y.weird").write_text("?", encoding="utf-8")
        code, out = run("queue", "--patchdrop-root", str(self.drop))
        self.assertEqual(code, 0)
        self.assertEqual(out["queueCount"], 2)
        actions = {e["file"]: e["action"] for e in out["entries"]}
        self.assertEqual(actions["x.patch"], "intake-check")
        self.assertEqual(actions["y.weird"], "review-manually")


if __name__ == "__main__":
    unittest.main()
