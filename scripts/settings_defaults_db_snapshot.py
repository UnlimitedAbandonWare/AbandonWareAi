#!/usr/bin/env python3
"""settings_defaults_db_snapshot -- read-only DB side-effect snapshot.

Wraps ``scripts/db_agent.py query --via live`` (HTTP-only lane into the running
server on http://127.0.0.1:18180). The file/runscript lane
(org.h2.tools.RunScript against the locked lmsdb.mv.db) is NEVER invoked:
``--via live`` is hard-coded and any db_agent reply whose ``via`` field is not
``live-http`` is treated as an error, not silently used.

    snapshot --label NAME [--out-dir DIR] [--base-url URL] [--max-rows N]
    diff SNAP_A SNAP_B [--out FILE]

Targets (per Codex settings-defaults assist brief D1):
    user_preference_profile - row count; owner_key only as sha256[:8];
        profile_meta only as normalized-JSON sha256 + top-level key list
    configuration_setting     - key list + per-key value sha256 (raw values
        are never emitted)
    current_model             - row count + sha256 of the id=1 row

A missing table is recorded as <TABLE>_MISSING and the run continues (the
absence is itself Codex's G0.5 HOLD evidence). A stopped server yields
status NOT_RUN - this tool never starts one.

Exit codes: 0 ok | 2 args/tool error | 3 live lane unreachable | 5 auth/other.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_AGENT = ROOT / "scripts" / "db_agent.py"
DEFAULT_OUT = ROOT / "var" / "settings-defaults-assist"
DEFAULT_BASE = "http://127.0.0.1:18180"
SCHEMA = "awx.settings-defaults-db-snapshot.v1"

WATCHED_TABLES = ("user_preference_profile", "configuration_setting",
                  "current_model")


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _canonical(obj) -> str:
    return json.dumps(obj, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), default=str)


def _now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _query(base_url: str, sql: str, max_rows: int, timeout: int = 90):
    """Run db_agent live-lane query. -> (payload, error). Never touches the
    file lane: ``--via live`` is fixed and the reply ``via`` is verified."""
    argv = [sys.executable, "-B", str(DB_AGENT), "query", "--via", "live",
            "--base-url", base_url, "--sql", sql, "--max-rows", str(max_rows)]
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              timeout=timeout, cwd=str(ROOT))
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, {"reason": f"db-agent-spawn-failed:{e}", "exit": -1}
    payload = None
    for line in reversed([l for l in (proc.stdout or "").splitlines()
                          if l.strip()]):
        try:
            payload = json.loads(line)
            break
        except json.JSONDecodeError:
            continue
    if payload is None:
        return None, {"reason": "db-agent-output-unparsed",
                      "exit": proc.returncode,
                      "stderrTail": (proc.stderr or "")[:300]}
    via = payload.get("via")
    if proc.returncode == 0 and via != "live-http":
        return None, {"reason": "non-live-lane-refused", "via": via,
                      "exit": proc.returncode}
    if proc.returncode != 0:
        err = {"reason": payload.get("reason") or f"exit-{proc.returncode}",
               "exit": proc.returncode,
               "httpStatus": payload.get("httpStatus")}
        return payload, err
    return payload, None


def _rows_as_dicts(payload: dict) -> list[dict]:
    cols = [str(c) for c in (payload.get("columns") or [])]
    out = []
    for r in payload.get("rows") or []:
        if isinstance(r, dict):
            out.append({c: r.get(c) for c in cols})
        else:
            out.append(dict(zip(cols, r)))
    return out


def _columns_of(base_url: str, max_rows: int):
    """-> ({table: [columns]}, error). Table/column names only."""
    sql = ("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
           "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, "
           "ORDINAL_POSITION")
    payload, err = _query(base_url, sql, max_rows)
    if err:
        return None, err
    cols = {}
    for row in _rows_as_dicts(payload):
        t = str(row.get("TABLE_NAME") or "")
        c = str(row.get("COLUMN_NAME") or "")
        if t:
            cols.setdefault(t, []).append(c)
    return cols, None


def _pick(cols: list[str], *needles: str) -> str | None:
    low = {c.lower(): c for c in cols}
    for n in needles:
        for lc, orig in low.items():
            if n in lc:
                return orig
    return None


def _snap_upp(base_url: str, max_rows: int) -> dict:
    sql = ("SELECT * FROM user_preference_profile ORDER BY 1")
    payload, err = _query(base_url, sql, max_rows)
    if err:
        return {"status": "ERROR", "error": err}
    rows = _rows_as_dicts(payload)
    out_rows = []
    for r in rows:
        entry = {}
        for k, v in r.items():
            lk = str(k).lower()
            if lk == "id":
                entry["id"] = v
            elif lk == "owner_key":
                entry["ownerKeySha8"] = _sha256_text(str(v))[:8] if v else None
            elif lk == "profile_meta":
                keys, canon = [], "null"
                if v:
                    try:
                        meta = json.loads(v)
                        keys = sorted(meta.keys()) if isinstance(meta, dict) else []
                        canon = _canonical(meta)
                    except (json.JSONDecodeError, TypeError):
                        canon = str(v)
                entry["profileMetaSha256"] = _sha256_text(canon)
                entry["profileMetaTopKeys"] = keys
            elif "updat" in lk:
                entry["updatedAtHash8"] = _sha256_text(str(v))[:8] if v else None
        out_rows.append(entry)
    return {"status": "OK", "rowCount": len(rows), "rows": out_rows}


def _snap_configuration_setting(base_url: str, max_rows: int,
                                cols: list[str]) -> dict:
    key_col = _pick(cols, "key", "name")
    val_col = _pick(cols, "value")
    if not key_col:
        return {"status": "ERROR",
                "error": {"reason": "key-column-unresolved",
                          "columns": cols}}
    sel = f'"{key_col}"' + (f', "{val_col}"' if val_col else "")
    sql = (f"SELECT {sel} FROM configuration_setting ORDER BY \"{key_col}\"")
    payload, err = _query(base_url, sql, max_rows)
    if err:
        return {"status": "ERROR", "error": err}
    items = []
    for r in _rows_as_dicts(payload):
        item = {"key": r.get(key_col)}
        if val_col:
            raw = r.get(val_col)
            item["valueSha256"] = _sha256_text("" if raw is None else str(raw))
        items.append(item)
    return {"status": "OK", "rowCount": len(items), "items": items,
            "note": "raw values never emitted - sha256 only"}


def _snap_current_model(base_url: str, max_rows: int) -> dict:
    sql = "SELECT * FROM current_model"
    payload, err = _query(base_url, sql, max_rows)
    if err:
        return {"status": "ERROR", "error": err}
    rows = _rows_as_dicts(payload)
    out = {"status": "OK", "rowCount": len(rows)}
    for r in rows:
        for k, v in r.items():
            if str(k).lower() == "id" and str(v) == "1":
                out["id1RowSha256"] = _sha256_text(_canonical(r))
    return out


def cmd_snapshot(args) -> int:
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    snap = {"schema": SCHEMA, "label": args.label,
            "createdAtUtc": _now_utc(), "baseUrl": args.base_url,
            "via": "live-http-only", "status": "OK", "tables": {}}
    cols, err = _columns_of(args.base_url, args.max_rows)
    if err is not None:
        reason = (err or {}).get("reason", "")
        snap["status"] = ("NOT_RUN" if "unreachable" in reason
                          else "AUTH_BLOCKED" if "auth" in reason
                          else "ERROR")
        snap["error"] = err
        snap["tables"] = {t: {"status": snap["status"]}
                          for t in WATCHED_TABLES}
        path = out_dir / f"snapshot-{args.label}.json"
        path.write_text(json.dumps(snap, indent=2, ensure_ascii=False),
                        encoding="utf-8")
        print(json.dumps({"snapshot": str(path), "status": snap["status"],
                          "reason": reason}, ensure_ascii=False))
        return {"NOT_RUN": 3, "AUTH_BLOCKED": 5}.get(snap["status"], 5)
    have = {t for t in cols}
    snap["tableCatalog"] = {"knownTableCount": len(cols)}
    for t in WATCHED_TABLES:
        if t not in have:
            snap["tables"][t] = {"status": f"{t.upper()}_MISSING"}
    if "user_preference_profile" in have:
        snap["tables"]["user_preference_profile"] = _snap_upp(
            args.base_url, args.max_rows)
    if "configuration_setting" in have:
        snap["tables"]["configuration_setting"] = _snap_configuration_setting(
            args.base_url, args.max_rows, cols["configuration_setting"])
    if "current_model" in have:
        snap["tables"]["current_model"] = _snap_current_model(
            args.base_url, args.max_rows)
    if any(s.get("status") == "ERROR" for s in snap["tables"].values()):
        snap["status"] = "PARTIAL"
    path = out_dir / f"snapshot-{args.label}.json"
    path.write_text(json.dumps(snap, indent=2, ensure_ascii=False),
                    encoding="utf-8")
    summary = {"snapshot": str(path), "status": snap["status"],
               "tables": {t: {"status": s["status"],
                              **({"rowCount": s.get("rowCount")}
                                 if "rowCount" in s else {})}
                          for t, s in snap["tables"].items()}}
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if snap["status"] in ("OK", "PARTIAL") else 5


def _fingerprint(table: dict) -> dict:
    """Stable per-row/item fingerprints for diffing."""
    fp = {}
    for key in ("rows", "items"):
        for r in table.get(key) or []:
            fp[_canonical(r)] = r
    return fp


def cmd_diff(args) -> int:
    try:
        a = json.loads(Path(args.snap_a).read_text(encoding="utf-8"))
        b = json.loads(Path(args.snap_b).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        print(json.dumps({"error": f"snapshot-unreadable:{e}"}))
        return 2
    report = {"schema": "awx.settings-defaults-db-diff.v1",
              "createdAtUtc": _now_utc(),
              "a": {"file": args.snap_a, "label": a.get("label"),
                    "status": a.get("status")},
              "b": {"file": args.snap_b, "label": b.get("label"),
                    "status": b.get("status")},
              "verdict": "IDENTICAL", "tables": {}}
    for t in sorted(set(a.get("tables") or {}) | set(b.get("tables") or {})):
        ta = (a.get("tables") or {}).get(t, {"status": "ABSENT_IN_A"})
        tb = (b.get("tables") or {}).get(t, {"status": "ABSENT_IN_B"})
        entry = {"a": ta.get("status"), "b": tb.get("status")}
        if ta.get("status") != tb.get("status"):
            entry["statusChange"] = f"{ta.get('status')}->{tb.get('status')}"
        if ta.get("status") == "OK" and tb.get("status") == "OK":
            if ta.get("rowCount") != tb.get("rowCount"):
                entry["rowCount"] = [ta.get("rowCount"), tb.get("rowCount")]
            fa, fb = _fingerprint(ta), _fingerprint(tb)
            added = [fb[k] for k in fb.keys() - fa.keys()]
            removed = [fa[k] for k in fa.keys() - fb.keys()]
            if added or removed:
                entry["added"] = added
                entry["removed"] = removed
            if not added and not removed and "rowCount" not in entry:
                entry["equal"] = True
        report["tables"][t] = entry
        if not entry.get("equal"):
            report["verdict"] = "CHANGED"
    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=2,
                                             ensure_ascii=False),
                                  encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("snapshot")
    p.add_argument("--label", required=True)
    p.add_argument("--out-dir", default=str(DEFAULT_OUT))
    p.add_argument("--base-url", default=DEFAULT_BASE)
    p.add_argument("--max-rows", type=int, default=500)
    p = sub.add_parser("diff")
    p.add_argument("snap_a")
    p.add_argument("snap_b")
    p.add_argument("--out")
    args = ap.parse_args(argv)
    return {"snapshot": cmd_snapshot, "diff": cmd_diff}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
