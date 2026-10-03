#!/usr/bin/env python3
"""Campaign reservation oracle. Stdlib only. No network. No ledger.jsonl writes.

Journal: <AWX_JEV_CAMPAIGN_DIR or data/agent-handoff/jev-spend/campaigns>/<id>.jsonl
Policy:  <id>.policy.json
Lock:    <id>.lock  (fail closed; the OS drops the lock if this process dies)

Live provider calls: 0. Amounts are decimal strings. Float money is refused.
Do not use os.kill to decide whether a lock is stale.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HARD_CAP_LIMIT = Decimal("2.00")
MAX_CALLS_FORBIDDEN = 3000
LOCK_TIMEOUT_S = 5.0
HOLD_WHYS = ("timeout", "cancel", "abandoned", "crash")
CALL_STATES = ("RESERVED", "DISPATCHED", "SETTLED", "RELEASED", "UNKNOWN_HELD")
_ID_RE = re.compile(r"[A-Za-z0-9._-]{1,80}")


class UsageError(Exception):
    pass


class _Refuse(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def campaign_dir() -> Path:
    raw = os.environ.get("AWX_JEV_CAMPAIGN_DIR", "").strip()
    if raw:
        return Path(raw)
    return ROOT / "data" / "agent-handoff" / "jev-spend" / "campaigns"


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def _check_id(value: str, label: str) -> str:
    text = (value or "").strip()
    if not _ID_RE.fullmatch(text):
        raise UsageError(label)
    return text


def parse_money(value) -> Decimal:
    if isinstance(value, Decimal):
        amount = value
    elif isinstance(value, bool) or isinstance(value, float):
        raise UsageError("float-not-allowed")
    elif isinstance(value, int):
        amount = Decimal(value)
    else:
        text = str(value).strip()
        if not re.fullmatch(r"\d+(\.\d+)?", text):
            raise UsageError("amount")
        try:
            amount = Decimal(text)
        except InvalidOperation as exc:
            raise UsageError("amount") from exc
    if amount < 0:
        raise UsageError("amount")
    return amount


def money_str(amount: Decimal) -> str:
    return format(amount, "f")


def _paths(directory: Path, campaign_id: str) -> tuple[Path, Path, Path]:
    directory.mkdir(parents=True, exist_ok=True)
    return (
        directory / (campaign_id + ".policy.json"),
        directory / (campaign_id + ".jsonl"),
        directory / (campaign_id + ".lock"),
    )


def _acquire(lock_path: Path, timeout: float):
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = open(lock_path, "a+b")  # noqa: SIM115 — closed by _release
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() < 1:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        deadline = time.monotonic() + max(0.0, timeout)
        while True:
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                return handle
            except OSError:
                if time.monotonic() >= deadline:
                    handle.close()
                    return None
                time.sleep(0.02)
    except Exception:
        handle.close()
        raise


def _release(handle) -> None:
    if handle is None:
        return
    try:
        if os.name == "nt":
            import msvcrt
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    except OSError:
        pass
    finally:
        handle.close()


def _read_events(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    events = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            raise UsageError("journal-corrupt") from exc
        if not isinstance(item, dict):
            raise UsageError("journal-corrupt")
        events.append(item)
    return events


def _append(path: Path, event: dict) -> None:
    line = json.dumps(event, ensure_ascii=True, separators=(",", ":"))
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(line + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def _load_policy(path: Path) -> dict:
    if not path.is_file():
        raise _Refuse("policy-missing")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise _Refuse("policy-invalid") from exc
    if not isinstance(data, dict):
        raise _Refuse("policy-invalid")
    for key in (
        "approval_ref", "campaign_id", "policy_revision", "startedAtUtc",
        "target_usd", "hard_cap_usd", "planHash", "max_calls",
    ):
        if key not in data or data[key] in ("", None):
            raise _Refuse("policy-invalid")
    try:
        hard = parse_money(data["hard_cap_usd"])
        target = parse_money(data["target_usd"])
        calls = int(data["max_calls"])
    except (UsageError, TypeError, ValueError) as exc:
        raise _Refuse("policy-invalid") from exc
    if hard > HARD_CAP_LIMIT or target > hard or calls < 1 or calls >= MAX_CALLS_FORBIDDEN:
        raise _Refuse("policy-invalid")
    providers = data.get("allowed_providers") or data.get("providers") or []
    if not isinstance(providers, list) or not providers:
        raise _Refuse("policy-invalid")
    data = dict(data)
    data["_hard"] = hard
    data["_target"] = target
    data["_calls"] = calls
    data["_providers"] = [str(item) for item in providers]
    models = data.get("allowed_models") or data.get("models") or []
    data["_models"] = [str(item) for item in models] if isinstance(models, list) else []
    return data


def _blank_call() -> dict:
    return {"state": None, "verified": Decimal("0"), "actual": None, "provider": None}


def fold(events: list[dict]) -> dict:
    calls: dict[str, dict] = {}
    overrun = False
    conflict = False
    target_reached = False
    for event in events:
        name = event.get("event")
        if name == "OVERRUN":
            overrun = True
            continue
        if name == "CONFLICT":
            conflict = True
            continue
        if name == "TARGET_REACHED":
            target_reached = True
            continue
        call_id = event.get("callId")
        if not call_id or name not in CALL_STATES:
            continue
        row = calls.setdefault(call_id, _blank_call())
        if event.get("provider"):
            row["provider"] = event["provider"]
        if event.get("verifiedMaxUsd") not in (None, ""):
            row["verified"] = parse_money(event["verifiedMaxUsd"])
        if name == "RESERVED":
            row["state"] = "RESERVED"
        elif name == "DISPATCHED":
            row["state"] = "DISPATCHED"
        elif name == "UNKNOWN_HELD":
            row["state"] = "UNKNOWN_HELD"
        elif name == "RELEASED":
            row["state"] = "RELEASED"
        elif name == "SETTLED":
            row["state"] = "SETTLED"
            row["actual"] = parse_money(event.get("actualUsd"))
    settled = inflight = held = Decimal("0")
    active = 0
    for row in calls.values():
        state = row["state"]
        if state == "SETTLED" and row["actual"] is not None:
            settled += row["actual"]
            active += 1
        elif state in ("RESERVED", "DISPATCHED"):
            inflight += row["verified"]
            active += 1
        elif state == "UNKNOWN_HELD":
            held += row["verified"]
            active += 1
    return {
        "calls": calls,
        "settled": settled,
        "inflight": inflight,
        "held": held,
        "active": active,
        "overrun": overrun,
        "conflict": conflict,
        "targetReached": target_reached,
    }


def _public_status(campaign_id: str, policy: dict | None, state: dict, extra: dict | None = None) -> dict:
    body = {
        "ok": True,
        "exit": 0,
        "campaignId": campaign_id,
        "policyRevision": None if policy is None else str(policy.get("policy_revision")),
        "settledUsd": money_str(state["settled"]),
        "inflightUsd": money_str(state["inflight"]),
        "unknownHeldUsd": money_str(state["held"]),
        "hardCapUsd": None if policy is None else money_str(policy["_hard"]),
        "targetUsd": None if policy is None else money_str(policy["_target"]),
        "targetReached": state["targetReached"],
        "overrun": state["overrun"],
        "conflict": state["conflict"],
        "callCount": state["active"],
        "calls": {
            call_id: {
                "state": row["state"],
                "provider": row["provider"],
                "verifiedMaxUsd": money_str(row["verified"]),
                "actualUsd": None if row["actual"] is None else money_str(row["actual"]),
            }
            for call_id, row in state["calls"].items()
        },
    }
    if extra:
        body.update(extra)
    return body


def _fail(exc) -> dict:
    if isinstance(exc, _Refuse):
        return {"ok": False, "exit": 5, "reason": exc.reason}
    return {"ok": False, "exit": 2, "reason": str(exc)}


def max_charge(snapshot, max_input_tokens, max_output_tokens, provider: str):
    """Decimal verified max, or None when the snapshot cannot price the call."""
    if not isinstance(snapshot, dict):
        return None
    if max_input_tokens is None or max_output_tokens is None:
        return None
    try:
        tokens_in = int(max_input_tokens)
        tokens_out = int(max_output_tokens)
    except (TypeError, ValueError):
        return None
    if tokens_in < 0 or tokens_out < 0:
        return None
    providers = snapshot.get("providers")
    if not isinstance(providers, dict):
        return None
    row = providers.get(provider)
    if not isinstance(row, dict):
        return None
    in_rate = row.get("inputUsdPerMillion")
    out_rate = row.get("outputUsdPerMillion")
    if in_rate in (None, "") or out_rate in (None, ""):
        return None
    try:
        per_in = parse_money(in_rate)
        per_out = parse_money(out_rate)
    except UsageError:
        return None
    return (Decimal(tokens_in) * per_in + Decimal(tokens_out) * per_out) / Decimal(1000000)


def _open(campaign_id: str, directory, lock_timeout: float):
    campaign_id = _check_id(campaign_id, "campaign")
    directory = Path(directory) if directory else campaign_dir()
    handle = _acquire(_paths(directory, campaign_id)[2], lock_timeout)
    if handle is None:
        raise _Refuse("lock-timeout")
    policy_path, journal, _lock = _paths(directory, campaign_id)
    return handle, policy_path, journal, campaign_id


def init_policy(campaign_id: str, source, directory=None, lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    try:
        campaign_id = _check_id(campaign_id, "campaign")
        raw = json.loads(Path(source).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise UsageError("policy-file")
        raw = dict(raw)
        raw["campaign_id"] = campaign_id
        if not raw.get("startedAtUtc"):
            raw["startedAtUtc"] = _now()
        handle, policy_path, journal, campaign_id = _open(campaign_id, directory, lock_timeout)
    except (_Refuse, UsageError) as exc:
        return _fail(exc)
    try:
        try:
            if _read_events(journal):
                raise UsageError("campaign-has-events")
            policy_path.write_text(json.dumps(raw, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
            try:
                loaded = _load_policy(policy_path)
            except _Refuse:
                policy_path.unlink(missing_ok=True)
                raise
            return _public_status(campaign_id, loaded, fold([]))
        except (_Refuse, UsageError) as exc:
            return _fail(exc)
    finally:
        _release(handle)


def _base_event(campaign_id, policy, seq, name, call_id, provider, amount, verified, actual, proof) -> dict:
    return {
        "seq": seq,
        "tsUtc": _now(),
        "campaignId": campaign_id,
        "callId": call_id,
        "provider": provider,
        "event": name,
        "amountUsd": money_str(amount),
        "verifiedMaxUsd": money_str(verified),
        "actualUsd": None if actual is None else money_str(actual),
        "proof": proof,
        "policyRevision": str(policy.get("policy_revision")),
    }


def reserve(campaign_id, call_id, verified_max_charge, provider="jev", model=None,
            directory=None, lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    """reserve(campaignId, callId, verifiedMaxCharge)."""
    try:
        call_id = _check_id(call_id, "call")
        provider = _check_id(provider, "provider")
        if verified_max_charge is None:
            raise _Refuse("uncomputable-max-charge")
        amount = parse_money(verified_max_charge)
        handle, policy_path, journal, campaign_id = _open(campaign_id, directory, lock_timeout)
    except (_Refuse, UsageError) as exc:
        return _fail(exc)
    try:
        try:
            policy = _load_policy(policy_path)
            events = _read_events(journal)
            state = fold(events)
            if state["overrun"]:
                return {"ok": False, "exit": 5, "reason": "overrun-block"}
            if state["conflict"]:
                return {"ok": False, "exit": 5, "reason": "conflict"}
            if provider not in policy["_providers"]:
                return {"ok": False, "exit": 5, "reason": "provider-not-allowed"}
            if model and policy["_models"] and model not in policy["_models"]:
                return {"ok": False, "exit": 5, "reason": "provider-not-allowed"}
            if call_id in state["calls"]:
                raise UsageError("duplicate-call")
            if state["active"] >= policy["_calls"]:
                return {"ok": False, "exit": 5, "reason": "max-calls"}
            projected = state["settled"] + state["inflight"] + state["held"] + amount
            if projected > policy["_hard"]:
                return {"ok": False, "exit": 5, "reason": "cap-exceeded"}
            seq = len(events) + 1
            reserved = _base_event(
                campaign_id, policy, seq, "RESERVED", call_id, provider, amount, amount, None, None)
            _append(journal, reserved)
            events.append(reserved)
            state = fold(events)
            total = state["settled"] + state["inflight"] + state["held"]
            if total >= policy["_target"] and not any(item.get("event") == "TARGET_REACHED" for item in events):
                proof = json.dumps({
                    "remainingWork": "manifest-not-closed",
                    "nextMaxUsd": money_str(amount),
                    "sumUsd": money_str(total),
                }, ensure_ascii=True, separators=(",", ":"))
                seq += 1
                _append(journal, _base_event(
                    campaign_id, policy, seq, "TARGET_REACHED", call_id, provider,
                    total, amount, None, proof))
                state["targetReached"] = True
            return _public_status(campaign_id, policy, state)
        except (_Refuse, UsageError) as exc:
            return _fail(exc)
    finally:
        _release(handle)


def _transition(campaign_id, call_id, name, directory, lock_timeout, actual, proof) -> dict:
    try:
        call_id = _check_id(call_id, "call")
        handle, policy_path, journal, campaign_id = _open(campaign_id, directory, lock_timeout)
    except (_Refuse, UsageError) as exc:
        return _fail(exc)
    try:
        try:
            policy = _load_policy(policy_path)
            events = _read_events(journal)
            state = fold(events)
            row = state["calls"].get(call_id)
            if row is None or row["state"] is None:
                raise UsageError("unknown-call")
            current = row["state"]
            if name == "DISPATCHED":
                if current == "DISPATCHED":
                    return _public_status(campaign_id, policy, state, {"noop": True})
                if current != "RESERVED":
                    raise UsageError("state")
            elif name == "UNKNOWN_HELD":
                if current == "UNKNOWN_HELD":
                    return _public_status(campaign_id, policy, state, {"noop": True})
                if current not in ("RESERVED", "DISPATCHED"):
                    raise UsageError("state")
            elif name == "RELEASED":
                if current != "RESERVED":
                    raise UsageError("state")
            elif name == "SETTLED":
                if current == "SETTLED":
                    if row["actual"] == actual:
                        return _public_status(campaign_id, policy, state, {"duplicate_receipt": True})
                    seq = len(events) + 1
                    _append(journal, _base_event(
                        campaign_id, policy, seq, "CONFLICT", call_id, row["provider"],
                        actual, row["verified"], actual, "conflicting-receipt"))
                    return {"ok": False, "exit": 5, "reason": "conflict", "campaignId": campaign_id}
                if current not in ("RESERVED", "DISPATCHED", "UNKNOWN_HELD"):
                    raise UsageError("state")
            seq = len(events) + 1
            written = _base_event(
                campaign_id, policy, seq, name, call_id, row["provider"],
                actual if actual is not None else row["verified"],
                row["verified"], actual, proof)
            _append(journal, written)
            events.append(written)
            if name == "SETTLED" and actual is not None and actual > row["verified"]:
                seq += 1
                marker = _base_event(
                    campaign_id, policy, seq, "OVERRUN", call_id, row["provider"],
                    actual, row["verified"], actual, "actual-above-verified-max")
                _append(journal, marker)
                events.append(marker)
            return _public_status(campaign_id, policy, fold(events))
        except (_Refuse, UsageError) as exc:
            return _fail(exc)
    finally:
        _release(handle)


def mark_dispatched(campaign_id, call_id, directory=None, lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    return _transition(campaign_id, call_id, "DISPATCHED", directory, lock_timeout, None, None)


def hold_unknown(campaign_id, call_id, why, directory=None, lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    if why not in HOLD_WHYS:
        return {"ok": False, "exit": 2, "reason": "why"}
    return _transition(campaign_id, call_id, "UNKNOWN_HELD", directory, lock_timeout, None, why)


def release_undispatched(campaign_id, call_id, proof, directory=None,
                         lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    text = "" if proof is None else str(proof).strip()
    if not text:
        return {"ok": False, "exit": 2, "reason": "proof-required"}
    return _transition(campaign_id, call_id, "RELEASED", directory, lock_timeout, None, text[:180])


def settle(campaign_id, call_id, verified_charge=None, directory=None,
           lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    if verified_charge is None:
        return {"ok": False, "exit": 2, "reason": "amount"}
    try:
        actual = parse_money(verified_charge)
    except UsageError as exc:
        return _fail(exc)
    return _transition(campaign_id, call_id, "SETTLED", directory, lock_timeout, actual, None)


def recover(campaign_id, directory=None, lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    """Leftover RESERVED becomes UNKNOWN_HELD. The balance does not grow."""
    try:
        handle, policy_path, journal, campaign_id = _open(campaign_id, directory, lock_timeout)
    except (_Refuse, UsageError) as exc:
        return _fail(exc)
    try:
        try:
            policy = _load_policy(policy_path)
            events = _read_events(journal)
            state = fold(events)
            seq = len(events)
            for call_id, row in list(state["calls"].items()):
                if row["state"] != "RESERVED":
                    continue
                seq += 1
                marker = _base_event(
                    campaign_id, policy, seq, "UNKNOWN_HELD", call_id, row["provider"],
                    row["verified"], row["verified"], None, "crash")
                _append(journal, marker)
                events.append(marker)
            return _public_status(campaign_id, policy, fold(events), {"recovered": True})
        except (_Refuse, UsageError) as exc:
            return _fail(exc)
    finally:
        _release(handle)


def status(campaign_id, directory=None, lock_timeout: float = LOCK_TIMEOUT_S) -> dict:
    try:
        campaign_id = _check_id(campaign_id, "campaign")
    except UsageError as exc:
        return _fail(exc)
    directory = Path(directory) if directory else campaign_dir()
    policy_path, journal, _lock = _paths(directory, campaign_id)
    try:
        policy = _load_policy(policy_path)
        events = _read_events(journal)
    except _Refuse as exc:
        return _fail(exc)
    except UsageError as exc:
        return _fail(exc)
    return _public_status(campaign_id, policy, fold(events))


def preflight(campaign_id, directory=None) -> str | None:
    """Reason when another positive reserve cannot fit, else None. Does not reserve."""
    current = status(campaign_id, directory=directory)
    if not current.get("ok"):
        return current.get("reason") or "policy-missing"
    if current.get("overrun"):
        return "overrun-block"
    if current.get("conflict"):
        return "conflict"
    hard = parse_money(current["hardCapUsd"])
    used = (parse_money(current["settledUsd"]) + parse_money(current["inflightUsd"])
            + parse_money(current["unknownHeldUsd"]))
    if used >= hard:
        return "cap-exceeded"
    return None


def _print(result: dict) -> int:
    code = int(result.get("exit", 2))
    if code == 5:
        print("budget_refused:" + str(result.get("reason")), file=sys.stderr)
    print(json.dumps(result, ensure_ascii=True))
    return code


def _charge_from_usage(path: str):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise UsageError("usage-json") from exc
    if not isinstance(data, dict):
        raise UsageError("usage-json")
    raw = data.get("actualUsd", data.get("cost"))
    if isinstance(raw, float):
        raise UsageError("float-not-allowed")
    return parse_money(raw)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Offline campaign reservation oracle. No network.")
    parser.add_argument("--lock-timeout", type=float, default=LOCK_TIMEOUT_S)
    sub = parser.add_subparsers(dest="cmd", required=True)

    init = sub.add_parser("init-policy")
    init.add_argument("--campaign", required=True)
    init.add_argument("--from", dest="source", required=True)

    reserve_p = sub.add_parser("reserve")
    reserve_p.add_argument("--campaign", required=True)
    reserve_p.add_argument("--call", required=True)
    reserve_p.add_argument("--provider", required=True)
    reserve_p.add_argument("--model")
    reserve_p.add_argument("--max-usd")
    reserve_p.add_argument("--pricing")
    reserve_p.add_argument("--max-in", type=int)
    reserve_p.add_argument("--max-out", type=int)

    for name in ("dispatch", "hold", "release", "settle", "recover", "status"):
        cmd = sub.add_parser(name)
        cmd.add_argument("--campaign", required=True)
        if name in ("dispatch", "hold", "release", "settle"):
            cmd.add_argument("--call", required=True)
        if name == "hold":
            cmd.add_argument("--why", required=True)
        if name == "release":
            cmd.add_argument("--proof", required=True)
        if name == "settle":
            cmd.add_argument("--actual-usd")
            cmd.add_argument("--usage-json")
        if name == "status":
            cmd.add_argument("--json", action="store_true")

    args = parser.parse_args(argv)
    try:
        if args.cmd == "init-policy":
            result = init_policy(args.campaign, args.source, lock_timeout=args.lock_timeout)
        elif args.cmd == "reserve":
            if args.max_usd:
                charge = parse_money(args.max_usd)
            elif args.pricing:
                try:
                    snapshot = json.loads(Path(args.pricing).read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError):
                    snapshot = None
                charge = max_charge(snapshot, args.max_in, args.max_out, args.provider)
                if charge is None:
                    result = {"ok": False, "exit": 5, "reason": "uncomputable-max-charge"}
                    return _print(result)
            else:
                raise UsageError("max-usd-or-pricing")
            result = reserve(
                args.campaign, args.call, charge, provider=args.provider,
                model=args.model, lock_timeout=args.lock_timeout)
        elif args.cmd == "dispatch":
            result = mark_dispatched(args.campaign, args.call, lock_timeout=args.lock_timeout)
        elif args.cmd == "hold":
            result = hold_unknown(args.campaign, args.call, args.why, lock_timeout=args.lock_timeout)
        elif args.cmd == "release":
            result = release_undispatched(args.campaign, args.call, args.proof, lock_timeout=args.lock_timeout)
        elif args.cmd == "settle":
            if args.actual_usd:
                charge = parse_money(args.actual_usd)
            elif args.usage_json:
                charge = _charge_from_usage(args.usage_json)
            else:
                raise UsageError("actual-usd-or-usage")
            result = settle(args.campaign, args.call, charge, lock_timeout=args.lock_timeout)
        elif args.cmd == "recover":
            result = recover(args.campaign, lock_timeout=args.lock_timeout)
        else:
            result = status(args.campaign, lock_timeout=args.lock_timeout)
    except (_Refuse, UsageError) as exc:
        result = _fail(exc)
    return _print(result)


if __name__ == "__main__":
    raise SystemExit(main())
