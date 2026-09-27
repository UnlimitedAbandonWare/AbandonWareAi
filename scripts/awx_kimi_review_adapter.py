"""Bounded Kimi Code CLI review seam — registration skeleton.

Generation is intentionally not implemented. Until the official Kimi Code CLI
is installed and its bytes, flags and account/billing proof model are verified
and pinned here, every mode fails closed: status reports the missing pin
instead of claiming readiness, and review is rejected before any child process
can spawn. The Grok acceptance-window model is not copied; a follow-up
change-set must define the Kimi-side account proof before generation exists.
"""
import hashlib
import os
from pathlib import Path
import time

# Reuse only the existing provider-independent packet validation. Kimi never
# enters the Codex or Grok transports, auth, environment or model policy.
from awx_codex_review_adapter import Rejected, validate_input as validate_packet

PINNED_VERSION = None   # fill after the official binary is verified
PINNED_SHA256 = None    # fill after the official binary is verified
MAX_BINARY_BYTES = 200 * 1024 * 1024

# Conventional install location of the official Kimi Code CLI on Windows;
# confirm against the real install before filling the pin constants above.
EXPECTED_EXECUTABLE = Path.home() / '.kimi' / 'bin' / 'kimi.exe'


def validate_input(payload):
    packet = validate_packet(payload)
    if packet['mode'] == 'review' and not packet['evidence']:
        raise Rejected('invalid-input')
    return packet


class KimiReviewAdapter:
    def __init__(self, executable):
        self.executable = Path(executable)

    def installation(self):
        """Pin local bytes, not authentication or entitlement; never launch."""
        try:
            if PINNED_SHA256 is None or PINNED_VERSION is None:
                return False, None, 'cli-capability-missing'
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
            if any(os.environ.get(name) for name in
                   ('AWX_KIMI_REVIEW_DEPTH', 'AWX_GROK_REVIEW_DEPTH', 'AWX_CODEX_REVIEW_DEPTH')):
                raise Rejected('recursive-invocation-blocked')
            verified, version, reason = self.installation()
            result.update(cliVerified=verified, cliVersion=version, reason=reason)
            if not verified:
                raise Rejected(reason)
            # The skeleton has no generation path: even a pinned binary still
            # needs a verified account/billing acceptance model before review
            # may spawn a child, so every verified install also fails closed.
            raise Rejected('isolation-unproven')
        except Rejected as exc:
            result.update(ok=False, status='blocked', reason=exc.reason, blockers=[exc.reason],
                          findings=[], canaryVerified=False, generationEnabled=False)
        except (OSError, ValueError, TypeError):
            result.update(ok=False, status='blocked', reason='isolation-unproven',
                          blockers=['isolation-unproven'], findings=[], canaryVerified=False,
                          generationEnabled=False)
        result['durationMs'] = max(0, int((time.monotonic() - started) * 1000))
        return result


def installed_adapter():
    return KimiReviewAdapter(EXPECTED_EXECUTABLE)
