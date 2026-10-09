"""Heartbeat-sidecar lease lifetime: Python must match Get-AwxLeaseLifetime.

A heartbeat renewal writes ONLY __patch_drop__/source-edit-heartbeats/
<leaseId>.json; lease.json bytes (and its fingerprint) stay fixed. Python
readers must merge a VALID sidecar - leaseId match, leaseFingerprint ==
sha256(lease.json bytes), renewedAtUtc <= now+30s, renewedAtUtc < until <=
renewedAtUtc+540min - and must never let an invalid sidecar extend a lease.

Synthetic temp roots only; no real lease is touched.
"""
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from scripts.test_codex_work_checkpoint import CP, decision

HERE = Path(__file__).resolve().parent


def _load(name):
    script = HERE / name
    if not script.exists():
        return None
    spec = importlib.util.spec_from_file_location(script.stem, script)
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault(script.stem, module)
    spec.loader.exec_module(module)
    return module


LLT = _load("lease_lifetime.py")
CAU = _load("codex_auto_unblock.py")
DOCTOR = _load("checkpoint_doctor.py")

LEASE_ID = "0123456789abcdef0123456789abcdef"
NOW = datetime.now(timezone.utc)
PS_O = "%Y-%m-%dT%H:%M:%S"  # helper for building PS 'o'-style strings


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat()


def ps_o(dt):
    # PowerShell round-trip format: 7 fractional digits, +00:00 offset.
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + \
        f"{dt.microsecond:06d}0+00:00"


def write_lease(root, lease_id=LEASE_ID, expires=None, targets=("docs/a.md",),
                name="sidecar"):
    root = Path(root)
    lock = root / "__patch_drop__" / "source-edit-locks" / (name + ".lock")
    lock.mkdir(parents=True, exist_ok=True)
    doc = {"schemaVersion": "awx.source_edit_session.lease.v1",
           "leaseId": lease_id, "ownerId": "devin-test", "topic": name,
           "root": str(root.resolve()), "mutationAllowed": True,
           "coordinationMode": "target-scoped",
           "targetPaths": [str(t).replace("\\", "/") for t in targets],
           "expiresAtUtc": expires if expires is not None
           else iso(datetime.now(timezone.utc) + timedelta(hours=1)),
           "status": "active"}
    path = lock / "lease.json"
    path.write_text(json.dumps(doc), encoding="utf-8")
    return path, doc


def lease_fp(lease_path):
    return hashlib.sha256(Path(lease_path).read_bytes()).hexdigest()


def write_heartbeat(root, lease_id=LEASE_ID, *, renewed=None, until=None,
                    fingerprint=None, hb_lease_id=None, raw=None,
                    target_name=None):
    """renewed/until may be datetime or ready text; raw writes bytes verbatim."""
    heartbeats = Path(root) / "__patch_drop__" / "source-edit-heartbeats"
    heartbeats.mkdir(parents=True, exist_ok=True)
    path = heartbeats / ((target_name or lease_id) + ".json")
    if raw is not None:
        path.write_bytes(raw)
        return path
    doc = {"leaseId": hb_lease_id if hb_lease_id is not None else lease_id,
           "leaseFingerprint": fingerprint or "",
           "renewedAtUtc": renewed if isinstance(renewed, str) else iso(renewed),
           "expiresAtUtc": until if isinstance(until, str) else iso(until)}
    path.write_text(json.dumps(doc), encoding="utf-8")
    return path


def valid_heartbeat(root, lease_path, lease_id=LEASE_ID, renewed=None, until=None):
    renewed = renewed or datetime.now(timezone.utc) - timedelta(minutes=2)
    until = until or datetime.now(timezone.utc) + timedelta(hours=2)
    return write_heartbeat(root, lease_id, renewed=renewed, until=until,
                           fingerprint=lease_fp(lease_path))


def ref_for(lease_path, root, doc):
    # Same ref shape codex_work_checkpoint.begin stores in manifest["lease"].
    return {"path": Path(lease_path).relative_to(root).as_posix(),
            "sha256": CP.digest(Path(lease_path).read_bytes()),
            "leaseId": doc.get("leaseId"), "ownerId": doc.get("ownerId"),
            "targetPaths": sorted(str(p).replace("\\", "/").casefold()
                                  for p in doc.get("targetPaths") or [])}


def manifest_for(lease_path, root, doc, target="docs/a.md"):
    return {"targets": [{"path": target}], "lease": ref_for(lease_path, root, doc)}


def expired_lease_with(root, sidecar=True, **hb_kw):
    """Expired lease.json + (optionally) a valid sidecar; returns (path, doc, manifest)."""
    (Path(root) / "docs").mkdir(exist_ok=True)
    (Path(root) / "docs" / "a.md").write_text("a\n", encoding="utf-8")
    path, doc = write_lease(root, expires=iso(NOW - timedelta(hours=1)), **{
        k: v for k, v in hb_kw.items() if k in ("lease_id", "name")})
    if sidecar:
        valid_heartbeat(root, path, doc["leaseId"],
                        **{k: v for k, v in hb_kw.items()
                           if k in ("renewed", "until")})
    return path, doc, manifest_for(path, Path(root), doc)


class SidecarBoundaryTest(unittest.TestCase):
    """W4 boundary matrix through the real lease_check gate."""

    def test_valid_sidecar_extends_expired_lease(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path, doc, manifest = expired_lease_with(root)
            CP.lease_check(root, manifest)  # must not raise

    def test_no_sidecar_expired_lease_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td, sidecar=False)
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_sidecar_lease_id_mismatch_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td)
            hb = Path(td) / "__patch_drop__" / "source-edit-heartbeats" / (LEASE_ID + ".json")
            data = json.loads(hb.read_text())
            data["leaseId"] = "f" * 32
            hb.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_sidecar_fingerprint_mismatch_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td)
            hb = Path(td) / "__patch_drop__" / "source-edit-heartbeats" / (LEASE_ID + ".json")
            data = json.loads(hb.read_text())
            data["leaseFingerprint"] = "0" * 64
            hb.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_lease_byte_change_breaks_fingerprint_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td)
            # lease.json rewritten after sidecar was issued -> fingerprint stale.
            doc["heartbeatNote"] = "x"
            path.write_text(json.dumps(doc), encoding="utf-8")
            manifest = manifest_for(path, Path(td), doc)
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_renewed_31s_in_future_refused(self):
        with tempfile.TemporaryDirectory() as td:
            future = datetime.now(timezone.utc) + timedelta(seconds=31)
            path, doc, manifest = expired_lease_with(td)
            write_heartbeat(td, renewed=iso(future),
                            until=iso(future + timedelta(hours=1)),
                            fingerprint=lease_fp(path))
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_renewed_29s_in_future_accepted(self):
        with tempfile.TemporaryDirectory() as td:
            future = datetime.now(timezone.utc) + timedelta(seconds=29)
            path, doc, manifest = expired_lease_with(td)
            write_heartbeat(td, renewed=iso(future),
                            until=iso(future + timedelta(hours=1)),
                            fingerprint=lease_fp(path))
            CP.lease_check(Path(td), manifest)

    def test_until_equal_renewed_refused(self):
        with tempfile.TemporaryDirectory() as td:
            moment = datetime.now(timezone.utc) - timedelta(minutes=1)
            path, doc, manifest = expired_lease_with(td)
            write_heartbeat(td, renewed=iso(moment), until=iso(moment),
                            fingerprint=lease_fp(path))
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_until_540min_exact_accepted(self):
        with tempfile.TemporaryDirectory() as td:
            renewed = datetime.now(timezone.utc) - timedelta(minutes=1)
            path, doc, manifest = expired_lease_with(td)
            write_heartbeat(td, renewed=iso(renewed),
                            until=iso(renewed + timedelta(minutes=540)),
                            fingerprint=lease_fp(path))
            CP.lease_check(Path(td), manifest)

    def test_until_540min_plus_1s_refused(self):
        with tempfile.TemporaryDirectory() as td:
            renewed = datetime.now(timezone.utc) - timedelta(minutes=1)
            path, doc, manifest = expired_lease_with(td)
            write_heartbeat(td, renewed=iso(renewed),
                            until=iso(renewed + timedelta(minutes=540, seconds=1)),
                            fingerprint=lease_fp(path))
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_z_suffix_and_offset_equivalent(self):
        with tempfile.TemporaryDirectory() as td:
            renewed = datetime.now(timezone.utc) - timedelta(minutes=1)
            path, doc, manifest = expired_lease_with(td)
            z = lambda d: iso(d).replace("+00:00", "Z")
            write_heartbeat(td, renewed=z(renewed), until=z(renewed + timedelta(hours=1)),
                            fingerprint=lease_fp(path))
            CP.lease_check(Path(td), manifest)

    def test_ps_seven_digit_fraction_accepted(self):
        with tempfile.TemporaryDirectory() as td:
            renewed = datetime.now(timezone.utc) - timedelta(minutes=1)
            path, doc, manifest = expired_lease_with(td)
            write_heartbeat(td, renewed=ps_o(renewed),
                            until=ps_o(renewed + timedelta(hours=1)),
                            fingerprint=lease_fp(path))
            CP.lease_check(Path(td), manifest)

    def test_broken_json_sidecar_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td, sidecar=False)
            write_heartbeat(td, raw=b"{not json")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_sidecar_directory_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td, sidecar=False)
            heartbeats = Path(td) / "__patch_drop__" / "source-edit-heartbeats"
            (heartbeats / (LEASE_ID + ".json")).mkdir(parents=True)
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_sidecar_symlink_refused(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc, manifest = expired_lease_with(td, sidecar=False)
            heartbeats = Path(td) / "__patch_drop__" / "source-edit-heartbeats"
            heartbeats.mkdir(parents=True)
            real = heartbeats / "real"
            real.mkdir()
            (real / "data.txt").write_text("x", encoding="utf-8")
            link = heartbeats / (LEASE_ID + ".json")
            try:
                link.symlink_to(real, target_is_directory=True)
            except (OSError, NotImplementedError):
                # No symlink privilege: a junction also carries
                # FILE_ATTRIBUTE_REPARSE_POINT and needs no admin.
                import subprocess
                proc = subprocess.run(
                    ["cmd", "/c", "mklink", "/J", str(link), str(real)],
                    capture_output=True)
                if proc.returncode != 0:
                    self.skipTest("no symlink or junction privilege")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(td), manifest)

    def test_valid_lease_shorter_sidecar_never_shrinks(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docs").mkdir()
            (root / "docs" / "a.md").write_text("a\n")
            expires = NOW + timedelta(hours=4)
            path, doc = write_lease(root, expires=iso(expires))
            write_heartbeat(root, renewed=iso(NOW - timedelta(minutes=1)),
                            until=iso(NOW + timedelta(minutes=30)),
                            fingerprint=lease_fp(path))
            CP.lease_check(root, manifest_for(path, root, doc))

    def test_non_hex_lease_id_never_reads_sidecar(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docs").mkdir()
            (root / "docs" / "a.md").write_text("a\n")
            path, doc = write_lease(root, lease_id="lease-1",
                                    expires=iso(NOW - timedelta(hours=1)))
            # A sidecar file exists under a guessed name; leaseId is not hex32
            # so the reader must ignore it (PS: early return -> absent).
            write_heartbeat(root, lease_id=LEASE_ID, target_name="lease-1",
                            renewed=iso(NOW), until=iso(NOW + timedelta(hours=1)),
                            fingerprint=lease_fp(path), hb_lease_id="lease-1")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(root, manifest_for(path, root, doc))

    def test_unparseable_lease_expiry_not_rescued(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docs").mkdir()
            (root / "docs" / "a.md").write_text("a\n")
            path, doc = write_lease(root, expires="not-a-date")
            valid_heartbeat(root, path)
            # PS summary marks expiry-invalid -> corrupt, never extended.
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(root, manifest_for(path, root, doc))


class SidecarLifecycleTest(unittest.TestCase):
    """W1 RED: begin/seal/finish must accept a heartbeat-extended lease."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "docs").mkdir()
        self.file = self.root / "docs" / "a.md"
        self.file.write_text("a\n", encoding="utf-8")
        self.run = "data/agent-handoff/codex-autonomy/hb-cycle"
        self.lease_rel = "__patch_drop__/source-edit-locks/sidecar.lock/lease.json"

    def begin_with_expired(self):
        path, doc = write_lease(self.root, expires=iso(NOW - timedelta(minutes=5)))
        valid_heartbeat(self.root, path)
        return CP.begin(self.root, self.run, ["docs/a.md"], decision(), self.lease_rel)

    def begin_then_expire(self):
        path, doc = write_lease(self.root)
        state = CP.begin(self.root, self.run, ["docs/a.md"], decision(), self.lease_rel)
        self.assertEqual("prepared", state["status"])
        # lease.json expires naturally mid-cycle; heartbeat keeps it alive via sidecar
        doc["expiresAtUtc"] = iso(NOW - timedelta(minutes=2))
        path.write_text(json.dumps(doc), encoding="utf-8")
        write_heartbeat(self.root, renewed=iso(NOW), until=iso(NOW + timedelta(hours=2)),
                        fingerprint=lease_fp(path))
        return path, doc

    def test_begin_accepts_heartbeat_extended_lease(self):
        state = self.begin_with_expired()
        self.assertEqual("prepared", state["status"])

    def test_seal_accepts_heartbeat_extended_lease(self):
        self.begin_then_expire()
        self.file.write_text("a2\n", encoding="utf-8")
        state = CP.seal(self.root, self.run)
        self.assertEqual("sealed", state["status"])

    def test_finish_verified_with_heartbeat_extended_lease(self):
        self.begin_then_expire()
        self.file.write_text("a2\n", encoding="utf-8")
        CP.seal(self.root, self.run)
        state = CP.finish(self.root, self.run, 0, "focused-unit-test")
        self.assertEqual("verified", state["status"])

    def test_invalid_sidecar_still_blocks_seal(self):
        path, doc = self.begin_then_expire()
        hb = self.root / "__patch_drop__" / "source-edit-heartbeats" / (LEASE_ID + ".json")
        data = json.loads(hb.read_text())
        data["leaseFingerprint"] = "0" * 64
        hb.write_text(json.dumps(data), encoding="utf-8")
        self.file.write_text("a2\n", encoding="utf-8")
        with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
            CP.seal(self.root, self.run)

    def test_finish_allow_released_path_unchanged(self):
        path, doc = self.begin_then_expire()
        self.file.write_text("a2\n", encoding="utf-8")
        CP.seal(self.root, self.run)
        # Owner released normally: lease.json gone, release event recorded.
        path.unlink()
        events = self.root / "__patch_drop__" / "source-edit-events"
        events.mkdir(parents=True)
        (events / (LEASE_ID + ".jsonl")).write_text(
            json.dumps({"event": "release", "leaseId": LEASE_ID}) + "\n",
            encoding="utf-8")
        state = CP.finish(self.root, self.run, 0, "focused-unit-test")
        self.assertEqual("verified", state["status"])


@unittest.skipUnless(LLT, "shared helper not yet present")
class LeaseLifetimeHelperTest(unittest.TestCase):
    """Direct contract of scripts/lease_lifetime.py (PS Get-AwxLeaseLifetime)."""

    def test_valid_sidecar_reports_effective(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path, doc = write_lease(root, expires=iso(NOW - timedelta(hours=1)))
            until = NOW + timedelta(hours=2)
            valid_heartbeat(root, path, until=until)
            lt = LLT.lifetime(root / "__patch_drop__" / "source-edit-heartbeats",
                              doc, path.read_bytes())
            self.assertEqual("valid", lt["heartbeatState"])
            self.assertAlmostEqual(until.timestamp(),
                                   lt["effective"].timestamp(), delta=1.0)

    def test_absent_sidecar_keeps_lease_expiry(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc = write_lease(td)
            lt = LLT.lifetime(Path(td) / "__patch_drop__" / "source-edit-heartbeats",
                              doc, path.read_bytes())
            self.assertEqual("absent", lt["heartbeatState"])
            self.assertEqual(lt["expires"], lt["effective"])

    def test_invalid_state_never_extends(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            expires = NOW - timedelta(hours=1)
            path, doc = write_lease(root, expires=iso(expires))
            write_heartbeat(root, fingerprint="0" * 64, renewed=iso(NOW),
                            until=iso(NOW + timedelta(hours=2)))
            lt = LLT.lifetime(root / "__patch_drop__" / "source-edit-heartbeats",
                              doc, path.read_bytes())
            self.assertEqual("invalid", lt["heartbeatState"])
            self.assertAlmostEqual(expires.timestamp(), lt["effective"].timestamp(), delta=1.0)

    def test_missing_lease_bytes_means_unverifiable(self):
        with tempfile.TemporaryDirectory() as td:
            path, doc = write_lease(td)
            lt = LLT.lifetime(Path(td) / "__patch_drop__" / "source-edit-heartbeats",
                              doc, None)
            self.assertEqual("absent", lt["heartbeatState"])

    def test_lease_expiry_missing_sidecar_still_visible(self):
        # Raw Get-AwxLeaseLifetime semantics: unparseable base expiry leaves
        # MinValue, so a valid sidecar still produces effectiveExpiresAtUtc.
        # Consumers that require a usable lease check `expires` separately.
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path, doc = write_lease(root, expires="not-a-date")
            until = NOW + timedelta(hours=2)
            valid_heartbeat(root, path, until=until)
            lt = LLT.lifetime(root / "__patch_drop__" / "source-edit-heartbeats",
                              doc, path.read_bytes())
            self.assertIsNone(lt["expires"])
            self.assertAlmostEqual(until.timestamp(), lt["effective"].timestamp(), delta=1.0)


@unittest.skipUnless(CAU, "codex_auto_unblock unavailable")
class AutoUnblockHeartbeatTest(unittest.TestCase):
    """W6: lease_scan must keep a heartbeat-extended lease live."""

    def root(self, td):
        root = Path(td)
        (root / "docs").mkdir(exist_ok=True)
        (root / "docs" / "a.md").write_text("a\n", encoding="utf-8")
        return root

    def locks(self, root):
        return root / "__patch_drop__" / "source-edit-locks"

    def test_heartbeat_extended_lease_is_live(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.root(td)
            path, doc = write_lease(root, expires=iso(NOW - timedelta(minutes=5)))
            valid_heartbeat(root, path)
            rep = CAU.lease_scan(self.locks(root), ["docs/a.md"])
            self.assertEqual("live", rep["result"])
            self.assertEqual("valid", rep["live"][0].get("heartbeatState"))

    def test_invalid_sidecar_lease_is_stale(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.root(td)
            path, doc = write_lease(root, expires=iso(NOW - timedelta(minutes=5)))
            write_heartbeat(root, fingerprint="0" * 64, renewed=iso(NOW),
                            until=iso(NOW + timedelta(hours=1)))
            rep = CAU.lease_scan(self.locks(root), ["docs/a.md"])
            self.assertEqual("stale", rep["result"])

    def test_no_sidecar_expired_is_stale(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.root(td)
            write_lease(root, expires=iso(NOW - timedelta(minutes=5)))
            rep = CAU.lease_scan(self.locks(root), ["docs/a.md"])
            self.assertEqual("stale", rep["result"])

    def test_lease_wait_dry_run_waits_on_heartbeat_lease(self):
        with tempfile.TemporaryDirectory() as td:
            root = self.root(td)
            path, doc = write_lease(root, expires=iso(NOW - timedelta(minutes=5)))
            valid_heartbeat(root, path)
            rep = CAU.lease_wait(["docs/a.md"], self.locks(root), max_min="auto",
                                 dry_run=True, authoritative=False)
            self.assertEqual("live", rep["result"])
            self.assertEqual(1200, rep["budgetSec"])


@unittest.skipUnless(DOCTOR and LLT, "doctor or helper unavailable")
class DoctorHeartbeatTest(unittest.TestCase):
    """W7: checkpoint_doctor reports effective expiry and honest hints."""

    def test_load_lease_merges_valid_sidecar(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path, doc = write_lease(root, expires=iso(NOW - timedelta(minutes=5)))
            until = NOW + timedelta(hours=2)
            valid_heartbeat(root, path, until=until)
            info = DOCTOR.load_lease(root, {"path": path.relative_to(root).as_posix()})
            self.assertEqual("valid", info["heartbeatState"])
            self.assertIn("effectiveExpiresAtUtc", info)
            self.assertGreater(info["secondsToExpiry"], 3600)

    def test_load_lease_invalid_sidecar_stays_expired(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path, doc = write_lease(root, expires=iso(NOW - timedelta(minutes=5)))
            write_heartbeat(root, fingerprint="0" * 64, renewed=iso(NOW),
                            until=iso(NOW + timedelta(hours=1)))
            info = DOCTOR.load_lease(root, {"path": path.relative_to(root).as_posix()})
            self.assertEqual("invalid", info["heartbeatState"])
            self.assertLess(info["secondsToExpiry"], 0)

    def test_expired_hint_mentions_heartbeat_when_valid(self):
        state = {"status": "hold", "firstBlockingRule": "source-lease-expired"}
        hint = DOCTOR.next_hint(state, {"secondsToExpiry": 300, "heartbeatState": "valid"})
        self.assertIn("heartbeat", hint.lower())

    def test_drift_hint_no_wrong_heartbeat_model(self):
        state = {"status": "hold", "firstBlockingRule": "source-lease-drift"}
        hint = DOCTOR.next_hint(state, None)
        self.assertNotIn("renews expiresAtUtc", hint)


if __name__ == "__main__":
    unittest.main()
