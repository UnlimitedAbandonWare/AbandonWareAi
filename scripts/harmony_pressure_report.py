import argparse
import datetime as _dt
import json
import re
import sys
from pathlib import Path
from typing import Any

if __package__:
    from .harmony_catch_contract import classify_java_catches, summarize_catches
else:
    from harmony_catch_contract import classify_java_catches, summarize_catches


ACTIVE_JAVA_ROOTS = (
    Path("main/java"),
    Path("app/src/main/java_clean"),
)

SUBSYSTEMS = {
    "S01_Overdrive": ("Overdrive", "Anchor", "DynamicContextCompressor"),
    "S02_CFVM": ("CFVM", "RawMatrixBuffer", "RawTile", "RetrievalOrder"),
    "S03_MoE": ("MoE", "ArtPlate", "RgbStrategySelector"),
    "S04_Matryoshka": ("Matryoshka", "ZCA", "Whitening", "Embedding"),
    "S05_ExtremeZ": ("ExtremeZ", "CancelShield", "TimeBudget"),
    "S06_Hypernova": ("HYPERNOVA", "TWPM", "CVaR", "Risk-K", "DPP"),
    "S07_CIH": ("CIH-RAG", "MLA", "Breadcrumb", "IQR"),
    "S08_Adapter": ("LangChain4j", "OpenAI", "OpenAi", "VersionPurity", "PromptBuilder"),
}

SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|"
    r"AIza[0-9A-Za-z_-]{20,}|"
    r"gsk_[A-Za-z0-9]{20,}|"
    r"pcsk_[A-Za-z0-9_-]{20,}"
)
DIAGNOSTIC_OR_TOOLING_PATH_MARKERS = (
    "/tools/",
    "/debug/",
    "/trace/",
    "/service/trace/",
    "/service/agent/",
    "/service/ops/",
    "/learning/ops/",
    "/controller/agent/",
)


def _relative(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.name


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def _java_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for rel in ACTIVE_JAVA_ROOTS:
        base = root / rel
        if base.exists():
            files.extend(p for p in base.rglob("*.java") if p.is_file())
    return sorted(files)


def _subsystem_hits(text: str) -> dict[str, int]:
    hits: dict[str, int] = {}
    for subsystem, terms in SUBSYSTEMS.items():
        count = sum(1 for term in terms if re.search(re.escape(term), text, re.IGNORECASE))
        if count:
            hits[subsystem] = count
    return hits


def _catch_pressure(path: Path, root: Path, text: str) -> tuple[int, int, int, int]:
    summary = summarize_catches(
        classify_java_catches(path=path, root=root, text=text)
    )
    return (
        summary["catchBlockCount"],
        summary["catchWithoutBreadcrumbCount"],
        summary["broadCatchCount"],
        summary["broadCatchWithoutLocalBreadcrumbCount"],
    )


def _is_llm_transport_or_trace_boundary(relative_path: str, text: str) -> bool:
    normalized = "/" + (relative_path or "").replace("\\", "/")
    name = normalized.rsplit("/", 1)[-1]
    return (
        "implements ChatModel" in text
        or name.endswith("ChatModel.java")
        or name.endswith("LlmCallTraceAspect.java")
        or "execution(* dev.langchain4j.model.chat.ChatModel.chat" in text
    )


def _looks_like_manual_prompt_candidate(relative_path: str, text: str) -> bool:
    if _is_llm_transport_or_trace_boundary(relative_path, text):
        return False
    touches_llm = any(
        token in text
        for token in (
            "ChatModel",
            "LlmClient",
            "UserMessage.from",
            ".complete(",
            ".generate(",
            "SystemMessage",
        )
    )
    uses_builder = "promptBuilder.build" in text or "PromptBuilder.build" in text
    prompt_like = bool(re.search(r"StringBuilder|String\s+\w*[Pp]rompt|UserMessage\.from|SystemMessage", text))
    return touches_llm and prompt_like and not uses_builder


def _is_diagnostic_or_tooling_file(relative_path: str) -> bool:
    normalized = "/" + (relative_path or "").replace("\\", "/")
    if any(marker in normalized for marker in DIAGNOSTIC_OR_TOOLING_PATH_MARKERS):
        return True
    name = normalized.rsplit("/", 1)[-1]
    return name.endswith(("Scanner.java", "Reporter.java", "ReportService.java", "ReportController.java"))


def _aspect_risk_score(
    *,
    explicit_order: bool,
    subsystem_count: int,
    lines: int,
    catch_without_trace: int,
    broad_without_trace: int,
) -> float:
    score = 0.0
    if not explicit_order:
        score += 0.35
    score += min(0.25, subsystem_count * 0.05)
    score += min(0.20, lines / 5000.0)
    score += min(0.10, catch_without_trace * 0.025)
    score += min(0.10, broad_without_trace * 0.05)
    return round(min(1.0, score), 4)


def _is_spring_aspect(text: str) -> bool:
    return bool(re.search(r"(?m)^\s*@Aspect\b", text))


def build_report(root: Path) -> dict[str, Any]:
    root = root.resolve()
    java_files = _java_files(root)
    cross_subsystem: list[dict[str, Any]] = []
    aspect_hotspots: list[dict[str, Any]] = []
    catch_pressure_files: list[dict[str, Any]] = []
    aspect_files = 0
    ordered_aspects = 0
    manual_prompt_candidates: list[dict[str, Any]] = []
    catch_blocks = 0
    catch_without_trace = 0
    broad_catches = 0
    broad_without_trace = 0
    secret_hits = 0

    for path in java_files:
        text = _read(path)
        lines = text.splitlines()
        rel = _relative(root, path)
        hits = _subsystem_hits(text)
        c, c_without, broad, broad_without = _catch_pressure(path, root, text)
        if c_without or broad_without:
            catch_pressure_files.append(
                {
                    "file": rel,
                    "lines": len(lines),
                    "catchBlocks": c,
                    "catchWithoutLocalBreadcrumbApprox": c_without,
                    "broadCatchBlocks": broad,
                    "broadCatchWithoutLocalBreadcrumbApprox": broad_without,
                }
            )
        if len(hits) >= 2:
            cross_subsystem.append(
                {
                    "file": rel,
                    "lines": len(lines),
                    "subsystems": sorted(hits),
                    "hitScore": sum(hits.values()),
                }
            )
        if _is_spring_aspect(text):
            aspect_files += 1
            explicit_order = "@Order" in text or "Ordered." in text or "getOrder(" in text
            if explicit_order:
                ordered_aspects += 1
            aspect_hotspots.append(
                {
                    "file": rel,
                    "lines": len(lines),
                    "explicitOrder": explicit_order,
                    "subsystemCount": len(hits),
                    "subsystems": sorted(hits),
                    "catchWithoutLocalBreadcrumbApprox": c_without,
                    "broadCatchWithoutLocalBreadcrumbApprox": broad_without,
                    "riskScore": _aspect_risk_score(
                        explicit_order=explicit_order,
                        subsystem_count=len(hits),
                        lines=len(lines),
                        catch_without_trace=c_without,
                        broad_without_trace=broad_without,
                    ),
                }
            )
        catch_blocks += c
        catch_without_trace += c_without
        broad_catches += broad
        broad_without_trace += broad_without
        if _looks_like_manual_prompt_candidate(rel, text):
            manual_prompt_candidates.append({"file": rel, "lines": len(lines)})
        secret_hits += len(SECRET_RE.findall(text))

    cross_subsystem.sort(
        key=lambda row: (len(row["subsystems"]), row["lines"], row["hitScore"]),
        reverse=True,
    )
    large_cross = [row for row in cross_subsystem if row["lines"] >= 1000]
    runtime_cross = [row for row in cross_subsystem if not _is_diagnostic_or_tooling_file(row["file"])]
    diagnostic_cross = [row for row in cross_subsystem if _is_diagnostic_or_tooling_file(row["file"])]
    runtime_large_cross = [row for row in runtime_cross if row["lines"] >= 1000]
    aspect_hotspots.sort(
        key=lambda row: (
            row["explicitOrder"],
            -row["riskScore"],
            -row["subsystemCount"],
            -row["lines"],
            row["file"],
        )
    )
    unordered_aspects = [row for row in aspect_hotspots if not row["explicitOrder"]]
    catch_pressure_files.sort(
        key=lambda row: (
            row["broadCatchWithoutLocalBreadcrumbApprox"],
            row["catchWithoutLocalBreadcrumbApprox"],
            row["lines"],
            row["file"],
        ),
        reverse=True,
    )
    runtime_large_evidence = sorted(
        (
            {
                "file": row["file"],
                "lines": int(row["lines"]),
                "subsystems": list(row["subsystems"]),
                "hitScore": int(row["hitScore"]),
            }
            for row in runtime_large_cross
        ),
        key=lambda row: (row["file"].casefold(), row["file"]),
    )
    broad_catch_evidence = sorted(
        (
            {
                "file": row["file"],
                "lines": int(row["lines"]),
                "broadCatchBlocks": int(row["broadCatchBlocks"]),
                "broadCatchWithoutLocalBreadcrumbApprox": int(
                    row["broadCatchWithoutLocalBreadcrumbApprox"]
                ),
            }
            for row in catch_pressure_files
            if int(row["broadCatchWithoutLocalBreadcrumbApprox"]) > 0
        ),
        key=lambda row: (row["file"].casefold(), row["file"]),
    )
    manual_prompt_evidence = sorted(
        (
            {
                "file": row["file"],
                "lines": int(row["lines"]),
            }
            for row in manual_prompt_candidates
        ),
        key=lambda row: (row["file"].casefold(), row["file"]),
    )
    critical_unordered_aspects = [
        row
        for row in unordered_aspects
        if row["subsystemCount"] >= 2
        or row["lines"] >= 1000
        or row["catchWithoutLocalBreadcrumbApprox"] > 0
    ]

    return {
        "generatedAt": _dt.datetime.now().isoformat(timespec="seconds"),
        "activeRoots": [root_name.as_posix() for root_name in ACTIVE_JAVA_ROOTS],
        "activeJavaFileCount": len(java_files),
        "crossSubsystemFiles": len(cross_subsystem),
        "crossSubsystemLargeFilesOver1000": len(large_cross),
        "runtimeCrossSubsystemFiles": len(runtime_cross),
        "runtimeCrossSubsystemLargeFilesOver1000": len(runtime_large_cross),
        "diagnosticCrossSubsystemFiles": len(diagnostic_cross),
        "topCrossSubsystemFiles": cross_subsystem[:25],
        "topRuntimeCrossSubsystemFiles": runtime_cross[:25],
        "topDiagnosticCrossSubsystemFiles": diagnostic_cross[:25],
        "catchBlocks": catch_blocks,
        "catchWithoutLocalBreadcrumbApprox": catch_without_trace,
        "catchWithoutLocalBreadcrumbRatio": round(catch_without_trace / catch_blocks, 4)
        if catch_blocks
        else 0.0,
        "broadCatchBlocks": broad_catches,
        "broadCatchWithoutLocalBreadcrumbApprox": broad_without_trace,
        "broadCatchWithoutLocalBreadcrumbRatio": round(broad_without_trace / broad_catches, 4)
        if broad_catches
        else 0.0,
        "topCatchPressureFiles": catch_pressure_files[:25],
        "aspectFiles": aspect_files,
        "aspectFilesWithExplicitOrderApprox": ordered_aspects,
        "unorderedAspectCount": len(unordered_aspects),
        "criticalUnorderedAspectCount": len(critical_unordered_aspects),
        "aspectOrderCoverageApprox": round(ordered_aspects / aspect_files, 4) if aspect_files else 0.0,
        "topUnorderedAspectHotspots": unordered_aspects[:30],
        "manualPromptCandidateCount": len(manual_prompt_candidates),
        "manualPromptCandidateFiles": manual_prompt_candidates[:30],
        "ledgerEvidence": {
            "runtimeCrossSubsystemLargeFiles": runtime_large_evidence,
            "broadCatchWithoutLocalBreadcrumbFiles": broad_catch_evidence,
            "manualPromptCandidateFiles": manual_prompt_evidence,
        },
        "secretPatternHits": secret_hits,
        "decision": "pressure_report",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate a Dynamic RAG harmony pressure report")
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument(
        "--output",
        default="verification/dynamic-rag-harmony-pressure-metrics.json",
        help="JSON output path",
    )
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    output = Path(args.output)
    if output.is_absolute():
        output = output.resolve()
    else:
        output = (root / output).resolve()
        try:
            output.relative_to(root)
        except ValueError:
            print(
                "[AWX][harmony-pressure] output-invalid "
                "reason=output-relative-outside-root",
                file=sys.stderr,
            )
            return 2
        try:
            link_count = output.stat().st_nlink
        except FileNotFoundError:
            link_count = 0
        except OSError:
            print(
                "[AWX][harmony-pressure] output-invalid "
                "reason=output-relative-inspection-failed",
                file=sys.stderr,
            )
            return 2
        if output.is_file() and link_count > 1:
            print(
                "[AWX][harmony-pressure] output-invalid "
                "reason=output-relative-hardlink",
                file=sys.stderr,
            )
            return 2
    report = build_report(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[AWX][harmony-pressure] report={output}")
    print(
        "[AWX][harmony-pressure] "
        f"crossSubsystemFiles={report['crossSubsystemFiles']} "
        f"crossSubsystemLargeFilesOver1000={report['crossSubsystemLargeFilesOver1000']} "
        f"runtimeCrossSubsystemLargeFilesOver1000={report['runtimeCrossSubsystemLargeFilesOver1000']} "
        f"diagnosticCrossSubsystemFiles={report['diagnosticCrossSubsystemFiles']} "
        f"broadCatchWithoutLocalBreadcrumbApprox={report['broadCatchWithoutLocalBreadcrumbApprox']} "
        f"aspectOrderCoverageApprox={report['aspectOrderCoverageApprox']} "
        f"secretPatternHits={report['secretPatternHits']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
