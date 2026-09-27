from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
SKILLS = ROOT / ".agents" / "skills"


def read_skill(name: str) -> str:
    return (SKILLS / name / "SKILL.md").read_text(encoding="utf-8")


def output_contract(text: str) -> str:
    match = re.search(r"^## .*Output Contract\s*$([\s\S]*?)(?=^## |\Z)", text, re.MULTILINE)
    return match.group(1) if match else ""


def three_query_families(text: str) -> list[str]:
    match = re.search(
        r"^## Exactly Three Query Recipe\s*$([\s\S]*?)(?=^## |\Z)",
        text,
        re.MULTILINE,
    )
    section = match.group(1) if match else ""
    return re.findall(r"^\| `([^`]+)` \|", section, re.MULTILINE)


def require(errors: list[str], label: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle not in text:
            errors.append(f"{label}: missing {needle}")


def main() -> int:
    names = {
        "generator": "demo1-generating-falsifiable-hypotheses",
        "retriever": "demo1-retrieving-counter-evidence",
        "verifier": "demo1-verifying-evidence-coherence",
        "triangulator": "demo1-triangulating-counter-evidence",
    }
    docs = {label: read_skill(name) for label, name in names.items()}
    contracts = {label: output_contract(text) for label, text in docs.items()}
    errors: list[str] = []

    for label, contract in contracts.items():
        require(errors, label, contract, "schemaVersion:", "packetRef:")

    require(
        errors,
        "generator",
        contracts["generator"],
        "original_claim:",
        "anchorCandidate:",
        "leader:",
        "strongestAlternative:",
        "evidenceNeeded:",
    )
    require(errors, "generator", docs["generator"], "leader is `ranking[0]`")
    require(
        errors,
        "generator",
        docs["generator"],
        "allowed_values",
        "highest-ranked allowed",
        "not_available",
    )
    require(
        errors,
        "retriever",
        contracts["retriever"],
        'relationTarget: "originalClaim"',
        'direction: "supports|counters|neutral|not_comparable"',
        'coverage: "complete|partial|unknown"',
        "decisionChanging: true | false",
        "traceRef:",
    )
    require(
        errors,
        "retriever",
        docs["retriever"],
        "A blocked slot is exhausted for this packet only",
        "Missing `hardBudget`",
    )
    require(
        errors,
        "verifier",
        contracts["verifier"],
        "id:",
        "traceRef:",
        "independenceGroup:",
        "consumedPacketRefs:",
        "decisiveEvidenceIds:",
        "unresolvedConflictIds:",
        "verificationGatePassed:",
    )
    require(errors, "verifier", docs["verifier"], "DEFER emits no VerdictPacket", "deferDecision")
    require(
        errors,
        "verifier claim-state split",
        docs["verifier"],
        "If the required claim artifact is missing",
        "return `DEFER` with no `VerdictPacket`",
        "A present literal `unknown` value",
        "may produce `UNDERDETERMINED` and `HOLD`",
    )
    require(
        errors,
        "triangulator",
        docs["triangulator"],
        "validate_counter_evidence_skill_family.py",
        "supports -> SUPPORTS",
        "counters -> CONFLICTS",
        "not_comparable -> NOT_COMPARABLE",
        "verdictPacket.decisiveEvidenceIds",
        "verdictPacket.unresolvedConflictIds",
        "revisedHypothesis -> alternativeSuggestion",
        "requiredNextEvidence",
        "verificationGatePassed",
        "original_claim -> originalClaim",
        "decisionQuestion -> question",
        "currentHypothesis=originalClaim",
        "`direction` or `supports/conflicts`",
        "deferDecision:",
        "decisiveEvidenceIds=[]",
        "unresolvedConflicts=[]",
        "null on DEFER",
        "forcedChoice=true",
        "not_available",
        "complete normalized matrix",
    )
    require(
        errors,
        "triangulator retrieval budget routing",
        docs["triangulator"],
        "Retrieval action `RUN` requires a new bounded `retrievalBudget`",
        "`REUSE` uses the referenced packet's budget metadata and requires no new budget",
        "Direct verifier, retrieval `SKIP`, and retrieval `DEFER` accept `retrievalBudget: null | not_required`",
    )

    expected_query_families = [
        "authoritative_constraint",
        "alternative_or_unknown",
        "provenance_and_time",
    ]
    actual_query_families = three_query_families(docs["retriever"])
    if actual_query_families != expected_query_families:
        errors.append(
            "retriever: query families must be exactly "
            f"{expected_query_families}, got {actual_query_families}"
        )

    for label in ("generator", "retriever"):
        if "verificationGatePassed:" in contracts[label]:
            errors.append(f"{label}: output contract must not own verificationGatePassed")
    require(
        errors,
        "weak-signal boundary",
        docs["generator"] + docs["retriever"] + docs["verifier"],
        "decisionAuthority: probe_only",
        "maxAttempts: 1",
        "weak-signal ID never appears in `decisiveEvidenceIds`",
        "its ID cannot enter `decisiveEvidenceIds`",
    )

    for label, text in docs.items():
        lines = len(text.splitlines())
        words = len(re.findall(r"\S+", text))
        if lines > 160:
            errors.append(f"{label}: line budget {lines}>160")
        if words > 1200:
            errors.append(f"{label}: word budget {words}>1200")

    result = {
        "schemaVersion": "demo1.counter-evidence-family-validation.v1",
        "ok": not errors,
        "skills": len(docs),
        "errorCount": len(errors),
        "errors": errors,
    }
    print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
