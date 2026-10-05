"""orchestra_route.py — pick a lane for one awx.orchestra-signal.v1 signal.

Never re-implements the existing classifiers: it calls
  scripts/codex_question_classifier.py --text
  scripts/awx_skill_router.py resolve
  scripts/devin_task_orchestrate.py plan --brief-file
and cites their JSON as evidence. Lane rules live in
scripts/fixtures/orchestra/route-rules.json (user-tunable, not hardcoded).

Guards wired in here (O7):
- 401/403/429 in signal text -> derived kind=question signal, never a retry.
- >roundtripLimit agent hops along the parentId stem -> ASK_USER.
- files overlapping an active lease or another in-progress signal -> warning
  + "같은 줄기로 묶거나 순서 정하기" suggestion; foreign leases untouched.

Network/paid calls: none. All evidence calls are local subprocesses.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA = "awx.orchestra-route.v1"

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        pass
SIGNAL_SCHEMA = "awx.orchestra-signal.v1"
DEFAULT_RULES = "scripts/fixtures/orchestra/route-rules.json"
DEFAULT_STORE = "data/agent-handoff/orchestra"
LEASE_GLOB = "source-edit-locks/*.lock/lease.json"
PATCH_DROP = "__patch_drop__"

PRODUCT_ROOTS = ("main/", "app/", "frontend/", "configs/", "src/")
TOOLING_ROOTS = ("scripts/", "docs/", ".agents/", "agent-prompts/", "var/",
                 "data/agent-handoff/")

ERROR_CODE_RE = re.compile(r"(?<!\d)(401|403|429)(?!\d)")
ERROR_WORD_RE = re.compile(
    r"(?i)\b(unauthorized|forbidden|too many requests|rate.?limit|"
    r"key[_ -]?invalid|plan[_ -]?gate)\b")


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def load_signal(root, store, ref):
    path = Path(ref)
    if not path.is_file():
        base = Path(root) / store
        for part in ("inbox", "outbox"):
            hits = sorted((base / part).glob("*/" + ref + ".json"))
            if hits:
                path = hits[0]
                break
        else:
            hit = base / "archive" / (ref + ".json")
            if hit.is_file():
                path = hit
    if not path.is_file():
        raise FileNotFoundError("signal-not-found:" + ref)
    return load_json(path), path


def stem_signals(root, store, sig):
    """Walk parentId chain upward; returns [sig, parent, grandparent, ...]."""
    chain = [sig]
    seen = {sig.get("id")}
    cur = sig
    for _ in range(20):
        pid = cur.get("parentId")
        if not pid or pid in seen:
            break
        try:
            parent, _ = load_signal(root, store, pid)
        except FileNotFoundError:
            break
        chain.append(parent)
        seen.add(pid)
        cur = parent
    return chain


def count_roundtrips(chain):
    hops = 0
    prev = None
    for sig in chain:
        sender = sig.get("from")
        if prev is not None and sender != prev:
            hops += 1
        prev = sender
    return hops


def run_tool(root, argv, timeout=60):
    try:
        proc = subprocess.run([sys.executable, "-B"] + argv, cwd=root,
                              capture_output=True, text=True, timeout=timeout,
                              encoding="utf-8", errors="replace")
        out = proc.stdout.strip()
        try:
            parsed = json.loads(out)
        except ValueError:
            parsed = {"raw": out[:400]}
        return {"exit": proc.returncode, "json": parsed}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"exit": None, "error": str(exc)}


def classifier_evidence(root, sig):
    text = (sig.get("summary") or "") + "\n" + (sig.get("notes") or "")
    evidence = {}
    evidence["questionClassifier"] = run_tool(
        root, ["scripts/codex_question_classifier.py", "--text", text[:2000]])
    evidence["skillRouter"] = run_tool(
        root, ["scripts/awx_skill_router.py", "resolve", text[:500]])
    brief = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".txt", prefix="orch-brief-",
                                         delete=False, encoding="utf-8") as tmp:
            tmp.write(text[:4000])
            brief = tmp.name
        evidence["taskOrchestrate"] = run_tool(
            root, ["scripts/devin_task_orchestrate.py", "plan",
                   "--brief-file", brief])
    finally:
        if brief:
            Path(brief).unlink(missing_ok=True)
    return evidence


def product_files(sig):
    return [f for f in (sig.get("files") or [])
            if f.replace("\\", "/").startswith(PRODUCT_ROOTS)]


def tooling_files(sig):
    return [f for f in (sig.get("files") or [])
            if f.replace("\\", "/").startswith(TOOLING_ROOTS)]


def marker_hits(sig, markers):
    text = ((sig.get("summary") or "") + " " + (sig.get("notes") or "")).lower()
    return [m for m in markers if m.lower() in text]


def highbar_hits(sig, rules):
    hits = []
    files = sig.get("files") or []
    prod = product_files(sig)
    seams = {f.replace("\\", "/").split("/")[0] for f in prod}
    if len(prod) >= 3 or len(seams) >= 2:
        hits.append("broad-surface")
    choices = sig.get("choices")
    text = (sig.get("summary") or "") + " " + (sig.get("notes") or "")
    if (isinstance(choices, list) and len(choices) >= 2) or \
            len(re.findall(r"또는|(?:^|\s)vs(?:\s|$)", text)) >= 1 and \
            ("선택" in text or "또는" in text or " vs " in text.lower()):
        hits.append("design-choice")
    if marker_hits(sig, rules.get("externalInfoMarkers", [])):
        hits.append("external-info")
    if marker_hits(sig, rules.get("qualityCeilingMarkers", [])):
        hits.append("quality-ceiling")
    if int(sig.get("priorAttempts") or 0) >= 2 or \
            "두 번 실패" in text or "failed twice" in text.lower():
        hits.append("prior-attempts")
    return hits


def error_code_signal(sig, rules):
    text = " ".join(str(sig.get(k) or "") for k in ("summary", "notes")) + " " + \
        " ".join(sig.get("openItems") or []) + " " + " ".join(sig.get("findings") or [])
    match = ERROR_CODE_RE.search(text)
    word = ERROR_WORD_RE.search(text)
    if not match and not word:
        return None
    code = match.group(1) if match else None
    table = rules.get("errorCodeMap", {})
    entry = table.get(code) if code else None
    if entry is None:  # keyword-only hit -> treat like 403 unknown cause
        entry = {"kind": "question", "lane": "ASK_USER",
                 "why": "auth/rate error word observed; classify before retry"}
    return {"detectedCode": code, "matchedWord": word.group(0) if word else None,
            "derivedSignal": {"kind": entry["kind"], "lane": entry["lane"],
                              "status": "new"},
            "why": entry["why"], "retrySignalCreated": False}


def lease_is_active(lease):
    """Raw lease.json has no status field: live = expiresAtUtc in the future
    and not explicitly released."""
    if str(lease.get("status") or "").lower() in ("released", "ended", "expired"):
        return False
    exp = lease.get("expiresAtUtc")
    if exp:
        try:
            from datetime import datetime, timezone
            return datetime.fromisoformat(str(exp).replace("Z", "+00:00")) > \
                datetime.now(timezone.utc)
        except ValueError:
            return False
    return bool(lease.get("mutationAllowed"))


def active_lease_targets(root):
    out = []
    locks = Path(root) / PATCH_DROP / LEASE_GLOB.split("/")[0]
    for lease_file in sorted(locks.glob("*.lock/lease.json")):
        try:
            lease = load_json(lease_file)
        except (OSError, ValueError):
            continue
        if not lease_is_active(lease):
            continue
        out.append({"topic": lease_file.parent.name[:-5],
                    "targets": lease.get("targetPaths") or []})
    return out


def overlap_warnings(root, store, sig):
    mine = {f.replace("\\", "/").casefold() for f in (sig.get("files") or [])}
    warnings = []
    for lease in active_lease_targets(root):
        hits = sorted(mine & {t.replace("\\", "/").casefold()
                              for t in lease["targets"]})
        if hits:
            warnings.append({"type": "lease-overlap", "owner": lease["topic"],
                             "files": hits,
                             "suggestion": "같은 줄기로 묶거나 순서 정하기"})
    base = Path(root) / store
    for part in ("inbox", "outbox"):
        part_dir = base / part
        if not part_dir.is_dir():
            continue
        for path in sorted(part_dir.rglob("*.json")):
            try:
                other = load_json(path)
            except (OSError, ValueError):
                continue
            if other.get("id") == sig.get("id") or \
                    other.get("status") in ("done", "dropped", "archive"):
                continue
            hits = sorted(mine & {f.replace("\\", "/").casefold()
                                  for f in (other.get("files") or [])})
            if hits:
                warnings.append({"type": "signal-overlap",
                                 "otherId": other.get("id"),
                                 "otherFrom": other.get("from"),
                                 "files": hits,
                                 "suggestion": "같은 줄기로 묶거나 순서 정하기"})
    return warnings


def tower_agent(lane, rules):
    """controlTower marker only: who commands the lane, never how it was picked."""
    tower = rules.get("controlTower") or {}
    if lane in (tower.get("appliesTo") or []):
        return tower.get("agent")
    return None


def next_agent(lane, sig, rules):
    lane_info = rules.get("lanes", {}).get(lane, {})
    if lane == "GPTPRO_THEN_CODEX" and sig.get("kind") in ("gptpro-brief", "codex-brief"):
        return "codex"
    return lane_info.get("nextAgent", "user")


def required_inputs(lane, sig, chain, rules):
    if lane == "GPTPRO_THEN_CODEX":
        evidence_ids = [s.get("id") for s in chain
                        if s.get("kind") == "web-evidence"]
        return {"zipProfileCandidates": ["product-source"],
                "webEvidenceSignalIds": evidence_ids,
                "pasteTemplate": "harmonized GPT Pro template (요청/모드/꼭 지킬 것/받을 사람 4칸)",
                "runner": "Pack-GPTPro.bat — user-only, agents never run gptpro_pack.py"}
    if lane == "AGY_RESEARCH":
        return {"fuseCommand": "python -B scripts/agy_web_fuse.py",
                "returnAs": "web-evidence signal via orchestra_signal.py new"}
    if lane in ("GROKBOT_AMPLIFY", "AGY_AS_GROKBOT"):
        return {"pack": "grokbot-current handover pack",
                "skill": "demo1-agy-grokbot-mode" if lane == "AGY_AS_GROKBOT"
                         else "demo1-grokbot-role"}
    if lane == "CODEX_DIRECT":
        return {"gates": ["demo1-source-edit-three-way-preflight", "lease",
                          "codex_work_checkpoint"]}
    if lane == "DEVIN":
        return {"allowed": "new tooling/docs only", "forbidden": "product source"}
    if lane == "ASK_USER":
        return {"question": sig.get("summary", "")[:200]}
    return {}


def decide(root, store, sig, rules, grokbot_absent=False):
    chain = stem_signals(root, store, sig)
    text = " ".join(str(sig.get(k) or "") for k in ("summary", "notes"))
    reasons = []

    # 1. Error-code guard: never emit a retry signal.
    err = error_code_signal(sig, rules)
    if err:
        lane = err["derivedSignal"]["lane"]
        reasons.append(f"error {err['detectedCode'] or err['matchedWord']} 관찰 → "
                       "재시도 대신 원인 신호")
        reasons.append(err["why"])
        return {"lane": lane, "reasons": reasons[:3],
                "derivedSignal": err["derivedSignal"],
                "detectedError": err, "roundtrips": count_roundtrips(chain),
                "chain": [s.get("id") for s in chain]}

    # 2. Roundtrip cap.
    hops = count_roundtrips(chain)
    limit = int(rules.get("roundtripLimit", 2))
    if hops > limit:
        reasons.append(f"같은 줄기 왕복 {hops}회 > 상한 {limit}회")
        reasons.append("막힌 줄기는 hold, 나머지 줄기는 계속")
        return {"lane": "ASK_USER", "reasons": reasons,
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}

    # 3. Irreversible.
    irr = marker_hits(sig, rules.get("irreversibleMarkers", []))
    if irr:
        reasons.append("되돌릴 수 없는 표지: " + ", ".join(irr[:3]))
        reasons.append("공개 비용/데이터 삭제/권한 정책은 사용자 결정")
        return {"lane": "ASK_USER", "reasons": reasons,
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}

    kind = sig.get("kind")
    # 4. Research first.
    if kind == "research-question":
        reasons.append("kind=research-question → 조사 먼저")
        reasons.append("결과는 web-evidence 신호로 회수")
        return {"lane": "AGY_RESEARCH", "reasons": reasons,
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}

    # 5. Vague idea -> amplify.
    vague = marker_hits(sig, rules.get("vagueIdeaMarkers", []))
    if kind == "idea" and (vague or len(text.strip()) < 60 or not sig.get("files")):
        lane = "AGY_AS_GROKBOT" if grokbot_absent else "GROKBOT_AMPLIFY"
        reasons.append("kind=idea 이고 아직 흐릿함(표지·짧은 요약·files 없음)")
        reasons.append("Grok Bot 부재" if grokbot_absent else "Grok Bot 연타로 키움")
        return {"lane": lane, "reasons": reasons,
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}

    # 6. High bar -> GPT Pro then Codex.
    gpt = rules.get("lanes", {}).get("GPTPRO_THEN_CODEX", {})
    hits = highbar_hits(sig, rules)
    if len(hits) >= int(gpt.get("minSignals", 2)):
        reasons.append("고점 표지 " + str(len(hits)) + "개: " + ", ".join(hits))
        reasons.append("GPT Pro 지시서(웹서치+ZIP)를 거쳐 Codex로")
        return {"lane": "GPTPRO_THEN_CODEX", "reasons": reasons,
                "highBar": hits, "roundtrips": hops,
                "chain": [s.get("id") for s in chain]}

    # 7. Product source, narrow -> Codex direct.
    if product_files(sig):
        reasons.append("제품 소스 파일 지정, 범위 좁음")
        reasons.append("근거가 PC 확인 기준 → Codex 직행")
        return {"lane": "CODEX_DIRECT", "reasons": reasons,
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}

    # 8. Tooling/docs scope -> Devin.
    if tooling_files(sig) or kind in ("devin-signal", "verify-finding"):
        reasons.append("도구·스크립트·규칙·검증 범위")
        reasons.append("제품 소스 수정 없음 → Devin")
        return {"lane": "DEVIN", "reasons": reasons,
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}

    # 9. Default: still-fuzzy non-idea goes back to amplify; else Devin.
    if kind == "idea":
        lane = "AGY_AS_GROKBOT" if grokbot_absent else "GROKBOT_AMPLIFY"
        return {"lane": lane, "reasons": ["기본값: 아이디어 → 키우기 먼저"],
                "roundtrips": hops, "chain": [s.get("id") for s in chain]}
    return {"lane": "DEVIN", "reasons": ["기본값: 실행/정리 성격 → Devin"],
            "roundtrips": hops, "chain": [s.get("id") for s in chain]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--store", default=DEFAULT_STORE)
    parser.add_argument("--rules", default=DEFAULT_RULES)
    parser.add_argument("--signal", "--id", dest="signal",
                        required=True, help="signal id or json path")
    parser.add_argument("--grokbot-absent", action="store_true")
    parser.add_argument("--apply", action="store_true",
                        help="write lane+status=routed back into the stored signal")
    parser.add_argument("--no-classifiers", action="store_true",
                        help="skip the 3 subprocess evidence calls (tests)")
    args = parser.parse_args()

    try:
        rules = load_json(Path(args.root) / args.rules)
        sig, path = load_signal(args.root, args.store, args.signal)
        decision = decide(args.root, args.store, sig, rules,
                          grokbot_absent=args.grokbot_absent)
        warnings = overlap_warnings(args.root, args.store, sig)
        evidence = {} if args.no_classifiers else classifier_evidence(args.root, sig)
        if args.apply and path.is_file() and str(path).startswith(str(Path(args.root) / args.store)):
            sig["lane"] = decision["lane"]
            sig["status"] = "routed"
            path.write_text(json.dumps(sig, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
        result = {
            "schemaVersion": SCHEMA,
            "signalId": sig.get("id"),
            "signalPath": str(path),
            "lane": decision["lane"],
            "tower": tower_agent(decision["lane"], rules),
            "reasons": decision.get("reasons", [])[:3],
            "nextAgent": next_agent(decision["lane"], sig, rules),
            "requiredInputs": required_inputs(decision["lane"], sig,
                                              stem_signals(args.root, args.store, sig),
                                              rules),
            "highBar": decision.get("highBar", []),
            "roundtrips": decision.get("roundtrips", 0),
            "derivedSignal": decision.get("derivedSignal"),
            "detectedError": decision.get("detectedError"),
            "overlapWarnings": warnings,
            "applied": bool(args.apply),
            "evidence": evidence,
        }
    except (OSError, ValueError, KeyError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
