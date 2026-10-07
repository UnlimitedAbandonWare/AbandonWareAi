"""Read-only assist for the Fold6 standalone audio reconnect brief.

Scanner: scripts/pair_brief_assist.py.
Extra commands: scope, hypothesis, product-gate, entry, route.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import pair_brief_assist as engine

SCHEMA = "awx.fold6-audio-reconnect-assist.v1"
DEFAULT_SPEC = "var/codex-assist-fold6-audio-reconnect-20261006/spec.json"
PACK = "var/codex-assist-fold6-audio-reconnect-20261006"
HYPO_REL = PACK + "/hypothesis.json"
RED_REL = PACK + "/red-boundary.json"
BOUNDARIES = (
    "phone-test-or-bootstrap",
    "audio-start",
    "chunk-batch",
    "audio-stop",
    "revisit-explicit-start",
    "dual-capture-refresh",
    "start-disabled",
)
HOT = (
    "main/resources/static/assets/display/app.js",
    "main/resources/static/assets/display/display-conversate.js",
    "main/resources/static/assets/display/display-voice.js",
    "src/test/js/display-stop-lifecycle.test.cjs",
    "src/test/js/display-capture-recovery.test.cjs",
    "scripts/meta_display_webapp_contract_tests.cjs",
)
HOT = tuple(path.casefold() for path in HOT)
SHA_FILES = {
    "app.js": "main/resources/static/assets/display/app.js",
    "display-conversate.js": "main/resources/static/assets/display/display-conversate.js",
    "display-voice.js": "main/resources/static/assets/display/display-voice.js",
}
REQUIRED_SHA = ("app.js", "display-conversate.js")
ENTRIES = ("data-fold6-test", "mode=phone-test")
ROUTES = ("phone-test", "bootstrap", "audio/start", "audio/chunk-batch", "audio/stop")
STAGES = ("precheck", "mic_open", "audio_graph", "server_begin", "unobserved")
LIFE = ("pagehide", "pageshow", "dispose", "pause", "explicit-start", "reconnect", "poll", "stop")
ENTRY_KEYS = {
    "schemaVersion", "status", "surface", "entry", "sourceSha12",
    "buildId", "videoBound", "substitute",
}
ROUTE_KEYS = {
    "schemaVersion", "status", "route", "httpStatus", "durationMs",
    "reasonCode", "voiceStage", "lifecycle", "sameBuild", "bodyCollected",
}
SECRET_NAMES = {
    "authorization", "cookie", "transcript", "grant", "token",
    "password", "audiobase64", "audiobody",
}
SUBSTITUTE_MARKERS = ("conversate/", "chat.js", "chat-ui.html", "/chat")
REASON = re.compile(r"^[a-z][a-z0-9_-]{0,63}$")
SHA12 = re.compile(r"^[a-f0-9]{12}$")
BUILD = re.compile(r"^[a-f0-9]{12,64}$")


def base(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
        "reclaim": False,
        "forceRelease": False,
    }


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def norm(path: str) -> str:
    return str(path).replace("\\", "/").lstrip("./").casefold()


def read_json(path: Path):
    try:
        if path.is_symlink():
            raise engine.AssistError("symlink-refused")
        return json.loads(path.read_text(encoding="utf-8"))
    except engine.AssistError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise engine.AssistError("json-unreadable") from exc


def secret_key(data: dict) -> bool:
    return any(str(key).casefold() in SECRET_NAMES for key in data)


def live_sha12(root: Path, rel: str):
    path = engine.under_root(root, rel)
    data = engine.read_text(path)
    if data is None:
        return None
    return engine.sha12_of(data)


def load_card(root: Path, rel: str):
    data = read_json(engine.under_root(root, rel))
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise engine.AssistError("spec-schema")
    return data


def cmd_scope(root: Path):
    lock_root = root / "__patch_drop__" / "source-edit-locks"
    overlaps = []
    if lock_root.is_dir() and not lock_root.is_symlink():
        for child in sorted(lock_root.iterdir()):
            if not child.is_dir() or child.name == "waiters" or child.is_symlink():
                continue
            lease_path = child / "lease.json"
            if not lease_path.is_file() or lease_path.is_symlink():
                continue
            data = read_json(lease_path)
            if not isinstance(data, dict):
                raise engine.AssistError("json-unreadable")
            raw_paths = data.get("targetPaths") or []
            if not isinstance(raw_paths, list):
                raise engine.AssistError("json-unreadable")
            paths = [norm(item) for item in raw_paths if isinstance(item, str)]
            hits = [item for item in paths if item in HOT]
            if hits:
                overlaps.append({
                    "topic": data.get("topic"),
                    "ownerId": data.get("ownerId"),
                    "expiresAtUtc": data.get("expiresAtUtc"),
                    "paths": hits,
                })
    status, code = ("OVERLAP", 7) if overlaps else ("CLEAR", 0)
    report = base("scope", status)
    report["overlaps"] = overlaps
    report["hotPaths"] = list(HOT)
    report["note"] = (
        "OVERLAP means wait unless this session is that ownerId. "
        "Do not force-release. This command does not reclaim."
    )
    return report, code


def cmd_hypothesis(root: Path, rel: str):
    data = load_card(root, rel)
    items = data.get("items")
    if not isinstance(items, list):
        raise engine.AssistError("spec-shape")
    found = {}
    for item in items:
        if not isinstance(item, dict):
            raise engine.AssistError("spec-shape")
        item_id = item.get("id")
        status = item.get("status")
        if item_id not in BOUNDARIES or status not in ("PENDING", "ACTIVE", "CLOSED"):
            raise engine.AssistError("spec-shape")
        if item_id in found:
            raise engine.AssistError("spec-shape")
        found[item_id] = status
    if set(found) != set(BOUNDARIES):
        raise engine.AssistError("spec-shape")
    active = [item_id for item_id, status in found.items() if status == "ACTIVE"]
    if len(active) > 1:
        status, code = "HYPOTHESIS_SPREAD", 3
    elif len(active) == 1:
        status, code = "ONE_ACTIVE", 0
    else:
        status, code = "NONE", 0
    report = base("hypothesis", status)
    report["active"] = active
    report["note"] = "At most one hypothesis may be ACTIVE. PENDING is not a failure."
    return report, code


def red_pin(root: Path):
    path = root / RED_REL
    if not path.is_file():
        return None
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        return None
    if data.get("status") != "RED_PINNED":
        return None
    boundary = data.get("boundary")
    allow = data.get("allowPaths")
    if boundary not in BOUNDARIES or not isinstance(allow, list) or not 1 <= len(allow) <= 4:
        return None
    cleaned = []
    for item in allow:
        if not isinstance(item, str):
            return None
        engine.under_root(root, item)
        folded = norm(item)
        if not folded.startswith("main/resources/static/assets/display/") or not folded.endswith(".js"):
            return None
        cleaned.append(folded)
    return {"boundary": boundary, "allowPaths": cleaned}


def classify(path: str):
    folded = norm(path)
    if any(marker in folded for marker in ("conversate/", "chat-ui.html")) or folded.endswith("/chat.js") or folded.endswith("chat.js"):
        if "assets/display/" in folded:
            return "product"
        return "substitute"
    if folded.startswith("main/java/"):
        return "out"
    if folded.startswith("main/resources/static/assets/display/") and folded.endswith(".js"):
        return "product"
    return None


def cmd_product_gate(root: Path, diff_text: str):
    kinds = []
    for _index, path, _text in engine.added_lines(diff_text):
        if not path:
            continue
        kind = classify(path)
        if kind and (kind, norm(path)) not in [(row[0], row[1]) for row in kinds]:
            kinds.append((kind, norm(path)))
    pinned = red_pin(root)
    product = [path for kind, path in kinds if kind == "product"]
    if any(kind == "substitute" for kind, _path in kinds):
        status, code = "SUBSTITUTE_SURFACE", 3
    elif any(kind == "out" for kind, _path in kinds):
        status, code = "OUT_OF_BRIEF", 3
    elif not product:
        status, code = "TEST_ONLY", 0
    elif pinned is None:
        status, code = "PRODUCT_BEFORE_RED", 3
    elif any(item not in pinned["allowPaths"] for item in product):
        status, code = "BOUNDARY_SPREAD", 3
    else:
        status, code = "PRODUCT_SCOPED", 0
    report = base("product-gate", status)
    report["productPaths"] = product
    report["red"] = {
        "pinned": pinned is not None,
        "boundary": None if pinned is None else pinned["boundary"],
    }
    report["note"] = (
        "TEST_ONLY is the state before a confirmed RED. "
        "RED_PINNED names one boundary and at most four assets/display js paths. "
        "EXAMPLE_NOT_RED does not unlock product edits. "
        "conversate/app.js, chat.js, and chat-ui.html stay substitute surfaces."
    )
    return report, code


def cmd_entry(root: Path, rel: str):
    data = load_card(root, rel)
    if secret_key(data):
        return base("entry", "SECRET_FIELD"), 3
    if set(data) != ENTRY_KEYS:
        raise engine.AssistError("spec-shape")
    status = data.get("status")
    surface = data.get("surface")
    entry = data.get("entry")
    sha = data.get("sourceSha12")
    build_id = data.get("buildId")
    video = data.get("videoBound")
    substitute = data.get("substitute")
    if status not in ("BOUND", "NOT_BOUND", "EXAMPLE_NOT_RED"):
        raise engine.AssistError("spec-shape")
    if surface != "assets/display" or entry not in ENTRIES:
        raise engine.AssistError("spec-shape")
    if type(video) is not bool or not isinstance(build_id, str):
        raise engine.AssistError("spec-shape")
    if not isinstance(substitute, list) or not all(isinstance(item, str) for item in substitute):
        raise engine.AssistError("spec-shape")
    if not isinstance(sha, dict):
        raise engine.AssistError("spec-shape")
    if not set(REQUIRED_SHA) <= set(sha) or not set(sha) <= set(SHA_FILES):
        raise engine.AssistError("spec-shape")
    if any(not isinstance(value, str) or not SHA12.fullmatch(value) for value in sha.values()):
        raise engine.AssistError("spec-shape")
    folded_sub = [norm(item) for item in substitute]
    if any(any(marker in item for marker in SUBSTITUTE_MARKERS) for item in folded_sub):
        report = base("entry", "SUBSTITUTE")
        report["videoBound"] = video
        return report, 3
    if status == "EXAMPLE_NOT_RED":
        report = base("entry", "EXAMPLE")
        report["note"] = "Example shape only. It does not bind the video to a build."
        return report, 0
    if status != "BOUND" or video is not True or not BUILD.fullmatch(build_id):
        report = base("entry", "NOT_BOUND")
        report["note"] = "Bind the standalone display entry, live sha12, and served build before a product edit."
        return report, 4
    stale = []
    for name, value in sha.items():
        current = live_sha12(root, SHA_FILES[name])
        if current != value:
            stale.append(name)
    if stale:
        report = base("entry", "ENTRY_STALE")
        report["stale"] = stale
        report["note"] = "Re-read the live file. Do not restore an older sha12."
        return report, 4
    report = base("entry", "BOUND")
    report["entry"] = entry
    report["buildId"] = build_id
    report["note"] = "BOUND ties the card to the live standalone display files. It is not a device PASS."
    return report, 0


def cmd_route(root: Path, rel: str):
    data = load_card(root, rel)
    if secret_key(data):
        return base("route", "SECRET_FIELD"), 3
    if set(data) != ROUTE_KEYS:
        raise engine.AssistError("spec-shape")
    status = data.get("status")
    route = data.get("route")
    http_status = data.get("httpStatus")
    duration = data.get("durationMs")
    reason = data.get("reasonCode")
    stage = data.get("voiceStage")
    life = data.get("lifecycle")
    same = data.get("sameBuild")
    body = data.get("bodyCollected")
    if status not in ("NARROWED", "OPEN", "EXAMPLE_NOT_RED"):
        raise engine.AssistError("spec-shape")
    if route not in ROUTES or stage not in STAGES:
        raise engine.AssistError("spec-shape")
    if type(http_status) is not int or not 0 <= http_status <= 599:
        raise engine.AssistError("spec-shape")
    if type(duration) is not int or not 0 <= duration <= 600000:
        raise engine.AssistError("spec-shape")
    if not isinstance(reason, str) or not REASON.fullmatch(reason):
        raise engine.AssistError("spec-shape")
    if not isinstance(life, list) or not 1 <= len(life) <= 8:
        raise engine.AssistError("spec-shape")
    if any(item not in LIFE for item in life):
        raise engine.AssistError("spec-shape")
    if type(same) is not bool or type(body) is not bool:
        raise engine.AssistError("spec-shape")
    if body:
        return base("route", "BODY_COLLECTED"), 3
    if status == "EXAMPLE_NOT_RED":
        report = base("route", "EXAMPLE")
        report["note"] = "Example shape only. It is not a failing-route finding."
        return report, 0
    if status != "NARROWED" or stage == "unobserved" or same is not True:
        report = base("route", "OPEN")
        report["route"] = route
        report["note"] = "One route is named, but the build link or voice stage is still open."
        return report, 4
    report = base("route", "NARROWED")
    report["route"] = route
    report["httpStatus"] = http_status
    report["reasonCode"] = reason
    report["voiceStage"] = stage
    report["note"] = "NARROWED is one fixture route. It is not a device PASS and not a product edit."
    return report, 0


def read_diff(path: Path) -> str:
    engine.reject_name(path.name)
    if path.is_symlink() or not path.is_file():
        raise engine.AssistError("diff-missing")
    if path.stat().st_size > engine.MAX_BYTES:
        raise engine.AssistError("file-too-large")
    return path.read_text(encoding="utf-8", errors="replace")


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    cmd = args[0]
    if cmd in ("pin", "cover", "diff-forbid"):
        engine.SCHEMA = SCHEMA
        if "--spec" not in args:
            args = [cmd, "--spec", DEFAULT_SPEC, *args[1:]]
        return engine.main(args)
    parser = argparse.ArgumentParser(description="Fold6 audio reconnect assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_root(command):
        command.add_argument("--root", default=".")
        return command

    add_root(sub.add_parser("scope"))
    hypo = add_root(sub.add_parser("hypothesis"))
    hypo.add_argument("--file", default=HYPO_REL)
    gate = add_root(sub.add_parser("product-gate"))
    gate.add_argument("--diff", required=True)
    entry = add_root(sub.add_parser("entry"))
    entry.add_argument("--card", required=True)
    route = add_root(sub.add_parser("route"))
    route.add_argument("--card", required=True)
    parsed = parser.parse_args(args)
    if parsed.cmd not in ("scope", "hypothesis", "product-gate", "entry", "route"):
        emit({"schemaVersion": SCHEMA, "status": "error", "reason": "usage", "productPass": False})
        return 2
    try:
        root = Path(parsed.root).resolve()
        if parsed.cmd == "scope":
            report, code = cmd_scope(root)
        elif parsed.cmd == "hypothesis":
            report, code = cmd_hypothesis(root, parsed.file)
        elif parsed.cmd == "entry":
            report, code = cmd_entry(root, parsed.card)
        elif parsed.cmd == "route":
            report, code = cmd_route(root, parsed.card)
        else:
            report, code = cmd_product_gate(root, read_diff(Path(parsed.diff)))
        emit(report)
        return code
    except engine.AssistError as exc:
        emit({
            "schemaVersion": SCHEMA,
            "status": "error",
            "reason": exc.reason,
            "productPass": False,
            "reclaim": False,
            "forceRelease": False,
        })
        return 2


if __name__ == "__main__":
    sys.exit(main())
