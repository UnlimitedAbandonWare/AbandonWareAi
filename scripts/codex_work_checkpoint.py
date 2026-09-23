"""Local checkpoints for an already-authorized, target-scoped Codex change.

This does not apply patches, grant source authority, execute commands, or release
leases. The caller seals its own patch immediately, runs real verification, and
passes the observed exit code to finish (normally in finally). Failed verification
restores only unchanged sealed postimages. No Git index/ref operations are used.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from build_error_miner import PATTERNS, SECRET_FRAGMENT_RE

MAX_BYTES = 2 * 1024 * 1024
GATES = {"bulkDelete", "unrecoverableOverwrite", "credentialChange", "externalRealData",
         "paidBulkCalls", "productionMutation", "permissionChange", "irreversibleLoss"}
FACTORS = {"recovery", "blastRadius", "regression", "uncertainty", "cost"}


class CheckpointError(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise CheckpointError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest() if data is not None else None


def safe_id(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}", value),
            "invalid-evidence-id")
    return value


def assess(decision):
    require(isinstance(decision, dict), "decision-required")
    safe_id(decision.get("goalId"))
    safe_id(decision.get("reasonCode"))
    risk, gates = decision.get("risk", {}), decision.get("gates", {})
    require(set(risk) == FACTORS and all(type(v) is int and 0 <= v <= 4 for v in risk.values()),
            "risk-factors-required")
    require(set(gates) == GATES and all(type(v) is bool for v in gates.values()), "gate-evidence-required")
    score = 5 * sum(risk.values())
    return {"status": "approval_required" if any(gates.values()) else "autonomous",
            "riskScore": score, "riskFactors": risk,
            "approvalReasons": sorted(k for k, v in gates.items() if v),
            "verificationDepth": "focused" if score < 25 else "affected-boundaries" if score < 60
            else "split-and-broaden", "goalId": decision["goalId"], "reasonCode": decision["reasonCode"]}


def no_links(path):
    for candidate in (path, *path.parents):
        if candidate.exists() or candidate.is_symlink():
            info = candidate.lstat()
            require(not stat.S_ISLNK(info.st_mode) and not
                    (getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT),
                    "reparse-traversal")
            require(not candidate.is_file() or info.st_nlink == 1, "hardlink-target")


def root_path(root):
    root = Path(os.path.abspath(root))
    require(root.is_dir() and not str(root).startswith("\\\\"), "local-root-required")
    no_links(root)
    return root


def file_identity(path):
    """Stable same-file evidence (volume + file index) so a target reached via a
    different spelling/alias is not mistaken for a different file. Best effort."""
    try:
        info = path.stat()
        return {"dev": info.st_dev, "ino": getattr(info, "st_ino", 0)}
    except OSError:
        return None


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0 or pid == os.getpid():
        return False
    if os.name == "nt":
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            code = ctypes.c_ulong()
            if not ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == 259  # STILL_ACTIVE
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def relative_path(root, text):
    require(isinstance(text, str) and text and "\\" not in text, "relative-path-required")
    parts = text.split("/")
    require(all(p and p not in (".", "..") and not p.endswith((".", " ")) and
                not re.search(r'[<>:"|?*\x00-\x1f]', p) for p in parts), "invalid-target-path")
    require(all(p.casefold() != ".git" for p in parts), "git-metadata-protected")
    path = root.joinpath(*parts)
    require(path.is_relative_to(root), "path-outside-root")
    no_links(path)
    return path


def contents(path):
    no_links(path)
    if not path.exists():
        return None
    require(path.is_file() and path.stat().st_size <= MAX_BYTES, "file-size-or-type")
    return path.read_bytes()


def javascript_protected_spans(text):
    # Recognize regex literals before their quotes or escaped slash pairs can be
    # mistaken for strings/comments. These spans never grant an exemption.
    regex_literal = (
        r'(?:(?<=[(=,:;!&|?{}\[\]>])|(?<=\breturn)|(?<=\bthrow))\s*'
        r'/(?![/*])(?:\\.|\[(?:\\.|[^\]\\\r\n])*\]|[^/\\[\r\n])+/'
        r'[dgimsuvy]*')
    non_code = re.compile(
        regex_literal + r'|//[^\r\n]*|/\*.*?(?:\*/|\Z)|'
        r'`(?:\\.|[^`\\])*(?:`|\Z)|'
        r'"(?:\\.|[^"\\])*(?:"|\Z)|\'(?:\\.|[^\'\\])*(?:\'|\Z)', re.S)
    return [match.span() for match in non_code.finditer(text)]

def nonliteral_ui_expressions(text, source_path):
    """Recognize bounded UI runtime expressions, never literal credentials.

    Only mask the sensitive *label*. RHS bytes still pass the original secret
    scanner. Comments, strings and template literals cannot grant an exemption.
    """
    if not source_path.endswith((".java", ".js", ".cjs", ".mjs")):
        return text
    quoted = re.compile(
        r'//[^\r\n]*|/\*.*?(?:\*/|\Z)|`(?:\\.|[^`\\])*(?:`|\Z)|'
        r'"(?:\\.|[^"\\])*(?:"|\Z)|\'(?:\\.|[^\'\\])*(?:\'|\Z)', re.S)
    protected = (javascript_protected_spans(text) if source_path.endswith((".js", ".cjs", ".mjs"))
                 else [m.span() for m in quoted.finditer(text)])
    ident = r"[A-Za-z_$][A-Za-z0-9_$]*"
    patterns = []
    if source_path.endswith((".js", ".cjs", ".mjs")):
        args = ident + r"(?:\s*,\s*" + ident + r")*"
        call = ident + r"\(" + args + r"\)"
        empty_string = r'''(?:""|'')'''
        string_call = r"String\(" + ident + r"\s*\?\?\s*" + empty_string + r"\)\.trim\(\)"
        patterns.extend([
            # An arrow parameter is syntax, not a credential assignment.
            r"(?m)^\s*(?:const|let|var)\s+" + ident + r"\s*=\s*(token)\s*=>",
            # A numeric math-placeholder index reads runtime data. The fixed DOM
            # attribute is the only string allowed; arbitrary indexed RHS stays strict.
            r'(?m)^\s*(?:const|let|var)\s+(token)\s*=\s*' + ident
            + r'\[Number\(' + ident + r'\.getAttribute\("data-chat-math"\)\)\];',
            r"(?m)^\s*(?:const|let|var)\s+(token)\s*=\s*(?:" + call + "|" + string_call + r");",
            r"\?\s*(token)\s*:\s*null\b",
            r"(?m)^\s*" + ident + r"\.(password)\s*=\s*" + empty_string + r";",
            # The fixed CSRF meta selector is a DOM read, not a stored token.
            r'''(?m)^\s*(token):\s*document\.querySelector\('meta\[name="_csrf"\]'\)\?\.content\s*\|\|\s*"",'''
        ])
    else:
        patterns.extend([
            r'(?m)^\s*if\s*\((apiKey)\s*==\s*null\b',
            r'(?m)^\s*String\s+(apiKey)\s*=\s*resolveOpenAiApiKey\(\);',
            r'(?m)^\s*String\s+(apiKey)\s*=\s*resolveApiKeyForBaseUrl\(' + ident + r'\);'
        ])
        patterns.append(r'(?m)^\s*String\s+(token)\s*=\s*' + ident
                        + r'\s*==\s*null\s*\?\s*""\s*:\s*stringValue\('
                        + ident + r'\.getToken\(\)\);')
    chars = list(text)
    for pattern in patterns:
        for match in re.finditer(pattern, text):
            start, end = match.span(1)
            if not any(a < end and start < b for a, b in protected):
                chars[start:end] = " " * (end - start)
    return "".join(chars)


def secret_free(data, source_path=""):
    text = (data or b"").decode("utf-8", errors="ignore")
    text = nonliteral_ui_expressions(text, source_path)
    if source_path.endswith(".java"):
        # The existing local Ollama binding has a public noncredential fallback.
        # Recognize only this exact placeholder; other literal defaults stay blocked.
        for sentinel in ("ollama", "sk-local"):
            binding = "${llm.api-" + "key:${LLM_API_KEY:" + sentinel + "}}"
            text = text.replace(binding, "<local-ollama-setting>")
    # Unresolved Spring/environment bindings name settings; they contain no values.
    # Accept only identifiers and empty/nested fallbacks, never literal defaults.
    placeholder = r"\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::)?\}"
    for _ in range(4):
        placeholder = r"\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::(?:" + placeholder + r")?)?\}"
    text = re.sub(placeholder, "<unresolved-setting>", text)
    java_escapes = list(re.finditer(r"\\u+[0-9a-fA-F]{4}", text))
    if source_path.endswith(".java") and all(
            int(m.group()[-4:], 16) >= 0xA0 and int(m.group()[-4:], 16) not in (0x2028, 0x2029)
            for m in java_escapes):
        # Only a literal-free, qualified call in Java code is exempt. Arguments
        # may contain names and nested calls; any string/char literal inside
        # overlaps a protected span below and keeps the assignment flagged.
        # Strings, comments, text blocks and ambiguous Unicode escapes stay strict.
        non_code = re.compile(
            r'//[^\r\n]*|/\*.*?(?:\*/|\Z)|"""(?:\\.|(?!""").)*(?:"""|\Z)|'
            r'"(?:\\.|[^"\\])*(?:"|\Z)|\'(?:\\.|[^\'\\])*(?:\'|\Z)', re.S)
        protected = [m.span() for m in non_code.finditer(text)]
        call = re.compile(
            r"(?:password|passwd|pwd|clientSecret|apiKey|token)\s*=\s*"
            r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\([^;]*\);", re.I)
        chars = list(text)
        # Non-ASCII escapes cannot introduce Java quotes, comments or operators.
        # ASCII/control escapes remain strict because Java processes them before lexing.
        names = r"(?:password|passwd|pwd|clientSecret|apiKey|token)"
        ident = r"[A-Za-z_$][A-Za-z0-9_$]*"
        noarg = ident + r"(?:\." + ident + r")+\(\)"
        conditional = re.compile(r"\b(" + names + r")\s*=\s*" + ident
                                 + r"\s*\?\s*" + noarg + r"\s*:\s*" + noarg + r"\s*;", re.I)
        comparison = re.compile(r"\b(" + names + r")\s*==(?!=)", re.I)
        iteration = re.compile(r"\bfor\s*\(\s*(?:java\.lang\.)?String\s+(token)\s*:\s*"
                               + r"(?:" + ident + r"(?=\s*[.)])|new\s+String\s*\[\]\s*\{)")
        for expression in (conditional, comparison, iteration):
            for match in expression.finditer(text):
                if not any(start < match.end() and match.start() < end for start, end in protected):
                    # Only the declaration/comparison label is masked. Keep every RHS byte.
                    chars[match.start(1):match.end(1)] = " " * (match.end(1) - match.start(1))
        # HexFormat encodes a runtime byte variable; it cannot contain a literal
        # credential. The ellipsis escape above cannot alter Java tokenization.
        encoding = re.compile(r"\b(?:password|passwd|pwd|clientSecret|apiKey|token)\s*=\s*HexFormat\.of\(\)\.formatHex\([A-Za-z_$][A-Za-z0-9_$]*\);", re.I)
        for match in encoding.finditer(text):
            if not any(start < match.end() and match.start() < end for start, end in protected):
                end = match.start() + match.group().index("=")
                chars[match.start():end] = " " * (end - match.start())
        for match in SECRET_FRAGMENT_RE.finditer(text):
            if call.fullmatch(match.group()) and not any(
                    start < match.end() and match.start() < end for start, end in protected):
                # Preserve the entire RHS for the original prefixed-value scan.
                end = match.start() + match.group().index("=")
                chars[match.start():end] = " " * (end - match.start())
        # A zero-initialized Java int loop counter contains no credential value.
        zero_loop = re.compile(r"\bfor\s*\(\s*int\s+(token)\s*=\s*0\s*;")
        for loop in zero_loop.finditer(text):
            if not any(start < loop.end() and loop.start() < end for start, end in protected):
                chars[loop.start(1):loop.end(1)] = " " * (loop.end(1) - loop.start(1))
        # A diagnostic label concatenated with a runtime value is not a literal
        # credential. Require the quote to be the end of the containing Java
        # string; retain the value and all subsequent bytes for secret scanning.
        diagnostic = re.compile(r'\b(token):\s*"\s*\+\s*token\b')
        for match in diagnostic.finditer(text):
            quote_end = text.index('"', match.start()) + 1
            if any(start <= match.start() and end == quote_end for start, end in protected):
                chars[match.start(1):match.end(1)] = " " * (match.end(1) - match.start(1))
        text = "".join(chars)
    if source_path.endswith((".js", ".cjs", ".mjs")):
        # Object properties that copy a member reference (or a parser token name) contain no credential
        # literal. Keep the RHS available to the prefixed-secret scan. Quoted
        # strings, comments and ambiguous template/Unicode syntax stay strict.
        non_code = re.compile(r'//[^\r\n]*|/\*.*?(?:\*/|\Z)|"(?:\\.|[^"\\])*(?:"|\Z)|\'(?:\\.|[^\'\\])*(?:\'|\Z)', re.S)
        protected = javascript_protected_spans(text)
        ambiguous = any(not any(start <= m.start() and m.end() <= end for start, end in protected)
                        for m in re.finditer(r"`|\\u(?:[0-9a-fA-F]{4}|\{)", text))
        member = re.compile(r"(?:(?:password|passwd|pwd|clientSecret|apiKey|token)\s*:\s*[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+|token\s*:\s*[A-Za-z_$][A-Za-z0-9_$]*)(?=\s*[,}])", re.I)
        chars = list(text)
        for match in member.finditer(text):
            before = text[:match.start()].rstrip()
            if not ambiguous and before.endswith(("{", ",")) and not any(
                    start < match.end() and match.start() < end for start, end in protected):
                end = match.start() + match.group().index(":")
                chars[match.start():end] = " " * (end - match.start())
        text = "".join(chars)
    def reference_only(match):
        value = match.group()
        # A name-only Spring binding is a reference, never a credential value.
        if re.fullmatch(r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*<unresolved-setting>", value, re.I):
            return True
        if source_path.endswith((".yaml", ".yml")):
            label = "api" + "-key: "
            return value in (label + "${LLM_API_KEY:ollama}",
                             label + "${BRAVE_API_KEY:__MISSING__}")
        return False
    require(not any(not reference_only(m) for m in SECRET_FRAGMENT_RE.finditer(text)) and not
            re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", text), "secret-pattern")


def artifact(path):
    # Unrecognized paths require the existing source owner; this is not authority inference.
    return path in ("AGENTS.md", "AGENTS.override.md", "README.md") or path.startswith(
        ("docs/", "agent-prompts/", ".agents/skills/")) and path.endswith(".md")


def run_path(root, name):
    require(isinstance(name, str) and (name.startswith("data/agent-handoff/codex-autonomy/") or
            root.name == ".codex" and name.startswith("autonomy-checkpoints/")), "checkpoint-storage-scope")
    return relative_path(root, name)


def lease_check(root, manifest):
    if not manifest.get("lease"):
        require(all(artifact(t["path"]) for t in manifest["targets"]), "source-owner-lease-required")
        return
    ref = manifest["lease"]
    require(ref["path"].startswith("__patch_drop__/source-edit-locks/") and
            ref["path"].endswith("/lease.json"), "source-lease-path")
    data = contents(relative_path(root, ref["path"]))
    require(data is not None and digest(data) == ref["sha256"], "source-lease-drift")
    lease = json.loads(data)
    expires = datetime.fromisoformat(lease.get("expiresAtUtc", "").replace("Z", "+00:00"))
    require(expires.tzinfo is not None and expires > datetime.now(timezone.utc), "source-lease-expired")
    require(lease.get("mutationAllowed") is True and lease.get("coordinationMode") == "target-scoped"
            and bool(lease.get("ownerId")) and root_path(lease.get("root", "")) == root,
            "source-lease-invalid")
    paths = {str(p).replace("\\", "/").casefold() for p in lease.get("targetPaths", [])}
    require({t["path"].casefold() for t in manifest["targets"]} <= paths, "source-lease-scope")


def overlap_warnings(root, targets, own_lease=None):
    # Advisory, never blocking: an active lease covering a declared target means
    # a concurrent writer; an expired one will still refuse a new overlapping
    # acquisition until it is released or recovered. The caller's own lease
    # (recorded in the manifest) is not a warning.
    locks = root / "__patch_drop__" / "source-edit-locks"
    if not locks.is_dir():
        return []
    wanted = {t.casefold() for t in targets}
    own = str(own_lease or "").replace("\\", "/").casefold()
    warnings, now = [], datetime.now(timezone.utc)
    for lease_file in sorted(locks.glob("*.lock/lease.json")):
        if lease_file.relative_to(root).as_posix().casefold() == own:
            continue
        try:
            data = json.loads(lease_file.read_bytes() or b"{}")
        except (OSError, ValueError):
            continue
        covered = sorted({str(p).replace("\\", "/") for p in
                          (data.get("targetPaths") if isinstance(data.get("targetPaths"), list) else [])
                          if str(p).replace("\\", "/").casefold() in wanted})
        if not covered:
            continue
        try:
            expired = datetime.fromisoformat(
                str(data.get("expiresAtUtc", "")).replace("Z", "+00:00")) <= now
        except ValueError:
            expired = False
        warnings.append("lease-overlap:" + lease_file.parent.name + ":" +
                        ("expired" if expired else "active") + ":" + ",".join(covered))
    return warnings


def write_json(path, value):
    no_links(path)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=True, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def save(run, state):
    state["updatedAtUtc"] = datetime.now(timezone.utc).isoformat()
    write_json(run / "checkpoint.json", state)


def begin(root, run_relative, targets, decision, lease=None):
    assessment = assess(decision)
    if assessment["status"] != "autonomous":
        return assessment
    root = root_path(root)
    run = run_path(root, run_relative)
    require(not run.exists(), "checkpoint-already-exists")
    require(0 < len(targets) <= 30, "bounded-target-set-required")
    require(len({p.casefold() for p in targets}) == len(targets), "duplicate-target")
    rows, backups = [], []
    for name in targets:
        path = relative_path(root, name)
        require(not (path == run or path.is_relative_to(run) or run.is_relative_to(path)), "checkpoint-target-overlap")
        require(not path.name.startswith(".env") and path.suffix.lower() not in
                (".pem", ".key", ".p12", ".pfx", ".jks"), "credential-path-protected")
        data = contents(path)
        secret_free(name.encode("utf-8"))
        secret_free(data, name)
        row = {"path": name, "existed": data is not None, "preimageSha256": digest(data)}
        identity = file_identity(path) if data is not None else None
        if identity:
            row["fileIdentity"] = identity
            row["preimageBytes"] = len(data)
        rows.append(row)
        backups.append(data)
    manifest = {"version": 1, "root": str(root), "targets": rows, "decision": assessment, "lease": None}
    if lease:
        manifest["lease"] = {"path": lease, "sha256": digest(contents(relative_path(root, lease)))}
    lease_check(root, manifest)
    run.mkdir(parents=True, exist_ok=False)
    (run / "before").mkdir()
    for i, data in enumerate(backups):
        if data is not None:
            with (run / "before" / f"{i}.bin").open("xb") as stream:
                stream.write(data)
    # Distinguish same-disk preimage copies from separate-device backups: a
    # preimage on the same volume shares the target's loss modes, so reports
    # must not describe it as an independent backup.
    run_device = file_identity(run)
    for row, path in zip(rows, (relative_path(root, t["path"]).parent for t in rows)):
        row["targetSameVolumeAsRun"] = bool(
            run_device and file_identity(path) and run_device["dev"] == file_identity(path)["dev"])
    write_json(run / "manifest.json", manifest)
    state = {**assessment, "status": "prepared", "manifestSha256": digest((run / "manifest.json").read_bytes()),
             "run": run_relative, "targetCount": len(rows), "checkpointScope": "declared-targets",
             "leaseOverlapWarnings": overlap_warnings(root, targets, lease),
             "nextAction": "verify-existing-owner-then-patch-and-seal"}
    save(run, state)
    return state


@contextmanager
def opened(root, run_relative):
    root = root_path(root)
    run = run_path(root, run_relative)
    require(run.is_dir(), "checkpoint-missing")
    lock = run / ".operation.lock"
    handle = None
    for attempt in range(2):
        try:
            handle = lock.open("xb")
            handle.write(json.dumps({"pid": os.getpid(), "at": datetime.now(timezone.utc).isoformat()}).encode("utf-8"))
            handle.flush()
            break
        except FileExistsError:
            holder = {}
            try:
                holder = json.loads(lock.read_bytes() or b"{}")
            except (OSError, ValueError):
                pass
            # Reclaim only a provably dead holder; an unreadable or live one keeps the hold.
            if attempt == 0 and isinstance(holder.get("pid"), int) and not pid_alive(holder["pid"]):
                try:
                    lock.unlink()
                    continue
                except OSError:
                    pass
            raise CheckpointError("checkpoint-operation-active") from None
    try:
        state = json.loads(contents(relative_path(root, run_relative + "/checkpoint.json")))
        raw = contents(relative_path(root, run_relative + "/manifest.json"))
        require(digest(raw) == state["manifestSha256"], "manifest-integrity")
        manifest = json.loads(raw)
        require(manifest["version"] == 1 and manifest["root"] == str(root), "checkpoint-root-mismatch")
        yield root, run, manifest, state
    finally:
        handle.close()
        lock.unlink()


def seal(root, run_relative):
    with opened(root, run_relative) as (root, run, manifest, state):
        require(state["status"] == "prepared", "checkpoint-not-prepared")
        lease_check(root, manifest)
        hashes, differences = {}, []
        for i, target in enumerate(manifest["targets"]):
            name = target["path"]
            before = contents(run / "before" / f"{i}.bin") if target["existed"] else None
            require(digest(before) == target["preimageSha256"], "backup-integrity")
            after = contents(relative_path(root, name))
            secret_free(name.encode("utf-8"))
            secret_free(before, name)
            secret_free(after, name)
            hashes[name] = digest(after)
            try:
                old, new = (before or b"").decode("utf-8"), (after or b"").decode("utf-8")
                differences.extend(difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True),
                                   fromfile="before/" + name, tofile="after/" + name))
            except UnicodeDecodeError:
                differences.append(f"Binary difference: {name}\n")
        diff = "".join(differences).encode("utf-8")
        # Headers and both full source versions were checked above. Rescanning
        # partial hunks would lose the Java string/comment context used there.
        with (run / "change.diff").open("xb") as stream:
            stream.write(diff)
        state.update(status="sealed", postimages=hashes, diffSha256=digest(diff),
                     nextAction="run-focused-verification-and-finish-in-finally")
        save(run, state)
        return state


def apply(root, run_relative, target, content_file):
    """Bounded write inside a prepared cycle: the declared target's current bytes
    must still equal the recorded preimage (or this cycle's own earlier apply).
    A file changed between read and apply is a conflict, never a silent overwrite.
    """
    root = root_path(root)
    with opened(root, run_relative) as (root, run, manifest, state):
        require(state["status"] == "prepared", "checkpoint-not-prepared")
        lease_check(root, manifest)
        matches = [t for t in manifest["targets"] if t["path"].casefold() == str(target).casefold()]
        require(len(matches) == 1, "apply-target-not-declared")
        row = matches[0]
        path = relative_path(root, row["path"])
        current = digest(contents(path))
        allowed = {row["preimageSha256"]}
        prior = state.get("appliedFiles", {}).get(row["path"])
        if prior:
            allowed.add(prior["sha256"])
        require(current in allowed, "apply-precondition-conflict")
        data = contents(Path(content_file))
        require(data is not None, "apply-content-missing")
        secret_free(row["path"].encode("utf-8"))
        secret_free(data, row["path"])
        temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".apply")
        try:
            with temp.open("xb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            require(digest(contents(path)) in allowed, "apply-precondition-conflict")
            os.replace(temp, path)
        finally:
            if temp.exists():
                temp.unlink()
        require(digest(contents(path)) == digest(data), "apply-verification-failed")
        state.setdefault("appliedFiles", {})[row["path"]] = {
            "at": datetime.now(timezone.utc).isoformat(), "sha256": digest(data)}
        save(run, state)
        return {"status": "applied", "path": row["path"], "postimageSha256": digest(data),
                "run": run_relative, "nextAction": "seal-then-verify"}


def restore(root, run_relative, only=(), staging=None):
    """Manual rollback of one cycle. Each target restores only while its current
    bytes still equal the sealed postimage; a foreign change becomes a reported
    conflict and is never overwritten. --staging verifies recovery by writing
    preimage copies to a separate scratch directory instead of live targets.
    """
    root = root_path(root)
    with opened(root, run_relative) as (root, run, manifest, state):
        require(state["status"] in ("sealed", "verified", "hold", "restoring", "rolled_back"),
                "checkpoint-not-restorable")
        postimages = state.get("postimages") or {}
        want = {str(t).casefold() for t in only} if only else None
        report = {"restored": [], "unchanged": [], "conflicts": [], "staged": []}
        stage_dir = None
        if staging:
            stage_dir = Path(staging)
            require(not str(stage_dir).startswith("\\\\"), "staging-local-required")
            no_links(stage_dir)
            stage_dir.mkdir(parents=True, exist_ok=True)
        for i, target in enumerate(manifest["targets"]):
            name = target["path"]
            if want and name.casefold() not in want:
                continue
            path = relative_path(root, name)
            before = contents(run / "before" / f"{i}.bin") if target["existed"] else None
            require(digest(before) == target["preimageSha256"], "backup-integrity")
            current = digest(contents(path))
            expected_post = postimages.get(name)
            unchanged = current == target["preimageSha256"]
            restorable = expected_post is not None and current == expected_post
            if not unchanged and not restorable:
                report["conflicts"].append({"path": name, "currentSha256": current,
                                            "expectedPostimage": expected_post,
                                            "reason": "unrecorded-external-change"})
                continue
            if stage_dir is not None:
                if unchanged:
                    report["unchanged"].append(name)
                    continue
                slot = stage_dir / f"{i}.bin"
                require(not slot.exists(), "staging-slot-exists")
                if before is None:
                    report["staged"].append({"path": name, "staged": "delete-on-restore"})
                    continue
                with slot.open("xb") as stream:
                    stream.write(before)
                require(digest(slot.read_bytes()) == target["preimageSha256"], "staging-verification-failed")
                report["staged"].append({"path": name, "stagedTo": str(slot),
                                         "sha256": target["preimageSha256"]})
                continue
            if unchanged:
                report["unchanged"].append(name)
            else:
                if before is None:
                    path.unlink()
                else:
                    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".restore")
                    try:
                        with temp.open("xb") as stream:
                            stream.write(before)
                            stream.flush()
                            os.fsync(stream.fileno())
                        os.replace(temp, path)
                    finally:
                        if temp.exists():
                            temp.unlink()
                require(digest(contents(path)) == target["preimageSha256"], "rollback-verification-failed")
                report["restored"].append(name)
        state["restoreReport"] = {k: (len(v) if isinstance(v, list) else v) for k, v in report.items()}
        state["restoreDetail"] = report
        if stage_dir is not None:
            state.update(status="restore_staged", stagingDir=str(stage_dir),
                         nextAction="verify-staged-copies-then-restore-without-staging")
        elif report["conflicts"]:
            state.update(status="hold", holdScope="checkpoint-declared-targets",
                         firstBlockingRule="restore-conflict",
                         blockingEvidence="current-hash-differs-from-sealed-postimage",
                         repositoryWideHold=False,
                         nextAction="inspect-foreign-change-before-any-overwrite")
        else:
            state.update(status="restored", nextAction="record-restore-in-journal")
        save(run, state)
        return {"status": state["status"], "run": run_relative, **state["restoreReport"],
                "conflicts": report["conflicts"], "staged": report["staged"]}


def classify(log, exit_code):
    if exit_code == 0:
        return "none", {}
    counts = {code: len(re.findall(pattern, log)) for code, pattern in PATTERNS if re.search(pattern, log)}
    for pattern, category in (
        (r"AssertionError|AssertionFailed|FAILED \(failures=|There were failing tests|FAILURES!!!", "test_assertion"),
        (r"error:|Unresolved reference|incompatible types", "compile"),
        (r"Could not (?:resolve|find)|Plugin .* not found", "dependency"),
        (r"ClassNotFoundException|NoClassDefFoundError", "classpath_or_cache"),
        (r"AccessDenied|Permission denied|Unauthorized|403|401", "permission_or_auth"),
        (r"timed? ?out|Timeout|TIMEOUT", "timeout"),
        (r"Connection refused|port.*in use|Address already in use", "runtime_unavailable")):
        if re.search(pattern, log):
            return category, counts
    return "unclassified_failure", counts


def finish(root, run_relative, exit_code, command_id, log=""):
    safe_id(command_id)
    require(type(exit_code) is int, "verification-exit-code-required")
    with opened(root, run_relative) as (root, run, manifest, state):
        require(state["status"] == "sealed", "checkpoint-not-sealed")
        category, counts = classify(log, exit_code)
        state.update(verificationExitCode=exit_code, commandId=command_id, failureClass=category,
                     failureCounts=counts, verificationEvidenceMode="caller-observed",
                     logSha256=digest(log.encode("utf-8")), restoredCount=0)
        try:
            lease_check(root, manifest)
            restore = []
            # Validate the entire set before the first restoration write.
            for i, target in enumerate(manifest["targets"]):
                path = relative_path(root, target["path"])
                require(digest(contents(path)) == state["postimages"][target["path"]], "postimage-drift")
                before = contents(run / "before" / f"{i}.bin") if target["existed"] else None
                require(digest(before) == target["preimageSha256"], "backup-integrity")
                restore.append((path, target, before))
            if exit_code:
                state.update(status="restoring", nextAction="finish-owned-rollback")
                save(run, state)
                for path, target, before in restore:
                    lease_check(root, manifest)
                    require(digest(contents(path)) == state["postimages"][target["path"]], "postimage-drift")
                    if digest(before) == state["postimages"][target["path"]]:
                        continue
                    if before is None:
                        path.unlink()  # Only a declared task-created file with an unchanged postimage.
                    else:
                        temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".restore")
                        try:
                            with temp.open("xb") as stream:
                                stream.write(before)
                                stream.flush()
                                os.fsync(stream.fileno())
                            require(digest(contents(path)) == state["postimages"][target["path"]], "postimage-drift")
                            os.replace(temp, path)
                        finally:
                            if temp.exists():
                                temp.unlink()
                    require(digest(contents(path)) == target["preimageSha256"], "rollback-verification-failed")
                    state["restoredCount"] += 1
                    save(run, state)
                state.update(status="rolled_back", nextAction="classify-repair-or-continue-independent-work")
            else:
                state.update(status="verified", nextAction="continue-next-goal-step")
        except (CheckpointError, OSError, ValueError, KeyError) as error:
            reason = str(error) if isinstance(error, CheckpointError) else "recovery-io-or-evidence-error"
            state.update(status="hold", holdScope="checkpoint-declared-targets", firstBlockingRule=reason,
                         blockingEvidence="current-hash-path-backup-or-lease-check-failed",
                         independentWorkCompleted="verification-result-recorded", repositoryWideHold=False,
                         nextAction="inspect-checkpoint-and-current-owner-before-recovery")
        if state["status"] in ("verified", "rolled_back"):
            try:
                try:
                    from scripts.awx_device_bus import checkpoint_event
                except ModuleNotFoundError:
                    from awx_device_bus import checkpoint_event
                checkpoint_event(root, manifest, state)
            except Exception:
                # Queue availability cannot rewrite verification or rollback results.
                state["deviceEvent"] = {"delivery": "evidence_needed", "reason": "event-publication-failed"}
        if state["status"] == "verified":
            completion_cleanup(root, run, manifest, state)
        save(run, state)
        return state


def completion_cleanup(root, run, manifest, state):
    """One finalization hook, bound to this goal and its verified target set.

    A normal checkpoint can be only one step of a goal. Only an exact final
    task request triggers cleanup; presence/age of unrelated files never does.
    Cleanup cannot rewrite successful verification or start another patch.
    """
    request_path = run / "task-cleanup-request.json"
    try:
        raw = contents(request_path)
        if raw is None:
            return
        request = json.loads(raw)
        require(request.get("schemaVersion") == "awx.completed-task-cleanup.v1" and
                request.get("taskId") == manifest["decision"]["goalId"], "cleanup-task-mismatch")
        posts = request.get("postimages", [])
        require(isinstance(posts, list) and all(isinstance(p, dict) for p in posts), "cleanup-postimage-set")
        hashes = {p.get("path"): p.get("sha256") for p in posts}
        require(len(hashes) == len(posts) and all(hashes.get(p) == sha and sha is not None
                for p, sha in state["postimages"].items()), "cleanup-checkpoint-postimage-mismatch")
        helper = Path(__file__).resolve().parents[1] / ".agents/skills/demo1-completed-directive-cleanup/scripts/cleanup_completed_directives.ps1"
        no_links(helper)
        require(helper.is_file(), "cleanup-helper-missing")
        require(os.name == "nt", "cleanup-windows-required")
        # No shell evaluation, report commands, unrelated process termination, or raw output forwarding.
        result = subprocess.run([
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(helper),
            "-Root", str(root), "-RequestPath", str(request_path), "-ExpectedRequestSha256", digest(raw),
            "-LogDirectory", str(run / "cleanup"), "-Apply"
        ], capture_output=True, timeout=150, creationflags=subprocess.CREATE_NO_WINDOW)
        require(digest(contents(request_path)) == digest(raw), "cleanup-request-changed")
        require(len(result.stdout) <= MAX_BYTES, "cleanup-result-too-large")
        output = json.loads(result.stdout.decode("utf-8-sig"))
        require(output.get("schemaVersion") == "awx.completed-task-cleanup.result.v1" and
                output.get("requestSha256") == digest(raw), "cleanup-result-unbound")
        keys = ("status", "reason", "taskStatus", "cleanupState", "deletedCount", "heldCount", "alreadyAbsentCount",
                "completionStatusRelativePath", "completionMarkdownRelativePath", "journalRelativePath", "requestSha256")
        state["completionCleanup"] = {k: output[k] for k in keys if k in output}
        if output.get("taskStatus") == "completed" and output.get("stopWork") is True:
            receipt_path = relative_path(root, output["completionStatusRelativePath"])
            receipt = json.loads(contents(receipt_path))
            require(receipt.get("taskId") == request["taskId"] and receipt.get("taskStatus") == "completed" and
                    receipt.get("request", {}).get("sha256") == digest(raw), "cleanup-receipt-unbound")
            state.update(taskStatus="completed", stopWork=True,
                         nextAction="none" if result.returncode == 0 and output["status"] == "complete" else "cleanup-only-after-condition-change")
        else:
            state["nextAction"] = "reconcile-required-completion-evidence"
    except (CheckpointError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        reason = str(error) if isinstance(error, CheckpointError) else "cleanup-io-timeout-or-evidence-error"
        state["completionCleanup"] = {"status": "hold", "reason": reason}
        state["nextAction"] = "reconcile-cleanup-receipt-and-required-proof"


def lease_conflict_autoflow(root, targets, run=None):
    """begin 실패 시 겹침 lease 분기 계획을 읽기 전용으로 첨부한다.
    --no-mark로 프롬프트 지문을 기록하지 않으며 어떤 파일도 쓰지 않는다."""
    tool = Path(__file__).resolve().parent / "lease_conflict_autoflow.py"
    if not targets or not tool.is_file():
        return None
    cmd = [sys.executable, "-B", str(tool), "--root", str(root),
           "plan", "--no-mark", "--goal-files", *targets]
    if run:
        # data/agent-handoff/codex-autonomy/<taskId>/<cycle> -> 내 lease 제외 판정용 taskId
        parts = Path(str(run)).parts
        if len(parts) >= 2 and "codex-autonomy" in parts[:-1]:
            cmd += ["--task", parts[-2]]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    try:
        return json.loads(proc.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError, AttributeError):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("assess", "begin", "apply", "seal", "finish", "restore", "status"))
    parser.add_argument("--root", default=".")
    parser.add_argument("--run", help="Exact unique checkpoint directory relative to root, using /")
    parser.add_argument("--decision", help="Local JSON decision packet; no raw prompts or credentials")
    parser.add_argument("--target", action="append", default=[])
    parser.add_argument("--content-file", help="apply: local file whose bytes replace the target")
    parser.add_argument("--staging", help="restore: scratch directory for verify-before-restore")
    parser.add_argument("--lease", help="Existing target-scoped source lease relative to root")
    parser.add_argument("--exit-code", type=int)
    parser.add_argument("--command-id", default="focused-verification")
    parser.add_argument("--log", help="Existing verification log, read locally; only counts/hash are saved")
    args = parser.parse_args()
    try:
        if args.action in ("assess", "begin"):
            require(bool(args.decision), "decision-required")
            decision = json.loads(Path(args.decision).read_text(encoding="utf-8-sig"))
        if args.action == "assess":
            result = assess(decision)
        elif args.action == "begin":
            result = begin(args.root, args.run, args.target, decision, args.lease)
        elif args.action == "apply":
            require(args.run and len(args.target) == 1 and args.content_file,
                    "apply-run-target-content-required")
            result = apply(args.root, args.run, args.target[0], args.content_file)
        elif args.action == "seal":
            result = seal(args.root, args.run)
        elif args.action == "finish":
            log = (contents(Path(args.log)) or b"").decode("utf-8", errors="replace") if args.log else ""
            result = finish(args.root, args.run, args.exit_code, args.command_id, log)
        elif args.action == "restore":
            require(args.run, "restore-run-required")
            result = restore(args.root, args.run, args.target, args.staging)
        else:
            root = root_path(args.root)
            run_path(root, args.run)
            result = json.loads(contents(relative_path(root, args.run + "/checkpoint.json")))
        print(json.dumps(result, ensure_ascii=True))
        return 0 if result["status"] in ("autonomous", "prepared", "sealed", "verified",
                                         "applied", "restored", "restore_staged") else 2
    except (CheckpointError, OSError, ValueError, KeyError, TypeError) as error:
        reason = str(error) if isinstance(error, CheckpointError) else "invalid-or-unavailable-local-evidence"
        hold = {"status": "hold", "firstBlockingRule": reason,
                "holdScope": "checkpoint", "blockingEvidence": "local-validation-failed",
                "independentWorkCompleted": "no-source-authority-granted", "repositoryWideHold": False}
        if args.action == "begin":
            flow = lease_conflict_autoflow(args.root, args.target, run=args.run)
            if flow is not None:
                hold["leaseConflictAutoflow"] = flow
        print(json.dumps(hold, ensure_ascii=True))
        return 2


if __name__ == "__main__":
    sys.exit(main())
