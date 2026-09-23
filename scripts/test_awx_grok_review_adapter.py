"""No generation is authorized until official isolation and billing controls are proven."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import awx_mcp_stdio_server as server

SPEC = importlib.util.find_spec('awx_grok_review_adapter')
if SPEC is not None:
    import awx_grok_review_adapter as grok


def review(**changes):
    return {'mode': 'review', 'requestId': 'public-case-1',
            'reviewReason': 'Find an off-by-one counterexample',
            'changeSummary': 'Review this synthetic range boundary',
            'evidence': [{'evidenceId': 'E1', 'relativePath': 'example.py',
                          'excerpt': 'def in_range(x): return 0 <= x <= 10'}], **changes}


class GrokReviewTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(SPEC, 'The Grok readiness adapter is not implemented')

    def run_without_processes(self, payload):
        # A hidden install, login, API fallback, or generation must break this test.
        with tempfile.TemporaryDirectory() as directory, patch.object(
                subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
            instance = grok.installed_adapter()
            instance.profile = Path(directory)
            return instance.run(payload)

    def test_missing_cli_is_a_non_generating_blocker(self):
        with tempfile.TemporaryDirectory() as directory:
            result = grok.GrokReviewAdapter(Path(directory) / 'missing.exe').run(review())
        self.assertEqual('cli-unavailable', result['reason'])
        self.assertEqual(0, result['generationCount'])

    def test_unverified_cli_never_runs_even_if_executable_is_present(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'grok.exe'
            executable.write_bytes(b'untrusted executable')
            with patch.object(subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
                result = grok.GrokReviewAdapter(executable).run(review())
        self.assertEqual('cli-capability-missing', result['reason'])
        self.assertFalse(result['generationEnabled'])

    def test_status_and_review_do_not_upgrade_unknown_evidence(self):
        for payload in ({'mode': 'status', 'requestId': 'status-1'}, review()):
            result = self.run_without_processes(payload)
            self.assertEqual('blocked', result['status'])
            self.assertFalse(result['ok'])
            self.assertFalse(result['canaryVerified'])
            self.assertIsNone(result['subscriptionOnly'])
            self.assertEqual('unknown', result['authMode'])
            self.assertEqual('unknown', result['accountMatch'])
            self.assertIsNone(result['observedModel'])
            self.assertEqual('not_observed', result['providerAttemptEvidence'])
            self.assertEqual(0, result['generationCount'])
            self.assertEqual([], result['findings'])
            self.assertNotIn('sessionId', result)

    def test_environment_cannot_enable_generation_or_change_auth(self):
        with patch.dict(os.environ, {'AWX_GROK_READY': '1', 'GROK_API_KEY': 'dummy',
                                     'XAI_API_KEY': 'dummy', 'GROK_MODEL': 'other'}):
            result = self.run_without_processes(review())
        self.assertFalse(result['generationEnabled'])
        self.assertEqual(0, result['generationCount'])
        self.assertIsNone(result['requestedModel'])

    def test_nested_review_fails_before_installation_inspection(self):
        with patch.dict(os.environ, {'AWX_GROK_REVIEW_DEPTH': '1'}):
            result = self.run_without_processes(review())
        self.assertEqual('recursive-invocation-blocked', result['reason'])

    def test_caller_cannot_override_command_model_auth_or_policy(self):
        for key in ('model', 'command', 'apiKey', 'subscriptionOnly', 'isolationVerified', 'cwd'):
            result = self.run_without_processes(review(**{key: 'untrusted'}))
            self.assertEqual('invalid-input', result['reason'])
            self.assertEqual(0, result['generationCount'])

    def test_rejects_empty_evidence_labels_and_byte_overflow(self):
        for evidence in ([], [{'evidenceId': 'E1', 'relativePath': 'a.py', 'excerpt': ' '}],
                         [{'evidenceId': 'E1', 'relativePath': 'a.py', 'excerpt': '\uac00' * 8193}]):
            self.assertEqual('invalid-input', self.run_without_processes(review(evidence=evidence))['reason'])
        for label in ('C:/private/file', '//host/share', '../auth.json', 'a/../b', '\\private', '.'):
            item = [{'evidenceId': 'E1', 'relativePath': label, 'excerpt': 'public'}]
            self.assertEqual('invalid-input', self.run_without_processes(review(evidence=item))['reason'])

    def test_rejects_bad_types_duplicate_ids_and_timeout_extensions(self):
        for payload in (None, [], review(timeoutMs=True), review(timeoutMs=4999),
                        review(timeoutMs=90001), review(requestId='private\nvalue'),
                        review(changeSummary=' '), review(evidence=review()['evidence'] * 2)):
            result = self.run_without_processes(payload)
            self.assertEqual('invalid-input', result['reason'])
            self.assertLess(len(json.dumps(result).encode()), 32768)
            self.assertNotIn('private', result['requestId'])

    def test_secret_input_is_not_echoed(self):
        value = 'Bearer ' + 'a' * 48
        result = self.run_without_processes(review(changeSummary=value))
        self.assertEqual('secret-input-blocked', result['reason'])
        self.assertNotIn(value, json.dumps(result))

    def test_secret_like_request_id_is_not_echoed_on_validation_failure(self):
        sensitive_id = 'sk-' + 'a' * 24
        for payload in (review(requestId=sensitive_id), review(requestId=sensitive_id, command='ignored')):
            result = self.run_without_processes(payload)
            self.assertEqual('', result['requestId'])
            self.assertNotIn(sensitive_id, json.dumps(result))


class GrokMcpBoundaryTest(unittest.TestCase):
    def test_new_tool_delivers_closed_blocked_result(self):
        reply = server.handle_request({'jsonrpc': '2.0', 'id': 15, 'method': 'tools/call',
                'params': {'name': 'grok_review_change', 'arguments': review()}})
        self.assertNotIn('error', reply, 'Grok must be available at the existing MCP boundary')
        result = reply['result']['structuredContent']
        schema = next(x['outputSchema'] for x in server.list_tools() if x['name'] == 'grok_review_change')
        self.assertTrue(server.schema_matches(result, schema))
        self.assertTrue(reply['result']['isError'])
        self.assertEqual(0, result['generationCount'])
        self.assertNotIn('sessionId', result)

    def test_invalid_failed_result_is_not_allowed_to_bypass_closed_schema(self):
        with patch.dict(server.HANDLERS, grok_review_change=lambda _: {'ok': False, 'reason': 'blocked', 'leak': 'private'}):
            reply = server.handle_request({'jsonrpc': '2.0', 'id': 16, 'method': 'tools/call',
                    'params': {'name': 'grok_review_change', 'arguments': review()}})
        self.assertNotIn('error', reply)
        self.assertEqual('output_schema_validation_failed', reply['result']['structuredContent']['reason'])
        self.assertNotIn('private', json.dumps(reply))


class GrokRuntimeBoundaryTest(unittest.TestCase):
    def prepare_profile(self, profile, request_ids):
        (profile / 'config.toml').write_bytes(grok.PROFILE_POLICY.encode())
        auth = b'public synthetic authentication fixture, never a credential'
        (profile / 'auth.json').write_bytes(auth)
        window = {'schemaVersion': 1, 'verifiedAt': time.time() - 1, 'expiresAt': time.time() + 120,
                  'cliSha256': grok.PINNED_SHA256, 'policySha256': grok.POLICY_HASH,
                  'authSha256': hashlib.sha256(auth).hexdigest(), 'accountMatch': 'confirmed',
                  'accountMatchSource': 'user-attestation', 'plan': 'SuperGrok',
                  'billingEvidenceSource': 'official-ui', 'extraCreditsPresent': False,
                  'autoTopUpEnabled': False, 'includedUsageAvailable': True,
                  'loginCompleted': True, 'requestIds': request_ids}
        (profile / 'acceptance-window.json').write_text(json.dumps(window))
        return window

    def test_user_policy_is_pinned_and_additional_managed_layers_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory)
            window = self.prepare_profile(profile, ['public-case-1'])
            adapter = grok.GrokReviewAdapter(profile / 'grok.exe', profile=profile)
            self.assertEqual(window, adapter.readiness())
            for name in ('requirements.toml', 'managed_config.toml'):
                with self.subTest(name=name):
                    (profile / name).write_bytes(b'[skills]\nignore = []\n')
                    with self.assertRaises(grok.Rejected) as rejected:
                        adapter.readiness()
                    self.assertEqual('isolation-unproven', rejected.exception.reason)
                    (profile / name).unlink()
            (profile / 'config.toml').write_bytes(b'[skills]\nignore = []\n')
            with self.assertRaises(grok.Rejected) as rejected:
                adapter.readiness()
            self.assertEqual('isolation-unproven', rejected.exception.reason)

    def test_expired_mismatched_and_credit_windows_never_start_children(self):
        changes = ({'expiresAt': 0}, {'expiresAt': time.time() + 3600},
                   {'accountMatch': 'unknown'}, {'authSha256': '0' * 64},
                   {'extraCreditsPresent': True}, {'autoTopUpEnabled': True},
                   {'includedUsageAvailable': False}, {'cliSha256': '0' * 64},
                   {'loginCompleted': False}, {'schemaVersion': True},
                   {'requestIds': ['a', 'b', 'c', 'd']})
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                profile = Path(directory)
                window = self.prepare_profile(profile, ['public-case-1'])
                (profile / 'acceptance-window.json').write_text(json.dumps({**window, **change}))
                adapter = grok.GrokReviewAdapter(profile / 'grok.exe', profile=profile)
                with patch.object(adapter, 'installation', return_value=(True, '1.0.30', 'isolation-unproven')):
                    with patch.object(subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
                        result = adapter.run({'mode': 'status', 'requestId': 'public-status'})
                self.assertFalse(result['generationEnabled'])
                self.assertEqual(0, result['generationCount'])

    def test_cancelled_or_expired_process_budget_does_not_spawn(self):
        cancelled = threading.Event(); cancelled.set()
        for deadline, cancel in ((time.monotonic() + 10, cancelled), (time.monotonic() - 1, None)):
            with self.assertRaises(grok.Rejected):
                grok.bounded_process(lambda *a, **kw: self.fail('must not spawn'),
                                     ['unused'], Path.cwd(), {}, deadline, cancel)

    def test_bounded_process_drains_both_streams_and_cleans_up_on_failures(self):
        workers = []
        def factory(*args, **kwargs):
            worker = server.OwnedWorker(*args, **kwargs); workers.append(worker); return worker
        code, out, err = grok.bounded_process(factory,
            [sys.executable, '-c', 'import sys; print("ok"); print("diagnostic",file=sys.stderr)'],
            Path.cwd(), os.environ.copy(), time.monotonic() + 5, None)
        self.assertEqual((0, b'ok\r\n' if os.name == 'nt' else b'ok\n'), (code, out))
        self.assertIn(b'diagnostic', err)
        for program, reason, seconds in (
                ('import time; time.sleep(20)', 'timeout', .2),
                ('import sys; sys.stdout.buffer.write(b"x"*300000); sys.stdout.flush()', 'output-limit-exceeded', 5),
                ('import sys; sys.stderr.buffer.write(b"x"*100000); sys.stderr.flush()', 'output-limit-exceeded', 5)):
            with self.subTest(reason=reason), self.assertRaises(grok.Rejected) as rejected:
                grok.bounded_process(factory, [sys.executable, '-c', program],
                                     Path.cwd(), os.environ.copy(), time.monotonic() + seconds, None)
            self.assertEqual(reason, rejected.exception.reason)
        self.assertTrue(all(worker.process.poll() is not None for worker in workers))

    def test_mcp_mock_success_is_closed_and_duplicate_generation_is_prevented(self):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory); self.prepare_profile(profile, ['public-case-1'])
            adapter = grok.GrokReviewAdapter(profile / 'grok.exe', profile=profile)
            outer = {'text': json.dumps({'marker': 'public-case-1', 'findings': []}),
                     'stopReason': 'end_turn', 'num_turns': 1,
                     'modelUsage': {'grok-4.6': {'modelCalls': 1}},
                     'usage': {'input_tokens': 2, 'output_tokens': 3}}
            calls = []
            def factory(argv, **kwargs):
                calls.append(argv)
                return server.OwnedWorker([sys.executable, '-c', 'print(' + repr(json.dumps(outer)) + ')'],
                                          cwd=kwargs['cwd'], env=os.environ.copy(), binary=True)
            with patch.object(adapter, 'installation', return_value=(True, '1.0.30', 'isolation-unproven')):
                with patch.object(adapter, 'inspect_profile'), patch.object(grok, 'installed_adapter', return_value=adapter):
                    with server.review_adapter.owned_worker(factory, time.monotonic() + 10):
                        reply = server.handle_request({'jsonrpc': '2.0', 'id': 20, 'method': 'tools/call',
                                'params': {'name': 'grok_review_change', 'arguments': review()}})
                        result = reply['result']['structuredContent']
                        self.assertEqual('completed', result['status'])
                        self.assertEqual(2, result['usage']['input'])
                        self.assertNotIn('sessionId', result)
                        again = adapter.run(review())
                        self.assertFalse(again['ok'])
                        self.assertEqual(0, again['generationCount'])
            self.assertEqual(1, len(calls))

    def test_valid_model_metadata_and_findings_are_required_together(self):
        packet = review()
        finding = {'evidenceIds': ['E1'], 'claim': 'Upper bound is inclusive',
                   'counterexample': 'x=10 is accepted', 'suggestedChange': 'Use x < 10',
                   'confidence': .9}
        output = {'text': json.dumps({'marker': packet['requestId'], 'findings': [finding]}),
                  'stopReason': 'end_turn', 'num_turns': 1,
                  'modelUsage': {'grok-4.6': {'modelCalls': 1}},
                  'usage': {'input_tokens': 12, 'output_tokens': 8}}
        findings, usage, observed_model = grok.normalize_response(json.dumps(output).encode(), packet)
        self.assertEqual([finding], findings)
        self.assertEqual(12, usage['input'])
        self.assertEqual('grok-4.6', observed_model)
        build_output = {**output, 'modelUsage': {'grok-4.6-build': {'modelCalls': 1}}}
        self.assertEqual('grok-4.6-build', grok.normalize_response(json.dumps(build_output).encode(), packet)[2])
        for change, reason in (({'modelUsage': {}}, 'model-unverified'),
                               ({'modelUsage': {'unknown-model': {'modelCalls': 1}}}, 'model-unverified'),
                               ({'modelUsage': {'grok-4.6-build': {'modelCalls': 0}}}, 'model-unverified'),
                               ({'modelUsage': {'grok-4.6-build': {'modelCalls': True}}}, 'model-unverified'),
                               ({'modelUsage': {'grok-4.6': {'modelCalls': 1}, 'grok-4.6-build': {'modelCalls': 1}}}, 'model-unverified'),
                               ({'stopReason': 'max_turns'}, 'malformed-output'),
                               ({'num_turns': 2}, 'malformed-output'),
                               ({'text': ''}, 'empty-response')):
            with self.assertRaises(grok.Rejected) as rejected:
                grok.normalize_response(json.dumps({**output, **change}).encode(), packet)
            self.assertEqual(reason, rejected.exception.reason)
        finding['evidenceIds'] = ['E2']
        output['text'] = json.dumps({'marker': packet['requestId'], 'findings': [finding]})
        with self.assertRaises(grok.Rejected):
            grok.normalize_response(json.dumps(output).encode(), packet)

    def test_untrusted_environment_and_receipt_do_not_supply_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory)
            adapter = grok.GrokReviewAdapter(profile / 'grok.exe', profile=profile)
            with patch.object(adapter, 'installation', return_value=(True, '1.0.30', 'isolation-unproven')):
                with patch.object(subprocess, 'Popen', side_effect=AssertionError('unexpected process')):
                    result = adapter.run(review())
                    self.assertFalse(result['generationEnabled'])
                    self.assertEqual(0, result['generationCount'])
                    (profile / 'acceptance-window.json').write_text('{"ready":true}')
                    result = adapter.run(review())
                    self.assertFalse(result['generationEnabled'])
                    self.assertEqual(0, result['generationCount'])

    def test_grok_child_environment_excludes_provider_keys_and_recursive_config(self):
        with patch.dict(os.environ, {'XAI_API_KEY': 'synthetic', 'OPENAI_API_KEY': 'synthetic',
                                     'GROK_MODEL': 'other', 'HTTPS_PROXY': 'synthetic',
                                     'GROK_OIDC_ISSUER': 'synthetic'}):
            env = grok.clean_environment(Path('profile'))
        for key in ('XAI_API_KEY', 'OPENAI_API_KEY', 'GROK_MODEL', 'HTTPS_PROXY', 'GROK_OIDC_ISSUER'):
            self.assertNotIn(key, env)
        self.assertEqual('1', env['AWX_GROK_REVIEW_DEPTH'])
        self.assertEqual('0', env['GROK_CURSOR_MCPS_ENABLED'])

    def test_installed_zero_tool_profile_requires_both_filters(self):
        # The pinned binary's loopback test showed --tools '' leaves 23 tools.
        argv = grok.review_command(Path('grok.exe'), 'public synthetic text')
        self.assertEqual('read_file', argv[argv.index('--tools') + 1])
        self.assertEqual({'read_file', 'search_tool', 'use_tool'},
                         set(argv[argv.index('--disallowed-tools') + 1].split(',')))
        self.assertEqual('*', argv[argv.index('--deny') + 1])
        self.assertNotIn('--always-approve', argv)


if __name__ == '__main__':
    unittest.main()
