#!/usr/bin/env python3
"""Run bounded test profiles through a hardened Docker CLI contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import signal
import shlex
import shutil
import subprocess
import sys
import tempfile
import tarfile
import threading
import time
from typing import Any
import xml.etree.ElementTree as ET


SCHEMA_JOB = "awx.docker-autograder.job.v1"
SCHEMA_RESULT = "awx.docker-autograder.result.v1"
ALLOWED_PROFILES = {"pytest", "gradle-junit"}
ALLOWED_PURPOSES = {"RED_PROBE", "GREEN_VERIFICATION"}
PROHIBITED_PARTS = {".git", ".gradle", "build", "node_modules", ".next", ".turbo"}
HASH_RE = re.compile(r"^[a-f0-9]{64}$")
IMAGE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/:@-]*@sha256:[a-f0-9]{64}$")
RUN_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$")
SELECTOR_RE = re.compile(r"^[A-Za-z0-9_./:*\[\]-]{1,240}$")
SECRET_RE = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|"
    r"gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|"
    r"sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,})"
)
MAX_JOB_BYTES = 262_144


class GraderError(Exception):
    def __init__(self, classification: str, detail: str = "") -> None:
        super().__init__(f"{classification}: {detail}" if detail else classification)
        self.classification = classification
        self.detail = detail


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def read_ready_json(path: Path, failure_class: str) -> tuple[dict[str, Any], str, bytes]:
    if not path.is_file():
        raise GraderError(failure_class, "json-missing")
    raw = path.read_bytes()
    if len(raw) > MAX_JOB_BYTES:
        raise GraderError(failure_class, "json-too-large")
    digest = sha256_bytes(raw)
    sidecar = path.with_name(path.name + ".sha256")
    ready = path.with_name(path.name + ".ready")
    if not sidecar.is_file() or not ready.is_file():
        raise GraderError(failure_class, "publication-incomplete")
    if sidecar.read_text(encoding="utf-8").strip().lower() != digest:
        raise GraderError(failure_class, "sidecar-mismatch")
    if ready.read_text(encoding="utf-8").strip().lower() != digest:
        raise GraderError(failure_class, "ready-mismatch")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GraderError(failure_class, "invalid-json") from exc
    if not isinstance(value, dict):
        raise GraderError(failure_class, "not-object")
    return value, digest, raw


def publish_ready_json(path: Path, value: dict[str, Any]) -> str:
    sidecar = path.with_name(path.name + ".sha256")
    ready = path.with_name(path.name + ".ready")
    if path.exists() or sidecar.exists() or ready.exists():
        raise GraderError("autograder-result-already-exists", str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    token = hashlib.sha256(f"{time.time_ns()}-{os.getpid()}".encode()).hexdigest()[:16]
    temps = [
        path.with_name(f".{path.name}.{token}.tmp"),
        sidecar.with_name(f".{sidecar.name}.{token}.tmp"),
        ready.with_name(f".{ready.name}.{token}.tmp"),
    ]
    try:
        raw = json.dumps(value, indent=2, sort_keys=False).encode("utf-8") + b"\n"
        temps[0].write_bytes(raw)
        digest = sha256_bytes(raw)
        temps[1].write_text(digest + "\n", encoding="utf-8", newline="")
        temps[2].write_text(digest + "\n", encoding="utf-8", newline="")
        os.replace(temps[0], path)
        os.replace(temps[1], sidecar)
        os.replace(temps[2], ready)
        return digest
    finally:
        for temp in temps:
            temp.unlink(missing_ok=True)


def is_reparse(path: Path) -> bool:
    if path.is_symlink():
        return True
    try:
        attributes = getattr(path.lstat(), "st_file_attributes", 0)
    except OSError as exc:
        raise GraderError("input-stat-failed", str(path)) from exc
    return bool(attributes & 0x400)


def reject_reparse_chain(root: Path, candidate: Path) -> None:
    cursor = candidate
    while True:
        if is_reparse(cursor):
            raise GraderError("reparse-path-rejected", str(cursor))
        if cursor == root:
            return
        if root not in cursor.parents:
            raise GraderError("path-outside-root", str(candidate))
        cursor = cursor.parent


def resolve_member(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative or ":" in relative:
        raise GraderError("path-outside-root", str(relative))
    requested = Path(relative)
    if requested.is_absolute() or any(part == ".." for part in requested.parts):
        raise GraderError("path-outside-root", relative)
    if any(part.lower() in PROHIBITED_PARTS for part in requested.parts):
        raise GraderError("input-path-prohibited", relative)
    try:
        candidate = (root / requested).resolve(strict=True)
    except OSError as exc:
        raise GraderError("input-file-missing", relative) from exc
    if candidate != root and root not in candidate.parents:
        raise GraderError("path-outside-root", relative)
    reject_reparse_chain(root, candidate)
    return candidate


def exact_keys(value: dict[str, Any], expected: set[str], failure_class: str) -> None:
    if set(value) != expected:
        raise GraderError(failure_class, "field-set")


def validate_job(job: dict[str, Any], raw: bytes) -> dict[str, Any]:
    expected = {
        "schemaVersion",
        "runId",
        "purpose",
        "profile",
        "imageRef",
        "includePaths",
        "testSelectors",
        "declaredTestCommand",
        "expectedSignal",
        "decisionSha256",
        "intentSpecSha256",
        "limits",
        "networkMode",
        "pullPolicy",
        "mutationAllowed",
    }
    exact_keys(job, expected, "autograder-job-invalid")
    if SECRET_RE.search(raw.decode("utf-8", errors="ignore")):
        raise GraderError("secret-leak-risk", "job")
    if job["schemaVersion"] != SCHEMA_JOB:
        raise GraderError("autograder-job-invalid", "schema")
    if not isinstance(job["runId"], str) or not RUN_RE.fullmatch(job["runId"]):
        raise GraderError("autograder-job-invalid", "run-id")
    if job["purpose"] not in ALLOWED_PURPOSES:
        raise GraderError("autograder-job-invalid", "purpose")
    if job["profile"] not in ALLOWED_PROFILES:
        raise GraderError("autograder-job-invalid", "profile")
    if not isinstance(job["imageRef"], str) or not IMAGE_RE.fullmatch(job["imageRef"]):
        raise GraderError("image-not-digest-pinned", str(job["imageRef"]))
    if job["networkMode"] != "none":
        raise GraderError("network-policy-invalid", str(job["networkMode"]))
    if job["pullPolicy"] != "never":
        raise GraderError("pull-policy-invalid", str(job["pullPolicy"]))
    if job["mutationAllowed"] is not False:
        raise GraderError("autograder-job-invalid", "mutation")
    for name in ("decisionSha256", "intentSpecSha256"):
        if not isinstance(job[name], str) or not HASH_RE.fullmatch(job[name]):
            raise GraderError("autograder-job-invalid", name)
    include_paths = job["includePaths"]
    selectors = job["testSelectors"]
    if not isinstance(include_paths, list) or not 1 <= len(include_paths) <= 128:
        raise GraderError("autograder-job-invalid", "include-paths")
    if not isinstance(selectors, list) or not 1 <= len(selectors) <= 64:
        raise GraderError("autograder-job-invalid", "selectors")
    for selector in selectors:
        if not isinstance(selector, str) or selector.startswith("-") or not SELECTOR_RE.fullmatch(selector):
            raise GraderError("selector-invalid", str(selector))
        if ".." in Path(selector.replace("::", "/")).parts:
            raise GraderError("selector-invalid", selector)
    declared_command = job["declaredTestCommand"]
    if (
        not isinstance(declared_command, str)
        or not declared_command.strip()
        or len(declared_command) > 1024
        or any(character in declared_command for character in ("\x00", "\r", "\n"))
    ):
        raise GraderError("autograder-job-invalid", "declared-test-command")
    signal = job["expectedSignal"]
    if not isinstance(signal, str) or len(signal) > 256 or "\x00" in signal:
        raise GraderError("autograder-job-invalid", "expected-signal")
    if job["purpose"] == "RED_PROBE" and not signal.strip():
        raise GraderError("autograder-job-invalid", "expected-signal-required")
    limits = job["limits"]
    if not isinstance(limits, dict):
        raise GraderError("autograder-job-invalid", "limits")
    exact_keys(
        limits,
        {
            "cpus",
            "memoryMb",
            "pids",
            "timeoutSeconds",
            "maxFiles",
            "maxInputBytes",
            "maxLogBytes",
            "maxXmlBytes",
            "maxTestCases",
        },
        "autograder-limits-invalid",
    )
    bounds = {
        "cpus": (0.1, 4.0),
        "memoryMb": (64, 4096),
        "pids": (16, 512),
        "timeoutSeconds": (1, 1800),
        "maxFiles": (1, 20_000),
        "maxInputBytes": (1, 1_073_741_824),
        "maxLogBytes": (1024, 4_194_304),
        "maxXmlBytes": (1024, 16_777_216),
        "maxTestCases": (1, 1_000_000),
    }
    for name, (minimum, maximum) in bounds.items():
        value = limits[name]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not minimum <= value <= maximum:
            raise GraderError("autograder-limits-invalid", name)
    return job


def iter_input_files(member: Path) -> list[Path]:
    if member.is_file():
        return [member]
    if not member.is_dir():
        raise GraderError("input-type-invalid", str(member))
    files: list[Path] = []
    for directory, dir_names, file_names in os.walk(member, followlinks=False):
        directory_path = Path(directory)
        if is_reparse(directory_path):
            raise GraderError("reparse-path-rejected", str(directory_path))
        for name in tuple(dir_names):
            child = directory_path / name
            if name.lower() in PROHIBITED_PARTS:
                raise GraderError("input-path-prohibited", str(child))
            if is_reparse(child):
                raise GraderError("reparse-path-rejected", str(child))
        for name in file_names:
            child = directory_path / name
            if is_reparse(child):
                raise GraderError("reparse-path-rejected", str(child))
            files.append(child)
    return files


def stage_inputs(root: Path, job: dict[str, Any], stage_input: Path) -> tuple[dict[str, str], str]:
    limits = job["limits"]
    selected: dict[str, Path] = {}
    for relative in job["includePaths"]:
        member = resolve_member(root, relative)
        for source in iter_input_files(member):
            rel = source.relative_to(root).as_posix()
            selected[rel] = source
    if len(selected) > int(limits["maxFiles"]):
        raise GraderError("input-file-limit-exceeded", str(len(selected)))
    total = sum(path.stat().st_size for path in selected.values())
    if total > int(limits["maxInputBytes"]):
        raise GraderError("input-byte-limit-exceeded", str(total))
    manifest: dict[str, str] = {}
    for relative, source in sorted(selected.items()):
        before = sha256_file(source)
        destination = stage_input / Path(relative)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        after = sha256_file(source)
        copied = sha256_file(destination)
        if before != after:
            raise GraderError("source-preimage-changed", relative)
        if before != copied:
            raise GraderError("staging-copy-mismatch", relative)
        manifest[relative] = before
    manifest_sha = sha256_bytes(canonical_json_bytes(manifest))
    return manifest, manifest_sha


def mount_arg(source: Path, target: str, readonly: bool = False) -> str:
    rendered = str(source.resolve())
    if rendered.startswith("\\\\"):
        raise GraderError("staging-root-not-local", rendered)
    if "," in rendered:
        raise GraderError("staging-path-invalid", "comma")
    suffix = ",readonly" if readonly else ""
    return f"type=bind,source={rendered},target={target}{suffix}"


def profile_shell(job: dict[str, Any]) -> str:
    selectors = [shlex.quote(value) for value in job["testSelectors"]]
    copy_prefix = "cp -R /input/. /workspace/ && cd /workspace && "
    archive_suffix = (
        "archive_code=0; tar -C /results -cf - . || archive_code=$?; "
        "if [ $archive_code -ne 0 ]; then exit 125; fi; exit $code"
    )
    if job["profile"] == "pytest":
        return (
            copy_prefix
            + "python -m pytest -q --disable-warnings --junitxml=/results/junit.xml "
            + " ".join(selectors)
            + " 1>&2; code=$?; "
            + archive_suffix
        )
    gradle_filters = " ".join(f"--tests {selector}" for selector in selectors)
    return (
        copy_prefix
        + "sh ./gradlew --offline --no-daemon --project-cache-dir /workspace/.gradle-autograder test "
        + gradle_filters
        + " 1>&2; code=$?; find . -path '*/build/test-results/*/TEST-*.xml' -type f "
        + "-exec cp {} /results/ \\;; "
        + archive_suffix
    )


def docker_argv(docker_bin: str, job: dict[str, Any], stage: Path, container_name: str) -> list[str]:
    limits = job["limits"]
    input_dir = stage / "input"
    workspace_size = min(int(limits["memoryMb"]), 1024)
    return [
        docker_bin,
        "run",
        "--rm",
        "--name",
        container_name,
        "--pull",
        "never",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--user",
        "65532:65532",
        "--cpus",
        str(limits["cpus"]),
        "--memory",
        f"{int(limits['memoryMb'])}m",
        "--memory-swap",
        f"{int(limits['memoryMb'])}m",
        "--pids-limit",
        str(int(limits["pids"])),
        "--stop-timeout",
        "1",
        "--init",
        "--ulimit",
        "nofile=1024:1024",
        "--tmpfs",
        f"/workspace:rw,nosuid,size={workspace_size}m,uid=65532,gid=65532,mode=0700",
        "--tmpfs",
        "/tmp:rw,noexec,nosuid,size=64m,uid=65532,gid=65532,mode=0700",
        "--tmpfs",
        f"/results:rw,noexec,nosuid,size={int(limits['maxXmlBytes'])},uid=65532,gid=65532,mode=0700",
        "--mount",
        mount_arg(input_dir, "/input", readonly=True),
        job["imageRef"],
        "sh",
        "-lc",
        profile_shell(job),
    ]


def drain_bounded_stream(
    stream: Any,
    path: Path,
    maximum: int,
    expected_signal: str,
    sink: dict[str, Any],
    key: str,
) -> None:
    digest = hashlib.sha256()
    total = 0
    persisted = 0
    try:
        with path.open("wb") as output:
            while True:
                chunk = stream.read(65_536)
                if not chunk:
                    break
                total += len(chunk)
                digest.update(chunk)
                remaining = maximum - persisted
                if remaining > 0:
                    prefix = chunk[:remaining]
                    output.write(prefix)
                    persisted += len(prefix)
        prefix_bytes = path.read_bytes()
        sink[key] = (
            {
                "bytes": total,
                "persistedBytes": persisted,
                "sha256": digest.hexdigest(),
                "truncated": total > maximum,
            },
            bool(expected_signal)
            and expected_signal in prefix_bytes.decode("utf-8", errors="replace"),
        )
    except Exception as exc:  # pragma: no cover - defensive thread boundary
        sink[key] = exc
    finally:
        stream.close()


def terminate_client_process_tree(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=5,
            check=False,
        )
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    if process.poll() is None:
        process.kill()


def parse_junit(results_dir: Path, max_xml_bytes: int, max_test_cases: int) -> dict[str, Any]:
    total = failed = errored = skipped = 0
    consumed = 0
    report_count = 0
    for report in results_dir.rglob("*.xml"):
        report_count += 1
        if report_count > max_test_cases:
            raise GraderError("junit-report-count-exceeded")
        report_size = report.stat().st_size
        if report_size < 0 or consumed + report_size > max_xml_bytes:
            raise GraderError("junit-xml-too-large")
        raw = report.read_bytes()
        if len(raw) != report_size:
            raise GraderError("junit-report-changed")
        consumed += len(raw)
        upper = raw.upper()
        if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper:
            raise GraderError("junit-xml-unsafe")
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as exc:
            raise GraderError("junit-xml-invalid") from exc
        suites = [root] if root.tag.rsplit("}", 1)[-1] == "testsuite" else [
            child for child in root if child.tag.rsplit("}", 1)[-1] == "testsuite"
        ]
        if not suites:
            raise GraderError("junit-xml-invalid", "no-suite")
        for suite in suites:
            try:
                values = [int(suite.attrib.get(name, "0")) for name in ("tests", "failures", "errors", "skipped")]
            except ValueError as exc:
                raise GraderError("junit-count-invalid") from exc
            if any(value < 0 for value in values):
                raise GraderError("junit-count-invalid")
            suite_total, suite_failed, suite_errored, suite_skipped = values
            if suite_failed + suite_errored + suite_skipped > suite_total:
                raise GraderError("junit-count-inconsistent")
            total += suite_total
            failed += suite_failed
            errored += suite_errored
            skipped += suite_skipped
    if report_count == 0:
        raise GraderError("junit-report-missing")
    if total == 0:
        raise GraderError("autograder-zero-tests")
    if total > max_test_cases:
        raise GraderError("test-case-limit-exceeded")
    passed = total - failed - errored - skipped
    return {
        "total": total,
        "passed": passed,
        "failed": failed,
        "errored": errored,
        "skipped": skipped,
        "passRatio": round(passed / total, 10),
    }


def extract_junit_archive(
    archive_path: Path,
    results_dir: Path,
    max_xml_bytes: int,
    max_files: int,
) -> None:
    if not archive_path.is_file() or archive_path.stat().st_size == 0:
        raise GraderError("junit-report-missing")
    consumed = 0
    file_count = 0
    try:
        archive = tarfile.open(archive_path, mode="r:")
    except (tarfile.TarError, OSError) as exc:
        raise GraderError("junit-archive-invalid") from exc
    with archive:
        for member in archive:
            member_path = PurePosixPath(member.name)
            if member.isdir():
                continue
            if (
                not member.isfile()
                or member_path.is_absolute()
                or ".." in member_path.parts
                or member_path.suffix.lower() != ".xml"
            ):
                raise GraderError("junit-archive-entry-invalid")
            file_count += 1
            consumed += member.size
            if file_count > max_files or member.size < 0 or consumed > max_xml_bytes:
                raise GraderError("junit-output-limit-exceeded")
            source = archive.extractfile(member)
            if source is None:
                raise GraderError("junit-archive-invalid")
            target = results_dir / f"report-{file_count:06d}.xml"
            written = 0
            with source, target.open("wb") as output:
                while True:
                    chunk = source.read(min(65_536, member.size - written + 1))
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > member.size or written > max_xml_bytes:
                        raise GraderError("junit-output-limit-exceeded")
                    output.write(chunk)
            if written != member.size:
                raise GraderError("junit-archive-invalid")
    if file_count == 0:
        raise GraderError("junit-report-missing")


def empty_tests() -> dict[str, Any]:
    return {"total": 0, "passed": 0, "failed": 0, "errored": 0, "skipped": 0, "passRatio": None}


def docker_exists(docker_bin: str) -> bool:
    if any(separator in docker_bin for separator in ("/", "\\")):
        return Path(docker_bin).is_file()
    return shutil.which(docker_bin) is not None


def base_result(job: dict[str, Any], job_sha: str, manifest_sha: str) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_RESULT,
        "runId": job["runId"],
        "purpose": job["purpose"],
        "generatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "jobSha256": job_sha,
        "inputManifestSha256": manifest_sha,
        "executionStatus": "HOLD",
        "failureClass": "none",
        "exitCode": None,
        "timedOut": False,
        "testVerdict": "NOT_RUN",
        "tests": empty_tests(),
        "expectedSignalMatched": False,
        "stdout": {"bytes": 0, "persistedBytes": 0, "sha256": sha256_bytes(b""), "truncated": False},
        "stderr": {"bytes": 0, "persistedBytes": 0, "sha256": sha256_bytes(b""), "truncated": False},
        "junitArchive": {
            "transport": "container-tmpfs-tar",
            "bytes": 0,
            "persistedBytes": 0,
            "sha256": sha256_bytes(b""),
            "truncated": False,
        },
        "combinedOutputSha256": sha256_bytes(
            (sha256_bytes(b"") + ":" + sha256_bytes(b"")).encode("ascii")
        ),
        "isolation": {
            "sourceMode": "local-temp-copy",
            "inputMountReadOnly": True,
            "rootFilesystemReadOnly": True,
            "networkMode": "none",
            "pullPolicy": "never",
            "capDropAll": True,
            "noNewPrivileges": True,
            "nonRootUser": True,
            "resourceLimitsApplied": True,
            "resultMode": "container-tmpfs-tar",
        },
        "secretPatternHits": 0,
        "mutationAllowed": False,
        "sourceMutationPerformed": False,
        "desktopFinalProof": "evidence_needed",
    }


def execute(root: Path, job: dict[str, Any], job_sha: str, output: Path, docker_bin: str) -> dict[str, Any]:
    stage_raw = tempfile.mkdtemp(prefix="awx-docker-autograder-")
    stage = Path(stage_raw).resolve()
    if str(stage).startswith("\\\\"):
        shutil.rmtree(stage, ignore_errors=True)
        raise GraderError("staging-root-not-local", str(stage))
    (stage / "input").mkdir()
    (stage / "results").mkdir()
    os.chmod(stage / "results", 0o777)
    stdout_path = stage / "junit.tar"
    stderr_path = stage / "stderr.log"
    result: dict[str, Any] | None = None
    try:
        _, manifest_sha = stage_inputs(root, job, stage / "input")
        result = base_result(job, job_sha, manifest_sha)
        if not docker_exists(docker_bin):
            result["failureClass"] = "docker-cli-unavailable"
            return result
        container_name = f"awx-grade-{job['runId'].lower()}-{job_sha[:10]}"
        argv = docker_argv(docker_bin, job, stage, container_name)
        started = time.monotonic()
        timed_out = False
        docker_kill_timed_out = False
        exit_code: int | None = None
        try:
            popen_options: dict[str, Any] = {"start_new_session": True} if os.name != "nt" else {
                "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP
            }
            process = subprocess.Popen(
                argv,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                **popen_options,
            )
        except FileNotFoundError:
            result["failureClass"] = "docker-cli-unavailable"
            return result
        if process.stdout is None or process.stderr is None:  # pragma: no cover - subprocess contract
            raise GraderError("autograder-log-capture-failed")
        drain_results: dict[str, Any] = {}
        archive_limit = (
            int(job["limits"]["maxXmlBytes"])
            + int(job["limits"]["maxFiles"]) * 1024
            + 10_240
        )
        drain_threads = [
            threading.Thread(
                target=drain_bounded_stream,
                args=(
                    process.stdout,
                    stdout_path,
                    archive_limit,
                    "",
                    drain_results,
                    "stdout",
                ),
                daemon=True,
            ),
            threading.Thread(
                target=drain_bounded_stream,
                args=(
                    process.stderr,
                    stderr_path,
                    int(job["limits"]["maxLogBytes"]),
                    job["expectedSignal"],
                    drain_results,
                    "stderr",
                ),
                daemon=True,
            ),
        ]
        for thread in drain_threads:
            thread.start()
        try:
            exit_code = process.wait(timeout=int(job["limits"]["timeoutSeconds"]))
        except subprocess.TimeoutExpired:
            timed_out = True
            result["timedOut"] = True
            result["failureClass"] = "autograder-timeout"
            try:
                subprocess.run(
                    [docker_bin, "kill", container_name],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=5,
                    check=False,
                )
            except subprocess.TimeoutExpired:
                docker_kill_timed_out = True
                result["failureClass"] = "docker-kill-timeout"
            terminate_client_process_tree(process)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                result["failureClass"] = "autograder-client-termination-failed"
                return result
        for thread in drain_threads:
            thread.join(timeout=5)
        if any(thread.is_alive() for thread in drain_threads):
            result["failureClass"] = "autograder-log-drain-timeout"
            return result
        if any(isinstance(value, Exception) for value in drain_results.values()) or set(drain_results) != {"stdout", "stderr"}:
            result["failureClass"] = "autograder-log-capture-failed"
            return result
        result["durationMs"] = int((time.monotonic() - started) * 1000)
        result["exitCode"] = exit_code
        result["timedOut"] = timed_out
        archive_info, _ = drain_results["stdout"]
        stderr_info, stderr_match = drain_results["stderr"]
        result["junitArchive"] = {"transport": "container-tmpfs-tar", **archive_info}
        result["stderr"] = stderr_info
        result["combinedOutputSha256"] = sha256_bytes(
            (result["stdout"]["sha256"] + ":" + stderr_info["sha256"]).encode("ascii")
        )
        result["expectedSignalMatched"] = stderr_match
        if timed_out:
            if not docker_kill_timed_out and result["failureClass"] == "none":
                result["failureClass"] = "autograder-timeout"
            return result
        try:
            if archive_info["truncated"]:
                raise GraderError("junit-output-limit-exceeded")
            extract_junit_archive(
                stdout_path,
                stage / "results",
                int(job["limits"]["maxXmlBytes"]),
                int(job["limits"]["maxFiles"]),
            )
            tests = parse_junit(
                stage / "results",
                int(job["limits"]["maxXmlBytes"]),
                int(job["limits"]["maxTestCases"]),
            )
        except GraderError as exc:
            result["failureClass"] = exc.classification
            return result
        result["tests"] = tests
        result["executionStatus"] = "COMPLETE"
        result["failureClass"] = "none"
        result["testVerdict"] = (
            "PASS"
            if exit_code == 0 and tests["failed"] == 0 and tests["errored"] == 0 and tests["skipped"] == 0
            else "FAIL"
        )
        return result
    finally:
        if result is not None:
            publish_ready_json(output, result)
        shutil.rmtree(stage, ignore_errors=True)


def run(root_value: str, job_value: str, output_value: str, docker_bin: str) -> dict[str, Any]:
    root = Path(root_value).resolve(strict=True)
    job_path = Path(job_value).resolve(strict=True)
    output = Path(output_value).resolve(strict=False)
    if job_path != root and root not in job_path.parents:
        raise GraderError("path-outside-root", str(job_path))
    if output == root or root not in output.parents:
        raise GraderError("path-outside-root", str(output))
    job, job_sha, raw = read_ready_json(job_path, "autograder-job-invalid")
    validate_job(job, raw)
    return execute(root, job, job_sha, output, docker_bin)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--job", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--docker-bin", default="docker")
    args = parser.parse_args()
    try:
        result = run(args.root, args.job, args.output, args.docker_bin)
    except (GraderError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "status": result["executionStatus"],
                "failureClass": result["failureClass"],
                "outputPath": str(Path(args.output).resolve(strict=False)),
                "mutationAllowed": False,
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
