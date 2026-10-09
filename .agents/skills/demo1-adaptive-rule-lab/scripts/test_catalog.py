import json
import tempfile
import unittest
from pathlib import Path

from labio import safe_path, digest, write_json
from catalog import inventory, build_index, search, validate_annotations, merge_assessment, record_usage
import catalog


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.skill = self.root / '.agents/skills/probe/SKILL.md'
        self.skill.parent.mkdir(parents=True)
        self.skill.write_text('---\nname: probe\ndescription: Investigate counterexamples before deciding.\n---\n# Probe\nKeep query and verdict owners separate.\n', encoding='utf-8')
        other = self.root / '.agents/skills/judge/SKILL.md'
        other.parent.mkdir(parents=True)
        other.write_text('---\nname: judge\ndescription: Decide from normalized evidence.\n---\n# Judge\nNo searching.\n', encoding='utf-8')
        (self.root / 'AGENTS.md').write_text('# Rules\n\n## Recovery\nPreserve recovery paths.\n', encoding='utf-8')
        self.annotations = {'schemaVersion': 'awx.semantic.catalog.v1', 'concepts': [{'id': 'counter-evidence', 'labels': ['반례', '반증', 'counterexample']}], 'entries': [], 'relations': []}

    def reviewed(self):
        entries = inventory(self.root)['entries']
        probe = next(e for e in entries if e['canonicalId'] == 'probe')
        self.annotations['entries'] = [{'id': probe['id'], 'sourceHash': probe['sourceHash'], 'functions': ['counter-evidence'], 'purposes': ['검증 전에 반례 찾기'], 'inputs': ['claim'], 'outputs': ['evidence'], 'constraints': ['no verdict authority'], 'evidenceLines': [3], 'reviewStatus': 'summary_reviewed'}]
        return probe

    def test_path_escape_and_alias_rejected(self):
        for value in ('../outside', '/outside', 'a/../b', 'C:/outside', 'a\\b'):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    safe_path(self.root, value)

    def test_stable_build_unknown_usage_and_rule_sections(self):
        self.reviewed()
        first = build_index(self.root, self.annotations)
        second = build_index(self.root, self.annotations)
        self.assertEqual(first['contentHash'], second['contentHash'])
        self.assertTrue(any(e['kind'] == 'rule' for e in first['entries']))
        self.assertTrue(all(e['usage']['observedInvocations'] is None for e in first['entries']))
        self.assertNotIn('_text', json.dumps(first))

    def test_semantic_alias_search_and_facet(self):
        self.reviewed()
        index = build_index(self.root, self.annotations)
        self.assertEqual(search(index, '반증', limit=1)[0]['canonicalId'], 'probe')
        self.assertEqual(search(index, '반례', facets={'functions': ['counter-evidence']})[0]['canonicalId'], 'probe')
        self.assertEqual(search(index, '반례', facets={'functions': ['absent']}), [])

    def test_changed_and_missing_sources_are_visible(self):
        self.reviewed()
        self.skill.write_text(self.skill.read_text(encoding='utf-8') + 'Changed.\n', encoding='utf-8')
        index = build_index(self.root, self.annotations)
        self.assertIn('stale', [e['annotationStatus'] for e in index['entries']])
        self.skill.unlink()
        index = build_index(self.root, self.annotations)
        self.assertTrue(any(d['reason'] == 'missing-annotated-source' for d in index['diagnostics']))

    def test_relations_must_bind_both_preimages(self):
        probe = self.reviewed()
        judge = next(e for e in inventory(self.root)['entries'] if e['canonicalId'] == 'judge')
        self.annotations['relations'] = [{'from': probe['id'], 'to': judge['id'], 'fromHash': probe['sourceHash'], 'toHash': '0' * 64, 'type': 'produces_input_for', 'activationCondition': 'when new evidence is needed', 'reason': 'distinct owners', 'mergeVerdict': 'keep_distinct', 'evidenceLines': [3]}]
        problems = validate_annotations(inventory(self.root), self.annotations)
        self.assertTrue(any(p['reason'] == 'stale-relation' for p in problems))

    def test_usage_counts_only_observed_events_once(self):
        probe = self.reviewed()
        event = {'eventId': 'one', 'routeId': probe['id'], 'stage': 'invoked', 'at': '2026-09-14T00:00:00Z', 'observationScope': 'this-test'}
        record_usage(self.root, event)
        record_usage(self.root, event)
        index = build_index(self.root, self.annotations)
        row = next(e for e in index['entries'] if e['id'] == probe['id'])
        self.assertEqual(row['usage']['observedInvocations'], 1)
        self.assertEqual(row['usage']['coverage'], 'instrumented_events_only')

    def test_similarity_never_approves_merge(self):
        a = {'id': 'a', 'sourceHash': 'same', 'constraints': ['no search'], 'functions': ['verify']}
        b = {'id': 'b', 'sourceHash': 'same', 'constraints': ['must search'], 'functions': ['verify']}
        self.assertEqual(merge_assessment(a, b)['verdict'], 'keep_distinct')
        self.assertFalse(merge_assessment(a, a)['mutationAllowed'])

    def test_compare_and_swap_refuses_changed_target(self):
        target = self.root / 'record.json'
        write_json(target, {'a': 1}, expected_hash=None)
        with self.assertRaises(ValueError):
            write_json(target, {'a': 2}, expected_hash='0' * 64)
        self.assertEqual(json.loads(target.read_text())['a'], 1)

    def test_external_origin_read_only_and_spec_scope(self):
        personal = self.root / 'personal'
        path = personal / 'p/SKILL.md'
        path.parent.mkdir(parents=True)
        path.write_text('---\nname: p\ndescription: Use when tracing.\n---\n', encoding='utf-8')
        spec = self.root / 'docs/superpowers/specs/a.md'
        spec.parent.mkdir(parents=True)
        spec.write_text('# Design\n', encoding='utf-8')
        result = inventory(self.root, [{'scope': 'personal-test', 'path': str(personal)}])
        self.assertTrue(any(e.get('readOnlyOrigin') for e in result['entries']))
        self.assertTrue(any(e['source'] == 'docs/superpowers/specs/a.md' for e in result['entries']))

    def test_publication_blocks_diagnostics_before_any_write(self):
        self.reviewed()
        self.annotations['entries'][0]['sourceHash'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'catalog-diagnostics'):
            catalog.publish_index(self.root, self.annotations)
        self.assertFalse((self.root / catalog.DEFAULT_INDEX).exists())
        self.assertFalse((self.root / '.agents/skills/SEMANTIC_INDEX.md').exists())

    def test_scoped_validation_preserves_unrelated_diagnostics(self):
        probe = self.reviewed()
        self.annotations['entries'].append({'id': 'repo|skill|removed-unrelated', 'sourceHash': '0' * 64})
        scoped = catalog.guidance_scope(self.root, self.annotations, [probe['source']], [])
        self.assertEqual(scoped['globalDiagnosticCount'], 1)
        self.assertEqual(scoped['diagnostics'], [])
        self.annotations['entries'][0]['sourceHash'] = '0' * 64
        self.assertTrue(catalog.guidance_scope(self.root, self.annotations, [probe['source']], [])['diagnostics'])

    def test_scope_rejects_missing_explicit_relation(self):
        self.reviewed()
        with self.assertRaisesRegex(ValueError, 'unknown-related-entry'):
            catalog.guidance_scope(self.root, self.annotations, [], ['repo|skill|nonexistent'])

    def test_valid_publication_checks_sources_and_readback(self):
        self.reviewed()
        result = catalog.publish_index(self.root, self.annotations)
        self.assertEqual(result['diagnostics'], 0)
        self.assertTrue((self.root / catalog.DEFAULT_INDEX).is_file())
        self.assertTrue((self.root / '.agents/skills/SEMANTIC_INDEX.md').is_file())

    def test_publication_failure_restores_only_owned_outputs(self):
        self.reviewed()
        def fail(path): raise RuntimeError('synthetic-publication-failure')
        with self.assertRaises(RuntimeError):
            catalog.publish_index(self.root, self.annotations, fault=fail)
        self.assertFalse((self.root / catalog.DEFAULT_INDEX).exists())
        human = self.root / '.agents/skills/SEMANTIC_INDEX.md'
        human.write_text('prior view', encoding='utf-8')
        def foreign(path):
            human.write_text('foreign view', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'preimage-drift'):
            catalog.publish_index(self.root, self.annotations, fault=foreign)
        self.assertFalse((self.root / catalog.DEFAULT_INDEX).exists())
        self.assertEqual(human.read_text(), 'foreign view')

    def test_second_output_foreign_drift_still_restores_own_first_output(self):
        self.reviewed()
        target = self.root / catalog.DEFAULT_INDEX
        human = self.root / '.agents/skills/SEMANTIC_INDEX.md'
        target.write_text('prior index', encoding='utf-8')
        human.write_text('prior view', encoding='utf-8')
        def foreign_second(path):
            if path == human:
                human.write_text('foreign view', encoding='utf-8')
                raise RuntimeError('synthetic-second-output-failure')
        with self.assertRaisesRegex(ValueError, 'rollback-postimage-drift'):
            catalog.publish_index(self.root, self.annotations, fault=foreign_second)
        self.assertEqual(target.read_text(), 'prior index')
        self.assertEqual(human.read_text(), 'foreign view')

    def test_scoped_mixed_case_duplicate_is_not_unrelated(self):
        self.reviewed()
        other = self.root / '.agents/skills/alias/SKILL.md'
        other.parent.mkdir(parents=True)
        other.write_text('---\nname: PROBE\ndescription: alias\n---\n', encoding='utf-8')
        problems = catalog.guidance_scope(self.root, self.annotations, [other.relative_to(self.root).as_posix()], [])
        self.assertTrue(any(item['reason'] == 'duplicate-identity' for item in problems['diagnostics']))


if __name__ == '__main__':
    unittest.main()
