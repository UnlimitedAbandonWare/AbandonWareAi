"""Read-only doctor for demo-1 script assets.

Gate (exit 0/1): root launcher project-root hardcodes, syntax of the tools
added for this rail, and non-placeholder secret patterns in those tools or
in root launchers. The wider tree is reported as advisory. No network.
"""
from __future__ import annotations

import ast
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from script_mask import Parser, configure_stdio, find_secrets, mask_text
from scripts_inventory import EXTENSIONS, collect, hardcoded_project_roots, repo_root

SCHEMA = "awx.script-doctor.v1"
DEFAULT_REPORT = Path("var/diagnostics/script_doctor_report.json")
OWNED = {
    "scripts/script_mask.py",
    "scripts/scripts_inventory.py",
    "scripts/script_doctor.py",
    "scripts/test_script_standards.py",
    "scripts/script_doctor.ps1",
    "Doctor-Scripts.bat",
}
CONTENT_CAP = 1024 * 1024
FINDING_CAP = 40


def resolve_under(root: Path, value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    return root / path


def display_path(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except (OSError, ValueError):
        return path.name


def read_text(path: Path) -> str | None:
    try:
        if path.stat().st_size > CONTENT_CAP:
            return None
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def python_syntax(root: Path, rels: list[str]) -> list[dict]:
    errors = []
    for rel in rels:
        if not rel.endswith(".py"):
            continue
        text = read_text(root / rel)
        if text is None:
            continue
        try:
            ast.parse(text)
        except SyntaxError as exc:
            errors.append({"path": rel, "line": int(exc.lineno or 0)})
    return errors


def powershell_syntax(root: Path, rels: list[str]) -> tuple[list[str], str | None]:
    if not rels:
        return [], None
    handle = tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix=".txt")
    list_path = handle.name
    try:
        for rel in rels:
            handle.write(str((root / rel).resolve()) + "\n")
        handle.close()
        command = (
            "$ErrorActionPreference='Continue'; "
            "Get-Content -LiteralPath $env:AWX_PS1_LIST | ForEach-Object { "
            "  $tokens=$null; $errs=$null; "
            "  try { "
            "    [void][System.Management.Automation.Language.Parser]::ParseFile($_, [ref]$tokens, [ref]$errs); "
            "    if ($errs -and $errs.Count -gt 0) { Write-Output $_ } "
            "  } catch { Write-Output $_ } "
            "}"
        )
        env = os.environ.copy()
        env["AWX_PS1_LIST"] = list_path
        completed = subprocess.run(
            ["powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120, env=env, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return [], type(exc).__name__
    finally:
        try:
            os.remove(list_path)
        except OSError:
            pass
    failed = []
    for line in (completed.stdout or "").splitlines():
        raw = line.strip()
        if not raw:
            continue
        try:
            failed.append(Path(raw).resolve().relative_to(root.resolve()).as_posix())
        except (OSError, ValueError):
            failed.append(Path(raw).name)
    return failed, None


def root_style(root: Path) -> list[dict]:
    rows = []
    for pattern in ("*.bat", "*.cmd"):
        for path in sorted(root.glob(pattern)):
            if not path.is_file():
                continue
            text = read_text(path) or ""
            folded = text.lower()
            rows.append({
                "name": path.name,
                "dynamicRoot": "%~dp0" in folded,
                "chcp65001": "chcp 65001" in folded,
                "returnsExit": "exit /b" in folded,
                "gradleWrapper": path.name.lower() == "gradlew.bat",
            })
    return rows


def secret_hits(root: Path, rels: list[str]) -> list[dict]:
    hits = []
    for rel in rels:
        text = read_text(root / rel)
        if not text:
            continue
        for hit in find_secrets(text):
            hits.append({
                "path": rel,
                "line": hit["line"],
                "pattern": hit["pattern"],
                "severity": hit["severity"],
            })
    return hits


def diagnose(root: Path | None = None) -> dict:
    root = (root or repo_root()).resolve()
    payload = collect(root)
    rels = [row["path"] for row in payload["files"]]
    py_errors = python_syntax(root, rels)
    ps1_rels = [rel for rel in rels if rel.endswith(".ps1")]
    ps1_errors, ps1_skip = powershell_syntax(root, ps1_rels)
    secrets = secret_hits(root, rels)
    hardcodes = hardcoded_project_roots(root)
    owned_syntax = [
        row for row in py_errors if row["path"] in OWNED
    ] + [{"path": rel, "line": 0} for rel in ps1_errors if rel in OWNED]
    gate_secrets = [
        row for row in secrets
        if row["severity"] == "review" and (
            row["path"] in OWNED or "/" not in row["path"]
        )
    ]
    styles = root_style(root)
    advisory_chcp = [
        row["name"] for row in styles
        if not row["gradleWrapper"] and not row["chcp65001"]
    ]
    healthy = not hardcodes and not owned_syntax and not gate_secrets
    not_run = [
        {
            "item": "orphan-move",
            "reason": "SELFASK AUTO option b: STALE flag only. Moving unreferenced scripts can break dynamic callers.",
        },
        {
            "item": "bulk-ps1-strictmode",
            "reason": "Existing root dispatchers keep their error preference. CmdletBinding would break argument forwarding. New scripts/script_doctor.ps1 carries the standard.",
        },
        {
            "item": "bulk-py-argparse",
            "reason": "Foreign leases cover scripts/git_ship.py, scripts/test_git_ship.py, scripts/awx_paths.py, and scripts/test_awx_paths.py. New tools carry argparse and masking.",
        },
        {
            "item": "js-node-syntax",
            "reason": "node --check per file is not on the local fast path. Python ast and the PowerShell parser cover this run.",
        },
        {
            "item": "root-bat-header-bulk",
            "reason": "Only launchers that pinned C:\\AbandonWare were rewritten. Other root launchers stay as they are; missing chcp is advisory.",
        },
    ]
    if ps1_skip:
        not_run.append({"item": "powershell-parser", "reason": ps1_skip})
    return {
        "schemaVersion": SCHEMA,
        "generatedAt": payload["generatedAt"],
        "projectRoot": ".",
        "healthy": healthy,
        "healthDefinition": (
            "gate = root .bat/.cmd project-root hardcode + syntax of this rail's tools "
            "+ non-placeholder secret patterns in those tools or root launchers. "
            "Pre-existing tree syntax and legacy style gaps are advisory."
        ),
        "gate": {
            "hardcodedProjectRoot": len(hardcodes),
            "ownedSyntaxErrors": len(owned_syntax),
            "ownedOrRootSecretLeaks": len(gate_secrets),
        },
        "inventory": {
            "total": payload["total"],
            "staleCount": payload["staleCount"],
            "byExtension": payload["byExtension"],
            "byCategory": payload["byCategory"],
            "orphanPolicy": payload["orphanPolicy"],
        },
        "advisory": {
            "pythonSyntaxErrors": len(py_errors),
            "powershellSyntaxErrors": len(ps1_errors),
            "secretPatternHits": len(secrets),
            "rootLaunchersMissingChcp": len(advisory_chcp),
        },
        "hardcodedProjectRoot": hardcodes,
        "notRun": not_run,
        "findings": {
            "pythonSyntax": py_errors[:FINDING_CAP],
            "powershellSyntax": ps1_errors[:FINDING_CAP],
            "secrets": secrets[:FINDING_CAP],
            "rootLaunchersMissingChcp": advisory_chcp[:FINDING_CAP],
        },
        "extensions": list(EXTENSIONS),
    }


def print_summary(report: dict) -> None:
    gate = report["gate"]
    advisory = report["advisory"]
    inventory = report["inventory"]
    print(
        "healthy=%s total=%s stale=%s gateHardcoded=%s gateSyntax=%s gateSecrets=%s "
        "advisoryPythonSyntax=%s advisorySecrets=%s"
        % (
            str(report["healthy"]).lower(),
            inventory["total"],
            inventory["staleCount"],
            gate["hardcodedProjectRoot"],
            gate["ownedSyntaxErrors"],
            gate["ownedOrRootSecretLeaks"],
            advisory["pythonSyntaxErrors"],
            advisory["secretPatternHits"],
        )
    )


def build_parser() -> Parser:
    parser = Parser(description="Diagnose script assets. Read-only. No network.")
    parser.add_argument("--summary", action="store_true", help="print one health line")
    parser.add_argument("--json", metavar="PATH", help="write the report")
    parser.add_argument("--root", default=None, help="project root override for tests")
    return parser


def main(argv: list[str] | None = None) -> int:
    configure_stdio()
    args = build_parser().parse_args(argv)
    root = Path(args.root).resolve() if args.root else repo_root()
    write_report = bool(args.json) or not args.summary
    try:
        report = diagnose(root)
        if args.summary or not args.json:
            print_summary(report)
        if write_report:
            target = resolve_under(root, args.json) if args.json else (root / DEFAULT_REPORT)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print("wrote %s" % display_path(root, target))
        return 0 if report["healthy"] else 1
    except OSError as exc:
        print(mask_text(str(exc)), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
