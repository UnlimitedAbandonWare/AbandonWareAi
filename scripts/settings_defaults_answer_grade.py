#!/usr/bin/env python3
"""settings_defaults_answer_grade -- rule-only grader for the settings
-defaults question pack (var/settings-defaults-assist/question-pack.json).

No LLM anywhere in the grading path.

    grade --question QG|QR|QW|QS (--answer-file F | --answer TEXT)
          [--pack PACK.json]
    grade-all --answers-dir DIR [--pack PACK.json]
          (expects QG.txt / QR.txt / QW.txt / QS.txt style files)

Verdicts: PASS / FAIL / UNKNOWN + machine-readable reasons.

Rules per question kind:
    QG  expected sorted list [1,2,3] present + code fence present +
        sentence count <= maxSentences
    QR  every canary sha256 (from qr-canary.sha, hashes only - the canary
        plaintext is never stored in the pack or this tool) found among the
        answer's token n-grams + expectedEvidence file name present
    QW  every expected substring present
    QS  exactly one sentence

UNKNOWN when the answer is empty/missing or the pack/canary data cannot be
loaded - never silently turned into 0 or PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACK = ROOT / "var" / "settings-defaults-assist" / "question-pack.json"
SCHEMA = "awx.settings-defaults-answer-grade.v1"

SENTENCE_END_RE = re.compile(r"[.!?。！？]+(?=\s|$)")
CODE_FENCE_RE = re.compile(r"```")
LIST_123_RE = re.compile(r"\[\s*1\s*,\s*2\s*,\s*3\s*\]")
WORD_SPLIT_RE = re.compile(r"[\s,，.。!！?？:：;；()（）\[\]{}<>\"'`~|/\\]+")
STRIP_CHARS = "\"'`*_~"


def count_sentences(text: str) -> int:
    """Approximate sentence count: terminal punctuation marks. Code fences
    are stripped first so code lines do not inflate the count."""
    body = CODE_FENCE_RE.sub("\n```\n", text or "")
    chunks = body.split("```")
    prose = " ".join(c for i, c in enumerate(chunks) if i % 2 == 0)
    return len(SENTENCE_END_RE.findall(prose))


def _norm_token(text: str) -> str:
    return text.strip(STRIP_CHARS + " ").strip()


def _candidates(text: str) -> set[str]:
    """Token n-grams (1..3 words) of the answer, normalized - canary strings
    are matched by sha256 so the plaintext never needs to be stored."""
    words = [_norm_token(w) for w in WORD_SPLIT_RE.split(text or "")]
    words = [w for w in words if w]
    out = set()
    for n in (1, 2, 3):
        for i in range(len(words) - n + 1):
            out.add(" ".join(words[i:i + n]))
    return out


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_pack(path: str | Path) -> dict | None:
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _canary_hashes(pack: dict, entry: dict, pack_path: Path) -> set[str] | None:
    ref = entry.get("canaryShaFile")
    if not ref:
        return None
    sha_path = (pack_path.parent / ref).resolve()
    try:
        doc = json.loads(sha_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return {c.get("sha256") for c in doc.get("canaries") or []
            if c.get("sha256")}


def grade(question_id: str, answer: str | None,
          pack_path: str | Path = DEFAULT_PACK) -> dict:
    pack_path = Path(pack_path)
    result = {"schema": SCHEMA, "question": question_id,
              "verdict": "UNKNOWN", "reasons": []}
    pack = load_pack(pack_path)
    if pack is None:
        result["reasons"].append("pack-unreadable")
        return result
    entry = (pack.get("questions") or {}).get(question_id)
    if entry is None:
        result["reasons"].append("question-not-in-pack")
        return result
    result["kind"] = entry.get("kind")
    if answer is None or not str(answer).strip():
        result["reasons"].append("answer-missing")
        return result
    reasons = result["reasons"]

    if question_id == "QG":
        rules = entry.get("rules") or {}
        if not LIST_123_RE.search(answer):
            reasons.append("expected-list-[1,2,3]-absent")
        if rules.get("requireCodeBlock") and not CODE_FENCE_RE.search(answer):
            reasons.append("code-block-absent")
        limit = int(rules.get("maxSentences", 8))
        n = count_sentences(answer)
        result["sentences"] = n
        if n > limit:
            reasons.append(f"sentences>{limit}")
    elif question_id == "QR":
        hashes = _canary_hashes(pack, entry, pack_path)
        if not hashes:
            reasons.append("canary-sha-unreadable")
        else:
            found = {_sha(c) for c in _candidates(answer)} & hashes
            result["canariesMatched"] = len(found)
            if found != hashes:
                reasons.append("canary-mismatch")
        ev = entry.get("expectedEvidence")
        if ev:
            result["evidenceFileFound"] = ev in answer
            if ev not in answer:
                reasons.append("evidence-file-missing")
    elif question_id == "QW":
        missing = [e for e in (entry.get("expected") or [])
                   if e not in answer]
        if missing:
            reasons.append("expected-substrings-absent:" + ",".join(missing))
    elif question_id == "QS":
        rules = entry.get("rules") or {}
        want = int(rules.get("exactSentences", 1))
        n = count_sentences(answer)
        result["sentences"] = n
        if n != want:
            reasons.append(f"sentences!={want}")
    else:
        reasons.append("ungraded-kind")
    result["verdict"] = "FAIL" if reasons else "PASS"
    return result


def _read_answer(args) -> str | None:
    if getattr(args, "answer", None) is not None:
        return args.answer
    if getattr(args, "answer_file", None):
        try:
            return Path(args.answer_file).read_text(encoding="utf-8")
        except OSError:
            return None
    return None


def cmd_grade(args) -> int:
    result = grade(args.question, _read_answer(args), args.pack)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["verdict"] != "UNKNOWN" else 5


def cmd_grade_all(args) -> int:
    d = Path(args.answers_dir)
    pack = load_pack(args.pack) or {}
    out = {"schema": SCHEMA + "-batch", "answersDir": str(d), "results": {}}
    for qid in (pack.get("questions") or {}):
        candidates = [d / f"{qid}.txt", d / f"{qid.lower()}.txt",
                      d / f"answer-{qid.lower()}.txt"]
        text = None
        for c in candidates:
            if c.is_file():
                try:
                    text = c.read_text(encoding="utf-8")
                except OSError:
                    text = None
                break
        out["results"][qid] = grade(qid, text, args.pack)
    if args.out:
        Path(args.out).write_text(json.dumps(out, indent=2,
                                             ensure_ascii=False),
                                  encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("grade")
    p.add_argument("--question", required=True)
    p.add_argument("--answer-file")
    p.add_argument("--answer")
    p.add_argument("--pack", default=str(DEFAULT_PACK))
    p = sub.add_parser("grade-all")
    p.add_argument("--answers-dir", required=True)
    p.add_argument("--pack", default=str(DEFAULT_PACK))
    p.add_argument("--out")
    args = ap.parse_args(argv)
    return {"grade": cmd_grade, "grade-all": cmd_grade_all}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
