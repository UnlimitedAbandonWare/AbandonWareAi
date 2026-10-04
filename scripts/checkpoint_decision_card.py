#!/usr/bin/env python3
"""codex_work_checkpoint --decision JSON 패킷 생성기 (읽기 전용 도우미).

세션에서 손으로 ~10문장짜리 해시테이블을 조립하던
{goalId, reasonCode, risk:{recovery,blastRadius,regression,uncertainty,cost},
gates:{8키 bool}} 패킷을 플래그 한 줄로 만든다. 스키마는 이 파일에 하드코딩하지
않고 codex_work_checkpoint 의 GATES/FACTORS/safe_id/assess 를 import 한다.

사용:
    python -B scripts/checkpoint_decision_card.py
        --goal-id X --reason-code Y
        [--recovery 0..4] [--blast-radius 0..4] [--regression 0..4]
        [--uncertainty 0..4] [--cost 0..4]          (기본 전부 0)
        [--gate bulkDelete] ...                     (8키 중 선택, 반복 가능)
        [--assess]     assess() 판정(status/riskScore/verificationDepth) 미리보기
        [--out PATH]   JSON 파일로 저장(기존 파일은 덮어쓰지 않음)

exit 0 성공, exit 2 검증/쓰기 오류. 상태 변경 없음.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
try:  # 스키마 SSOT: codex_work_checkpoint
    from codex_work_checkpoint import FACTORS, GATES, assess, safe_id
except Exception as e:  # pragma: no cover — import 실패 시에도 패킷은 만든다
    FACTORS = {"recovery", "blastRadius", "regression", "uncertainty", "cost"}
    GATES = {"bulkDelete", "unrecoverableOverwrite", "credentialChange",
             "externalRealData", "paidBulkCalls", "productionMutation",
             "permissionChange", "irreversibleLoss"}
    assess = None
    safe_id = None
    _IMPORT_ERR = str(e)
else:
    _IMPORT_ERR = None


def build_packet(args):
    packet = {
        "goalId": args.goal_id,
        "reasonCode": args.reason_code,
        "risk": {f: getattr(args, f) for f in FACTORS},
        "gates": {g: (g in set(args.gate)) for g in sorted(GATES)},
    }
    return packet


def validate(packet):
    if safe_id is not None:
        safe_id(packet["goalId"])
        safe_id(packet["reasonCode"])
    else:
        import re
        for v in (packet["goalId"], packet["reasonCode"]):
            if not (isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}", v)):
                raise ValueError("invalid goalId/reasonCode")
    if set(packet["risk"]) != FACTORS:
        raise ValueError("risk keys mismatch: %s" % sorted(packet["risk"]))
    for k, v in packet["risk"].items():
        if type(v) is not int or not (0 <= v <= 4):
            raise ValueError("risk %s must be int 0..4" % k)
    if set(packet["gates"]) != GATES:
        raise ValueError("gates keys mismatch")
    for k in packet["gates"]:
        if type(packet["gates"][k]) is not bool:
            raise ValueError("gate %s must be bool" % k)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description=__doc__,
        epilog="gates: " + ", ".join(sorted(GATES)))
    ap.add_argument("--goal-id", required=True)
    ap.add_argument("--reason-code", required=True)
    for f in FACTORS:
        ap.add_argument("--" + f.replace("blastRadius", "blast-radius"),
                        type=int, default=0, dest=f)
    ap.add_argument("--gate", action="append", default=[], choices=sorted(GATES))
    ap.add_argument("--assess", action="store_true")
    ap.add_argument("--out", default="")
    args = ap.parse_args(argv)

    try:
        packet = build_packet(args)
        validate(packet)
    except ValueError as e:
        print("decision-card: %s" % e, file=sys.stderr)
        return 2

    payload = {"packet": packet}
    if args.assess:
        if assess is None:
            print("decision-card: assess unavailable (%s)" % _IMPORT_ERR, file=sys.stderr)
            return 2
        try:
            payload["assessment"] = assess(packet)
        except Exception as e:
            print("decision-card: assess failed: %s" % e, file=sys.stderr)
            return 2

    text = json.dumps(payload, ensure_ascii=False, indent=1)
    if args.out:
        out = Path(args.out)
        if out.exists():
            print("decision-card: refuse overwrite %s" % out, file=sys.stderr)
            return 2
        try:
            out.write_text(text + "\n", encoding="utf-8")
        except OSError as e:
            print("decision-card: cannot write %s: %s" % (out, e), file=sys.stderr)
            return 2
        print(json.dumps({"ok": True, "out": str(out),
                          "gatesTrue": [k for k, v in packet["gates"].items() if v]},
                         ensure_ascii=False))
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
