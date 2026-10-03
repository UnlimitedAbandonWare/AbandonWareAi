"""Fixture checks for the AutoGrade B rail. No product source writes."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "autograde_b_rail", ROOT / "scripts" / "autograde_b_rail.py")
RAIL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RAIL)


WEB = """
private ObjectProvider<RuleBreakInterceptor> ruleBreakInterceptorProvider;
public void addInterceptors(InterceptorRegistry registry) {
    RuleBreakInterceptor ruleBreak = ruleBreakInterceptorProvider.getIfAvailable();
    registry.addInterceptor(ruleBreak);
}
"""
INTERCEPTOR = """
@Component
@ConditionalOnClass(name = {"com.example.lms.guard.rulebreak.RuleBreakContext"})
public class RuleBreakInterceptor implements HandlerInterceptor {}
"""
APP = '@SpringBootApplication(scanBasePackages = {"com.example.lms", "com.nova.protocol"})'
IMPORTS = "\n".join([
    "ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration",
    "com.example.lms.agent.context.AgentDbContextAutoConfiguration",
])


class AutogradeBRailTest(unittest.TestCase):
    def test_component_scan_registration_is_not_a_reregister(self):
        b00 = RAIL.classify_b00(WEB, INTERCEPTOR, APP, IMPORTS)
        self.assertEqual("NO_CHANGE_VERIFIED", b00["action"])
        self.assertEqual("present", b00["ruleBreak"])
        self.assertEqual("present", b00["imports"])
        self.assertEqual("absent", b00["importsRuleBreak"])
        self.assertEqual("forbidden", b00["reregister"])
        line = RAIL.verdict_line(b00, "STALE")
        self.assertIn("action=NO_CHANGE_VERIFIED", line)
        self.assertNotIn("NEED_CODEX_MIN_PATCH", line)

    def test_missing_provider_needs_codex_min_patch(self):
        b00 = RAIL.classify_b00("class WebMvcConfig {}", INTERCEPTOR, APP, IMPORTS)
        self.assertEqual("NEED_CODEX_MIN_PATCH", b00["action"])
        self.assertEqual("absent", b00["ruleBreak"])

    def test_imports_naming_rulebreak_counts_as_registered(self):
        named = IMPORTS + "\ncom.example.lms.guard.rulebreak.RuleBreakAutoConfiguration\n"
        b00 = RAIL.classify_b00(WEB, "class Other {}", APP, named)
        self.assertEqual("present", b00["importsRuleBreak"])
        self.assertEqual("NO_CHANGE_VERIFIED", b00["action"])

    def test_symbol_line_uses_live_text(self):
        self.assertEqual(3, RAIL.find_symbol_line(WEB, "addInterceptors"))
        self.assertIsNone(RAIL.find_symbol_line(None, "execute"))
        moved = "void save() {}\n" + ("// save note\n" * 8) + "static ChatRequestDto save(\n"
        self.assertEqual(10, RAIL.find_symbol_line(moved, "save", 12))

    def test_search_observations_stay_distinct(self):
        self.assertEqual("blank-query-skip", RAIL.classify_search_observation(
            "SKIPPED", True, None, "EMPTY_QUERY", ["results", "skippedReason"]))
        self.assertEqual("genuine-empty", RAIL.classify_search_observation(
            "OK", True, None, None, ["results"]))
        self.assertEqual("hits", RAIL.classify_search_observation(
            "OK", False, None, None, ["results"]))
        self.assertEqual("fail-soft-body-conflated", RAIL.classify_search_observation(
            "FAIL_SOFT", True, "RuntimeException", "TIMEOUT_OR_EXCEPTION", ["results"]))
        self.assertEqual("fail-soft-distinguished", RAIL.classify_search_observation(
            "FAIL_SOFT", True, "RuntimeException", "TIMEOUT_OR_EXCEPTION",
            ["results", "failReason"]))

    def test_remap_marks_zip_hash_mismatch_without_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = RAIL.POINTS[0]["path"]
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("void addInterceptors() {}\n", encoding="utf-8")
            before = target.read_bytes()
            row = RAIL.remap_point(root, RAIL.POINTS[0])
            self.assertFalse(row["shaMatch"])
            self.assertEqual(1, row["liveSymbolLine"])
            self.assertEqual(before, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
