"""Pre-send payload guard. Prints violation locations and kinds only.

Exit 0 clean, 1 violation, 2 usage. Matched secret and PII values are
not written to stdout or stderr.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

STATE_LIMIT = 8192
WHOLE_LIMIT = 65536
FORBIDDEN_KEYS = {
    "answer", "answers", "history", "transcript", "messages",
    "sourcecode", "source_code", "snippet",
}
QUESTION_KEYS = {"query", "text", "prompt", "question"}
SECRET_KINDS = (
    ("key-aiza", re.compile(r"AIza[0-9A-Za-z_\-]{20,}")),
    ("key-sk", re.compile(r"sk-[A-Za-z0-9]{16,}")),
    ("key-vck", re.compile(r"vck_[A-Za-z0-9]{8,}")),
    ("key-bearer", re.compile(r"Bearer ")),
    ("key-hex", re.compile(r"\b[0-9a-fA-F]{32,}\b")),
    ("key-b64", re.compile(r"\b[A-Za-z0-9+/]{40,}={0,2}\b")),
)
EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
PHONE = re.compile(r"01[016789]-?\d{3,4}-?\d{4}")
RRN = re.compile(r"\d{6}-?[1-4]\d{6}")
DIGITS = re.compile(r"\d{13,19}")


def _luhn(digits: str) -> bool:
    if not digits.isdigit() or not 13 <= len(digits) <= 19:
        return False
    total = 0
    for index, char in enumerate(reversed(digits)):
        number = ord(char) - 48
        if index % 2 == 1:
            number *= 2
            if number > 9:
                number -= 9
        total += number
    return total % 10 == 0


def _pointer(parts: list) -> str:
    return "$" + "".join("." + part if part.isidentifier() else "[" + part + "]" for part in parts)


def _add(found: list, path: str, kind: str) -> None:
    item = {"path": path, "kind": kind}
    if item not in found:
        found.append(item)


def _scan_string(text: str, path: str, found: list) -> None:
    for kind, pattern in SECRET_KINDS:
        if pattern.search(text):
            _add(found, path, kind)
    if EMAIL.search(text):
        _add(found, path, "pii-email")
    if PHONE.search(text):
        _add(found, path, "pii-phone")
    if RRN.search(text):
        _add(found, path, "pii-rrn")
    for match in DIGITS.finditer(text):
        if _luhn(match.group(0)):
            _add(found, path, "pii-card")
            break


def _walk(node, parts: list, found: list, marker: list) -> None:
    if isinstance(node, dict):
        if node.get("synthetic") is True:
            marker.append(True)
        for key, value in node.items():
            key_text = str(key)
            path = _pointer(parts + [key_text])
            if key_text.lower() in FORBIDDEN_KEYS:
                _add(found, path, "forbidden-field")
            if key_text == "zeroDataRetention" and value is True:
                _add(found, path, "zdr-true")
            if isinstance(value, str) and (key_text in QUESTION_KEYS or key_text == "query"):
                if value.startswith("합성 질문:"):
                    marker.append(True)
            _walk(value, parts + [key_text], found, marker)
        return
    if isinstance(node, list):
        for index, value in enumerate(node):
            _walk(value, parts + [str(index)], found, marker)
        return
    if isinstance(node, str):
        if node.startswith("합성 질문:"):
            marker.append(True)
        _scan_string(node, _pointer(parts), found)


def _body_of(doc: dict) -> dict:
    body = doc.get("body")
    if isinstance(body, dict):
        return body
    return doc


def _provider_rules(doc: dict, body: dict, found: list) -> None:
    model = body.get("model") if isinstance(body.get("model"), str) else ""
    url = doc.get("url") if isinstance(doc.get("url"), str) else ""
    jev = model.startswith("typesafe-ai/jev") or "/evaluate" in url
    gemini = (not jev) and ("gemini" in model.lower() or "generationConfig" in body or "contents" in body)
    if jev:
        if model != "typesafe-ai/jev":
            _add(found, "$.model", "jev-model")
        gateway = {}
        options = body.get("providerOptions")
        if isinstance(options, dict) and isinstance(options.get("gateway"), dict):
            gateway = options["gateway"]
        if gateway.get("only") != ["typesafe-ai"]:
            _add(found, "$.providerOptions.gateway.only", "gateway-only")
    if gemini:
        config = body.get("generationConfig") if isinstance(body.get("generationConfig"), dict) else {}
        tokens = config.get("maxOutputTokens")
        if isinstance(tokens, bool) or not isinstance(tokens, int) or tokens > 256:
            _add(found, "$.generationConfig.maxOutputTokens", "max-output-tokens")
        if "temperature" in config and config.get("temperature") != 0.2:
            _add(found, "$.generationConfig.temperature", "temperature")


def inspect(doc, raw_size: int) -> list:
    if not isinstance(doc, dict):
        return [{"path": "$", "kind": "shape"}]
    found: list = []
    marker: list = []
    if raw_size > WHOLE_LIMIT:
        _add(found, "$", "size-whole")
    body = _body_of(doc)
    state = body.get("state")
    if isinstance(state, (dict, list)):
        encoded = json.dumps(state, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > STATE_LIMIT:
            _add(found, "$.state", "size-state")
    _walk(doc, [], found, marker)
    if not marker:
        _add(found, "$.question", "synthetic-marker")
    _provider_rules(doc, body, found)
    headers = doc.get("headers")
    if isinstance(headers, dict):
        for key, value in headers.items():
            if str(key).lower() == "authorization" or (isinstance(value, str) and "Bearer " in value):
                _add(found, "$.headers", "key-bearer")
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Guard a Jev or Gemini request body before send.")
    parser.add_argument("payload", nargs="?")
    args = parser.parse_args(argv)
    if not args.payload:
        print("usage payload", file=sys.stderr)
        return 2
    path = Path(args.payload)
    try:
        raw = path.read_bytes()
        doc = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        print("usage json", file=sys.stderr)
        return 2
    violations = inspect(doc, len(raw))
    print(json.dumps({"ok": not violations, "violations": violations}, ensure_ascii=True))
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
