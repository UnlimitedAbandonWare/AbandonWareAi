#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""demo-1 Gemini route config probe (read-only).

목적: codex-gemini-chat-catalog-53b36b1f의 A1 잔여 증거 — 실행 JVM의
app.ai.remote-model-selection-routes 관련 유효값을 허용된 읽기 전용 채널로 추정한다.
management/actuator 우회, 설정 변경, 프로세스 메모리 읽기는 절대 하지 않는다.

입력: main/resources 의 application.properties / application.yml /
application-<profile>.<ext> (기본 프로필 local,meta-display) + 체인에 이름이 나온
환경변수(User/Machine/Process). 이름에 KEY/TOKEN/SECRET/PASSWORD가 들어가는
환경변수는 present/absent만 보고한다.

출력: 키별 유효값 + 출처(file:line | env NAME | code-default) 표 또는 --json.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

DEFAULT_KEYS = [
    "app.ai.allow-remote-model-selection",
    "app.ai.remote-model-selection-routes",
    "llmrouter.api-first.enabled",
    "llmrouter.models.gemini-pro.enabled",
    "llmrouter.models.gemini-pro.name",
    "gemini.gateway.enabled",
    "gemini.gateway.purpose.router.enabled",
]

# 설정 파일에 정의가 없을 때 사용하는 코드 기본값 (@Value / bool() 기본값).
# 출처: ChatModelCatalogService.java:48, LlmRouterAspect.java:274.
CODE_DEFAULTS = {
    "llmrouter.api-first.enabled": ("false", "java-default:ChatModelCatalogService.java:48"),
}

SECRET_NAME_RE = re.compile(r"(KEY|TOKEN|SECRET|PASSWORD)", re.IGNORECASE)
PLACEHOLDER_RE = re.compile(r"\$\{")


def _strip_comment(line: str) -> str:
    """YAML/성질상 따옴표 없는 스칼라의 ' #' 주석만 제거한다."""
    out = []
    in_s = in_d = False
    i = 0
    while i < len(line):
        c = line[i]
        if c == "'" and not in_d:
            in_s = not in_s
        elif c == '"' and not in_s:
            in_d = not in_d
        elif c == "#" and not in_s and not in_d and (i == 0 or line[i - 1] in " \t"):
            break
        out.append(c)
        i += 1
    return "".join(out)


def parse_properties(text: str, fname: str):
    """Java .properties 의 key=value/key: value 행을 파싱한다."""
    rows = {}
    buf = ""
    start_line = 0
    for ln, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        if buf:
            buf += line.lstrip()
        else:
            start_line = ln
            buf = line
        if buf.endswith("\\") and not buf.endswith("\\\\"):
            buf = buf[:-1]
            continue
        cur = buf
        buf = ""
        s = _strip_comment(cur).strip()
        if not s or s.startswith("#") or s.startswith("!"):
            continue
        m = re.match(r"([^=: \t]+)\s*[=:]?\s*(.*)$", s)
        if not m:
            continue
        key, val = m.group(1).strip(), m.group(2).strip()
        if key:
            rows[key] = (val, f"{fname}:{start_line}")
    return rows


def parse_yaml_subset(text: str, fname: str):
    """중첩 map + 스칼라만 지원하는 최소 YAML 파서 (본 repo 설정 파일 한정).

    리스트/멀티라인/앵커는 지원하지 않는다. `key:` 컨테이너와 `key: scalar`만 추적하며
    들여쓰기로 경로를 만든다.
    """
    rows = {}
    stack = []  # (indent, path)
    for ln, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        body = _strip_comment(raw).strip()
        if not body or body.startswith("-"):
            continue
        m = re.match(r"^([^:\s][^:]*)\s*:\s*(.*)$", body)
        if not m:
            continue
        key = m.group(1).strip().strip('"').strip("'")
        val = m.group(2).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = ".".join([p for _, p in stack] + [key])
        if val == "" or val == "|" or val == ">":
            stack.append((indent, key))
        else:
            if (val.startswith('"') and val.endswith('"')) or (
                val.startswith("'") and val.endswith("'")
            ):
                val = val[1:-1]
            rows[path] = (val, f"{fname}:{ln}")
    return rows


def resolve_placeholders(value: str, lookup):
    """${NAME:default} 체인을 재귀 해석한다. lookup(name)->(value|None)."""
    out = value
    for _ in range(8):
        m = PLACEHOLDER_RE.search(out)
        if not m:
            break
        depth = 0
        end = -1
        for i in range(m.end(), len(out)):
            if out[i] == "{":
                depth += 1
            elif out[i] == "}":
                if depth == 0:
                    end = i
                    break
                depth -= 1
        if end < 0:
            break
        inner = out[m.end() : end]
        # 첫 번째 ':'는 이름/기본값 경계 — 다만 중첩 ${} 안의 ':'는 제외
        name, default, lvl, split_at = inner, "", 0, -1
        for j, ch in enumerate(inner):
            if ch == "{":
                lvl += 1
            elif ch == "}":
                lvl -= 1
            elif ch == ":" and lvl == 0:
                split_at = j
                break
        if split_at >= 0:
            name, default = inner[:split_at], inner[split_at + 1 :]
        env_val = lookup(name)
        rep = env_val if env_val is not None else resolve_placeholders(default, lookup)
        out = out[: m.start()] + rep + out[end + 1 :]
    return out


def env_names_in(chain: str):
    names = []
    for m in PLACEHOLDER_RE.finditer(chain):
        depth, end = 0, -1
        for i in range(m.end(), len(chain)):
            if chain[i] == "{":
                depth += 1
            elif chain[i] == "}":
                if depth == 0:
                    end = i
                    break
                depth -= 1
        if end < 0:
            continue
        inner = chain[m.end() : end]
        lvl, split_at = 0, -1
        for j, ch in enumerate(inner):
            if ch == "{":
                lvl += 1
            elif ch == "}":
                lvl -= 1
            elif ch == ":" and lvl == 0:
                split_at = j
                break
        names.append(inner[:split_at] if split_at >= 0 else inner)
    return names


def read_env(name: str):
    """Process -> User -> Machine 순으로 환경변수를 읽는다(Windows는 registry)."""
    if name in os.environ:
        return os.environ[name], f"env:{name}(Process)"
    try:
        import winreg

        for hive, sub, label in (
            (winreg.HKEY_CURRENT_USER, r"Environment", "User"),
            (
                winreg.HKEY_LOCAL_MACHINE,
                r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment",
                "Machine",
            ),
        ):
            try:
                with winreg.OpenKey(hive, sub) as k:
                    val, _ = winreg.QueryValueEx(k, name)
                    return str(val), f"env:{name}({label})"
            except OSError:
                continue
    except ImportError:
        pass
    return None, None


def collect_env_snapshot(path: str):
    """선택적 launcher env 스냅샷(JSON {name:value}) — 체인에 나온 이름만 사용한다."""
    if not path or not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return {str(k): str(v) for k, v in data.items()}
    except (OSError, ValueError):
        pass
    return {}


def probe(root: str, profiles, keys, env_snapshot=None, env_reader=None):
    env_reader = env_reader or read_env
    env_snapshot = env_snapshot or {}

    layers = []  # (fname, rows)
    base_dir = os.path.join(root, "main", "resources")
    candidates = ["application.properties", "application.yml"]
    for p in profiles:
        candidates += [f"application-{p}.properties", f"application-{p}.yml", f"application-{p}.yaml"]
    loaded = []
    for fname in candidates:
        fp = os.path.join(base_dir, fname)
        if not os.path.isfile(fp):
            continue
        with open(fp, encoding="utf-8", errors="replace") as f:
            text = f.read()
        rows = parse_properties(text, fname) if fname.endswith(".properties") else parse_yaml_subset(text, fname)
        layers.append((fname, rows))
        loaded.append(fname)
        # 로딩된 파일의 spring.config.import를 보고만 한다(그 파일 자체를 열지는 않음).
        for k, (v, _src) in rows.items():
            if k == "spring.config.import":
                layers[-1][1][k] = (v, layers[-1][1][k][1])

    result = {}
    for key in keys:
        best = None  # (raw, src)
        for fname, rows in layers:
            if key in rows:
                best = (rows[key][0], rows[key][1])
        entry = {"key": key, "effective": None, "source": None, "chain": None, "env": {}}
        if best is None:
            cd = CODE_DEFAULTS.get(key)
            if cd:
                entry["effective"], entry["source"] = cd[0], cd[1]
            else:
                entry["effective"], entry["source"] = None, "not-defined"
            result[key] = entry
            continue
        raw, src = best
        entry["chain"] = raw
        names = env_names_in(raw)
        seen = set()

        def lookup(nm):
            if nm in seen:
                return None
            seen.add(nm)
            if nm in env_snapshot:
                return env_snapshot[nm]
            val, _ = env_reader(nm)
            return val

        for nm in names:
            masked = bool(SECRET_NAME_RE.search(nm))
            if nm in env_snapshot:
                entry["env"][nm] = {
                    "state": "set(snapshot)",
                    "value": ("<masked:present>" if masked else env_snapshot[nm]),
                }
            else:
                val, scope = env_reader(nm)
                entry["env"][nm] = {
                    "state": "unset" if val is None else f"set({scope})",
                    "value": ("<masked:present>" if (masked and val is not None) else val),
                }
        entry["effective"] = resolve_placeholders(raw, lookup)
        env_set = [n for n in names if entry["env"].get(n, {}).get("state", "unset") != "unset"]
        # 비밀명(env 이름에 KEY/TOKEN/SECRET/PASSWORD)이 실제로 설정돼 기여하면
        # effective 출력도 가린다 — 값은 어디에도 쓰지 않는다.
        secret_set = [n for n in env_set if SECRET_NAME_RE.search(n)]
        if secret_set:
            entry["effective"] = f"<masked:secret-env {secret_set[0]} set>"
        entry["source"] = f"{src}" + (f" + env {env_set[0]}" if env_set else " (env unset → default)")
        result[key] = entry
    return {"profiles": list(profiles), "filesLoaded": loaded, "keys": result}


def main(argv=None):
    ap = argparse.ArgumentParser(description="read-only Gemini route config probe")
    ap.add_argument("--root", default=".")
    ap.add_argument("--profiles", default="local,meta-display")
    ap.add_argument("--key", action="append", default=None)
    ap.add_argument("--env-snapshot", default="")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    profiles = [p.strip() for p in args.profiles.split(",") if p.strip()]
    keys = args.key or DEFAULT_KEYS
    snap = collect_env_snapshot(args.env_snapshot) if args.env_snapshot else {}
    rep = probe(args.root, profiles, keys, env_snapshot=snap)

    if args.json:
        print(json.dumps(rep, ensure_ascii=False, indent=2))
        return 0
    print(f"profiles={','.join(profiles)} files={','.join(rep['filesLoaded'])}")
    for k in keys:
        e = rep["keys"][k]
        print(f"- {k} = {e['effective']}  [{e['source']}]")
        for nm, st in e["env"].items():
            print(f"    env {nm}: {st['state']} value={st['value']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
