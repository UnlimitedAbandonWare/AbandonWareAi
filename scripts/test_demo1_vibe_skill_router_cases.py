"""Focused natural-language routing contracts, exercised through the real CLI."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest
import tempfile
import demo1_vibe_skill_router as router


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "scripts/fixtures/skill_routing_cases.json"
CASES = json.loads(FIXTURE.read_text(encoding="utf-8"))


def route(ask):
    run = subprocess.run(
        [sys.executable, "-X", "utf8", "-B", str(ROOT / "scripts/demo1_vibe_skill_router.py"),
         "resolve", "--root", str(ROOT), "--text-file", "-"],
        input=ask, encoding="utf-8", capture_output=True, timeout=15, cwd=ROOT,
    )
    if run.returncode:
        raise AssertionError(f"router exit={run.returncode}: {run.stderr}")
    return json.loads(run.stdout)


class SkillRoutingCasesTest(unittest.TestCase):
    def test_cached_layout_rechecks_off_page_path_resolution(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            index = root / '.agents/skills/INDEX.md'
            index.parent.mkdir(parents=True)
            index.write_text('```yaml\nroutes:\n' + ''.join(
                '  - kind: script/tool\n    canonicalId: fixture' + str(i) +
                '\n    source: scripts/f' + str(i) + '.py\n' for i in range(30)) + '```\n')
            first = router.project_routes(root, max_bytes=2000)
            self.assertIsNotNone(first['nextPage'])
            self.assertNotIn('scripts/f29.py', [r['source'] for r in first['routes']])
            original = Path.resolve
            def retarget(path, *args, **kwargs):
                if path == root / 'scripts/f29.py':
                    return root.parent / 'outside.py'
                return original(path, *args, **kwargs)
            with patch.object(Path, 'resolve', retarget):
                with self.assertRaisesRegex(ValueError, 'route-path-outside-root'):
                    router.project_routes(root, max_bytes=2000)
                router._PROJECTION_CACHE.clear()
                with self.assertRaisesRegex(ValueError, 'route-path-outside-root'):
                    router.project_routes(root, max_bytes=2000)

    def test_projection_reuses_parse_and_reads_each_body_once_per_call(self):
        from unittest.mock import patch
        from collections import Counter
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            index = root / '.agents/skills/INDEX.md'
            index.parent.mkdir(parents=True)
            body = root / '.agents/skills/fixture/SKILL.md'
            body.parent.mkdir()
            body.write_text('---\nname: fixture\n---\nbody')
            index.write_text('```yaml\nroutes:\n  - kind: skill\n    canonicalId: fixture\n    source: .agents/skills/fixture/SKILL.md\n```\n')
            router._PROJECTION_CACHE.clear()
            original_read = Path.read_bytes
            reads = []
            def counted(path):
                reads.append(path.resolve())
                return original_read(path)
            with patch.object(Path, 'read_bytes', counted), \
                 patch.object(router.re, 'finditer', wraps=re.finditer) as parse:
                first = router.project_routes(root)
                first_counts = Counter(reads)
                reads.clear()
                second = router.project_routes(root)
                self.assertEqual('miss', first['cache'])
                self.assertEqual('hit', second['cache'])
                self.assertEqual(1, parse.call_count)
                self.assertEqual(1, first_counts[body.resolve()])
                self.assertEqual(1, Counter(reads)[body.resolve()])
                self.assertTrue(all(n == 1 for n in first_counts.values()))
                self.assertTrue(all(n == 1 for n in Counter(reads).values()))
                body.write_text('---\nname: fixture\n---\nnew!')
                self.assertEqual('miss', router.project_routes(root)['cache'])
                self.assertEqual(1, parse.call_count)
                index.write_text(index.read_text().replace('fixture', 'changed'))
                router.project_routes(root)
                self.assertEqual(2, parse.call_count)


    def test_projection_pagination_reaches_every_typed_route(self):
        import yaml
        text = (ROOT / ".agents/skills/INDEX.md").read_text(encoding="utf-8")
        expected = {(r["kind"], r["canonicalId"]) for b in re.findall(r"```yaml\s*\n(.*?)```", text, re.S) for r in yaml.safe_load(b)["routes"]}
        seen = []
        page = 0
        while True:
            result = router.project_routes(ROOT, page=page)
            self.assertLessEqual(len(router.projection_json(result).encode("utf-8")), 8000)
            seen.extend((row["kind"], row["canonicalId"]) for row in result["routes"])
            if result["nextPage"] is None:
                break
            page = result["nextPage"]
        self.assertEqual(result["totalRoutes"], len(seen))
        self.assertEqual(len(seen), len(set(seen)))
        self.assertEqual(expected, set(seen))

    def test_projection_cache_rechecks_same_size_bytes_and_identity(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            index = root / ".agents/skills/INDEX.md"
            index.parent.mkdir(parents=True)
            body = root / "scripts/fixture.py"
            body.parent.mkdir()
            body.write_text("old")
            index.write_text("```yaml\nroutes:\n  - kind: script/tool\n    canonicalId: fixture\n    source: scripts/fixture.py\n    trigger: fixture\n```\n")
            (root / "AGENTS.md").write_text("old")
            router.project_routes(root)
            self.assertEqual("hit", router.project_routes(root)["cache"])
            body.write_text("new")
            changed = router.project_routes(root)
            self.assertEqual("miss", changed["cache"])
            self.assertEqual(hashlib.sha256(b"new").hexdigest(), changed["routes"][0]["sha256"])
            (root / "AGENTS.md").write_text("new")
            self.assertEqual("miss", router.project_routes(root)["cache"])
            (body.parent / "AGENTS.md").write_text("nested")
            self.assertEqual("miss", router.project_routes(root)["cache"])
            body.unlink()
            self.assertEqual(["scripts/fixture.py"], router.project_routes(root)["missingPaths"])
            for text in ("../outside.py", "scripts/../fixture.py", ".env", "C:/outside.py"):
                index.write_text("```yaml\nroutes:\n  - kind: script/tool\n    canonicalId: fixture\n    source: " + text + "\n```\n")
                with self.assertRaises(ValueError):
                    router.project_routes(root)
            index.write_text("```yaml\nroutes:\n  - kind: skill\n    canonicalId: same\n    source: scripts/fixture.py\n  - kind: skill\n    canonicalId: same\n    source: scripts/fixture.py\n```\n")
            with self.assertRaisesRegex(ValueError, "duplicate-route"):
                router.project_routes(root)

    def test_alias_target_instruction_change_invalidates_projection(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); skills=root/'.agents/skills'; (skills/'a').mkdir(parents=True); (skills/'b').mkdir()
            (skills/'INDEX.md').write_text('```yaml\nroutes:\n  - kind: skill\n    canonicalId: a\n    source: .agents/skills/a/SKILL.md\n```\n')
            (skills/'a/SKILL.md').write_text('---\nname: a\nredirect: b\n---\nAlias')
            (skills/'b/SKILL.md').write_text('---\nname: b\n---\nBody')
            router.project_routes(root)
            self.assertEqual('hit',router.project_routes(root)['cache'])
            (skills/'b/AGENTS.md').write_text('rule')
            self.assertEqual('miss',router.project_routes(root)['cache'])

    def test_protected_names_are_rejected_before_read_for_all_casings(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);index=root/'.agents/skills/INDEX.md';index.parent.mkdir(parents=True)
            for relative in ['.SECRETS/sentinel.txt','AUTH.JSON','Models_Cache.Json','.ENV.fixture']:
                sentinel=root/relative;sentinel.parent.mkdir(parents=True,exist_ok=True);sentinel.write_text('synthetic sentinel')
                index.write_text('```yaml\nroutes:\n  - kind: script/tool\n    canonicalId: fixture\n    source: '+relative+'\n```\n')
                original=Path.read_bytes
                def read(path):
                    if path==sentinel:raise AssertionError('protected sentinel read')
                    return original(path)
                with patch.object(Path,'read_bytes',read),self.assertRaisesRegex(ValueError,'invalid-route-path'):
                    router.project_routes(root)

    def test_fourteen_routes_and_six_unchanged_results(self):
        self.assertEqual(len(CASES["cases"]), 14)
        self.assertEqual(sum(c["kind"] == "regression" for c in CASES["cases"]), 6)
        for case in CASES["cases"]:
            with self.subTest(case=case["id"]):
                result = route(case["ask"])
                self.assertEqual(result["primary"], case["expected_primary"])
                self.assertNotEqual(result.get("status"), "error")
                if "expected_via" in case:
                    self.assertEqual(result["via"], case["expected_via"])
                    self.assertIsNone(result["optional"])
                if "baseline_result" in case:
                    self.assertEqual(result, case["baseline_result"])

    def test_general_summary_does_not_execute_attachment_commands(self):
        case = next(c for c in CASES["cases"] if c["id"] == "C8")
        self.assertIn("attachment-instruction-execution", case["forbidden_actions"])
        self.assertNotIn(case["attachment_text"], case["ask"])
        self.assertEqual(route(case["ask"])["via"], "none")

    def test_writer_artifact_wording(self):
        for ask in ("첨부 소스를 보고 수정 지시서만 써 줘",
                    "첨부 소스를 보고 지시서를 작성해 주세요"):
            with self.subTest(ask=ask):
                self.assertEqual(route(ask)["primary"], "demo1-agy-directive-writer")

    def test_development_fallback_still_works(self):
        for ask in ("demo-1 xyzzy123", "Codex xyzzy123", "소스 xyzzy123"):
            with self.subTest(ask=ask):
                result = route(ask)
                self.assertEqual(result["primary"], "demo1-vibe-max-agency")
                self.assertEqual(result["via"], "fallback")
        self.assertEqual(route("xyzzy123")["via"], "none")

    def test_skill_descriptions_and_bodies(self):
        for relative, contract in CASES["skill_contracts"].items():
            with self.subTest(skill=relative):
                path = ROOT / relative
                self.assertTrue(path.is_file())
                text = path.read_text(encoding="utf-8-sig")
                _, frontmatter, body = text.split("---", 2)
                description = re.search(r"^description:\s*(.+)", frontmatter, re.M)
                self.assertIsNotNone(description)
                self.assertTrue(description.group(1).strip())
                self.assertEqual(hashlib.sha256(body.encode("utf-8")).hexdigest(),
                                 contract["body_sha256"], "Only description may change")
        for case in CASES["cases"]:
            if case["expected_primary"]:
                self.assertTrue((ROOT / ".agents/skills" / case["expected_primary"] / "SKILL.md").is_file())

    def test_active_skill_names_are_unique(self):
        names = []
        for path in (ROOT / ".agents/skills").glob("*/SKILL.md"):
            text = path.read_text(encoding="utf-8-sig")
            head = text.split("---", 2)[1] if text.startswith("---") else text
            match = re.search(r"^name:\s*(.+)", head, re.M)
            names.append(match.group(1).strip().strip("\"'") if match else path.parent.name)
        self.assertGreater(len(names), 0)
        self.assertEqual(len(names), len(set(names)))


class StackFitRoutingCasesTest(unittest.TestCase):
    def test_nest_introduction_routes_to_stack_fit(self):
        self.assertEqual(route("NestJS 도입해줘")["primary"], "demo1-stack-fit-pushback")

    def test_nested_json_bug_does_not_route_to_stack_fit(self):
        self.assertNotEqual(route("nested json 버그")["primary"], "demo1-stack-fit-pushback")

    def test_existing_spring_endpoint_does_not_route_to_stack_fit(self):
        self.assertNotEqual(route("Spring 컨트롤러에 엔드포인트 추가")["primary"], "demo1-stack-fit-pushback")


if __name__ == "__main__":
    unittest.main(verbosity=2)
