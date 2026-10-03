#!/usr/bin/env python3
"""Lint a Devin PASTE brief for SWE-2 task fit. Stdlib only. Does not edit the brief."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCHEMA = "devin-brief-lint.v1"
UNBOUNDED = ("알아서 전부", "전체 개선", "다 고쳐", "전부 고쳐", "알아서 개선")
PATH_RE = re.compile(
    r"(?i)([\w./\\-]+\.(?:py|ps1|md|java|js|yml|yaml|json|bat)\b"
    r"|(?:scripts|main|frontend|\.windsurf|\.agents|\.grok)[/\\])"
)
STEP_RE = re.compile(r"(?m)^[ \t]*(?:#{1,3}[ \t]*)?(G|D|WP)(\d{1,2})\b")
COMMAND_RE = re.compile(
    r"^(Set-Location|python|powershell|git|gradle|\.\\gradlew|npm|pip)\b",
    re.I,
)
SUGGEST = {
    "anti_stop_top": "맨 위에 [ANTI-STOP] 한 줄을 넣고, 읽고 멈추지 말라고 적으세요.",
    "anti_stop_bottom": "맨 끝에도 [ANTI-STOP] 한 줄을 넣으세요.",
    "set_location_first": "첫 명령은 Set-Location C:\\AbandonWare\\demo-1\\demo-1\\src 로 두세요.",
    "one_line_goal": "\"한 줄 목표\" 칸에 이번 작업이 끝나면 뭐가 달라지는지 한 줄로 적으세요.",
    "allow_list": "수정 허용 칸에 고칠 파일 경로를 적으세요.",
    "deny_list": "변경 금지 또는 절대 금지 칸을 두세요.",
    "step_count": "G0, D0, WP0처럼 단계 번호를 10개 이하로 적으세요. 더 많으면 지시서를 나누세요.",
    "acceptance": "Acceptance 표를 넣고, 각 항목의 통과 조건을 적으세요.",
    "not_run": "안 돌린 항목은 NOT_RUN이라고 적고, NOT_RUN이 있으면 완료가 아니라고 적으세요.",
    "external_api_line": "보고 첫 줄을 `외부 API:` 로 시작하라고 적으세요.",
    "ledger": "ledger 경로를 data\\agent-handoff\\ 아래로 적으세요.",
    "unbounded_scope": "\"전체 개선\" 같은 말은 빼고, 고칠 파일 목록이 있는 좁은 목표로 다시 적으세요.",
    "forbid_push": "절대 금지에 push를 적으세요.",
    "forbid_commit": "절대 금지에 commit을 적으세요.",
    "forbid_full_test": "절대 금지에 전체 테스트 스위트를 적으세요.",
    "forbid_secrets": "절대 금지에 비밀값 출력·열람을 적으세요.",
    "forbid_chat_js": "절대 금지에 chat.js를 적으세요. 예외가 필요하면 파일과 줄 수를 한 줄로 한정하세요.",
}


def _section_from(text: str, labels: tuple[str, ...]) -> str:
    indexes = [text.find(label) for label in labels if text.find(label) != -1]
    if not indexes:
        return ""
    return text[min(indexes):]


def _has_path(text: str) -> bool:
    return PATH_RE.search(text) is not None


def step_count(text: str) -> int:
    series: dict[str, set[int]] = {"G": set(), "D": set(), "WP": set()}
    for match in STEP_RE.finditer(text):
        series[match.group(1)].add(int(match.group(2)))
    counts = [len(values) for values in series.values() if values]
    return max(counts) if counts else 0


def _check(check_id: str, ok: bool, detail: str) -> dict:
    return {"id": check_id, "ok": bool(ok), "detail": detail}


def lint_text(text: str) -> dict:
    """Return FIT plus missing items. text is the brief body."""
    lines = text.splitlines()
    anti_indexes = [index for index, line in enumerate(lines) if "[ANTI-STOP]" in line]
    top_ok = any(index < 12 for index in anti_indexes)
    bottom_ok = len(anti_indexes) >= 2 and any(index >= max(0, len(lines) - 12) for index in anti_indexes)
    goal_at = text.find("한 줄 목표")
    before_goal = text if goal_at < 0 else text[:goal_at]
    commands = []
    for line in before_goal.splitlines():
        stripped = line.strip()
        if COMMAND_RE.match(stripped):
            commands.append(stripped)
    set_ok = bool(commands) and commands[0].lower().startswith("set-location")
    set_detail = commands[0][:160] if commands else "목표 앞에 명령이 없음"

    allow = _section_from(text, ("수정 허용", "변경 허용"))
    deny = _section_from(text, ("절대 금지", "변경 금지", "금지 목록"))
    # Keep the allow-list probe on the allow region only, before the deny header.
    allow_body = allow
    for label in ("절대 금지", "변경 금지", "금지 목록"):
        cut = allow_body.find(label)
        if cut != -1:
            allow_body = allow_body[:cut]
    steps = step_count(text)
    step_ok = 1 <= steps <= 10
    if steps > 10:
        step_detail = "단계 %s개 (10개 초과)" % steps
    elif steps == 0:
        step_detail = "단계 번호 없음"
    else:
        step_detail = "단계 %s개" % steps

    phrase = next((item for item in UNBOUNDED if item in text), "")
    unbounded_ok = not phrase or _has_path(allow_body)
    unbounded_detail = phrase if phrase and not unbounded_ok else ""

    checks = [
        _check("anti_stop_top", top_ok, "앞 12줄" if top_ok else "앞에 없음"),
        _check("anti_stop_bottom", bottom_ok, "끝 12줄" if bottom_ok else "끝에 없음"),
        _check("set_location_first", set_ok, set_detail),
        _check("one_line_goal", "한 줄 목표" in text, "한 줄 목표" if "한 줄 목표" in text else "없음"),
        _check("allow_list", "수정 허용" in text or "변경 허용" in text, "허용 목록" if allow else "없음"),
        _check("deny_list", bool(deny), "금지 목록" if deny else "없음"),
        _check("step_count", step_ok, step_detail),
        _check("acceptance", bool(re.search(r"(?i)acceptance", text)), "Acceptance" if re.search(r"(?i)acceptance", text) else "없음"),
        _check("not_run", "NOT_RUN" in text, "NOT_RUN" if "NOT_RUN" in text else "없음"),
        _check("external_api_line", "외부 API:" in text, "외부 API:" if "외부 API:" in text else "없음"),
        _check("ledger", "ledger:" in text.lower() or "agent-handoff" in text, "ledger" if "agent-handoff" in text or "ledger:" in text.lower() else "없음"),
        _check("unbounded_scope", unbounded_ok, unbounded_detail or "범위 문장 없음"),
        _check("forbid_push", bool(re.search(r"(?i)\bpush\b", deny)), "push" if re.search(r"(?i)\bpush\b", deny) else "금지 목록에 없음"),
        _check("forbid_commit", bool(re.search(r"(?i)\bcommit\b", deny)), "commit" if re.search(r"(?i)\bcommit\b", deny) else "금지 목록에 없음"),
        _check("forbid_full_test", bool(re.search(r"전체 테스트|:test\b|full suite", deny, re.I)), "전체 테스트" if re.search(r"전체 테스트|:test\b|full suite", deny, re.I) else "금지 목록에 없음"),
        _check("forbid_secrets", bool(re.search(r"비밀|secret", deny, re.I)), "비밀" if re.search(r"비밀|secret", deny, re.I) else "금지 목록에 없음"),
        _check("forbid_chat_js", bool(re.search(r"(?i)chat\.js", deny)), "chat.js" if re.search(r"(?i)chat\.js", deny) else "금지 목록에 없음"),
    ]
    for item in checks:
        if item["id"] == "step_count":
            item["count"] = steps
    missing = [item["id"] for item in checks if not item["ok"]]
    suggestions = [SUGGEST[item] for item in missing]
    by_id = {item["id"]: item for item in checks}
    if not by_id["unbounded_scope"]["ok"]:
        fit = "HANDOFF"
    elif not by_id["one_line_goal"]["ok"] and not by_id["allow_list"]["ok"]:
        fit = "HANDOFF"
    elif not missing:
        fit = "GOOD"
    else:
        fit = "SPLIT"
    return {
        "schema": SCHEMA,
        "fit": fit,
        "stepCount": steps,
        "missing": missing,
        "suggestions": suggestions,
        "checks": checks,
    }


def render_console(result: dict, source: str) -> str:
    lines = [
        "FIT %s" % result["fit"],
        "file: %s" % source,
        "steps: %s" % result["stepCount"],
        "missing: %s" % (", ".join(result["missing"]) or "(none)"),
    ]
    for suggestion in result["suggestions"]:
        lines.append("suggest: %s" % suggestion)
    return "\n".join(lines)


def lint_path(path: Path) -> dict:
    text = path.read_text(encoding="utf-8", errors="replace")
    result = lint_text(text)
    result["source"] = str(path)
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Lint a Devin PASTE brief for SWE-2 fit")
    parser.add_argument("brief", help="Path to the PASTE text file")
    parser.add_argument("--json", dest="json_path", default="")
    parser.add_argument("--md", dest="md_path", default="")
    args = parser.parse_args(argv)
    path = Path(args.brief)
    if not path.is_file():
        print("missing file: %s" % path)
        return 1
    result = lint_path(path)
    print(render_console(result, str(path)))
    if args.json_path:
        out = Path(args.json_path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.md_path:
        out = Path(args.md_path)
        out.parent.mkdir(parents=True, exist_ok=True)
        body = ["# Brief lint", "", "- FIT: %s" % result["fit"], "- steps: %s" % result["stepCount"], ""]
        for suggestion in result["suggestions"]:
            body.append("- %s" % suggestion)
        if not result["suggestions"]:
            body.append("- (none)")
        out.write_text("\n".join(body) + "\n", encoding="utf-8")
    return {"GOOD": 0, "SPLIT": 2, "HANDOFF": 3}[result["fit"]]


if __name__ == "__main__":
    sys.exit(main())
