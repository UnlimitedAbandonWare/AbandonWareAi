#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import hashlib
import pathlib
import random
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "build_error_miner.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_error_miner", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorMinerRedactionTest(unittest.TestCase):
    def test_repeated_missing_symbols_keep_five_examples_and_symbol_context(self):
        miner = load_module()
        block = "error: cannot find symbol\n    missing();\n    ^\nsymbol: method missing()\n"
        result = miner.scan_text(block * 12)["JavacCannotFindSymbol"]
        self.assertEqual(12, result["count"])
        self.assertLessEqual(len(result["examples"]), 5)
        self.assertTrue(all("symbol: method missing()" in example for example in result["examples"]))

    def test_seeded_synthetic_logs_preserve_counts_caps_and_redaction(self):
        miner = load_module()
        seed = 20260912
        rng = random.Random(seed)
        for case in range(512):
            count = rng.randrange(13)
            newline = rng.choice(("\n", "\r\n", "\r"))
            unicode_word = rng.choice(("한글", "Δοκιμή", "日本語", "café", "🧪"))
            secret = "sk-" + "".join(rng.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(32))
            credential = rng.choice(("token=", "api_key=", "password=")) + secret
            path = rng.choice((r"C:\synthetic\src\Missing.java", "/synthetic/src/Missing.java"))
            url = "https://synthetic.invalid/build/" + str(case)
            block = newline.join((
                f"error: cannot find symbol {unicode_word} {credential} {path} {url}",
                "    missing();", "    ^", "symbol: method missing()", "",
            ))
            source = block * count + newline.join((f"noise {unicode_word}", "BUILD SUCCESSFUL"))
            input_hash = hashlib.sha256(source.encode("utf-8")).hexdigest()
            with self.subTest(seed=seed, case=case, inputHash=input_hash):
                hits = miner.scan_text(source)
                found = hits.get("JavacCannotFindSymbol", {"count": 0, "examples": []})
                self.assertEqual(count, found["count"])
                self.assertTrue(all(len(entry["examples"]) <= 5 for entry in hits.values()), "example-cap")
                rendered = "\n".join(example for entry in hits.values() for example in entry["examples"])
                self.assertTrue(secret not in rendered, "credential-redaction")
                self.assertTrue(path not in rendered and url not in rendered, "path-url-redaction")
                if count:
                    self.assertTrue("<secret>" in rendered and "<path>" in rendered, "redaction-markers")
                    self.assertTrue(unicode_word in rendered, "unicode-context")
                    self.assertTrue(all("symbol: method missing()" in item for item in found["examples"]), "symbol-context")
                else:
                    self.assertEqual({}, hits)

    def test_examples_redact_secret_shaped_log_fragments(self):
        miner = load_module()
        secret = "sk-" + "C" * 24
        hits = miner.scan_text(f"""
> Task :compileJava FAILED
error: cannot find symbol token={secret}
symbol: class MissingThing
""")

        rendered = str(hits)

        self.assertTrue(secret not in rendered, "credential-redaction")
        self.assertTrue("token=sk-" not in rendered, "credential-prefix-redaction")
        self.assertTrue("<secret>" in rendered, "redaction-marker")

    def test_missing_explicit_input_rejects_before_writing_reports(self):
        miner = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            missing = root / "missing-build.log"
            valid = root / "valid-build.log"
            valid.write_text("BUILD SUCCESSFUL\n", encoding="utf-8")
            cases = (
                ("missing", str(missing), "reason=input-not-found"),
                ("mixed", f"{valid},{missing}", "reason=input-not-found"),
                ("empty", "   ", "reason=no-input-paths"),
            )

            for name, inputs, reason in cases:
                with self.subTest(name=name):
                    output_base = root / "reports" / name / "build-errors"
                    stdout = io.StringIO()
                    stderr = io.StringIO()

                    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                        exit_code = miner.main(
                            ["scan", "--in", inputs, "--out", str(output_base)]
                        )

                    self.assertEqual(2, exit_code)
                    self.assertIn(reason, stderr.getvalue())
                    self.assertNotIn(str(missing), stderr.getvalue())
                    self.assertEqual("", stdout.getvalue())
                    for suffix in (".json", ".csv", ".ndjson", ".md"):
                        self.assertFalse(pathlib.Path(str(output_base) + suffix).exists())


if __name__ == "__main__":
    unittest.main()
