import importlib.util
import io
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
TOOLBOX_PATH = ROOT / "scripts" / "awx_mcp_toolbox.py"
SPEC = importlib.util.spec_from_file_location("awx_mcp_toolbox_input_json", TOOLBOX_PATH)
toolbox = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(toolbox)


class LoadPayloadTest(unittest.TestCase):
    def test_powershell_utf8_bom_stdin_payload_is_accepted(self):
        payload = b'\xef\xbb\xbf{"nodeRole":"desktop","root":"."}'
        stdin = io.TextIOWrapper(
            io.BytesIO(payload),
            encoding="cp949",
            errors="surrogateescape",
        )

        with mock.patch.object(toolbox.sys, "stdin", stdin):
            parsed = toolbox.load_payload("-")

        self.assertEqual({"nodeRole": "desktop", "root": "."}, parsed)


if __name__ == "__main__":
    unittest.main()
