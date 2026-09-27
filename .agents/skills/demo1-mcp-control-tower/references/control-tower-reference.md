# Control Tower Reference

Use this reference only after `demo1-mcp-control-tower` triggers and the task
needs detailed command examples, archive rules, producer bundle rules, or
verification command variants.

## Command Cookbook

Invoke toolbox commands with stdin JSON to avoid PowerShell quoting drift:

```powershell
'{"nodeRole":"macmini","q":"search terms"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - archive_search
```

Expose the same manifest over line-delimited JSON-RPC:

```powershell
python .\scripts\awx_mcp_stdio_server.py
```

Render a producer-local MCP config with source-isolation guard:

```bash
python scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --output .codex/awx-control-tower.mcp.json --audit-log .codex/awx-control-tower.audit.jsonl
```

Run producer node smoke from a producer-local worktree or clone:

```bash
python scripts/awx_mcp_node_smoke.py --root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --node-role macmini
```

Run producer smoke-to-bundle handoff:

```bash
python scripts/awx_mcp_producer_handoff.py --source-root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --patchdrop-root /path/to/PatchDrop --producer-script ./__patch_drop__/producer_bundle.py --node-role macmini --topic topic_slug --pathspec relative/path --audit-log .codex/awx-control-tower.audit.jsonl
```

Audit the local control-tower contract without treating external host proof as
complete:

```powershell
python .\scripts\awx_mcp_completion_audit.py --root .
```

Run the default Desktop control loop without dispatch or producer-kit writes:

```powershell
'{"nodeRole":"desktop","root":".","topic":"topic_slug","patchdrop_root":".\\__patch_drop__","write_dispatch":false,"write_producer_kit":false,"require_producer_bundles":false,"require_supabase_live_proof":false}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - desktop_control_loop
```

Use dispatch or producer-kit writes only for explicit multi-node producer work:

```powershell
'{"nodeRole":"desktop","topic":"topic_slug","patchdrop_root":".\\__patch_drop__","write_dispatch":true,"write_producer_kit":true,"producer_roots":{"macmini":"macmini-worktree","notebook":"notebook-worktree"},"pathspec":["relative/path"]}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - desktop_control_loop
```

Export a producer install kit into PatchDrop only when producer worktrees need
bootstrap files:

```powershell
'{"nodeRole":"desktop","topic":"topic_slug","patchdrop_root":".\\__patch_drop__"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - producer_kit_export
```

Validate copied Mac mini or Notebook smoke evidence:

```powershell
'{"nodeRole":"desktop","evidence_dir":"data\\agent-handoff\\mcp-control-tower","required_roles":["macmini","notebook"]}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - external_evidence_audit
```

Render a two-node dispatch packet:

```powershell
'{"nodeRole":"desktop","topic":"topic_slug","patchdrop_root":".\\__patch_drop__","producer_roots":{"macmini":"macmini-worktree","notebook":"notebook-worktree"},"pathspec":["relative/path"]}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - desktop_dispatch_packet
```

Emit copyable PatchDrop dispatch files:

```powershell
'{"nodeRole":"desktop","topic":"topic_slug","patchdrop_root":".\\__patch_drop__","write_dispatch":true,"producer_roots":{"macmini":"macmini-worktree","notebook":"notebook-worktree"},"pathspec":["relative/path"]}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - desktop_dispatch_packet
```

## Standard Flow

Safe Patch flow contract:

1. Desktop broad loop: run `desktop_control_loop` in read-only mode for
   Desktop-only checks. Add dispatch or producer-kit writes only when assigning
   distributed work. It combines `source_scan`, optional dispatch refresh, and
   `external_evidence_audit` while keeping Desktop final proof as
   `evidence_needed`.
2. Producer bootstrap: run `producer_kit_export` when producer worktrees may
   not already contain MCP scripts, manifest, skills, and prompts. Install the
   exported kit into producer-local worktrees only.
3. Broad probe: run `source_scan` and inspect PatchDrop/source isolation when a
   narrower one-tool probe is enough.
4. Focused probe: use `archive_search`, focused source reads, or current
   failure evidence to narrow the target.
5. Minimal diff: use `patch_plan` and `patch_render` to produce only the
   smallest PatchDrop candidate.
6. Desktop verification: final apply and Gradle/boot proof stay on the Desktop
   canonical root.
7. Failure classification: use `build_error_mine` and tool `failReason` fields
   without raw log dumps.
8. Retry only once per unchanged failure class.

## Tool Notes

- `source_scan`: broad probe of active roots, PatchDrop state, env-name
  references, and secret-pattern hit count.
- `archive_search`: search `index_path`, `ARCHIVE_INDEX`,
  `NAS_ARCHIVE_ROOT/index.jsonl`, then `BackupsXS/index.jsonl` with `q`,
  `filters`, and `top_k`; require at least two passes and emit
  `evidence_needed` if still empty.
- `patch_plan`: convert evidence into a direct/2-way/N-way Safe Patch plan.
- `patch_render`: produce the PatchDrop v3 sidecar contract and scan any
  candidate patch for secret/filemode blockers.
- `desktop_control_loop`: default Desktop check command; keep
  `write_dispatch=false` and `write_producer_kit=false` for Desktop-only work.
  Set `write_dispatch=true` only to refresh dispatch JSON, role command files,
  and Desktop intake script under PatchDrop `dispatch/`, then read
  `nextActions`.
- `producer_kit_export`: writes `__patch_drop__/producer-kit/topic-producer-kit/`
  with checksummed MCP runner files, schemas, skills, prompts, and install
  helpers.
- `desktop_dispatch_packet`: available when only dispatch rendering is needed.
- `producer_command_plan`: renders exact node setup, single-node smoke, and
  PatchDrop handoff commands from a producer-local worktree.
- `boot_verify`: returns role-local verification commands; Desktop final proof
  remains `evidence_needed` until run on the canonical root.
- `build_error_mine`: classifies build logs without raw log dumps.
- `external_evidence_intake`: copies valid PatchDrop proof into
  `data/agent-handoff/mcp-control-tower`.
- `external_evidence_audit`: validates Mac mini or Notebook host proof without
  raw logs and returns `nextActions` and `optionalNextActions` when proof is
  missing.

## Archive Rules

- `archive_search` reads only `index.jsonl` rows and returns path/title/hash
  evidence, not full archived content.
- `archive_restore` must receive `mode=restore`, `glob`, `target_dir`, and
  `audit_log`; pass `verify_log` when a producer or Desktop needs append-only
  checksum proof.
- Mac mini and Notebook `archive_restore` calls must target local temp/worktree
  paths. If `canonical_root` is provided and `target_dir` is under it, the tool
  must return `restore_target_blocked` / `smb-conflict-risk`.
- Restore returns explicit `preReview.performed`, `preReview.candidateCount`,
  and restored-row `checksumVerified=true` evidence.
- Audit records contain only `requestId`, `sessionId`, `nodeRole`, `toolName`,
  `inputHash`, `outputCount`, `elapsedMs`, `decision`, and `failReason`.
- `verify_log` rows can contain pre-review plus checksum evidence without
  restored file contents.

## Producer Bundle Rules

`external_evidence_audit` rejects producer `.patch` sidecars with:

- Git file mode headers: `filemode-blocked`.
- Path traversal, absolute, or UNC targets: `unsafe-path`.
- Forbidden targets such as `pages/api/**`, `.env*`, `apikey.*`, keystores, and
  shared cache/build directories: `forbidden-path:*`.

Desktop janitor still owns any explicit override decision during final apply.

## Verification Commands

Toolbox regression:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox_tests.ps1
```

Local completion audit:

```powershell
python .\scripts\awx_mcp_completion_audit.py --root .
```

For broader Desktop proof, continue with janitor and Gradle gates from
`patchdrop-safe-patch-orchestrator`.
