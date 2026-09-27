# PatchDrop Manual Default Reference

Use this file for queue commands, explicit handoff gates, and verification
details that do not need to load with every skill invocation.

## Queue Probe Commands

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
'{"nodeRole":"desktop","root":".","requestId":"patchdrop-manual-source-scan","sessionId":"patchdrop-manual"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - source_scan
```

## Queue Classifier

| Evidence | Meaning | Default action |
| --- | --- | --- |
| no intersecting/unknown target reservation and `topLevelPatchCount=0` | Desktop source edits are not blocked by PatchDrop | continue Desktop-only preflight |
| `pendingProducerCount>0` but no top-level patch | supporting producer evidence exists | do not consume unless requested |
| one complete top-level `<slug>-v3.patch` bundle | explicit handoff candidate | switch to PatchDrop consumer gates |
| multiple or incomplete top-level patch sidecars | ambiguous queue | stop with `patch-drop-pending` |

## Manual Handoff Gates

When the user explicitly asks to consume PatchDrop, use
`patchdrop-safe-patch-orchestrator` and require:

- exactly one active cumulative v3 patch;
- report, verify log, SHA file, and manifest sidecars;
- manifest-pinned active patch name;
- count-only secret scan;
- `git apply --check`;
- Desktop Gradle verification before moving files to `applied/`.

## Verification Commands

Use the narrowest available checks:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1 -Suite CoreGuards
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
python .\scripts\awx_mcp_completion_audit.py --root .
```

## Fixture Artifact Hygiene

If completion audit reports `dispatch-artifacts-invalid` for generated test
slugs such as `external-proof-test-*`, `handoff-hash-test-*`,
`janitor-test-*`, `missing-handoff-test-*`, or `stale-node-smoke-*`, inspect the
exact prefix under `__patch_drop__\dispatch` before touching anything.

Treat those prefixes as generated fixtures only when the slug itself starts
with one of the test prefixes and there is no explicit user handoff request for
that topic. Clean or quarantine only files matching that exact prefix. Never
remove live topics such as `mcp-control-loop`, `public-domain-migration`, or
`trace-memory-runtime-proof` to make an audit pass.
