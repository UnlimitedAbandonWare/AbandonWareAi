#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""codex_goal_autoclose — 미완료 goal 충돌 사전 진단/해소 권장 페이로드 생성.

배경: 코덱스 `create_goal`은 동일 스레드에 미완료 goal이 남아 있으면
`cannot create a new goal because this thread has an unfinished goal`로
즉시 실패한다. 이 도구는 goals_1.sqlite 를 **읽기 전용**으로 열어
현재 미완료 goal을 보여 주고, 닫기용 `update_goal` 권장 페이로드를 출력한다.

DB 기본 경로: %USERPROFILE%\\.codex\\goals_1.sqlite (`--db`로 재지정 가능).
잠금 안전: `file:...?mode=ro` URI로 열어 메인 프로세스와 락 충돌을 피하고,
'database is locked' 시 짧게 재시도한다. 쓰기는 절대 하지 않는다.

용법:
  python -B scripts/codex_goal_autoclose.py --status          # 현황 진단
  python -B scripts/codex_goal_autoclose.py --suggest-close   # 닫기 페이로드
  python -B scripts/codex_goal_autoclose.py --status --thread <id>
  python -B scripts/codex_goal_autoclose.py --test            # 자체점검
종료코드: 0=정상(미완료 0개 포함), 3=DB 잠금/읽기 실패, 2=인자 오류.
"""
import argparse
import json
import os
import sqlite3
import sys
import tempfile
import time
from pathlib import Path

# '완료'로 간주하는 상태 — 관찰된 값: active/paused/blocked/usage_limited/complete
_FINISHED = {"complete", "completed", "failed", "cancelled", "canceled", "done"}


def _default_db():
    return Path(os.environ.get("USERPROFILE", str(Path.home()))) / ".codex" / "goals_1.sqlite"


def _connect_ro(path):
    uri = "file:%s?mode=ro" % str(path).replace("\\", "/")
    last = None
    for _ in range(5):  # 잠깐의 락은 재시도로 흡수
        try:
            return sqlite3.connect(uri, uri=True)
        except sqlite3.OperationalError as e:
            last = e
            time.sleep(0.15)
    raise last


def _fetch_goals(conn, thread=None):
    where = ""
    params = ()
    if thread:
        where = "WHERE thread_id = ?"
        params = (thread,)
    rows = conn.execute(
        "SELECT thread_id, goal_id, objective, status, updated_at_ms "
        "FROM thread_goals %s ORDER BY updated_at_ms DESC" % where,
        params,
    ).fetchall()
    return rows


def _classify(rows):
    unfinished = [r for r in rows if (r[3] or "").lower() not in _FINISHED]
    counts = {}
    for r in rows:
        counts[r[3]] = counts.get(r[3], 0) + 1
    return counts, unfinished


def _report_status(db_path, rows, counts, unfinished):
    print(json.dumps({
        "schema": "awx.codex-goal-status.v1",
        "dbPath": str(db_path),
        "dbExists": True,
        "totalGoals": len(rows),
        "statusCounts": counts,
        "unfinishedCount": len(unfinished),
        "unfinished": [
            {
                "goalId": r[1],
                "threadId": r[0],
                "status": r[3],
                "objectivePreview": (r[2] or "")[:80],
                "updatedAtMs": r[4],
            }
            for r in unfinished
        ],
        "hint": ("if unfinished goals exist, close them via update_goal "
                 "before calling create_goal to avoid the conflict"),
    }, ensure_ascii=False, indent=2))


def _report_suggest(unfinished):
    # 코덱스 쪽에서 그대로 치환해 쓸 수 있는 권장 update_goal 페이로드
    print(json.dumps({
        "schema": "awx.codex-goal-autoclose.v1",
        "unfinishedCount": len(unfinished),
        "suggestedCalls": [
            {
                "tool": "update_goal",
                "arguments": {"status": "complete"},
                "targetGoalId": r[1],
                "targetThreadId": r[0],
                "currentStatus": r[3],
                "note": ("close the thread's open goal as 'complete', then retry "
                         "create_goal; translate to 'completed' if the client enum differs"),
            }
            for r in unfinished
        ],
    }, ensure_ascii=False, indent=2))


def _self_test(tmp_path):
    """임시 sqlite로 정상/잠금/부재 경로를 검증한다."""
    db = tmp_path / "goals_test.sqlite"
    conn = sqlite3.connect(str(db))
    conn.execute(
        "CREATE TABLE thread_goals(thread_id TEXT, goal_id TEXT, objective TEXT, "
        "status TEXT, token_budget INTEGER, tokens_used INTEGER, "
        "time_used_seconds INTEGER, created_at_ms INTEGER, updated_at_ms INTEGER)")
    conn.executemany(
        "INSERT INTO thread_goals VALUES(?,?,?,?,?,?,?,?,?)",
        [("t1", "g1", "obj one", "active", 0, 0, 0, 1, 10),
         ("t1", "g2", "obj two", "complete", 0, 0, 0, 1, 20),
         ("t2", "g3", "obj three", "blocked", 0, 0, 0, 1, 30)])
    conn.commit()
    conn.close()

    conn = _connect_ro(db)
    rows = _fetch_goals(conn)
    counts, unfinished = _classify(rows)
    assert len(rows) == 3 and len(unfinished) == 2, "fetch/classify mismatch"
    assert unfinished[0][1] == "g3", "ordering by updated_at_ms desc failed"
    conn.close()
    # 잠금 상태에서도 ro 읽기가 성공해야 함 (WAL 아니어도 읽기 잠금 공유)
    locker = sqlite3.connect(str(db))
    locker.execute("BEGIN EXCLUSIVE")
    try:
        conn = _connect_ro(db)
        try:
            _fetch_goals(conn)
        finally:
            conn.close()  # connect는 lazy — 쿼리 실패해도 핸들 반납 필수(Win32 잔류 잠금)
    except sqlite3.OperationalError:
        pass  # 배타 잠금 중 ro 실패는 허용 — 재시도 로직 존재 확인이 목적
    finally:
        locker.close()
    print("codex_goal_autoclose self-test: pass")
    return 0


def _utf8_stdio():
    # cp949 콘솔에서 한글 포함 JSON 출력이 깨지지 않게 한다
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main(argv=None):
    _utf8_stdio()
    ap = argparse.ArgumentParser(
        description="코덱스 미완료 goal 진단 및 닫기 권장 페이로드 생성 (읽기 전용).")
    ap.add_argument("--db", help="goals sqlite 경로 (기본 %USERPROFILE%\\.codex\\goals_1.sqlite)")
    ap.add_argument("--thread", help="특정 thread_id만 조회")
    ap.add_argument("--status", action="store_true", help="미완료 goal 현황 출력")
    ap.add_argument("--suggest-close", action="store_true", dest="suggest_close",
                    help="update_goal 권장 페이로드 출력")
    ap.add_argument("--test", action="store_true", help="자체점검")
    args = ap.parse_args(argv)

    if args.test:
        td = Path(tempfile.mkdtemp(prefix="cga-test-"))
        try:
            return _self_test(td)
        finally:
            import shutil
            shutil.rmtree(str(td), ignore_errors=True)

    db_path = Path(args.db) if args.db else _default_db()
    if not db_path.is_file():
        # DB 자체가 없으면 미완료 goal도 없다는 뜻 — 진단상 정상
        print(json.dumps({
            "schema": "awx.codex-goal-status.v1",
            "dbPath": str(db_path),
            "dbExists": False,
            "unfinishedCount": 0,
            "unfinished": [],
            "note": "goals DB absent — no create_goal conflict possible",
        }, ensure_ascii=False, indent=2))
        return 0

    try:
        conn = _connect_ro(db_path)
        try:
            rows = _fetch_goals(conn, thread=args.thread)
        finally:
            conn.close()
    except sqlite3.Error as e:
        print(json.dumps({
            "schema": "awx.codex-goal-status.v1",
            "dbPath": str(db_path),
            "dbExists": True,
            "error": "db-read-failed: %s" % e,
        }, ensure_ascii=False), file=sys.stderr)
        return 3

    counts, unfinished = _classify(rows)
    if args.suggest_close:
        _report_suggest(unfinished)
    else:  # 기본/--status 모두 현황 출력
        _report_status(db_path, rows, counts, unfinished)
    return 0


if __name__ == "__main__":
    sys.exit(main())
