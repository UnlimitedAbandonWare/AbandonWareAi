#!/usr/bin/env python3
"""AWX Grok UAW Stop hook 설치/점검/제거.

Contract: PASTE_DEVIN_GROK_CLI_UAW_WEBSEARCH_20261002 (DV4).

  --apply      ~/.grok/hooks/uaw-citation-gate.json 등록
               (기존 파일이 있으면 .bak-YYYYMMDD 로 먼저 백업)
  --uninstall  등록 해제 (.bak 백업이 있으면 복원)
  --check      설치 상태 + 기능 probe 점검 → ALL_OK / FAIL:<항목> (exit 0/1)

표준 라이브러리만. auth.json·세션·토큰 등 ~/.grok 의 다른 파일은
읽지도 쓰지도 않는다 — hooks/*.json 하나만 만진다.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

HOOK_NAME = "uaw-citation-gate"
HOOK_TIMEOUT_S = 10  # Stop 기본 600s — 이 게이트는 즉시결정이라 10s면 충분


def _root() -> Path:
    return Path(__file__).resolve().parent.parent


def _grok_home() -> Path:
    return Path.home() / ".grok"


def _hook_script() -> Path:
    return _root() / "scripts" / "grok_websearch_hook.py"


def _hook_json() -> Path:
    return _grok_home() / "hooks" / (HOOK_NAME + ".json")


def _backup_path(path: Path) -> Path:
    return path.with_name(path.name + ".bak-" + time.strftime("%Y%m%d"))


def _command() -> str:
    # 인스톨러를 실행한 그 인터프리터 + 절대 경로 — PATH 조각 의존 제거
    return '"%s" -B "%s"' % (sys.executable, _hook_script())


def _hook_doc() -> dict:
    return {
        "hooks": {
            "Stop": [
                {
                    "hooks": [
                        {"type": "command", "command": _command(),
                         "timeout": HOOK_TIMEOUT_S}
                    ]
                }
            ]
        }
    }


def _probe(script: Path) -> tuple[bool, str]:
    """실제 hook 프로세스를 띄워 block/allow 두 케이스를 확인."""
    miss = {"hookEventName": "stop", "reason": "end_turn",
            "stopHookActive": False, "sessionId": "install-check",
            "lastAssistantMessage": "공식 문서는 https://example.com/docs 를 참고하세요."}
    active = dict(miss, stopHookActive=True)
    try:
        p1 = subprocess.run([sys.executable, "-B", str(script)],
                            input=json.dumps(miss).encode("utf-8"),
                            capture_output=True, timeout=HOOK_TIMEOUT_S)
        if p1.returncode != 0:
            return False, "probe-exit-%d" % p1.returncode
        out = json.loads(p1.stdout.decode("utf-8", errors="replace") or "{}")
        if out.get("decision") != "block":
            return False, "probe-no-block"
        p2 = subprocess.run([sys.executable, "-B", str(script)],
                            input=json.dumps(active).encode("utf-8"),
                            capture_output=True, timeout=HOOK_TIMEOUT_S)
        if p2.returncode != 0 or p2.stdout.strip():
            return False, "probe-circuit-breaker"
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        return False, "probe-error:%s" % str(exc)[:60]
    return True, "probe-ok"


def _check() -> int:
    fails = []

    script = _hook_script()
    if script.exists():
        print("OK hook script: %s" % script)
    else:
        fails.append("hook-script-missing")
        print("FAIL hook script missing: %s" % script)

    cfg = _hook_json()
    doc = None
    if cfg.exists():
        try:
            doc = json.loads(cfg.read_text(encoding="utf-8"))
            print("OK hook json: %s" % cfg)
        except (OSError, ValueError) as exc:
            fails.append("hook-json-invalid")
            print("FAIL hook json invalid: %s" % str(exc)[:80])
    else:
        fails.append("hook-json-missing")
        print("FAIL hook json missing: %s" % cfg)

    if isinstance(doc, dict):
        try:
            groups = doc["hooks"]["Stop"]
            cmds = [h.get("command", "") for g in groups
                    for h in g.get("hooks", []) if isinstance(h, dict)]
            if any(str(_hook_script()) in c for c in cmds):
                print("OK hook command -> grok_websearch_hook.py")
            else:
                fails.append("hook-command-mismatch")
                print("FAIL hook command mismatch: %s" % (cmds or "none"))
        except (KeyError, TypeError):
            fails.append("hook-json-shape")
            print("FAIL hook json shape: hooks.Stop[].hooks[] 없음")

    if Path(sys.executable).exists():
        print("OK interpreter: %s" % sys.executable)
    else:
        fails.append("interpreter-missing")
        print("FAIL interpreter missing: %s" % sys.executable)

    if script.exists():
        ok, detail = _probe(script)
        if ok:
            print("OK functional probe: block + circuit-breaker")
        else:
            fails.append(detail)
            print("FAIL functional probe: %s" % detail)

    # 참고 정보 (DV1/DV2 산출물 — 실패 처리는 안 함)
    for label, p in (("rule", _grok_home() / "rules" / "uaw-web-research.md"),
                     ("skill", _grok_home() / "skills" / "awx-uaw-web-research" / "SKILL.md")):
        print("INFO %s: %s (%s)" % (label, p, "present" if p.exists() else "absent"))

    if fails:
        print("FAIL: " + ",".join(fails))
        return 1
    print("ALL_OK")
    return 0


def _apply() -> int:
    hooks_dir = _grok_home() / "hooks"
    target = _hook_json()
    try:
        hooks_dir.mkdir(parents=True, exist_ok=True)
        if target.exists():
            backup = _backup_path(target)
            shutil.copy2(target, backup)
            print("backup: %s -> %s" % (target, backup))
        target.write_text(json.dumps(_hook_doc(), ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
        json.loads(target.read_text(encoding="utf-8"))  # 쓴 즉시 재파싱 검증
        print("applied: %s" % target)
        print("command: %s" % _command())
        return 0
    except (OSError, ValueError) as exc:
        print("FAIL apply: %s" % str(exc)[:120])
        return 1


def _uninstall() -> int:
    target = _hook_json()
    backup = _backup_path(target)
    try:
        if target.exists():
            target.unlink()
            print("removed: %s" % target)
        else:
            print("absent: %s (nothing to remove)" % target)
        if backup.exists():
            shutil.copy2(backup, target)
            print("restored backup: %s" % backup)
        return 0
    except OSError as exc:
        print("FAIL uninstall: %s" % str(exc)[:120])
        return 1


def main() -> int:
    arg = sys.argv[1] if len(sys.argv) > 1 else "--check"
    if arg == "--apply":
        return _apply()
    if arg == "--uninstall":
        return _uninstall()
    if arg == "--check":
        return _check()
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
