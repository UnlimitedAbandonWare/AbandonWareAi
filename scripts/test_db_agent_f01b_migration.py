"""Authorization stays explicit; only the two pinned local F01 base migrations."""
import contextlib
import hashlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import db_agent as db


class F01BaseMigrationGateTest(unittest.TestCase):
    def args(self, **overrides):
        migration = db.ROOT / "main/resources/db/migration/V20260912_03__job_idempotency.sql"
        values = dict(file=str(migration), allow_tables=["awx_jobs,awx_job_results"],
                      approved_migration_sha256=hashlib.sha256(migration.read_bytes()).hexdigest(),
                      db_path=str(db.DEFAULT_DB_BASE), dry_run=True, i_mean_it=False, pretty=False)
        values.update(overrides)
        return SimpleNamespace(**values)

    def invoke(self, args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), patch.object(db, "require_ready") as ready:
            code = db.cmd_apply(args)
            ready.assert_not_called()
        return code, json.loads(out.getvalue())

    def test_exact_approved_additive_migration_is_planned_without_db_access(self):
        code, result = self.invoke(self.args())
        self.assertEqual(0, code)
        self.assertTrue(result["wouldApply"])
        self.assertEqual(3, result["statementCount"])

    def test_default_apply_still_denies_alter(self):
        code, result = self.invoke(self.args(approved_migration_sha256=None))
        self.assertEqual(5, code)
        self.assertFalse(result["wouldApply"])

    def test_separately_approved_receipt_proposal_requires_its_table_allowlist(self):
        migration = db.ROOT / "docs/diagnostics/f01b-understanding-receipt-proposal.sql"
        args = self.args(file=str(migration),
                        approved_migration_sha256=hashlib.sha256(migration.read_bytes()).hexdigest())
        code, result = self.invoke(args)
        self.assertEqual("approved-migration-table-allowlist-required", result["reason"])
        self.assertNotEqual(0, code)
        args.allow_tables = ["awx_jobs,awx_job_results,awx_understanding_receipts"]
        code, result = self.invoke(args)
        self.assertEqual(0, code)
        self.assertTrue(result["wouldApply"])
        self.assertEqual(10, result["statementCount"])

    def test_wrong_hash_is_denied(self):
        code, result = self.invoke(self.args(approved_migration_sha256="0" * 64))
        self.assertEqual("approved-migration-identity-mismatch", result["reason"])
        self.assertNotEqual(0, code)

    def test_noncanonical_database_is_denied(self):
        code, result = self.invoke(self.args(db_path=str(db.ROOT / "var/shared/lmsdb")))
        self.assertEqual("approved-migration-local-store-required", result["reason"])
        self.assertNotEqual(0, code)

    def test_missing_explicit_local_database_is_denied(self):
        with patch.dict(os.environ, {"LMS_DB_URL": "jdbc:postgresql://example.invalid/shared"}):
            code, result = self.invoke(self.args(db_path=None))
        self.assertEqual("approved-migration-local-store-required", result["reason"])
        self.assertNotEqual(0, code)

    def test_copy_or_modified_sql_is_not_an_approved_migration(self):
        with tempfile.TemporaryDirectory() as directory:
            sql = Path(directory) / "V20260912_03__job_idempotency.sql"
            sql.write_text("ALTER TABLE awx_jobs DROP COLUMN payload;", encoding="utf-8")
            code, result = self.invoke(self.args(file=str(sql),
                approved_migration_sha256=hashlib.sha256(sql.read_bytes()).hexdigest()))
        self.assertEqual("approved-migration-identity-mismatch", result["reason"])
        self.assertNotEqual(0, code)


if __name__ == "__main__":
    unittest.main()
