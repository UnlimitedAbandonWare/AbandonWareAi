"""Exact synthetic settings redaction input remains safe only on its test path."""
import unittest
from scripts.test_codex_work_checkpoint import CP
class SettingsRedactionCheckpointTest(unittest.TestCase):
    def test_json_run_reference_label_only(self):
        code='String token=request.path("runToken").textValue();'
        CP.secret_free(code.encode(),"main/java/Controller.java")
        for text in ['String token="REALVALUE123456789";','// '+code,code+' String apiKey="REALVALUE123456789";']:
            with self.subTest(length=len(text)),self.assertRaisesRegex(CP.CheckpointError,"secret-pattern"):
                CP.secret_free(text.encode(),"main/java/Controller.java")
    def test_exact_fixture_only(self):
        fixture='"api' + '_key=PRIVATE secret"'
        path="src/test/java/com/example/lms/routing/RoutingRedactionTest.java"
        CP.secret_free(fixture.encode(),path)
        for text,source in [(fixture.replace("PRIVATE","CHANGED"),path),(fixture,"main/java/Redaction.java"),(fixture+'; "api'+'_key=REALVALUE123456789"',path)]:
            with self.subTest(source=source),self.assertRaisesRegex(CP.CheckpointError,"secret-pattern"):
                CP.secret_free(text.encode(),source)
if __name__=="__main__":unittest.main()
