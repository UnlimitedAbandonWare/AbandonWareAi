"""Bounded read-only probes. Configured, reachable and authenticated are distinct."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import sys
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request

try:
    from scripts.awx_project_secrets import catalog, usable
except ModuleNotFoundError:
    from awx_project_secrets import catalog, usable

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def mcp_probe(root, runner=subprocess.run):
    packets = [{'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
                'params': {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'awx-readiness', 'version': '1'}}},
               {'jsonrpc': '2.0', 'method': 'notifications/initialized', 'params': {}},
               {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list', 'params': {}}]
    env = {k: v for k, v in os.environ.items() if k.upper() in {'PATH', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'HOME'}}
    env.update(AWX_SOURCE_ROOT=str(root), AWX_SOURCE_ACCESS='shared-read', PYTHONDONTWRITEBYTECODE='1')
    try:
        response = runner([sys.executable, '-B', str(Path(root)/'scripts/awx_mcp_stdio_server.py')],
                          input=''.join(json.dumps(p)+'\n' for p in packets), text=True, encoding='utf-8',
                          capture_output=True, timeout=5, cwd=root, env=env, check=False)
        messages = [json.loads(line) for line in response.stdout.splitlines() if line.strip()]
        replies = {m.get('id'): m for m in messages}
        initialized = replies.get(1, {}).get('result', {})
        listed = replies.get(2, {}).get('result', {}).get('tools')
        if response.returncode != 0 or 'protocolVersion' not in initialized or not isinstance(listed, list):
            return {'status': 'unavailable', 'reason': 'mcp_handshake_failed', 'toolCount': 0}
        return {'status': 'available', 'reason': 'initialize_and_tools_list', 'toolCount': len(listed)}
    except subprocess.TimeoutExpired:
        return {'status': 'timeout', 'reason': 'mcp_handshake_deadline', 'toolCount': 0}
    except (OSError, ValueError, AttributeError):
        return {'status': 'unavailable', 'reason': 'mcp_protocol_error', 'toolCount': 0}


def configured_mcp(root=None):
    path = Path(os.environ.get('CODEX_HOME', str(Path.home()/'.codex')))/'config.toml'
    try:
        data = tomllib.loads(path.read_text(encoding='utf-8-sig'))
        servers = data.get('mcp_servers', {})
        rows = []
        sources = [('codex-user', servers)]
        if root is not None:
            project_path = Path(root)/'.mcp.json'
            if project_path.is_file():
                sources.append(('project', json.loads(project_path.read_text(encoding='utf-8-sig')).get('mcpServers', {})))
        for source, entries in sources:
            for name, server in entries.items():
                if not isinstance(server, dict) or not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_.-]{0,47}', name) or re.search(r'sk-|AIza|token|secret|password', name, re.I):
                    continue
                env_names = server.get('env', {})
                rows.append({'name': name, 'source': source, 'enabled': server.get('enabled', True) is True,
                    'transport': 'stdio' if 'command' in server else 'http' if 'url' in server else 'unknown',
                    'envNames': sorted(n for n in env_names if re.fullmatch(r'[A-Z][A-Z0-9_]{1,80}', n)
                                       and not re.search('openssl|opnessl', n, re.I)),
                    'connectionStatus': 'not_probed'})
        # No command, URL, environment value, bearer token or OAuth file is copied.
        return {'configuredServerCount': len(servers), 'servers': rows,
                'enabledServerCount': sum(isinstance(s, dict) and s.get('enabled', True) is True for s in servers.values()),
                'connectionStatus': 'not_probed'}
    except (OSError, ValueError, TypeError, AttributeError):
        return {'configuredServerCount': 0, 'enabledServerCount': 0, 'connectionStatus': 'evidence_needed'}


def http_probe(url, headers=None, timeout=3, opener=None):
    """Never follow redirects with credentials, use ambient proxies, or retain bodies."""
    started = time.monotonic()
    result = {'status': 'unavailable', 'reason': 'connection_failed', 'models': []}
    try:
        client = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        request = urllib.request.Request(url, headers=headers or {}, method='GET')
        with client.open(request, timeout=timeout) as response:
            body = response.read(524289)
            if len(body) > 524288:
                result.update(status='reachable_unverified', reason='response_limit')
            else:
                try:
                    data = json.loads(body)
                    rows = data.get('data', data.get('models'))
                    if url == 'https://api.deepgram.com/v1/projects' and isinstance(data.get('projects'), list):
                        result.update(status='available', reason='read_only_project_list', resourceCount=len(data['projects']))
                    elif not isinstance(rows, list):
                        result.update(status='reachable_unverified', reason='unexpected_response')
                    else:
                        models = [r.get('id', r.get('name', r.get('model', ''))) for r in rows if isinstance(r, dict)]
                        result.update(status='available', reason='read_only_model_list',
                                      models=sorted({m for m in models if isinstance(m, str) and
                                                     re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]{0,95}', m)})[:128])
                except (ValueError, AttributeError):
                    result.update(status='reachable_unverified', reason='unexpected_response')
    except urllib.error.HTTPError as error:
        status = 'auth_failed' if error.code in (401, 403) else 'rate_limited' if error.code == 429 else 'unavailable'
        result.update(status=status, reason='http_'+str(error.code))
        error.close()
    except (TimeoutError, socket.timeout):
        result.update(status='timeout', reason='deadline')
    except urllib.error.URLError as error:
        result.update(status='timeout' if isinstance(error.reason, (TimeoutError, socket.timeout)) else 'unavailable', reason='transport_failed')
    except (OSError, ValueError):
        pass
    result['latencyMs'] = round((time.monotonic()-started)*1000)
    return result


def local_endpoint(value, fallback):
    value = value or fallback
    if '://' not in value:
        value = 'http://'+value
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.query or parsed.fragment:
        return None
    # A peer's loopback endpoint is never a remotely callable service.
    if parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
        return None
    return value.rstrip('/')


def service_probe(provider, values, timeout):
    if provider == 'ollama':
        endpoint = local_endpoint(values.get('OLLAMA_HOST'), 'http://127.0.0.1:11434')
        if endpoint is None:
            return {'status': 'evidence_needed', 'reason': 'remote_endpoint_not_attested'}
        return http_probe(endpoint+'/api/tags', timeout=timeout)
    if provider not in ('redis', 'database'):
        return {'status': 'not_probed', 'reason': 'no_verified_nonbillable_probe'}
    host = values.get('REDIS_HOST' if provider == 'redis' else 'DB_HOST')
    port = values.get('REDIS_PORT' if provider == 'redis' else 'DB_PORT', '6379' if provider == 'redis' else '5432')
    url = values.get('REDIS_URL' if provider == 'redis' else 'DATABASE_URL')
    auth_value = values.get('REDIS_PASSWORD', '')
    username = values.get('REDIS_USERNAME', '')
    scheme = ''
    if url:
        parsed = urllib.parse.urlsplit(url)
        host, port, scheme = parsed.hostname, parsed.port or port, parsed.scheme
        auth_value = urllib.parse.unquote(parsed.password or auth_value)
        username = urllib.parse.unquote(parsed.username or username)
    if not host:
        return {'status': 'not_configured', 'reason': 'endpoint_missing'}
    if host not in ('localhost', '127.0.0.1', '::1'):
        return {'status': 'evidence_needed', 'reason': 'remote_endpoint_not_attested'}
    try:
        with socket.create_connection((host, int(port)), timeout=timeout) as connection:
            if provider == 'database':
                return {'status': 'reachable_unverified', 'reason': 'tcp_only_auth_not_tested'}
            if scheme == 'rediss':
                connection = ssl.create_default_context().wrap_socket(connection, server_hostname=host)
            with connection:
                def command(parts):
                    encoded = [p.encode() for p in parts]
                    request = ('*'+str(len(parts))+'\r\n').encode()+b''.join(
                        ('$'+str(len(p))+'\r\n').encode()+p+b'\r\n' for p in encoded)
                    connection.sendall(request)
                    return connection.recv(1024)
                if auth_value:
                    reply = command(['AUTH', username, auth_value] if username else ['AUTH', auth_value])
                    if reply != b'+OK\r\n':
                        return {'status': 'auth_failed', 'reason': 'redis_auth_rejected'}
                reply = command(['PING'])
                if reply == b'+PONG\r\n':
                    return {'status': 'available', 'reason': 'redis_ping'}
                return {'status': 'auth_failed' if reply.startswith(b'-NOAUTH') else 'unavailable', 'reason': 'redis_ping_rejected'}
    except (TimeoutError, socket.timeout):
        return {'status': 'timeout', 'reason': 'deadline'}
    except (OSError, ValueError):
        return {'status': 'unavailable', 'reason': 'connection_failed'}


def probe(root, device, values, references=None, http=http_probe, private_discovery=None):
    config = catalog(root)
    timeout = min(3, max(.1, config.get('probeTimeoutSeconds', 3)))
    references = references or {}
    started = time.monotonic()
    routes = {
        'openai': ('https://api.openai.com/v1/models', 'OPENAI_API_KEY', 'Authorization', 'Bearer '),
        'groq': ('https://api.groq.com/openai/v1/models', 'GROQ_API_KEY', 'Authorization', 'Bearer '),
        'gemini': ('https://generativelanguage.googleapis.com/v1beta/models', 'GEMINI_API_KEY', 'x-goog-api-key', ''),
        'soniox': ('https://api.soniox.com/v1/models', 'SONIOX_API_KEY', 'Authorization', 'Bearer '),
        'deepgram': ('https://api.deepgram.com/v1/projects', 'DEEPGRAM_API_KEY', 'Authorization', 'Token '),
    }
    def one(item):
        provider, names = item
        configured = [n for n in names if usable(values.get(n))]
        entry = {'provider': provider, 'envNames': names, 'secretRefs': {n: references[n] for n in names if n in references},
                 'configuredNames': configured, 'device': device, 'remoteUsability': 'not_attested'}
        entry['kind'] = 'speech-to-text' if provider in ('deepgram', 'soniox') else 'database' if provider in (
            'upstash', 'database', 'redis', 'neo4j', 'supabase', 'pinecone') else 'api'
        if time.monotonic()-started > min(20, config.get('probeBudgetSeconds', 20)):
            entry.update(status='timeout', reason='probe_budget')
        elif not configured and provider != 'ollama':
            entry.update(status='not_configured', reason='environment_missing')
        elif provider in routes:
            url, key, header, prefix = routes[provider]
            if provider == 'openai' and values.get('OPENAI_BASE_URL', '').rstrip('/') not in ('', 'https://api.openai.com/v1'):
                entry.update(status='evidence_needed', reason='custom_endpoint_not_attested')
            elif provider == 'soniox' and values.get('SONIOX_STT_REGION', 'us').strip().lower() not in ('', 'us'):
                entry.update(status='evidence_needed', reason='regional_endpoint_not_attested')
            elif not usable(values.get(key)):
                entry.update(status='not_configured', reason='credential_missing')
            else:
                entry.update(http(url, {header: prefix+values[key]}, timeout))
        else:
            entry.update(service_probe(provider, values, timeout))
        # Model metadata is also untrusted: never repeat known credential bytes.
        sensitive = [v for n, v in values.items() if usable(v) and re.search('KEY|TOKEN|SECRET|PASSWORD', n)]
        models = [m for m in entry.pop('models', []) if not any(v in m for v in sensitive)]
        entry['modelCount'] = len(models)
        # Provider-returned names can contain arbitrary encoded payloads. Retain them only
        # in the protected discovery document, not in public registry/event records.
        if private_discovery is not None:
            private_discovery[provider] = models
        return entry
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(one, config['providers'].items()))
    rows.append({'provider': 'awx-control-tower-stdio', 'kind': 'mcp', 'device': device,
                 'envNames': [], 'secretRefs': {}, 'modelCount': 0, 'remoteUsability': 'not_attested', **mcp_probe(root)})
    browser_roots = [Path(os.environ.get(n, '.')) for n in ('PROGRAMFILES', 'PROGRAMFILES(X86)', 'LOCALAPPDATA')]
    for executable, kind in [('codex', 'mcp'), ('msedge', 'browser'), ('chrome', 'browser'), ('ollama', 'local-model')]:
        present = bool(shutil.which(executable))
        if executable in ('msedge', 'chrome'):
            relative = 'Microsoft/Edge/Application/msedge.exe' if executable == 'msedge' else 'Google/Chrome/Application/chrome.exe'
            present = present or any((p/relative).is_file() for p in browser_roots)
        rows.append({'provider': executable+'-executable', 'kind': kind, 'device': device,
                     'status': 'not_probed' if present else 'not_configured',
                     'reason': 'executable_present_session_not_proven' if present else 'executable_not_found',
                     'envNames': [], 'secretRefs': {}, 'modelCount': 0, 'remoteUsability': 'not_attested'})
    return {'schemaVersion': 'awx.device-capabilities.v1', 'device': device,
            'timestamp': datetime.now(timezone.utc).isoformat(), 'ttlSeconds': max(1, min(3600, int(config.get('registryTtlSeconds', 300)))),
            'probeMode': 'read-only-no-generation', 'mcpConfiguration': configured_mcp(root), 'capabilities': rows}
