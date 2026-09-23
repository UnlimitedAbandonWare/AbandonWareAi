"""Runtime byte encoding contains no literal value; other assignments stay strict."""
import unittest
from pathlib import Path
from scripts.codex_work_checkpoint import secret_free, CheckpointError
FIELD="to"+"ken"

class JavaEncodingTest(unittest.TestCase):
    def test_byte_variable_encoding_and_ellipsis(self):
        text='class Sample {String '+FIELD+'=HexFormat.of().formatHex(bytes);long time;String hint="\\u2026";}'
        secret_free(text.encode(),"Sample.java")
        with self.assertRaises(CheckpointError): secret_free(text.encode(),"report.md")

    def test_literals_comments_and_structural_unicode_remain_blocked(self):
        expression=FIELD+'=HexFormat.of().formatHex(bytes);'
        for text in ('// '+expression,'/* '+expression+' */','"'+expression+'"',
                     expression.replace('bytes','"synthetic-value"'),expression.replace('bytes','getBytes()'),
                     '\\u0022 '+expression+' \\u0022',expression+' '+FIELD+'="synthetic-value";'):
            with self.subTest(textLength=len(text)),self.assertRaises(CheckpointError):
                secret_free(text.encode(),"Sample.java")

    def test_real_controller_contains_runtime_assignments_only(self):
        p=Path(__file__).resolve().parents[1]/"main/java/com/example/lms/assist/DisplayConversateController.java"
        secret_free(p.read_bytes(),str(p))

if __name__=="__main__": unittest.main()
