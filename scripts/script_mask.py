"""Secret masking and project-root hardcode detection.

Stdlib only. Matched secret text is never returned, logged, or written.
Pattern literals are split so this file does not contain a credential value.
"""
from __future__ import annotations

import argparse
import re
import sys

_SK = "sk" + "-"
_BEARER = "Bea" + "rer"
_AWS = "AK" + "IA"
_ASSIGNED = (
    "(?i)\\b(?:api[_-]?key|secret|pass"
    + "word|to"
    + "ken)\\b\\s*[=:]\\s*['\\\"][^'\\\"]{12,}['\\\"]"
)

SECRET_PATTERNS = (
    ("openai_sk", re.compile(_SK + r"[A-Za-z0-9]{20,}")),
    ("bearer", re.compile(_BEARER + r"\s+[A-Za-z0-9\-._]{20,}", re.IGNORECASE)),
    ("aws_access_key", re.compile(_AWS + r"[0-9A-Z]{16}")),
    ("assigned_secret", re.compile(_ASSIGNED)),
)

PLACEHOLDER_RE = re.compile(
    r"(changeme|dummy|placeholder|example|sk-local|sk-test|your-api-key|"
    r"redacted|not-real|\$\{)",
    re.IGNORECASE,
)

HARDCODE_RE = re.compile(r"C:[\\/]+AbandonWare(?:[\\/]|$)", re.IGNORECASE)


class Parser(argparse.ArgumentParser):
    """Argument parser that exits 3 on usage errors and masks the message."""

    def error(self, message):
        self.print_usage(sys.stderr)
        print(mask_text(message), file=sys.stderr)
        raise SystemExit(3)


def mask_text(text: str) -> str:
    """Replace secret-shaped spans with stable redaction tokens."""
    if not text:
        return text
    masked = text
    for name, pattern in SECRET_PATTERNS:
        masked = pattern.sub("[REDACTED:%s]" % name, masked)
    return masked


def find_secrets(text: str) -> list[dict]:
    """Return line, pattern name, and severity. Never the matched value."""
    hits = []
    for index, line in enumerate(text.splitlines(), 1):
        severity = "advisory" if PLACEHOLDER_RE.search(line) else "review"
        for name, pattern in SECRET_PATTERNS:
            if pattern.search(line):
                hits.append({"line": index, "pattern": name, "severity": severity})
                break
    return hits


def project_root_hardcode_lines(text: str) -> list[int]:
    """Line numbers that pin the checkout with a C:\\AbandonWare path."""
    return [index for index, line in enumerate(text.splitlines(), 1)
            if HARDCODE_RE.search(line)]


def configure_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (OSError, ValueError):
            pass
