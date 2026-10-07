#!/usr/bin/env python3
"""codex_auto_unblock.py — D30~D33 자동 해제 판정 보조 도구 (읽기 전용, stdlib만).

Codex 목표 세션이 "되돌릴 수 있는 일"에서 멈추지 않게, 분류기 D30~D33이
지시하는 판정 근거를 수집한다. 어느 서브명령도 lease 해제·reclaim·쓰기를
하지 않는다.

서브명령:
  budget --ledger <dir> [--cap N]
      ledger(journal.json/report.md/handoff.json)에서 라이브 시도를
      "모델 도달"과 "모델 도달 전 실패"로 나눠 재집계하고 D30 조건을 판정
      -> JSON {allowed, extendBy, recountedUsed, observedUsed, cap, reasons}
  log-evidence --model <id|pattern> --since HH:MM --until HH:MM
               [--log-dir DIR] [--log-glob GLOB]
      launcher 로그에서 requestHash별 phase/terminal/final-response(문자 수)
      -> JSON {window, files, requests:[{hash, firstTs, lastTs, phases,
          finalResponse}]}. sessionId·토큰류는 출력하지 않는다.
  lease-wait --paths <p...> [--max-min auto|N] [--interval 60] [--dry-run]
             [--enqueue] [--task ID] [--locks-dir DIR] [--root DIR]
      겹침 lease가 풀릴 때까지 확인만 한다 -> JSON {result: free|stale|live|
      free_wait_turn, free, live, stale, waitedSeconds, waitedSec, budgetSec,
      budgetBasis, lastStatus, blockers}. 해제·reclaim은 절대 하지 않는다.
      --max-min auto(기본값)는 막은 lease의 상태로 예산을 정한다:
      finishing/releasePending+heartbeat 정상 → 상대 만료+5분, active → 20분,
      stale/orphan → 기다리지 않음. 전체 상한 60분. --enqueue는
      <locks-dir>/waiters/<대상sha12>/<UTC>-<task>.json 대기표로 같은 대상을
      기다리는 세션의 순번을 정한다(내 것만 생성·삭제). --task는 대기 중
      lease_conflict_autoflow.py heartbeat로 내 잠금을 갱신한다.
  superseded --ledger <dir>
      같은 첨부 접두어(예: codex-api3-stream-failed-*)의 더 새 ledger가
      완료를 남겼는지 -> JSON {superseded, by, byResult, passItems, checked}

Usage:
  python -B scripts/codex_auto_unblock.py <subcommand> ...
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
READ_MAX = 512 * 1024

# ---------------------------------------------------------------------------
# 공통: 텍스트 수집·마스킹
# ---------------------------------------------------------------------------
MASK_RES = [
    re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-]{6,}"),
    re.compile(r"(?i)(sessionId|sid|token|api[_-]?key)[=:]\s*['\"]?[\w.\-]{6,}"),
    re.compile(r"sk-[A-Za-z0-9_\-]{8,}"),
    re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
               r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
]


def mask(text):
    if not text:
        return text
    for rx in MASK_RES:
        text = rx.sub(lambda m: (m.group(1) + "***") if m.lastindex else "***",
                      text)
    return text


def ledger_texts(ledger):
    """ledger 디렉터리의 판정 재료 텍스트를 모은다 (저널+보고서+handoff+감사)."""
    ledger = Path(ledger)
    texts = []
    jf = ledger / "journal.json"
    if jf.is_file():
        try:
            j = json.loads(jf.read_text(encoding="utf-8", errors="replace")
                           [:READ_MAX])
            texts.append(str(j.get("purpose", "")))
            texts.append(str(j.get("result", "")))
            for ev in j.get("events", []):
                if isinstance(ev, dict):
                    texts.append(str(ev.get("text", "")))
        except ValueError:
            pass
    for pat in ("report.md", "final-report.md", "handoff.json",
                "blocked-audit*.json", "blocked-audit*.md", "plan.md"):
        for f in sorted(ledger.glob(pat)):
            try:
                if f.is_file() and f.stat().st_size <= READ_MAX:
                    texts.append(f.read_text(encoding="utf-8",
                                             errors="replace"))
            except OSError:
                continue
    return texts


# ---------------------------------------------------------------------------
# budget — D30 재집계·증액 판정
# ---------------------------------------------------------------------------
TALLY_RE = re.compile(
    r"(?:라이브|live|생성|generation|호출|calls?|attempt)[^\n]{0,24}?"
    r"(\d+)\s*/\s*(\d+)", re.IGNORECASE)
PRE_MODEL_RE = re.compile(
    r"SemanticRequestFingerprint|ClassNotFound|NoClassDefFound|"
    r"클래스\s*(로딩|누락)|DevWatch\s*재빌드|재빌드\s*중|"
    r"launcher.{0,12}(fail|already)|HTTP\s*500.{0,16}controller|"
    r"controller.{0,8}(전|이전)|pre.?model|모델 호출 전 실패|"
    r"모델 도달 전", re.IGNORECASE)
MULT_RE = re.compile(r"[x×]\s*(\d+)")
HARDCAP_RE = re.compile(
    r"hard[\s_-]?cap|절대\s*(증액|추가)\s*금지|증액\s*금지", re.IGNORECASE)
AUTH_FAIL_RE = re.compile(r"\b(401|403|429)\b")
EXTENDED_RE = re.compile(
    r"D30|auto.?extend|자동\s*증액|증액\s*적용|live-budget", re.IGNORECASE)


def _count_premodel(texts):
    fails = 0
    for t in texts:
        for line in t.splitlines():
            if PRE_MODEL_RE.search(line):
                m = MULT_RE.search(line)
                fails += int(m.group(1)) if m else 1
    return fails


def budget_eval(ledger, cap=10):
    texts = ledger_texts(ledger)
    blob = "\n".join(texts)
    observed, tally_cap = 0, None
    for m in TALLY_RE.finditer(blob):
        used, of = int(m.group(1)), int(m.group(2))
        if used > observed:
            observed, tally_cap = used, of
    cap = tally_cap or cap
    premfails = _count_premodel(texts)
    recounted = max(0, observed - premfails)

    reasons = []
    allowed, extend = False, 0
    if observed == 0:
        reasons.append("no-tally-observed")
    else:
        if HARDCAP_RE.search(blob):
            reasons.append("hard-cap")
        if AUTH_FAIL_RE.search(blob):
            reasons.append("auth-or-rate-fail-observed")
        if EXTENDED_RE.search(blob):
            reasons.append("already-extended")
        if reasons:
            pass  # 증액 금지 — partial 종료 근거
        elif recounted < cap:
            allowed = True
            reasons.append("recount-under-cap")
        else:
            allowed = True
            extend = max(2, cap // 2)
            reasons.append("extend-allowed")
    return {"allowed": allowed, "extendBy": extend,
            "recountedUsed": recounted, "observedUsed": observed,
            "cap": cap, "preModelFails": premfails, "reasons": reasons}


# ---------------------------------------------------------------------------
# log-evidence — D31 로그 증거 (requestHash별 phase/terminal/final-response)
# ---------------------------------------------------------------------------
TS_RE = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?)"
                   r"([+-]\d{2}:?\d{2}|Z)")
HASH_RE = re.compile(r"hash:([0-9a-fA-F]{6,})")
# requestHash가 없는 줄(rag-pipeline final-response 등)은 [chat-NN <uuid>]
# 스레드 id로 같은 요청에 붙인다.
BRACKET_UUID_RE = re.compile(
    r"\[[^\]]*?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12})[^\]]*\]")
PHASE_RE = re.compile(r"phase=(\w+)")
HTTP_RE = re.compile(r"httpStatus=(\d+)")
STAGE_RE = re.compile(r"stage=([\w-]+)")
CHARS_RE = re.compile(r"characters=(\d+)")
EVID_RE = re.compile(r"evidenceCount=(\d+)")
ELAPSED_RE = re.compile(r"elapsedMs=(\d+)")


def _ts_kst(line):
    m = TS_RE.search(line)
    if not m:
        return None
    raw, tz = m.group(1), m.group(2)
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            dt = datetime.strptime(raw, fmt)
            break
        except ValueError:
            continue
    else:
        return None
    if tz == "Z":
        off = timezone.utc
    else:
        sign = 1 if tz[0] == "+" else -1
        digits = tz[1:].replace(":", "")
        off = timezone(sign * timedelta(hours=int(digits[:2]),
                                      minutes=int(digits[2:])))
    return dt.replace(tzinfo=off).astimezone(KST)


def _in_window(dt, since, until):
    hm = dt.strftime("%H:%M")
    if since <= until:
        return since <= hm <= until
    return hm >= since or hm <= until  # 자정 넘김


def log_evidence(log_root, model="", since="00:00", until="23:59",
                 log_glob="chat-ui-vibe-listener-*.out.log"):
    root = Path(log_root)
    model_re = re.compile(model, re.IGNORECASE) if model else None
    groups, files, uuid_to_hash = {}, [], {}
    for f in sorted(root.glob("**/" + log_glob)):
        try:
            if f.stat().st_size > 200 * 1024 * 1024:
                continue
            lines = f.read_text(encoding="utf-8", errors="replace"
                                ).splitlines()
        except OSError:
            continue
        used = False
        for line in lines:
            if "[plan9-" not in line and "[rag-pipeline]" not in line \
                    and "requestHash" not in line:
                continue
            dt = _ts_kst(line)
            if dt is None or not _in_window(dt, since, until):
                continue
            hm = HASH_RE.search(line)
            um = BRACKET_UUID_RE.search(line)
            if hm:
                h = hm.group(1)
                if um:
                    uuid_to_hash.setdefault(um.group(1).lower(), h)
            elif um and um.group(1).lower() in uuid_to_hash:
                h = uuid_to_hash[um.group(1).lower()]
            elif um:
                h = "uuid:" + um.group(1)[:8]
            else:
                continue
            g = groups.setdefault(h, {"hash": h, "firstTs": None,
                                      "lastTs": None, "phases": [],
                                      "finalResponse": None,
                                      "_modelHit": False})
            if model_re and (model_re.search(line)
                             or model_re.search(h)):
                g["_modelHit"] = True
            ts = dt.strftime("%H:%M:%S")
            if g["firstTs"] is None or ts < g["firstTs"]:
                g["firstTs"] = ts
            if g["lastTs"] is None or ts > g["lastTs"]:
                g["lastTs"] = ts
            pm = PHASE_RE.search(line)
            if pm:
                entry = {"phase": pm.group(1), "ts": ts}
                sm = HTTP_RE.search(line)
                if sm:
                    entry["httpStatus"] = int(sm.group(1))
                em = ELAPSED_RE.search(line)
                if em:
                    entry["elapsedMs"] = int(em.group(1))
                g["phases"].append(entry)
            sm = STAGE_RE.search(line)
            if sm and sm.group(1) == "final-response":
                fr = {"stage": "final-response", "ts": ts}
                cm = CHARS_RE.search(line)
                if cm:
                    fr["characters"] = int(cm.group(1))
                vm = EVID_RE.search(line)
                if vm:
                    fr["evidenceCount"] = int(vm.group(1))
                g["finalResponse"] = fr
            used = True
        if used:
            files.append(str(f))
    reqs = [g for g in groups.values()
            if model_re is None or g["_modelHit"]]
    for g in reqs:
        g.pop("_modelHit", None)
    return {"window": {"since": since, "until": until, "model": model},
            "files": files,
            "requests": sorted(reqs, key=lambda g: g["firstTs"] or "")}


# ---------------------------------------------------------------------------
# lease-wait — D32 겹침 lease 대기 (읽기 전용)
# ---------------------------------------------------------------------------
def _norm(p):
    return str(p).replace("\\", "/").strip("/").casefold()


def _overlaps(target, mine):
    t = _norm(target)
    for m in mine:
        m = _norm(m)
        if t == m or t.startswith(m + "/") or m.startswith(t + "/"):
            return True
    return False


def lease_scan(locks_dir, paths):
    locks = Path(locks_dir)
    live, stale, free = [], [], []
    if locks.is_dir():
        now = datetime.now(timezone.utc)
        for lock in sorted(locks.glob("*.lock")):
            jf = lock / "lease.json"
            try:
                j = json.loads(jf.read_text(encoding="utf-8",
                                            errors="replace"))
            except (OSError, ValueError):
                live.append({"topic": lock.stem, "lifecycle": "orphan"})
                continue
            targets = [str(t) for t in
                       (j.get("targetPaths") or j.get("targets") or [])]
            if not any(_overlaps(t, paths) for t in targets):
                continue
            topic = j.get("topic") or lock.stem
            try:
                exp = datetime.fromisoformat(
                    str(j.get("expiresAtUtc", "")).replace("Z", "+00:00"))
                expired = exp <= now
            except ValueError:
                expired = False
            row = {"topic": topic,
                   "expiresAtUtc": j.get("expiresAtUtc", "")}
            if j.get("status") != "active":
                row["lifecycle"] = "stale"
                stale.append(row)
            elif expired:
                row["lifecycle"] = "stale"
                stale.append(row)
            else:
                row["lifecycle"] = "live"
                live.append(row)
    result = "live" if live else ("stale" if stale else "free")
    if result == "free":
        free = [str(p) for p in paths]
    return {"result": result, "free": free, "live": live, "stale": stale}


WAITERS_DIRNAME = "waiters"
AUTO_BUDGET_ACTIVE_SEC = 20 * 60        # live+heartbeat 정상 기본 대기
AUTO_BUDGET_FINISHING_PAD_SEC = 5 * 60  # finishing: 상대 만료 이후 버퍼
AUTO_BUDGET_MAX_SEC = 60 * 60           # 전체 상한
LEASE_WAIT_CMD = ("python -B scripts/codex_auto_unblock.py lease-wait "
                  "--paths {paths} --max-min auto --enqueue --task {task}")


def _parse_utc(value):
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def _scan_authoritative(root, paths, task=None):
    """lease_conflict_autoflow의 권위 분류 (heartbeat 연장·finishing·
    releasePending 반영). 실패 시 None -> 호출자가 경량 lease_scan으로 폴백."""
    try:
        scripts_dir = str(Path(__file__).resolve().parent)
        if scripts_dir not in sys.path:
            sys.path.insert(0, scripts_dir)
        import lease_conflict_autoflow as autoflow
    except ImportError:
        return None
    try:
        targets = [autoflow.scope.canon(p) for p in paths]
        rep = autoflow.scan_report(Path(root).resolve(), targets,
                                   my_task=task)
    except Exception:
        return None
    live, stale = [], []
    for row in rep.get("overlappingLeases", []):
        item = {"topic": row.get("topic"), "leaseId": row.get("leaseId"),
                "ownerTaskId": row.get("ownerTaskId"),
                "status": row.get("status"),
                "lifecycle": row.get("lifecycle"),
                "expiresAtUtc": row.get("expiresAtUtc"),
                "heartbeatState": row.get("heartbeatState"),
                "releasePending": row.get("releasePending")}
        (live if row.get("lifecycle") == "live" else stale).append(item)
    result = "live" if live else ("stale" if stale else "free")
    return {"result": result, "free": rep.get("freeTargets", []),
            "live": live, "stale": stale}


def _row_budget(row, now):
    """막은 lease 한 줄의 대기 예산(초, 근거)."""
    if row.get("lifecycle") != "live":
        return 0, "stale_skip"
    finishing = (row.get("status") == "finishing"
                 or bool(row.get("releasePending")))
    if finishing and row.get("heartbeatState") == "valid":
        exp = _parse_utc(row.get("expiresAtUtc"))
        if exp is not None:
            return (min(AUTO_BUDGET_MAX_SEC,
                        max(0, int((exp - now).total_seconds()))
                        + AUTO_BUDGET_FINISHING_PAD_SEC),
                    "finishing_ttl")
    return AUTO_BUDGET_ACTIVE_SEC, "active_default"


def _auto_budget(live_rows, now):
    """여러 lease가 막으면 가장 긴 예산. 전체 상한 AUTO_BUDGET_MAX_SEC."""
    best_sec, best_basis = 0, "stale_skip"
    for row in live_rows:
        sec, basis = _row_budget(row, now)
        row["budgetSec"], row["budgetBasis"] = sec, basis
        if sec > best_sec:
            best_sec, best_basis = sec, basis
    return min(best_sec, AUTO_BUDGET_MAX_SEC), best_basis


def _waiter_dir(locks_dir, paths):
    key = hashlib.sha256("\n".join(sorted(_norm(p) for p in paths))
                         .encode("utf-8")).hexdigest()[:12]
    return Path(locks_dir) / WAITERS_DIRNAME / key


def _waiter_ticket(locks_dir, paths, task, budget_sec):
    """내 대기표만 생성. 반환: Path(실패 시 None)."""
    now = datetime.now(timezone.utc)
    safe_task = re.sub(r"[^A-Za-z0-9_.-]+", "-", task or "anon")
    d = _waiter_dir(locks_dir, paths)
    try:
        d.mkdir(parents=True, exist_ok=True)
        ticket = d / (now.strftime("%Y%m%dT%H%M%SZ") + "-" + safe_task
                      + ".json")
        payload = {"schemaVersion": "awx.lease-wait-waiter.v1",
                   "task": task or "anon",
                   "targets": sorted(_norm(p) for p in paths),
                   "createdAtUtc": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
                   "expiresAtUtc": (now + timedelta(seconds=budget_sec))
                   .strftime("%Y-%m-%dT%H:%M:%SZ")}
        ticket.write_text(json.dumps(payload, ensure_ascii=False,
                                     indent=2), encoding="utf-8")
        return ticket
    except OSError:
        return None


def _waiter_turn_mine(my_ticket, now=None):
    """가장 이른 만료 안 된 대기표가 내 것이면 True."""
    now = now or datetime.now(timezone.utc)
    d = my_ticket.parent
    try:
        tickets = sorted(d.glob("*.json"))
    except OSError:
        return True
    my_created = None
    try:
        my_created = json.loads(my_ticket.read_text(
            encoding="utf-8", errors="replace")).get("createdAtUtc")
    except (OSError, ValueError):
        pass
    earliest, earliest_name = None, None
    for t in tickets:
        try:
            d2 = json.loads(t.read_text(encoding="utf-8", errors="replace"))
        except (OSError, ValueError):
            continue
        exp = _parse_utc(d2.get("expiresAtUtc"))
        if exp is not None and exp <= now:
            continue  # 만료된 대기표는 건너뜀
        created = _parse_utc(d2.get("createdAtUtc"))
        if created is None:
            continue
        if earliest is None or (created, t.name) < (earliest, earliest_name):
            earliest, earliest_name = created, t.name
    if earliest is None:
        return True  # 유효 대기표가 내 것밖에 없음
    if my_created is None:
        return False
    return (earliest_name == my_ticket.name)


def _default_heartbeat(task, root):
    """대기 중 내 lease 만료 방지: autoflow heartbeat 1회 호출."""
    if not task:
        return None
    try:
        proc = subprocess.run(
            [sys.executable, "-B",
             str(Path(root) / "scripts" / "lease_conflict_autoflow.py"),
             "heartbeat", "--task", task],
            cwd=str(root), capture_output=True, text=True, timeout=30)
        return proc.returncode
    except (OSError, subprocess.SubprocessError):
        return None


def lease_wait(paths, locks_dir, max_min="auto", interval=60, dry_run=False,
               enqueue=False, task=None, root=None, now_fn=None, sleep_fn=None,
               heartbeat_fn=None, scanner=None, authoritative=True,
               wall_now_fn=None):
    """겹침 lease가 풀릴 때까지 기다린다. 읽기 전용(+--enqueue 시 내 대기표만).
    max_min: "auto"면 막은 lease 상태로 예산 계산, 숫자면 기존 고정값.
    authoritative+root면 heartbeat·finishing을 반영하는 권위 분류로 스캔하고
    실패 시 경량 lease_scan으로 폴백한다."""
    now_fn = now_fn or time.time
    sleep_fn = sleep_fn or time.sleep
    wall_now_fn = wall_now_fn or (lambda: datetime.now(timezone.utc))
    if heartbeat_fn is None and task and not dry_run:
        hb_root = Path(root or ".").resolve()
        heartbeat_fn = lambda: _default_heartbeat(task, hb_root)
    if scanner is None:
        if root is not None and authoritative:
            def scanner(p):
                return (_scan_authoritative(root, p, task=task)
                        or lease_scan(locks_dir, p))
        else:
            scanner = lambda p: lease_scan(locks_dir, p)

    started = now_fn()
    ticket = None
    if enqueue and not dry_run:
        # 예산은 첫 스캔 뒤 알지만, 순번은 대기 시작 시점부터 잡아야 하므로
        # 잠정 예산(상한)으로 만들고 실제 만료는 첫 스캔 뒤 갱신하지 않는다.
        ticket = _waiter_ticket(locks_dir, paths, task, AUTO_BUDGET_MAX_SEC)
    try:
        budget_sec, basis = None, None
        while True:
            rep = scanner(paths)
            if budget_sec is None:
                if isinstance(max_min, str) and max_min.strip().lower() == "auto":
                    budget_sec, basis = _auto_budget(
                        rep.get("live", []), wall_now_fn())
                else:
                    budget_sec = int(float(max_min) * 60)
                    basis = "fixed"
                budget_sec = min(budget_sec, AUTO_BUDGET_MAX_SEC)
            waited = int(now_fn() - started)
            rep.update({"waitedSec": waited, "waitedSeconds": waited,
                        "budgetSec": budget_sec, "budgetBasis": basis,
                        "lastStatus": rep["result"],
                        "blockers": rep.get("live") or rep.get("stale", [])})
            if dry_run or rep["result"] == "stale":
                return rep
            if rep["result"] == "free":
                if enqueue and ticket is not None \
                        and not _waiter_turn_mine(ticket, now=wall_now_fn()):
                    rep["result"] = rep["lastStatus"] = "free_wait_turn"
                else:
                    rep["result"] = rep["lastStatus"] = "free"
                    return rep
            if now_fn() >= started + budget_sec:
                return rep
            if heartbeat_fn is not None:
                heartbeat_fn()
            sleep_fn(interval)
    finally:
        if ticket is not None:
            try:
                ticket.unlink(missing_ok=True)
            except OSError:
                pass


# ---------------------------------------------------------------------------
# superseded — D33 같은 접두어의 더 새 ledger 완료 여부
# ---------------------------------------------------------------------------
TASK_SUFFIX_RE = re.compile(r"-[0-9a-f]{6,10}$", re.IGNORECASE)
VERIFIED_RE = re.compile(r"verif|pass|done|complete|성공|완료", re.IGNORECASE)
PASS_ITEM_RE = re.compile(r"\bA(\d{1,2})\b[^\n]{0,40}\bPASS\b|\bPASS\b[^\n]{0,40}\bA(\d{1,2})\b")


def _journal(path):
    try:
        return json.loads(path.read_text(encoding="utf-8",
                                         errors="replace")[:READ_MAX])
    except (OSError, ValueError):
        return None


def _pass_items(d):
    items = set()
    texts = []
    rf = d / "report.md"
    if rf.is_file():
        try:
            texts.append(rf.read_text(encoding="utf-8", errors="replace")
                         [:READ_MAX])
        except OSError:
            pass
    j = _journal(d / "journal.json")
    if j:
        texts.extend(str(e.get("text", "")) for e in j.get("events", [])
                     if isinstance(e, dict))
    for t in texts:
        for m in PASS_ITEM_RE.finditer(t):
            items.add("A" + (m.group(1) or m.group(2)))
    return sorted(items, key=lambda x: int(x[1:]))


def superseded_eval(ledger):
    ledger = Path(ledger)
    mine = _journal(ledger / "journal.json") or {}
    my_id = mine.get("taskId") or ledger.name
    prefix = TASK_SUFFIX_RE.sub("", my_id)
    roots = {ledger.parent}
    for extra in (ledger.parent / "codex-autonomy",
                  ledger.parent.parent / "codex-autonomy"):
        if extra.is_dir():
            roots.add(extra)
    checked, candidates = [], []
    for root in roots:
        for d in sorted(root.iterdir() if root.is_dir() else []):
            if not d.is_dir() or d == ledger:
                continue
            j = _journal(d / "journal.json")
            if not j:
                continue
            tid = j.get("taskId") or d.name
            checked.append(tid)
            if TASK_SUFFIX_RE.sub("", tid) != prefix or tid == my_id:
                continue
            if str(j.get("updatedAtUtc", "")) <= str(
                    mine.get("updatedAtUtc", "")):
                continue
            candidates.append((tid, j, d))
    done = [c for c in candidates
            if VERIFIED_RE.search(str(c[1].get("result") or ""))]
    if not done:
        return {"superseded": False, "by": None, "byResult": None,
                "passItems": [], "checked": sorted(checked),
                "candidates": [c[0] for c in candidates]}
    tid, j, d = done[0]
    return {"superseded": True, "by": tid,
            "byResult": j.get("result"),
            "passItems": _pass_items(d),
            "checked": sorted(checked),
            "candidates": [c[0] for c in candidates]}


# ---------------------------------------------------------------------------
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=
                                 argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("budget", help="D30 라이브 상한 재집계·증액 판정")
    b.add_argument("--ledger", required=True)
    b.add_argument("--cap", type=int, default=10)

    le = sub.add_parser("log-evidence",
                        help="D31 requestHash별 phase/final-response")
    le.add_argument("--model", default="",
                    help="모델 id 또는 requestHash 패턴 (정규식)")
    le.add_argument("--since", required=True, help="HH:MM (KST)")
    le.add_argument("--until", required=True, help="HH:MM (KST)")
    le.add_argument("--log-dir", default="var/rag-launcher")
    le.add_argument("--log-glob",
                    default="chat-ui-vibe-listener-*.out.log")

    lw = sub.add_parser("lease-wait",
                        help="D32 겹침 lease 대기 (읽기 전용)")
    lw.add_argument("--paths", nargs="+", required=True)
    lw.add_argument("--max-min", default="auto",
                    help="auto(기본, 상대 상태로 예산) 또는 숫자 분")
    lw.add_argument("--interval", type=int, default=60)
    lw.add_argument("--dry-run", action="store_true")
    lw.add_argument("--enqueue", action="store_true",
                    help="대기표를 만들어 같은 대상 대기 세션과 순번 조정")
    lw.add_argument("--task", default=None,
                    help="내 taskId — 대기 중 heartbeat 갱신·대기표 기록")
    lw.add_argument("--locks-dir", default=None,
                    help="기본 <root>/__patch_drop__/source-edit-locks; "
                         "명시하면 그 폴더만 경량 스캔")
    lw.add_argument("--root", default=".",
                    help="repo root — 권위 lease 분류(heartbeat 반영)용")

    sp = sub.add_parser("superseded",
                        help="D33 더 새 ledger 완료 여부")
    sp.add_argument("--ledger", required=True)

    args = ap.parse_args(argv)
    if args.cmd == "budget":
        out = budget_eval(args.ledger, args.cap)
    elif args.cmd == "log-evidence":
        out = log_evidence(args.log_dir, args.model, args.since,
                           args.until, args.log_glob)
    elif args.cmd == "lease-wait":
        locks_dir = args.locks_dir or str(
            Path(args.root) / "__patch_drop__" / "source-edit-locks")
        out = lease_wait(args.paths, locks_dir, args.max_min,
                         args.interval, args.dry_run, enqueue=args.enqueue,
                         task=args.task, root=args.root,
                         authoritative=args.locks_dir is None)
    else:
        out = superseded_eval(args.ledger)
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
