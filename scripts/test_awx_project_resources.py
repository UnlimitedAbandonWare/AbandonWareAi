"""Synthetic-only credential, queue and probe regressions. No live API calls."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import uuid

from scripts.awx_project_secrets import SecretStore, Conflict, catalog, runtime_environment
from scripts.awx_device_bus import emit, publish, checkpoint_event, task_start, records, render_result
from scripts.awx_device_capabilities import http_probe, probe, mcp_probe

ROOT = Path(__file__).absolute().parents[1]


class ResourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='awx-resource-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root/'config').mkdir()
        (self.root/'config/project-resources.json').write_bytes((ROOT/'config/project-resources.json').read_bytes())
        (self.root/'.secrets').mkdir()
        self.store = SecretStore(self.root, security=lambda root: None)
        self.persisted = patch('scripts.awx_project_secrets.environment', return_value={}).start()
        self.addCleanup(patch.stopall)

    def test_exact_values_and_recovery_remain_only_in_secrets(self):
        first = 'synthetic value with "quotes"\nUnicode-한글\\ending'
        second = first+'-new'
        self.store.sync('desktop', {'OPENAI_API_KEY': first})
        self.store.sync('desktop', {'OPENAI_API_KEY': second})
        self.assertEqual(second, self.store.load({})['OPENAI_API_KEY'])
        copies = list((self.root/'.secrets').rglob('*.bak_*'))
        self.assertTrue(any(first in p.read_text() or '\\nUnicode' in p.read_text() for p in copies))
        self.assertEqual('.secrets/providers.json#/values/OPENAI_API_KEY', self.store.references()['OPENAI_API_KEY'])
        self.assertTrue(all('.secrets' in p.parts or p.name == 'project-resources.json' for p in self.root.rglob('*') if p.is_file()))

    def test_three_way_conflict_is_atomic_and_stale_peer_cannot_revert(self):
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-old'})
        self.store.sync('notebook', {'OPENAI_API_KEY': 'fixture-old'})
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-new'})
        self.store.sync('notebook', {'OPENAI_API_KEY': 'fixture-old'})
        self.assertEqual('fixture-new', self.store.load({})['OPENAI_API_KEY'])
        before = self.store.path.read_bytes()
        result = self.store.sync('notebook', {'OPENAI_API_KEY': 'fixture-other', 'GROQ_API_KEY': 'fixture-add'})
        self.assertEqual('conflict', result['status'])
        self.assertEqual(before, self.store.path.read_bytes())

    def test_absence_preserves_values_and_unknown_or_openssl_names_rejected(self):
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-value'})
        self.store.sync('desktop', {'OPENAI_API_KEY': ''})
        self.assertEqual('fixture-value', self.store.load({})['OPENAI_API_KEY'])
        for name in ['OPENSSL_KEY', 'opnessl', 'CODEX_AUTH_TOKEN']:
            with self.assertRaises(Conflict):
                self.store.sync('desktop', {name: 'fixture'})

    def test_security_gate_precedes_io(self):
        def deny(root): raise Conflict('denied')
        with self.assertRaises(Conflict):
            SecretStore(self.root, security=deny).sync('desktop', {'OPENAI_API_KEY': 'fixture'})
        self.assertFalse(self.store.path.exists())

    def test_child_environment_does_not_modify_original(self):
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-shared'})
        local = {'OPENAI_API_KEY': 'fixture-local', 'openssl': 'untouched'}
        result = self.store.load(local)
        self.assertEqual('fixture-shared', result['OPENAI_API_KEY'])
        self.assertEqual('fixture-local', local['OPENAI_API_KEY'])
        self.assertEqual('untouched', result['openssl'])

    def test_unavailable_optional_sharing_preserves_manual_process_values(self):
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-shared'})
        local = {'OPENAI_API_KEY': 'fixture-manual', 'openssl': 'untouched'}
        for reason in ('secrets-access-evidence-needed', 'smb-client-security-evidence-needed'):
            with self.subTest(reason=reason), patch.object(SecretStore, 'sync', return_value={'status': 'unchanged'}), \
                 patch.object(SecretStore, 'load', side_effect=Conflict(reason)):
                result = runtime_environment(self.root, local)
                self.assertEqual(local, result)
                self.assertIsNot(local, result)
        with patch.object(SecretStore, 'sync', return_value={'status': 'unchanged'}), \
             patch.object(SecretStore, 'load', side_effect=Conflict('secret-store-invalid')):
            with self.assertRaisesRegex(Conflict, 'secret-store-invalid'):
                runtime_environment(self.root, local)

    def test_queue_is_append_only_and_rejects_secret_paths(self):
        task = str(uuid.uuid4())
        first = emit(self.root, task, 'desktop', 'notebook', 'patch_verified', ['scripts/example.py'], 'verified', 0)
        before = (self.root/first).read_bytes()
        second = emit(self.root, task, 'notebook', 'desktop', 'acknowledged', [], 'acknowledged')
        self.assertNotEqual(first, second)
        self.assertEqual(before, (self.root/first).read_bytes())
        record = json.loads(before)
        for field in ['taskId', 'sourceDevice', 'targetDevice', 'eventType', 'changedFiles', 'status', 'timestamp']:
            self.assertIn(field, record)
        for path in ['.secrets/providers.json', '../x', '/absolute', 'config/secrets/key', 'main/sk-fixture.py']:
            with self.assertRaises(Conflict): emit(self.root, task, 'desktop', 'notebook', 'patch_verified', [path], 'verified')
        with self.assertRaises(Conflict): emit(self.root, 'raw-sensitive-session', 'desktop', 'notebook', 'task_started')

    def test_runtime_current_windows_value_beats_inherited_file_reference_and_shared_store(self):
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-old', 'GEMINI_API_KEY': 'fixture-old'})
        (self.root/'.env').write_text('OPENAI_API_KEY=.secrets/providers.json#/values/OPENAI_API_KEY\nGEMINI_API_KEY=fixture-old\n')
        before = self.store.path.read_bytes()
        current = {'OPENAI_API_KEY': 'fixture-current', 'GEMINI_API_KEY': 'fixture-explicit-latest'}
        with patch('scripts.awx_project_secrets.environment', return_value=current), \
             patch.object(SecretStore, 'sync', return_value={'status': 'unchanged'}), \
             patch.object(SecretStore, 'load', return_value={'OPENAI_API_KEY': 'fixture-shared-old', 'GEMINI_API_KEY': 'fixture-shared-old'}):
            result = runtime_environment(self.root, {'OPENAI_API_KEY': 'fixture-inherited-old'})
        self.assertEqual(current, result)
        self.assertEqual(before, self.store.path.read_bytes())

    def test_runtime_blank_current_setting_does_not_reactivate_legacy_credential(self):
        (self.root/'.env').write_text('GEMINI_API_KEY=fixture-old\n')
        with patch('scripts.awx_project_secrets.environment', return_value={'GEMINI_API_KEY': ''}):
            result = runtime_environment(self.root, {'GEMINI_API_KEY': 'fixture-inherited-old'})
        self.assertEqual('', result['GEMINI_API_KEY'])

    def test_runtime_budget_refresh_is_local_only_and_does_not_export_ledger(self):
        path = self.root/'config/project-resources.json'
        config = json.loads(path.read_text())
        config['runtimeEnvironmentNames'] = ['CONVERSATE_ASR_CLOUD_VERIFICATION_USD', 'CONVERSATE_ASR_CLOUD_LEDGER']
        path.write_text(json.dumps(config))
        fresh = {'CONVERSATE_ASR_CLOUD_VERIFICATION_USD': '0.50', 'CONVERSATE_ASR_CLOUD_LEDGER': 'fixture-ledger'}
        self.store.sync('desktop', {'OPENAI_API_KEY': 'fixture-key'})
        with patch('scripts.awx_project_secrets.environment', side_effect=lambda names, **kw: {k:v for k,v in fresh.items() if k in names}), \
             patch.object(SecretStore, 'sync', return_value={'status': 'unchanged'}) as sync:
            result = runtime_environment(self.root, {'CONVERSATE_ASR_CLOUD_VERIFICATION_USD': '0.25'})
        self.assertEqual('0.50', result['CONVERSATE_ASR_CLOUD_VERIFICATION_USD'])
        self.assertEqual('fixture-ledger', result['CONVERSATE_ASR_CLOUD_LEDGER'])
        self.assertFalse(set(fresh) & set(sync.call_args.args[1]))

    def test_manual_runtime_rereads_selected_store_after_rotation(self):
        self.store.sync('desktop', {'GEMINI_API_KEY': 'fixture-current'})
        with patch.dict(os.environ, {'AWX_PROJECT_KEYS_SOURCE_ROOT': str(self.root)}):
            result = runtime_environment(self.root, {'GEMINI_API_KEY': 'fixture-loaded-before-rotation', 'openssl': 'keep'})
        self.assertEqual('fixture-current', result['GEMINI_API_KEY'])
        self.assertEqual('keep', result['openssl'])

    def test_publication_never_overwrites_id(self):
        identity = str(uuid.uuid4())
        ref = publish(self.root, 'events/notebook', {'fixture': 1}, identity)
        with self.assertRaises(Conflict): publish(self.root, 'events/notebook', {'fixture': 2}, identity)
        self.assertEqual({'fixture': 1}, json.loads((self.root/ref).read_text()))

    def test_public_paths_and_hook_context_reject_untrusted_filenames(self):
        with self.assertRaises(Conflict): publish(self.root, '../../outside', {})
        directory = self.root/'data/device-resources/events/notebook'
        directory.mkdir(parents=True)
        (directory/'raw-private-filename.json').write_text('{}')
        self.assertEqual([], records(self.root, 'events', 'notebook'))
        output = json.loads(render_result('hook', {'status': 'observed'}))
        self.assertEqual('UserPromptSubmit', output['hookSpecificOutput']['hookEventName'])

    def test_http_auth_timeout_redirect_and_unexpected_body(self):
        class Client:
            def __init__(self, error): self.error = error
            def open(self, *args, **kwargs): raise self.error
        for code, expected in [(401, 'auth_failed'), (403, 'auth_failed'), (429, 'rate_limited'), (302, 'unavailable')]:
            result = http_probe('https://example.invalid', opener=Client(urllib.error.HTTPError('private', code, 'private', {}, None)))
            self.assertEqual(expected, result['status'])
            self.assertNotIn('private', json.dumps(result))
        self.assertEqual('timeout', http_probe('https://example.invalid', opener=Client(TimeoutError()))['status'])

    def test_registry_does_not_echo_keys_or_claim_generation(self):
        import base64
        secret = 'fixture-private-key'
        encoded = base64.b64encode(secret.encode()).decode().rstrip('=')
        def fake(url, headers, timeout):
            return {'status': 'available', 'reason': 'read_only_model_list', 'models': ['gpt-fixture', secret, encoded]}
        with patch('scripts.awx_device_capabilities.service_probe', return_value={'status': 'not_probed', 'reason': 'fixture'}), \
             patch('scripts.awx_device_capabilities.mcp_probe', return_value={'status': 'available', 'reason': 'fixture', 'toolCount': 1}), \
             patch('scripts.awx_device_capabilities.configured_mcp', return_value={}):
            record = probe(self.root, 'desktop', {'OPENAI_API_KEY': secret},
                           {'OPENAI_API_KEY': '.secrets/providers.json#/values/OPENAI_API_KEY'}, http=fake)
        self.assertNotIn(secret, json.dumps(record))
        self.assertNotIn(encoded, json.dumps(record))
        row = next(r for r in record['capabilities'] if r['provider'] == 'openai')
        self.assertEqual(2, row['modelCount'])
        self.assertEqual('read-only-no-generation', record['probeMode'])

    def test_mcp_requires_initialize_and_tools_list_without_tool_execution(self):
        from types import SimpleNamespace
        def runner(command, **kwargs):
            packets = [json.loads(line) for line in kwargs['input'].splitlines()]
            self.assertEqual(['initialize', 'notifications/initialized', 'tools/list'], [p['method'] for p in packets])
            self.assertNotIn('OPENAI_API_KEY', kwargs['env'])
            return SimpleNamespace(returncode=0, stdout='{"id":1,"result":{"protocolVersion":"fixture"}}\n{"id":2,"result":{"tools":[{}]}}\n')
        result = mcp_probe(self.root, runner=runner)
        self.assertEqual('available', result['status'])
        self.assertEqual(1, result['toolCount'])

    def test_device_override_rejected_before_probe_or_secret_access(self):
        with patch('scripts.awx_device_bus.host_facts', return_value={'role': 'desktop', 'hostId': 'fixture'}), \
             patch('scripts.awx_device_bus.environment') as get_env:
            with self.assertRaises(Conflict): task_start(self.root, 'notebook')
            get_env.assert_not_called()

    def test_discovery_is_confined_to_protected_store(self):
        ref = self.store.save_discovery('desktop', {'openai': ['synthetic-private-model']})
        self.assertTrue(ref.startswith('.secrets/discovery/desktop/'))
        self.assertEqual({'openai': ['synthetic-private-model']}, json.loads((self.root/ref).read_text()))

    def test_checkpoint_event_preserves_verification_scope(self):
        manifest = {'decision': {'goalId': 'raw-private-task'}, 'targets': [{'path': 'scripts/example.py', 'preimageSha256': None}]}
        state = {'status': 'verified', 'verificationExitCode': 0, 'postimages': {'scripts/example.py': 'hash'}}
        with patch('scripts.awx_device_bus.host_facts', return_value={'role': 'desktop'}): checkpoint_event(self.root, manifest, state)
        record = (self.root/state['deviceEvent']['path']).read_text()
        self.assertNotIn('raw-private-task', record)
        self.assertEqual(0, json.loads(record)['verification']['exitCode'])


if __name__ == '__main__': unittest.main()
