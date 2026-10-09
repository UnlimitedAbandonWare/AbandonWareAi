#!/usr/bin/env python3
"""model_default_probe — report the effective /chat default model chain (read-only).

Answers: "what is the effective default chat model and where does it come from?"
Chain: personal-override (/api/settings/preferences, owner-cookie scope)
     > DB currentModel (live only) > app.ai.ui-default-model > app.ai.default-model
     > chat.defaults.model > llm.chat-model > hardcoded gemma4:26b.

Stdlib only. Never reads .env/.secrets/credential files. No LLM calls.

Usage:
  python -B scripts/model_default_probe.py [--json] [--log <out.log>]
                                          [--base http://127.0.0.1:18180]
                                          [--no-server]

  python -B scripts/model_default_probe.py --focus            (Nova Focus effective model resolver)
  python -B scripts/model_default_probe.py --ready [--json]     (Nova ready gate: say '테스트해도 됩니다' only on READY)
  python -B scripts/model_default_probe.py --restarts-since 17:00   (DevWatch + launcher restarts, for 재시작 알림)

Verdicts:
  AUTH_FIRST_OK              effective default is a chatgpt-oauth: route
  AUTH_DEFAULT_BUT_ROUTE_BROKEN  auth default + recent chatgpt_oauth_* failures in --log
  LOCAL_DEFAULT_VIOLATION    effective default resolves to a local Ollama model
  API_DEFAULT_INTERMEDIATE   effective default is an API (non-oauth, non-local) id
  STATIC_LOCAL_DEFAULT       server down; static chain still lands on local
  STATIC_AUTH_PROJECTED      server down; launcher override will make it auth next start
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL_HINT = re.compile(r"^(?!chatgpt-oauth:|llmrouter\.|gpt-|openai|o\d|o-|claude|gemini|openai/|meta-llama/|llama)(.+)$")
OAUTH_REASON = re.compile(r"reasonCode=(chatgpt_oauth_[a-z0-9_]+)")
ENV_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_.-]+)(?::([^}]*))?\}")

SCAN_FILES = [
    "main/resources/application.yml",
    "main/resources/application-llm.yaml",
    "main/resources/application-local.yml",
    "main/resources/application-local-llm.yml",
    "main/resources/application-dev.yml",
    "main/resources/application-proj-override.yml",
    "main/resources/application-meta-display.yml",
    "main/java/com/example/lms/web/PageController.java",
    "main/java/com/example/lms/web/ChatUiViewConfig.java",
    "main/java/com/example/lms/llm/ModelCapabilities.java",
    "main/java/com/example/lms/llm/DynamicChatModelFactory.java",
    "main/java/com/example/lms/config/LangChain4jBeans.java",
    "main/java/com/example/lms/config/LangChainConfig.java",
    "main/java/com/example/lms/llm/OpenAiChatModel.java",
    "main/java/com/example/lms/service/EnsembleJudgeService.java",
    "main/java/com/example/lms/config/NovaPropertyAliasEnvironmentPostProcessor.java",
    "configs/agent-test-model-policy.yaml",
]

MAX_HITS_PER_FILE = 40


def read_lines(rel):
    path = ROOT / rel
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None


def prop_default(lines, key):
    """Return the :default inside ${KEY:default} or `key: value` yaml scalar."""
    if lines is None:
        return None
    hit = {"placeholder": None, "scalar": None}
    needle = "${" + key + ":"
    scalar = re.compile(r"^\s*" + re.escape(key.split(".")[-1]) + r"\s*:\s*(\S+)")
    for ln, line in enumerate(lines, 1):
        if needle in line:
            m = re.search(re.escape("${" + key + ":") + r"([^}]*)}", line)
            hit["placeholder"] = {"line": ln, "default": m.group(1) if m else None}
        elif scalar.match(line) and "${" not in line:
            hit["scalar"] = {"line": ln, "value": scalar.match(line).group(1)}
    return hit if hit["placeholder"] or hit["scalar"] else None


def scan_gemma4():
    out = {}
    for rel in SCAN_FILES:
        lines = read_lines(rel)
        if lines is None:
            continue
        hits = [ln for ln, l in enumerate(lines, 1) if "gemma4:26b" in l]
        if hits:
            out[rel] = hits[:MAX_HITS_PER_FILE]
    return out


def launcher_override():
    lines = read_lines("scripts/start_rag_stack.ps1") or []
    found = {}
    for ln, line in enumerate(lines, 1):
        for key in ("APP_AI_UI_DEFAULT_MODEL", "APP_AI_DEFAULT_MODEL"):
            m = re.search(key + r"\s*=\s*'([^']*)'", line)
            if m:
                found[key] = {"line": ln, "value": m.group(1)}
    return {
        "file": "scripts/start_rag_stack.ps1",
        "defaults": found,
        "processEnv": {k: os.environ.get(k) for k in
                       ("APP_AI_UI_DEFAULT_MODEL", "APP_AI_DEFAULT_MODEL")
                       if os.environ.get(k)},
    }


def oauth_models():
    path = ROOT / "data/agent-handoff/chatgpt-oauth/models.json"
    info = {"file": "data/agent-handoff/chatgpt-oauth/models.json", "present": path.is_file()}
    if not path.is_file():
        return info
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        info["error"] = "unreadable"
        return info
    models = [m for m in doc.get("models") or [] if isinstance(m, str)]
    info.update({
        "status": doc.get("status"),
        "syncedAtUtc": doc.get("syncedAtUtc"),
        "count": doc.get("count"),
        "models": models,
        "routeIds": ["chatgpt-oauth:" + m for m in models],
        "recommended": "chatgpt-oauth:gpt-5.5" if "gpt-5.5" in models
                       else ("chatgpt-oauth:" + models[0] if models else None),
    })
    return info


def test_policy():
    lines = read_lines("configs/agent-test-model-policy.yaml") or []
    purposes = {}
    current = None
    for line in lines:
        m = re.match(r"^  ([a-z_]+):\s*$", line)
        if m:
            current = m.group(1)
            continue
        m = re.match(r"^\s*prefer:\s*\[([^\]]*)\]", line)
        if m and current:
            items = [x.strip().strip('"') for x in m.group(1).split(",") if x.strip()]
            purposes[current] = items
            current = None
    gate = None
    for line in lines:
        if "apiFirstUntil" in line:
            gate = line.split(":", 1)[1].strip().strip('"')
    return {"purposesPrefer": purposes, "apiFirstUntil": gate}


def get_json(url, timeout=3.0):
    req = urllib.request.Request(url, headers={"Accept": "application/json",
                                               "Cookie": "ownerKey=devin-model-probe-01"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return e.code, None
    except (OSError, ValueError):
        return None, None


def server_probe(base):
    out = {"base": base, "reachable": False}
    st, models = get_json(base.rstrip("/") + "/api/chat/models")
    if st is None:
        out["modelsEndpoint"] = {"status": "unreachable"}
        return out
    out["reachable"] = True
    if st == 200 and isinstance(models, list):
        auth = [c.get("id") for c in models
                if isinstance(c, dict) and str(c.get("id", "")).startswith("chatgpt-oauth:")]
        local = [c.get("id") for c in models
                 if isinstance(c, dict) and isinstance(c.get("id"), str)
                 and LOCAL_HINT.match(c["id"])]
        out["modelsEndpoint"] = {
            "status": st, "count": len(models),
            "authIds": auth, "localCount": len(local),
        }
    else:
        out["modelsEndpoint"] = {"status": st}
    st2, prefs = get_json(base.rstrip("/") + "/api/settings/preferences")
    if st2 == 200 and isinstance(prefs, dict):
        eff = prefs.get("effective") or {}
        src = prefs.get("sources") or {}
        fac = prefs.get("factoryDefaults") or {}
        ovr = prefs.get("overrides") or {}
        out["preferences"] = {
            "status": st2,
            "effectiveModel": eff.get("model"),
            "modelSource": src.get("model"),
            "factoryModel": fac.get("model"),
            "overrideModel": ovr.get("model"),
            "scope": prefs.get("ownerScope"),
        }
    else:
        out["preferences"] = {"status": st2}
    return out


def log_scan(path):
    if not path:
        latest = sorted((ROOT / "var/rag-launcher").glob(
            "*/chat-ui-vibe-listener-18180.out.log"),
            key=lambda p: p.stat().st_mtime if p.is_file() else 0, reverse=True)
        path = str(latest[0]) if latest else None
    if not path or not Path(path).is_file():
        return {"path": path, "present": False}
    counts = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                for m in OAUTH_REASON.findall(line):
                    counts[m] = counts.get(m, 0) + 1
    except OSError:
        return {"path": path, "present": False}
    return {"path": path, "present": True, "oauthFailureReasonCodes": counts}


def lane_of(model_id):
    s = (model_id or "").strip()
    if s.startswith("chatgpt-oauth:"):
        return "auth"
    if s.startswith("llmrouter.") or s.startswith("gpt-") or s.startswith("openai") \
            or s.startswith("o-") or re.match(r"o\d", s) or "/" in s \
            or s.startswith("gemini") or s.startswith("claude"):
        return "api"
    if not s:
        return "unknown"
    return "local"


def static_chain(cfg, launcher):
    """Best offline guess: env > app.ai.ui-default-model > llm.chat-model > hardcode."""
    env_ui = (launcher["processEnv"].get("APP_AI_UI_DEFAULT_MODEL")
              or (launcher["defaults"].get("APP_AI_UI_DEFAULT_MODEL") or {}).get("value"))
    if env_ui:
        return env_ui, "launcher-env:app.ai.ui-default-model"
    ui = ((cfg.get("app.ai.ui-default-model") or {}).get("placeholder") or {}).get("default")
    if ui and not ui.startswith("${"):
        return ui, "property:app.ai.ui-default-model"
    chat = ((cfg.get("llm.chat-model") or {}).get("placeholder") or {}).get("default")
    if chat and not chat.startswith("${"):
        return chat, "property:llm.chat-model"
    return "gemma4:26b", "hardcode:ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL"


# ---- Nova Focus effective model resolver (--focus), added 2026-10-09 by Grok Bot ----
# Same logic as C:\Users\nninn\grokbot-tools\served_asset_check.py --resolve. Read-only:
# saved profile (scripts/db_agent.py SELECT) + /api/chat/models selectable + focus_terminal events
# + dev-reload last Spring restart vs source mtimes + luna preset order. Verdicts:
# MODEL_NOT_SELECTABLE > STALE_RUNTIME > PRESET_WRITER_RISK > PROFILE_UNREAD > DEFAULT_SHADOWED_BY_PROFILE > OK
import datetime as _dt_mod, hashlib, subprocess, pathlib, types
KST = _dt_mod.timezone(_dt_mod.timedelta(hours=9))
SECRETISH = re.compile(r'(sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,}|ghp_[A-Za-z0-9]{12,}|Bearer\s+\S+|(?i:api[_-]?key|token|secret|password)\s*[=:]\s*\S+)')
WATCH_GLOBS = ['main/resources/application*.yml', 'main/java/com/example/lms/assist/NovaFocus*.java',
               'main/java/com/example/lms/service/ChatModelCatalogService.java',
               'main/resources/static/assets/display/display-focus*.js']
PRESET_JS = 'main/resources/static/assets/display/display-focus-controls.js'
ORDER = ['MODEL_NOT_SELECTABLE', 'STALE_RUNTIME', 'PRESET_WRITER_RISK', 'PROFILE_UNREAD',
         'DEFAULT_SHADOWED_BY_PROFILE', 'OK']
ACTIONABLE = {'MODEL_NOT_SELECTABLE', 'STALE_RUNTIME', 'PRESET_WRITER_RISK', 'PROFILE_UNREAD'}

def sha(b): return hashlib.sha256(b).hexdigest()
def mask(v):
    if isinstance(v, str): return SECRETISH.sub('***', v)
    if isinstance(v, dict): return {k: mask(x) for k, x in v.items()}
    if isinstance(v, list): return [mask(x) for x in v]
    return v
def kst(ms):
    try: return _dt_mod.datetime.fromtimestamp(ms / 1000, KST).strftime('%Y-%m-%d %H:%M:%S KST')
    except Exception: return None

def http_json(url, timeout=8):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'Cache-Control': 'no-cache'}), timeout=timeout) as r:
        return json.load(r)

def check_assets(paths, root, base, prefix, fetch=None):
    rows, bad = [], 0
    for p in paths:
        rel = p.replace('\\', '/'); src = root / rel
        url_path = rel.split(prefix, 1)[1] if prefix in rel else rel
        url = base.rstrip('/') + '/' + url_path.lstrip('/')
        row = {'source': rel, 'url': url, 'sourceSha12': sha(src.read_bytes())[:12] if src.exists() else None}
        try:
            if fetch is not None:  # tests / --ready: fetch(url) -> bytes
                body = fetch(url); row.update(status=200, servedSha12=sha(body)[:12])
            else:
                req = urllib.request.Request(url, headers={'Cache-Control': 'no-cache'})
                with urllib.request.urlopen(req, timeout=8) as r:
                    body = r.read(); row.update(status=r.status, servedSha12=sha(body)[:12], cacheControl=r.headers.get('Cache-Control'))
        except Exception as e:
            row.update(error=type(e).__name__ + ': ' + str(e)[:120])
        row['match'] = row.get('servedSha12') is not None and row.get('servedSha12') == row['sourceSha12']
        bad |= not row['match']; rows.append(row)
    return rows, bad

def catalog_index(cat):
    if isinstance(cat, dict): cat = cat.get('models') or cat.get('items') or []
    return {x.get('id'): x for x in cat if isinstance(x, dict)}

def check_models(ids, by):
    rows, bad = [], 0
    for m in ids:
        x = by.get(m); row = {'id': m, 'present': x is not None}
        if x: row.update({k: x.get(k) for k in ('provider', 'modelId', 'selectable', 'reason', 'status')})
        bad |= not (x and x.get('selectable') is True); rows.append(row)
    return rows, bad

# ---- resolver pieces (each returns plain dicts; injectable for tests) ----
PROFILE_SQL = ("SELECT LOWER(LEFT(RAWTOHEX(HASH('SHA-256', STRINGTOUTF8(owner_key))),12)) AS owner_hash, "
               "settings_version, settings_json FROM nova_focus_profile WHERE channel='live' "
               "ORDER BY settings_version DESC LIMIT 20")

def read_profiles(root, runner=None):
    """SELECT-only through the project's existing scripts/db_agent.py."""
    agent = root / 'scripts' / 'db_agent.py'
    if runner is None:
        if not agent.exists(): return {'ok': False, 'reason': 'db_agent_missing'}
        def runner():
            env = dict(os.environ, PYTHONIOENCODING='utf-8')
            r = subprocess.run([sys.executable, '-B', str(agent), 'query', '--via', 'live', '--sql', PROFILE_SQL,
                                '--max-rows', '20'], capture_output=True, timeout=30, cwd=str(root), env=env)
            return r.returncode, r.stdout.decode('utf-8-sig', 'replace')
    try:
        code, out = runner(); d = json.loads(out)
    except Exception as e:
        return {'ok': False, 'reason': 'db_agent_failed:' + type(e).__name__}
    cols = d.get('columns', []); rows = []
    for item in d.get('rows', []):
        row = item if isinstance(item, dict) else dict(zip(cols, item)); row = {k.lower(): v for k, v in row.items()}
        try: s = json.loads(row.get('settings_json') or '{}')
        except Exception: s = {}
        rows.append({'ownerHash': str(row.get('owner_hash') or '')[:12], 'settingsVersion': row.get('settings_version'),
                     'answerSelection': mask(s.get('answerSelection')), 'webSearchEnabled': s.get('webSearchEnabled'),
                     'reasoningPreset': s.get('reasoningPreset')})
    return {'ok': bool(d.get('ok', code == 0)), 'via': d.get('via'), 'rows': rows}

def recent_focus(events, limit=5):
    if isinstance(events, dict): events = events.get('events') or events.get('items') or []
    out = []
    for e in events:
        d = (e.get('data') if isinstance(e, dict) else None) or {}
        if d.get('stage') != 'focus_terminal': continue
        am = d.get('answerModel') or {}
        out.append({'at': kst(e.get('tsMs') or d.get('observedAtMs') or 0), 'tsMs': e.get('tsMs'),
                    'ownerHash': str(d.get('ownerHash', '')).replace('hash:', '')[:12],
                    'outcome': d.get('outcome'), 'reasonCode': d.get('reasonCode'),
                    'exceptionClass': d.get('exceptionClass'), 'latencyMs': d.get('latencyMs'),
                    'serverInstanceHash': d.get('serverInstanceHash'),
                    'fallbackReason': am.get('focus.selection.fallbackReason') or am.get('fallbackReason'),
                    'selectedModel': am.get('selectedModel') or am.get('effectiveModel')})
    out.sort(key=lambda r: r.get('tsMs') or 0, reverse=True)
    return out[:limit]

RESTART_RE = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) \[DEV-RELOAD\] Spring restart')  # grokbot3: was '.*Spring restart' -> matched 'no Spring restart' static lines
def freshness(root, log_text=None):
    log = root / 'var' / 'dev-reload' / 'dev-reload.log'
    if log_text is None:
        log_text = log.read_text(encoding='utf-8', errors='replace') if log.exists() else ''
    last = None
    for line in log_text.splitlines():
        m = RESTART_RE.match(line)
        if m: last = m.group(1)
    # 2026-10-09 grokbot3: launcher ForceRestart runs (var/rag-launcher/<ts>-*/result.json ready, springReused=false)
    # also reload the JVM but never appear in dev-reload.log -> without this, --focus reported false STALE_RUNTIME.
    lr = last_launcher_restart(root)
    if lr and (not last or lr['startedKst'] > last):
        last = lr['startedKst']; res_src = 'launcher:' + lr['run']
    else:
        res_src = 'dev-reload.log' if last else None
    res = {'lastSpringRestart': (last + ' KST') if last else None, 'restartSource': res_src,
           'newerThanRestart': [], 'staticChangedAfterRestart': []}
    if not last: res['note'] = 'no Spring restart line in dev-reload.log or launcher runs'; return res
    t = _dt_mod.datetime.strptime(last, '%Y-%m-%d %H:%M:%S').replace(tzinfo=KST).timestamp()
    for g in WATCH_GLOBS:
        for p in root.glob(g):
            if p.is_file() and p.stat().st_mtime > t + 1:
                rel = p.relative_to(root).as_posix()
                row = {'file': rel, 'mtime': _dt_mod.datetime.fromtimestamp(p.stat().st_mtime, KST).strftime('%H:%M:%S KST')}
                # static assets are tier STATIC (no Spring restart); judged by served sha, not restart time
                (res['staticChangedAfterRestart'] if rel.startswith('main/resources/static/') else res['newerThanRestart']).append(row)
    return res

PRESET_LINE = re.compile(r'\[[^\]\n]*/[^/\n]*/i[^\]\n]*\]')
def preset_order(root, by):
    p = root / PRESET_JS
    if not p.exists(): return {'file': PRESET_JS, 'present': False}
    hits = []
    for n, line in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        if 'luna' not in line.lower(): continue
        for arr in PRESET_LINE.findall(line):
            pats = re.findall(r'/([^/\n]+)/i', arr)
            if not any('luna' in x.lower() for x in pats): continue
            first = pats[0]
            before = pats[:pats.index(next(x for x in pats if 'luna' in x.lower()))]
            # which catalog ids would the earlier patterns hit, and are they selectable?
            risky = []
            for pat in before:
                for cid, x in by.items():
                    if cid and re.search(pat, cid, re.I) and x.get('selectable') is not True:
                        risky.append(cid)
            hits.append({'line': n, 'patterns': pats, 'firstPattern': first,
                         'nonSelectableBeforeLuna': sorted(set(risky))})
    return {'file': PRESET_JS, 'present': True, 'lists': hits}

def resolve(a, root, http=http_json, profile_runner=None, log_text=None):
    base = a.base.rstrip('/'); out = {'checkedAt': _dt_mod.datetime.now(KST).strftime('%Y-%m-%d %H:%M:%S KST')}
    try: by = catalog_index(http(base + '/api/chat/models'))
    except Exception as e: return {'verdicts': ['SERVER_UNREACHABLE'], 'error': type(e).__name__ + ': ' + str(e)[:120]}, 2
    try: focus = recent_focus(http(base + '/api/diagnostics/debug/events?limit=500'))
    except Exception as e: focus = []; out['eventsError'] = type(e).__name__
    out['recentFocusTerminals'] = focus
    owner = (a.owner_hash or '').replace('hash:', '')[:12] or next((f['ownerHash'] for f in focus if f['ownerHash']), '')
    out['ownerHash'] = owner or None
    prof = read_profiles(root, profile_runner); verdicts = []; why = []
    row = None
    if prof.get('ok'):
        cands = [r for r in prof['rows'] if not owner or r['ownerHash'] == owner]
        row = max(cands, key=lambda r: r.get('settingsVersion') or 0) if cands else None
    out['profile'] = row if row else {'readOk': prof.get('ok'), 'reason': prof.get('reason') or 'owner_not_found'}
    if row is None and not prof.get('ok'):
        verdicts.append('PROFILE_UNREAD'); why.append('saved profile not read; do not assume AUTO/defaults apply')
    elif row is None:
        why.append('owner has no saved nova_focus_profile row -> server defaults (AUTO) apply')  # grokbot3
    sel = (row or {}).get('answerSelection') or {}
    mode = sel.get('mode') or ('AUTO' if row else None)
    model = sel.get('modelId'); routing = sel.get('routing') or {}
    eff = {'mode': mode, 'requestedModel': model, 'executionTarget': routing.get('executionTarget'),
           'fallbackAllowed': routing.get('effectiveFallbackAllowed', routing.get('fallbackAllowed'))}
    if mode == 'FIXED':
        x = by.get(model); eff['catalog'] = {k: x.get(k) for k in ('selectable', 'reason', 'provider', 'modelId')} if x else None
        if not x or x.get('selectable') is not True:
            verdicts.append('MODEL_NOT_SELECTABLE')
            why.append(f'FIXED {model} is {"absent" if not x else "selectable=" + str(x.get("selectable"))} in /api/chat/models'
                       + ('; fallback OFF -> admission fails before ChatWorkflow' if not eff['fallbackAllowed'] else ''))
        verdicts.append('DEFAULT_SHADOWED_BY_PROFILE')
        why.append('owner is FIXED: AUTO default / yml default changes do not reach this owner')
    out['effective'] = eff
    out['freshness'] = fr = freshness(root, log_text)
    if fr.get('newerThanRestart'):
        verdicts.append('STALE_RUNTIME'); why.append(f"{len(fr['newerThanRestart'])} watched file(s) changed after last Spring restart {fr['lastSpringRestart']}")
    if fr.get('staticChangedAfterRestart'):
        sa, sbad = check_assets([r['file'] for r in fr['staticChangedAfterRestart']], root, base,
                                'main/resources/static/', fetch=getattr(a, 'fetch', None))
        out['staticServedCheck'] = sa
        if sbad and 'STALE_RUNTIME' not in verdicts:
            verdicts.append('STALE_RUNTIME'); why.append('static file changed after restart and served sha != disk sha')
    if a.paths:
        assets, bad = check_assets(a.paths, root, base, a.static_prefix); out['assets'] = assets
        if bad and 'STALE_RUNTIME' not in verdicts: verdicts.append('STALE_RUNTIME'); why.append('served asset sha != source sha')
    out['presetWriter'] = pw = preset_order(root, by)
    if any(h['nonSelectableBeforeLuna'] for h in pw.get('lists', [])):
        verdicts.append('PRESET_WRITER_RISK')
        why.append('preset list matches a non-selectable id before luna: ' + ', '.join(
            f"{PRESET_JS}:{h['line']} -> {h['nonSelectableBeforeLuna']}" for h in pw['lists'] if h['nonSelectableBeforeLuna']))
    if not verdicts: verdicts = ['OK']
    verdicts = sorted(set(verdicts), key=ORDER.index)
    out['verdicts'] = verdicts; out['why'] = why
    out['next'] = {'MODEL_NOT_SELECTABLE': 'fix the writer (preset picks selectable only + save-time validation); do not edit AUTO defaults',
                   'STALE_RUNTIME': 'reload via demo1-dev-reload and re-run this check before any live test',
                   'PRESET_WRITER_RISK': 'reorder/filter preset by catalog selectable (lease owner only)',
                   'PROFILE_UNREAD': 'read the profile (db_agent SELECT) before choosing a file',
                   'DEFAULT_SHADOWED_BY_PROFILE': 'target the saved FIXED selection path, not defaults',
                   'OK': 'model path is reachable; look downstream (ChatWorkflow/provider)'}[verdicts[0]]
    return out, (1 if set(verdicts) & ACTIONABLE else 0)

def focus_main(args):
    out, code = resolve(args, ROOT)
    text = json.dumps(mask(out), ensure_ascii=False, indent=2)
    if args.json_out: Path(args.json_out).write_text(text, encoding='utf-8')
    print(text)
    return code


# ---- Nova ready gate (--ready) + restart notices (--restarts-since), added 2026-10-09 by Grok Bot (grokbot3) ----
# Read-only. No model calls, no writes, no restarts. Answers ONE question for the user:
# "can I test Nova Focus now?"  READY only when (a) one live runtime and no launcher mid-(re)start,
# (b) --focus model path OK, (c) no Java/yml change after the last JVM restart (static: served sha == disk),
# (d) a real focus_terminal success after the last restart in logs/debug-events.ndjson.
# Launcher lock = named kernel mutex Local\AWX-RAG-<sha256(lower root)[:20]> (start_rag_stack.ps1:733/746/747).
# A kernel mutex cannot go stale (released when its process exits); the stale thing is the RECORD
# (var/rag-launcher/LATEST.json + spring-owned.json) -> verdict RUNTIME_RECORD_STALE.
READY_ORDER = ['RUNTIME_DOWN', 'LAUNCHER_BUSY', 'RUNTIME_RECORD_STALE', 'STALE_RUNTIME', 'FOCUS_MODEL_BLOCKED',
               'FOCUS_LAST_FAILED', 'FOCUS_MODEL_UNVERIFIED', 'NOT_PROVEN_FOCUS', 'READY']
RUN_DIR_RE = re.compile(r'^(\d{8})-(\d{6})-(stop-)?([0-9a-f]{8})$')
HEALTH_URL = 'http://127.0.0.1:18181/actuator/health'

def _load_json(p):
    try: return json.loads(pathlib.Path(p).read_text(encoding='utf-8-sig'))
    except Exception: return None

def _kst_from_iso(text):
    try:
        t = re.sub(r'(\.\d{6})\d+', r'\1', str(text).strip()).replace('Z', '+00:00')  # .NET 7-digit fraction
        d = _dt_mod.datetime.fromisoformat(t)
        if d.tzinfo is None: d = d.replace(tzinfo=_dt_mod.timezone.utc)
        return d.astimezone(KST)
    except Exception: return None

def launcher_runs(root, since=None):
    """var/rag-launcher/<yyyyMMdd-HHmmss>-<8hex>[ /stop-] dirs (dir time = KST local) newest first."""
    base = pathlib.Path(root) / 'var' / 'rag-launcher'; out = []
    if not base.is_dir(): return out
    for d in base.iterdir():
        m = RUN_DIR_RE.match(d.name)
        if not m or not d.is_dir(): continue
        started = _dt_mod.datetime.strptime(m.group(1) + m.group(2), '%Y%m%d%H%M%S').replace(tzinfo=KST)
        if since and started < since - _dt_mod.timedelta(hours=1): continue
        r = _load_json(d / 'result.json') or {}
        row = {'run': d.name, 'started': started, 'kind': 'stop' if m.group(3) else 'start',
               'status': r.get('status'), 'role': r.get('role') or r.get('runtimeRole'), 'caller': r.get('caller'),
               'springReused': r.get('springReused'), 'springPid': r.get('springPid'),
               'ready': _kst_from_iso(r.get('completedAtUtc')) if r.get('status') == 'ready' else None,
               'failurePoint': r.get('failurePoint')}
        if r.get('status') == 'failed':
            try:
                head = (d / 'launcher.log').read_text(encoding='utf-8', errors='replace')[:4000]
                mm = re.search(r'reason=(\S+)', head); row['reason'] = mm.group(1) if mm else None
            except OSError: row['reason'] = None
        out.append(row)
    out.sort(key=lambda r: r['started'], reverse=True)
    return out

def last_launcher_restart(root):
    for r in launcher_runs(root, since=_dt_mod.datetime.now(KST) - _dt_mod.timedelta(days=3)):
        if r['kind'] == 'start' and r['status'] == 'ready' and r['springReused'] is False:
            return {'run': r['run'], 'startedKst': r['started'].strftime('%Y-%m-%d %H:%M:%S')}
    return None

def parse_since(text, now=None):
    now = now or _dt_mod.datetime.now(KST); t = (text or '').strip()
    m = re.fullmatch(r'(\d+)\s*([mh])', t)
    if m: return now - _dt_mod.timedelta(minutes=int(m.group(1)) * (60 if m.group(2) == 'h' else 1))
    for fmt in ('%Y-%m-%d %H:%M:%S', '%Y-%m-%d %H:%M', '%H:%M:%S', '%H:%M'):
        try:
            d = _dt_mod.datetime.strptime(t, fmt)
            if fmt.startswith('%H'): d = d.replace(year=now.year, month=now.month, day=now.day)
            return d.replace(tzinfo=KST)
        except ValueError: pass
    raise ValueError('use HH:MM, "YYYY-MM-DD HH:MM" or 30m/2h')

DEVLOG_RE = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) \[DEV-RELOAD\] (.*)$')
def restarts_since(root, since, log_text=None):
    """Every JVM (re)start/stop signal since <since>: DevWatch log + launcher runs. Read-only."""
    ev = []
    if log_text is None:
        lp = pathlib.Path(root) / 'var' / 'dev-reload' / 'dev-reload.log'
        log_text = lp.read_text(encoding='utf-8', errors='replace') if lp.exists() else ''
    for line in log_text.splitlines():
        m = DEVLOG_RE.match(line)
        if not m: continue
        at = _dt_mod.datetime.strptime(m.group(1), '%Y-%m-%d %H:%M:%S').replace(tzinfo=KST)
        if at < since: continue
        msg = m.group(2)
        if 'Spring restart' in msg and 'source changed' not in msg: kind = 'DEVWATCH_RESTART_START'
        elif 'socket ready' in msg: kind = 'DEVWATCH_CLIENT_RECONNECTED'
        elif msg.startswith('armed'): kind = 'DEVWATCH_ARMED'
        elif 'source changed' in msg and 'no Spring restart' not in msg: kind = 'DEVWATCH_TRIGGER'
        else: continue
        files = msg.split(' :: ', 1)[1].split('; ') if ' :: ' in msg else []
        ev.append({'at': at, 'kind': kind, 'detail': ', '.join(pathlib.PurePath(f).name for f in files)[:200]})
    for r in launcher_runs(root, since=since):
        if r['started'] < since: continue
        if r['kind'] == 'stop':
            ev.append({'at': r['started'], 'kind': 'LAUNCHER_STOP', 'detail': r['run']})
        elif r['status'] == 'ready':
            ev.append({'at': r['started'], 'kind': 'LAUNCHER_START', 'detail': r['run']})
            if r['ready']:
                ev.append({'at': r['ready'], 'kind': 'LAUNCHER_READY_REUSED' if r['springReused'] else 'LAUNCHER_READY_NEW_JVM',
                           'detail': f"pid={r['springPid']} role={r['role']} caller={r['caller']}"})
        elif r['status'] == 'failed':
            ev.append({'at': r['started'], 'kind': 'LAUNCHER_FAILED',
                       'detail': f"{r.get('reason') or r.get('failurePoint')} ({r['run']}) - JVM not touched"})
        else:
            ev.append({'at': r['started'], 'kind': 'LAUNCHER_IN_PROGRESS_OR_UNKNOWN', 'detail': r['run']})
    ev.sort(key=lambda e: e['at'])
    return ev

def restart_notice_ko(ev):
    jvm = []
    for e in ev:
        if e['kind'] not in ('DEVWATCH_RESTART_START', 'LAUNCHER_START', 'LAUNCHER_STOP', 'LAUNCHER_READY_NEW_JVM'): continue
        if e['kind'] == 'LAUNCHER_START' and jvm and jvm[-1]['kind'] == 'DEVWATCH_RESTART_START' \
                and abs((e['at'] - jvm[-1]['at']).total_seconds()) <= 10: continue   # DevWatch spawned this launcher run
        jvm.append(e)
    if not jvm: return '재시작 없음'
    parts = []
    for e in jvm:
        label = {'DEVWATCH_RESTART_START': '자동 재시작 시작(DevWatch)', 'LAUNCHER_START': '실행기 재시작 시작',
                 'LAUNCHER_STOP': '서버 정지', 'LAUNCHER_READY_NEW_JVM': '재시작 완료'}[e['kind']]
        parts.append(f"{e['at'].strftime('%H:%M')} {label}")
    return '재시작 감지: ' + ' → '.join(parts[-6:])

def _win():
    return os.name == 'nt'

def mutex_held(root):
    """True/False if the launcher's named mutex currently exists (some launcher is mid-run); None off-Windows."""
    if not _win(): return None
    import ctypes
    norm = str(pathlib.Path(root).resolve()).strip().rstrip('\\/').lower()
    name = 'Local\\AWX-RAG-' + hashlib.sha256(norm.encode('utf-8')).hexdigest()[:20]
    k = ctypes.windll.kernel32
    k.OpenMutexW.restype = ctypes.c_void_p
    h = k.OpenMutexW(0x00100000, False, name)  # SYNCHRONIZE only; never waits/acquires
    if h:
        k.CloseHandle(ctypes.c_void_p(h)); return True
    return False

def pid_alive(pid):
    if not pid: return False
    if not _win():
        try: os.kill(int(pid), 0); return True
        except Exception: return False
    import ctypes
    k = ctypes.windll.kernel32; k.OpenProcess.restype = ctypes.c_void_p
    h = k.OpenProcess(0x1000, False, int(pid))
    if not h: return False
    code = ctypes.c_ulong(); k.GetExitCodeProcess(ctypes.c_void_p(h), ctypes.byref(code)); k.CloseHandle(ctypes.c_void_p(h))
    return code.value == 259

def port_owner(port):
    try:
        out = subprocess.run(['netstat', '-ano', '-p', 'TCP'], capture_output=True, timeout=15).stdout.decode('utf-8', 'replace')
    except Exception: return None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[1].endswith(':' + str(port)) and parts[3].upper() in ('LISTENING', 'LISTEN'):
            try: return int(parts[4])
            except ValueError: return None
    return None

def leftover_launcher_windows():
    """cmd.exe windows still running Start-*.bat (usually a finished/failed run waiting at 'pause')."""
    if not _win(): return []
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='cmd.exe'\" | Where-Object { $_.CommandLine -match "
          "'Start-(Meta-Display|RAG)\\.bat' } | ForEach-Object { '{0}|{1}|{2}' -f $_.ProcessId, "
          "$_.CreationDate.ToString('yyyy-MM-dd HH:mm'), ([IO.Path]::GetFileName(($_.CommandLine -replace '\"',' ').Trim().Split(' ')[-1])) }")
    try:
        out = subprocess.run(['powershell', '-NoProfile', '-Command', ps], capture_output=True, timeout=20).stdout.decode('utf-8', 'replace')
    except Exception: return []
    rows = []
    for line in out.splitlines():
        p = line.strip().split('|')
        if len(p) == 3: rows.append({'pid': int(p[0]), 'since': p[1], 'bat': p[2]})
    return rows

def runtime_state(root, probes=None):
    """(a) launcher/runtime consistency. probes: dict of injectable callables for tests."""
    pr = dict(mutex=mutex_held, alive=pid_alive, port=port_owner, health=None, windows=leftover_launcher_windows)
    pr.update(probes or {})
    root = pathlib.Path(root)
    latest = _load_json(root / 'var' / 'rag-launcher' / 'LATEST.json') or {}
    owned = _load_json(pathlib.Path(latest.get('runDirectory') or (root / '_none')) / 'spring-owned.json') or {}
    listener = ((owned.get('listener') or {}).get('processId')) or latest.get('springPid')
    if pr['health'] is None:
        def _h():
            try: return (http_json(HEALTH_URL, timeout=5) or {}).get('status')
            except Exception: return None
        pr['health'] = _h
    st = {'latestRun': latest.get('runId'), 'latestStatus': latest.get('status'), 'role': latest.get('runtimeRole') or latest.get('role'),
          'latestReadyAt': (_kst_from_iso(latest.get('completedAtUtc')) or _dt_mod.datetime.min.replace(tzinfo=KST)).strftime('%H:%M:%S KST')
                           if latest.get('completedAtUtc') else None,
          'recordedListenerPid': listener, 'launcherMutexHeld': pr['mutex'](root), 'health': pr['health'](),
          'port18180Owner': pr['port'](18180)}
    st['listenerAlive'] = bool(listener) and pr['alive'](listener)
    st['leftoverLauncherWindows'] = pr['windows']()
    if st['health'] != 'UP':
        st['state'] = 'LAUNCHER_BUSY' if st['launcherMutexHeld'] else 'RUNTIME_DOWN'
    elif st['launcherMutexHeld']:
        st['state'] = 'LAUNCHER_BUSY'          # serving, but a launcher is mid-(re)start -> may vanish any second
    elif not st['listenerAlive'] or (st['port18180Owner'] and listener and st['port18180Owner'] != listener):
        st['state'] = 'RUNTIME_RECORD_STALE'   # something answers, but not the run the record describes
    else:
        st['state'] = 'RUNTIME_UP'
    return st

def focus_proof(root, since, lines=None, max_bytes=4_000_000, owner=None):
    """(d) last focus_terminal after <since> from logs/debug-events.ndjson (tail only)."""
    if lines is None:
        p = pathlib.Path(root) / 'logs' / 'debug-events.ndjson'
        if not p.exists(): return {'present': False}
        with open(p, 'rb') as fh:
            fh.seek(0, 2); size = fh.tell(); fh.seek(max(0, size - max_bytes))
            lines = fh.read().decode('utf-8', 'replace').splitlines()
    terms, shown = [], {}
    for line in lines:
        if 'focus_' not in line: continue
        try: e = json.loads(line)
        except ValueError: continue
        d = e.get('data') or {}; at = _kst_from_iso(e.get('ts'))
        if not at or at < since: continue
        if d.get('stage') == 'focus_terminal':
            terms.append({'at': at, 'outcome': d.get('outcome'), 'reasonCode': d.get('reasonCode'),
                          'ownerHash': str(d.get('ownerHash', '')).replace('hash:', '')[:12],
                          'latencyMs': d.get('latencyMs'), 'activation': d.get('activationHash')})
        elif d.get('stage') in ('focus_first_visible', 'focus_presentation_done'):
            shown.setdefault(d.get('activationHash'), set()).add(d.get('stage'))
    others = [t for t in terms if owner and t['ownerHash'] != owner]
    if owner: terms = [t for t in terms if t['ownerHash'] == owner]
    res = {'present': True, 'owner': owner, 'terminalsSinceRestart': len(terms),
           'successSinceRestart': sum(1 for t in terms if t['outcome'] == 'success'),
           'otherOwnersSuccessSinceRestart': sum(1 for t in others if t['outcome'] == 'success')}
    if terms:
        last = terms[-1]; res['last'] = dict(last, at=last['at'].strftime('%H:%M:%S KST'),
                                              displayed=sorted(shown.get(last['activation'], [])))
        ok = [t for t in terms if t['outcome'] == 'success']
        if ok: res['lastSuccessAt'] = ok[-1]['at'].strftime('%H:%M:%S KST')
    return res

def ready_gate(a, root, http=http_json, profile_runner=None, log_text=None, probes=None, event_lines=None, now=None):
    now = now or _dt_mod.datetime.now(KST)
    out = {'checkedAt': now.strftime('%Y-%m-%d %H:%M:%S KST')}
    rt = runtime_state(root, probes); out['runtime'] = rt
    reasons = []
    if rt['state'] in ('RUNTIME_DOWN', 'LAUNCHER_BUSY', 'RUNTIME_RECORD_STALE'):
        reasons.append(rt['state'])
    if rt['state'] != 'RUNTIME_DOWN':
        a = types.SimpleNamespace(**vars(a))
        if not (a.owner_hash or '').strip():
            pr = read_profiles(root, profile_runner)
            rows = [r for r in pr.get('rows') or [] if r.get('answerSelection')]
            if rows:
                a.owner_hash = max(rows, key=lambda r: r.get('settingsVersion') or 0)['ownerHash']
                out['ownerPick'] = 'highest settingsVersion saved profile (user device); override with --owner-hash'
        out['ownerHash'] = (a.owner_hash or '').replace('hash:', '')[:12] or None
        foc, _code = resolve(a, root, http=http, profile_runner=profile_runner, log_text=log_text)
        out['focus'] = {k: foc.get(k) for k in ('verdicts', 'why', 'next', 'effective', 'freshness', 'staticServedCheck')}
        fv = foc.get('verdicts') or []
        if 'SERVER_UNREACHABLE' in fv and 'RUNTIME_DOWN' not in reasons: reasons.append('RUNTIME_DOWN')
        if 'STALE_RUNTIME' in fv: reasons.append('STALE_RUNTIME')
        if set(fv) & {'MODEL_NOT_SELECTABLE', 'PRESET_WRITER_RISK'}: reasons.append('FOCUS_MODEL_BLOCKED')
        if 'PROFILE_UNREAD' in fv: reasons.append('FOCUS_MODEL_UNVERIFIED')
        fr = foc.get('freshness') or {}
        last_restart = fr.get('lastSpringRestart')
        since = (_dt_mod.datetime.strptime(last_restart[:19], '%Y-%m-%d %H:%M:%S').replace(tzinfo=KST)
                 if last_restart else now - _dt_mod.timedelta(hours=1))
        out['lastRestart'] = last_restart
        fp = focus_proof(root, since, lines=event_lines, owner=out['ownerHash']); out['focusProof'] = fp
        if fp.get('last') and fp['last']['outcome'] not in ('success', 'cancelled'): reasons.append('FOCUS_LAST_FAILED')
        elif not fp.get('successSinceRestart'): reasons.append('NOT_PROVEN_FOCUS')
    out['restartsLast60m'] = [dict(e, at=e['at'].strftime('%H:%M:%S')) for e in
                              restarts_since(root, now - _dt_mod.timedelta(minutes=60), log_text=log_text)]
    verdict = sorted(set(reasons), key=READY_ORDER.index)[0] if reasons else 'READY'
    out['verdict'] = verdict; out['reasons'] = sorted(set(reasons), key=READY_ORDER.index)
    out['koreanOneLiner'] = ready_ko(out)
    return out, {'READY': 0, 'RUNTIME_DOWN': 2}.get(verdict, 1)

def ready_ko(o):
    rt = o.get('runtime') or {}; fp = o.get('focusProof') or {}; v = o['verdict']
    role = rt.get('role') or '?'; pid = rt.get('recordedListenerPid')
    lr = (o.get('lastRestart') or '')[11:16]
    win = rt.get('leftoverLauncherWindows') or []
    tail = f" 남아 있는 실행기 창 {len(win)}개는 닫아도 됩니다(서버와 무관)." if win and v != 'LAUNCHER_BUSY' else ''
    if v == 'READY':
        return (f"테스트해도 됩니다: 서버({role}, pid {pid}) 정상, 모델 경로 OK, 재시작({lr}) 이후 "
                f"노바 답변 성공 {fp.get('lastSuccessAt', '')[:5]} 확인." + tail)
    if v == 'RUNTIME_DOWN':
        return '서버가 꺼져 있거나 응답하지 않습니다(health≠UP). 아직 테스트하지 마세요.' + tail
    if v == 'LAUNCHER_BUSY':
        return '다른 실행기가 지금 서버를 (재)기동하는 중입니다. 방금 띄운 창은 닫고, 끝날 때까지 기다려 주세요(다시 실행하지 마세요).'
    if v == 'RUNTIME_RECORD_STALE':
        return f"응답하는 서버가 실행 기록(pid {pid})과 다릅니다. 확인 전에는 테스트하지 마세요." + tail
    if v == 'STALE_RUNTIME':
        n = len(((o.get('focus') or {}).get('freshness') or {}).get('newerThanRestart') or [])
        return f"서버는 켜져 있지만 마지막 재시작({lr}) 이후 바뀐 소스 {n}개가 아직 반영 안 됐습니다. 재시작 후 다시 확인할게요." + tail
    if v == 'FOCUS_MODEL_BLOCKED':
        fv = (o.get('focus') or {}).get('verdicts') or []
        return f"서버는 켜져 있지만 저장된 노바 모델 설정 때문에 답변이 막힙니다({fv[0] if fv else '?'})." + tail
    if v == 'FOCUS_MODEL_UNVERIFIED':
        return '서버는 켜져 있지만 저장된 노바 설정을 읽지 못해 모델 경로를 확인하지 못했습니다. 확인 전에는 테스트를 권하지 않습니다.' + tail
    if v == 'FOCUS_LAST_FAILED':
        l = fp.get('last') or {}
        return f"서버는 켜져 있지만 재시작 후 마지막 노바 답변이 실패했습니다({l.get('reasonCode')}, {str(l.get('at'))[:5]})." + tail
    other = f" (테스트용 다른 owner 성공 {fp.get('otherOwnersSuccessSinceRestart')}건은 사용자 기기 증거가 아님)" if fp.get('otherOwnersSuccessSinceRestart') else ''
    return (f"서버({role})는 켜져 있고 모델 경로도 OK지만, 재시작({lr}) 이후 사용자 기기의 노바 답변 기록은 아직 없습니다{other}. "
            f"'테스트해도 됩니다'가 아니라 '첫 질문으로 확인해 주세요'입니다." + tail)

def ready_main(args):
    out, code = ready_gate(args, ROOT)
    text = json.dumps(mask(out), ensure_ascii=False, indent=2, default=str)
    if args.json_out: Path(args.json_out).write_text(text, encoding='utf-8')
    if args.json: print(text)
    rt = out.get('runtime') or {}; fp = out.get('focusProof') or {}
    print(f"NOVA_READY={out['verdict']} reasons={','.join(out['reasons']) or '-'} runtime={rt.get('state')} "
          f"role={rt.get('role')} pid={rt.get('recordedListenerPid')} health={rt.get('health')} "
          f"mutexHeld={rt.get('launcherMutexHeld')} lastRestart={out.get('lastRestart')} "
          f"focus={((out.get('focus') or {}).get('verdicts') or ['-'])} focusSuccessSinceRestart={fp.get('successSinceRestart')} "
          f"lastFocus={(fp.get('last') or {}).get('outcome')}@{(fp.get('last') or {}).get('at')}")
    print('한 줄: ' + out['koreanOneLiner'])
    return code

def restarts_main(args):
    since = parse_since(args.restarts_since)
    ev = restarts_since(ROOT, since)
    for e in ev: print(f"{e['at'].strftime('%H:%M:%S')} {e['kind']:<32} {e['detail']}")
    if not ev: print(f"(no restart/stop/launcher events since {since.strftime('%H:%M')} KST)")
    print('한 줄: ' + restart_notice_ko(ev))
    return 0


def main():
    import argparse
    ap = argparse.ArgumentParser(description="probe effective /chat default model")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--log", default=None)
    ap.add_argument("--base", default="http://127.0.0.1:18180")
    ap.add_argument("--no-server", action="store_true")
    ap.add_argument("--focus", action="store_true", help="Nova Focus effective model resolver (saved profile + catalog + events + freshness)")
    ap.add_argument("--owner-hash", default="", help="12-hex ownerHash for --focus; default = owner of latest focus_terminal")
    ap.add_argument("--json-out", default="", help="--focus: also write JSON here")
    ap.add_argument("paths", nargs="*", help="--focus: static sources to sha-compare with served copies")
    ap.add_argument("--ready", action="store_true", help="Nova ready gate: READY only if runtime+model+freshness+real focus answer agree")
    ap.add_argument("--restarts-since", default="", help="list JVM restarts/stops since HH:MM | 'YYYY-MM-DD HH:MM' | 30m | 2h")
    args = ap.parse_args()
    if args.restarts_since:
        return restarts_main(args)
    if args.ready:
        args.static_prefix = "main/resources/static/"
        return ready_main(args)
    if args.focus:
        args.static_prefix = "main/resources/static/"
        return focus_main(args)

    app_yml = read_lines("main/resources/application.yml")
    llm_yml = read_lines("main/resources/application-llm.yaml")
    cfg = {
        "llm.provider": prop_default(app_yml, "LLM_PROVIDER") or prop_default(app_yml, "llm.provider"),
        "llm.chat-model": prop_default(app_yml, "LLM_CHAT_MODEL") or prop_default(app_yml, "llm.chat-model"),
        "app.ai.ui-default-model": prop_default(app_yml, "APP_AI_UI_DEFAULT_MODEL")
            or prop_default(app_yml, "app.ai.ui-default-model"),
        "app.ai.default-model": prop_default(app_yml, "APP_AI_DEFAULT_MODEL")
            or prop_default(app_yml, "app.ai.default-model"),
        "chat.defaults.model": prop_default(llm_yml, "chat.defaults.model")
            or {"note": "application-llm.yaml:862 -> ${openai.api.model.default:${llm.chat-model:gemma4:26b}}"},
    }
    launcher = launcher_override()
    oauth = oauth_models()
    policy = test_policy()
    logscan = log_scan(args.log)
    server = {"reachable": False, "skipped": "no-server"} if args.no_server \
        else server_probe(args.base)

    if server.get("reachable") and isinstance(server.get("preferences"), dict) \
            and server["preferences"].get("effectiveModel"):
        effective = server["preferences"]["effectiveModel"]
        source = "live:/api/settings/preferences effective.model (%s)" % (
            server["preferences"].get("modelSource"))
        live = True
    else:
        effective, source = static_chain(cfg, launcher)
        live = False

    lane = lane_of(effective)
    broken = bool(logscan.get("oauthFailureReasonCodes"))
    if not live:
        verdict = "STATIC_AUTH_PROJECTED" if lane == "auth" else (
            "STATIC_LOCAL_DEFAULT" if lane == "local" else "STATIC_API_DEFAULT")
    elif lane == "auth":
        verdict = "AUTH_DEFAULT_BUT_ROUTE_BROKEN" if broken else "AUTH_FIRST_OK"
    elif lane == "local":
        verdict = "LOCAL_DEFAULT_VIOLATION"
    elif lane == "api":
        verdict = "API_DEFAULT_INTERMEDIATE"
    else:
        verdict = "UNKNOWN"

    report = {
        "schemaVersion": "awx.model-default-probe.v1",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "root": str(ROOT),
        "verdict": verdict,
        "effectiveDefault": {"model": effective, "lane": lane, "source": source,
                             "live": live,
                             "chain": "personal-override > db-currentModel > "
                                      "app.ai.ui-default-model > app.ai.default-model > "
                                      "chat.defaults.model > llm.chat-model > hardcode"},
        "configChain": cfg,
        "launcherOverride": launcher,
        "oauth": oauth,
        "agentTestPolicy": policy,
        "server": server,
        "logScan": logscan,
        "gemma4_26b_locations": scan_gemma4(),
    }
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print("verdict:", verdict)
        print("effective default:", effective, "(%s, lane=%s)" % (source, lane))
        print("auth route ids:", ", ".join(oauth.get("routeIds") or []) or "none")
        print("server reachable:", server.get("reachable"))
        if logscan.get("oauthFailureReasonCodes"):
            print("recent oauth failures:", logscan["oauthFailureReasonCodes"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
