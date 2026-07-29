from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROMPT = ROOT / "agent-prompts" / "codex_9h_smb_decommission_usage_optimization_goal.md"


def audit_prompt(text: str) -> list[str]:
    violations: list[str] = []
    lines = text.splitlines()
    if not lines or lines[0] != "/goal" or lines[-1] != "[DONE]":
        violations.append("goal_shape")

    required = (
        "agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md",
        "desktop_source_safe_patch",
        "prompt_artifact_only",
        "snapshotFresh=false",
        "sourceTruthFingerprint",
        "statusCount",
        "do_not_repeat_until_input_changes",
        "awx.chat-request-proof.v1",
        "[LLM_REQUEST_PROOF]",
        "providerAttemptObservedCount",
        "wireAttemptObservedCount",
        "modelSuccessClaimAllowed=false",
        "rawProofLinesStored=false",
        "supporting_evidence_needed",
        "nextAction=none_for_desktop_only",
    )
    for marker in required:
        if marker not in text:
            violations.append("missing:" + marker)

    if any(stale in text for stale in ("fileCount=2018", "fileCount=109", "fileCount=841")):
        violations.append("stale_snapshot_count")
    if re.search(r"\$hits\s*=\s*Select-String[\s\S]{0,500}?\n\s*-Recurse\b", text):
        violations.append("unsupported_select_string_recurse")
    if "Never infer provider/wire success from `clientHttpResponse`" not in text:
        violations.append("provider_wire_inference_guard")
    if "Supabase live proof is blocked by missing project ref/auth." in text:
        violations.append("supabase_optional_stop_regression")
    return violations


class SmbUsagePromptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = PROMPT.read_text(encoding="utf-8")

    def test_current_prompt_has_no_contract_violations(self):
        self.assertEqual([], audit_prompt(self.text))

    def test_audit_detects_stale_snapshot_counts(self):
        self.assertIn("stale_snapshot_count", audit_prompt(self.text + "\nfileCount=2018\n"))

    def test_audit_detects_unsupported_secret_scan(self):
        mutated = self.text + "\n$hits = Select-String -Path $files\n  -Recurse\n"
        self.assertIn("unsupported_select_string_recurse", audit_prompt(mutated))

    def test_audit_detects_missing_prompt_only_profile(self):
        mutated = self.text.replace("prompt_artifact_only", "prompt_profile_removed")
        self.assertIn("missing:prompt_artifact_only", audit_prompt(mutated))

    def test_audit_detects_provider_wire_inference_regression(self):
        guard = "Never infer provider/wire success from `clientHttpResponse`"
        mutated = self.text.replace(guard, "Provider inference guard removed")
        self.assertIn("provider_wire_inference_guard", audit_prompt(mutated))


if __name__ == "__main__":
    unittest.main()
