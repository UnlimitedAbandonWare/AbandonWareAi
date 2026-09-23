"""Skeleton seam: registration exists, generation stays fail-closed until a pin."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import awx_mcp_stdio_server as server

SPEC = importlib.util.find_spec('awx_kimi_review_adapter')
if SPEC is not None:
    import awx_kimi_review_adapter as kimi


def review(**changes):
    return {'mode': 'review', 'requestId': 'public-case-1',
            'reviewReason': 'Find an off-by-one counterexample',
            'changeSummary': 'Review this synthetic range boundary',
            'evidence': [{'evidenceId': 'E1', 'relativePath': 'example.py',
                          'excerpt': 'def in_range(x): return 0 <= x <= 10'}], **changes}


class KimiReviewTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(SPEC, 'The Kimi readiness adapter is not implemented')

    def run_without_processes(self, payload):
        # A hidden install, login, API fallback, or generation must break this test.
        with patch.object(subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
            return kimi.installed_adapter().run(payload)

    def test_missing_pin_is_a_non_generating_blocker(self):
        for payload in ({'mode': 'status', 'requestId': 'status-1'}, review()):
            result = self.run_without_processes(payload)
            self.assertEqual('blocked', result['status'])
            self.assertEqual('cli-capability-missing', result['reason'])
            self.assertEqual(0, result['generationCount'])
            self.assertFalse(result['generationEnabled'])
            self.assertFalse(result['cliVerified'])

    def test_unpinned_executable_never_runs_even_if_present(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'kimi.exe'
            executable.write_bytes(b'untrusted executable')
            with patch.object(subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
                result = kimi.KimiReviewAdapter(executable).run(review())
        self.assertEqual('cli-capability-missing', result['reason'])
        self.assertFalse(result['generationEnabled'])

    def test_review_requires_evidence(self):
        result = self.run_without_processes(review(evidence=[]))
        self.assertEqual('invalid-input', result['reason'])

    def test_invalid_input_is_blocked(self):
        for payload in ({'mode': 'status'}, {'mode': 'other', 'requestId': 'x'},
                        {'mode': 'status', 'requestId': 'x', 'extra': 1}):
            result = self.run_without_processes(payload)
            self.assertEqual('blocked', result['status'])
            self.assertEqual('invalid-input', result['reason'])

    def test_recursive_invocation_is_blocked(self):
        for name in ('AWX_KIMI_REVIEW_DEPTH', 'AWX_GROK_REVIEW_DEPTH', 'AWX_CODEX_REVIEW_DEPTH'):
            with self.subTest(name=name), patch.dict(os.environ, {name: '1'}):
                result = self.run_without_processes({'mode': 'status', 'requestId': 'status-2'})
            self.assertEqual('recursive-invocation-blocked', result['reason'])

    def test_pinned_binary_still_fails_closed_until_acceptance_model_exists(self):
        # Even a future verified pin has no generation path in this skeleton:
        # the account/billing acceptance model is a separate change-set.
        blob = b'future pinned kimi executable'
        digest = hashlib.sha256(blob).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'kimi.exe'
            executable.write_bytes(blob)
            with patch.object(kimi, 'PINNED_SHA256', digest), \
                    patch.object(kimi, 'PINNED_VERSION', '0.0.0'), \
                    patch.object(subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
                result = kimi.KimiReviewAdapter(executable).run(review())
        self.assertTrue(result['cliVerified'])
        self.assertEqual('0.0.0', result['cliVersion'])
        self.assertEqual('blocked', result['status'])
        self.assertEqual('isolation-unproven', result['reason'])
        self.assertEqual(0, result['generationCount'])

    def test_mcp_wiring_lists_tool_and_blocked_result_matches_closed_schema(self):
        names = [row['name'] for row in server.list_tools()]
        self.assertIn('kimi_review_change', names)
        schema = next(row['outputSchema'] for row in server.list_tools()
                      if row['name'] == 'kimi_review_change')
        reply = server.handle_request({'jsonrpc': '2.0', 'id': 'kimi-1', 'method': 'tools/call',
            'params': {'name': 'kimi_review_change',
                       'arguments': {'mode': 'status', 'requestId': 'status-3'}}})
        self.assertNotIn('error', reply)
        content = reply['result']['structuredContent']
        self.assertFalse(content['ok'])
        self.assertTrue(server.schema_matches(content, schema))
        self.assertEqual('cli-capability-missing', content['reason'])

    def test_mcp_rejects_ignored_arguments(self):
        reply = server.handle_request({'jsonrpc': '2.0', 'id': 'kimi-2', 'method': 'tools/call',
            'params': {'name': 'kimi_review_change',
                       'arguments': {'mode': 'status', 'requestId': 'status-4', 'model': 'x'}}})
        self.assertEqual(-32602, reply['error']['code'])


if __name__ == '__main__':
    unittest.main()
