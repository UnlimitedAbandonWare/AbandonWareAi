#!/usr/bin/env python3
"""Read-only probe for the ma21in F01-F07 repair.

Prints one JSON document. Does not edit source, leases, indexes, or config.
Exit 0: report written. Exit 1: --fail-on-open and a finding is still open.
Exit 2: a required source file is missing.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

CHAT_MUST_STAY_CHAT = (
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4o",
    "gpt-4o-mini",
    "o3",
    "o3-mini",
    "o4-mini",
    "gpt-5.5",
    "gpt-5.6-luna",
    "fixture-unknown",
)
YAML_MUST_GUARD = (
    "gpt-5-pro",
    "gpt-5.5-pro",
    "gpt-5.1-codex",
    "gpt-5-codex",
    "o3-deep-research",
    "o4-mini-deep-research",
)
JAVA_MUST_GUARD = (
    "gpt-5-pro",
    "gpt-5.1-codex",
    "gpt-5-codex",
    "o3-deep-research",
    "o4-mini-deep-research",
)
HOST_EXPECTATIONS = (
    ("HTTPS://API.OPENAI.COM/v1", True),
    ("https://api.openai.com.evil.invalid/v1", False),
    ("https://evil.invalid/api.openai.com/v1", False),
    ("https://api.openai.com@evil.invalid/v1", False),
    ("http://api.openai.com/v1", False),
    ("https://evilopenai.com/v1", False),
    ("http://localhost:11434/v1", False),
)
URL_CASES = (
    {
        "name": "google-openai-compat",
        "input": "https://generativelanguage.googleapis.com/v1beta/openai/",
        "want": "https://generativelanguage.googleapis.com/v1beta/openai",
    },
    {
        "name": "tenant-prefix",
        "input": "https://gateway.example.invalid/v1/tenant-a",
        "want": "https://gateway.example.invalid/v1/tenant-a",
    },
    {
        "name": "custom-prefix",
        "input": "https://gateway.example.invalid/custom-api/",
        "want": "https://gateway.example.invalid/custom-api",
    },
    {
        "name": "openai-origin-v1",
        "input": "https://api.openai.com/v1",
        "want": "https://api.openai.com/v1",
    },
    {
        "name": "loopback-origin-v1",
        "input": "http://127.0.0.1:11435/v1",
        "want": "http://127.0.0.1:11435/v1",
    },
    {
        "name": "loopback-origin-needs-v1",
        "input": "http://127.0.0.1:11434",
        "want": "http://127.0.0.1:11434/v1",
    },
)
REPAIR_PATHS = (
    "main/resources/application-llm.yaml",
    "main/java/ai/abandonware/nova/config/NovaModelGuardProperties.java",
    "main/java/ai/abandonware/nova/orch/llm/ModelGuardSupport.java",
    "main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
    "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java",
    "main/java/com/example/lms/llm/OpenAiEndpointCompatibility.java",
    "main/java/com/example/lms/llm/OpenAiCompatBaseUrl.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
    "main/java/com/example/lms/config/LangChainConfig.java",
    "main/java/com/example/lms/service/embedding/HfInferenceEmbeddingModel.java",
    "main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java",
    "main/java/com/example/lms/vector/FederatedEmbeddingStore.java",
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/resources/static/js/chat.js",
    "src/test/java/ai/abandonware/nova/orch/aop/ModelGuardYamlCompatibilityTest.java",
    "src/test/java/ai/abandonware/nova/orch/llm/ModelGuardEndpointContractTest.java",
    "src/test/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModelTest.java",
)


def read_text(root: Path, rel: str) -> str | None:
    path = root / rel
    if not path.is_file():
        return None
    return path.read_text(encoding="utf-8")


def yaml_prefixes(text: str) -> list[str] | None:
    start = text.find("    model-guard:")
    end = text.find("    web-failsoft:", start if start >= 0 else 0)
    if start < 0 or end <= start:
        return None
    found = []
    for line in text[start:end].splitlines():
        stripped = line.strip()
        if stripped.startswith("- "):
            found.append(stripped[2:].strip())
    return found


def java_default_prefixes(text: str) -> list[str] | None:
    marker = "responsesOnlyPrefixes = new ArrayList<>(List.of("
    start = text.find(marker)
    if start < 0:
        return None
    end = text.find("));", start)
    if end < 0:
        return None
    body = text[start + len(marker):end]
    return [part.strip().strip('"') for part in body.split(",") if part.strip().strip('"')]


def responses_only(model: str, prefixes: list[str], prefix_hyphen: bool) -> bool:
    canon = model.strip().lower()
    for prefix in prefixes:
        item = prefix.strip().lower()
        if not item:
            continue
        if canon == item or (prefix_hyphen and canon.startswith(item + "-")):
            return True
    return False


def classify(models: tuple[str, ...], prefixes: list[str], prefix_hyphen: bool) -> dict[str, bool]:
    return {model: responses_only(model, prefixes, prefix_hyphen) for model in models}


def mismatches(actual: dict[str, bool], want_true: bool) -> list[str]:
    return sorted(model for model, flagged in actual.items() if flagged is not want_true)


def current_sanitize(base_url: str) -> str:
    value = base_url.strip()
    while value.endswith("/"):
        value = value[:-1]
    lower = value.lower()
    index = lower.find("/v1")
    if index >= 0:
        end = index + 3
        if len(lower) == end or lower[end] in "/?#":
            return value[:end]
    return value + "/v1"


def substring_host(url: str) -> bool:
    lowered = url.lower()
    return "api.openai.com" in lowered or ("openai.com" in lowered and "/v1" in lowered)


def constructor_args(text: str, start: int) -> str | None:
    marker = "new OpenAiResponsesChatModel"
    index = text.find(marker, start)
    if index < 0:
        return None
    paren = text.find("(", index)
    if paren < 0:
        return None
    depth = 0
    for offset, char in enumerate(text[paren:], paren):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[paren + 1:offset]
    return None


def call_passes_cap(args: str | None) -> bool:
    if args is None:
        return False
    lowered = args.replace(" ", "")
    return any(token in lowered for token in (
        "maxOutputTokens",
        "extractMaxOutputTokens",
        "ca.maxTokens",
        ".maxTokens",
    ))


def load_leases(root: Path, now: datetime) -> list[dict]:
    locks = root / "__patch_drop__" / "source-edit-locks"
    rows = []
    if not locks.is_dir():
        return rows
    for lease_file in sorted(locks.glob("*.lock/lease.json")):
        try:
            data = json.loads(lease_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        raw_expiry = str(data.get("expiresAtUtc") or "")
        try:
            expiry = datetime.fromisoformat(raw_expiry.replace("Z", "+00:00"))
            expired = expiry <= now
        except ValueError:
            expired = False
        paths = [str(path).replace("\\", "/") for path in data.get("targetPaths") or []]
        rows.append({
            "topic": data.get("topic"),
            "status": "expired" if expired else "active",
            "stillBlocksOverlap": True,
            "expiresAtUtc": raw_expiry,
            "ownerProcessId": data.get("ownerProcessId"),
            "ownerState": "owner-evidence-needed" if not data.get("ownerProcessId") else "present",
            "targetPaths": paths,
        })
    return rows


def blocks_for(rel: str, leases: list[dict]) -> list[dict]:
    wanted = rel.casefold()
    hits = []
    for lease in leases:
        matched = [path for path in lease["targetPaths"] if path.casefold() == wanted]
        if matched:
            hits.append({
                "topic": lease["topic"],
                "status": lease["status"],
                "expiresAtUtc": lease["expiresAtUtc"],
                "ownerState": lease["ownerState"],
                "paths": matched,
            })
    return hits


def finding(fid: str, status: str, evidence: list, leases: list[dict], paths: list[str]) -> dict:
    return {
        "id": fid,
        "status": status,
        "gradleProof": "not-run",
        "evidence": evidence,
        "paths": paths,
        "leaseBlocks": [block for path in paths for block in blocks_for(path, leases)],
    }


def build_report(root: Path) -> tuple[dict, int]:
    missing = [rel for rel in (
        "main/resources/application-llm.yaml",
        "main/java/ai/abandonware/nova/orch/llm/ModelGuardSupport.java",
        "main/java/ai/abandonware/nova/config/NovaModelGuardProperties.java",
        "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java",
        "main/java/com/example/lms/llm/OpenAiCompatBaseUrl.java",
        "build.gradle.kts",
    ) if not (root / rel).is_file()]
    if missing:
        return {"schemaVersion": "awx.ma21in-f01-f07-probe.v1", "missing": missing}, 2

    now = datetime.now(timezone.utc)
    leases = load_leases(root, now)
    yaml = read_text(root, "main/resources/application-llm.yaml") or ""
    guard = read_text(root, "main/java/ai/abandonware/nova/orch/llm/ModelGuardSupport.java") or ""
    props = read_text(root, "main/java/ai/abandonware/nova/config/NovaModelGuardProperties.java") or ""
    aspect = read_text(root, "main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java") or ""
    router = read_text(root, "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java") or ""
    responses = read_text(root, "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java") or ""
    base_url = read_text(root, "main/java/com/example/lms/llm/OpenAiCompatBaseUrl.java") or ""
    langchain = read_text(root, "main/java/com/example/lms/config/LangChainConfig.java") or ""
    hf = read_text(root, "main/java/com/example/lms/service/embedding/HfInferenceEmbeddingModel.java") or ""
    upstash = read_text(root, "main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java") or ""
    workflow = read_text(root, "main/java/com/example/lms/service/ChatWorkflow.java") or ""
    ollama = read_text(root, "main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java") or ""
    chat_js = read_text(root, "main/resources/static/js/chat.js") or ""
    gradle = read_text(root, "build.gradle.kts") or ""

    configured = yaml_prefixes(yaml) or []
    defaults = java_default_prefixes(props) or []
    prefix_hyphen = 'startsWith(pp + "-")' in guard
    substring = 'contains("api.openai.com")' in guard
    yaml_chat = classify(CHAT_MUST_STAY_CHAT, configured, prefix_hyphen)
    java_chat = classify(CHAT_MUST_STAY_CHAT, defaults, prefix_hyphen)
    yaml_guard = classify(YAML_MUST_GUARD, configured, prefix_hyphen)
    java_guard = classify(JAVA_MUST_GUARD, defaults, prefix_hyphen)
    host_mismatches = []
    if substring:
        host_mismatches = [url for url, want in HOST_EXPECTATIONS if substring_host(url) is not want]
    f01_open = bool(
        mismatches(yaml_chat, False)
        or mismatches(java_chat, False)
        or mismatches(yaml_guard, True)
        or mismatches(java_guard, True)
        or prefix_hyphen
        or host_mismatches
    )

    old_sanitize = 'indexOf("/v1")' in base_url and 'return s + "/v1"' in base_url
    url_rows = []
    if old_sanitize:
        for case in URL_CASES:
            mirrored = current_sanitize(case["input"])
            url_rows.append({
                "name": case["name"],
                "input": case["input"],
                "mirroredCurrent": mirrored,
                "want": case["want"],
                "matchesWant": mirrored == case["want"],
                "idempotent": current_sanitize(mirrored) == mirrored,
            })
    f02_open = old_sanitize and any(not row["matchesWant"] for row in url_rows)

    f03_open = "OpenAiEndpointCompatibility.toCompletionsPrompt(messages)" in responses
    legacy_kept = "public static String toCompletionsPrompt(" in (
        read_text(root, "main/java/com/example/lms/llm/OpenAiEndpointCompatibility.java") or ""
    )

    route_start = aspect.find("case ROUTE_RESPONSES")
    router_start = router.find("case ROUTE_RESPONSES")
    aspect_args = constructor_args(aspect, route_start if route_start >= 0 else 0)
    router_args = constructor_args(router, router_start if router_start >= 0 else 0)
    workflow_start = workflow.find("callResponsesFallback")
    workflow_args = constructor_args(workflow, workflow_start if workflow_start >= 0 else 0)
    aspect_cap = call_passes_cap(aspect_args)
    router_cap = call_passes_cap(router_args)
    workflow_cap = call_passes_cap(workflow_args)
    f04_open = not aspect_cap or not router_cap

    f05_open = "incomplete_details" not in responses
    f06_open = 'case "hf"' not in langchain and "default:" in langchain and "ollamaEmbeddingModel" in langchain
    match_at = upstash.find("new EmbeddingMatch<>")
    match_stmt = upstash[match_at:upstash.find(";", match_at)] if match_at >= 0 else ""
    f07_open = "queryEmbedding" in match_stmt

    tests = {
        "ModelGuardEndpointContractTest": (root / "src/test/java/ai/abandonware/nova/orch/llm/ModelGuardEndpointContractTest.java").is_file(),
        "ModelGuardYamlCompatibilityTest": (root / "src/test/java/ai/abandonware/nova/orch/aop/ModelGuardYamlCompatibilityTest.java").is_file(),
        "ResponsesMessageContractTest": (root / "src/test/java/ai/abandonware/nova/orch/llm/ResponsesMessageContractTest.java").is_file(),
        "ResponsesToolRoundTripContractTest": (root / "src/test/java/ai/abandonware/nova/orch/llm/ResponsesToolRoundTripContractTest.java").is_file(),
        "ResponsesOutputLimitContractTest": (root / "src/test/java/ai/abandonware/nova/orch/llm/ResponsesOutputLimitContractTest.java").is_file(),
        "ResponsesTerminalStatusContractTest": (root / "src/test/java/ai/abandonware/nova/orch/llm/ResponsesTerminalStatusContractTest.java").is_file(),
        "OpenAiCompatBaseUrlContractTest": (root / "src/test/java/com/example/lms/llm/OpenAiCompatBaseUrlContractTest.java").is_file(),
        "EmbeddingProviderSelectionContractTest": (root / "src/test/java/com/example/lms/config/EmbeddingProviderSelectionContractTest.java").is_file(),
        "UpstashMatchedEmbeddingContractTest": (root / "src/test/java/com/example/lms/service/vector/UpstashMatchedEmbeddingContractTest.java").is_file(),
        "OpenAiResponsesChatModelTest": (root / "src/test/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModelTest.java").is_file(),
    }
    findings = [
        finding("F01", "open" if f01_open else "closed", [
            {"yamlChatMisclassified": mismatches(yaml_chat, False)},
            {"javaDefaultChatMisclassified": mismatches(java_chat, False)},
            {"yamlGuardMissing": mismatches(yaml_guard, True)},
            {"javaGuardMissing": mismatches(java_guard, True)},
            {"prefixHyphenMatcher": prefix_hyphen},
            {"substringHostMismatches": host_mismatches},
            {"existingTest": "src/test/java/ai/abandonware/nova/orch/llm/ModelGuardEndpointContractTest.java"},
        ], leases, [
            "main/resources/application-llm.yaml",
            "main/java/ai/abandonware/nova/config/NovaModelGuardProperties.java",
            "main/java/ai/abandonware/nova/orch/llm/ModelGuardSupport.java",
            "main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java",
            "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
            "src/test/java/ai/abandonware/nova/orch/aop/ModelGuardYamlCompatibilityTest.java",
        ]),
        finding("F03", "open" if f03_open else "closed", [
            {"responsesUsesCompletionsPrompt": f03_open},
            {"legacyCompletionsPromptStillPresent": legacy_kept},
        ], leases, [
            "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java",
            "main/java/com/example/lms/llm/OpenAiEndpointCompatibility.java",
        ]),
        finding("F04", "open" if f04_open else "closed", [
            {"guardAspectPassesCap": aspect_cap},
            {"routerAspectPassesCap": router_cap},
            {"chatWorkflowFallbackPassesCap": workflow_cap},
        ], leases, [
            "main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java",
            "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
            "main/java/com/example/lms/service/ChatWorkflow.java",
        ]),
        finding("F05", "open" if f05_open else "closed", [
            {"incompleteDetailsRead": not f05_open},
        ], leases, [
            "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java",
        ]),
        finding("F02", "open" if f02_open else "closed", [
            {"oldSanitizePattern": old_sanitize},
            {"urlCases": url_rows},
        ], leases, [
            "main/java/com/example/lms/llm/OpenAiCompatBaseUrl.java",
            "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
            "main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java",
        ]),
        finding("F06", "open" if f06_open else "closed", [
            {"primarySwitchHasHf": 'case "hf"' in langchain},
            {"hfComponentReadsPluralKey": "embeddings.provider" in hf},
            {"primaryReadsSingularKey": "embedding.provider" in langchain},
        ], leases, [
            "main/java/com/example/lms/config/LangChainConfig.java",
            "main/java/com/example/lms/service/embedding/HfInferenceEmbeddingModel.java",
        ]),
        finding("F07", "open" if f07_open else "closed", [
            {"matchUsesQueryVector": f07_open},
            {"includeVectorsFalse": 'body.put("includeVectors", false)' in upstash},
        ], leases, [
            "main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java",
            "main/java/com/example/lms/vector/FederatedEmbeddingStore.java",
        ]),
    ]
    preserve = {
        "chatWorkflowCap": workflow_cap,
        "ollamaTopLevelDimensions": 'body.put("dimensions", targetDim)' in ollama,
        "chatSseParser": "function createSseEventParser" in chat_js,
        "legacyCompletionsPrompt": legacy_kept,
    }
    report = {
        "schemaVersion": "awx.ma21in-f01-f07-probe.v1",
        "observedAtUtc": now.isoformat(),
        "root": str(root),
        "gradleProof": "not-run",
        "build": {
            "gradlew": (root / "gradlew.bat").is_file(),
            "mainSourceDirDeclared": 'srcDirs("main/java")' in gradle,
            "testSourceDirDeclared": 'srcDirs("src/test/java")' in gradle,
            "mainClassDeclared": 'mainClass.set("com.example.lms.LmsApplication")' in gradle,
            "langchain4jDeclared": "dev.langchain4j:langchain4j:1.0.1" in gradle,
            "dependencyInsight": "not-run",
        },
        "testsPresent": tests,
        "preserve": preserve,
        "findings": findings,
        "repairPathLeases": [
            {"path": rel, "leaseBlocks": blocks_for(rel, leases)}
            for rel in REPAIR_PATHS
            if blocks_for(rel, leases)
        ],
        "openCount": sum(1 for item in findings if item["status"] == "open"),
    }
    return report, 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only F01-F07 contract probe")
    parser.add_argument("--root", default=".")
    parser.add_argument("--fail-on-open", action="store_true")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    report, code = build_report(root)
    json.dump(report, sys.stdout, ensure_ascii=True, indent=2)
    sys.stdout.write("\n")
    if code == 0 and args.fail_on_open and report.get("openCount", 0):
        return 1
    return code


if __name__ == "__main__":
    raise SystemExit(main())
