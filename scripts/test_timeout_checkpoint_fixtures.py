"""Fixed synthetic timeout fixtures remain scoped; adjacent credential literals still fail."""
import unittest
from scripts.test_codex_work_checkpoint import CP

class TimeoutCheckpointFixturesTest(unittest.TestCase):
    def fixtures(self):
        return [
            ('frontend/test/bff.test.mjs', '"Bea' + 'rer should-not-forward"'),
            ('src/test/java/com/example/lms/service/chat/ChatRunClusterHttpTest.java',
             '"/fixture/viewer?session=206&to' + 'ken="+run.clientToken()'),
            ('src/test/java/com/example/lms/jobs/JdbcJobServiceTest.java',
             'String to' + 'ken = sql.queryForObject("SELECT worker_token FROM awx_jobs WHERE task_id=?", String.class, id);'),
        ]
    def test_exact_synthetic_fixtures(self):
        for path,code in self.fixtures():
            with self.subTest(path=path): CP.secret_free(code.encode(),path)
    def test_same_expression_in_application_source_is_rejected(self):
        for path,code in self.fixtures():
            with self.subTest(path=path),self.assertRaisesRegex(CP.CheckpointError,'secret-pattern'):
                CP.secret_free(code.encode(),'main/java/Application.java')
    def test_changed_expression_is_rejected(self):
        mutations=[('should-not-forward','changed-private-value'),('session=206','session=207'),('SELECT worker_token','SELECT replacement_token')]
        for (path,code),(old,new) in zip(self.fixtures(),mutations):
            with self.subTest(path=path),self.assertRaisesRegex(CP.CheckpointError,'secret-pattern'):
                CP.secret_free(code.replace(old,new).encode(),path)
    def test_adjacent_credential_is_rejected(self):
        for path,code in self.fixtures():
            extra=' String api'+'Key="REALVALUE123456789";'
            with self.subTest(path=path),self.assertRaisesRegex(CP.CheckpointError,'secret-pattern'):
                CP.secret_free((code+extra).encode(),path)

if __name__=='__main__': unittest.main()
