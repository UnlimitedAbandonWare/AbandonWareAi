"""Read-only assist for two Codex chat briefs.

Scanner: scripts/pair_brief_assist.py. Stdlib only.
No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import sys

import pair_brief_assist as engine

SCHEMA = "awx.chat-wait-truncation-assist.v1"
DEFAULT_SPEC = "var/codex-assist-chat-wait-truncation-20261006/spec.json"


def main(argv=None):
    engine.SCHEMA = SCHEMA
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] in ("pin", "cover", "diff-forbid") and "--spec" not in args:
        args = [args[0], "--spec", DEFAULT_SPEC, *args[1:]]
    return engine.main(args)


if __name__ == "__main__":
    sys.exit(main())
