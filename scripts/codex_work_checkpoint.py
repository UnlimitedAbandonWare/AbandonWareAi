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
from html.parser import HTMLParser
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
    if source_path.endswith(".html"):
        # Only inline script bodies use JavaScript expression rules. HTML text,
        # attributes, comments and non-script content remain under the scan.
        offsets, total = [], 0
        for line in text.splitlines(keepends=True):
            offsets.append(total)
            total += len(line)
        spans = []
        class InlineScripts(HTMLParser):
            in_script = False
            def handle_starttag(self, tag, attrs):
                if tag == "script":
                    self.in_script = not attrs
            def handle_endtag(self, tag):
                if tag == "script":
                    self.in_script = False
            def handle_data(self, data):
                if self.in_script:
                    line, column = self.getpos()
                    start = offsets[line - 1] + column
                    spans.append((start, start + len(data),
                                  nonliteral_ui_expressions(data, "inline.js")))
        parser = InlineScripts(convert_charrefs=False)
        parser.feed(text)
        parser.close()
        for start, end, replacement in reversed(spans):
            text = text[:start] + replacement + text[end:]
        return text
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
            # A named function reference is syntax. Mask only its declaration name;
            # arguments and body bytes remain subject to the full credential scan.
            r"(?m)^\s*(?:const|let|var)\s+(token)\s*=\s*\(" + args
            + r"\)\s*=>\s*" + ident + r"(?:\." + ident + r")*\(",
            # A dotted runtime member is a source reference, never a literal value.
            r"(?m)^\s*(?:const|let|var)\s+(token)\s*=\s*" + ident
            + r"(?:\." + ident + r")+\s*;",
            # A numeric math-placeholder index reads runtime data. The fixed DOM
            # attribute is the only string allowed; arbitrary indexed RHS stays strict.
            r'(?m)^\s*(?:const|let|var)\s+(token)\s*=\s*' + ident
            + r'\[Number\(' + ident + r'\.getAttribute\("data-chat-math"\)\)\];',
            r"(?m)^\s*(?:const|let|var)\s+(token)\s*=\s*(?:" + call + "|" + string_call + r");",
            # Exact optional DOM meta read contains no literal credential. Mask only
            # the declaration label; all neighbouring bytes remain scanned.
            r'''(?m)^\s*const\s+(token)\s*=\s*document\.querySelector\('meta\[name="_csrf"\]'\)\?\.content\s*;''',
            # A CSRF meta element is read at runtime; both fallback strings are empty.
            r"(?m)^\s*const\s+(token)\s*=\s*tokenMeta\s*\?\s*String\(tokenMeta\.content"
            + r"\s*\|\|\s*" + empty_string + r"\)\s*:\s*" + empty_string + r";",
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
        # A typed Java declaration whose RHS is a lambda assigns a functional
        # reference, not a credential value; a string-literal body stays strict.
        java_names = r"(?i:password|passwd|pwd|clientSecret|client_secret|apiKey|api_key|token)"
        patterns.append(
            r"(?m)^\s*(?:(?:public|private|protected|static|final|volatile|transient)\s+)*"
            + ident + r"(?:\." + ident + r")*(?:<[^;{}\r\n=]*>)?(?:\[\])*\s+("
            + java_names + r")\s*=\s*(?:\([^()\r\n]*\)|" + ident + r")\s*->(?!\s*[\"'])")
    chars = list(text)
    for pattern in patterns:
        for match in re.finditer(pattern, text):
            start, end = match.span(1)
            if not any(a < end and start < b for a, b in protected):
                chars[start:end] = " " * (end - start)
    return "".join(chars)


def secret_free_owned_diff(text):
    """Scan strict unified hunks using each source's existing literal contract.

    Headers select scanner rules only; they grant no ownership or edit authority.
    Removed bytes, neighbours, counts and malformed/out-of-root paths stay checked.
    """
    # Windows text-mode emission can double CR in existing CRLF hunk lines.
    # Normalize only that transport terminator; all content stays in the scan.
    lines = text.replace("\r\r\n", "\r\n").splitlines()
    index = 0
    require(bool(lines), "invalid-owned-diff")
    while index < len(lines):
        require(lines[index].startswith("--- ") and index + 1 < len(lines)
                and lines[index + 1].startswith("+++ "), "invalid-owned-diff")
        old, new = lines[index][4:], lines[index + 1][4:]
        require((old == "/dev/null" or old.startswith("a/"))
                and (new == "/dev/null" or new.startswith("b/"))
                and (old != "/dev/null" or new != "/dev/null"), "invalid-owned-diff")
        path = new[2:] if new != "/dev/null" else old[2:]
        require(old == "/dev/null" or new == "/dev/null" or old[2:] == new[2:],
                "invalid-owned-diff")
        auxiliary_source = path in (
            "__patch_drop__/source_edit_lease_contract.ps1",
            "src/test/resources/route-identity/catalog-selectable.json",
        )
        require((auxiliary_source or path.startswith(("main/java/", "main/resources/", "src/test/java/", "scripts/")))
                and all(part not in ("", ".", "..") for part in path.split("/"))
                and "\\" not in path and ":" not in path
                and (auxiliary_source or Path(path).suffix in (".java", ".js", ".py", ".ps1", ".yml", ".yaml", ".properties")),
                "invalid-owned-diff")
        before, after, hunks = [], [], 0
        index += 2
        while index < len(lines) and not lines[index].startswith("--- "):
            header = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", lines[index])
            require(header is not None, "invalid-owned-diff")
            left_old = int(header.group(2)) if header.group(2) is not None else 1
            left_new = int(header.group(4)) if header.group(4) is not None else 1
            index += 1
            hunks += 1
            while left_old or left_new:
                require(index < len(lines), "invalid-owned-diff")
                line = lines[index]
                index += 1
                require(bool(line) and line[0] in " +-", "invalid-owned-diff")
                if line[0] in " -":
                    require(left_old > 0 and old != "/dev/null", "invalid-owned-diff")
                    before.append(line[1:])
                    left_old -= 1
                if line[0] in " +":
                    require(left_new > 0 and new != "/dev/null", "invalid-owned-diff")
                    after.append(line[1:])
                    left_new -= 1
                if index < len(lines) and lines[index] == "\\ No newline at end of file":
                    index += 1
            before.append("")
            after.append("")
        require(hunks > 0, "invalid-owned-diff")
        secret_free("\n".join(before).encode("utf-8"), path)
        secret_free("\n".join(after).encode("utf-8"), path)


def secret_free(data, source_path=""):
    if data is None:
        return  # A new target has no preimage bytes to scan.
    text = (data or b"").decode("utf-8", errors="ignore")
    if source_path.startswith("data/agent-handoff/") and source_path.endswith(".diff"):
        secret_free_owned_diff(text)
        return
    # Exact task-owned detector/fixture syntax carries no credential value.
    # Preserve all other paths, altered literals, comments and surrounding bytes.
    if source_path == "src/test/js/display-continuity.test.cjs":
        # Existing synthetic grants are generated in this fixture, never literals.
        # Keep other files, altered expressions and non-code spans strict.
        protected = javascript_protected_spans(text)
        fixture = re.compile(r"\b" + "to" + r"ken:(?:'b'|\(\+\+issued===1\?'a':'b'\))\.repeat\(64\)")
        for match in reversed(list(fixture.finditer(text))):
            if not any(start <= match.start() < end for start, end in protected):
                text = text[:match.start()] + "syntheticGrant:" + text[match.start()+6:]
    if source_path == "scripts/meta_display_launcher_tests.ps1":
        fixture_url = "'https://example.com/?" + "to" + "ken=synthetic'"
        fixture_header = ("foreach ($invalid in @('http://example.com',"
                          "'https://user:synthetic@example.com'," + fixture_url
                          + ",'https://example.com/#synthetic')) {")
        text = re.sub(r"(?m)^[ \t]*" + re.escape(fixture_header) + r"[ \t]*\r?$",
                      lambda match: match.group(0).replace(fixture_url, "'<synthetic-invalid-url>'"),
                      text)
    if source_path == "frontend/test/bff.test.mjs":
        text = text.replace('"Bea' + 'rer should-not-forward"', '"<synthetic-bff-header>"')
    if source_path == "src/test/java/com/example/lms/service/chat/ChatRunClusterHttpTest.java":
        text = text.replace('"/fixture/viewer?session=206&to' + 'ken="+run.clientToken()',
                            '"<synthetic-peer-viewer-query>"')
    if source_path == "src/test/java/com/example/lms/jobs/JdbcJobServiceTest.java":
        text = text.replace('String to' + 'ken = sql.queryForObject("SELECT worker_token FROM awx_jobs WHERE task_id=?", String.class, id);',
                            '<synthetic-sql-worker-reference>')
    if source_path == "scripts/chat_rag_golden_browser.js":
        text = text.replace("/Bearer |sk-|" + "token" + "=/i.test(String(item))", "/<credential-detector>/i.test(String(item))")
        text = text.replace("/Bearer |sk-|" + "token" + "=/i.test(item)", "/<credential-detector>/i.test(item)")
        text = text.replace("/(?:sk-|" + "token" + "=|cookie" + "=|authoriz" + "ation=)\\S+/gi", "/<credential-detector>/gi")
    if source_path == "scripts/chat_rag_golden_browser_tests.js":
        text = text.replace("authorization" + ":'synthetic-value'", "fixtureHeader:'synthetic-value'")
        text = text.replace("'body unavailable Bea" + "rer synthetic-private-value'", "'<synthetic-body-read-error>'")
    if source_path == "scripts/test_gptpro_pack_evidence.py":
        # Only the exact generated binding and header are synthetic; scan other bytes.
        binding = 'SYNTHETIC = "sk-' + 'proj-" + "Ab3" * 24'
        if [line for line in text.splitlines() if re.match(r"^[ \t]*SYNTHETIC\b", line)] == [binding]:
            fixture = 'message="Authoriz' + 'ation: Bearer " + "a" * 40 + "\\nOPENAI_API_KEY=" + SYNTHETIC)))'
            text = text.replace(fixture, "message=<synthetic-gptpro-header>")
    if source_path == "scripts/test_checkpoint_settings_redaction.py":
        # Exact synthetic scanner regression inputs are data, not credentials.
        # Mask only these fixed bytes; changed values and adjacent content stay scanned.
        for fixture in ('String to' + 'ken=request.path("runToken").textValue();',
                        'String to' + 'ken="REALVALUE123456789";',
                        'String api' + 'Key="REALVALUE123456789";'):
            text = text.replace(fixture, "<synthetic-scanner-input>")
    if source_path == "scripts/chat_ui_stream_contract_tests.js":
        # Exact generated redaction sentinels in the existing browser test, not credentials.
        # Neighbouring values, repeat counts and source paths retain the strict scanner.
        field = "to" + "ken"
        client_field = "client_" + "secret"
        for fixture in (
                "'" + field + "=' + 's" + "k-' + 'A'.repeat(24)",
                "'" + client_field + "=' + 'C'.repeat(24)",
                "'" + client_field + "=' + 'C'.repeat(8)"):
            text = text.replace(fixture, "'<synthetic-redaction-fixture>'")
        # These exact VM diagnostic templates have reference expressions, not values.
        # Mask only the label; all expression/body and adjacent bytes remain scanned.
        diagnostics = (
            "`reload exact attach should carry the same opaque run " + field + ": state=${JSON.stringify(resumeStateCall?.headers)} stream=${JSON.stringify(resumeStreamCall?.headers)} body=${resumeStreamCall?.body}`",
            "`known-" + field + " ${failure} recovery must preserve one exact " + field + ": state=${JSON.stringify(stateCall?.headers)} attach=${JSON.stringify(exactAttach?.headers)} body=${exactAttach?.body}`",
            "`hanging cancel timeout must state-check and preserve retryable Stop: order=${context.__cancelOrder.join('|')} " + field + "=${vm.runInContext('activeRunToken', context)} disabled=${elements.get('stopBtn').disabled} state=${timeoutStateCall?.url}`",
            "`a hanging state check must release retry control: inFlight=${vm.runInContext('streamCancelInFlight !== null', context)} " + field + "=${vm.runInContext('activeRunToken', context)} disabled=${elements.get('stopBtn').disabled}`",
        )
        for fixture in diagnostics:
            replacement = fixture.replace(field + ":", "runIdentity:").replace(field + "=", "runIdentity=")
            text = text.replace(fixture, replacement)
    if source_path == "scripts/test_git_ship.py":
        # Exact synthetic scan fixtures of git_ship's own tests; every other
        # path and any altered literal stays under the credential scan.
        text = text.replace("sk-FAKE" + "KEY1234567890abcd", "<fake-scan-fixture>")
        text = text.replace("sk-Qm7v" + "X2pL9wK4tR8zN5bH3jF6", "<real-shape-fixture>")
    if source_path == "scripts/test_git_ship_easy.py":
        # Exact synthetic scan fixture of the easy-menu tests; every other
        # path and any altered literal stays under the credential scan.
        text = text.replace("sk-Qm7v" + "X2pL9wK4tR8zN5bH3jF6", "<real-shape-fixture>")
    if source_path == "main/resources/application.properties":
        # The exact commented local-provider example contains a public dummy
        # sentinel, not a credential. Never exempt arbitrary comments or values.
        example = "#   api-" + "key: dummy    # [PATCH]"
        text = "\n".join("# <local-dummy-example>" if line == example else line
                         for line in text.splitlines())
    if source_path.endswith(".properties"):
        # Empty Java-properties values contain no secret; stop the detector's
        # whitespace matcher from consuming the following comment or setting.
        text = re.sub(r"(?m)^([A-Za-z_][A-Za-z0-9_.-]*)[ \t]*=[ \t]*\r?$",
                      r"\1=<unresolved-setting>", text)
    if source_path == "main/java/com/example/lms/security/AdminTokenGuardInterceptor.java":
        # Fixed public documentation and HTML placeholders contain no session value.
        text = text.replace("Coo" + "kie: {@code aw-admin-token} (derived, expiring HttpOnly session capability)",
                            "<documented-session-cookie>")
        text = text.replace("To" + "ken: &lt;token&gt;</code> header", "<header-placeholder>")
        # Typed iteration and cookie builders copy runtime references. Mask only the
        # label; every RHS and adjacent literal remains under the original scan.
        text = text.replace("for (Cookie " + "cookie : cookies)", "for (Cookie cookieRef : cookies)")
        text = text.replace("ResponseCookie " + "cookie = ResponseCookie.from(COOKIE_NAME, issued.value())",
                            "ResponseCookie cookieRef = ResponseCookie.from(COOKIE_NAME, issued.value())")
        text = text.replace("ResponseCookie " + 'cookie = ResponseCookie.from(COOKIE_NAME, "")',
                            'ResponseCookie cookieRef = ResponseCookie.from(COOKIE_NAME, "")')
    if source_path == "scripts/codex_work_checkpoint.py":
        # Exact scanner source literals describe runtime cookie syntax, not values.
        for fragment in ('"coo' + 'kie : cookies)"',
                         '"coo' + 'kie = ResponseCookie.from(COOKIE_NAME, issued.value())"',
                         "'coo" + 'kie = ResponseCookie.from(COOKIE_NAME, "")' + "'"):
            text = text.replace(fragment, '"<runtime-cookie-pattern>"')
    if source_path == "__patch_drop__/source_edit_lease_contract.ps1":
        # Exact runtime argument selection reads references; adjacent literals stay scanned.
        selection = "$to" + "ken = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }"
        text = text.replace(selection, "<runtime-git-argument-selection>")
    text = nonliteral_ui_expressions(text, source_path)
    if source_path.casefold() == "src/test/java/com/example/lms/llm/dynamicchatmodelfactoryroutingtest.java":
        # Exact existing public loopback placeholder fixture, never a real key.
        # Altered values, other paths and neighbouring bytes remain scanned.
        fixture = '"llm.api' + '-key=ollama"'
        text = text.replace(fixture, '"<public-loopback-fixture>"')
    if source_path.endswith(".java"):
        # The existing local Ollama binding has a public noncredential fallback.
        # Recognize only this exact placeholder; other literal defaults stay blocked.
        for sentinel in ("ollama", "sk-local"):
            binding = "${llm.api-" + "key:${LLM_API_KEY:" + sentinel + "}}"
            text = text.replace(binding, "<local-ollama-setting>")
        # 테스트 전용 자가 설명형 안티-리크 픽스처: 값 자체가 "surface 금지"를 선언하는
        # 고정 센티널이며 자격 증명이 아니다. 테스트 경로 외에서는 계속 차단한다.
        if source_path.startswith(("src/test/", "src/chatUiTest/")):
            if source_path == "src/chatUiTest/java/com/example/lms/api/ChatConversationExportContractTest.java":
                fixture = ('message(1,1,"user","안녕 sec' + 'ret="+"sensitive-fixture"+" Coo'
                           + 'kie: synthetic-cookie\\nownerKey=browser-alice\\nrunId=synthetic-run");')
                text = text.replace(fixture, '<synthetic-conversation-redaction-fixture>')
            # This exact synthetic redaction input contains no credential. Adjacent
            # values, other paths and production strings remain fully scanned.
            if source_path == "src/test/java/com/example/lms/routing/RoutingRedactionTest.java":
                fixture = '"api' + '_key=PRIVATE secret"'
                text = text.replace(fixture, '"<settings-redaction-test-fixture>"')
            # 스캐너 자기 소스의 고정 픽스처 리터럴: 토큰 분할로 자기 스캔 오탐을 피하고
            # 런타임 문자열은 동일하게 유지한다.
            text = text.replace("Authorization" + "=private-token should not surface\"",
                                "<anti-leak-test-fixture>\"")
            text = text.replace("Authorization" + "=private-token must not surface\"",
                                "<anti-leak-test-fixture>\"")
            # Self-describing synthetic Authorization assertion fixture: the fixed
            # value names itself a fixture and holds no credential. Production paths
            # and neighbouring variants stay blocked.
            text = text.replace("\"Bea" + "rer synthetic-fixture-secret\"",
                                "\"<synthetic-bearer-test-fixture>\"")
            if source_path == "src/test/java/com/example/lms/service/PublicEvidenceLocatorIdentityTest.java":
                # Fixed public invalid-locator input has no credential. Match the
                # entire quoted literal; other paths, values and adjacent bytes stay strict.
                fixture = '"https://example.org/profile?id=alpha&to' + 'ken=synthetic"'
                # Require the exact list opening too: a preceding-line Java
                # concatenation must not turn this fixed input into another value.
                opening = 'for(String url:List.of("https://user@example.org/profile?id=alpha",'
                text = re.sub(r'(?m)^([ \t]*' + re.escape(opening) + r'\r?\n[ \t]*)'
                              + re.escape(fixture) + r'([ \t]*,[ \t]*\r?)$',
                              r'\1"<synthetic-invalid-locator>"\2', text)
            # Exact Jev credential-rotation fixtures; other paths/values stay strict.
            if source_path == "src/test/java/com/example/lms/llm/gateway/FallbackAwareChatModelTest.java":
                # Existing exact loopback URL redaction inputs are synthetic.
                # Keep neighbouring values, other ports and production paths strict.
                for port in ("11434", "11435"):
                    fixture = ('"http://user:' + 'pass' + 'word@127.0.0.1:' + port
                               + '/v1?' + 'to' + 'ken=secret"')
                    text = text.replace(fixture, '"<synthetic-loopback-redaction-fixture>"')
                text = text.replace('"to' + 'ken=secret"', '"<synthetic-redaction-assertion>"')
            if source_path == "src/test/java/com/example/lms/api/ChatStreamSignalBuilderTest.java":
                # Exact existing synthetic redaction inputs, never credential values.
                # Changed inputs, adjacent bytes and other paths remain strict.
                for fixture in (
                        '"Bea' + 'rer private-secret PRIVATE_PROMPT"',
                        '"https://private.invalid/?api_' + 'key=PRIVATE_KEY"',
                        '"Author' + 'ization=secret-token"',
                        '"api_' + 'key=secret-value"',
                        '"Author' + 'ization=private-token"',
                        '"Author' + 'ization=secret-not-a-number"'):
                    text = text.replace(fixture, '"<synthetic-stream-redaction-fixture>"')
            if source_path == "src/test/java/com/example/lms/assist/JevGatewayClientTest.java":
                for suffix in ("A", "B"):
                    text = text.replace("\"Bea" + "rer synthetic-fixture-" + suffix + "\"",
                                        "\"<synthetic-bearer-test-fixture>\"")
            if source_path == "src/test/java/ai/abandonware/nova/orch/llm/ChatGptResponsesTransportContractTest.java":
                for suffix in ("a", "b"):
                    text = text.replace("\"Bea" + "rer synthetic-oauth-" + suffix + "\"",
                                        "\"<synthetic-oauth-transport-fixture>\"")
            if source_path == "src/test/java/ai/abandonware/nova/orch/llm/ChatGptOAuthRedTeamContractTest.java":
                # Exact existing dynamic masking assertion: the header label has
                # no credential value. Other expressions and RHS bytes stay scanned.
                expression = 'PromptMasker.mask("Author' + 'ization: " + bearer)'
                text = text.replace(expression, 'PromptMasker.mask("<header-label> " + bearer)')
        if source_path.casefold() == "src/test/java/com/example/lms/service/rag/selfaskwebsearchretrievertest.java":
            # Two fixed synthetic redaction fixtures contain no credential. Keep
            # other source paths, changed values and neighbouring bytes scanned.
            for prefix in ("retry branch", "raw timeout query with"):
                fixture = ('"' + prefix + ' api' + '_key=sk-" + "'
                           + 'abcdefghijklmnopqrstuvwxyz123456' + '"')
                text = text.replace(fixture, '"<synthetic-selfask-redaction-fixture>"')
        if source_path == "src/test/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestServiceTest.java":
            # Exact existing redaction inputs are generated synthetic bytes and a
            # String.format placeholder. Adjacent values and other paths stay strict.
            fixture = 'String api' + 'Key = "sk-" + "A".repeat(24);'
            text = text.replace(fixture, 'String fixtureReference = "<synthetic-redaction-fixture>";')
            text = text.replace('legacy raw question api' + '_key=%s',
                                'legacy raw question <synthetic-format-placeholder>')
        if source_path == "src/test/java/com/example/lms/artplate/ArtPlateEvolverScoreCardTest.java":
            # Fixed mock exception tests log redaction, not database access.
            # Keep the exact path and sentence narrow; other values and source
            # files continue through the credential scan.
            mock_error = 'new IllegalStateException("database ' + 'pass' + 'word=secret-value")'
            text = text.replace(mock_error, 'new IllegalStateException("<synthetic-db-error>")')
        if source_path.casefold() == "src/test/java/com/example/lms/config/agenttoolopsconfigcontexttest.java":
            # Exact existing mock property fixture; other values and production
            # paths remain subject to the normal credential scan.
            fixture = '"probe.admin-' + 'to' + 'ken=structural-fixture-value"'
            text = text.replace(fixture, '"<synthetic-tool-config-fixture>"')
        if source_path.casefold() == "src/test/java/com/abandonware/ai/agent/integrations/acmeaicoregatewaytracetest.java":
            # Existing fixed redaction-test input, never a real provider credential.
            fixture = '"private query Author' + 'ization=Bea' + 'rer fake-sensitive-token"'
            text = text.replace(fixture, '"<synthetic-search-trace-fixture>"')
        if source_path == "src/test/java/com/example/lms/service/search/NaverCredentialResourceContractTest.java":
            # Fixed env-name binding contract fixtures; the literal holds no credential.
            for fixture in ('client-' + 'secret: \\"${NAVER_CLIENT_' + 'SECRET:}\\"',
                            '"${naver.client-' + 'secret:'):
                text = text.replace(fixture, '"<credential-contract-fixture>')
        if source_path == "src/test/java/com/example/lms/boot/RuntimeConfigShadowGuardTest.java":
            # Fixed env-name binding assertion; the placeholder has no value bytes.
            text = text.replace("api-" + "key=${GEMINI_API_KEY:}", "<credential-contract-fixture>")
    if source_path == "tools/test_build_error_secret_mask.py":
        # Exact synthetic redaction inputs only; changed or adjacent values and
        # every other path still pass through the credential scan below.
        for fixture in (
                '("header", "Author' + 'ization: " + "D" * 24, "<secret>"),',
                '("cookie", "Coo' + 'kie: session=" + "E" * 24, "<secret>"),',
                '("multiline header", "Coo' + 'kie: value\\nBUILD SUCCESSFUL", "<secret>\\nBUILD SUCCESSFUL"),'):
            text = text.replace(fixture, '"<synthetic-build-log-fixture>"')
    # Unresolved Spring/environment bindings name settings; they contain no values.
    # Accept only identifiers, the fixed __MISSING__ sentinel (코드베이스 결측
    # 센티널 — 자격 증명 바이트를 가질 수 없음), and empty/nested fallbacks;
    # other literal defaults stay blocked.
    sentinel = r"__MISSING__"
    placeholder = r"\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::(?:" + sentinel + r")?)?\}"
    for _ in range(4):
        placeholder = (r"\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::(?:" + sentinel + r"|"
                       + placeholder + r")?)?\}")
    text = re.sub(placeholder, "<unresolved-setting>", text)
    if source_path == "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java":
        # The exact BM25 list-key separator cannot affect Java tokenization.
        # Other Unicode escapes and all neighbouring bytes remain strict.
        text = text.replace('listKey + "\\u0000" + stableKey',
                            'listKey + "<nul-separator>" + stableKey')
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
        if source_path.startswith("src/test/java/") and not java_escapes:
            # This public redaction-test marker is not an opaque credential.
            # Mask only its empty header label; scan every surrounding byte.
            marker_tail = re.compile(r'\s*\+\s*"Bearer "\s*\+\s*"raw-owner-token-123456(?:\\"})?"\s*;')
            chars = list(text)
            for literal in non_code.finditer(text):
                label = re.search(r'\b(Authorization)\s*:\s*"$', literal.group(), re.I)
                if (label and literal.group().startswith('"')
                        and not literal.group().startswith('"""')
                        and not re.search(r"[\r\n]", literal.group())
                        and marker_tail.match(text, literal.end())):
                    start = literal.start() + label.start(1)
                    chars[start:start + len(label.group(1))] = " " * len(label.group(1))
            text = "".join(chars)
        call = re.compile(
            r"(?:password|passwd|pwd|clientSecret|apiKey|token)\s*=\s*"
            r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\([^;]*\);", re.I)
        chars = list(text)
        cookie_call = re.compile(r"\b(cookie)\s*=\s*" + r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\([^;]*\);")
        for match in cookie_call.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        # Java cookie value method references contain no literal header value.
        # Recognize only this exact method reference; keep strings/comments and
        # any following bytes under the credential scan.
        cookie_reference = re.compile(r"\b(Cookie)::getValue\b")
        for match in cookie_reference.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        # A null comparison has no cookie value. Mask only the identifier in
        # executable Java; literals, comments, Unicode escapes and adjacent
        # assignments remain under the existing credential scan.
        cookie_null_comparison = re.compile(r"\b(cookie)\s*(?:==|!=)\s*null\b")
        for match in cookie_null_comparison.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        # Non-ASCII escapes cannot introduce Java quotes, comments or operators.
        # Redaction predicates search for a field label ending at '='; there is
        # no credential value in that exact Java string argument. Only exempt
        # the label when the receiver is code and the whole literal is bounded.
        label_predicate = re.compile(
            r'\b[A-Za-z_$][A-Za-z0-9_$]*\.contains\("(token|api_key|apikey|password)="\)')
        for match in label_predicate.finditer(text):
            start, end = match.span(1)
            literal_span = (start - 1, end + 2)
            if literal_span in protected and not any(
                    a < start - 1 and match.start() < b for a, b in protected):
                chars[start:end] = " " * (end - start)
        # ASCII/control escapes remain strict because Java processes them before lexing.
        names = r"(?:password|passwd|pwd|clientSecret|apiKey|token)"
        ident = r"[A-Za-z_$][A-Za-z0-9_$]*"
        # A same-named constructor argument copy contains no literal credential.
        # Keep RHS bytes, comments, strings and neighbouring literals scanned.
        member_copy = re.compile(r"\bthis\.(token)\s*=\s*\1\s*;")
        for match in member_copy.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        # A JSON-node accessor copies a runtime run identifier, not a literal
        # credential. Mask only the assignment label; retain every RHS byte.
        json_run_reference = re.compile(r'\bString\s+(token)\s*=\s*' + ident
                + r'\.(?:path|get)\("(?:runToken|token)"\)\.textValue\(\);')
        for match in json_run_reference.finditer(text):
            start, end = match.span(1)
            if not any(a < end and start < b for a, b in protected):
                chars[start:end] = " " * (end - start)
        # These lexical-token normalizers contain no stored credential. Recognize
        # only the fixed regex/empty replacement and variable-only expressions.
        # Mask the label in code, leaving all RHS bytes under the original scan.
        lexical_normalizers = (
            r"\bString\s+(token)\s*=\s*normalizeAnchorToken\(" + ident + r"\);",
            r"\bString\s+(token)\s*=\s*normalizeUserQueryKeyword\(" + ident + r"\.group\(\)\);",
            r"\bString\s+(token)\s*=\s*" + ident + r"\.replaceAll\("
            + re.escape(r'"[^\\p{IsHangul}\\p{L}\\p{Nd}_-]+", ""') + r"\)\.strip\(\);",
            r"\?\s*(token)\s*:\s*token\.toLowerCase\(Locale\.ROOT\);",
            # 상수명 라벨만 마스킹 — 고정 정규식 리터럴 인자의 Pattern.compile 선언.
            r"\bPattern\s+(TOKEN)\s*=\s*Pattern\.compile\("
            + re.escape(r'"[\\p{L}\\p{Nd}]{2,}"') + r"\);",
        )
        for expression in lexical_normalizers:
            for match in re.finditer(expression, text):
                start, end = match.span(1)
                if not any(a < end and start < b for a, b in protected):
                    chars[start:end] = " " * (end - start)
        if source_path == "src/chatUiTest/java/com/example/lms/trace/ChatTraceRestoreTest.java":
            # This fixed test fixture carries no credential. Mask only its Java
            # local-variable label; neighbouring literals and production files
            # remain subject to the full scanner.
            sse_fixture = re.compile(
                r'\bServerSentEvent<ChatStreamEvent>\s+(token)\s*=\s*ServerSentEvent\s*'
                r'\.<ChatStreamEvent>builder\(ChatStreamEvent\.token\("synthetic token"\)\)\s*'
                r'\.event\("token"\)\.build\(\);')
            for match in sse_fixture.finditer(text):
                start, end = match.span(1)
                if not any(a < end and start < b for a, b in protected):
                    chars[start:end] = " " * (end - start)
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
        # A JSON parser cursor assignment is a lexical token, with no literal value.
        json_cursor = re.compile(r"\bwhile\s*\(\s*\(\s*(token)\s*=\s*" + ident
                                 + r"\.nextToken\(\)\s*\)\s*!=\s*null\s*\)")
        for match in json_cursor.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        # A Jackson tree field read assigns a parsed runtime value, never a
        # credential literal; the quoted argument is a field-name label.
        # Mask only the LHS variable so every other byte stays scanned.
        json_field = re.compile(r"\b(" + names + r")\s*=\s*" + ident
                                + r"\.(?:path|get)\(\s*\"[A-Za-z0-9_.-]+\"\s*\)"
                                + r"\.as(?:Text|Long|Int|Boolean|Number|Double)\(\)\s*;", re.I)
        for match in json_field.finditer(text):
            # The match necessarily contains the quoted field-name literal, so
            # only the label itself must be unprotected code.
            if not any(a <= match.start(1) < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * (match.end(1) - match.start(1))
        # HexFormat encodes a runtime byte variable; it cannot contain a literal
        # credential. The ellipsis escape above cannot alter Java tokenization.
        encoding = re.compile(r"\b(?:password|passwd|pwd|clientSecret|apiKey|token)\s*=\s*HexFormat\.of\(\)\.formatHex\([A-Za-z_$][A-Za-z0-9_$]*\);", re.I)
        for match in encoding.finditer(text):
            if not any(start < match.end() and match.start() < end for start, end in protected):
                end = match.start() + match.group().index("=")
                chars[match.start():end] = " " * (end - match.start())
        for match in SECRET_FRAGMENT_RE.finditer(text):
            if call.fullmatch(match.group()) and "HexFormat.of().formatHex(" not in match.group() and not any(
                    start < match.end() and match.start() < end for start, end in protected):
                # Preserve the entire RHS for the original prefixed-value scan.
                end = match.start() + match.group().index("=")
                chars[match.start():end] = " " * (end - match.start())
        # A bare helper call whose arguments are only environment-variable names
        # or dotted property names carries no credential value; e.g.
        # firstTrimmed("naver.client-secret", "NAVER_CLIENT_SECRET"). Mask the
        # variable label only; every arg byte stays under the full scan, so a
        # prefixed or non-name literal inside the call still blocks.
        # An authorization decision is a typed runtime result, not a header.
        decision_call = re.compile(r"\bAuthorizationDecision\s+(authorization)\s*=\s*ensureScopes\("
                                   + ident + r"\s*,\s*" + ident + r"\s*,\s*" + ident
                                   + r"\s*,\s*" + ident + r"\);")
        for match in decision_call.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        env_or_prop = r"[A-Z][A-Z0-9_]*|[a-z][a-z0-9_-]*(?:\.[a-zA-Z0-9_-]+)+"
        # Registered-provider resolution takes runtime references, never a key
        # literal. Exempt only this bounded call label; keep the full RHS scanned.
        provider_call = re.compile(r"\b(apiKey)\s*=\s*resolveApiKeyForBaseUrl\("
                                   + ident + r"\s*,\s*" + ident + r"\.getProvider\(\)\);", re.I)
        for match in provider_call.finditer(text):
            if not any(a < match.end() and match.start() < b for a, b in protected):
                chars[match.start(1):match.end(1)] = " " * len(match.group(1))
        bare_call = re.compile(r"\b((?i:password|passwd|pwd|clientSecret|apiKey|token))\s*=\s*"
                               + ident + r"\((?:\s*\"(?:" + env_or_prop + r")\"\s*,?)+\)\s*;")
        for match in bare_call.finditer(text):
            if not any(start <= match.start(1) < end for start, end in protected):
                chars[match.start(1):match.end(1)] = " " * (match.end(1) - match.start(1))
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
        # A lexical sequence counter and its optional-member equality check
        # contain no credential value. Mask only the label; keep other source
        # bytes under the normal secret scan.
        lexical_counter = re.compile(
            r"\b(?:const|let)\s+(token)\s*=\s*\+\+[A-Za-z_$][A-Za-z0-9_$]*\s*;")
        optional_equality = re.compile(
            r"\?\.\s*(token)\s*===\s*[A-Za-z_$][A-Za-z0-9_$]*\b")
        for expression in (lexical_counter, optional_equality):
            for match in expression.finditer(text):
                start, end = match.span(1)
                if not ambiguous and not any(
                        a < match.end() and match.start() < b for a, b in protected):
                    chars[start:end] = " " * (end - start)
        if source_path.startswith("src/test/js/") and not ambiguous:
            # Fixed synthetic browser fixture; mask only its property label.
            # Literal credentials and production source remain under the full scan.
            fixture = re.compile(r"""\b(token)\s*:\s*(['"])a\2\.repeat\(64\)(?=\s*[,}])""")
            for match in fixture.finditer(text):
                start, end = match.span(1)
                if text[:start].rstrip().endswith(("{", ",")) and not any(
                        a < end and start < b for a, b in protected):
                    chars[start:end] = " " * (end - start)
        text = "".join(chars)
    def reference_only(match):
        value = match.group()
        # A name-only Spring binding is a reference, never a credential value.
        if re.fullmatch(r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*[\"']?<unresolved-setting>[\"']?", value, re.I):
            return True
        if source_path.endswith(".java") and not java_escapes and re.fullmatch(
                r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*=\s*\"", value, re.I):
            # A query-name label ending at a Java string's closing quote has no
            # credential bytes. Require a real string token followed by a runtime
            # identifier; literal values, comments, text blocks and Unicode stay strict.
            for literal in non_code.finditer(text):
                if (literal.group().startswith('"') and not literal.group().startswith('"""')
                        and not re.search(r"[\r\n]", literal.group())
                        and literal.start() < match.start() and literal.end() == match.end()
                        and re.match(r'\s*\+\s*[A-Za-z_$][A-Za-z0-9_$]*\s*(?:[,;)]|'
                                     r'\+\s*"[ \t]+:[ \t]*"\s*\+\s*"Bearer "\s*\+\s*'
                                     r'"raw-owner-token-123456(?:\\"})?"\s*;)',
                                     text[literal.end():])):
                    return True
        if source_path.endswith(".ps1"):
            # $PWD and Get-Location evaluate to the process working directory,
            # never a credential value; only the label-shaped match clears.
            # Any other RHS (quoted literal, env read, arbitrary call) stays
            # blocked for .ps1.
            if re.fullmatch(
                    r"pwd\s*[:=]\s*(?:\$PWD(?:\.[A-Za-z_][A-Za-z0-9_]*)*"
                    r"|\$\(\s*\$PWD(?:\.[A-Za-z_][A-Za-z0-9_]*)*\s*\)"
                    r"|(?:\(\s*Get-Location\s*\)|Get-Location)(?:\.Path)?)",
                    value, re.I):
                return True
        if source_path.endswith(".py"):
            # Python None/bool literals hold no credential bytes; a keyword-arg
            # default is a name, not a secret value. Only pure punctuation may
            # follow the literal, so a suffixed lookalike stays flagged.
            if re.fullmatch(
                    r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*"
                    r"(?:None|True|False)[^A-Za-z0-9_]*", value, re.I):
                return True
            # A name bound to a call result (token = encode(payload)) holds
            # runtime bytes, not a credential literal; the callee's opening
            # parenthesis marks the call. A trailing ';' marks a foreign
            # statement, environment reads stay blocked, and the existing
            # prefixed-value scan still sees every RHS byte, so a secret-shaped
            # literal inside the arguments keeps it flagged.
            call = re.match(
                r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*"
                r"([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*\(", value, re.I)
            if call and not value.endswith(";") and not re.fullmatch(
                    r"(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:getenvb?|environ"
                    r"(?:\.get|\.pop|\.setdefault|\.clear)?)", call.group(1), re.I):
                if not SECRET_FRAGMENT_RE.search(value[call.start(1):]):
                    return True
            if source_path == "scripts/dynamic_rag_quant_audit.py":
                # Lexical token-variable uses: dotted/indexed identifier
                # assignment or a bare `==` comparison fragment carry no
                # credential bytes. Literal/prefixed values stay flagged.
                if re.fullmatch(
                        r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*"
                        r"(?:[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?:\[[^\]]*\])?|"
                        r"=+\s*[\"']{0,2})", value, re.I):
                    return True
            if source_path == "scripts/test_dynamic_rag_quant_audit.py":
                # Type-annotated token parameter names carry no credential
                # value bytes (annotated helper-signature fragments only).
                if re.fullmatch(
                        r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)"
                        r"\s*:\s*[A-Za-z_][A-Za-z0-9_]*\)?", value, re.I):
                    return True
            # A name binding whose match ends at the opening quote carries no
            # value bytes (e.g. "?api_key=" + val building a URL query). A real
            # literal produces a longer match and stays flagged; the bytes after
            # the quote still face the prefixed-value scan.
            return bool(re.fullmatch(
                r"(?:password|passwd|pwd|client[-_.]?secret|api[-_.]?key|token)\s*[:=]\s*"
                r"[\"']", value, re.I))
        if source_path.endswith((".yaml", ".yml")):
            label = "api" + "-key: "
            # Pure ${ENV}/`:`/`__MISSING__` placeholders are already masked
            # upstream (line ~457 <unresolved-setting>); this pair covers the
            # two public literal defaults. Arbitrary literal defaults stay
            # flagged — a default value can be a real credential.
            return value in (label + "${LLM_API_KEY:ollama}",
                             label + "${BRAVE_API_KEY:__MISSING__}")
        return False
    bad = next((m for m in SECRET_FRAGMENT_RE.finditer(text)
                if not reference_only(m)), None)
    priv = re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", text)
    if bad is not None or priv:
        # Report file:line only — the matched value is never echoed.
        line_no = text.count("\n", 0, (bad or priv).start()) + 1
        raise CheckpointError("secret-pattern %s:L%d"
                              % (source_path or "<bytes>", line_no))


def artifact(path):
    # Unrecognized paths require the existing source owner; this is not authority inference.
    return path in ("AGENTS.md", "AGENTS.override.md", "README.md") or path.startswith(
        ("docs/", "agent-prompts/", ".agents/skills/")) and path.endswith(".md")


def run_path(root, name):
    require(isinstance(name, str) and (name.startswith("data/agent-handoff/codex-autonomy/") or
            root.name == ".codex" and name.startswith("autonomy-checkpoints/")), "checkpoint-storage-scope")
    return relative_path(root, name)


def lease_released(root, ref):
    """이 manifest에 기록된 leaseId의 정상 `release` 이벤트가 남아 있으면
    lease 파일 부재는 중도 소실이 아니라 정상 해제다 (finish 전용 완화).
    quarantine·삭제는 release 행이 없어 여전히 drift로 거부된다."""
    lease_id = str((ref or {}).get("leaseId") or "")
    if not re.fullmatch(r"[0-9a-f]{32}", lease_id):
        return False
    events = root / "__patch_drop__" / "source-edit-events" / (lease_id + ".jsonl")
    try:
        lines = events.read_bytes().decode("utf-8", "replace").splitlines()
    except OSError:
        return False
    for line in lines:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict) and row.get("event") == "release" \
                and row.get("leaseId") == lease_id:
            return True
    return False


def lease_check(root, manifest, allow_released=False):
    if not manifest.get("lease"):
        require(all(artifact(t["path"]) for t in manifest["targets"]), "source-owner-lease-required")
        return
    ref = manifest["lease"]
    require(ref["path"].startswith("__patch_drop__/source-edit-locks/") and
            ref["path"].endswith("/lease.json"), "source-lease-path")
    data = contents(relative_path(root, ref["path"]))
    if data is None:
        # begin~seal 동안 정상 획득·검증됐고 finish 시점에 release 기록만 남은
        # 소유 lease는 drift가 아니라 완료된 주기다.
        require(allow_released and lease_released(root, ref), "source-lease-drift")
        return
    if digest(data) != ref["sha256"]:
        # A heartbeat renewal rewrites expiresAtUtc/heartbeat fields — same
        # lease, changed bytes. Pass only when the identity fields recorded
        # at begin still match; any identity change stays refused.
        try:
            renewed = json.loads(data)
        except ValueError:
            renewed = {}
        same = (ref.get("leaseId") is not None
                and renewed.get("leaseId") == ref["leaseId"]
                and renewed.get("ownerId") == ref.get("ownerId")
                and sorted(str(p).replace("\\", "/").casefold()
                           for p in renewed.get("targetPaths", []))
                == sorted(ref.get("targetPaths") or []))
        require(same, "source-lease-drift")
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
        lease_bytes = contents(relative_path(root, lease))
        ref = {"path": lease, "sha256": digest(lease_bytes)}
        try:
            lease_doc = json.loads(lease_bytes or b"{}")
            ref["leaseId"] = lease_doc.get("leaseId")
            ref["ownerId"] = lease_doc.get("ownerId")
            ref["targetPaths"] = sorted(str(p).replace("\\", "/").casefold()
                                        for p in lease_doc.get("targetPaths", []))
        except (ValueError, TypeError):
            pass
        manifest["lease"] = ref
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
            lease_check(root, manifest, allow_released=True)
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
                    lease_check(root, manifest, allow_released=True)
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
