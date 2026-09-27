import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "agentic_chat_postprocess.py"


def run(*args):
    proc = subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                          capture_output=True, text=True, timeout=60)
    out = proc.stdout.strip()
    return proc.returncode, json.loads(out) if out else {}


def _write(tmp: Path, name: str, text: str) -> Path:
    path = tmp / name
    path.write_text(text, encoding="utf-8")
    return path


DIRTY_COMMIT = (
    "User: 지시서대로 커밋 메시지를 만들어\n"
    "> 원문 대화가 여기 남았다\n"
    "@objective-executor @demo1-work-ledger @demo1-core-request-router\n"
    "fix: chat renderer reconnect handling\n\n\n\n"
    "- receiver.js 재연결 게이트 정리\n"
    "Assistant: 알겠습니다\n"
)


class AgenticChatPostprocessTest(unittest.TestCase):
    def test_clean_commit_strips_residue(self):
        with tempfile.TemporaryDirectory() as td:
            src = _write(Path(td), "msg.txt", DIRTY_COMMIT)
            code, out = run("clean", "--input", str(src), "--kind", "commit")
            self.assertEqual(code, 0, out)
            cleaned = out["cleaned"]
            self.assertIn("fix: chat renderer reconnect handling", cleaned)
            self.assertIn("receiver.js", cleaned)
            self.assertNotIn("User:", cleaned)
            self.assertNotIn("Assistant:", cleaned)
            self.assertNotIn("@objective-executor", cleaned)
            self.assertNotIn("> 원문", cleaned)
            self.assertNotIn("\n\n\n", cleaned)
            self.assertGreaterEqual(out["removedLines"], 4)

    def test_nfc_normalization(self):
        with tempfile.TemporaryDirectory() as td:
            # NFD jamo -> NFC precomposed
            src = _write(Path(td), "ko.txt", "\ud55c\u1100\u1173\u11af\n")
            code, out = run("clean", "--input", str(src))
            self.assertEqual(code, 0)
            self.assertIn("\ud55c\uae00", out["cleaned"])

    def test_report_template_gaps_and_apply(self):
        with tempfile.TemporaryDirectory() as td:
            src = _write(Path(td), "rep.md", "## 요약\n내용\n")
            code, out = run("clean", "--input", str(src), "--kind", "report")
            self.assertEqual(code, 0)
            self.assertEqual(out["templateGaps"],
                             ["## 변경", "## 검증", "## 잔여"])
            code, out = run("clean", "--input", str(src), "--kind",
                            "report", "--apply-template")
            self.assertEqual(code, 0)
            self.assertTrue(out["appliedTemplate"])
            self.assertIn("## 검증", out["cleaned"])

    def test_secret_blocks_output(self):
        with tempfile.TemporaryDirectory() as td:
            # fixture built at runtime so the file itself holds no
            # matchable secret literal (checkpoint secret_free scans bytes)
            fake_key = "sk-" + "A" * 24
            src = _write(Path(td), "leak.txt",
                         "note\napi_" + "key = \"" + fake_key + "\"\n")
            code, out = run("clean", "--input", str(src))
            self.assertEqual(code, 5, out)
            self.assertEqual(out["blocked"], "secret-pattern")
            self.assertNotIn("cleaned", out)
            self.assertNotIn(fake_key, json.dumps(out))
            code, out = run("scan", "--input", str(src))
            self.assertEqual(code, 5)
            self.assertIn("generic-secret-assign", out["patterns"])

    def test_clean_to_output_file(self):
        with tempfile.TemporaryDirectory() as td:
            src = _write(Path(td), "msg.txt", DIRTY_COMMIT)
            dst = Path(td) / "out.txt"
            code, out = run("clean", "--input", str(src),
                            "--output", str(dst))
            self.assertEqual(code, 0)
            self.assertNotIn("cleaned", out)
            self.assertIn("fix:", dst.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
