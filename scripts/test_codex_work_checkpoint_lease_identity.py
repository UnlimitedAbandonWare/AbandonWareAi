"""Lease-identity drift + yaml placeholder scanner regressions.

lease_check compared the whole lease.json sha256, so a heartbeat renewal
(expiresAtUtc refresh) refused seal/finish as source-lease-drift. The check
now accepts a renewed lease only when leaseId/ownerId/targetPaths recorded
at begin still match; identity changes and missing files stay refused.
The yaml scanner accepts bare ${ENV} / ${ENV:} placeholders plus the two
known yaml defaults (${LLM_API_KEY:ollama}, ${BRAVE_API_KEY:__MISSING__});
arbitrary ${ENV:literal} defaults, real literals, and PRIVATE KEY blocks
stay blocked. Synthetic fixtures only.
"""
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from scripts.test_codex_work_checkpoint import CP


def future(days=0, hours=1):
    return (datetime.now(timezone.utc) + timedelta(days=days, hours=hours)).isoformat()


def write_lease(root, name, **kw):
    lock = Path(root) / "__patch_drop__" / "source-edit-locks" / (name + ".lock")
    lock.mkdir(parents=True, exist_ok=True)
    doc = {"schemaVersion": "awx.source_edit_session.lease.v1",
           "leaseId": kw.get("leaseId", "lease-1"),
           "ownerId": kw.get("ownerId", "devin-x"),
           "root": str(Path(root).resolve()),
           "mutationAllowed": True, "coordinationMode": "target-scoped",
           "targetPaths": kw.get("targetPaths", ["docs/a.md"]),
           "expiresAtUtc": kw.get("expiresAtUtc", future()),
           "status": "active"}
    path = lock / "lease.json"
    path.write_text(json.dumps(doc), encoding="utf-8")
    return path, doc


def ref_for(path, root, doc):
    rel = path.relative_to(root).as_posix()
    return {"path": rel, "sha256": CP.digest(path.read_bytes()),
            "leaseId": doc["leaseId"], "ownerId": doc["ownerId"],
            "targetPaths": sorted(p.casefold() for p in doc["targetPaths"])}


class LeaseIdentityDriftTest(unittest.TestCase):
    def manifest(self, root, ref):
        return {"targets": [{"path": "docs/a.md"}], "lease": ref}

    def test_heartbeat_renewal_same_identity_passes(self):
        with tempfile.TemporaryDirectory() as root:
            path, doc = write_lease(root, "renew")
            ref = ref_for(path, Path(root), doc)
            doc["expiresAtUtc"] = future(hours=5)
            doc["lastHeartbeatAtUtc"] = future(hours=0)
            path.write_text(json.dumps(doc), encoding="utf-8")
            CP.lease_check(Path(root), self.manifest(root, ref))

    def test_lease_id_change_still_refused(self):
        with tempfile.TemporaryDirectory() as root:
            path, doc = write_lease(root, "swap")
            ref = ref_for(path, Path(root), doc)
            doc["leaseId"] = "other-lease"
            path.write_text(json.dumps(doc), encoding="utf-8")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-drift"):
                CP.lease_check(Path(root), self.manifest(root, ref))

    def test_target_paths_change_still_refused(self):
        with tempfile.TemporaryDirectory() as root:
            path, doc = write_lease(root, "scope")
            ref = ref_for(path, Path(root), doc)
            doc["targetPaths"] = ["docs/a.md", "docs/evil.md"]
            path.write_text(json.dumps(doc), encoding="utf-8")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-drift"):
                CP.lease_check(Path(root), self.manifest(root, ref))

    def test_missing_lease_still_refused(self):
        with tempfile.TemporaryDirectory() as root:
            ref = {"path": "__patch_drop__/source-edit-locks/gone.lock/lease.json",
                   "sha256": CP.digest(b"{}"), "leaseId": "x"}
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-drift"):
                CP.lease_check(Path(root), self.manifest(root, ref))

    def test_expired_renewal_still_refused_expired(self):
        with tempfile.TemporaryDirectory() as root:
            path, doc = write_lease(root, "exp")
            ref = ref_for(path, Path(root), doc)
            doc["expiresAtUtc"] = "2020-01-01T00:00:00+00:00"
            path.write_text(json.dumps(doc), encoding="utf-8")
            with self.assertRaisesRegex(CP.CheckpointError, "source-lease-expired"):
                CP.lease_check(Path(root), self.manifest(root, ref))


class YamlPlaceholderScannerTest(unittest.TestCase):
    key = "api" + "-key"

    def test_env_placeholders_pass(self):
        for value in ("${OPENAI_API_KEY}", "${BRAVE_API_KEY:__MISSING__}",
                      "${LLM_API_KEY:ollama}", "${NEW_VAR_9}", "${NEW_VAR_9:}"):
            with self.subTest(value=value):
                CP.secret_free((self.key + ": " + value + "\n").encode(),
                               "configs/x.yaml")

    def test_literal_values_stay_blocked(self):
        for value in ('"sk-live-0123456789abcdef"', "plainLiteralValue123",
                      "${NEW_VAR_9:def-ault}", "${unclosed"):
            with self.subTest(value=value), self.assertRaisesRegex(
                    CP.CheckpointError, "secret-pattern"):
                CP.secret_free((self.key + ": " + value + "\n").encode(),
                               "configs/x.yaml")

    def test_private_key_block_still_blocked(self):
        body = "-----BEGIN " + "PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"
        with self.assertRaisesRegex(CP.CheckpointError, "secret-pattern"):
            CP.secret_free(body.encode(), "configs/x.yaml")

    def test_reason_carries_file_and_line_not_value(self):
        secret = "s3cr" + "et-value-123"
        body = "a: 1\n" + self.key + ": " + secret + "\n"
        try:
            CP.secret_free(body.encode(), "configs/x.yaml")
            self.fail("expected CheckpointError")
        except CP.CheckpointError as exc:
            self.assertIn("configs/x.yaml:L2", str(exc))
            self.assertNotIn(secret, str(exc))


if __name__ == "__main__":
    unittest.main()
