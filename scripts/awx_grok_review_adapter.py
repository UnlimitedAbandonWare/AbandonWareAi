"""Bounded Grok Build reviews through the existing AWX owned worker.

Generation requires a short-lived, account/binary/policy-bound acceptance
window written by Desktop after official login and billing verification.
This is not an unattended billing monitor: UI-only evidence expires, and the
initial window permits at most three distinct attempts, without retries.
"""
from contextlib import contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time

# Reuse only the existing provider-independent packet validation. Grok never
# enters the Codex App Server transport, auth, environment or model policy.
from awx_codex_review_adapter import Rejected, _WORKER, canonical, validate_input as validate_packet

PINNED_VERSION = '1.0.34'
PINNED_SHA256 = '021d8f7f6bdf9db48b6c87e799cd99130a76c463e6e6a3161510839aed016d94'
MAX_BINARY_BYTES = 200 * 1024 * 1024
REVIEW_MODEL = 'grok-4.6'
PROFILE_POLICY = '''[skills]
ignore = ["~/.agents", "~/.codex/integrations/grok-build/review-profile/bundled"]
[permission]
deny = ["*", "MCPTool(*)"]
[grok_com_config]
disable_api_key_auth = true
[cli]
auto_update = false
[session]
load_envrc = false
[models]
default = "grok-4.6"
allowed_models = ["grok-4.6"]
max_retries = 0
[marketplace]
sources = []
official_marketplace_auto_installed = true
default_skills_installs_purged = true
'''
POLICY_HASH = hashlib.sha256(PROFILE_POLICY.encode()).hexdigest()
WINDOW_FIELDS = {'schemaVersion', 'verifiedAt', 'expiresAt', 'cliSha256', 'policySha256',
                 'authSha256', 'accountMatch', 'accountMatchSource', 'plan',
                 'billingEvidenceSource', 'extraCreditsPresent', 'autoTopUpEnabled',
                 'includedUsageAvailable', 'loginCompleted', 'requestIds'}


def safe_file(path, limit):
    for item in (path, *path.parents):
        if item.is_symlink() or (item.exists() and getattr(item.stat(), 'st_file_attributes', 0) & 1024):
            raise Rejected('isolation-unproven')
    if not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise Rejected('isolation-unproven')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise Rejected('isolation-unproven')
    return data


def clean_environment(profile):
    names = {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMSPEC', 'PATHEXT', 'TEMP', 'TMP',
             'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMDATA'}
    env = {key: value for key, value in os.environ.items() if key.upper() in names}
    env['PATH'] = os.path.join(env.get('SYSTEMROOT', r'C:\Windows'), 'System32')
    env.update(GROK_HOME=str(profile), GROK_DISABLE_AUTOUPDATER='1', GROK_MEMORY='0',
               GROK_SUBAGENTS='0', GROK_WEB_FETCH='0', GROK_WRITE_FILE='0', AWX_GROK_REVIEW_DEPTH='1')
    for source in ('CURSOR', 'CLAUDE'):
        for feature in ('SKILLS', 'RULES', 'AGENTS', 'MCPS', 'HOOKS'):
            env[f'GROK_{source}_{feature}_ENABLED'] = '0'
    return env


def review_command(executable, prompt):
    # Verified against the pinned binary using a loopback inference stub:
    # --tools '' keeps 23 tools. A nonempty allowlist plus subtraction also
    # removes the otherwise-always-on MCP search_tool/use_tool definitions.
    return [str(executable), '--permission-mode', 'dontAsk', '--tools', 'read_file',
            '--disallowed-tools', 'read_file,search_tool,use_tool', '--deny', '*',
            '--no-subagents', '--disable-web-search', '--model', REVIEW_MODEL,
            '--max-turns', '1', '--output-format', 'json', '-p', prompt]


def bounded_process(factory, argv, cwd, env, deadline, cancel):
    """Drain both pipes within limits; close owned descendants before returning."""
    if cancel is not None and cancel.is_set():
        raise Rejected('cancelled')
    if time.monotonic() >= deadline:
        raise Rejected('timeout')
    worker = factory(argv, cwd=str(cwd), env=env, binary=True)
    buffers = [bytearray(), bytearray()]
    overflow = threading.Event()
    readers = []
    cleanup_ok = False

    def drain(stream, output, limit):
        try:
            while chunk := stream.read(4096):
                if len(output) + len(chunk) > limit:
                    overflow.set()
                    return
                output.extend(chunk)
        except (OSError, ValueError):
            overflow.set()

    try:
        worker.process.stdin.close()
        for stream, output, limit in ((worker.process.stdout, buffers[0], 262144),
                                      (worker.process.stderr, buffers[1], 65536)):
            thread = threading.Thread(target=drain, args=(stream, output, limit), daemon=True)
            readers.append(thread)
            thread.start()
        while worker.process.poll() is None or any(t.is_alive() for t in readers):
            if cancel is not None and cancel.is_set():
                raise Rejected('cancelled')
            if overflow.is_set():
                raise Rejected('output-limit-exceeded')
            if time.monotonic() >= deadline:
                raise Rejected('timeout')
            time.sleep(.01)
        if overflow.is_set():
            raise Rejected('output-limit-exceeded')
        return worker.process.returncode, bytes(buffers[0]), bytes(buffers[1])
    finally:
        cleanup_ok = worker.close()
        for thread in readers:
            thread.join(timeout=1)
        if not cleanup_ok or any(t.is_alive() for t in readers):
            raise Rejected('child-cleanup-failed')
        worker.process.stdout.close()
        worker.process.stderr.close()


def normalize_response(raw, packet):
    try:
        outer = json.loads(raw, parse_constant=lambda _: None)
        if not isinstance(outer, dict) or outer.get('stopReason') != 'end_turn' or outer.get('num_turns') != 1:
            raise Rejected('malformed-output')
        models = outer.get('modelUsage', {})
        if not isinstance(models, dict) or len(models) != 1:
            raise Rejected('model-unverified')
        observed_model = next(iter(models))
        if (observed_model not in {REVIEW_MODEL, 'grok-4.6-build'}
                or not isinstance(models[observed_model], dict)
                or type(models[observed_model].get('modelCalls')) is not int
                or models[observed_model]['modelCalls'] < 1):
            raise Rejected('model-unverified')
        if not isinstance(outer.get('text'), str) or not outer['text'].strip():
            raise Rejected('empty-response')
        body = json.loads(outer['text'], parse_constant=lambda _: None)
        if (not isinstance(body, dict) or set(body) != {'marker', 'findings'}
                or body['marker'] != packet['requestId'] or not isinstance(body['findings'], list)
                or len(body['findings']) > 5):
            raise Rejected('malformed-output')
        known_ids = {item['evidenceId'] for item in packet['evidence']}
        for finding in body['findings']:
            if (not isinstance(finding, dict) or set(finding) !=
                    {'evidenceIds', 'claim', 'counterexample', 'suggestedChange', 'confidence'}):
                raise Rejected('malformed-output')
            ids = finding['evidenceIds']
            if (not isinstance(ids, list) or not ids or len(ids) > 8
                    or any(not isinstance(i, str) or i not in known_ids for i in ids)):
                raise Rejected('malformed-output')
            for key in ('claim', 'counterexample', 'suggestedChange'):
                if not isinstance(finding[key], str) or not 1 <= len(finding[key].strip()) <= 2000:
                    raise Rejected('malformed-output')
            confidence = finding['confidence']
            if type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
                raise Rejected('malformed-output')
        # Reuse the secret-input gate on the returned public findings as well.
        if body['findings']:
            validate_input({**packet, 'changeSummary': canonical(body['findings']).decode()[:4000],
                            'evidence': [{'evidenceId': 'E1', 'relativePath': 'public-output.txt',
                                          'excerpt': canonical(body['findings']).decode()}]})
        usage = outer.get('usage')
        allowed_usage = {'input_tokens': 'input', 'output_tokens': 'output',
                         'reasoning_tokens': 'reasoning', 'total_tokens': 'total',
                         'cache_read_input_tokens': 'cacheRead', 'cache_creation_input_tokens': 'cacheCreation'}
        normalized_usage = ({allowed_usage[key]: value for key, value in usage.items()
                             if key in allowed_usage and type(value) is int and value >= 0}
                            if isinstance(usage, dict) else {})
        return body['findings'], normalized_usage or None, observed_model
    except (ValueError, TypeError, AttributeError, KeyError, UnicodeError, RecursionError):
        raise Rejected('malformed-output') from None


@contextmanager
def exclusive_review(profile):
    path = profile / 'review.lock'
    if path.exists():
        safe_file(path, 16)
    stream = path.open('a+b')
    locked = False
    try:
        if path.stat().st_size == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            locked = True
        except OSError:
            raise Rejected('review-busy') from None
        yield
    finally:
        if locked:
            stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        stream.close()


def validate_input(payload):
    packet = validate_packet(payload)
    if re.search(r'\bBearer\s+[A-Za-z0-9._~+/=-]{16,}', canonical(packet).decode('utf-8'), re.I):
        raise Rejected('secret-input-blocked')
    if packet['mode'] == 'review' and not packet['evidence']:
        raise Rejected('invalid-input')
    for item in packet['evidence']:
        label = item['relativePath'].replace('\\', '/')
        if (not item['excerpt'].strip() or not label.strip(' ./')
                or any(ord(ch) < 32 for ch in label)):
            raise Rejected('invalid-input')
    return packet


class GrokReviewAdapter:
    def __init__(self, executable, *, profile=None):
        self.executable = Path(executable)
        self.profile = Path(profile) if profile is not None else Path.home() / '.codex/integrations/grok-build/review-profile'

    def readiness(self):
        if not (self.profile / 'auth.json').is_file():
            raise Rejected('auth-required')
        if safe_file(self.profile / 'config.toml', 4096) != PROFILE_POLICY.encode():
            raise Rejected('isolation-unproven')
        if any((self.profile / name).exists() for name in ('requirements.toml', 'managed_config.toml')):
            raise Rejected('isolation-unproven')
        try:
            window = json.loads(safe_file(self.profile / 'acceptance-window.json', 8192))
        except (OSError, ValueError, Rejected):
            raise Rejected('extra-usage-unverified') from None
        if not isinstance(window, dict) or set(window) != WINDOW_FIELDS:
            raise Rejected('extra-usage-unverified')
        now = time.time()
        if (type(window['verifiedAt']) not in (int, float) or type(window['expiresAt']) not in (int, float)
                or not window['verifiedAt'] <= now < window['expiresAt'] <= window['verifiedAt'] + 900
                or type(window['schemaVersion']) is not int or window['schemaVersion'] != 1
                or window['cliSha256'] != PINNED_SHA256
                or window['policySha256'] != POLICY_HASH
                or window['billingEvidenceSource'] != 'official-ui'
                or window['extraCreditsPresent'] is not False or window['autoTopUpEnabled'] is not False
                or window['includedUsageAvailable'] is not True):
            raise Rejected('extra-usage-unverified')
        if (window['accountMatch'] != 'confirmed' or window['accountMatchSource'] != 'user-attestation'
                or window['loginCompleted'] is not True or window['plan'] not in ('SuperGrok', 'SuperGrok Heavy')
                or window['authSha256'] != hashlib.sha256(safe_file(self.profile / 'auth.json', 65536)).hexdigest()):
            raise Rejected('account-mismatch')
        ids = window['requestIds']
        if (not isinstance(ids, list) or not 1 <= len(ids) <= 3 or len(set(ids)) != len(ids)
                or any(not isinstance(i, str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', i) for i in ids)):
            raise Rejected('extra-usage-unverified')
        return window

    def inspect_profile(self, factory, cwd, env, deadline, cancel):
        code, out, _ = bounded_process(factory, [str(self.executable), 'inspect', '--json'],
                                        cwd, env, min(deadline, time.monotonic() + 20), cancel)
        try:
            value = json.loads(out)
            if (code or value['grokVersion'] != PINNED_VERSION or value['skills'] or value['hooks']
                    or value['projectInstructions'] or value['plugins'] or value['lspServers']
                    or value['permissions']['loaded'] != 2 or value['permissions']['skipped']
                    or value['loginPolicy']['apiKeyAuthDisabled'] is not True
                    or any(not row.get('disabled', False) for row in value['mcpServers'])
                    or len(value['configSources']) != 1):
                raise Rejected('isolation-unproven')
        except (KeyError, TypeError, ValueError, AttributeError):
            raise Rejected('isolation-unproven') from None

    def generate(self, packet, result):
        owner = _WORKER.get()
        if owner is None:
            raise Rejected('worker-ownership-required')
        factory, worker_deadline, cancel = owner
        deadline = min(worker_deadline - 4, time.monotonic() + packet.get('timeoutMs', 60000) / 1000)
        prompt = ('Review only this public/synthetic packet. Paths are labels; use no tools. '
                  'Return JSON only: {"marker":"the requestId", "findings":[{"evidenceIds":["E1"],'
                  '"claim":"...","counterexample":"...","suggestedChange":"...","confidence":0.9}]}. '
                  'Use at most five findings, only supplied evidence IDs. For a marker-only canary, return findings=[].\n'
                  + canonical(packet).decode())
        argv = review_command(self.executable, prompt)
        if len(subprocess.list2cmdline(argv)) > 30000:
            raise Rejected('invalid-input')
        env = clean_environment(self.profile)
        with exclusive_review(self.profile), tempfile.TemporaryDirectory(prefix='awx-grok-review-') as temp:
            window = self.readiness()
            if packet['requestId'] not in window['requestIds']:
                raise Rejected('extra-usage-unverified')
            self.inspect_profile(factory, Path(temp), env, deadline, cancel)
            # Recheck the binary and profile after inspection, immediately before generation.
            if not self.installation()[0]:
                raise Rejected('cli-capability-missing')
            if self.readiness() != window:
                raise Rejected('extra-usage-unverified')
            attempt = self.profile / ('attempt-' + hashlib.sha256(packet['requestId'].encode()).hexdigest() + '.json')
            record = {'requestHash': hashlib.sha256(canonical(packet)).hexdigest(),
                      'reservedAt': time.time(), 'status': 'attempt-reserved'}
            try:
                with attempt.open('xb') as stream:
                    stream.write(canonical(record)); stream.flush(); os.fsync(stream.fileno())
            except FileExistsError:
                raise Rejected('review-busy') from None
            result['generationCount'] = 1
            code, out, err = bounded_process(factory, argv, Path(temp), env, deadline, cancel)
            if code:
                message = err.lower()
                reason = ('quota-exhausted' if any(word in message for word in (b'quota', b'rate limit', b'credits'))
                          else 'auth-required' if any(word in message for word in (b'login', b'authenticate', b'unauthorized'))
                          else 'malformed-output')
                raise Rejected(reason)
            findings, usage, observed_model = normalize_response(out, packet)
            result.update(ok=True, status='completed', reason='completed', findings=findings,
                          canaryVerified=True, usage=usage, observedModel=observed_model,
                          observedModelSource='cli_model_usage')
            if len(canonical(result)) > 32768:
                raise Rejected('output-limit-exceeded')

    def installation(self):
        """Pin local bytes, not authentication or entitlement; never launch."""
        try:
            if not self.executable.is_file():
                return False, None, 'cli-unavailable'
            for path in (self.executable, *self.executable.parents):
                if path.is_symlink() or getattr(path.stat(), 'st_file_attributes', 0) & 1024:
                    return False, None, 'cli-capability-missing'
            if not 0 < self.executable.stat().st_size <= MAX_BINARY_BYTES:
                return False, None, 'cli-capability-missing'
            digest = hashlib.sha256()
            total = 0
            with self.executable.open('rb') as stream:
                while chunk := stream.read(1024 * 1024):
                    total += len(chunk)
                    if total > MAX_BINARY_BYTES:
                        return False, None, 'cli-capability-missing'
                    digest.update(chunk)
            if digest.hexdigest() != PINNED_SHA256:
                return False, None, 'cli-capability-missing'
            return True, PINNED_VERSION, 'isolation-unproven'
        except (OSError, ValueError):
            return False, None, 'cli-unavailable'

    def run(self, payload):
        started = time.monotonic()
        result = {
            'ok': False, 'status': 'blocked', 'reason': 'invalid-input',
            'requestId': '', 'authMode': 'unknown', 'accountMatch': 'unknown',
            'accountMatchSource': 'unknown', 'plan': 'unknown',
            'subscriptionOnly': None, 'requestedModel': None, 'observedModel': None,
            'observedModelSource': 'not_observed', 'generationCount': 0,
            'durationMs': 0, 'cleanupOk': True, 'canaryVerified': False,
            'usage': None, 'providerAttemptEvidence': 'not_observed',
            'generationEnabled': False, 'cliVerified': False, 'cliVersion': None,
            'blockers': [], 'findings': [],
        }
        try:
            packet = validate_input(payload)
            result['requestId'] = packet['requestId']
            if os.environ.get('AWX_GROK_REVIEW_DEPTH'):
                raise Rejected('recursive-invocation-blocked')
            verified, version, reason = self.installation()
            result.update(cliVerified=verified, cliVersion=version, reason=reason)
            if not verified:
                raise Rejected(reason)
            window = self.readiness()
            result.update(authMode='subscription-session', accountMatch='confirmed',
                          accountMatchSource='user-attestation', plan=window['plan'],
                          subscriptionOnly=True, requestedModel=REVIEW_MODEL, generationEnabled=True)
            if packet['mode'] == 'status':
                result.update(ok=True, status='ready', reason='ready')
            else:
                self.generate(packet, result)
        except Rejected as exc:
            result.update(ok=False, status='blocked', reason=exc.reason, blockers=[exc.reason],
                          findings=[], canaryVerified=False, generationEnabled=False)
            if exc.reason == 'child-cleanup-failed':
                result['cleanupOk'] = False
        except (OSError, ValueError, TypeError):
            result.update(ok=False, status='blocked', reason='isolation-unproven',
                          blockers=['isolation-unproven'], findings=[], canaryVerified=False,
                          generationEnabled=False)
        result['durationMs'] = max(0, int((time.monotonic() - started) * 1000))
        return result


def installed_adapter():
    return GrokReviewAdapter(Path.home() / '.grok' / 'bin' / 'grok.exe')
