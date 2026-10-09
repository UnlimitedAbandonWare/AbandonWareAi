"""Effective source-edit lease lifetime: lease.json plus heartbeat sidecar.

Python port of the PowerShell contract Get-AwxLeaseLifetime
(__patch_drop__/source_edit_lease_contract.ps1) so every Python reader reaches
the same verdict the session helper does:

- lease expiry comes from expiresAtUtc (legacy fallback: expiresAt);
- a heartbeat sidecar at
  __patch_drop__/source-edit-heartbeats/<leaseId>.json extends the effective
  expiry only when ALL hold: leaseId matches case-sensitively and is hex32,
  leaseFingerprint == sha256(lease.json bytes) case-insensitively,
  renewedAtUtc <= now+30s, renewedAtUtc < expiresAtUtc <= renewedAtUtc+540min;
- the sidecar path must not traverse a symlink/reparse point;
- effective expiry = max(lease expiry, valid sidecar expiry);
- an invalid sidecar never extends anything; heartbeatState is
  absent (no file / leaseId not usable) | invalid (file exists, checks fail)
  | valid.

Timestamp caveat: naive ISO text is read as UTC (PS summary parses with
AssumeUniversal). Get-AwxLeaseLifetime's internal DateTimeOffset.Parse would
treat naive sidecar fields as local time, but both writers emit round-trip 'o'
format with an explicit offset, so the divergence is unreachable in practice.

Everything here is read-only: nothing is created, modified or deleted.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import stat
from datetime import datetime, timedelta, timezone
from pathlib import Path

HEARTBEATS_REL = "__patch_drop__/source-edit-heartbeats"
LEASE_ID_RE = re.compile(r"[a-fA-F0-9]{32}\Z")  # PS -match is case-insensitive
MAX_RENEWAL_SKEW = timedelta(seconds=30)   # renewedAtUtc may lead now by <=30s
MAX_RENEWAL_TTL = timedelta(minutes=540)   # heartbeat TTL ceiling (contract)


def parse_time(value):
    """Aware datetime in UTC or None. Naive ISO text is read as UTC."""
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc)


def iso(moment):
    return moment.astimezone(timezone.utc).isoformat() if moment else None


def heartbeats_dir_for_locks(locks_dir):
    """Sibling heartbeat dir for the standard lock layout, else None."""
    locks_dir = Path(locks_dir)
    if locks_dir.name != "source-edit-locks":
        return None
    return locks_dir.parent / "source-edit-heartbeats"


def heartbeats_dir_for_lease_file(lease_file):
    """<root>/__patch_drop__/source-edit-locks/<t>.lock/lease.json -> sibling."""
    path = Path(lease_file)
    if path.parent.parent.name != "source-edit-locks":
        return None
    return path.parent.parent.parent / "source-edit-heartbeats"


def _has_reparse_ancestor(path):
    """Assert-AwxLeaseSafePath port: an existing symlink/reparse ancestor or an
    unreadable ancestor makes the sidecar unsafe to trust."""
    ancestor = Path(os.path.abspath(path))
    while True:
        try:
            if os.path.lexists(ancestor):
                info = ancestor.lstat()
                if stat.S_ISLNK(info.st_mode) or (
                        getattr(info, "st_file_attributes", 0)
                        & stat.FILE_ATTRIBUTE_REPARSE_POINT):
                    return True
        except OSError:
            return True
        parent = ancestor.parent
        if parent == ancestor:
            return False
        ancestor = parent


def read_heartbeat(heartbeats_dir, lease, lease_bytes=None, now=None):
    """Sidecar verdict for one lease. Never extends on invalid input.

    lease_bytes must be the exact lease.json file bytes (fingerprint check);
    when omitted the sidecar cannot be verified and counts as absent.
    Returns {state, renewedAtUtc, expiresAtUtc, ageSeconds} - timestamps are
    ISO strings or None, ageSeconds an int or None.
    """
    now = now or datetime.now(timezone.utc)
    result = {"state": "absent", "renewedAtUtc": None,
              "expiresAtUtc": None, "ageSeconds": None}
    if not isinstance(lease, dict) or lease_bytes is None or heartbeats_dir is None:
        return result
    lease_id = str(lease.get("leaseId") or "")
    if not LEASE_ID_RE.fullmatch(lease_id):
        return result
    path = Path(heartbeats_dir) / (lease_id + ".json")
    try:
        exists = path.exists()
    except OSError:
        exists = False
    if not exists:
        return result
    result["state"] = "invalid"
    try:
        if _has_reparse_ancestor(path):
            return result
        heartbeat = json.loads(path.read_bytes())
        if not isinstance(heartbeat, dict):
            return result
        renewed = parse_time(heartbeat.get("renewedAtUtc"))
        until = parse_time(heartbeat.get("expiresAtUtc"))
        fingerprint = hashlib.sha256(lease_bytes).hexdigest()
        valid = (heartbeat.get("leaseId") == lease_id and renewed and until
                 and str(heartbeat.get("leaseFingerprint") or "").lower() == fingerprint
                 and renewed <= now + MAX_RENEWAL_SKEW
                 and renewed < until <= renewed + MAX_RENEWAL_TTL)
        if not valid:
            return result
        result.update(state="valid", renewedAtUtc=iso(renewed),
                      expiresAtUtc=iso(until),
                      ageSeconds=max(0, math.floor((now - renewed).total_seconds())))
    except (OSError, ValueError, TypeError):
        pass
    return result


def lifetime(heartbeats_dir, lease, lease_bytes=None, now=None):
    """Merged lifetime view for one lease.

    Returns {leaseId, expires, effective, heartbeatState, heartbeatAgeSeconds,
    heartbeat, now}. expires is the lease.json expiry (None when unparseable);
    effective is expires possibly extended by a valid sidecar. Both are aware
    datetimes. Consumers that gate on a *usable* lease must check `expires`
    is not None separately (the PS summary refuses expiry-invalid leases even
    when a sidecar is valid).
    """
    now = now or datetime.now(timezone.utc)
    if not isinstance(lease, dict):
        lease = {}
    expires = parse_time(lease.get("expiresAtUtc") or lease.get("expiresAt"))
    heartbeat = read_heartbeat(heartbeats_dir, lease, lease_bytes, now)
    effective = expires
    until = parse_time(heartbeat.get("expiresAtUtc")) \
        if heartbeat.get("state") == "valid" else None
    if until is not None and (effective is None or until > effective):
        effective = until
    return {"leaseId": str(lease.get("leaseId") or ""),
            "expires": expires, "effective": effective,
            "heartbeatState": heartbeat["state"],
            "heartbeatAgeSeconds": heartbeat["ageSeconds"],
            "heartbeat": heartbeat, "now": now}
