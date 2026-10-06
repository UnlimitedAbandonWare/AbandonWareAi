"""Read-only entry for the Display Gemini websearch-only assist.

Delegates to pair_brief_assist. A clean exit is a scan result, not a product PASS.
No network, Gradle, server, or product write.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as scan

SPEC = "var/codex-assist-display-gemini-websearch-20261006/spec.json"
COMMANDS = ("pin", "cover", "diff-forbid")


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or args[0] not in COMMANDS:
        print(
            '{"schemaVersion":"awx.pair-brief-assist.v1","status":"error",'
            '"reason":"usage","productPass":false}'
        )
        return 2
    if "--spec" not in args:
        args.extend(["--spec", SPEC])
    return scan.main(args)


if __name__ == "__main__":
    sys.exit(main())
