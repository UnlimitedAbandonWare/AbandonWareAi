"""Generated evidence commands use task artifacts without reserving source."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest import mock
import json

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('dispatch_toolbox', ROOT / 'scripts/awx_mcp_toolbox.py')
toolbox = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toolbox)
audit_spec = importlib.util.spec_from_file_location('dispatch_completion', ROOT / 'scripts/awx_mcp_completion_audit.py')
completion = importlib.util.module_from_spec(audit_spec)
audit_spec.loader.exec_module(completion)


class EvidenceDispatchTests(unittest.TestCase):
    def packet(self, topic, **extra):
        return toolbox.desktop_dispatch_packet({'nodeRole': 'desktop', 'topic': topic,
            'require_producer_bundles': False, **extra})

    def test_independent_topics_bind_intake_and_audit_to_distinct_output_paths(self):
        a, b = self.packet('task-a'), self.packet('task-b')
        self.assertNotEqual(a['evidenceDir'], b['evidenceDir'])
        for row in (a, b):
            self.assertIn(row['evidenceDir'], row['desktopIntakeCommand'])
            self.assertIn(row['evidenceDir'], row['desktopAuditCommand'])

    def test_completion_reads_only_selected_evidence_directory_and_exact_topic(self):
        with tempfile.TemporaryDirectory(prefix='awx-proof-scope-') as tmp:
            root = Path(tmp)
            dispatch = root / '__patch_drop__/dispatch'
            dispatch.mkdir(parents=True)
            for topic in ('alpha', 'beta'):
                (dispatch / f'{topic}-desktop-dispatch.json').write_text(json.dumps(
                    {'packets':[{'nodeRole':'macmini', 'sentinel':topic}]}))
            self.assertEqual(completion.latest_dispatch_packet_for_role(root, 'macmini', 'alpha')['sentinel'], 'alpha')
            selected = root / 'evidence/alpha'
            with mock.patch.object(completion, 'find_external_node_smoke_proof', return_value=None) as lookup:
                completion.audit(root, evidence_dir=selected, topic='alpha')
            # An absent task proof must not fall back to another task's legacy directory.
            self.assertEqual(lookup.call_args_list, [mock.call(selected, 'macmini'), mock.call(selected, 'notebook')])

    def test_generated_wrapper_runs_with_unrelated_source_lock_and_releases_artifact_handle(self):
        with tempfile.TemporaryDirectory(prefix='awx-dispatch-') as tmp:
            root = Path(tmp)
            (root / '__patch_drop__/source-edit-locks/unrelated.lock').mkdir(parents=True)
            (root / '__patch_drop__/source_edit_lease_contract.ps1').write_bytes(
                (ROOT / '__patch_drop__/source_edit_lease_contract.ps1').read_bytes())
            (root / 'scripts').mkdir()
            (root / 'scripts/awx_mcp_toolbox.py').write_text(
                'import json,sys,time\nfrom pathlib import Path\n'
                'p=json.load(sys.stdin); d=Path(p["evidence_dir"]); d.mkdir(parents=True,exist_ok=True)\n'
                'if sys.argv[1]=="external_evidence_intake":\n'
                ' with (d/"publishing").open("x") as f: f.write("owned")\n'
                ' time.sleep(0.15)\n'
                ' (d/"publishing").unlink()\n'
                '(d / (sys.argv[1]+".json")).write_text("verified")\n'
                'print(json.dumps({"ok":True,"externalEvidenceComplete":True,"producerBundleTopic":"fixture","producerBundles":[],"unrelatedPatchDropEvidence":{"total":0},"evidence_needed":[]}))\n')
            (root / 'scripts/awx_mcp_completion_audit.py').write_text(
                'import argparse\nfrom pathlib import Path\n'
                'p=argparse.ArgumentParser();p.add_argument("--root");p.add_argument("--evidence-dir");p.add_argument("--topic");a=p.parse_args()\n'
                'assert (Path(a.evidence_dir)/"external_evidence_audit.json").exists()\n'
                '(Path(a.evidence_dir)/"completed").touch()\n')
            result = self.packet('fixture', evidence_dir=str(root / 'evidence'))
            artifacts = toolbox.write_desktop_dispatch_artifacts(result, {}, str(root / '__patch_drop__'), 'fixture')
            self.assertTrue(artifacts['ok'], artifacts)
            script = root / '__patch_drop__/dispatch/fixture-desktop-intake.ps1'
            env = dict(os.environ)
            env.pop('PSModulePath', None)
            def run():
                return subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(script)], cwd=root, env=env, capture_output=True, text=True, errors='replace', timeout=25)
            with ThreadPoolExecutor(max_workers=2) as pool:
                calls = list(pool.map(lambda _: run(), range(2)))
            for call in calls:
                self.assertEqual(call.returncode, 0, call.stdout + call.stderr)
            self.assertTrue((root / 'evidence/completed').exists(), call.stdout + call.stderr + script.read_text())
            self.assertTrue((root / '__patch_drop__/source-edit-locks/unrelated.lock').exists())
            self.assertEqual(len(list((root / '__patch_drop__/source-edit-locks').iterdir())), 1)


if __name__ == '__main__':
    unittest.main()
