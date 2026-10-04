"""Offline, read-only Stack-Fit Gate. Policy is JSON-compatible YAML; stdlib only.

Exit codes follow agent_vibe_auto_decision.py: FIT/OVERRIDE=0, ADAPT=3,
DECLINE=4. Evidence identifies locations/hashes, never request text or values.
"""
from __future__ import annotations

import argparse
import ast
from collections import Counter
from dataclasses import dataclass
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib

MAX_BYTES = 2 * 1024 * 1024
EXIT = {"FIT": 0, "OVERRIDE": 0, "ADAPT": 3, "DECLINE": 4}


class InputError(Exception):
    """Reason codes only; no raw input in exceptions or diagnostics."""


@dataclass(frozen=True)
class Change:
    path: str
    before: str
    after: str


def forbidden_path(path: Path) -> bool:
    parts = [p.casefold() for p in path.parts]
    return (any(p.startswith(".env") or p in (".secrets", ".git", ".ssh") for p in parts)
            or path.suffix.casefold() in (".pem", ".key", ".pfx", ".p12", ".jks"))


def safe_path(path: Path, root: Path | None = None) -> Path:
    if forbidden_path(path):
        raise InputError("input-path-blocked")
    resolved = path.resolve()
    if forbidden_path(resolved):
        raise InputError("input-path-blocked")
    if root is not None and not resolved.is_relative_to(root.resolve()):
        raise InputError("input-path-blocked")
    return resolved


def read_text(path: Path, root: Path | None = None) -> str:
    path = safe_path(path, root)
    try:
        if path.stat().st_size > MAX_BYTES:
            raise InputError("input-too-large")
        return path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError):
        raise InputError("input-unreadable") from None


def load_policy(root: Path) -> dict:
    try:
        policy = json.loads(read_text(root / "configs/stack-fit.yaml", root))
        if policy["schemaVersion"] != 1 or not policy["hold"]:
            raise ValueError
        for item in policy["hold"]:
            re.compile(item["pattern"], re.I)
            if not item.get("why") or not item.get("instead"):
                raise ValueError
        re.compile(policy["actionPattern"], re.I)
        return policy
    except (KeyError, ValueError, TypeError, re.error):
        raise InputError("policy-invalid") from None


def result(verdict, findings=(), reason=None):
    out = {"schemaVersion": 1, "verdict": verdict, "exitCode": EXIT[verdict],
           "findings": list(findings)}
    if reason:
        out["reason"] = reason
    return out


def finding(item, kind, source, line, content):
    return {"kind": kind, "tech": item["tech"],
            "evidence": {"source": source, "line": line,
                         "sha12": hashlib.sha256(content.encode("utf-8")).hexdigest()[:12]},
            "why": item["why"], "instead": item["instead"],
            "directConflict": bool(item.get("directConflict")), "overridden": False}


def matching(policy, content, *, dependency=False):
    for item in policy["hold"]:
        if not re.search(item["pattern"], content, re.I):
            continue
        if item.get("serverOnly") and not dependency and not re.search(
                policy["serverContextPattern"], content, re.I):
            continue
        yield item


def request_clauses(text):
    """Skip code/test tables and explicit quoted evaluation examples, not tasks.

    A research clause cannot suppress a later introduction clause. Markdown
    policy documents describe blocked technologies without requesting installs.
    """
    fenced = False
    test_section = False
    for number, raw in enumerate(text.splitlines(), 1):
        line = html.unescape(raw).strip().rstrip("\\")
        line = re.sub(r"\\([#`*\-])", r"\1", line)
        if line.startswith("```"):
            fenced = not fenced
            continue
        if fenced or line.startswith(("|", ">")):
            continue
        if re.match(r"(?:#{1,6}\s|W\d+[.)])", line):
            test_section = bool(re.search(r"회귀\s*테스트|regression\s*tests?|evaluation\s*cases", line, re.I))
        if test_section:
            continue
        if re.match(r"[-*]?\s*hold\s*\([^)]*금지", line, re.I):
            continue  # Radar prohibition list, not an introduction request.
        # A router's match list and a quoted rule stub are literal policy data.
        # Strip only their values, preserving any independent install request.
        if re.search(r"케이스|판정\s*예시|탐지:|match\s*(?:예)?\s*:|→|->|블록.{0,30}추가", line, re.I):
            line = re.sub(r'"[^"\n]*"|“[^”\n]*”', "", line)
        line = re.sub(r"match\s*예\s*:.*?(?=;\s*primary_skill)", "", line, flags=re.I)
        line = re.sub(r"^.*?요청이\s*오면,.*?계속하게\s*만든다\.", "", line)
        for clause in re.split(r"(?<=[.!?。])\s+|[;；,]|\b(?:and|but|then)\b|그리고|하지만", line, flags=re.I):
            # Examples in a detector/routing contract are data, not installs.
            meta = re.search(r"케이스|판정\s*예시|탐지:|match\s*:|→|->", clause, re.I)
            if meta:
                clause = re.sub(r'"[^"\n]*"|“[^”\n]*”', "", clause)
            yield number, clause


def text_findings(policy, text):
    found = []
    lines = text.splitlines()
    for number, clause in request_clauses(text):
        for item in matching(policy, clause):
            # Negation must apply to this technology/action, not a whole document.
            if re.search(r"금지|도입\s*(?:안|하지)|추가\s*(?:안|하지)|(?:do\s+not|never|don't)\b", clause, re.I):
                continue
            if re.search(r"(?:비교|조사|문서).{0,20}(?:만|읽기\s*전용)|read[ -]only|compare\s+only", clause, re.I) and not re.search(
                    r"(?:후|then|하지만|but).{0,80}(?:도입|추가|introduce|adopt|add)", clause, re.I):
                continue
            held = (re.search(r"(?<![A-Za-z0-9_])HOLD(?![A-Za-z0-9_])", lines[number - 1]) and
                    re.search(r"규칙과\s*충돌|CONFLICT/ASK_ONCE", lines[number - 1]))
            if re.search(policy["actionPattern"], clause, re.I) or held:
                f = finding(item, "prior-held-proposal" if held else "text-request", "text", number, clause)
                if held:
                    f["directConflict"] = True
                found.append(f)
    return found


def dependencies(path, content):
    """Return production/build dependency identities, ignoring version-only edits."""
    name = Path(path).name.casefold()
    if not content.strip():
        return set()
    try:
        if name == "package.json":
            doc = json.loads(content)
            if not isinstance(doc, dict):
                raise ValueError
            sections = [doc.get(k, {}) for k in ("dependencies", "devDependencies")]
            if any(not isinstance(d, dict) or any(not isinstance(k, str) or not isinstance(v, str)
                                                  for k, v in d.items()) for d in sections):
                raise ValueError
            return set(sections[0]) | set(sections[1])
        if name in ("build.gradle", "build.gradle.kts"):
            identities = set()
            clean = re.sub(r"/\*.*?\*/|(?m:^\s*//[^\n]*)", "", content, flags=re.S)
            for match in re.finditer(r"\b(?:implementation|api|runtimeOnly)\s*(?:\((.*?)\)|[ \t]+([^\n;{}]+))", clean, re.S):
                args = match.group(1) if match.group(1) is not None else match.group(2)
                group = re.search(r'\bgroup\s*[=:]\s*[\'"]([^\'"]+)', args)
                artifact = re.search(r'\bname\s*[=:]\s*[\'"]([^\'"]+)', args)
                if group and artifact:
                    identities.add(group.group(1) + ":" + artifact.group(1))
                else:
                    coordinate = re.search(r'[\'"]([^\'"]+:[^\'"]+)[\'"]', args)
                    if coordinate:
                        value = coordinate.group(1)
                        identities.add(value.rsplit(":", 1)[0] if value.count(":") >= 2 else value)
            return identities
        if re.fullmatch(r"requirements.*\.txt", name):
            return {re.split(r"[<>=!~\[;\s]", line.strip())[0].lower()
                    for line in content.splitlines() if line.strip() and not line.lstrip().startswith(("#", "-"))}
        if name == "pyproject.toml":
            doc = tomllib.loads(content)
            deps = doc.get("project", {}).get("dependencies", [])
            poetry = doc.get("tool", {}).get("poetry", {}).get("dependencies", {})
            return {re.split(r"[<>=!~\[;\s]", d)[0].lower() for d in deps} | (set(poetry) - {"python"})
    except (ValueError, TypeError, AttributeError):
        raise InputError("manifest-invalid") from None
    return set()


def policy_artifact(path):
    p = path.replace("\\", "/").casefold()
    return (p.endswith((".md", ".yaml", ".yml")) and not Path(p).name.startswith("docker-compose")
            or p.startswith("scripts/test_") or p == "scripts/stack_fit_guard.py")


def change_findings(policy, change):
    path = change.path.replace("\\", "/")
    if forbidden_path(Path(path)) or policy_artifact(path):
        return []
    found = []
    old = dependencies(path, change.before)
    new = dependencies(path, change.after)
    exempt = policy["exemptions"]
    for dep in sorted(new - old):
        if dep in exempt["testOnlyDependencies"] or any(dep.startswith(prefix) for prefix in exempt["sameFamilyModules"]):
            continue
        for item in matching(policy, dep, dependency=True):
            found.append(finding(item, "new-dependency", path, 1, dep))
    name = Path(path).name.casefold()
    markers = []
    python_calls = set()
    if name.endswith(".py"):
        try:
            tree = ast.parse(change.after)
            python_calls = {node.lineno for node in ast.walk(tree) if isinstance(node, ast.Call)}
        except SyntaxError:
            pass  # An incomplete source still gets lexical import/bind checks.
    if name == "nest-cli.json" and not change.before:
        markers.append(("nestjs", 1, name))
    if (name == "dockerfile" or name.startswith("docker-compose")) and not change.before:
        markers.append(("docker-runtime", 1, name))
    # Ignore unmodified marker lines; a version update is not a new server.
    previous = Counter(change.before.splitlines())
    for line, value in enumerate(change.after.splitlines(), 1):
        if previous[value]:
            previous[value] -= 1
            continue
        stripped = value.strip()
        if stripped.startswith(("#", "//", "/*", "*")):
            continue
        if re.search(r"\b(?:import|require)\b.*@nestjs/", value) or (name.endswith(".module.ts") and "@Module" in value):
            markers.append(("nestjs", line, value))
        python_expression = not name.endswith(".py") or line in python_calls
        if python_expression and re.search(r"\buvicorn\b", value):
            markers.append(("fastapi", line, value))
        if python_expression and re.search(r"\bFastAPI\s*\(", value):
            markers.append(("fastapi", line, value))
        if python_expression and re.search(r"\bFlask\s*\(", value):
            markers.append(("flask", line, value))
        if python_expression and re.search(r"\b(?:get_wsgi_application|get_asgi_application)\s*\(|django-admin\s+runserver", value):
            markers.append(("django", line, value))
        if name.endswith(".go") and re.search(r"\bhttp\.ListenAndServe(?:TLS)?\s*\(", value):
            markers.append(("go-server", line, value))
        if name.endswith(".rs") and re.search(r"TcpListener::bind|axum::serve|HttpServer::new", value):
            markers.append(("rust-server", line, value))
        if name.endswith(".kt") and re.search(r"\bembeddedServer\s*\(|\brunApplication\s*<", value):
            markers.append(("kotlin-server", line, value))
        if re.search(r"\bflask\s+run\b", value):
            markers.append(("flask", line, value))
        if not name.endswith(".py") and re.search(r"\bexpress\s*\(", value):
            markers.append(("express", line, value))
        if re.search(r"\.listen\s*\(", value) and re.search(r"\b(?:express|fastify|koa)\b", change.after):
            tech = next((t for t in ("express", "fastify", "koa") if re.search(r"\b" + t + r"\b", change.after)), "express")
            markers.append((tech, line, value))
        elif name.endswith((".js", ".ts", ".mjs", ".cjs")) and re.search(r"\.listen\s*\(", value):
            markers.append(("node-server", line, value))
    by_tech = {item["tech"]: item for item in policy["hold"]}
    for tech, line, value in markers:
        found.append(finding(by_tech[tech], "new-marker", path, line, value))
    return found


def accepted_overrides(root):
    accepted = set()
    directory = root / "docs/architecture/decisions"
    for path in sorted(directory.glob("ADR-*.md")):
        text = read_text(path, root)
        head = re.match(r"\A---\s*\n(.*?)\n---(?:\s*\n|$)", text, re.S)
        if not head:
            continue
        pairs = re.findall(r"(?m)^([A-Za-z]+):[ \t]*([^\n]*)$", head.group(1))
        if len(pairs) != len({key for key, _ in pairs}):
            continue
        fields = dict(pairs)
        if fields.get("status", "").strip().casefold() != "accepted" or fields.get("approvedBy", "").strip().casefold() != "user":
            continue
        tech = fields.get("tech", "").strip()
        if not re.fullmatch(r"[a-z][a-z0-9-]*(?:[ \t]*,[ \t]*[a-z][a-z0-9-]*)*", tech):
            continue
        accepted.update(t.strip() for t in tech.split(","))
    return accepted


def evaluate(root, *, text="", changes=()):
    root = Path(root).resolve()
    try:
        policy = load_policy(root)
        found = text_findings(policy, text)
        for change in changes:
            found.extend(change_findings(policy, change))
        overrides = accepted_overrides(root)
    except InputError as exc:
        return result("DECLINE", reason=str(exc))
    unique = {}
    for f in found:
        f["overridden"] = f["tech"] in overrides
        unique[(f["kind"], f["tech"], f["evidence"]["source"], f["evidence"]["line"])] = f
    findings = list(unique.values())
    remaining = [f for f in findings if not f["overridden"]]
    if not findings:
        return result("FIT")
    if not remaining:
        return result("OVERRIDE", findings)
    if any(f["directConflict"] or not f["instead"] or "확인 필요" in f["instead"] for f in remaining):
        return result("DECLINE", findings)
    return result("ADAPT", findings)


def git_read(root, *args, optional=False):
    executable = shutil.which("git") or ("F:/git/cmd/git.exe" if Path("F:/git/cmd/git.exe").is_file() else None)
    if not executable:
        raise InputError("git-unavailable")
    try:
        run = subprocess.run([executable, "-C", str(root), *args],
                             capture_output=True, encoding="utf-8", errors="replace",
                             env=dict(os.environ, GIT_OPTIONAL_LOCKS="0"), timeout=8)
    except (OSError, subprocess.TimeoutExpired):
        raise InputError("git-unavailable") from None
    if run.returncode and not optional:
        raise InputError("git-read-failed")
    return "" if run.returncode else run.stdout


def read_changes(root):
    """Read staged, unstaged, and untracked states independently, no secret blobs."""
    changes = []
    for cached in (True, False):
        flags = ("--cached",) if cached else ()
        names = git_read(root, "diff", *flags, "--name-only", "-z", "--no-ext-diff", "--no-renames").split("\0")
        for name in filter(None, names):
            if not relevant_path(name):
                continue
            target = safe_path(root / name, root)
            before = git_read(root, "show", ("HEAD:" if cached else ":") + name, optional=True)
            after = git_read(root, "show", ":" + name, optional=True) if cached else (read_text(target, root) if target.is_file() else "")
            changes.append(Change(name, before, after))
    for name in filter(None, git_read(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0")):
        if relevant_path(name):
            changes.append(Change(name, "", read_text(root / name, root)))
    return changes


def relevant_path(name):
    path = Path(name)
    if forbidden_path(path) or policy_artifact(name):
        return False
    return (path.name.casefold() in ("package.json", "nest-cli.json", "build.gradle", "build.gradle.kts", "pyproject.toml", "dockerfile")
            or path.name.casefold().startswith(("requirements", "docker-compose"))
            or path.suffix.casefold() in (".py", ".js", ".ts", ".tsx", ".mjs", ".cjs", ".go", ".rs", ".kt", ".sh", ".ps1", ".bat"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--text")
    source.add_argument("--text-file", type=Path)
    source.add_argument("--diff", action="store_true")
    source.add_argument("--paths", help="Comma-separated root-relative paths")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        text = args.text or ""
        changes = []
        if args.text_file is not None:
            text = read_text(args.text_file)
        if len(text.encode("utf-8")) > MAX_BYTES:
            raise InputError("input-too-large")
        if args.diff:
            changes = read_changes(root)
        if args.paths:
            for name in args.paths.split(","):
                name = name.strip().replace("\\", "/")
                content = read_text(root / name, root)
                before = git_read(root, "show", "HEAD:" + name, optional=True)
                changes.append(Change(name, before, content))
        out = evaluate(root, text=text, changes=changes)
    except InputError as exc:
        out = result("DECLINE", reason=str(exc))
    if args.json:
        print(json.dumps(out, ensure_ascii=True))
    else:
        print("STACK_FIT: " + out["verdict"])
        for f in out["findings"]:
            print(f'{f["tech"]} | 이유: {f["why"]} | 대신: {f["instead"]}')
        if out.get("reason"):
            print("reason=" + out["reason"])
    return out["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
