"""Fixture tests for scripts/agy_log_health.py (stdlib, no real user logs)."""
import json
import os
import tempfile
import unittest

from scripts import agy_log_health as H

_FAKE_KEY = "dead" + "beef" + "cafe" + "f00d" + "1234"
_FAKE_TOKEN = "ya29." + "AAG" + "xyz789"

FIXTURE = "\n".join([
    'E1005 10:04:17.612222    1247 rules.go:459] Invalid rule trigger: CORTEX_MEMORY_TRIGGER_UNSPECIFIED',
    'E1005 10:04:17.612744    1247 rules.go:459] Invalid rule trigger: CORTEX_MEMORY_TRIGGER_UNSPECIFIED',
    'W1005 10:04:17.616925    1247 rules.go:545] Rule file C:\\proj\\AGENTS.md truncated by 6350 bytes (original 30295 bytes, limit 24000 bytes)',
    'E1005 10:04:11.809180     667 skills.go:241] Failed to parse skill file C:\\proj\\.agents\\skills\\demo-a\\SKILL.md: failed to parse frontmatter: yaml: line 2: mapping values are not allowed in this context',
    'E1005 10:04:11.809757     673 skills.go:241] Failed to parse skill file C:\\proj\\.agents\\skills\\demo-b\\SKILL.md: invalid frontmatter format',
    'E1005 10:04:13.557864    1059 skills.go:241] Failed to parse skill file C:\\proj/.agents/skills/demo-a/SKILL.md: invalid frontmatter format',
    'E1005 10:04:11.786899       1 errorreport.go:224] Unable to start git event watcher for workspace file:///C:/proj: core.repositoryformatversion does not support extension: worktreeconfig',
    'E1005 10:04:11.663952       1 launchsteps.go:84] Failed to resolve GeminiDir ".gemini": .gemini must be an absolute path',
    'W1005 10:04:11.636589     105 cache.go:135] Cache(loadCodeAssistResponse): Singleflight refresh failed: error getting token source: You are not logged into Antigravity.',
    'E1005 10:04:11.636589     105 errorreport.go:224] error getting token source: You are not logged into Antigravity.',
    'E1002 14:53:46.885939     469 prehooks.go:43] failed to call custom pre-invocation hook jsonhook__awx-websearch-default_PreInvocation_0_0: JSON hook "jsonhook__awx-websearch-default_PreInvocation_0_0" failed: command failed: exit status 1',
    'I1005 10:04:12.000000       1 main.go:1] user mail someone@example.com '
    + 'api_' + 'key=' + _FAKE_KEY + ' token ' + _FAKE_TOKEN + ' done',
]) + "\n"


class ScanLogTest(unittest.TestCase):
    def _write(self, text):
        fd, path = tempfile.mkstemp(suffix=".log", prefix="cli-fixture-")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        self.addCleanup(os.unlink, path)
        return path

    def test_counts(self):
        report = H.scan_log(self._write(FIXTURE))
        self.assertEqual(report["rule_trigger_invalid"], 2)
        self.assertEqual(report["agents_md_truncated_bytes"], 6350)
        self.assertEqual(report["agents_md_truncated_lines"], 1)
        self.assertEqual(report["agents_md_original_bytes"], 30295)
        self.assertEqual(report["agents_md_limit_bytes"], 24000)
        self.assertEqual(report["skill_parse_fail"]["count"], 3)
        self.assertEqual(report["skill_parse_fail"]["files"], ["demo-a", "demo-b"])
        self.assertEqual(report["git_watcher_fail"], 1)
        self.assertEqual(report["not_logged_in"], 2)
        self.assertEqual(report["geminidir_relative"], 1)
        self.assertEqual(report["hook_fail"], 1)
        self.assertEqual(report["hook_fail_names"],
                         ["jsonhook__awx-websearch-default_PreInvocation_0_0"])

    def test_redaction(self):
        report = H.scan_log(self._write(FIXTURE))
        blob = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("someone@example.com", blob)
        self.assertNotIn(_FAKE_TOKEN, blob)
        self.assertNotIn(_FAKE_KEY, blob)

    def test_empty_log(self):
        report = H.scan_log(self._write("I1005 ok\n"))
        self.assertEqual(report["rule_trigger_invalid"], 0)
        self.assertEqual(report["agents_md_truncated_bytes"], 0)
        self.assertEqual(report["skill_parse_fail"]["files"], [])

    def test_newest_log_picks_latest_over_5kb(self):
        with tempfile.TemporaryDirectory() as td:
            small = os.path.join(td, "cli-20261005_999999.log")
            big_old = os.path.join(td, "cli-20261005_000001.log")
            big_new = os.path.join(td, "cli-20261005_235959.log")
            other = os.path.join(td, "notes.txt")
            with open(big_old, "wb") as fh:
                fh.write(b"x" * 6000)
            with open(big_new, "wb") as fh:
                fh.write(b"y" * 6001)
            with open(small, "wb") as fh:
                fh.write(b"tiny")
            with open(other, "wb") as fh:
                fh.write(b"z" * 9000)
            os.utime(big_old, (1000000, 1000000))
            os.utime(big_new, (2000000, 2000000))
            os.utime(small, (3000000, 3000000))
            self.assertEqual(H.newest_log(td), big_new)
            os.unlink(big_new)
            self.assertEqual(H.newest_log(td), big_old)
            os.unlink(big_old)
            self.assertIsNone(H.newest_log(td))


if __name__ == "__main__":
    unittest.main()
