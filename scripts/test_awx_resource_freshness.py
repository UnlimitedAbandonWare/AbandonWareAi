"""Fresh project context, with synthetic values and no provider requests."""
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from scripts import awx_device_bus as bus
from scripts import awx_device_capabilities as capabilities
from scripts.awx_project_secrets import SecretStore, Conflict

ROOT = Path(__file__).absolute().parents[1]


class FreshnessTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='awx-freshness-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root/'config').mkdir()
        (self.root/'config/project-resources.json').write_bytes((ROOT/'config/project-resources.json').read_bytes())
        self.addCleanup(patch.stopall)
        patch('scripts.awx_device_bus.host_facts', return_value={'role': 'desktop', 'hostId': 'fixture'}).start()
        patch('scripts.awx_device_bus.environment', return_value={'GEMINI_API_KEY': 'fixture-current'}).start()
        patch('scripts.awx_project_secrets.environment', return_value={}).start()
        patch.object(SecretStore, 'read', side_effect=Conflict('secrets-access-evidence-needed')).start()
        patch.object(capabilities, 'service_probe', return_value={'status': 'not_probed', 'reason': 'fixture'}).start()
        patch.object(capabilities, 'mcp_probe', return_value={'status': 'not_probed', 'reason': 'fixture'}).start()
        patch.object(capabilities, 'configured_mcp', return_value={}).start()
        original = capabilities.probe
        def safe_probe(*args, **kwargs):
            kwargs['http'] = lambda *a: {'status': 'available', 'reason': 'read_only_model_list', 'models': []}
            return original(*args, **kwargs)
        patch.object(bus, 'probe', side_effect=safe_probe).start()

    def start(self):
        result = bus.task_start(self.root, 'desktop')
        return result, json.loads((self.root/result['registryRef']).read_text())

    def test_env_file_settings_join_current_environment_without_overwriting_it(self):
        (self.root/'.env').write_text('GEMINI_API_KEY=fixture-legacy\nUPSTASH_REDIS_REST_TOKEN=fixture-redis\nUPSTASH_REDIS_REST_URL=https://example.invalid\nOPENSSL_KEY=fixture-protected\n')
        before = (self.root/'.env').read_bytes()
        _, record = self.start()
        row = next(r for r in record['capabilities'] if r['provider'] == 'upstash')
        self.assertIn('UPSTASH_REDIS_REST_TOKEN', row['configuredNames'])
        self.assertEqual(before, (self.root/'.env').read_bytes())
        self.assertNotIn('fixture-current', json.dumps(record))
        self.assertNotIn('fixture-redis', json.dumps(record))
        self.assertNotIn('OPENSSL_KEY', json.dumps(record))
        gemini = next(r for r in record['capabilities'] if r['provider'] == 'gemini')
        self.assertEqual('environment', gemini['valueSources']['GEMINI_API_KEY'])

    def test_hook_observes_changed_file_even_with_recent_registry_and_denied_sync(self):
        self.start()
        (self.root/'.env').write_text('UPSTASH_REDIS_REST_TOKEN=fixture-new\n')
        stream = io.StringIO()
        with patch('sys.argv', ['bus', 'hook', '--root', str(self.root), '--device', 'desktop']), contextlib.redirect_stdout(stream):
            self.assertEqual(0, bus.main())
        context = json.loads(stream.getvalue())['hookSpecificOutput']['additionalContext']
        result = json.loads(context.split('AWX project resources: ', 1)[1])
        self.assertEqual('observed', result['status'])
        record = json.loads((self.root/result['registryRef']).read_text())
        upstash = next(r for r in record['capabilities'] if r['provider'] == 'upstash')
        self.assertIn('UPSTASH_REDIS_REST_TOKEN', upstash['configuredNames'])

    def test_missing_sharing_evidence_preserves_concrete_failure_reason(self):
        result, record = self.start()
        self.assertEqual('secrets-access-evidence-needed', record['sharedSecrets']['reason'])
        self.assertEqual('evidence_needed', result['sharedSecretsStatus'])

    def test_database_configuration_is_discoverable_without_exporting_values(self):
        directory = self.root/'main/resources'
        directory.mkdir(parents=True)
        (directory/'application-local.yml').write_text('spring:\n  datasource:\n    url: jdbc:h2:mem:fixture\n    password: fixture-private-password\n')
        _, record = self.start()
        row = next(r for r in record['capabilities'] if r['provider'] == 'database')
        self.assertTrue(row['configurationRefs'])
        self.assertNotEqual('not_configured', row['status'])
        self.assertNotIn('fixture-private-password', json.dumps(record))
        self.assertNotIn('jdbc:h2', json.dumps(record))

    def test_sync_also_publishes_current_registry_and_retains_nonzero_blocker_exit(self):
        stream = io.StringIO()
        with patch('sys.argv', ['bus', 'sync', '--root', str(self.root), '--device', 'desktop']), contextlib.redirect_stdout(stream):
            code = bus.main()
        result = json.loads(stream.getvalue())
        self.assertEqual(2, code)
        self.assertTrue((self.root/result['registryRef']).is_file())

    def test_status_expires_observations_and_does_not_echo_peer_payloads(self):
        _, record = self.start()
        status = getattr(bus, 'registry_status', lambda root: {})
        self.assertEqual('fresh', status(self.root).get('devices', {}).get('desktop', {}).get('freshness'))
        record['timestamp'] = (datetime.now(timezone.utc)-timedelta(hours=1)).isoformat()
        # Replace only this test's registry, so there is no newer real observation.
        for p in bus.records(self.root, 'registry', 'desktop'):
            p.write_text(json.dumps(record))
        summary = status(self.root)
        self.assertEqual('stale', summary['devices']['desktop']['freshness'])
        self.assertEqual('not_observed', summary['devices']['macmini']['freshness'])
        record['timestamp'] = datetime.now(timezone.utc).isoformat()
        record['private'] = 'fixture-peer-secret'
        bus.publish(self.root, 'registry/notebook', dict(record, device='notebook'))
        self.assertNotIn('fixture-peer-secret', json.dumps(status(self.root)))

    def test_explicit_macmini_selection_is_limited_to_a_darwin_host(self):
        with patch.object(bus, 'host_facts', return_value={'role': 'macbook', 'system': 'Darwin', 'hostId': 'macbook-fixture'}):
            self.assertEqual('macmini', bus.require_device(self.root, 'macmini')['role'])
        with self.assertRaises(Conflict):
            bus.require_device(self.root, 'macmini')

    def test_actual_mcp_server_names_are_discoverable_without_commands_or_credentials(self):
        from scripts.awx_device_capabilities import configured_mcp
        # Use the real parser instead of the external config stub from setUp.
        original = self._mcp_parser
        (self.root/'config.toml').write_text('[mcp_servers.example]\ncommand="fixture-private-command"\n[mcp_servers.example.env]\nOPENAI_API_KEY="fixture-private-key"\n')
        with patch.dict(os.environ, {'CODEX_HOME': str(self.root)}):
            result = original()
        self.assertEqual('example', result.get('servers', [{}])[0].get('name'))
        self.assertNotIn('fixture-private', json.dumps(result))

    _mcp_parser = staticmethod(capabilities.configured_mcp)

    def test_speech_catalog_probes_use_official_read_only_routes(self):
        observed = []
        def http(url, headers, timeout):
            observed.append((url, tuple(headers)))
            return {'status': 'available', 'reason': 'read_only_model_list', 'models': []}
        result = self._probe(self.root, 'desktop', {'SONIOX_API_KEY': 'fixture-soniox',
            'DEEPGRAM_API_KEY': 'fixture-deepgram'}, http=http)
        self.assertIn(('https://api.soniox.com/v1/models', ('Authorization',)), observed)
        self.assertIn(('https://api.deepgram.com/v1/projects', ('Authorization',)), observed)
        self.assertNotIn('fixture-soniox', json.dumps(result))

    _probe = staticmethod(capabilities.probe)

    def test_security_failure_reason_survives_without_returning_raw_output(self):
        from scripts.awx_project_secrets import verify_security
        from types import SimpleNamespace
        response = SimpleNamespace(returncode=2, stdout=b'{"status":"evidence_needed","reason":"device-enrollment-evidence-needed"}')
        with patch('scripts.awx_project_secrets.os.name', 'nt'), patch('scripts.awx_project_secrets.subprocess.run', return_value=response):
            with self.assertRaisesRegex(Conflict, '^device-enrollment-evidence-needed$'):
                verify_security(self.root)

    def test_runtime_refreshes_legacy_inputs_and_preserves_original_environment(self):
        from scripts.awx_project_secrets import runtime_environment
        (self.root/'.env').write_text('GEMINI_API_KEY=fixture-old\nUPSTASH_REDIS_REST_TOKEN=fixture-new\n')
        local = {'GEMINI_API_KEY': 'fixture-current', 'openssl': 'fixture-protected'}
        result = runtime_environment(self.root, local)
        self.assertEqual('fixture-current', result['GEMINI_API_KEY'])
        self.assertEqual('fixture-new', result['UPSTASH_REDIS_REST_TOKEN'])
        self.assertNotIn('UPSTASH_REDIS_REST_TOKEN', local)
        self.assertEqual('fixture-protected', result['openssl'])

    def test_dotenv_is_literal_and_does_not_evaluate_or_import_unknown_values(self):
        from scripts.awx_resource_inputs import dotenv
        names = {'GEMINI_API_KEY', 'UPSTASH_REDIS_REST_TOKEN'}
        result = dotenv('GEMINI_API_KEY="fixture with # spaces" # note\nUPSTASH_REDIS_REST_TOKEN=$(whoami)\nCODEX_AUTH_TOKEN=fixture-private\n', names)
        self.assertEqual({'GEMINI_API_KEY': 'fixture with # spaces'}, result)

    def test_config_installer_accepts_only_the_public_resource_catalog(self):
        from scripts.awx_mcp_safe_install import safe_relative
        self.assertEqual('config/project-resources.json', str(safe_relative('config/project-resources.json')))
        for name in ['.secrets/providers.json', '.env', 'shared.env', 'config/credentials.json']:
            with self.assertRaises(Conflict):
                safe_relative(name)

    def test_manual_loaded_snapshot_is_not_overridden_by_stale_user_environment(self):
        import subprocess
        patch.stopall()
        (self.root/'.secrets').mkdir()
        (self.root/'.secrets/providers.json').write_text(json.dumps({'version':1,'values':{
            'GEMINI_API_KEY':{'value':'fixture-from-share'}}}))
        command = ". '"+str(ROOT/'scripts/use_project_keys.ps1')+"' -Root '"+str(self.root)+"' | Out-Null; if ($env:AWX_PROJECT_KEYS_SOURCE_ROOT) { 'marker-present' }"
        child_env = dict(os.environ)
        child_env.pop('PSModulePath', None)
        result = subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-Command',command],capture_output=True,text=True,env=child_env)
        self.assertEqual('marker-present', result.stdout.strip())
        from scripts.awx_host_runtime import child_environment
        with patch.dict(os.environ, {'AWX_PROJECT_KEYS_SOURCE_ROOT':str(self.root), 'GEMINI_API_KEY':'fixture-from-share'}), \
             patch('scripts.awx_project_secrets.environment', return_value={'GEMINI_API_KEY':'fixture-stale-user'}):
            child = child_environment(self.root, self.root.parent/'fixture-state')
        self.assertEqual('fixture-from-share', child['GEMINI_API_KEY'])

    def test_direct_snapshot_plan_preserves_bytes_and_apply_adds_without_deletion(self):
        import importlib.util
        available = importlib.util.find_spec('scripts.awx_project_keys')
        self.assertIsNotNone(available)
        from scripts.awx_project_keys import refresh
        # Undo the external-security stub; this store has an explicit fixture gate.
        patch.stopall()
        (self.root/'.secrets').mkdir()
        store = SecretStore(self.root, security=lambda root: None)
        store.sync('desktop', {'GEMINI_API_KEY':'fixture-old','GROQ_API_KEY':'fixture-retain'})
        before = store.path.read_bytes()
        incoming = {'GEMINI_API_KEY':'fixture-new','SONIOX_API_KEY':'fixture-add'}
        planned = refresh(self.root, incoming, apply=False, security=lambda root: None)
        self.assertEqual('planned', planned['status'])
        self.assertEqual(before, store.path.read_bytes())
        changed = refresh(self.root, incoming, apply=True, security=lambda root: None)
        self.assertEqual('updated', changed['status'])
        self.assertEqual('fixture-new', store.load({})['GEMINI_API_KEY'])
        self.assertEqual('fixture-retain', store.load({})['GROQ_API_KEY'])
        self.assertNotIn('fixture-', json.dumps(changed))


class OpenaiFileReferenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='awx-openai-reference-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root/'config').mkdir()
        (self.root/'config/project-resources.json').write_bytes((ROOT/'config/project-resources.json').read_bytes())
        (self.root/'.secrets').mkdir()
        self.store = self.root/'.secrets/providers.json'
        self.write_key('fixture-current')
        self.reference = '.secrets/providers.json#/values/OPENAI_API_KEY'

    def write_key(self, value):
        self.store.write_text(json.dumps({'version': 1, 'values': {
            'OPENAI_API_KEY': {'value': value}, 'GROQ_API_KEY': {'value': 'fixture-private-other'}
        }, 'baselines': {}}))

    def test_each_legacy_file_selects_snapshot_over_stale_process_and_rereads(self):
        from scripts.awx_resource_inputs import project_inputs
        for name in ('.env', 'shared.env'):
            with self.subTest(name=name):
                path = self.root/name
                path.write_text('OPENAI_API_KEY="'+self.reference+'" # retained\nGEMINI_API_KEY=fixture-legacy\n')
                before = path.read_bytes()
                for key in ('fixture-current', 'fixture-next'):
                    self.write_key(key)
                    values, sources, summary = project_inputs(self.root, {'OPENAI_API_KEY': 'fixture-stale', 'GEMINI_API_KEY': 'fixture-personal'})
                    self.assertEqual(key, values['OPENAI_API_KEY'])
                    self.assertEqual('shared-store-reference', sources['OPENAI_API_KEY'])
                    self.assertEqual('fixture-personal', values['GEMINI_API_KEY'])
                    self.assertNotIn('GROQ_API_KEY', values)
                    self.assertNotIn(key, json.dumps(summary))
                self.assertEqual(before, path.read_bytes())
                path.unlink()

    def test_reference_failures_do_not_fall_back_to_stale_environment(self):
        from scripts.awx_resource_inputs import project_inputs
        path = self.root/'.env'
        for reference in ('../providers.json#/values/OPENAI_API_KEY', self.reference+'-OTHER'):
            path.write_text('OPENAI_API_KEY='+reference+'\n')
            with self.assertRaisesRegex(Conflict, '^openai-secret-reference-invalid$'):
                project_inputs(self.root, {'OPENAI_API_KEY': 'fixture-stale'})
        path.write_text('OPENAI_API_KEY='+self.reference+'\n')
        for value in ('', 'test', '${MISSING}', self.reference):
            self.write_key(value)
            with self.assertRaisesRegex(Conflict, '^openai-secret-reference-unavailable$'):
                project_inputs(self.root, {'OPENAI_API_KEY': 'fixture-stale'})
        for raw in ('{"version":1,"values":{}}', 'invalid-json'):
            self.store.write_text(raw)
            with self.assertRaisesRegex(Conflict, '^openai-secret-reference-unavailable$'):
                project_inputs(self.root, {'OPENAI_API_KEY': 'fixture-stale'})
        self.store.unlink()
        with self.assertRaisesRegex(Conflict, '^openai-secret-reference-unavailable$'):
            project_inputs(self.root, {'OPENAI_API_KEY': 'fixture-stale'})

    def test_new_process_reads_saved_key_without_manual_loader_or_inherited_key(self):
        import subprocess
        (self.root/'shared.env').write_text('OPENAI_API_KEY='+self.reference+'\n')
        env = dict(os.environ)
        env.pop('OPENAI_API_KEY', None)
        env.pop('AWX_PROJECT_KEYS_SOURCE_ROOT', None)
        program = ('import sys; from scripts.awx_resource_inputs import project_inputs; '
                   'v=project_inputs(sys.argv[1], {})[0]; '
                   'print("match="+str(v.get("OPENAI_API_KEY")=="fixture-current"))')
        import sys
        result = subprocess.run([sys.executable, '-B', '-c', program, str(self.root)], cwd=ROOT,
                                env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode)
        self.assertEqual('match=True', result.stdout.strip())

    def test_explicit_refresh_still_imports_new_user_environment(self):
        from scripts import awx_project_keys as keys
        (self.root/'.env').write_text('OPENAI_API_KEY='+self.reference+'\n')
        stream = io.StringIO()
        with patch('sys.argv', ['keys', 'refresh', '--root', str(self.root), '--apply']), \
             patch.object(keys, 'environment', return_value={'OPENAI_API_KEY': 'fixture-new-user'}), \
             patch.object(keys, 'refresh', return_value={'status': 'updated'}) as refresh, \
             contextlib.redirect_stdout(stream):
            self.assertEqual(0, keys.main())
        self.assertEqual('fixture-new-user', refresh.call_args.args[1]['OPENAI_API_KEY'])
        self.assertNotIn('fixture-', stream.getvalue())


if __name__ == '__main__':
    unittest.main()
