"""Read-only continuation advice; validates receipts, never executes a patch."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
import time

TASK_ID = "meta-display-sync-v1-20260912"
SKILL = ".agents/skills/demo1-meta-display-webapp"
TASK_FILES = [
    f"{SKILL}/SKILL.md", f"{SKILL}/agents/openai.yaml",
    f"{SKILL}/references/source-contract.md",
    f"{SKILL}/references/continuation-contract.md",
    f"{SKILL}/scripts/next_step.py",
    "agent-prompts/meta_rayban_display_sync_v1_20260912.md",
    ".agents/skills/demo1-meta-display-sync-client/SKILL.md",
    ".agents/skills/demo1-meta-display-sync-client/agents/openai.yaml",
    ".agents/skills/demo1-meta-display-resume/SKILL.md",
    ".agents/skills/demo1-meta-display-resume/agents/openai.yaml",
    ".agents/skills/demo1-meta-display-verification/SKILL.md",
    ".agents/skills/demo1-meta-display-verification/agents/openai.yaml",
]
RECEIPT_DIR = "data/agent-handoff/codex/report/meta-display-sync-v1"
CANONICAL = "C:/AbandonWare/demo-1/demo-1/src"
APP = "main/resources/static/assets/display/"
CORE = [APP + "display-core.js", "scripts/meta_display_webapp_contract_tests.cjs"]
UI = CORE + [APP + name for name in ("index.html", "styles.css", "app.js")]
ALL_CLIENT = UI + [APP + name for name in ("manifest.webmanifest", "favicon.png")]
BACKEND = [
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java",
    "main/java/com/example/lms/api/ChatSessionAccessGuard.java",
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/dto/ChatRequestDto.java",
    "main/java/com/example/lms/dto/ChatResponseDto.java",
    "main/java/com/example/lms/dto/RagEvidenceMetadata.java",
    "main/java/com/example/lms/security/ChatOpenSecurityConfig.java",
    "main/java/com/example/lms/config/AppSecurityConfig.java",
    "main/java/com/example/lms/web/OwnerKeyBootstrapFilter.java",
    "main/java/com/example/lms/web/ClientOwnerKeyResolver.java",
    "main/java/com/example/lms/service/ChatService.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/prompt/PromptBuilder.java",
    "main/resources/redis/chat_admission.lua",
]
STAGES = {
    "E0": {"targets": ["AGENTS.md", "settings.gradle", "build.gradle.kts", "app/build.gradle.kts"],
           "checks": {"intake-reviewed": "desktop-intake"}},
    "E1": {"targets": CORE, "checks": {"client-contracts": "command"}},
    "E2": {"targets": UI, "checks": {"navigation": "browser"}},
    "E3": {"targets": ALL_CLIENT + BACKEND,
           "checks": {"local-sync": "browser", "session-continuity": "browser", "meta-shell": "browser"}},
    "E4": {"targets": ALL_CLIENT + BACKEND, "checks": {"official-simulator": "official-simulator"}},
}
MAX_RECEIPT = 65536
MAX_EVIDENCE = 1024 * 1024
MAX_TARGET = 8 * 1024 * 1024
RUNTIME_MAX_AGE_SECONDS = 86400
SECRET = re.compile(r"sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|Bearer\s+[A-Za-z0-9._~+/-]{16,}=*")


class EvidenceError(Exception):
    pass


def checked_path(root, relative):
    if not isinstance(relative, str) or not relative or ":" in relative or any(
            ord(c) < 32 or ord(c) == 127 for c in relative):
        raise EvidenceError("unsafe-path")
    normalized = relative.replace("\\", "/")
    parts = normalized.split("/")
    if normalized.startswith("/") or any(p in ("", ".", "..") or p.endswith((".", " ")) for p in parts):
        raise EvidenceError("unsafe-path")
    current = Path(root)
    for part in [None] + parts:
        if part is not None:
            current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise EvidenceError("unsafe-path")
    return current


def read_bounded(root, relative, limit):
    path = checked_path(root, relative)
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise EvidenceError("unsafe-path")
        if before.st_size > limit:
            raise EvidenceError("input-too-large")
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    checked_path(root, relative)
    if len(data) > limit:
        raise EvidenceError("input-too-large")
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise EvidenceError("evidence-changed-during-read")
    return data


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def target_hash(root, relative):
    return hashlib.sha256(read_bounded(root, relative, MAX_TARGET)).hexdigest()


def task_binding(root):
    values = {p: target_hash(root, p) for p in TASK_FILES}
    encoded = json.dumps(values, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError("duplicate-json-key")
        result[key] = value
    return result


def read_json(data):
    try:
        text = data.decode("utf-8-sig")
        if SECRET.search(text):
            raise EvidenceError("secret-pattern-detected")
        result = json.loads(text, object_pairs_hook=no_duplicate_keys)
        if not isinstance(result, dict):
            raise EvidenceError("invalid-record")
        return result
    except (UnicodeError, ValueError, RecursionError):
        raise EvidenceError("invalid-json") from None


def normalized_windows_path(value):
    return value.replace("\\", "/").rstrip("/").casefold() if isinstance(value, str) else ""


def validate_intake(data):
    intake = read_json(data)
    if not (intake.get("status") == "PASS" and intake.get("reason") == "match"
            and normalized_windows_path(intake.get("canonicalExecutionRoot")) == CANONICAL.casefold()
            and intake.get("targetRel") == "AGENTS.md"
            and normalized_windows_path(intake.get("desktopTarget")) == (CANONICAL + "/AGENTS.md").casefold()
            and intake.get("withinRoot") is True and intake.get("exists") is True
            and intake.get("reparseRisk") is False):
        raise EvidenceError("desktop-intake-unproven")


def validate_receipt(root, stage, record, binding, deadline):
    def check_budget():
        if time.monotonic() > deadline:
            raise EvidenceError("inspection-budget-exceeded")

    check_budget()
    if not (type(record.get("schemaVersion")) is int and record["schemaVersion"] == 1
            and record.get("taskId") == TASK_ID and record.get("stage") == stage):
        raise EvidenceError("record-contract-mismatch")
    if not (record.get("status") == "pass" and record.get("executionOwner") == "desktop"
            and record.get("executionMode") == "executed" and record.get("performedNow") is True):
        raise EvidenceError("verification-unproven")
    if record.get("taskBinding") != binding:
        raise EvidenceError("task-binding-stale")
    hashes = record.get("targetHashes")
    if not isinstance(hashes, dict) or set(hashes) != set(STAGES[stage]["targets"]):
        raise EvidenceError("target-contract-mismatch")
    for relative, expected in hashes.items():
        check_budget()
        if target_hash(root, relative) != expected:
            raise EvidenceError("target-hash-stale")
        check_budget()
    checks = record.get("checks")
    if not isinstance(checks, dict) or set(checks) != set(STAGES[stage]["checks"]):
        raise EvidenceError("check-contract-mismatch")
    for name, kind in STAGES[stage]["checks"].items():
        check_budget()
        check = checks[name]
        if not isinstance(check, dict) or not (check.get("kind") == kind
                and check.get("performedNow") is True and check.get("result") == "pass"):
            raise EvidenceError("verification-unproven")
        if (kind in ("command", "desktop-intake") or "exitCode" in check) and (
                type(check.get("exitCode")) is not int or check["exitCode"] != 0):
            raise EvidenceError("verification-unproven")
        try:
            observed = datetime.fromisoformat(check["observedAt"].replace("Z", "+00:00"))
            if observed.tzinfo is None:
                raise ValueError()
            age = (datetime.now(timezone.utc) - observed).total_seconds()
        except (KeyError, TypeError, AttributeError, ValueError, OverflowError):
            raise EvidenceError("verification-time-unproven") from None
        if age < -300:
            raise EvidenceError("verification-time-unproven")
        if stage in ("E3", "E4") and age > RUNTIME_MAX_AGE_SECONDS:
            raise EvidenceError("runtime-evidence-stale")
        evidence = check.get("evidence")
        if not isinstance(evidence, dict):
            raise EvidenceError("evidence-reference-missing")
        relative = evidence.get("path")
        if not isinstance(relative, str) or not relative.replace("\\", "/").startswith(RECEIPT_DIR + "/evidence/"):
            raise EvidenceError("unsafe-path")
        data = read_bounded(root, relative, MAX_EVIDENCE)
        check_budget()
        if not data or hashlib.sha256(data).hexdigest() != evidence.get("sha256"):
            raise EvidenceError("evidence-hash-stale")
        if SECRET.search(data.decode("utf-8", errors="replace")):
            raise EvidenceError("secret-pattern-detected")
        if kind == "desktop-intake":
            validate_intake(data)
        check_budget()


def inspect(root, *, desktop_execution=False):
    root = Path(root).absolute()
    deadline = time.monotonic() + 25
    result = {
        "schemaVersion": 1, "taskId": TASK_ID, "status": "READY",
        "nextStage": "E0", "nextAction": "perform-desktop-intake", "reason": "evidence-needed",
        "acceptedStageCount": 0, "taskBinding": None,
        "observedExecutionOwner": "desktop" if desktop_execution else "notebook-supporting",
        "receiptConsistencyOnly": True, "sourceMutationAllowed": False,
        "sourceGateRequiredBeforeMutation": True, "desktopFinalProof": "evidence_needed",
    }
    try:
        binding = task_binding(root)
        if time.monotonic() > deadline:
            raise EvidenceError("inspection-budget-exceeded")
        result["taskBinding"] = binding
    except FileNotFoundError:
        result.update(status="HOLD", reason="input-artifact-missing", nextAction="restore-required-task-artifact")
        return result
    except (OSError, EvidenceError) as error:
        result.update(status="HOLD", reason=str(error) if isinstance(error, EvidenceError) else "input-unreadable")
        return result
    for stage in STAGES:
        result.update(nextStage=stage, nextAction="perform-desktop-intake" if stage == "E0" else "continue-declared-stage")
        try:
            record_data = read_bounded(root, f"{RECEIPT_DIR}/{stage}.json", MAX_RECEIPT)
            if time.monotonic() > deadline:
                raise EvidenceError("inspection-budget-exceeded")
        except FileNotFoundError:
            return result
        except (OSError, EvidenceError) as error:
            result.update(status="HOLD", reason=str(error) if isinstance(error, EvidenceError) else "record-unreadable", nextAction="repair-stage-evidence")
            return result
        try:
            record = read_json(record_data)
            validate_receipt(root, stage, record, binding, deadline)
        except (OSError, EvidenceError) as error:
            result.update(status="HOLD", reason=str(error) if isinstance(error, EvidenceError) else "evidence-unreadable", nextAction="repair-stage-evidence")
            return result
        result["acceptedStageCount"] += 1
    result.update(status="RECEIPTS_COMPLETE", nextStage=None, reason="records-consistent",
                  nextAction="review-desktop-final-evidence", completionScope="receipt-consistency-only")
    return result


def is_desktop_execution(root):
    if os.name != "nt" or platform.node().casefold() != "desktop-m5nov6k":
        return False
    if normalized_windows_path(str(Path(root).absolute())) != CANONICAL.casefold():
        return False
    try:
        response = subprocess.run(["git", "--no-optional-locks", "-C", str(root), "rev-parse", "--show-toplevel"],
                                  capture_output=True, text=True, timeout=5, check=False)
        return response.returncode == 0 and normalized_windows_path(response.stdout.strip()) == CANONICAL.casefold()
    except (OSError, subprocess.SubprocessError):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--requirements", action="store_true")
    args = parser.parse_args()
    if args.requirements:
        result = {"taskId": TASK_ID, "receiptDirectory": RECEIPT_DIR, "taskFiles": TASK_FILES,
                  "stages": STAGES, "sourceMutationAllowed": False, "receiptConsistencyOnly": True,
                  "receiptFields": ["schemaVersion", "taskId", "stage", "status", "executionOwner",
                                    "executionMode", "performedNow", "taskBinding", "targetHashes", "checks"],
                  "checkFields": ["kind", "performedNow", "observedAt", "result", "evidence"],
                  "commandExitCodeRequired": ["command", "desktop-intake"],
                  "runtimeMaxAgeSeconds": RUNTIME_MAX_AGE_SECONDS}
    else:
        result = inspect(args.root, desktop_execution=is_desktop_execution(args.root))
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return 2 if result.get("status") == "HOLD" else 0


if __name__ == "__main__":
    raise SystemExit(main())
