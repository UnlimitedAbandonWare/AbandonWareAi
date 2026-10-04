"""Focused natural-language routing contracts, exercised through the real CLI."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest


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
