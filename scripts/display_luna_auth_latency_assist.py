"""Read-only assist for Display Luna auth and first useful sentence.

pin, cover, and diff-forbid delegate to pair_brief_assist.
gap reports the delta slice and the holds that must stay open.
A clean exit is a scan result, not a product PASS.
No network, Gradle, server, or product write.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as scan

SPEC = "var/codex-assist-display-luna-auth-latency-20261006/spec.json"
COMMANDS = ("pin", "cover", "diff-forbid", "gap")
POLICY = "main/java/com/example/lms/llm/OpenAiModelSelectionPolicy.java"
FACTORY = "main/java/com/example/lms/llm/DynamicChatModelFactory.java"
OAUTH = "main/java/com/example/lms/llm/ChatGptOAuthRegistration.java"
COMPAT = "main/java/com/example/lms/llm/OpenAiEndpointCompatibility.java"
MODEL = "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java"
ANSWER = "main/java/com/example/lms/assist/NovaFocusAnswerService.java"
SERVICE = "main/java/com/example/lms/assist/NovaFocusService.java"
STATE = "main/java/com/example/lms/assist/NovaFocusState.java"
FLOW = "main/resources/static/assets/display/display-focus-flow.js"

SLICE = (
    ("publishable-delta", (ANSWER, SERVICE, STATE, MODEL),
     ("appendPublishableDelta", "onPublishableDelta", "DisplayAnswerDelta")),
    ("useful-clock", (ANSWER, SERVICE, STATE),
     ("firstUsefulSentence",)),
    ("prefix-resume", (FLOW,),
     ("renderedPrefix", "answerDeltaSeq", "publishedPrefix")),
)
FORBID = (
    ("stream-label", FLOW, "provider streaming"),
    ("service-tier-payload", COMPAT, "service_tier"),
    ("service-tier-model", MODEL, "service_tier"),
    ("silent-alias-factory", FACTORY, "chatgpt-oauth:gpt-5.6-luna"),
    ("silent-alias-policy", POLICY, "chatgpt-oauth:gpt-5.6-luna"),
    ("gemini-into-luna", ANSWER, "geminiGroundingToLuna"),
)


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def read_rel(root: Path, rel: str):
    data = scan.read_text(scan.under_root(root, rel))
    if data is None:
        return None
    return data.decode("utf-8", errors="replace")


def _line(text, needle):
    if text is None:
        return None
    for index, line in enumerate(text.splitlines(), start=1):
        if needle in line:
            return index
    return None


def first_hit(root, paths, needles):
    for rel in paths:
        text = read_rel(root, rel)
        if text is None:
            continue
        for needle in needles:
            found = _line(text, needle)
            if found:
                return rel, found, needle
    return None, None, None


def cmd_gap(root: Path):
    signals = []
    for rule_id, paths, needles in SLICE:
        rel, found, needle = first_hit(root, paths, needles)
        signals.append({
            "id": rule_id,
            "path": rel,
            "status": "PRESENT" if found else "ABSENT",
            "line": found,
            "needle": needle,
        })
    for rule_id, rel, needle in FORBID:
        text = read_rel(root, rel)
        found = _line(text, needle)
        signals.append({
            "id": rule_id,
            "path": rel,
            "status": "FILE_MISSING" if text is None else ("HIT" if found else "CLEAR"),
            "line": found,
        })
    holds = []
    oauth = read_rel(root, OAUTH)
    oauth_line = _line(oauth, "https://api.openai.com/v1")
    holds.append({
        "id": "custom-api-transport",
        "path": OAUTH,
        "status": "FILE_MISSING" if oauth is None else ("HOLD_OPEN" if oauth_line else "HOLD_MOVED"),
        "line": oauth_line,
    })
    factory = read_rel(root, FACTORY)
    adapter = _line(factory, "CodexAppServer")
    holds.append({
        "id": "managed-adapter",
        "path": FACTORY,
        "status": "FILE_MISSING" if factory is None else ("HOLD_REVIEW" if adapter else "HOLD_OPEN"),
        "line": adapter,
    })
    bad = {"ABSENT", "HIT", "FILE_MISSING", "HOLD_MOVED"}
    open_gap = any(row["status"] in bad for row in signals) or any(
        row["status"] in bad for row in holds)
    report = scan.base_report("gap", "GAP_OPEN" if open_gap else "GAP_CLEAR")
    report["signals"] = signals
    report["holds"] = holds
    report["note"] = (
        "GAP_CLEAR means the three slice markers are present and the forbid rows are clear. "
        "HOLD_OPEN remains a hold. It is not auth proof, TTFT, or glasses proof."
    )
    return report, (4 if open_gap else 0)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or args[0] not in COMMANDS:
        emit({
            "schemaVersion": scan.SCHEMA,
            "status": "error",
            "reason": "usage",
            "productPass": False,
        })
        return 2
    if args[0] == "gap":
        root = Path(".")
        if "--root" in args:
            root = Path(args[args.index("--root") + 1])
        try:
            report, code = cmd_gap(root.resolve())
        except scan.AssistError as exc:
            emit({
                "schemaVersion": scan.SCHEMA,
                "status": "error",
                "reason": exc.reason,
                "productPass": False,
            })
            return 2
        emit(report)
        return code
    if "--spec" not in args:
        args.extend(["--spec", SPEC])
    return scan.main(args)


if __name__ == "__main__":
    sys.exit(main())
