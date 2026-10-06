"""Characterize synthetic log redaction and supported import entry points."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ("build_error_miner", "build_error_pattern_scanner")


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorSecretMaskTest(unittest.TestCase):
    def test_synthetic_secret_and_nonsecret_examples(self):
        examples = (
            ("key prefix", "sk-" + "A" * 24, "<secret>"),
            ("assignment", "token=" + "B" * 24, "<secret>"),
            ("bearer", "Bearer " + "C" * 24, "<secret>"),
            ("header", "Authorization: " + "D" * 24, "<secret>"),
            ("cookie", "Cookie: session=" + "E" * 24, "<secret>"),
            ("ordinary text", "BUILD SUCCESSFUL", "BUILD SUCCESSFUL"),
            ("short prefix", "sk-short", "sk-short"),
            ("word boundary", "prefixsk-" + "F" * 24, "prefixsk-" + "F" * 24),
            ("multiline header", "Cookie: value\nBUILD SUCCESSFUL", "<secret>\nBUILD SUCCESSFUL"),
            # 줄끝 콜론은 다음 줄 식을 삼키지 않는다(파이썬 if-guard 오탐 회귀).
            ("eol colon guard", "if not to" + "ken:\n    break", "if not to" + "ken:\n    break"),
            # `=` 연속행(Java 리터럴 분할)은 여전히 비밀 후보로 마스킹한다.
            ("equals continuation", "to" + "ken =\n    \"H" + "H" * 24 + "\"", "<secret>"),
            ("same-line pair still hits", "to" + "ken: abc123", "<secret>"),
        )
        for name in TOOLS:
            tool = load_tool(name)
            for label, source, expected in examples:
                with self.subTest(tool=name, scenario=label):
                    self.assertEqual(expected, tool.SECRET_FRAGMENT_RE.sub("<secret>", source))

    def test_patterns_and_flags_match(self):
        miner, scanner = (load_tool(name) for name in TOOLS)
        self.assertEqual(miner.SECRET_FRAGMENT_RE.pattern, scanner.SECRET_FRAGMENT_RE.pattern)
        self.assertEqual(miner.SECRET_FRAGMENT_RE.flags, scanner.SECRET_FRAGMENT_RE.flags)

    def test_redaction_stays_at_existing_output_boundaries(self):
        miner, scanner = (load_tool(name) for name in TOOLS)
        source = "error: cannot find symbol token=" + "G" * 24
        self.assertEqual("error: cannot find symbol <secret>", miner.normalize_line(source))
        counts, samples = scanner.scan_text(source + "\n" + source)
        self.assertEqual(2, counts["cannot_find_symbol"])
        self.assertEqual("error: cannot find symbol <secret>", samples["cannot_find_symbol"])

    def test_package_import(self):
        code = "from tools import build_error_miner, build_error_pattern_scanner"
        result = subprocess.run([sys.executable, "-B", "-c", code], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)

    def test_file_location_import_outside_repository_has_no_output_or_writes(self):
        code = """
import importlib.util
import sys
for index, path in enumerate(sys.argv[1:]):
    spec = importlib.util.spec_from_file_location('synthetic_tool_' + str(index), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.SECRET_FRAGMENT_RE.sub('<secret>', 'token=' + 'H' * 24) == '<secret>'
"""
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, "-I", "-B", "-c", code]
                + [str(ROOT / "tools" / (name + ".py")) for name in TOOLS],
                cwd=directory, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("", result.stdout)
            self.assertEqual([], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
