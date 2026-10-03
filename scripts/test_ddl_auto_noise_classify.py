"""ddl_auto_noise_classify unittest — 소음/실에러 분리 + 권고만 출력 계약.

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 2.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ddl_auto_noise_classify.py")
SPEC = importlib.util.spec_from_file_location("ddl_auto_noise_classify",
                                              SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("ddl_auto_noise_classify", MOD)
    SPEC.loader.exec_module(MOD)

LOG_NOISE_ONLY = """2026-09-29 WARN  GenerationTarget encountered exception accepting command : Error executing DDL "alter table x add constraint FK1 foreign key"
org.hibernate.tool.schema.spi.CommandAcceptanceException: Error executing DDL "
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Constraint "FKabc" already exists; SQL statement:
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Index "IDX1" already exists; SQL statement:
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Table "FOO" already exists; SQL statement:
"""

LOG_MIXED = LOG_NOISE_ONLY + """Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Column "C9" not found; SQL statement:
"""


def make_root(tmp: Path) -> Path:
    res = tmp / "main" / "resources"
    res.mkdir(parents=True)
    (res / "application.yml").write_text(
        "spring:\n  jpa:\n    hibernate:\n      ddl-auto: update\n",
        encoding="utf-8")
    return tmp


class DdlAutoNoiseTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = make_root(Path(self.tmp.name))

    def run_main(self, *argv):
        out = self.root / "out" / "noise.json"
        code = MOD.main(["--root", str(self.root), "--json-out", str(out),
                         *argv])
        payload = json.loads(out.read_text(encoding="utf-8")) \
            if out.is_file() else None
        return code, payload

    def test_all_known_noise(self):
        log = self.root / "out.log"
        log.write_text(LOG_NOISE_ONLY, encoding="utf-8")
        code, payload = self.run_main(str(log))
        self.assertEqual(0, code)
        self.assertEqual("all-known-noise", payload["verdict"])
        self.assertGreaterEqual(payload["knownNoise"]["count"], 3)
        self.assertEqual(0, payload["real"]["count"])
        self.assertIn("update", json.dumps(payload["ddlAutoConfig"]))
        self.assertTrue(payload["forCodexRecommendations"])

    def test_mixed_real_separated(self):
        log = self.root / "out.log"
        log.write_text(LOG_MIXED, encoding="utf-8")
        code, payload = self.run_main(str(log))
        self.assertEqual(0, code)
        self.assertEqual("mixed", payload["verdict"])
        self.assertGreaterEqual(payload["real"]["count"], 1)

    def test_log_not_found_exit_2(self):
        code = MOD.main(["--root", str(self.root),
                         str(self.root / "absent.log")])
        self.assertEqual(2, code)

    def test_verify_json_mode(self):
        vj = self.root / "verify.json"
        vj.write_text(json.dumps({
            "exceptions": {"log": str(self.root / "out.log"), "classes": [
                {"class": "runtime-classpath", "fatal": True, "count": 0},
                {"class": "spring-fatal", "fatal": True, "count": 0},
                {"class": "exception-lines", "fatal": False, "count": 132},
                {"class": "error-level", "fatal": False, "count": 132}]}}),
            encoding="utf-8")
        (self.root / "out.log").write_text(LOG_NOISE_ONLY, encoding="utf-8")
        code, payload = self.run_main("--verify-json", str(vj))
        self.assertEqual(0, code)
        self.assertTrue(payload["source"]["logRescanned"])
        self.assertEqual("all-known-noise", payload["verdict"])
        self.assertEqual(132, payload["exceptionLines"])

    def test_never_changes_config(self):
        # 설정 파일 읽기 함수/권고 생성은 쓰기 호출을 갖지 않는다.
        import inspect
        for fn in (MOD.find_ddl_auto, MOD.recommendations):
            self.assertNotIn("write_text", inspect.getsource(fn))
        src = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("이 도구는 설정을 바꾸지 않는다", src)


if __name__ == "__main__":
    unittest.main()
