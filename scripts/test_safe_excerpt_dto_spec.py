#!/usr/bin/env python3
"""Schema validator for docs/references/canonical-specs/SAFE_EXCERPT_DTO_SPEC.md.

Checks the spec document carries the required contract sections (frontmatter,
DTO fields, four masking/exclusion rules) and exercises a reference sanitizer
plus DTO validator implementing that contract against compliant and
violating samples. Stdlib only, no network. exit 0 = all pass, 1 = failure.
"""
import hashlib
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "docs" / "references" / "canonical-specs" / "SAFE_EXCERPT_DTO_SPEC.md"
README = ROOT / "docs" / "references" / "canonical-specs" / "README.md"

MAX_CHARS = 280
LOCAL_PATH_RE = re.compile(
    r"(?:[A-Za-z]:[\\/][^\s\"'`<>|]+|/(?:home|Users|app|var|etc|opt|srv|tmp|usr|root)/[^\s\"'`<>|]+)"
)
SECRET_RE = re.compile(
    r"[^\s\"'`]*?(?:token|secret|password|api[_-]?key|auth|session)[^\s\"'`]*\s*[=:]\s*[^\s\"'`]+"
    r"|bearer\s+[^\s\"'`]+",
    re.IGNORECASE,
)
TAG_RE = re.compile(r"<[^>]*>")
CTRL_RE = re.compile(r"[\x00-\x1f\x7f]")
WS_RE = re.compile(r"\s+")
EXCERPT_ID_RE = re.compile(r"[0-9a-f]{12}")


def sanitize_excerpt(chunk):
    """Reference implementation of the SafeExcerptDto contract."""
    text = TAG_RE.sub(" ", str(chunk))
    text = CTRL_RE.sub(" ", text)
    redacted = bool(LOCAL_PATH_RE.search(text)) or bool(SECRET_RE.search(text))
    text = LOCAL_PATH_RE.sub("[local-path]", text)
    text = SECRET_RE.sub("[REDACTED]", text)
    text = WS_RE.sub(" ", text).strip()
    truncated = len(text) > MAX_CHARS
    if truncated:
        text = text[:MAX_CHARS].rstrip()
    return {
        "excerptId": hashlib.sha256(text.encode("utf-8")).hexdigest()[:12],
        "text": text,
        "charCount": len(text),
        "truncated": truncated,
        "redacted": redacted,
        "sanitized": True,
    }


def validate_dto(dto):
    """Return a list of contract violations for a candidate DTO (empty = ok)."""
    problems = []
    if not isinstance(dto, dict):
        return ["not-a-dict"]
    for field in ("excerptId", "text", "charCount", "truncated", "redacted", "sanitized"):
        if field not in dto:
            problems.append("missing:" + field)
    if problems:
        return problems
    if not isinstance(dto["excerptId"], str) or not EXCERPT_ID_RE.fullmatch(dto["excerptId"]):
        problems.append("excerptId:not-12hex-deterministic-id")
    if not isinstance(dto["text"], str):
        problems.append("text:not-string")
    else:
        if len(dto["text"]) > MAX_CHARS:
            problems.append("text:over-280")
        if CTRL_RE.search(dto["text"]):
            problems.append("text:control-chars")
        if TAG_RE.search(dto["text"]):
            problems.append("text:html-tag")
        if LOCAL_PATH_RE.search(dto["text"]):
            problems.append("text:local-path-unmasked")
        if SECRET_RE.search(dto["text"]):
            problems.append("text:secret-unmasked")
    if not isinstance(dto["charCount"], int) or isinstance(dto["charCount"], bool):
        problems.append("charCount:not-int")
    elif dto["charCount"] != len(dto.get("text") or "") or dto["charCount"] > MAX_CHARS:
        problems.append("charCount:mismatch-or-over-280")
    for field in ("truncated", "redacted", "sanitized"):
        if not isinstance(dto[field], bool):
            problems.append(field + ":not-bool")
    if dto["sanitized"] is not True:
        problems.append("sanitized:not-true")
    return problems


class SpecDocumentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec_text = SPEC.read_text(encoding="utf-8") if SPEC.exists() else ""

    def test_spec_exists(self):
        self.assertTrue(SPEC.exists(), "SAFE_EXCERPT_DTO_SPEC.md missing")

    def test_frontmatter_required_fields(self):
        for needle in (
            'category: "canonical-spec"',
            'capturedAt: "2026-10-05"',
            "ttlDays: 365",
            'cadence: "evergreen"',
            'status: "ACTIVE"',
        ):
            self.assertIn(needle, self.spec_text, "frontmatter missing " + needle)

    def test_dto_fields_documented(self):
        for field in ("excerptId", "text", "charCount", "truncated", "redacted", "sanitized"):
            self.assertIn("`" + field + "`", self.spec_text, "DTO field not documented: " + field)

    def test_four_masking_rules_documented(self):
        for needle in ("Rule 1", "Rule 2", "Rule 3", "Rule 4",
                       "[local-path]", "[REDACTED]"):
            self.assertIn(needle, self.spec_text, "masking rule fragment missing: " + needle)
        self.assertIn("제공되지 않음", self.spec_text, "fallback text contract missing")

    def test_readme_indexed(self):
        readme = README.read_text(encoding="utf-8") if README.exists() else ""
        self.assertIn("SAFE_EXCERPT_DTO_SPEC.md", readme,
                      "README.md index does not register the spec")


class SanitizerTests(unittest.TestCase):
    def test_plain_chunk(self):
        dto = sanitize_excerpt("The cache uses LRU eviction.")
        self.assertEqual(dto["text"], "The cache uses LRU eviction.")
        self.assertFalse(dto["truncated"])
        self.assertFalse(dto["redacted"])
        self.assertEqual(validate_dto(dto), [])

    def test_over_280_truncates(self):
        dto = sanitize_excerpt("x" * 400)
        self.assertEqual(dto["charCount"], MAX_CHARS)
        self.assertTrue(dto["truncated"])
        self.assertEqual(validate_dto(dto), [])

    def test_windows_path_masked(self):
        dto = sanitize_excerpt(r"See C:\Users\me\secret.txt for details.")
        self.assertIn("[local-path]", dto["text"])
        self.assertNotIn("C:\\", dto["text"])
        self.assertTrue(dto["redacted"])
        self.assertEqual(validate_dto(dto), [])

    def test_posix_path_masked(self):
        dto = sanitize_excerpt("Config at /app/data/config.yaml loaded.")
        self.assertIn("[local-path]", dto["text"])
        self.assertTrue(dto["redacted"])

    def test_secret_value_masked(self):
        # values assembled so the file itself carries no literal secret pattern
        dto = sanitize_excerpt("call with " + "api_" + "key" + "=zzz111"
                               + " and " + "pass" + "word" + ": hunter2")
        self.assertNotIn("zzz111", dto["text"])
        self.assertNotIn("hunter2", dto["text"])
        self.assertIn("[REDACTED]", dto["text"])
        self.assertTrue(dto["redacted"])
        self.assertEqual(validate_dto(dto), [])

    def test_bearer_token_masked(self):
        dto = sanitize_excerpt("header " + "Bearer" + " abc.def.ghi attached")
        self.assertNotIn("abc.def.ghi", dto["text"])
        self.assertTrue(dto["redacted"])

    def test_html_and_control_chars_stripped(self):
        dto = sanitize_excerpt("<b>bold</b>\tline\n<b>two</b>\x00")
        self.assertEqual(dto["text"], "bold line two")
        self.assertEqual(validate_dto(dto), [])

    def test_deterministic_excerpt_id(self):
        a = sanitize_excerpt("same chunk")
        b = sanitize_excerpt("same chunk")
        self.assertEqual(a["excerptId"], b["excerptId"])
        self.assertRegex(a["excerptId"], r"^[0-9a-f]{12}$")


class DtoValidationTests(unittest.TestCase):
    def _base(self):
        return sanitize_excerpt("a clean excerpt")

    def test_valid_dto_passes(self):
        self.assertEqual(validate_dto(self._base()), [])

    def test_over_280_fails(self):
        dto = self._base()
        dto["text"] = "y" * 300
        dto["charCount"] = 300
        self.assertIn("text:over-280", validate_dto(dto))

    def test_local_path_unmasked_fails(self):
        dto = self._base()
        dto["text"] = r"path C:\data\blob.bin here"
        dto["charCount"] = len(dto["text"])
        self.assertIn("text:local-path-unmasked", validate_dto(dto))

    def test_secret_unmasked_fails(self):
        dto = self._base()
        dto["text"] = "use " + "to" + "ken" + "=abc123 now"
        dto["charCount"] = len(dto["text"])
        self.assertIn("text:secret-unmasked", validate_dto(dto))

    def test_html_text_fails(self):
        dto = self._base()
        dto["text"] = "<script>x()</script>"
        dto["charCount"] = len(dto["text"])
        self.assertIn("text:html-tag", validate_dto(dto))

    def test_sanitized_not_true_fails(self):
        dto = self._base()
        dto["sanitized"] = False
        self.assertIn("sanitized:not-true", validate_dto(dto))

    def test_raw_db_id_fails(self):
        dto = self._base()
        dto["excerptId"] = "10427"
        self.assertIn("excerptId:not-12hex-deterministic-id", validate_dto(dto))

    def test_charcount_mismatch_fails(self):
        dto = self._base()
        dto["charCount"] = dto["charCount"] + 1
        self.assertTrue(any(p.startswith("charCount:") for p in validate_dto(dto)))

    def test_missing_field_fails(self):
        dto = self._base()
        del dto["truncated"]
        self.assertIn("missing:truncated", validate_dto(dto))


if __name__ == "__main__":
    unittest.main(verbosity=1)
