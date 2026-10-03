#!/usr/bin/env python3
"""spawn_agent role preflight — blocks roles known-broken on this checkout.

Run ONCE before any spawn_agent call:

    python -B scripts/spawn_preflight.py <role-or-agent-type>

exit 0  -> role is not on the blocklist; spawn may proceed.
exit 2  -> blocked role; prints the approved alternative lane.

Blocklist (SSOT = .agents/skills/demo1-glm-route-guard/SKILL.md):
    glm_worker  -> native spawn fails HTTP 400 under the ChatGPT login;
                   use the glm_agent MCP lane (see $demo1-glm-route-guard)
                   or mark GLM=SESSION_UNAVAILABLE.

Unknown roles pass — this tool only enforces documented broken routes,
it is not a capability check.
"""
import sys

BLOCKED = {
    "glm_worker": "use $demo1-glm-route-guard (glm_agent MCP lane) or mark "
                  "GLM=SESSION_UNAVAILABLE; native spawn fails HTTP 400 "
                  "under the ChatGPT login",
}


def main(argv):
    if len(argv) != 2 or argv[1] in ("-h", "--help"):
        print(__doc__.strip())
        return 0 if len(argv) == 2 else 2
    role = argv[1].strip()
    if role in BLOCKED:
        print("spawn_preflight: BLOCKED role=%s -> %s" % (role, BLOCKED[role]))
        return 2
    print("spawn_preflight: ok role=%s (not on blocklist)" % role)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
