from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROMPT = ROOT / "agent-prompts" / "agents" / "demo1_three_node_smb_codex" / "system_ko.md"

REQUIRED = (
    "SMB_ACCESS",
    "legacyCompatibilityInput=MACSRC_SMB_DIRECT",
    "guardedDirectMode=YDRIVE_SMB_GUARDED_DIRECT",
    "LOCAL_PRODUCER",
    "HOLD",
    "directGateExplicitNotebookImplementation=required",
    "requiredGuardSkill=demo1-macsrc-smb-direct-patch",
    "sharedLeaseRequired=true",
    "preimageCompareAndSwapRequired=true",
    "desktopFinalProof=evidence_needed",
)
FORBIDDEN = (
    "- Mac mini?\u20ac Notebook?\u20ac ?먮낯 SMB 寃쎈줈瑜?吏곸젒 ?섏젙?섏? ?딅뒗??",
    "Notebook?\u20ac ?먮낯 SMB 寃쎈줈瑜??덈? ?섏젙?섏? ?딅뒗??",
)


def assignment_values(text: str, name: str) -> list[str]:
    return re.findall(rf"(?m)^{re.escape(name)}=([^\s#]+)\s*$", text)


def has_explicit_mode_scope(normalized: str) -> bool:
    return any(
        scope in normalized
        for scope in (
            "default",
            "unguarded",
            "local_producer",
            "macsrc_smb_direct",
            "guarded",
            "기본",
            "비보호",
            "가드",
            "예외",
            "except",
        )
    ) or bool(re.search(r"guarded[-_ ]exception", normalized))


def audit_prompt(text: str) -> list[str]:
    violations = [f"missing:{marker}" for marker in REQUIRED if marker not in text]
    violations.extend(f"forbidden:{marker}" for marker in FORBIDDEN if marker in text)

    if assignment_values(text, "defaultNotebookMode") != ["SMB_ACCESS"]:
        violations.append("default_notebook_mode_assignment")
    if assignment_values(text, "defaultDirectSmbEdit") != ["false"]:
        violations.append("default_direct_smb_edit_assignment")
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

    for line in text.splitlines():
        normalized = line.casefold()
        notebook_worktree_only = (
            "notebook" in normalized
            and "source" in normalized
            and re.search(r"\bedit(?:s|ing)?\b", normalized)
            and "worktree" in normalized
            and re.search(r"\bmust\b|\brequired\b|\bonly\b", normalized)
        )
        if notebook_worktree_only and not has_explicit_mode_scope(normalized):
            violations.append("notebook_worktree_only_contradiction")
            break
    return violations


class ThreeNodeSmbPromptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = PROMPT.read_text(encoding="utf-8")

    def test_prompt_has_guarded_four_mode_policy(self):
        self.assertEqual([], audit_prompt(self.text))

    def test_audit_rejects_universal_direct_smb_edit_denial(self):
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

    def test_audit_rejects_unscoped_notebook_worktree_only_rule(self):
        mutated = self.text + "\nNotebook source edits use a worktree only.\n"
        self.assertIn("notebook_worktree_only_contradiction", audit_prompt(mutated))

    def test_audit_rejects_notebook_worktree_mandate(self):
        mutated = self.text + "\nNotebook source edits must use a local worktree.\n"
        self.assertIn("notebook_worktree_only_contradiction", audit_prompt(mutated))

    def test_audit_rejects_duplicate_or_wrong_canonical_query_count(self):
        mutated = self.text + "\ncanonicalQueryCount=4\n"
        self.assertIn("canonical_query_count_assignment", audit_prompt(mutated))


if __name__ == "__main__":
    unittest.main()
