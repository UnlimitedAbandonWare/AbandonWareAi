"""Synthetic output-boundary checks; no environment reads or external requests."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import common
import main


class ResultRedaction(unittest.TestCase):
    secret = "synthetic-private-credential"

    def payload(self):
        return {"stamp": "synthetic", "rows": [{"provider": "naver", "cls": common.OK,
                "code": "SUBSCRIPTION_TOKEN_INVALID", "detail": "rejected " + self.secret}],
                "nested": [{"echo": self.secret}]}

    def test_saved_result_scrubs_known_values_without_mutating_input_or_codes(self):
        original = self.payload()
        with tempfile.TemporaryDirectory() as temp, patch.object(common, "OUT_DIR", Path(temp)):
            target = Path(temp) / "result.json"
            common.save_result(original, target, secret_values=(self.secret,))
            rendered = target.read_text(encoding="utf-8")
        self.assertNotIn(self.secret, rendered)
        self.assertEqual("SUBSCRIPTION_TOKEN_INVALID", json.loads(rendered)["rows"][0]["code"])
        self.assertIn(self.secret, original["rows"][0]["detail"])

    def test_cli_stdout_and_disk_share_the_sanitized_payload(self):
        rows = self.payload()["rows"]
        ctx = {"stamp": "synthetic", "secret_values": (self.secret,)}
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as temp, patch.object(common, "OUT_DIR", Path(temp)), \
                patch.object(main, "cmd_check", return_value=(rows, ctx)), contextlib.redirect_stdout(output):
            target = Path(temp) / "result.json"
            code = main.main(["check", "naver", "--json", "--out", str(target)])
            saved = target.read_text(encoding="utf-8")
        self.assertEqual(0, code)
        self.assertNotIn(self.secret, output.getvalue())
        self.assertNotIn(self.secret, saved)
        self.assertEqual(json.loads(output.getvalue()), json.loads(saved))
        self.assertEqual("SUBSCRIPTION_TOKEN_INVALID", json.loads(saved)["rows"][0]["code"])


if __name__ == "__main__":
    unittest.main()
