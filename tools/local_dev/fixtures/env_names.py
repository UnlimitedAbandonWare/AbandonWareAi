#!/usr/bin/env python3
"""env_names.py -- print env var NAMES containing secret-looking words.

Never prints values. Exit 1 if a forbidden name (or the canary value
AWX_SECRET_SENTINEL_VALUE / AWX_DEV_CANARY_VALUE) is visible in the
environment, which would mean the dispatcher did not scrub.
"""
import os
import re
import sys

PATTERNS = [re.compile(p, re.IGNORECASE) for p in (
    r".*_API_KEY$", r".*_TOKEN$", r".*SECRET.*", r".*PASSWORD.*",
    r"AI_GATEWAY_.*", r"OPENAI_.*", r"GEMINI_.*", r"GOOGLE_API_KEY",
    r"ANTHROPIC_.*", r"VERCEL_.*", r"AWX_JEV_VIA_WRAPPER$",
    r"AWX_DEV_CANARY.*")]

names = sorted(os.environ)
hits = [n for n in names if any(p.match(n) for p in PATTERNS)]
for n in names:
    if any(p.match(n) for p in PATTERNS):
        print("NAME:" + n)
sentinel = os.environ.get("AWX_SECRET_SENTINEL_VALUE")
canary = os.environ.get("AWX_DEV_CANARY_VALUE")
if sentinel is not None:
    print("SENTINEL_VALUE_VISIBLE")
if canary is not None:
    print("CANARY_VALUE_VISIBLE")
print("hits=%d total=%d" % (len(hits), len(names)))
sys.exit(1 if hits or sentinel is not None or canary is not None else 0)
