"""Pure, redacted Java catch classification shared by Harmony consumers."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


HARMONY_BOUNDED_CATCH_CONTRACTS: dict[tuple[str, str], tuple[str, str]] = {
    (
        "main/java/com/abandonware/ai/agent/orchestrator/subagent/GlmActivationStateMachine.java",
        "safeBoolean",
    ): (r"RuntimeException", r"return\s+false\s*;"),
    (
        "main/java/com/abandonware/ai/agent/orchestrator/subagent/GlmAgentCore.java",
        "executeInternal",
    ): (r"RuntimeException", r""),
    (
        "main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChain.java",
        "attemptAllowed",
    ): (r"RuntimeException", r"return\s+false\s*;"),
    (
        "main/java/com/abandonware/ai/agent/orchestrator/subagent/SubagentProviderChain.java",
        "record",
    ): (r"RuntimeException", r""),
    (
        "main/java/com/abandonware/ai/agent/tool/impl/ops/CounterEvidenceRetrieveTool.java",
        "normalizedScore",
    ): (r"RuntimeException", r"return\s+0\.0d\s*;"),
    (
        "main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java",
        "normalize",
    ): (
        r"IllegalArgumentException(?:\s*\|\s*DateTimeException)?",
        r"return\s+null\s*;",
    ),
    (
        "main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java",
        "isValidDurableField",
    ): (r"NumberFormatException", r"return\s+false\s*;"),
    (
        "main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java",
        "boundedCount",
    ): (r"RuntimeException", r"count\s*=\s*0L\s*;"),
    (
        "main/java/com/example/lms/config/LocalLlmProcessManager.java",
        "terminate",
    ): (r"RuntimeException", r"return\s+new\s+TerminationResult\s*\([^;]*\)\s*;"),
    (
        "main/java/com/example/lms/config/LocalLlmProcessManager.java",
        "rollbackTransferredProcess",
    ): (r"RuntimeException", r""),
    (
        "main/java/com/example/lms/config/LocalLlmProcessManager.java",
        "shellBacked",
    ): (r"RuntimeException", r"executable\s*=\s*first\s*;"),
    (
        "main/java/com/example/lms/config/LocalLlmProcessManager.java",
        "findListener",
    ): (r"Exception", r"return\s+ListenerInfo\.none\s*\(\s*\)\s*;"),
    (
        "main/java/com/example/lms/config/LocalLlmProcessManager.java",
        "portFromUrl",
    ): (r"Exception", r"return\s+11435\s*;"),
    (
        "main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java",
        "hasCredential",
    ): (r"RuntimeException", r"return\s+false\s*;"),
    (
        "main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java",
        "strictExternalEndpointShape",
    ): (r"RuntimeException", r"return\s+false\s*;"),
    (
        "main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java",
        "providerPathMatches",
    ): (r"RuntimeException", r"return\s+false\s*;"),
    (
        "main/java/com/example/lms/ensemble/EnsembleJudgeService.java",
        "parseDebugPatchVote",
    ): (r"IllegalArgumentException", r"return\s+null\s*;"),
    (
        "main/java/com/example/lms/ensemble/EvidenceGroundedTriadicDebugAdjudicator.java",
        "nonNegativeLong",
    ): (r"NumberFormatException", r"return\s+0L\s*;"),
    (
        "main/java/com/example/lms/guard/ConversationFrameV1.java",
        "parse",
    ): (r"IllegalArgumentException", r"return\s+OFF\s*;"),
    (
        "main/java/com/example/lms/guard/ProviderCredentialResolver.java",
        "parseNonNegativeInt",
    ): (r"NumberFormatException", r"return\s+0\s*;"),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "logAcceptedRequestAttempt",
    ): (r"RuntimeException", r""),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "logDroppedRequestAttemptState",
    ): (r"RuntimeException", r""),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "logProviderReceipt",
    ): (r"RuntimeException", r""),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "endpointIdentityHash",
    ): (r"IllegalArgumentException", r"return\s*;"),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "requestTimelineEndpoint",
    ): (r"IllegalArgumentException", r""),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "requestAttemptMessageFingerprint",
    ): (
        r"RuntimeException",
        r"return\s+new\s+RequestAttemptFingerprint\s*\([^;]*\)\s*;",
    ),
    (
        "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
        "requestAttemptFingerprint",
    ): (
        r"Exception",
        r"return\s+new\s+RequestAttemptFingerprint\s*\([^;]*\)\s*;",
    ),
}

_LOCAL_BREADCRUMB_RE = re.compile(
    r"TraceStore|DebugEventStore|log\.|logger\.|LOG\.|System\.err|console\.debug|throw\s+|checkpoint|AWX|"
    r"Thread\.currentThread\s*\(\s*\)\.interrupt\s*\(|outcomes\.put\s*\(|diagnostics\.add\s*\(|"
    r"logSuppressed|traceSuppressed|traceTelemetrySkipped|traceWriteHold|recordDebugEventEmitFailure|"
    r"\btraceInteractionContainment\s*\(|"
    r"recordDebugEventResolveFailure|"
    r"traceContextPropagationSkipped|traceCancelShieldSkipped|recordError|traceSkipped|"
    r"traceParseSkipped|"
    r"recordProviderError|"
    r"recordStreamFailure|"
    r"logFailSoft|"
    r"AgentPipelineHealthTrace\.trace[A-Za-z0-9_]*|"
    r"traceWebSoakMaxRecentParseFallback|traceAspectError|lastEx\s*=|"
    r"recordRunFailure|recordRunOnceFailure|recordTerminal|"
    r"recordNoiseFilterFallback|traceInterruptedPoll|traceMetaIntParseFallback|"
    r"traceSearchPolicyFailure|traceCancelFailure|traceFailure|"
    r"recordFailure|tracePreflightSkipped|tracePolicyBlock|traceHybridExecutor|"
    r"WebFailSoftFailureTrace\.record|"
    r"trace[A-Za-z0-9_]*(?:Suppressed|Failure|Skipped|Fallback|Rejected|RiskNumber|MalformedRow)\s*\(|"
    r"INVALID_NUMBER_SUPPRESSOR\.accept|"
    r"WebFailSoftTraceSuppressions\.trace|"
    r"HybridTraceSuppressions\.trace|"
    r"DegradedStorageTraceSuppressions\.trace|"
    r"faultMaskingLayerMonitor|monitor\.record|\.addSuppressed\s*\(|terminalReason\s*=|"
    r"\.blockedContract\s*\(|errorCount\s*\+\+|\.completeExceptionally\s*\(|"
    r"\brethrow\s*\(|\bthrowUnchecked\s*\(|"
    r"ResponseEntity\.status\s*\(|\.rejectCapacity\s*\(|(?:Files|mover)\.move\s*\(|"
    r"\bskipPreparedTriad\s*\(|\bexecutorFailSoft\s*\(|"
    r"\brejectBodyExecutorSaturated\s*\(|\brejectDeadline\s*\(|\brejectHeader\s*\(|"
    r"\breject\s*\(|\.onError\s*\(|\bcompleted\.put\s*\(|\breasonCode\s*=|"
    r"\bopenCircuit\s*\(|\bmissing\.add\s*\(|\bResult\.notCancelled\s*\(|"
    r"\blease\.close\s*\(|\brecordNdjsonDrop\s*\(|"
    r"(?:ParsedIntegral|BoundedJson)\.invalid\s*\("
)

_JAVA_NON_CODE_RE = re.compile(
    r'/\*.*?\*/|//[^\n\r]*|""".*?"""|"(?:\\.|[^"\\\r\n])*"|\'(?:\\.|[^\'\\\r\n])+\'',
    re.DOTALL,
)


@dataclass(frozen=True)
class CatchEvidence:
    path: str
    line: int
    caught_type_class: str
    reason_code: str


def strip_java_comments_and_strings_preserve_lines(text: str) -> str:
    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    return _JAVA_NON_CODE_RE.sub(blank, text or "")


def iter_java_catch_blocks(scan_text: str) -> Iterable[tuple[int, str, str]]:
    for match in re.finditer(r"\bcatch\s*\(([^)]*)\)\s*\{", scan_text or ""):
        body_open = match.end() - 1
        body_close = java_matching_brace_end(scan_text, body_open)
        if body_close >= 0:
            yield match.start(), match.group(1), scan_text[match.end():body_close]


def classify_java_catches(*, path: Path, root: Path, text: str) -> tuple[CatchEvidence, ...]:
    relative_path = _strict_relative_path(path, root)
    scan_text = strip_java_comments_and_strings_preserve_lines(text)
    rows: list[CatchEvidence] = []
    for catch_start, catch_header, body in iter_java_catch_blocks(scan_text):
        if _LOCAL_BREADCRUMB_RE.search(body):
            reason = "LOCAL_BREADCRUMB"
        elif is_expected_empty_poll_timeout_catch(scan_text, catch_start, body):
            reason = "EMPTY_POLL_TIMEOUT"
        elif is_expected_terminal_failure_close_catch(scan_text, catch_start, body):
            reason = "TERMINAL_FAILURE_CLOSE"
        elif is_known_bounded_catch_contract(path, root, scan_text, catch_start, body):
            reason = "NAMED_BOUNDED_CONTRACT"
        elif has_deferred_exception_rethrow(scan_text, catch_start, body):
            reason = "DEFERRED_RETHROW"
        else:
            reason = "NO_LOCAL_BREADCRUMB"
        rows.append(CatchEvidence(
            path=relative_path,
            line=text.count("\n", 0, catch_start) + 1,
            caught_type_class=_caught_type_class(catch_header),
            reason_code=reason,
        ))
    return tuple(rows)


def summarize_catches(rows: Sequence[CatchEvidence]) -> dict[str, int]:
    broad = {"EXCEPTION", "RUNTIME_EXCEPTION", "THROWABLE"}
    return {
        "catchBlockCount": len(rows),
        "catchWithoutBreadcrumbCount": sum(
            row.reason_code == "NO_LOCAL_BREADCRUMB" for row in rows
        ),
        "broadCatchCount": sum(row.caught_type_class in broad for row in rows),
        "broadCatchWithoutLocalBreadcrumbCount": sum(
            row.caught_type_class in broad
            and row.reason_code == "NO_LOCAL_BREADCRUMB"
            for row in rows
        ),
    }


def is_expected_empty_poll_timeout_catch(scan_text: str, catch_start: int, body: str) -> bool:
    if body.strip():
        return False
    catch_header = re.match(r"catch\s*\(([^)]*)\)\s*\{", scan_text[catch_start:])
    if not catch_header:
        return False
    return re.fullmatch(
        r"\s*(?:java\.util\.concurrent\.)?TimeoutException\s+pollTimeout\s*",
        catch_header.group(1),
    ) is not None


def is_expected_terminal_failure_close_catch(scan_text: str, catch_start: int, body: str) -> bool:
    catch_header = re.match(r"catch\s*\(([^)]*)\)\s*\{", scan_text[catch_start:])
    if not catch_header:
        return False
    if re.fullmatch(
            r"\s*(?:java\.lang\.)?RuntimeException\s+terminalFailure\s*",
            catch_header.group(1),
    ) is None:
        return False
    return re.fullmatch(r"\s*close\s*\(\s*\)\s*;\s*", body) is not None


def is_known_bounded_catch_contract(
        path: Path,
        root: Path,
        scan_text: str,
        catch_start: int,
        body: str) -> bool:
    relative_path = _strict_relative_path(path, root)
    candidates = [
        (method_name, contract)
        for (contract_path, method_name), contract in HARMONY_BOUNDED_CATCH_CONTRACTS.items()
        if contract_path == relative_path
    ]
    if not candidates:
        return False
    catch_header = re.match(r"catch\s*\(([^)]*)\)\s*\{", scan_text[catch_start:])
    if not catch_header:
        return False
    for method_name, (exception_pattern, body_pattern) in candidates:
        if not java_catch_is_inside_named_method(scan_text, catch_start, method_name):
            continue
        if re.fullmatch(
                rf"\s*(?:{exception_pattern})\s+[A-Za-z_$][A-Za-z0-9_$]*\s*",
                catch_header.group(1),
        ) is None:
            continue
        return re.fullmatch(rf"\s*(?:{body_pattern})\s*", body) is not None
    return False


def java_catch_is_inside_named_method(scan_text: str, catch_start: int, method_name: str) -> bool:
    method_pattern = re.compile(
        rf"\b{re.escape(method_name)}\s*\([^;{{}}]*\)\s*"
        rf"(?:throws\s+[^{{}}]+)?\{{"
    )
    for match in method_pattern.finditer(scan_text, 0, catch_start):
        body_open = match.end() - 1
        body_close = java_matching_brace_end(scan_text, body_open)
        if body_open < catch_start < body_close:
            return True
    return False


def java_matching_brace_end(scan_text: str, body_open: int) -> int:
    if body_open < 0 or body_open >= len(scan_text) or scan_text[body_open] != "{":
        return -1
    depth = 1
    for index in range(body_open + 1, len(scan_text)):
        if scan_text[index] == "{":
            depth += 1
        elif scan_text[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    return -1


def has_deferred_exception_rethrow(scan_text: str, catch_start: int, body: str) -> bool:
    catch_header = re.match(r"catch\s*\(([^)]*)\)\s*\{", scan_text[catch_start:])
    if not catch_header:
        return False
    parameter = re.search(r"([A-Za-z_$][A-Za-z0-9_$]*)\s*$", catch_header.group(1))
    if not parameter:
        return False
    caught_name = parameter.group(1)
    assignments = re.finditer(
        rf"\b([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*{re.escape(caught_name)}\s*;",
        body,
    )
    tail_start = catch_start + catch_header.end() + len(body)
    for assignment in assignments:
        target_name = assignment.group(1)
        scope_end = java_local_scope_end(scan_text, target_name, catch_start)
        if scope_end <= tail_start:
            continue
        if re.search(
                rf"\bthrow\s+{re.escape(target_name)}\s*;",
                scan_text[tail_start:scope_end]):
            return True
    return False


def java_local_scope_end(scan_text: str, target_name: str, before: int) -> int:
    declaration_pattern = re.compile(
        rf"\b(?:final\s+)?[A-Za-z_$][A-Za-z0-9_$.<>,?\[\]]*\s+"
        rf"{re.escape(target_name)}\s*(?:=|;)"
    )
    declarations = list(declaration_pattern.finditer(scan_text, 0, before))
    if not declarations:
        return before
    scope_start = scan_text.rfind("{", 0, declarations[-1].start())
    if scope_start < 0:
        return before
    scope_header = scan_text[max(0, scope_start - 240):scope_start]
    if re.search(r"\b(?:class|interface|enum|record)\b[^{};]*$", scope_header):
        return before
    depth = 1
    for index in range(scope_start + 1, len(scan_text)):
        if scan_text[index] == "{":
            depth += 1
        elif scan_text[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    return before


def _strict_relative_path(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError as error:
        raise ValueError("catch evidence path is outside repository root") from error


def _caught_type_class(catch_header: str) -> str:
    parameter = re.fullmatch(
        r"\s*(?:final\s+)?(.+?)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*",
        catch_header,
    )
    if not parameter:
        return "OTHER"
    classes = {
        "Exception": "EXCEPTION",
        "RuntimeException": "RUNTIME_EXCEPTION",
        "Throwable": "THROWABLE",
    }
    for member in parameter.group(1).split("|"):
        simple_name = member.strip().rsplit(".", 1)[-1]
        if simple_name in classes:
            return classes[simple_name]
    return "OTHER"
