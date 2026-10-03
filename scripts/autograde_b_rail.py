"""Read-only AutoGrade B00 registration rail and P01-P23 live remap.

Prints whether RuleBreak is already registered and whether ZIP line hints
still match live files. It does not edit product source and it never
recommends a second RuleBreak registration.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

WEB_MVC = "main/java/com/example/lms/config/WebMvcConfig.java"
IMPORTS = "main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
INTERCEPTOR = "main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java"
LMS_APP = "main/java/com/example/lms/LmsApplication.java"

# ZIP snapshot from Downloads/SOURCE_MAP.md (2026-09-28). Live bytes win.
POINTS = (
    {"id": "P01", "path": WEB_MVC, "symbol": "addInterceptors", "zipLines": "39-72",
     "zipSha": "99730157e6d742dfc8ed738c0140187dab361c695fe83948177b83cb00f981c8"},
    {"id": "P02", "path": IMPORTS, "symbol": "AutoConfiguration.imports", "zipLines": "1-6",
     "zipSha": "5c647cb81692df339ef336f4acfc5dcc4e169d879278616ebaf9973fad22d241"},
    {"id": "P03", "path": "main/java/com/example/lms/api/ChatRequestSettingsMerger.java",
     "symbol": "merge", "zipLines": "84-127",
     "zipSha": "3974d4ba945c1ed533459baa7293fc60a78ce48e985c0ebdb5adf834a7ffa555"},
    {"id": "P04", "path": "main/java/com/example/lms/service/SettingsService.java",
     "symbol": "save / saveAllSettings / getAllSettings", "zipLines": "66-133",
     "zipSha": "cb2b3427361127c472983b1c5b5f8d38dbba2e32d93a443954853dcc5e52f74e"},
    {"id": "P05", "path": "main/java/com/example/lms/prompt/PromptContext.java",
     "symbol": "PromptContext", "zipLines": "98-125",
     "zipSha": "60bf635974bc818ba148bc06dbe50fce28e94b5429a923159206d70f0a17b069"},
    {"id": "P06", "path": "main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java",
     "symbol": "execute", "zipLines": "44-85",
     "zipSha": "aea768e073a0b8fd2eec4ebcecdee8daced3872da090dc0200eb709cebcfbca2"},
    {"id": "P07", "path": "main/java/com/example/lms/service/ChatWorkflow.java",
     "symbol": "composeForPrompt", "zipLines": "2902-2917",
     "zipSha": "7d7fd4d92f042329679ac68f3283c1425678964a858f5391f4e627f51da99775"},
    {"id": "P08", "path": "main/java/com/example/lms/service/ChatWorkflow.java",
     "symbol": "PromptContext.builder", "zipLines": "2954-2971",
     "zipSha": "7d7fd4d92f042329679ac68f3283c1425678964a858f5391f4e627f51da99775"},
    {"id": "P09", "path": "main/java/com/example/lms/service/ChatWorkflow.java",
     "symbol": "promoteForPromptDetailed", "zipLines": "3036-3056",
     "zipSha": "7d7fd4d92f042329679ac68f3283c1425678964a858f5391f4e627f51da99775"},
    {"id": "P10", "path": "main/java/com/example/lms/service/ChatWorkflow.java",
     "symbol": "ensembleCandidates", "zipLines": "3070-3102",
     "zipSha": "7d7fd4d92f042329679ac68f3283c1425678964a858f5391f4e627f51da99775"},
    {"id": "P11", "path": "main/java/com/example/lms/service/ChatWorkflow.java",
     "symbol": "promptBuilder.build", "zipLines": "3245-3254",
     "zipSha": "7d7fd4d92f042329679ac68f3283c1425678964a858f5391f4e627f51da99775"},
    {"id": "P12", "path": "main/java/com/example/lms/prompt/StandardPromptBuilder.java",
     "symbol": "render", "zipLines": "276-328",
     "zipSha": "472c37e3803a4af3ffa54c89711a0526d58fbff17d12984d29ad204c15c3703a"},
    {"id": "P13", "path": "main/java/com/example/lms/trace/PromptTraceAspect.java",
     "symbol": "enrichDbContextBeforePromptBuild", "zipLines": "34-45",
     "zipSha": "b3f5b17140747b63e1db7c27b5adb6d03e193ffedf11f7dd077368162924458f"},
    {"id": "P14", "path": "main/java/com/example/lms/service/ChatWorkflow.java",
     "symbol": "applyEvidenceReleasePolicy", "zipLines": "7630-7690",
     "zipSha": "7d7fd4d92f042329679ac68f3283c1425678964a858f5391f4e627f51da99775"},
    {"id": "P15", "path": "main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java",
     "symbol": "route", "zipLines": "277-301",
     "zipSha": "cf653582b3c89fd57e4db4c9e0ac374c8ff083afce8f60493b7f65133e3dffb1"},
    {"id": "P16", "path": "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
     "symbol": "canServe", "zipLines": "187-225",
     "zipSha": "94acf49354a82a00932a2b0f323a5716294956f7853ee909bc4fb5e25543ebcc"},
    {"id": "P17", "path": "main/java/com/example/lms/assist/FocusMemoryService.java",
     "symbol": "retrieve", "zipLines": "184-248",
     "zipSha": "785b3b9cbfef6e72f6fe69838d30ad2c354d334a5ef2c48633aed82b2e524221"},
    {"id": "P18", "path": "main/java/com/example/lms/service/chat/FinalizedMemoryPersistence.java",
     "symbol": "persist", "zipLines": "16-45",
     "zipSha": "abbc5a39d555b857192e9ff3676a583df024b7eb1bcceb72a54abce21a75650c"},
    {"id": "P19", "path": "main/java/com/example/lms/service/chat/ChatRunRegistry.java",
     "symbol": "tryBeginCommit", "zipLines": "674-739",
     "zipSha": "b43c7f9fd4ddfeca7f91abae2a4aad2f825e11909e1c596c9689ba63bfe40833"},
    {"id": "P20", "path": "main/resources/static/js/chat.js",
     "symbol": "recoverExactRunAfterTransportLoss", "zipLines": "1774-1823",
     "zipSha": "b773814c1de367944edab4c73937b1142e1719c990785a3918132d2077b99b43"},
    {"id": "P21", "path": "main/java/com/example/lms/infra/exec/ContextPropagation.java",
     "symbol": "wrap", "zipLines": "38-82",
     "zipSha": "5cf20792e28d40af3290f41ede8bcd6295859ac5c87965c58b1f8c899bed1278"},
    {"id": "P22", "path": "main/java/com/example/lms/assist/DisplayRelay.java",
     "symbol": "publish", "zipLines": "27-43",
     "zipSha": "743e90d890607f2ee71a1b7ec7ef889f42e2ead7fb0ae4b8bbfce8a9dd4e0650"},
    {"id": "P23", "path": "main/resources/static/js/chat-trace-ui.js",
     "symbol": "withDebugQuery", "zipLines": "19-34",
     "zipSha": "f09a374e9c8b94e91fb318987bb1918d3847b89efd54e03104a9fec8841d2076"},
)

BODY_DISCRIMINATORS = ("failReason", "status", "outcome", "skippedReason", "executionStatus")


def read_text(root: Path, rel: str) -> str | None:
    path = root / rel
    if not path.is_file():
        return None
    return path.read_bytes().decode("utf-8", errors="replace")


def sha256_file(root: Path, rel: str) -> str | None:
    path = root / rel
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_anchor(zip_lines: str) -> int | None:
    head = str(zip_lines or "").split("-", 1)[0].strip()
    return int(head) if head.isdigit() else None


def find_symbol_line(text: str | None, symbol: str, zip_start: int | None = None) -> int | None:
    if not text or not symbol:
        return None
    tokens = [part.strip() for part in symbol.split("/") if part.strip()]
    lines = text.splitlines()
    hits = []
    for token in tokens:
        hits = [index for index, line in enumerate(lines, 1) if token in line]
        if hits:
            break
    if not hits:
        return None
    if zip_start is None:
        return hits[0]
    return min(hits, key=lambda number: (abs(number - zip_start), number))


def classify_b00(web_mvc: str | None, interceptor: str | None, application: str | None,
                 imports: str | None) -> dict:
    provider = bool(web_mvc) and all(token in web_mvc for token in (
        "ObjectProvider<RuleBreakInterceptor>", "getIfAvailable()", "addInterceptor("))
    component = bool(interceptor) and "@Component" in interceptor and "class RuleBreakInterceptor" in interceptor
    conditional = bool(interceptor) and "@ConditionalOnClass" in interceptor
    scan = bool(application) and "scanBasePackages" in application and "com.example.lms" in application
    import_lines = []
    if imports:
        import_lines = [line.strip() for line in imports.splitlines()
                        if line.strip() and not line.strip().startswith("#")]
    imports_present = bool(import_lines)
    imports_rulebreak = any("RuleBreak" in line for line in import_lines)
    registered = provider and (component or imports_rulebreak) and (scan or imports_rulebreak)
    return {
        "ruleBreak": "present" if provider else "absent",
        "component": "present" if component else "absent",
        "conditionalOnClass": "present" if conditional else "absent",
        "componentScan": "present" if scan else "absent",
        "imports": "present" if imports_present else "absent",
        "importsRuleBreak": "present" if imports_rulebreak else "absent",
        "importEntryCount": len(import_lines),
        "action": "NO_CHANGE_VERIFIED" if registered else "NEED_CODEX_MIN_PATCH",
        "reregister": "forbidden",
        "globalHold": "forbidden",
    }


def verdict_line(b00: dict, zip_sha: str) -> str:
    return (
        f"B00 RuleBreak={b00['ruleBreak']}; imports={b00['imports']}; "
        f"importsRuleBreak={b00['importsRuleBreak']}; componentScan={b00['componentScan']}; "
        f"zipSha={zip_sha}; action={b00['action']}"
    )


def classify_search_observation(status, zero_results, fail_reason, skipped_reason, response_keys) -> str:
    keys = set(response_keys or ())
    if status == "SKIPPED" and skipped_reason == "EMPTY_QUERY":
        return "blank-query-skip"
    if status == "OK" and zero_results is True and not fail_reason:
        return "genuine-empty"
    if status == "OK" and zero_results is False:
        return "hits"
    if status == "FAIL_SOFT":
        if keys.intersection(BODY_DISCRIMINATORS):
            return "fail-soft-distinguished"
        return "fail-soft-body-conflated"
    return "unclassified"


def remap_point(root: Path, point: dict) -> dict:
    live_sha = sha256_file(root, point["path"])
    text = read_text(root, point["path"])
    return {
        "id": point["id"],
        "path": point["path"],
        "symbol": point["symbol"],
        "zipLines": point["zipLines"],
        "zipSha": point["zipSha"],
        "liveSha": live_sha,
        "shaMatch": live_sha == point["zipSha"] if live_sha else False,
        "liveSymbolLine": find_symbol_line(text, point["symbol"], zip_anchor(point["zipLines"])),
        "present": live_sha is not None,
    }


def build_report(root: Path) -> dict:
    web = read_text(root, WEB_MVC)
    interceptor = read_text(root, INTERCEPTOR)
    application = read_text(root, LMS_APP)
    imports = read_text(root, IMPORTS)
    b00 = classify_b00(web, interceptor, application, imports)
    rows = [remap_point(root, point) for point in POINTS]
    p01 = next(row for row in rows if row["id"] == "P01")
    p02 = next(row for row in rows if row["id"] == "P02")
    zip_sha = "match" if p01["shaMatch"] and p02["shaMatch"] else "STALE"
    return {
        "schemaVersion": "awx.autograde-b-rail.v1",
        "b00": b00,
        "zipSha": zip_sha,
        "verdictLine": verdict_line(b00, zip_sha),
        "remap": rows,
        "productWrite": False,
    }


def render_remap(rows: list) -> str:
    lines = ["| ID | live symbol line | zip lines | sha | path |", "|---|---|---|---|---|"]
    for row in rows:
        sha = "match" if row["shaMatch"] else ("ABSENT" if not row["present"] else "STALE")
        line = row["liveSymbolLine"] if row["liveSymbolLine"] is not None else "-"
        lines.append(f"| {row['id']} | {line} | {row['zipLines']} | {sha} | `{row['path']}` |")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Read-only B00 rail and P-point remap")
    parser.add_argument("--root", default=".")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    report = build_report(Path(args.root).resolve())
    if args.json:
        json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
        sys.stdout.write("\n")
    else:
        print(report["verdictLine"])
        print(render_remap(report["remap"]))
    return 0 if report["b00"]["action"] == "NO_CHANGE_VERIFIED" else 2


if __name__ == "__main__":
    sys.exit(main())
