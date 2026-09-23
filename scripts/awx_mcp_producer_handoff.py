#!/usr/bin/env python3
"""Run MCP node smoke and then create a PatchDrop producer bundle.

This is the producer-node handoff wrapper for Mac mini/Notebook local
worktrees. It keeps the Desktop canonical root as verification-only evidence:
the smoke proves the MCP contract and restore guard, then the existing
PatchDrop producer helper renders the v3 bundle from an explicit pathspec.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "awx.mcp.producer_handoff.v1"
DESKTOP_CANONICAL = Path("C:/AbandonWare/demo-1/demo-1/src")
SAFE_AUDIT_FIELDS = (
    "requestId",
    "sessionId",
    "nodeRole",
    "toolName",
    "inputHash",
    "outputCount",
    "elapsedMs",
    "decision",
    "failReason",
)
SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|"
    r"pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
    r"sbp_[A-Za-z0-9_-]{10,}|"
    r"\b(?:authorization|cookie)\s*[:=]|"
    r"-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----",
    re.ASCII | re.IGNORECASE,
)
PRODUCER_FAIL_RE = re.compile(r"\[producer-bundle\]\[FAIL\]\[([A-Za-z0-9_.:-]+)\]", re.ASCII)
FORBIDDEN_PATCH_PATH_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(^|/)(apikey\.txt|apikey\.ps1)$", re.IGNORECASE), "secret-setup"),
    (re.compile(r"(^|/)\.env[^/]*(?:/|$)", re.IGNORECASE), "secret-env"),
    (re.compile(r"(^|/)pages/api/", re.IGNORECASE), "nextjs-pages-api"),
    (re.compile(r"(^|/)(\.gradle|build|node_modules|\.next|\.turbo|\.swc)(/|$)", re.IGNORECASE), "shared-cache-build-output"),
    (re.compile(r"\.(p12|jks)$", re.IGNORECASE), "keystore"),
)


def main() -> int:
    parser = argparse.ArgumentParser(description="AWX MCP producer handoff")
    parser.add_argument("--source-root", default=".", help="Producer-local worktree/clone root.")
    parser.add_argument("--canonical-root", default=str(DESKTOP_CANONICAL), help="Desktop canonical root for guard proof.")
    parser.add_argument("--patchdrop-root", default="", help="PatchDrop output root.")
    parser.add_argument(
        "--producer-script",
        default="",
        help="Path to producer-local __patch_drop__/producer_bundle.py.",
    )
    parser.add_argument("--node-role", default="macmini", choices=("macmini", "notebook", "desktop"))
    parser.add_argument("--topic", required=True)
    parser.add_argument("--pathspec", required=True, action="append", nargs="+")
    parser.add_argument("--audit-log", default="", help="Append a redacted allowlisted audit row.")
    parser.add_argument("--producer-command-hash", default="", help="SHA256 of the Desktop-rendered producer command file.")
    args = parser.parse_args()

    started = time.monotonic()
    pathspecs = [item for group in args.pathspec for item in group]
    slug = slugify(args.topic)
    bundle = f"{slug}-{args.node_role}-v3"
    if args.node_role in {"macmini", "notebook"} and is_shared_source_root(args.source_root):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="smb-direct-edit",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1

    source_root = Path(args.source_root).resolve()
    canonical_root = Path(args.canonical_root).resolve()
    if args.node_role in {"macmini", "notebook"} and is_shared_source_root(str(source_root)):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="smb-direct-edit",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1
    if args.node_role in {"macmini", "notebook"} and is_under_canonical_source_root(source_root, canonical_root):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="smb-direct-edit",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1

    patchdrop_root = Path(args.patchdrop_root).resolve() if args.patchdrop_root else source_root / "__patch_drop__"
    producer_script = (
        Path(args.producer_script).resolve()
        if args.producer_script
        else source_root / "__patch_drop__" / "producer_bundle.py"
    )
    if args.node_role in {"macmini", "notebook"} and not is_under_path(producer_script, source_root):
        result = failure_result(
            args=args,
            slug=slug,
            bundle=bundle,
            started=started,
            fail_reason="producer-helper-shared",
            decision="producer_handoff_failed",
        )
        append_audit_if_requested(args.audit_log, result)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 1
    node_smoke = Path(__file__).resolve().with_name("awx_mcp_node_smoke.py")

    smoke = run_json(
        [
            sys.executable,
            str(node_smoke),
            "--root",
            str(source_root),
            "--canonical-root",
            str(canonical_root),
            "--node-role",
            args.node_role,
            "--query",
            f"{slug} producer handoff",
        ],
        cwd=source_root,
    )

    raw_outputs = [smoke["raw"]]
    failures: list[str] = []
    if smoke["exitCode"] != 0 or not bool(smoke.get("json", {}).get("ok", False)):
        failures.append("node-smoke-failed")

    bundle_result: dict[str, Any] = {
        "ok": False,
        "exitCode": None,
        "sidecarsComplete": False,
        "shaVerified": False,
        "sourceIsolation": {},
        "desktopFinalProof": "evidence_needed",
        "promotionReady": False,
        "diffHeaderCount": 0,
        "filemodeLineCount": 0,
        "allowedNewFileCount": 0,
        "filemodeViolationCount": 0,
        "binaryPatchMarkerCount": 0,
        "secretPatternHits": 0,
        "rawSecretPatternHits": 0,
        "outputHash": "",
        "outputLineCount": 0,
        "failReason": "",
    }

    if not failures:
        command = [
            sys.executable,
            str(producer_script),
            "--topic",
            args.topic,
            "--node",
            args.node_role,
            "--source-root",
            str(source_root),
            "--patchdrop-root",
            str(patchdrop_root),
        ]
        for spec in pathspecs:
            command.extend(["--pathspec", spec])
        producer = run_text(command, cwd=source_root)
        producer_fail = producer_fail_reason(producer["raw"])
        producer_output_secret_hits = len(SECRET_RE.findall(producer["raw"]))
        raw_outputs.append(producer["raw"])
        sidecars_complete = all(
            (patchdrop_root / args.node_role / f"{bundle}{suffix}").exists()
            for suffix in (".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json")
        ) and (patchdrop_root / f"{slug}.{args.node_role}-pending.md").exists()
        patch_path = patchdrop_root / args.node_role / f"{bundle}.patch"
        sha_summary = read_producer_sha_summary(patchdrop_root, args.node_role, slug, bundle) if sidecars_complete else {
            "ok": False,
            "failReason": "producer-sidecars-missing",
        }
        patch_contract = validate_patch_contract(patch_path)
        bundle_paths = [
            patchdrop_root / args.node_role / f"{bundle}{suffix}"
            for suffix in (".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json")
        ]
        bundle_paths.append(patchdrop_root / f"{slug}.{args.node_role}-pending.md")
        sidecar_secret_hits = secret_hit_count_for_paths(bundle_paths)
        manifest_summary = read_manifest_summary(
            patchdrop_root / args.node_role / f"{bundle}.manifest.json",
            expected_topic=slug,
            expected_node=args.node_role,
            expected_active_patch=f"{bundle}.patch",
            expected_source_root_hash=stable_hash(str(source_root)),
            actual_patch=patch_contract,
            actual_secret_hits=sidecar_secret_hits,
            actual_raw_secret_hits=producer_output_secret_hits,
        )
        promotion_ready = (
            producer["exitCode"] == 0
            and sidecars_complete
            and manifest_summary["ok"]
            and sha_summary["ok"]
            and patch_contract["ok"]
            and sidecar_secret_hits == 0
            and producer_output_secret_hits == 0
        )
        bundle_fail_reasons = [
            reason
            for reason in (
                producer_fail,
                patch_contract["failReason"],
                manifest_summary["failReason"],
                sha_summary["failReason"],
                "secret-leak-risk" if sidecar_secret_hits or producer_output_secret_hits else "",
            )
            if reason
        ]
        bundle_result = {
            "ok": promotion_ready,
            "exitCode": producer["exitCode"],
            "sidecarsComplete": sidecars_complete,
            "shaVerified": sha_summary["ok"],
            "sourceIsolation": manifest_summary["sourceIsolation"],
            "desktopFinalProof": manifest_summary["desktopFinalProof"],
            "promotionReady": promotion_ready,
            "diffHeaderCount": patch_contract["diffHeaderCount"],
            "filemodeLineCount": patch_contract["filemodeLineCount"],
            "allowedNewFileCount": patch_contract["allowedNewFileCount"],
            "filemodeViolationCount": patch_contract["filemodeViolationCount"],
            "binaryPatchMarkerCount": patch_contract["binaryPatchMarkerCount"],
            "secretPatternHits": sidecar_secret_hits,
            "rawSecretPatternHits": producer_output_secret_hits,
            "patchHash": sha256_file(patch_path),
            "outputHash": stable_hash(producer["raw"]),
            "outputLineCount": len([line for line in producer["raw"].splitlines() if line.strip()]),
            "failReason": ",".join(bundle_fail_reasons),
        }
        if producer["exitCode"] != 0:
            failures.append("producer-bundle-failed")
            if producer_fail:
                failures.append(producer_fail)
        if not sidecars_complete:
            failures.append("producer-sidecars-missing")
        if sidecars_complete and not sha_summary["ok"]:
            failures.append(sha_summary["failReason"])
        if not manifest_summary["ok"]:
            failures.append(manifest_summary["failReason"])
        if patch_path.is_file() and not patch_contract["ok"]:
            failures.extend(reason for reason in patch_contract["failReason"].split(",") if reason)
        if sidecar_secret_hits or producer_output_secret_hits:
            failures.append("secret-leak-risk")

    raw_secret_hits = len(SECRET_RE.findall("\n".join(raw_outputs))) + int(bundle_result.get("secretPatternHits", 0))
    if raw_secret_hits and "secret-leak-risk" not in failures:
        failures.append("secret-leak-risk")

    result = {
        "schemaVersion": SCHEMA_VERSION,
        "ok": not failures,
        "requestId": "producer-handoff",
        "sessionId": safe_scalar(smoke.get("json", {}).get("sessionId", "awx-mcp-producer-handoff"), 96),
        "nodeRole": args.node_role,
        "toolName": "producer_handoff",
        "inputHash": producer_input_hash(args, pathspecs),
        "outputCount": 1 if not failures else 0,
        "topic": slug,
        "producerCommandHash": normalize_sha256(args.producer_command_hash),
        "sourceRootInputHash": stable_hash(args.source_root.strip()),
        "sourceRootHash": stable_hash(str(source_root)),
        "canonicalRootHash": stable_hash(str(canonical_root)),
        "patchDropHash": stable_hash(str(patchdrop_root)),
        "smoke": {
            "ok": bool(smoke.get("json", {}).get("ok", False)),
            "exitCode": smoke["exitCode"],
            "decision": safe_scalar(smoke.get("json", {}).get("decision", ""), 80),
            "evidence_needed": smoke.get("json", {}).get("evidence_needed", []),
        },
        "bundle": bundle_result,
        "desktopFinalProof": "evidence_needed",
        "rawSecretPatternHits": raw_secret_hits,
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
        "decision": "producer_handoff" if not failures else "producer_handoff_failed",
        "failReason": ",".join(failures),
    }
    append_audit_if_requested(args.audit_log, result)
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return 0 if result["ok"] else 1


def is_shared_source_root(raw_path: str) -> bool:
    stripped = raw_path.strip()
    if stripped.startswith("\\\\"):
        return True
    if windows_mapped_drive_root(stripped):
        return True
    normalized = stripped.replace("\\", "/").lower()
    parts = [part for part in normalized.split("/") if part]
    if "patchdrop" in parts or "__patch_drop__" in parts:
        return True
    return normalized.startswith(("/volumes/", "/mnt/", "/media/"))


def windows_mapped_drive_root(raw_path: str) -> str:
    stripped = raw_path.strip()
    if not re.match(r"^[A-Za-z]:", stripped):
        return ""
    if os.name != "nt":
        return ""
    try:
        import ctypes
        from ctypes import wintypes

        drive = stripped[:2].upper()
        size = wintypes.DWORD(512)
        buffer = ctypes.create_unicode_buffer(size.value)
        result = ctypes.windll.mpr.WNetGetConnectionW(drive, buffer, ctypes.byref(size))
        if result == 234 and size.value > len(buffer):
            buffer = ctypes.create_unicode_buffer(size.value)
            result = ctypes.windll.mpr.WNetGetConnectionW(drive, buffer, ctypes.byref(size))
        if result != 0:
            return ""
        remote = buffer.value.strip()
        return remote if remote.startswith("\\\\") else ""
    except Exception:
        return ""


def is_under_canonical_source_root(source_root: Path, canonical_root: Path) -> bool:
    return source_root == canonical_root or canonical_root in source_root.parents


def is_under_path(child: Path, parent: Path) -> bool:
    return child == parent or parent in child.parents


def producer_fail_reason(raw_output: str) -> str:
    match = PRODUCER_FAIL_RE.search(raw_output or "")
    if not match:
        return ""
    return safe_scalar(match.group(1), 120)


def secret_hit_count_for_paths(paths: list[Path]) -> int:
    hits = 0
    for path in paths:
        if not path.is_file():
            continue
        try:
            hits += len(SECRET_RE.findall(path.read_text(encoding="utf-8", errors="replace")))
        except OSError:
            hits += 1
    return hits


def validate_patch_contract(patch_path: Path) -> dict[str, Any]:
    if not patch_path.is_file():
        return {
            "ok": False,
            "failReason": "producer-patch-missing",
            "diffHeaderCount": 0,
            "filemodeLineCount": 0,
            "allowedNewFileCount": 0,
            "filemodeViolationCount": 0,
            "binaryPatchMarkerCount": 0,
            "forbiddenPathCount": 0,
            "secretPatternHits": 0,
        }
    try:
        patch_text = patch_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {
            "ok": False,
            "failReason": "producer-patch-unreadable",
            "diffHeaderCount": 0,
            "filemodeLineCount": 0,
            "allowedNewFileCount": 0,
            "filemodeViolationCount": 0,
            "binaryPatchMarkerCount": 0,
            "forbiddenPathCount": 0,
            "secretPatternHits": 0,
        }

    failures: list[str] = []
    mode_summary = patch_mode_summary_text(patch_text)
    if mode_summary["filemodeViolationCount"]:
        failures.append("filemode-blocked")
    binary_markers = len(re.findall(r"(?m)^(GIT binary patch|Binary files .+ differ)$", patch_text))
    if binary_markers:
        failures.append("binary-patch-blocked")
    secret_hits = len(SECRET_RE.findall(patch_text))
    if secret_hits:
        failures.append("secret-leak-risk")
    forbidden = forbidden_patch_paths_text(patch_text)
    if forbidden:
        failures.append("forbidden-path:" + ",".join(sorted({item["reason"] for item in forbidden})))
    diff_headers = len(re.findall(r"(?m)^diff --git ", patch_text.lstrip("\ufeff")))
    if not patch_text.strip():
        failures.append("producer-patch-empty")
    elif diff_headers == 0:
        failures.append("producer-patch-not-unified-diff")
    elif patch_structure_violation_count_text(patch_text):
        failures.append("producer-patch-not-unified-diff")
    return {
        "ok": not failures,
        "failReason": ",".join(failures),
        "diffHeaderCount": diff_headers,
        "filemodeLineCount": mode_summary["filemodeLineCount"],
        "allowedNewFileCount": mode_summary["allowedNewFileCount"],
        "filemodeViolationCount": mode_summary["filemodeViolationCount"],
        "binaryPatchMarkerCount": binary_markers,
        "forbiddenPathCount": len(forbidden),
        "secretPatternHits": secret_hits,
    }


def patch_mode_summary_text(patch_text: str) -> dict[str, int]:
    text = patch_text.lstrip("\ufeff")
    blocks: list[list[str]] = []
    preamble: list[str] = []
    current: list[str] | None = None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            if current is not None:
                blocks.append(current)
            current = [line]
        elif current is None:
            preamble.append(line)
        else:
            current.append(line)
    if current is not None:
        blocks.append(current)

    mode_header = re.compile(r"^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$")
    preamble_modes = [line for line in preamble if mode_header.match(line)]
    total = len(preamble_modes)
    allowed = 0
    violations = len(preamble_modes)
    seen_targets: set[str] = set()
    for block in blocks:
        mode_lines = [line for line in block if mode_header.match(line)]
        total += len(mode_lines)
        envelope = patch_envelope_lines(block)
        has_old_null = "--- /dev/null" in envelope
        has_new_null = "+++ /dev/null" in envelope
        if has_new_null or (has_old_null and not mode_lines):
            violations += max(1, len(mode_lines))
            continue
        if not mode_lines:
            continue
        header = re.fullmatch(r'diff --git a/([^\s"\\]+) b/([^\s"\\]+)', block[0]) if block else None
        target = header.group(1) if header is not None and header.group(1) == header.group(2) else ""
        if is_canonical_new_file_block(block, mode_lines) and target not in seen_targets:
            seen_targets.add(target)
            allowed += 1
        else:
            violations += len(mode_lines)
    return {
        "filemodeLineCount": total,
        "allowedNewFileCount": allowed,
        "filemodeViolationCount": violations,
    }


def patch_envelope_lines(lines: list[str]) -> list[str]:
    first_hunk = next((index for index, line in enumerate(lines) if line.startswith("@@ ")), len(lines))
    return lines[:first_hunk]


def is_canonical_repo_relative_patch_path(value: str) -> bool:
    if not value or value != value.replace("\\", "/"):
        return False
    if value.startswith(("/", "//")) or re.match(r"^[A-Za-z]:", value):
        return False
    if re.search(r"[\x00-\x20\x7f\"<>:|?*]", value):
        return False
    return all(
        part not in {"", ".", ".."}
        and not part.endswith((".", " "))
        and re.match(r"^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", part, re.IGNORECASE) is None
        for part in value.split("/")
    )


def is_canonical_new_file_block(block: list[str], mode_lines: list[str]) -> bool:
    if not block or mode_lines != ["new file mode 100644"]:
        return False
    header = re.fullmatch(r'diff --git a/([^\s"\\]+) b/([^\s"\\]+)', block[0])
    if header is None or header.group(1) != header.group(2):
        return False
    target = header.group(1)
    if not is_canonical_repo_relative_patch_path(target):
        return False
    if any(pattern.search(target) for pattern, _ in FORBIDDEN_PATCH_PATH_PATTERNS):
        return False
    envelope = patch_envelope_lines(block)
    old_headers = [line for line in envelope if line.startswith("--- ")]
    new_headers = [line for line in envelope if line.startswith("+++ ")]
    if old_headers != ["--- /dev/null"] or new_headers != [f"+++ b/{target}"]:
        return False
    if not has_canonical_new_file_hunk(block):
        return False
    return not any(
        line.startswith(("rename from ", "rename to ", "copy from ", "copy to "))
        or line == "GIT binary patch"
        or (line.startswith("Binary files ") and line.endswith(" differ"))
        for line in block
    )


def has_exact_hunk_sequence(lines: list[str], *, require_new_file: bool) -> bool:
    first_hunk = next((index for index, line in enumerate(lines) if line.startswith("@@ ")), -1)
    if first_hunk < 0:
        return False
    envelope = lines[:first_hunk]
    old_indexes = [index for index, line in enumerate(envelope) if line.startswith("--- ")]
    new_indexes = [index for index, line in enumerate(envelope) if line.startswith("+++ ")]
    if len(old_indexes) != 1 or len(new_indexes) != 1 or new_indexes[0] != old_indexes[0] + 1:
        return False
    if first_hunk != new_indexes[0] + 1:
        return False
    index = first_hunk
    hunk_count = 0
    header_pattern = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$")
    while index < len(lines):
        header = header_pattern.fullmatch(lines[index])
        if header is None:
            return False
        old_start = int(header.group(1))
        old_expected = int(header.group(2)) if header.group(2) is not None else 1
        new_start = int(header.group(3))
        new_expected = int(header.group(4)) if header.group(4) is not None else 1
        if require_new_file and (hunk_count != 0 or old_start != 0 or old_expected != 0 or new_start != 1 or new_expected <= 0):
            return False
        hunk_count += 1
        index += 1
        old_actual = 0
        new_actual = 0
        old_eof_marked = False
        new_eof_marked = False
        while index < len(lines) and not lines[index].startswith("@@ "):
            line = lines[index]
            if line == r"\ No newline at end of file":
                if index == 0 or not lines[index - 1].startswith((" ", "+", "-")):
                    return False
                if any(candidate.startswith("@@ ") for candidate in lines[index + 1 :]):
                    return False
                previous_kind = lines[index - 1][0]
                if previous_kind == "-":
                    if old_eof_marked or new_eof_marked:
                        return False
                    old_eof_marked = True
                elif previous_kind == "+":
                    if new_eof_marked:
                        return False
                    new_eof_marked = True
                else:
                    if old_eof_marked or new_eof_marked:
                        return False
                    old_eof_marked = True
                    new_eof_marked = True
                index += 1
                continue
            if not line or line[0] not in {" ", "+", "-"}:
                return False
            if new_eof_marked or (old_eof_marked and line[0] != "+"):
                return False
            if require_new_file and line[0] != "+":
                return False
            if line[0] in {" ", "-"}:
                old_actual += 1
            if line[0] in {" ", "+"}:
                new_actual += 1
            if old_actual > old_expected or new_actual > new_expected:
                return False
            index += 1
        if old_actual != old_expected or new_actual != new_expected:
            return False
    return hunk_count == 1 if require_new_file else hunk_count > 0


def has_canonical_new_file_hunk(lines: list[str]) -> bool:
    return has_exact_hunk_sequence(lines, require_new_file=True)


def patch_structure_violation_count_text(patch_text: str) -> int:
    text = patch_text.lstrip("\ufeff")
    blocks: list[list[str]] = []
    preamble: list[str] = []
    current: list[str] | None = None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            if current is not None:
                blocks.append(current)
            current = [line]
        elif current is None:
            preamble.append(line)
        else:
            current.append(line)
    if current is not None:
        blocks.append(current)
    violations = 1 if any(line.strip() for line in preamble) or not blocks else 0
    seen_targets: set[str] = set()
    for block in blocks:
        header = re.fullmatch(r'diff --git a/([^\s"\\]+) b/([^\s"\\]+)', block[0])
        if header is None or header.group(1) != header.group(2):
            violations += 1
            continue
        target = header.group(1)
        if not is_canonical_repo_relative_patch_path(target) or target in seen_targets:
            violations += 1
            continue
        seen_targets.add(target)
        envelope = patch_envelope_lines(block)
        old_headers = [line for line in envelope if line.startswith("--- ")]
        new_headers = [line for line in envelope if line.startswith("+++ ")]
        mode_lines = [
            line
            for line in block
            if re.match(r"^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$", line)
        ]
        if old_headers == ["--- /dev/null"]:
            if not is_canonical_new_file_block(block, mode_lines):
                violations += 1
        elif old_headers != [f"--- a/{target}"] or new_headers != [f"+++ b/{target}"]:
            violations += 1
        elif not has_exact_hunk_sequence(block, require_new_file=False):
            violations += 1
    parsed = subprocess.run(
        ["git", "apply", "--numstat", "--whitespace=nowarn", "-"],
        input=text.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if parsed.returncode != 0:
        violations += 1
    return violations


def normalize_patch_path(value: str) -> str:
    path = value.strip().strip('"').replace("\\", "/")
    if path in {"", "/dev/null"}:
        return ""
    if path.startswith(("a/", "b/")):
        path = path[2:]
    return path


def forbidden_patch_paths_text(patch_text: str) -> list[dict[str, str]]:
    paths: list[str] = []
    seen: set[str] = set()
    hunk_started = False
    for line in patch_text.lstrip("\ufeff").splitlines():
        candidates: list[str] = []
        if line.startswith("diff --git "):
            hunk_started = False
            parts = line.split()
            candidates.extend(parts[2:4])
        elif line.startswith("@@ "):
            hunk_started = True
        elif not hunk_started and line.startswith(("+++ ", "--- ")):
            candidates.append(line[4:].split("\t", 1)[0])
        elif line.startswith(("rename from ", "rename to ", "copy from ", "copy to ")):
            candidates.append(line.split(" ", 2)[2])
        for candidate in candidates:
            normalized = normalize_patch_path(candidate)
            if normalized and normalized not in seen:
                seen.add(normalized)
                paths.append(normalized)
    blocked: list[dict[str, str]] = []
    for target in paths:
        if not is_canonical_repo_relative_patch_path(target):
            blocked.append({"path": target, "reason": "unsafe-path"})
            continue
        for pattern, reason in FORBIDDEN_PATCH_PATH_PATTERNS:
            if pattern.search(target):
                blocked.append({"path": target, "reason": reason})
                break
    return blocked


def failure_result(
    *,
    args: argparse.Namespace,
    slug: str,
    bundle: str,
    started: float,
    fail_reason: str,
    decision: str,
) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "ok": False,
        "requestId": "producer-handoff",
        "sessionId": "awx-mcp-producer-handoff",
        "nodeRole": args.node_role,
        "toolName": "producer_handoff",
        "inputHash": producer_input_hash(args, []),
        "outputCount": 0,
        "topic": slug,
        "producerCommandHash": normalize_sha256(args.producer_command_hash),
        "sourceRootInputHash": stable_hash(str(args.source_root).strip()),
        "sourceRootHash": stable_hash(str(args.source_root)),
        "canonicalRootHash": stable_hash(str(args.canonical_root)),
        "patchDropHash": stable_hash(str(args.patchdrop_root)),
        "smoke": {
            "ok": False,
            "exitCode": None,
            "decision": "not_run",
            "evidence_needed": [],
        },
        "bundle": {
            "ok": False,
            "exitCode": None,
            "sidecarsComplete": False,
            "shaVerified": False,
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "promotionReady": False,
            "diffHeaderCount": 0,
            "patchHash": "",
            "outputHash": "",
            "outputLineCount": 0,
        },
        "desktopFinalProof": "evidence_needed",
        "rawSecretPatternHits": 0,
        "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
        "decision": decision,
        "failReason": fail_reason,
    }


def producer_input_hash(args: argparse.Namespace, pathspecs: list[str]) -> str:
    payload = {
        "sourceRoot": str(getattr(args, "source_root", "")),
        "canonicalRoot": str(getattr(args, "canonical_root", "")),
        "patchdropRoot": str(getattr(args, "patchdrop_root", "")),
        "nodeRole": str(getattr(args, "node_role", "")),
        "topic": str(getattr(args, "topic", "")),
        "producerCommandHash": normalize_sha256(getattr(args, "producer_command_hash", "")),
        "pathspecCount": len(pathspecs),
    }
    return stable_hash(json.dumps(payload, sort_keys=True, separators=(",", ":")))


def append_audit_if_requested(raw_path: str, result: dict[str, Any]) -> None:
    if not raw_path.strip():
        return
    path = Path(raw_path).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {key: result.get(key, "") for key in SAFE_AUDIT_FIELDS}
    path.write_text("", encoding="utf-8") if not path.exists() else None
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=True, separators=(",", ":")) + "\n")


def read_manifest_summary(
    manifest_path: Path,
    *,
    expected_topic: str,
    expected_node: str,
    expected_active_patch: str,
    expected_source_root_hash: str,
    actual_patch: dict[str, Any],
    actual_secret_hits: int,
    actual_raw_secret_hits: int,
) -> dict[str, Any]:
    if not manifest_path.exists():
        return {
            "ok": False,
            "failReason": "producer-manifest-missing",
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "diffHeaderCount": 0,
        }
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {
            "ok": False,
            "failReason": "producer-manifest-invalid",
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "diffHeaderCount": 0,
        }
    if not isinstance(data, dict):
        return {
            "ok": False,
            "failReason": "producer-manifest-invalid",
            "sourceIsolation": {},
            "desktopFinalProof": "evidence_needed",
            "diffHeaderCount": 0,
        }

    desktop_final_proof = safe_scalar(data.get("desktopFinalProof", ""), 80)
    verification = data.get("verification") if isinstance(data.get("verification"), dict) else {}
    diff_header_count = actual_patch["diffHeaderCount"]
    isolation = data.get("sourceIsolation")
    if not isinstance(isolation, dict):
        return {
            "ok": False,
            "failReason": "producer-source-isolation-missing",
            "sourceIsolation": {},
            "desktopFinalProof": desktop_final_proof,
            "diffHeaderCount": diff_header_count,
        }

    source_isolation = {
        "guard": safe_scalar(isolation.get("guard", ""), 40),
        "sourceRootKind": safe_scalar(isolation.get("sourceRootKind", ""), 40),
        "sharedSourceRoot": isolation.get("sharedSourceRoot"),
        "desktopCanonicalSourceRoot": isolation.get("desktopCanonicalSourceRoot"),
        "directCanonicalSourceEdit": isolation.get("directCanonicalSourceEdit"),
        "gitRootPresent": isolation.get("gitRootPresent") is True,
        "gitRootMatchesSourceRoot": isolation.get("gitRootMatchesSourceRoot") is True,
        "gitRootHash": safe_scalar(isolation.get("gitRootHash", ""), 120),
    }
    failures: list[str] = []
    exact_fields = {
        "schemaVersion": "patchdrop-producer-v3",
        "topic": expected_topic,
        "slug": expected_topic,
        "node": expected_node,
        "activePatch": expected_active_patch,
        "desktopFinalProof": "evidence_needed",
        "sourceRootInputHash": expected_source_root_hash,
        "sourceRootHash": expected_source_root_hash,
    }
    for name, expected in exact_fields.items():
        if data.get(name) != expected:
            failures.append(f"producer-manifest-{name}")
    if (
        source_isolation["guard"] != "PASS"
        or source_isolation["sourceRootKind"] != "local-worktree"
        or source_isolation["sharedSourceRoot"] is not False
        or source_isolation["desktopCanonicalSourceRoot"] is not False
        or source_isolation["directCanonicalSourceEdit"] is not False
        or not source_isolation["gitRootPresent"]
        or not source_isolation["gitRootMatchesSourceRoot"]
        or source_isolation["gitRootHash"] != expected_source_root_hash
    ):
        failures.append("producer-source-isolation-violation")

    expected_metrics = {
        "diffHeaderCount": actual_patch["diffHeaderCount"],
        "filemodeLineCount": actual_patch["filemodeLineCount"],
        "allowedNewFileCount": actual_patch["allowedNewFileCount"],
        "filemodeViolationCount": actual_patch["filemodeViolationCount"],
        "forbiddenPathCount": actual_patch["forbiddenPathCount"],
        "secretPatternHits": actual_secret_hits,
        "rawSecretPatternHits": actual_raw_secret_hits,
    }
    for name, expected in expected_metrics.items():
        value = verification.get(name)
        if type(value) is not int or value != expected:
            failures.append(f"producer-manifest-verification-{name}")
    if diff_header_count <= 0:
        failures.append("producer-patch-not-unified-diff")
    return {
        "ok": not failures,
        "failReason": ",".join(failures),
        "sourceIsolation": source_isolation,
        "desktopFinalProof": desktop_final_proof,
        "diffHeaderCount": diff_header_count,
    }


def read_producer_sha_summary(patchdrop_root: Path, node_role: str, slug: str, bundle: str) -> dict[str, Any]:
    node_dir = patchdrop_root / node_role
    sha_path = node_dir / f"{bundle}.sha256.txt"
    expected_files = {
        f"{bundle}.patch": node_dir / f"{bundle}.patch",
        f"{bundle}.report.md": node_dir / f"{bundle}.report.md",
        f"{bundle}.verify.log": node_dir / f"{bundle}.verify.log",
        f"{bundle}.manifest.json": node_dir / f"{bundle}.manifest.json",
        f"../{slug}.{node_role}-pending.md": patchdrop_root / f"{slug}.{node_role}-pending.md",
    }
    if not sha_path.exists():
        return {"ok": False, "failReason": "producer-sha-sidecar-missing"}

    entries: dict[str, str] = {}
    try:
        lines = sha_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return {"ok": False, "failReason": "producer-sha-sidecar-unreadable"}
    for line in lines:
        parts = line.strip().split(None, 1)
        if len(parts) != 2:
            continue
        digest, name = parts[0].lower(), parts[1].strip().replace("\\", "/")
        if re.fullmatch(r"[a-f0-9]{64}", digest):
            entries[name] = digest

    missing_entries = [name for name in expected_files if name not in entries]
    if missing_entries:
        return {
            "ok": False,
            "failReason": "producer-sha-entry-missing:" + ",".join(missing_entries),
        }

    for name, path in expected_files.items():
        if not path.exists():
            return {"ok": False, "failReason": "producer-sidecars-missing:" + name}
        actual = sha256_file(path)
        if actual != entries[name]:
            return {"ok": False, "failReason": "producer-sha-mismatch:" + name}

    return {"ok": True, "failReason": ""}


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def run_json(command: list[str], cwd: Path) -> dict[str, Any]:
    raw = run_text(command, cwd)
    try:
        raw["json"] = json.loads(raw["stdout"])
    except json.JSONDecodeError:
        raw["json"] = {"ok": False, "decision": "json_parse_failed", "failReason": "json-parse"}
    return raw


def run_text(command: list[str], cwd: Path) -> dict[str, Any]:
    proc = subprocess.run(
        command,
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    raw = (proc.stdout or "") + (proc.stderr or "")
    return {"exitCode": proc.returncode, "stdout": proc.stdout or "", "raw": raw}


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9._-]+", "-", value.strip().lower()).strip("-")
    if not slug:
        raise SystemExit("topic-invalid")
    return slug


def safe_scalar(value: Any, limit: int) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\r", " ").replace("\n", " ").strip()
    return text[:limit]


def normalize_sha256(value: Any) -> str:
    text = safe_scalar(value, 120).lower()
    return text if re.fullmatch(r"[a-f0-9]{64}", text) else ""


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="ignore")).hexdigest()


def sha256_file(path: Path) -> str:
    if not path.exists():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
