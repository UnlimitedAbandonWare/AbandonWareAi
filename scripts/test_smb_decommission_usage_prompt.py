from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROMPT = ROOT / "agent-prompts" / "codex_9h_smb_decommission_usage_optimization_goal.md"


def assignment_values(text: str, name: str) -> list[str]:
    return re.findall(rf"(?m)^{re.escape(name)}=([^\s#]+)\s*$", text)


def has_explicit_mode_scope(normalized: str) -> bool:
    return any(
        scope in normalized
        for scope in (
            "default",
            "unguarded",
            "기본",
            "비보호",
        )
    )


def legacy_compatibility_input_is_explicit(text: str) -> bool:
    for line in text.splitlines():
        normalized = re.sub(r"[`*]+", "", line).casefold()
        if (
            "macsrc_smb_direct" in normalized
            and "compatibility input" in normalized
            and re.search(r"never\s+(?:be\s+)?emit", normalized)
        ):
            return True
    return False


def legacy_mode_output_lines(text: str) -> list[str]:
    output_lines: list[str] = []
    for line in text.splitlines():
        if "MACSRC_SMB_DIRECT" not in line:
            continue
        if line.strip() == "legacyCompatibilityInput=MACSRC_SMB_DIRECT":
            continue
        normalized = re.sub(r"[`*]+", "", line).casefold()
        if (
            "compatibility input" in normalized
            and re.search(r"never\s+(?:be\s+)?emit", normalized)
        ):
            continue
        output_lines.append(line)
    return output_lines


def has_semantic_direct_gate_bundle(text: str) -> bool:
    normalized_lines = [re.sub(r"[`*]+", "", line).casefold() for line in text.splitlines()]
    all_gates_authorize = any(
        "all" in line
        and "gate" in line
        and "pass" in line
        and "ydrive_smb_guarded_direct" in line
        and "sourcewriteroot=y:\\" in line
        and "authorizedmutation=true" in line
        for line in normalized_lines
    )
    failures_hold = any(
        "fail" in line
        and "hold" in line
        and "sourcewriteroot=null" in line
        and "authorizedmutation=false" in line
        and "fallbackworkspace=null" in line
        for line in normalized_lines
    )
    other_access_unrestricted = any(
        "read" in line
        and "tool" in line
        and "non-source" in line
        and "unrestricted" in line
        for line in normalized_lines
    )
    return all_gates_authorize and failures_hold and other_access_unrestricted


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
        "SUPPORT_CONTRACT",
        "SUPPORT_SCENARIO",
        "FALSIFY",
        "NEUTRAL",
        "APPLY | HOLD | REJECT",
        "defaultDirectSmbEdit=false",
        "legacyCompatibilityInput=MACSRC_SMB_DIRECT",
        "guardedDirectMode=YDRIVE_SMB_GUARDED_DIRECT",
        "canonicalWorkspace=Y:\\",
        "backingShareIdentityVerified=true|false",
        "backingShareIdentityReason=match|mismatch|evidence-needed",
        "sourceWriteRoot=Y:\\|null",
        "authorizedMutation=true|false",
        "externalReadAccess=unrestricted",
        "applicationSourceWriteRootOnly=true",
        "fallbackWorkspace=null",
        "directGateBackingIdentity=required",
        "directGateSourceLease=required",
        "directGatePreimageCas=required",
        "SMB_ACCESS",
        "LOCAL_PRODUCER",
        "HOLD",
        "requiredGuardSkill=demo1-macsrc-smb-direct-patch",
        "guardRollbackRequired=true",
        "desktopFinalProof=evidence_needed",
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
    if not legacy_compatibility_input_is_explicit(text):
        violations.append("legacy_compatibility_input_only_contract")
    if legacy_mode_output_lines(text):
        violations.append("legacy_mode_user_facing_output")
    if not has_semantic_direct_gate_bundle(text):
        violations.append("direct_gate_bundle")
    if "Direct SMB source editing is forbidden and should not appear as a normal workflow." in text:
        violations.append("blanket_direct_smb_denial")
    if assignment_values(text, "canonicalQueryCount") != ["3"]:
        violations.append("canonical_query_count_assignment")
    for line in text.splitlines():
        normalized = re.sub(r"\s+", " ", line.casefold())
        has_direct_smb_source_edit = all(
            (
                re.search(r"\bdirect(?:ly)?\b", normalized),
                re.search(r"\bsmb\b", normalized),
                re.search(r"\bsource\b", normalized),
                re.search(r"\bedit(?:s|ing)?\b", normalized),
            )
        )
        is_denial = re.search(
            r"\bforbid\w*\b|\bprohibited\b|\bnot permitted\b|\bno\b|\bnever\b",
            normalized,
        )
        if has_direct_smb_source_edit and is_denial and not has_explicit_mode_scope(normalized):
            violations.append("blanket_direct_smb_denial")
            break

        has_korean_direct_smb_source_edit = (
            "smb" in normalized
            and "소스" in line
            and "직접" in line
            and any(edit in line for edit in ("수정", "편집", "변경"))
        )
        is_korean_universal_denial = (
            any(prohibition in line for prohibition in ("금지", "허용하지 않", "불가"))
            or ("절대" in line and "않" in line)
        )
        if (
            has_korean_direct_smb_source_edit
            and is_korean_universal_denial
            and not has_explicit_mode_scope(normalized)
        ):
            violations.append("blanket_direct_smb_denial")
            break
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

    def test_audit_rejects_old_blanket_direct_edit_denial(self):
        mutated = self.text + "\nDirect SMB source editing is forbidden and should not appear as a normal workflow.\n"
        self.assertIn("blanket_direct_smb_denial", audit_prompt(mutated))

    def test_audit_rejects_universal_direct_edit_denial(self):
        mutated = self.text + "\nNo direct SMB source edits are permitted under any circumstances.\n"
        self.assertIn("blanket_direct_smb_denial", audit_prompt(mutated))

    def test_audit_rejects_reordered_plural_direct_smb_edit_denial(self):
        mutated = self.text + "\nAll direct edits to the SMB source are forbidden.\n"
        self.assertIn("blanket_direct_smb_denial", audit_prompt(mutated))

    def test_audit_rejects_universal_english_and_korean_direct_edit_denials(self):
        mutations = (
            "Direct SMB source editing is forbidden.",
            "Notebook은 SMB 소스 직접 수정을 절대 금지한다.",
        )
        for statement in mutations:
            with self.subTest(statement=statement):
                self.assertIn(
                    "blanket_direct_smb_denial",
                    audit_prompt(self.text + "\n" + statement + "\n"),
                )

    def test_audit_allows_default_or_unguarded_direct_edit_limits(self):
        mutated = self.text + "\nNo unguarded direct SMB source edits are permitted.\n"
        self.assertNotIn("blanket_direct_smb_denial", audit_prompt(mutated))

    def test_audit_rejects_guarded_english_direct_edit_denial(self):
        mutated = self.text.replace(
            "[DONE]",
            "No guarded direct SMB source edits are permitted.\n[DONE]",
        )
        self.assertIn("blanket_direct_smb_denial", audit_prompt(mutated))

    def test_audit_rejects_guarded_korean_direct_edit_denial(self):
        mutated = self.text.replace(
            "[DONE]",
            "가드된 YDRIVE_SMB_GUARDED_DIRECT 소스 직접 수정도 허용하지 않는다.\n[DONE]",
        )
        self.assertIn("blanket_direct_smb_denial", audit_prompt(mutated))

    def test_audit_rejects_legacy_mode_output_forms(self):
        allowed = "\n".join(
            (
                "legacyCompatibilityInput=MACSRC_SMB_DIRECT",
                "`MACSRC_SMB_DIRECT` is accepted only as a compatibility input and must never be emitted.",
            )
        )
        forbidden = (
            "guardedDirectException=MACSRC_SMB_DIRECT",
            "| `MACSRC_SMB_DIRECT` | guarded source mutation |",
            "guardedDirectMode=MACSRC_SMB_DIRECT",
            "The MACSRC_SMB_DIRECT exception may mutate application source.",
        )
        for output_form in forbidden:
            with self.subTest(output_form=output_form):
                violations = audit_prompt("/goal\n" + allowed + "\n" + output_form + "\n[DONE]")
                self.assertIn("legacy_mode_user_facing_output", violations)

    def test_audit_allows_only_explicit_legacy_compatibility_declaration(self):
        text = "\n".join(
            (
                "/goal",
                "legacyCompatibilityInput=MACSRC_SMB_DIRECT",
                "`MACSRC_SMB_DIRECT` is accepted only as a compatibility input and must never be emitted.",
                "[DONE]",
            )
        )
        violations = audit_prompt(text)
        self.assertNotIn("legacy_compatibility_input_only_contract", violations)
        self.assertNotIn("legacy_mode_user_facing_output", violations)

    def test_audit_rejects_ydrive_name_without_semantic_gate_bundle(self):
        text = "/goal\nguardedDirectMode=YDRIVE_SMB_GUARDED_DIRECT\n[DONE]"
        self.assertIn("direct_gate_bundle", audit_prompt(text))

    def test_audit_rejects_duplicate_or_wrong_canonical_query_count(self):
        mutated = self.text + "\ncanonicalQueryCount=4\n"
        self.assertIn("canonical_query_count_assignment", audit_prompt(mutated))
if __name__ == "__main__":
    unittest.main()
