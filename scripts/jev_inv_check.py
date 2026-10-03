#!/usr/bin/env python3
"""Regex evidence aid for Jev WP/INV checks.

Report-only unless --strict. Does not reimplement the ZDR literal scan or the
retired-vocabulary scan. Does not replace JUnit. A miss here is a hint for
Codex, not a product patch.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLI_REL = Path("main/java/com/example/lms/assist/JevGatewayClient.java")
ADV_REL = Path("main/java/com/example/lms/assist/JevDecisionAdvisor.java")
YML_REL = Path("main/resources/application-meta-display.yml")

# Frozen from the client fail(...) literals and advisor defer("...") literals
# observed 2026-09-30. A newly introduced string fails the subset check.
ALLOWED_REASONS = frozenset({
    "auth_blocked",
    "auth_invalid",
    "billing-blocked",
    "budget_skip",
    "busy",
    "cancelled",
    "encode_failed",
    "endpoint_invalid",
    "endpoint_not_allowed",
    "endpoint_not_https",
    "error",
    "http_",
    "invalid_response",
    "jev_not_configured",
    "model_unverified",
    "network",
    "oversized_response",
    "permission_denied",
    "plan_gate",
    "rate_limited",
    "redirect",
    "shadow",
    "state_oversized",
    "timeout",
    "transport_error",
    "upstream_error",
    "wrong_model",
})

FAIL_CALL = re.compile(r"\bfail\s*\(([^;]*?)\)\s*;", re.S)
DEFER_CALL = re.compile(r"\bdefer\s*\(([^;]*?)\)\s*;", re.S)
QUOTED = re.compile(r'"([A-Za-z0-9_-]+)"')
SUBSTRING = re.compile(r'\.contains\(\s*"jev"\s*\)')
NEW_BUILDER = re.compile(r"HttpClient\.newBuilder")


def _read(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def _code_chars(source: str):
    i = 0
    n = len(source)
    while i < n:
        if source.startswith("//", i):
            j = source.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if source.startswith("/*", i):
            j = source.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if source[i] == '"':
            i += 1
            while i < n and source[i] != '"':
                i += 2 if source[i] == "\\" else 1
            i = min(n, i + 1)
            continue
        yield i, source[i]
        i += 1


def _method_body_range(source: str, name: str) -> tuple[int, int] | None:
    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", source):
        if match.start() > 0 and source[match.start() - 1] == ".":
            continue
        depth = 0
        end_paren = None
        for index, char in _code_chars(source[match.end() - 1:]):
            absolute = match.end() - 1 + index
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    end_paren = absolute
                    break
        if end_paren is None:
            continue
        open_brace = None
        for index, char in _code_chars(source[end_paren + 1:]):
            if char == "{":
                open_brace = end_paren + 1 + index
                break
            if not char.isspace():
                break
        if open_brace is None:
            continue
        depth = 0
        for index, char in _code_chars(source[open_brace:]):
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return open_brace, open_brace + index + 1
    return None


def _reason_strings(source: str, pattern: re.Pattern[str]) -> set[str]:
    found = set()
    for match in pattern.finditer(source):
        found.update(QUOTED.findall(match.group(1)))
    return found


def _row(check_id: str, status: str, detail: str) -> dict:
    return {"id": check_id, "status": status, "detail": detail}


def _default_of(line: str) -> str | None:
    code = line.split("#", 1)[0]
    env = re.search(r"\$\{[^:{}]+:([^}]+)\}", code)
    if env:
        return env.group(1).strip()
    plain = re.search(r":\s*(.*?)\s*$", code)
    if not plain:
        return None
    value = plain.group(1).strip().strip("'\"")
    return value or None


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _child_block(lines: list[str], index: int) -> list[str]:
    base = _indent(lines[index])
    block = []
    for line in lines[index + 1:]:
        if not line.strip():
            block.append(line)
            continue
        if _indent(line) <= base:
            break
        block.append(line)
    return block


def _key_line(block: list[str], key: str) -> str | None:
    pattern = re.compile(r"^\s*" + re.escape(key) + r"\s*:")
    for line in block:
        if pattern.match(line):
            return line
    return None


def _demo_jev_block(text: str) -> list[str] | None:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if re.match(r"^demo:\s*(?:#.*)?$", line):
            demo = _child_block(lines, index)
            for child_index, child in enumerate(demo):
                if re.match(r"^\s*jev:\s*(?:#.*)?$", child):
                    return _child_block(demo, child_index)
    return None


def _root_jev_block(text: str) -> list[str] | None:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if re.match(r"^jev:\s*(?:#.*)?$", line):
            return _child_block(lines, index)
    return None


def _budget_enabled_default(block: list[str]) -> str | None:
    direct = _key_line(block, "budget.enabled")
    if direct:
        return _default_of(direct)
    for index, line in enumerate(block):
        if re.match(r"^\s*budget\s*:\s*(?:#.*)?$", line):
            for nested in _child_block(block, index):
                if re.match(r"^\s*enabled\s*:", nested):
                    return _default_of(nested)
    return None


def check_wp1(cli: str | None) -> dict:
    if cli is None:
        return _row("WP1-model", "FAIL", "client-absent")
    if SUBSTRING.search(cli):
        return _row("WP1-model", "FAIL", "substring-check-present")
    return _row("WP1-model", "PASS", "substring-check-absent")


def check_wp2_client(cli: str | None) -> dict:
    if cli is None:
        return _row("WP2-client", "FAIL", "client-absent")
    hits = list(NEW_BUILDER.finditer(cli))
    if not hits:
        return _row("WP2-client", "FAIL", "builder-absent")
    span = _method_body_range(cli, "evaluate")
    if span is None:
        return _row("WP2-client", "FAIL", "evaluate-span-absent")
    start, end = span
    if any(start <= hit.start() < end for hit in hits):
        return _row("WP2-client", "FAIL", "builder-inside-evaluate")
    return _row("WP2-client", "PASS", "builder-outside-evaluate")


def check_wp2_reject(advisor: str | None) -> dict:
    if advisor is None:
        return _row("WP2-reject", "FAIL", "advisor-absent")
    if "RejectedExecutionException" in advisor:
        return _row("WP2-reject", "PASS", "rejection-handler-present")
    return _row("WP2-reject", "FAIL", "rejection-handler-absent")


def _brace_span(source: str, open_brace: int) -> int | None:
    depth = 0
    for index, char in _code_chars(source[open_brace:]):
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return open_brace + index + 1
    return None


def check_inv1(advisor: str | None) -> dict:
    """Worker lambdas must remember before they release the permit.

    Releases outside those lambdas (budget miss, rejected submission) do not
    store a result, so they are not part of this order check.
    """
    if advisor is None:
        return _row("INV1-order", "FAIL", "advisor-absent")
    bodies = []
    for match in re.finditer(r"\(\s*\)\s*->\s*\{", advisor):
        open_brace = match.end() - 1
        end = _brace_span(advisor, open_brace)
        if end is None:
            continue
        body = advisor[open_brace:end]
        if "inFlight.release" in body:
            bodies.append(body)
    if len(bodies) != 2:
        return _row("INV1-order", "FAIL", "worker-count-%d" % len(bodies))
    for body in bodies:
        remember = body.find("remember(")
        release = body.find("inFlight.release")
        if remember < 0 or remember > release:
            return _row("INV1-order", "FAIL", "remember-after-release")
    return _row("INV1-order", "PASS", "remember-before-release")


def check_inv3(cli: str | None, advisor: str | None) -> dict:
    if cli is None or advisor is None:
        return _row("INV3-reasons", "FAIL", "source-absent")
    found = _reason_strings(cli, FAIL_CALL) | _reason_strings(advisor, DEFER_CALL)
    extra = sorted(found - ALLOWED_REASONS)
    if extra:
        return _row("INV3-reasons", "FAIL", "new-reason:" + ",".join(extra))
    return _row("INV3-reasons", "PASS", "subset")


def check_inv4(yml: str | None) -> dict:
    if yml is None:
        return _row("INV4-yml", "FAIL", "yml-absent")
    jev = _demo_jev_block(yml)
    root = _root_jev_block(yml)
    if jev is None or root is None:
        return _row("INV4-yml", "FAIL", "jev-block-absent")
    expected = {
        "mode": "off",
        "free-only": "true",
        "allow-paid": "false",
        "connect-timeout-ms": "250",
        "request-timeout-ms": "800",
        "decision-wait-ms": "150",
    }
    for key, want in expected.items():
        line = _key_line(jev, key)
        if line is None or _default_of(line) != want:
            return _row("INV4-yml", "FAIL", "default:" + key)
    window = _key_line(jev, "free-window-end")
    if window is None or not _default_of(window):
        return _row("INV4-yml", "FAIL", "free-window-end")
    zdr = "\n".join(root)
    if "DEMO_JEV_ZDR:false" not in zdr:
        return _row("INV4-yml", "FAIL", "zdr-default")
    return _row("INV4-yml", "PASS", "defaults")


def check_wp3(yml: str | None) -> dict:
    if yml is None:
        return _row("WP3-default", "SKIP", "yml-absent")
    jev = _demo_jev_block(yml)
    if jev is None:
        return _row("WP3-default", "SKIP", "jev-block-absent")
    enabled = _budget_enabled_default(jev)
    daily_line = _key_line(jev, "daily-max-calls")
    daily = _default_of(daily_line) if daily_line else None
    if enabled is None and daily is None:
        return _row("WP3-default", "SKIP", "keys-absent")
    if enabled is not None and enabled.lower() != "false":
        return _row("WP3-default", "FAIL", "budget-enabled")
    if daily is not None and daily != "0":
        return _row("WP3-default", "FAIL", "daily-max-calls")
    return _row("WP3-default", "PASS", "defaults")



_CFG_ALLOW = re.compile(r"(?i)^demo\.jev\.allow-paid\s*[=:]\s*true$")
_CFG_MODE = re.compile(r"(?i)^demo\.jev\.mode\s*[=:]\s*on$")
_CFG_DAILY = re.compile(r"(?i)^demo\.jev\.(?:budget\.)?daily-max-calls\s*[=:]\s*(\d+)$")


def _cfg_files(root: Path):
    resources = root / "main" / "resources"
    if resources.is_dir():
        for item in resources.rglob("*"):
            if item.is_file() and item.suffix.lower() in {".yml", ".yaml", ".properties"}:
                yield item
    config = root / "config"
    if config.is_dir():
        for item in config.rglob("*"):
            if item.is_file():
                yield item
    for item in root.glob("*.properties"):
        if item.is_file():
            yield item
    for item in root.glob(".env*"):
        if item.is_file():
            yield item


def _cfg_yaml_hit(text: str) -> bool:
    jev = _demo_jev_block(text)
    if not jev:
        return False
    for key, bad in (("allow-paid", "true"), ("mode", "on")):
        line = _key_line(jev, key)
        if line and (_default_of(line) or "").lower() == bad:
            return True
    for line in jev:
        code = line.split("#", 1)[0]
        if "daily-max-calls" not in code:
            continue
        value = _default_of(line) or ""
        if value.isdigit() and int(value) > 0:
            return True
    return False


def _cfg_prop_hit(text: str) -> bool:
    for line in text.splitlines():
        code = line.split("#", 1)[0].strip()
        if not code or code.startswith("-D") or "-Ddemo.jev" in code:
            continue
        if _CFG_ALLOW.match(code) or _CFG_MODE.match(code):
            return True
        daily = _CFG_DAILY.match(code)
        if daily and int(daily.group(1)) > 0:
            return True
    return False


def check_cfg_overrides(root: Path) -> dict:
    """FAIL when committable config enables paid Jev, mode on, or a positive daily cap.

    Test JVM -D and env-var spellings are not targets. Detail never includes file text.
    """
    for item in _cfg_files(root):
        try:
            text = item.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        suffix = item.suffix.lower()
        hit = _cfg_yaml_hit(text) if suffix in {".yml", ".yaml"} else _cfg_prop_hit(text)
        if hit:
            return _row("CFG-overrides", "FAIL", "committed-override")
    return _row("CFG-overrides", "PASS", "defaults-held")


def run_checks(root: Path) -> list[dict]:
    cli = _read(root / CLI_REL)
    advisor = _read(root / ADV_REL)
    yml = _read(root / YML_REL)
    return [
        check_wp1(cli),
        check_wp2_client(cli),
        check_wp2_reject(advisor),
        check_inv1(advisor),
        check_inv3(cli, advisor),
        check_inv4(yml),
        check_wp3(yml),
        check_cfg_overrides(root),
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Report-only Jev static checks. --strict exits 1 on FAIL.")
    parser.add_argument("--root", default=str(ROOT))
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    checks = run_checks(Path(args.root))
    payload = {
        "schemaVersion": "awx.jev-inv-check.v1",
        "strict": bool(args.strict),
        "evidence": "regex-heuristic",
        "checks": checks,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=True, indent=2))
    else:
        for row in checks:
            print("%s %s %s" % (row["id"], row["status"], row["detail"]))
    failed = any(row["status"] == "FAIL" for row in checks)
    if args.strict and failed:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
