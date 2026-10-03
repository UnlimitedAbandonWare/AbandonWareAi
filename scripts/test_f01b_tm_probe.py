"""f01b_tm_probe unittest — 임시 Java fixture 정적 스캔.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.8.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f01b_tm_probe.py")
SPEC = importlib.util.spec_from_file_location("f01b_tm_probe", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("f01b_tm_probe", MOD)
    SPEC.loader.exec_module(MOD)

JDBC_PRIVATE = """
public final class JdbcJobService implements JobService {
    public JdbcJobService(DataSource dataSource) {
        this.transactions = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    }
}
"""

JDBC_SHARED = """
public final class JdbcJobService implements JobService {
    public JdbcJobService(DataSource dataSource,
                          PlatformTransactionManager txManager) {
        this.transactions = new TransactionTemplate(txManager);
    }
}
"""

JOB_CONFIG = """
public class JobConfig {
    @Bean public JobService jobService(DataSource dataSource) {
        return new JdbcJobService(dataSource);
    }
}
"""


class TmProbeTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.jdir = self.root / "main/java/com/example/lms/jobs"
        self.cdir = self.root / "main/java/com/example/lms/config"
        self.jdir.mkdir(parents=True)
        self.cdir.mkdir(parents=True)

    def run_main(self, *argv):
        return MOD.main(list(argv))

    def _payload(self):
        return json.loads(
            (self.root / "data/diagnostics/f01b-trace-access-0929/"
             "f01b_tm_probe.json").read_text(encoding="utf-8"))

    def test_private_tm_suspect(self):
        (self.jdir / "JdbcJobService.java").write_text(JDBC_PRIVATE)
        (self.cdir / "JobConfig.java").write_text(JOB_CONFIG)
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        payload = self._payload()
        self.assertEqual(payload["verdict"], "PRIVATE_TM_SUSPECT")
        self.assertTrue(payload["runtimeProofRequired"])

    def test_shared_tm_hint(self):
        (self.jdir / "JdbcJobService.java").write_text(JDBC_SHARED)
        (self.cdir / "JobConfig.java").write_text(JOB_CONFIG)
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 0)
        self.assertEqual(self._payload()["verdict"], "SHARED_HINT")

    def test_missing_files_exit_2(self):
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 2)
        self.assertEqual(self._payload()["verdict"], "SOURCE_MISSING")

    def test_ambiguous_exit_3(self):
        (self.jdir / "JdbcJobService.java").write_text(
            "public final class JdbcJobService {}\n")
        (self.cdir / "JobConfig.java").write_text(JOB_CONFIG)
        code = self.run_main("--root", str(self.root))
        self.assertEqual(code, 3)
        self.assertEqual(self._payload()["verdict"], "AMBIGUOUS")


if __name__ == "__main__":
    unittest.main()
