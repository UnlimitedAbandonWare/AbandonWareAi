#!/usr/bin/env python3
"""session_close_gate.py — 세션 종료 관문. 기존 도구(lease/journal/checkpoint/golden)를
묶어 안전 종료 조건을 판정한다. legacy PASS는 전체 목표 성공의 증명이 아니다.

subcommands:
  check-start --targets <manifest.json> [--task <taskId>]
      뜨거운 파일(configs/hot-files.yaml) 겹침 + 내 lease 유효 + journal open 여부
      → GO / HOLD:<사유>  (exit 0/1/2)

  close --task <taskId> [--golden C1,C2] [--max-sends N] [--model <id>]
        [--report <path>] [--base http://127.0.0.1:18180] [--json <out>]
      ① declared-scope 변경분이 sealed/verified checkpoint로 덮였는지
      ② REPORT Acceptance 파싱(brief_round_gate.parse_verdicts 규칙: PASS / 사유 있는 NOT_RUN)
        + 테스트 결과 증거 파일·journal verify 이벤트 존재 여부
      ③ --golden 지정 시 chat_rag_golden_browser.js 를 strict+--max-sends 로 실행
        (서버 없으면 NOT_RUN; modelMatched=false 이면 FAIL)
      ④ 다른 세션 lease targetPaths 와 내 worktree diff 교집합 = 0
      ⑤ work_journal close 기록 + lease end 여부
      출력 한 줄: PASS / BLOCKED:<첫 실패> / NOT_RUN:<사유>. 종료코드 0/1/2.

비밀값을 읽거나 출력하지 않는다. 경로만 다룬다.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from brief_lint import _write_utf8  # noqa: E402
from brief_round_gate import STATUS_RE  # noqa: E402
import agent_code_evidence_gate as code_evidence  # noqa: E402
import run_verified_command as verified_command  # noqa: E402
import task_context  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
HOT_FILES = ROOT / "configs" / "hot-files.yaml"
LOCKS = ROOT / "__patch_drop__" / "source-edit-locks"
HANDOFF = ROOT / "data" / "agent-handoff"
JOURNAL_ROOT = HANDOFF / "codex-autonomy"
GOLDEN_JS = ROOT / "scripts" / "chat_rag_golden_browser.js"
GOLDEN_MAX_SENDS = 6  # 지시서 상한: 골든 전송 합계 6회

HOT_PATH_RE = re.compile(r'^\s*-\s*path:\s*"([^"]+)"', re.MULTILINE)
ACC_HEAD_RE = re.compile(r"acceptance|완료\s*기준|수용\s*기준", re.IGNORECASE)
ACC_ID_RE = re.compile(r"\b([A-Z]{1,3}\d{1,2})\b")
REPORT_NAMES = ("REPORT.md", "report.md", "final-report.md", "FINAL-REPORT.md")


def _load_hot_paths(path: Path | None = None) -> list[str]:
    path = path or HOT_FILES
    if not path.is_file():
        return []
    text = path.read_text(encoding="utf-8", errors="replace")
    return [p.strip().casefold() for p in HOT_PATH_RE.findall(text)]


def _sha256_text(value: str) -> str:
    import hashlib
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _load_leases(locks_dir: Path | None = None) -> list[dict]:
    locks_dir = locks_dir or LOCKS
    leases = []
    if not locks_dir.is_dir():
        return leases
    for lease_file in sorted(locks_dir.glob("*.lock/lease.json")):
        try:
            data = json.loads(lease_file.read_bytes() or b"{}")
        except (OSError, ValueError):
            data = {"topic": lease_file.parent.name, "_unreadable": True}
        data["_file"] = lease_file
        data["_topic"] = str(data.get("topic") or lease_file.parent.name)
        try:
            exp = datetime.fromisoformat(str(data.get("expiresAtUtc", "")).replace("Z", "+00:00"))
            data["_expired"] = exp <= datetime.now(timezone.utc)
        except ValueError:
            data["_expired"] = False
        data["_targetPaths"] = [str(p).replace("\\", "/").casefold()
                              for p in (data.get("targetPaths") or [])]
        leases.append(data)
    return leases


def _overlapping(hot_paths: set[str], foreign_leases: list[dict], own_task_hash: str) -> list[dict]:
    hits = []
    for lease in foreign_leases:
        if lease.get("_expired"):
            continue
        if own_task_hash and lease.get("taskIdHash") == own_task_hash:
            continue
        overlap = sorted(set(lease.get("_targetPaths") or []) & hot_paths)
        if overlap:
            hits.append({"topic": lease.get("_topic"), "ownerId": lease.get("ownerId"),
                         "expiresAtUtc": lease.get("expiresAtUtc"), "paths": overlap})
    return hits


def _journal_path(task_id: str) -> Path:
    return JOURNAL_ROOT / task_id / "journal.json"


def _task_dir(task_id: str) -> Path:
    return JOURNAL_ROOT / task_id


def _load_journal(task_id: str) -> dict | None:
    path = _journal_path(task_id)
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_bytes().decode("utf-8-sig"))
    except (OSError, ValueError):
        return None


def _own_lease(task_id: str, leases: list[dict]) -> dict | None:
    task_hash = _sha256_text(task_id)
    for lease in leases:
        if lease.get("taskIdHash") == task_hash and not lease.get("_expired"):
            return lease
    return None


def _manifest_paths(manifest: Path) -> list[str]:
    """lease TargetManifest(JSON) 또는 줄 단위 경로 목록 둘 다 받는다."""
    text = manifest.read_bytes().decode("utf-8-sig", errors="replace")
    try:
        data = json.loads(text)
        targets = data.get("targets", [])
        return [str(t.get("path")).replace("\\", "/") for t in targets if isinstance(t, dict)]
    except ValueError:
        return [ln.strip().replace("\\", "/") for ln in text.splitlines() if ln.strip()]


def check_start(targets_file: Path, task_id: str | None) -> tuple[str, int, dict]:
    paths = _manifest_paths(targets_file)
    hot = set(_load_hot_paths())
    leases = _load_leases()
    own_hash = _sha256_text(task_id) if task_id else ""

    declared_hot = sorted(p.casefold() for p in paths if p.casefold() in hot)
    blockers = []
    if declared_hot:
        hits = _overlapping(set(declared_hot), leases, own_hash)
        if hits:
            blockers.append("hot-file-lease-overlap:" + ",".join(
                f"{h['topic']}~{','.join(h['paths'])}" for h in hits))
    if task_id:
        if _own_lease(task_id, leases) is None:
            blockers.append("own-lease-missing-or-expired")
        journal = _load_journal(task_id)
        if journal is None or journal.get("status") != "in_progress":
            blockers.append("journal-not-open")
    verdict = "HOLD:" + ";".join(blockers) if blockers else "GO"
    detail = {"declaredHotPaths": declared_hot, "blockers": blockers}
    return verdict, (1 if blockers else 0), detail


def _git_changed_paths(root: Path, git: str = "git") -> set[str] | None:
    """diff --name-only HEAD + untracked 목록. git 실패 시 None."""
    names: set[str] = set()
    for args in (["diff", "--name-only", "HEAD"], ["ls-files", "--others", "--exclude-standard"]):
        try:
            out = subprocess.run([git, *args], cwd=root, capture_output=True,
                                 text=True, encoding="utf-8", errors="replace", timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            return None
        if out.returncode != 0:
            return None
        names.update(ln.strip().replace("\\", "/").casefold() for ln in out.stdout.splitlines() if ln.strip())
    return names


def _sealed_paths(task_dir: Path, journal: dict | None = None) -> dict[str, str]:
    """<taskId>/cycle-*/checkpoint.json 중 sealed/verified 상태의 target path 목록.
    checkpoint가 거부한 파일(시크릿 패턴 등)은 journal의 preserve 이벤트(sha256+bytes
    보존)로도 되돌릴 기준점이 된다 — 둘 다 인정한다."""
    covered: dict[str, str] = {}
    if task_dir.is_dir():
        for ck in sorted(task_dir.glob("cycle-*/checkpoint.json")):
            try:
                data = json.loads(ck.read_bytes().decode("utf-8-sig"))
            except (OSError, ValueError):
                continue
            if data.get("status") not in ("sealed", "verified"):
                continue
            rows = data.get("targets") or []
            paths = [str(r.get("path", "")) for r in rows if isinstance(r, dict)]
            paths += list((data.get("postimages") or {}).keys())
            for p in paths:
                p = p.replace("\\", "/").casefold()
                if p:
                    covered[p] = f"{ck.parent.name}:{data['status']}"
    if journal:
        preserved = _preserved_basenames(journal)
        for p in preserved:
            covered.setdefault("*/" + p, f"journal-preserve:{p}")
    return covered


def _preserved_basenames(journal: dict) -> set[str]:
    """journal preserve 이벤트의 ref 파일명(.bin 제외) 집합 — checkpoint 불가 파일용."""
    names: set[str] = set()
    for ev in journal.get("events", []):
        if ev.get("kind") != "preserve":
            continue
        for ref in ev.get("refs", []):
            name = Path(str(ref)).name
            if name.endswith(".bin"):
                names.add(name[:-4].casefold())
        text = str(ev.get("text") or "")
        m = re.match(r"^([\w./-]+\.\w+)\s", text)
        if m:
            names.add(Path(m.group(1)).name.casefold())
    return names


def _find_report(task_id: str) -> Path | None:
    slug = task_id.rsplit("-", 1)[0]
    candidates = []
    for base in (JOURNAL_ROOT / task_id, HANDOFF / f"devin-{task_id}", HANDOFF / f"devin-{slug}"):
        for name in REPORT_NAMES:
            candidates.append(base / name)
    if not JOURNAL_ROOT.is_dir():
        return None
    for task_dir in sorted(JOURNAL_ROOT.iterdir()):
        if task_dir.name != task_id or not task_dir.is_dir():
            continue
        candidates.extend(task_dir.glob("*/REPORT.md"))
    for c in candidates:
        if c.is_file():
            return c
    return None


def _acceptance_details(report: Path, required: list[str] | None = None) -> dict:
    """Read only the bounded Acceptance section; duplicate IDs keep the worse state."""
    empty = {"acceptance": required or [], "blocked": [], "acceptanceStatus": "FAIL"}
    if report.stat().st_size > task_context.JOURNAL_LIMIT:
        return {**empty, "blocked": ["report-oversize"]}
    text = report.read_bytes().decode("utf-8-sig", errors="replace")
    lines = text.splitlines()
    start, level = None, None
    for i, ln in enumerate(lines):
        heading = re.match(r"^\s{0,3}(#{1,6})\s+(.+)", ln)
        if heading and ACC_HEAD_RE.search(heading.group(2)):
            start, level = i + 1, len(heading.group(1))
            break
    if start is None:
        return {**empty, "blocked": ["acceptance-section-missing"]}
    verdicts, reasons = {}, {}
    rank = {"PASS": 0, "NOT_RUN": 1, "HOLD": 2, "PENDING": 3,
            "NOT_REPRODUCED": 3, "BLOCKED": 3, "FAIL": 4, None: 4}
    for ln in lines[start:]:
        heading = re.match(r"^\s{0,3}(#{1,6})\s+", ln)
        if heading and len(heading.group(1)) <= level:
            break
        item = re.match(r"^\s*(?:\|\s*|[-*]\s*)?(?:\*\*)?([A-Z]{1,3}\d{1,2})\b", ln)
        if not item:
            continue
        aid = item.group(1)
        status_text = ln[item.end():]
        if ln.lstrip().startswith("|"):
            cells = [cell.strip().strip("*") for cell in status_text.split("|")]
            status_text = next((cell for cell in cells if STATUS_RE.fullmatch(cell)),
                               next((cell for cell in cells if STATUS_RE.match(cell)), ""))
            if status_text:
                status_text = " | ".join(cells[cells.index(status_text):])
        status_match = STATUS_RE.search(status_text)
        status = status_match.group(1) if status_match else None
        reason = status_text[status_match.end():].strip(" .:|-—\t") if status_match else ""
        if aid not in verdicts or rank[status] > rank[verdicts[aid]]:
            verdicts[aid] = status
            reasons[aid] = reason
        elif status == verdicts[aid] == "NOT_RUN" and len(reason) < len(reasons[aid]):
            reasons[aid] = reason
    wanted = list(dict.fromkeys(required if required is not None else verdicts))
    if not wanted:
        return {**empty, "blocked": ["acceptance-items-missing"]}
    blocked = []
    for aid in wanted:
        if aid not in verdicts:
            blocked.append(f"{aid}(missing)")
        elif verdicts[aid] not in ("PASS", "NOT_RUN", "HOLD"):
            blocked.append(f"{aid}({verdicts[aid] or 'no-status'})")
        elif verdicts[aid] == "NOT_RUN" and len(reasons[aid]) < 5:
            blocked.append(f"{aid}(NOT_RUN-reason-missing)")
    states = {verdicts.get(aid) for aid in wanted}
    status = "FAIL" if blocked else "HAS_HOLD" if "HOLD" in states else (
        "HAS_NOT_RUN" if "NOT_RUN" in states else "ALL_PASS")
    return {"acceptance": wanted, "blocked": blocked, "acceptanceStatus": status,
            "requiredAcceptanceBound": required is not None}


def _acceptance_status(report: Path, required: list[str] | None = None) -> tuple[list[str], list[str]]:
    detail = _acceptance_details(report, required)
    return detail["acceptance"], detail["blocked"]


def _test_evidence(task_dir: Path, journal: dict | None, root: Path = ROOT,
                   contract_path: Path | None = None, contract_sha256: str | None = None) -> dict:
    """References remain references; reuse the existing immutable code-evidence contract.

    Supported bundles contain run.json (run_verified_command) and evidence.json
    (agent_code_evidence_gate's run/evidence envelope). The caller separately pins
    the existing gate-owned contract. Unsupported/missing adapters fail soft.
    This reducer never runs commands or completion cleanup.
    """
    refs, verified, rejected = [], set(), []
    if task_dir.is_dir():
        for p in sorted(task_dir.rglob("*"))[:task_context.RECEIPT_LIMIT]:
            if not p.is_file():
                continue
            if re.search(r"(test|verify)", p.name, re.IGNORECASE) and p.suffix in (".log", ".txt", ".json"):
                refs.append(p.name)
            if p.name != "run.json":
                continue
            refs.append(p.relative_to(task_dir).as_posix())
            try:
                paths = [p, p.with_name("evidence.json")]
                for path in paths:
                    task_context.safe_path(root, path.relative_to(root).as_posix())
                    if path.stat().st_size > code_evidence.MAX_INPUT_BYTES:
                        raise ValueError("receipt-oversize")
                run = verified_command.load_run(p.parent)
                if contract_path is None or not contract_sha256:
                    raise ValueError("independent-contract-missing")
                contract_file = task_context.safe_path(root, contract_path.relative_to(root).as_posix())
                contract = code_evidence._parse_object(code_evidence._read_bounded(contract_file))
                for key in ("candidateRoot", "oraclePath"):
                    target = contract_file.parent / contract[key]
                    task_context.safe_path(root, target.relative_to(root).as_posix())
                envelope = code_evidence._parse_object(code_evidence._read_bounded(paths[1]))
                reduced = code_evidence.evaluate_file(paths[1], contract_path=contract_file,
                                                       contract_sha256=contract_sha256)
                if reduced["verdict"] != "PASS" or envelope.get("run") != run:
                    rejected.append({"receipt": p.relative_to(task_dir).as_posix(),
                                     "reason": "unverified", "reasonCodes": reduced["reasonCodes"]})
                    continue
                command = run.get("commandArgv")
                command_ok = (isinstance(command, list) and bool(command)
                              and all(isinstance(arg, str) and arg for arg in command)
                              and _sha256_text(json.dumps(command)) == run.get("commandSha256")
                              and run.get("cwd") == str(root.resolve()))
                bindings = []
                identities = run.get("sourceIdentity")
                if not isinstance(identities, list) or not identities:
                    raise ValueError("source-binding-missing")
                for row in identities:
                    if not isinstance(row, dict):
                        raise ValueError("invalid-source-binding")
                    name = Path(row["path"]).relative_to(root.resolve()).as_posix()
                    if row.get("kind") != "file":
                        raise ValueError("unsupported-source-binding")
                    bindings.append(task_context.binding(root, name, row.get("sha256")))
                results_ok = bool(run.get("resultFiles")) and all(
                    task_context.binding(p.parent, row.get("path", ""), row.get("sha256"))["currentApplicable"] is True
                    for row in run.get("resultFiles") or [])
                checked = task_context.evidence_row(root, p.name, "VERIFIED_PASS", run.get("exitCode"), bindings,
                    command_ok and results_ok, "run_verified_command")
                if checked["state"] == "VERIFIED_PASS":
                    verified.update(b["path"] for b in bindings)
                else:
                    rejected.append({"receipt": p.relative_to(task_dir).as_posix(),
                                     "reason": checked["state"], "reasonCodes": reduced["reasonCodes"]})
            except (OSError, ValueError, TypeError, KeyError, task_context.ContextError):
                rejected.append({"receipt": p.relative_to(task_dir).as_posix(), "reason": "unreadable-or-unbound"})
    if journal:
        for ev in journal.get("events", []):
            if ev.get("kind") == "verify":
                refs.append("journal-verify-event")
    return {"evidenceStatus": "VERIFIED" if verified else "REFERENCE_ONLY" if refs else "MISSING",
            "testEvidence": ",".join(sorted(set(refs))[:5]) or None,
            "verifiedScope": sorted(verified), "receiptDiagnostics": rejected[:5]}


def _server_up(base: str, timeout: float = 5.0) -> bool:
    try:
        with urllib.request.urlopen(base.rstrip("/") + "/chat", timeout=timeout) as resp:
            return resp.status == 200
    except Exception:
        return False


def _run_golden(root: Path, base: str, scenarios: list[str], max_sends: int,
                model: str | None, runner=None) -> dict:
    cmd = ["node", str(GOLDEN_JS), "--base", base, "--only", ",".join(scenarios),
           "--repeat", "1", "--max-sends", str(max_sends), "--selection-mode", "strict"]
    if model:
        cmd += ["--model", model]
    if runner is None:
        def runner(c):
            try:
                out = subprocess.run(c, cwd=root, capture_output=True, text=True,
                                     encoding="utf-8", errors="replace", timeout=600)
                return {"rc": out.returncode, "stdout": out.stdout, "stderr": out.stderr}
            except (OSError, subprocess.TimeoutExpired) as exc:
                return {"rc": 99, "stdout": "", "stderr": str(exc)}
    res = runner(cmd)
    line = (res.get("stdout") or "").strip().splitlines()
    payload = None
    for ln in reversed(line):
        ln = ln.strip()
        if ln.startswith("{"):
            try:
                payload = json.loads(ln)
                break
            except ValueError:
                continue
    return {"command": cmd, "returncode": res.get("rc"), "stderrTail": (res.get("stderr") or "")[-300:],
            "payload": payload}


def close_gate(task_id: str, *, root: Path = ROOT, golden: list[str] | None = None,
               max_sends: int = 4, model: str | None = None, report_override: Path | None = None,
               base: str = "http://127.0.0.1:18180", git: str = "git",
               leases=None, journal=None, changed=None, runner=None,
               required_acceptance: list[str] | None = None,
               evidence_contract: Path | None = None,
               evidence_contract_sha256: str | None = None) -> tuple[str, int, dict]:
    """⑤개 검사를 순서대로. 첫 실패에서 멈추지 않고 전부 기록하되 verdict는 첫 실패."""
    checks: dict[str, dict] = {}
    task_dir = JOURNAL_ROOT / task_id
    journal = journal if journal is not None else _load_journal(task_id)
    leases = leases if leases is not None else _load_leases()
    own_hash = _sha256_text(task_id)
    acceptance = {"acceptanceStatus": "FAIL", "requiredAcceptanceBound": False}
    evidence = _test_evidence(task_dir, journal, root,
        evidence_contract.resolve() if evidence_contract else None, evidence_contract_sha256)

    # ① declared-scope 변경분 checkpoint seal 확인
    scope = [str(p).replace("\\", "/") for p in (journal or {}).get("plannedScope", [])]
    changed = changed if changed is not None else _git_changed_paths(root, git)
    if changed is None:
        checks["1_checkpoint_seal"] = {"status": "FAIL", "reason": "git-diff-unavailable"}
    else:
        changed_scope = sorted(p for p in (s.casefold() for s in scope) if p in changed)
        sealed = _sealed_paths(task_dir, journal)
        preserved = _preserved_basenames(journal or {})
        unsealed = [p for p in changed_scope
                    if p not in sealed and Path(p).name not in preserved]
        checks["1_checkpoint_seal"] = (
            {"status": "PASS", "sealed": sealed, "changedScope": changed_scope}
            if not unsealed else
            {"status": "FAIL", "unsealed": unsealed,
             "hint": "codex_work_checkpoint.py begin --run <task>/cycle-NN --target <path> ... seal"})

    # ② REPORT Acceptance + 테스트 증거
    report = report_override if report_override else _find_report(task_id)
    if report is not None:
        report = report.resolve()
    if report is None or not report.is_file():
        checks["2_report_acceptance"] = {"status": "FAIL", "reason": "report-missing",
                                         "hint": "REPORT.md에 Acceptance A# PASS/NOT_RUN(사유) 기록"}
    else:
        acceptance = _acceptance_details(report, required_acceptance)
        status = "PASS" if not acceptance["blocked"] and evidence["testEvidence"] else "FAIL"
        checks["2_report_acceptance"] = {"status": status, "report": str(report.relative_to(root)),
                                          **acceptance, **evidence}

    # ③ golden 공통 합격 (지정 시에만)
    if golden:
        sends_cap = min(max_sends, GOLDEN_MAX_SENDS)
        if not _server_up(base):
            checks["3_golden"] = {"status": "NOT_RUN", "reason": f"server-unreachable:{base}"}
        else:
            gr = _run_golden(root, base, golden, sends_cap, model, runner=runner)
            payload = gr.get("payload") or {}
            results = payload.get("results") or []
            mismatched = [r for r in results if r.get("modelMatched") is False]
            failed = [r for r in results if r.get("verdict") not in ("PASS",)]
            missing = sorted(set(golden) - {r.get("id") for r in results})
            if not payload:
                checks["3_golden"] = {"status": "NOT_RUN", "reason": "golden-no-output",
                                      "rc": gr.get("returncode"), "stderr": gr.get("stderrTail")}
            elif mismatched:
                checks["3_golden"] = {"status": "FAIL", "reason": "model-mismatch",
                                      "rows": [{k: r.get(k) for k in ("id", "requestedModel", "observedModel")}
                                               for r in mismatched]}
            elif gr.get("returncode") != 0 or not results or missing:
                checks["3_golden"] = {"status": "FAIL", "reason": "golden-incomplete-or-nonzero",
                                      "missingCases": missing}
            elif failed:
                checks["3_golden"] = {"status": "FAIL", "reason": "golden-failed",
                                      "rows": [{k: r.get(k) for k in ("id", "verdict", "reasons")}
                                               for r in failed]}
            else:
                checks["3_golden"] = {"status": "PASS", "sends": payload.get("sends"),
                                      "results": [{k: r.get(k) for k in
                                                   ("id", "verdict", "requestedModel", "observedModel", "modelMatched")}
                                                  for r in results]}
            checks["3_golden"]["goldenRc"] = gr.get("returncode")

    # ④ 다른 세션 lease 경로 무변경
    if changed is None:
        checks["4_foreign_lease"] = {"status": "FAIL", "reason": "git-diff-unavailable"}
    else:
        foreign_paths: dict[str, str] = {}
        for lease in leases:
            if lease.get("taskIdHash") == own_hash:
                continue
            for p in lease.get("_targetPaths") or []:
                foreign_paths.setdefault(p, lease.get("_topic"))
        # 공유 dirty 트리에서는 외부 세션 자신의 미커밋 변경도 diff에 섞인다.
        # 귀속 증명이 불가하므로 FAIL은 "내 declared scope ∩ 외부 lease"로 한정하고,
        # 전체 diff ∩ 외부 lease는 증거 필드로만 노출한다.
        scope_cf = {s.casefold() for s in scope}
        my_targets = sorted(p for p in changed if p in foreign_paths and p in scope_cf)
        foreign_dirty = sorted(p for p in changed if p in foreign_paths and p not in scope_cf)
        own_topics = {l.get("_topic") for l in leases if l.get("taskIdHash") == own_hash}
        lock_writes = sorted(
            p for p in changed
            if p.startswith("__patch_drop__/source-edit-locks/")
            and p.split("/")[2] not in {f"{t}.lock" for t in own_topics if t})
        if my_targets or lock_writes:
            checks["4_foreign_lease"] = {"status": "FAIL",
                                         "myForeignLeaseWrites": [(p, foreign_paths[p]) for p in my_targets[:5]],
                                         "otherLockWrites": lock_writes[:5]}
        else:
            checks["4_foreign_lease"] = {"status": "PASS",
                                         "foreignLeasedDirty": [(p, foreign_paths[p]) for p in foreign_dirty[:5]],
                                         "note": "attribution-unprovable: foreign sessions' in-flight edits"}

    # ⑤ journal close + lease end
    j_status = (journal or {}).get("status")
    own = _own_lease(task_id, leases)
    ok5 = (journal is not None and j_status == "closed" and own is None)
    checks["5_journal_lease"] = {"status": "PASS" if ok5 else "FAIL",
                                 "journalStatus": j_status,
                                 "journalResult": (journal or {}).get("result"),
                                 "leaseActive": own.get("_topic") if own else None,
                                 "hint": "work_journal.py close + source_edit_session.ps1 -Action end"}

    order = ["1_checkpoint_seal", "2_report_acceptance", "3_golden",
             "4_foreign_lease", "5_journal_lease"]
    verdict, code = "PASS", 0
    for name in order:
        c = checks.get(name)
        if c is None:
            continue
        if c["status"] == "FAIL":
            verdict, code = "BLOCKED:" + name, 1
            break
        if c["status"] == "NOT_RUN" and verdict == "PASS":
            verdict, code = "NOT_RUN:" + str(c.get("reason", name)), 2
    safe = all(checks[name]["status"] == "PASS" for name in
               ("1_checkpoint_seal", "4_foreign_lease", "5_journal_lease"))
    return verdict, code, {"taskId": task_id, "checks": checks, "verdict": verdict,
        "closureStatus": "SAFE_CLOSED" if safe else "BLOCKED",
        "acceptanceStatus": acceptance["acceptanceStatus"], **evidence,
        "journalResult": (journal or {}).get("result"), "goalCompletion": "NOT_PROVEN",
        "goalCompletionReason": "exact-goal-read-only-validator-unavailable",
        "legacyMeaning": "PASS means this gate's closure conditions passed; it does not prove whole-goal success."}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    cs = sub.add_parser("check-start")
    cs.add_argument("--targets", required=True, help="lease manifest JSON 또는 줄 단위 경로 파일")
    cs.add_argument("--task", default=None)
    cl = sub.add_parser("close")
    cl.add_argument("--task", required=True)
    cl.add_argument("--golden", default=None, help="C1,C2 처럼 시나리오 csv")
    cl.add_argument("--max-sends", type=int, default=4)
    cl.add_argument("--model", default=None)
    cl.add_argument("--report", default=None)
    cl.add_argument("--required-acceptance", default=None, help="original required IDs, comma-separated")
    cl.add_argument("--evidence-contract", default=None, help="independent agent_code_evidence_gate contract")
    cl.add_argument("--evidence-contract-sha256", default=None, help="contract pin supplied by its owner")
    cl.add_argument("--base", default="http://127.0.0.1:18180")
    cl.add_argument("--git", default="git")
    cl.add_argument("--json", dest="json_out", default=None, help="JSON 결과 저장 경로")
    args = ap.parse_args(argv)

    if args.cmd == "check-start":
        verdict, code, detail = check_start(Path(args.targets), args.task)
        _write_utf8(verdict + "\n")
        _write_utf8(json.dumps({"verdict": verdict, **detail}, ensure_ascii=False) + "\n")
        return code

    golden = [s.strip().upper() for s in args.golden.split(",")] if args.golden else None
    verdict, code, detail = close_gate(
        args.task, golden=golden, max_sends=args.max_sends, model=args.model,
        report_override=Path(args.report) if args.report else None,
        base=args.base, git=args.git,
        required_acceptance=args.required_acceptance.split(",") if args.required_acceptance else None,
        evidence_contract=Path(args.evidence_contract) if args.evidence_contract else None,
        evidence_contract_sha256=args.evidence_contract_sha256)
    _write_utf8(verdict + "\n")
    payload = json.dumps(detail, ensure_ascii=False, indent=2)
    if args.json_out:
        Path(args.json_out).write_bytes(payload.encode("utf-8"))
    _write_utf8(payload + "\n")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
