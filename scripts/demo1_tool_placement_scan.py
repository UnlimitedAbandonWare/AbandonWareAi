#!/usr/bin/env python3
"""demo1_tool_placement_scan.py — 기존 도구 배치 스캐너 (tool placement advisor).

매 턴 ask + dirty + active journal + lease 상태를 보고, 이미 있는
script/bat/skill 호출만 순위화해 JSON으로 낸다. 새 라우터를 만들지 않는다:
스킬 해석은 demo1_vibe_skill_router.py resolve를 그대로 subprocess로 호출하고,
이 스캐너는 (a) router가 놓치는 trigger의 정답 타점, (b) 알려진 미스라우팅
교정, (c) 상태 기반 제안(stale journal/lease/dirty)만 얹는다.

지시서: agent-prompts/tool-placement-scan-20260926/brief.md
  ForceRestart meta display -> caption이 아니라 demo1-dev-reload / Start-RAG
  RAG debug trail           -> 스킬이 아니라 Read-RAG-Debug.bat -> LATEST.json
  SelfAsk ownership         -> OwnershipContractTest + canonical SelfAskPlanner
  commit dirty / goal done / verify all models
                            -> conditional_local_git / goal-complete-stop / spend-guard
  zombie journal            -> safe-cleanup이 아니라 work_journal list +
                              agent_recovery_status

Read-only: 어떤 파일/lease/journal도 변경하지 않고, 서버를 켜지/끄지 않는다.
Exit codes: 0 ok / 2 usage / 6 evidence-io.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.tool-placement-scan.v1"
SCRIPTS = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPTS.parent
BASE = "data/agent-handoff/codex-autonomy"
LOCKS_REL = "__patch_drop__/source-edit-locks"
JOURNAL_SCHEMA = "awx.work_journal.v1"
ROUTER_PY = SCRIPTS / "demo1_vibe_skill_router.py"

STALE_JOURNAL_HOURS = 24
MAX_SCAN = 512
MAX_DIRTY_SAMPLE = 10
MAX_PLACEMENTS = 12

# --- 정답 타점 테이블: 패턴이 ask에 매치되면 기존 호출을 그대로 순위화한다 ---
# match: 소문자 substring (router와 같은 규칙), "re:" 접두사는 regex.
# calls: (call, kind, why) — kind = skill|script|bat|ps1|file|test
# routerBeat: 이 rule이 교정하는 router primary skill 이름들.
TRIGGERS = [
    {
        "id": "dev-reload",
        "match": ["forcerestart", "force restart", "restart meta",
                  "meta display restart", "재시작", "재기동", "리로드",
                  "devwatch", "dev-reload", "live 반영"],
        "calls": [
            ("$demo1-dev-reload", "skill",
             "Java/Spring/Display 소스 변경 반영 절차 (compile+ForceRestart)"),
            ("Start-RAG.bat", "bat", "live 반영 시작점 — stale JVM 200은 증거 아님"),
            ("powershell -File scripts/dev_reload_watch.ps1 -MetaDisplay",
             "ps1", "DevWatch 자동 재빌드 관찰"),
        ],
        "routerBeat": ["demo1-meta-display-simple-caption"],
        "why": "ForceRestart/재기동 ask가 'meta display' 단어에 끌려 caption 스킬로"
               " 잘못 라우팅되는 실측 케이스 교정",
    },
    {
        "id": "rag-debug-trail",
        "match": ["rag debug", "debug trail", "rag launcher", "launcher 실패",
                  "read-rag-debug", "latest.json", "rag 기동", "부팅 실패",
                  "rag 디버그"],
        "calls": [
            ("Read-RAG-Debug.bat", "bat",
             "skill-free 첫 읽기 — failurePoint+evidencePaths+nextCommand"),
            ("powershell -File scripts/read_rag_debug_trail.ps1", "ps1",
             "동일 경로 직접 호출"),
            ("var/rag-launcher/LATEST.json", "file", "런처 상태 SSOT"),
        ],
        "routerBeat": ["demo1-evidence-debugging"],
        "why": "기동/디버그 조사는 스킬 조회 없이 Read-RAG-Debug가 정답 타점",
    },
    {
        "id": "selfask-ownership",
        "match": ["selfask", "self-ask", "selfaskplanner", "orphan planner",
                  "selfask ownership", "planner orphan"],
        "calls": [
            (".\\gradlew.bat test --tests "
             "com.example.lms.governance.SelfAskPlannerOwnershipContractTest",
             "test", "canonical 소유권 계약 테스트"),
            ("main/java/com/example/lms/service/rag/SelfAskPlanner.java",
             "file", "canonical SelfAskPlanner — archive/ZIP 이식 금지"),
            ("$demo1-rag-strategy-orchestration", "skill",
             "Self-Ask 전략 seam의 기존 지도"),
        ],
        "routerBeat": ["demo1-evidence-debugging"],
        "why": "SelfAsk 소유권 질문은 계약 테스트+canonical 파일이 정답 타점",
    },
    {
        "id": "zombie-journal",
        "match": ["zombie journal", "좀비 저널", "stale journal", "저널 정리",
                  "journal cleanup", "orphan journal", "in_progress 잔여",
                  "저널이 남", "journal left"],
        "calls": [
            ("python -B scripts/work_journal.py list --active", "script",
             "활성 journal 인벤토리 (in_progress는 미확인이지 done이 아님)"),
            ("python -B scripts/agent_recovery_status.py status", "script",
             "journal+lease+quarantine 후보를 한 번에"),
            ("python -B scripts/agent_scope_lease.py who", "script",
             "lease 소유자 조회"),
        ],
        "routerBeat": ["demo1-safe-cleanup"],
        "why": "좀비/stale journal은 디스크 클리너가 아니라 journal/recovery 도구",
    },
    {
        "id": "conditional-git",
        "match": ["commit dirty", "커밋", "local commit", "git 정리",
                  "commit해", "커밋해", "staged scan"],
        "calls": [
            ("$demo1-conditional-local-git", "skill",
             "허용 범위: status/diff + owned path selective add"),
            ("python -B scripts/conditional_local_git.py", "script",
             "조건부 로컬 git 게이트"),
            ("python -B scripts/agent_git_vibe_commit.py --repo . "
             "--path <owned> --message-file <file>", "script",
             "staged-blob 시크릿 스캔 통과 후 로컬 커밋 1건"),
        ],
        "routerBeat": [],
        "why": "commit dirty ask는 router null — 조건부 로컬 git 경로가 정답",
    },
    {
        "id": "goal-complete",
        "match": ["goal complete", "목표 완료", "목표 달성", "작업 완료",
                  "완료 선언", "done criteria", "goal done", "끝났"],
        "calls": [
            ("$demo1-goal-complete-stop", "skill",
             "수용 기준 충족 시 turn 종료 절차"),
            ("python -B scripts/work_journal.py close --task <taskId> "
             "--result verified --summary <한 줄>", "script",
             "자기 journal만 닫는다"),
        ],
        "routerBeat": [],
        "why": "목표 완료 ask는 router null — goal-complete-stop이 정답",
    },
    {
        "id": "spend-guard",
        "match": ["verify all models", "모델 전부", "전체 모델", "전체 테스트",
                  "paid model", "api 비용", "크레딧", "credit", "spend",
                  "유료 모델"],
        "calls": [
            ("$demo1-agent-api-spend-guard", "skill",
             "검증 루프의 API 크레딧 소각 차단"),
            (". .\\scripts\\agent_api_spend_guard.ps1", "ps1",
             "Assert-AgentSpendAllow dot-source 게이트"),
            ("유료 fanout 기본 허용 — AWX_AGENT_ALLOW_PAID_MODELS=0 만 차단",
             "ps1", "전체 모델 검증 요청도 spend guard 적용"),
        ],
        "routerBeat": [],
        "why": "verify all models ask는 router null — spend guard가 정답",
    },
    {
        "id": "lease-conflict",
        "match": ["lease conflict", "리스 충돌", "foreign lease", "lease 막힘",
                  "blocked by lease", "점유 중"],
        "calls": [
            ("python -B scripts/lease_conflict_autoflow.py plan "
             "--goal-files <paths> --task <taskId>", "script",
             "stale 자동 reclaim + live는 request-release"),
            ("python -B scripts/agent_scope_lease.py who", "script",
             "lease 소유자/상태"),
        ],
        "routerBeat": [],
        "why": "lease 충돌은 우회/복제가 아니라 autoflow plan이 정답",
    },
    {
        "id": "verify-status",
        "match": ["status rag", "verify rag", "서버 상태", "alive",
                  "health check", "살아있"],
        "calls": [
            ("Status-RAG.bat", "bat", "alive-only 읽기"),
            ("Verify-RAG.bat", "bat",
             "post-edit 판정 (exit 0/3/6, tool-ran과 target-verified 분리)"),
        ],
        "routerBeat": [],
        "why": "상태/검증 ask의 skill-free 진입점",
    },
    {
        "id": "multi-seam-brief",
        "match": ["multi-seam", "pasted brief", "devin brief", "orchestrat",
                  "브리프", "지시서 실행"],
        "calls": [
            ("python -B scripts/devin_task_orchestrate.py plan "
             "--brief-file <path>", "script",
             "독립 seam의 phase 시퀀싱 — 한 스킬에 몰지 않음"),
            ("$demo1-devin-source-orchestrator", "skill",
             "phase별 skill+tool 지도"),
        ],
        "routerBeat": [],
        "why": "멀티 seam 붙여넣기는 오케스트레이터가 정답",
    },
    {
        "id": "goal-next",
        "match": ["next goal", "다음 목표", "what next", "다음 작업",
                  "goal next"],
        "calls": [
            ("powershell -File scripts/goal_next.ps1 -Status", "ps1",
             "현재 목표 상태/다음 진입 읽기전용"),
        ],
        "routerBeat": [],
        "why": "다음 목표 질문은 goal_next.ps1 -Status",
    },
    {
        "id": "toolchain",
        "match": ["compile", "컴파일", "빌드 방법", "how to test",
                  "어떻게 테스트", "toolchain"],
        "calls": [
            ("$demo1-toolchain-auto-select", "skill",
             "in-repo 도구 탐지 -> 최소 도구 선택"),
            (".\\gradlew.bat :compileJava -x test", "test",
             "compile-only 경로"),
        ],
        "routerBeat": [],
        "why": "빌드/테스트 방법 ask는 toolchain 선택이 정답",
    },
    {
        "id": "work-ledger",
        "match": ["소스 수정", "패치", "edit source", "파일 변경",
                  "code change", "fix source"],
        "calls": [
            ("$demo1-work-ledger", "skill",
             "journal open -> checkpoint preimage -> verify -> status"),
            ("powershell -File __patch_drop__/source_edit_session.ps1 "
             "-Action begin -TargetManifest <targets.json>", "ps1",
             "소스/실행파일 대상 lease"),
            ("python -B scripts/codex_work_checkpoint.py begin --run "
             "<cycle> --target <path>", "script", "변경 전 preimage"),
        ],
        "routerBeat": [],
        "why": "소스 변경 ask는 work-ledger 절차가 선행",
    },
    {
        "id": "safe-cleanup",
        "match": ["safe cleanup", "디스크 정리", "disk pressure", "잔여물",
                  "pycache", "__pycache__"],
        "calls": [
            ("$demo1-safe-cleanup", "skill",
             "allowlist+WhatIf-first 클리너"),
            ("Safe-Cleanup.bat (WhatIf 기본)", "bat", "삭제 전 미리보기"),
        ],
        "routerBeat": [],
        "why": "실제 디스크 압박/잔여물은 safe-cleanup이 정답 (journal 문제가 아닐 때)",
    },
]

# 매 턴 상태 기반 baseline — ask 매치가 없어도 진입 절차는 항상 유효하다.
ENTRY_BASELINE = [
    ("python -B scripts/agent_preflight.py --root .", "script",
     "task entry: device bus + journals + leases + guard 상태 한 번에"),
    ("python -B scripts/work_journal.py list --active", "script",
     "활성 journal 확인 — in_progress는 done이 아님"),
]


def utcnow():
    return datetime.now(timezone.utc)


def parse_ts(value):
    try:
        stamp = datetime.fromisoformat(str(value or "").replace("Z", "+00:00"))
    except ValueError:
        return None
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=timezone.utc)
    return stamp


def _read_json(path: Path):
    try:
        return json.loads(path.read_bytes())
    except (OSError, ValueError):
        return None


def _norm(text):
    return re.sub(r"\s+", " ", (text or "").lower()).strip()


def _match_count(patterns, text):
    count = 0
    for pat in patterns or []:
        if not isinstance(pat, str):
            continue
        if pat.startswith("re:"):
            try:
                if re.search(pat[3:], text, re.IGNORECASE):
                    count += 1
            except re.error:
                continue
        elif pat.lower() in text:
            count += 1
    return count


# --- 상태 수집 (모두 read-only) ----------------------------------------------

def collect_journals(root: Path, agent: str):
    now = utcnow()
    base = root / BASE
    in_progress, stale = [], []
    owned = foreign = 0
    if base.is_dir():
        for path in sorted(base.glob("*/journal.json"))[:MAX_SCAN]:
            doc = _read_json(path)
            if not isinstance(doc, dict) or doc.get("schemaVersion") != JOURNAL_SCHEMA:
                continue
            if doc.get("status") != "in_progress":
                continue
            stamp = parse_ts(doc.get("updatedAtUtc"))
            idle = round((now - stamp).total_seconds() / 60, 1) if stamp else None
            row = {"taskId": doc.get("taskId"), "idleMinutes": idle}
            in_progress.append(row)
            if agent and doc.get("agent") == agent:
                owned += 1
            elif agent:
                foreign += 1
            if idle is not None and idle > STALE_JOURNAL_HOURS * 60:
                stale.append(row)
    return {"status": "ok", "inProgressCount": len(in_progress),
            "ownedInProgress": owned if agent else None,
            "foreignInProgress": foreign if agent else None,
            "staleCount": len(stale), "stale": stale[:10],
            "staleHours": STALE_JOURNAL_HOURS}


def collect_leases(root: Path):
    """lease.json 직접 읽기 — ps1 status 없이도 active/expired 판정 가능."""
    now = utcnow()
    lock_root = root / LOCKS_REL
    rows, active, expired, corrupt = [], 0, 0, 0
    if lock_root.is_dir():
        for lock_dir in sorted(lock_root.glob("*.lock"))[:MAX_SCAN]:
            doc = _read_json(lock_dir / "lease.json")
            if not isinstance(doc, dict):
                corrupt += 1
                continue
            exp = parse_ts(doc.get("expiresAtUtc") or doc.get("expiresAt"))
            status = "expired" if exp and exp <= now else "active"
            if status == "active":
                active += 1
            else:
                expired += 1
            rows.append({"topic": doc.get("topic") or lock_dir.name[:-5],
                         "status": status,
                         "expiresAtUtc": doc.get("expiresAtUtc"),
                         "targetCount": doc.get("targetCount")})
    return {"status": "ok", "activeCount": active, "expiredCount": expired,
            "corruptCount": corrupt, "topics": rows[:20]}


def collect_dirty(root: Path):
    cmd = ["git", "--no-optional-locks", "status", "--porcelain=v1", "--untracked-files=normal"]
    try:
        proc = subprocess.run(cmd, cwd=str(root), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=20)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"status": "unavailable", "reason": f"git-call-failed:{type(error).__name__}"}
    if proc.returncode != 0:
        return {"status": "unavailable", "reason": "git-status-nonzero"}
    paths = [line[3:].strip() for line in str(proc.stdout or "").splitlines()
             if line.strip()]
    return {"status": "ok", "count": len(paths), "sample": paths[:MAX_DIRTY_SAMPLE]}


def router_resolve(root: Path, ask: str):
    """기존 vibe router를 그대로 호출 — 재구현하지 않는다."""
    if not ROUTER_PY.is_file():
        return {"status": "unavailable", "reason": "router-missing"}
    cmd = [sys.executable, "-B", str(ROUTER_PY), "resolve",
           ask, "--root", str(root)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=40)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"status": "unavailable", "reason": f"router-call-failed:{error}"}
    for line in str(proc.stdout or "").splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                return {"status": "ok", "resolve": json.loads(line)}
            except ValueError:
                continue
    return {"status": "unavailable",
            "reason": f"router-json-missing(exit={proc.returncode})"}


# --- 순위화 -----------------------------------------------------------------

def rank(ask: str, state: dict, router: dict):
    text = _norm(ask)
    placements = []
    seen = set()

    def emit(rule_id, call, kind, why, corrects=None):
        key = (rule_id, call)
        if key in seen or len(placements) >= MAX_PLACEMENTS:
            return
        seen.add(key)
        placements.append({"rank": len(placements) + 1, "id": rule_id,
                           "call": call, "kind": kind, "why": why,
                           "corrects": corrects, "existing": True})

    # 1) ask 매치 rule (score desc, 테이블 순서 tiebreak)
    scored = []
    for order, rule in enumerate(TRIGGERS):
        score = _match_count(rule.get("match"), text)
        if score:
            scored.append((-score, order, rule))
    scored.sort(key=lambda item: (item[0], item[1]))
    matched_ids = []
    for _, _, rule in scored:
        matched_ids.append(rule["id"])
        for call, kind, why in rule["calls"]:
            emit(rule["id"], call, kind, why, corrects=rule.get("why"))

    # 2) 상태 기반 제안
    journals = state.get("journals") or {}
    if journals.get("staleCount"):
        emit("zombie-journal",
             "python -B scripts/agent_recovery_status.py status", "script",
             f"stale in_progress journal {journals['staleCount']}건 "
             f"(idle>{STALE_JOURNAL_HOURS}h) 감지")
    leases = state.get("leases") or {}
    if leases.get("expiredCount"):
        emit("lease-conflict",
             "python -B scripts/lease_conflict_autoflow.py reclaim --dry-run",
             "script", f"만료 lease {leases['expiredCount']}건 — dry-run 먼저")
    dirty = state.get("dirty") or {}
    if dirty.get("count") and "conditional-git" in matched_ids:
        emit("conditional-git",
             "python -B scripts/conditional_local_git.py status", "script",
             f"dirty {dirty['count']}경로 — owned path만 selective add")

    # 3) router 해석 자체를 placement로 포함 (호출만, 대체 아님)
    if ask:
        emit("skill-resolve",
             f'python -B scripts/demo1_vibe_skill_router.py resolve "{ask}"',
             "script", "ONE primary skill 해석은 router의 일 — 이 스캐너는 보조")

    # 4) baseline entry
    if state.get("enabled"):
        for call, kind, why in ENTRY_BASELINE:
            emit("entry-baseline", call, kind, why)

    # misroute 판정: 매치된 rule의 routerBeat에 router primary가 걸리면 교정
    router_primary = ((router or {}).get("resolve") or {}).get("primary")
    misroutes = []
    for _, _, rule in scored:
        beat = rule.get("routerBeat") or []
        if router_primary and router_primary in beat:
            misroutes.append({
                "rule": rule["id"], "routerPrimary": router_primary,
                "useInstead": [c for c, _, _ in rule["calls"]],
                "why": rule.get("why")})
    return placements, matched_ids, misroutes


def save_diag(root: Path, out: dict) -> str:
    diag = root / "var" / "diagnostics"
    diag.mkdir(parents=True, exist_ok=True)
    name = "tool-placement-scan-%s.json" % datetime.now(
        timezone.utc).strftime("%Y%m%d-%H%M%S")
    path = diag / name
    path.write_text(json.dumps(out, ensure_ascii=False, indent=1),
                    encoding="utf-8")
    return str(path)


def print_brief(out: dict, detail_path: str) -> None:
    ask = out.get("ask") or "(no ask)"
    if len(ask) > 60:
        ask = ask[:57] + "..."
    print('scan ask : "%s"' % ask)
    print("next     : %s" % (out.get("nextSingleCall") or "none"))
    tops = ", ".join(p["id"] for p in (out.get("placements") or [])[:3])
    print("plcmts   : %d (top: %s)" % (len(out.get("placements") or []),
                                       tops or "none"))
    mis = out.get("misroutes") or []
    print("misroute : %d%s" % (len(mis), (" " + "; ".join(
        "%s->%s" % (m.get("rule"), m.get("routerPrimary"))
        for m in mis[:2])) if mis else ""))
    state = out.get("state") or {}
    j = state.get("journals") or {}
    le = state.get("leases") or {}
    d = state.get("dirty") or {}
    print("state    : journals stale=%s leases expired=%s dirty=%s" % (
        j.get("staleCount", "n/a"), le.get("expiredCount", "n/a"),
        d.get("count", "n/a")))
    print("detail   : %s" % detail_path)


def cmd_scan(root: Path, args) -> int:
    ask = str(args.ask or "")
    state = {"enabled": not args.skip_state}
    if not args.skip_state:
        state["journals"] = collect_journals(root, args.agent or "")
        state["leases"] = collect_leases(root)
        state["dirty"] = ({"status": "skipped", "reason": "--no-git"}
                          if args.no_git else collect_dirty(root))
    router = ({"status": "skipped", "reason": "--no-router"}
              if args.no_router or not ask else router_resolve(root, ask))
    placements, matched, misroutes = rank(ask, state, router)
    out = {
        "schemaVersion": SCHEMA, "action": "scan", "root": str(root),
        "ask": ask[:300], "matchedTriggers": matched,
        "placements": placements,
        "nextSingleCall": placements[0]["call"] if placements else None,
        "misroutes": misroutes,
        "router": router, "state": state,
        "advisoryOnly": True,
        "note": "기존 도구 호출 순위화만 — 새 라우터/실행자가 아니다",
    }
    if args.json:
        print(json.dumps(out, ensure_ascii=True))
    else:
        print_brief(out, save_diag(root, out))
    return 0


def cmd_list_triggers() -> int:
    print(json.dumps({
        "schemaVersion": SCHEMA, "action": "list-triggers",
        "triggers": [{"id": r["id"], "match": r["match"],
                      "calls": [c for c, _, _ in r["calls"]],
                      "routerBeat": r.get("routerBeat") or []}
                     for r in TRIGGERS],
        "baseline": [c for c, _, _ in ENTRY_BASELINE],
    }, ensure_ascii=True))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", default="scan",
                        choices=("scan", "list-triggers"))
    parser.add_argument("ask", nargs="?", default="",
                        help="이번 턴 user ask 텍스트")
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    parser.add_argument("--agent", default="",
                        help="caller agent 이름 — journal owned/foreign 분리")
    parser.add_argument("--skip-state", action="store_true",
                        help="journal/lease/dirty 수집 생략 (테스트/오프라인)")
    parser.add_argument("--no-router", action="store_true",
                        help="router resolve subprocess 생략")
    parser.add_argument("--no-git", action="store_true",
                        help="git status --porcelain 수집 생략")
    parser.add_argument("--json", action="store_true",
                        help="전체 JSON을 stdout으로 (기본: brief+var/diagnostics 파일)")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        if args.action == "list-triggers":
            return cmd_list_triggers()
        return cmd_scan(root, args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                          "reason": f"evidence-io:{error}"}, ensure_ascii=True))
        return 6


if __name__ == "__main__":
    sys.exit(main())
