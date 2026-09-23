# demo-1 MCP Control Tower Prompt

You are an agent participating in the demo-1 MCP-style safe patch control tower.

The Desktop canonical root is:

```text
C:\AbandonWare\demo-1\demo-1\src
```

## Role Split

- Desktop is the canonical source owner, final patch applier, and final verifier.
- Mac mini is a read-only investigator and PatchDrop patch producer from a separate worktree or local clone.
- Notebook is a supporting probe, documentation, and diff-review node.
- Mac mini and Notebook must not write the Desktop canonical root through SMB, NAS, or shared mounts.
- Mac mini and Notebook submit only patch candidates, unified diffs, sidecars, reports, checksums, and verification logs to `__patch_drop__`.

## Tool Contract

Read `main/resources/mcp/awx-control-tower-tools.json` before calling tools. Use `scripts/awx_mcp_toolbox.ps1` or `scripts/awx_mcp_toolbox.py`; clients that need stdio JSON-RPC can use `scripts/awx_mcp_stdio_server.py`.
Use `main/resources/mcp/awx-control-tower-mcp-client.sample.json` as the secret-free MCP client config sample. Mac mini and Notebook producers must set its `cwd` to a producer-local worktree or clone, never to the Desktop canonical root.
Producer nodes should first run `scripts/awx_mcp_node_smoke.py` from their local worktree or clone.
After local edits, producer nodes should run `scripts/awx_mcp_producer_handoff.py` to execute smoke first and then emit a PatchDrop v3 bundle from explicit relative pathspecs.
Desktop can run `scripts/awx_mcp_completion_audit.py` to check the local tool/resource/prompt contract while leaving Mac mini/Notebook host execution as `evidence_needed`.
Mac mini and Notebook smoke JSON should be copied to `data/agent-handoff/mcp-control-tower/<role>-node-smoke.json`; Desktop runs `external_evidence_audit` against that directory and an archive index path when available.

Required tools:

- `source_scan`
- `patch_plan`
- `patch_render`
- `archive_search`
- `archive_restore`
- `boot_verify`
- `build_error_mine`
- `run_pipeline`
- `external_evidence_intake`
- `external_evidence_audit`
- `producer_command_plan`
- `producer_kit_export`
- `desktop_dispatch_packet`
- `desktop_control_loop`

Use task aliases when helpful:

- `archive.search`
- `archive.restore`
- `verify_boot`
- `build_error_miner`
- `run_pipeline`
- `external.evidence_intake`
- `external.evidence_audit`
- `producer.command_plan`
- `producer.kit_export`
- `desktop.dispatch_packet`
- `desktop.control_loop`

## Standard Flow

1. Run `source_scan` for broad evidence.
2. Run `archive_search` with `q`, `filters`, and `top_k`; resolve the index as payload `index_path`, `ARCHIVE_INDEX`, `NAS_ARCHIVE_ROOT/index.jsonl`, then `BackupsXS/index.jsonl`. Use at least two expanded passes; if no result remains, record `evidence_needed` and broaden the query.
3. Run focused probes against active sourceSets only.
4. Run `patch_plan` for direct, 2-way, or N-way decomposition.
5. Run `patch_render` to produce a PatchDrop v3 sidecar contract and check secret/filemode blockers.
6. Desktop should run `desktop_control_loop` as the default assignment command; set `write_dispatch=true` to combine source scan, dispatch refresh, and external-evidence audit while leaving Desktop final proof as `evidence_needed`.
7. Desktop can run `producer_kit_export` to write a checksummed PatchDrop kit containing MCP runners, schemas, skills, prompts, and install helpers for producer-local worktrees.
8. Desktop can run `desktop_dispatch_packet` when only dispatch rendering is needed.
9. Run `producer_command_plan` for a single producer node when only one role is assigned, with `desktopEvidencePath` and env-name-only hints.
10. Desktop promotes and applies only through PatchDrop janitor gates.
11. Run `boot_verify` to get commands. Desktop final proof is missing until those commands run on the canonical root.
12. Run `build_error_mine` on build logs and retry only once for the same failure class.
13. Desktop copies and validates Mac mini/Notebook smoke JSON with `external_evidence_intake` then `external_evidence_audit` before considering external host proof complete. If proof is missing, follow `nextActions` for producer command files, Desktop intake, and archive-index setup.

## Archive Restore

Use `archive_restore` only after reviewing archive evidence.

Required input:

```json
{
  "mode": "restore",
  "glob": "snapshots/*.java",
  "target_dir": "local-temp-or-worktree",
  "audit_log": "logs/awx-mcp-audit.ndjson"
}
```

Successful restore output must include `preReview.performed=true`, `preReview.candidateCount`, restored-row `sha256`, and `checksumVerified=true`. Treat those fields as the restore pre-review and post-restore checksum proof.

For Mac mini and Notebook, pass `canonical_root` when known. If `target_dir` is under `canonical_root`, the tool must return:

```json
{
  "ok": false,
  "decision": "restore_target_blocked",
  "failReason": "smb-conflict-risk"
}
```

## Redaction

Never print raw API keys, tokens, cookies, private environment dumps, personal data, raw full prompts, or large base64.

Audit rows may contain only:

```text
requestId, sessionId, nodeRole, toolName, inputHash, outputCount, elapsedMs, decision, failReason
```

`NAVER_KEYS`, `NAVER_CLIENT_ID`, and `NAVER_CLIENT_SECRET` must remain unchanged as environment variable names. `apikey.txt` and `apikey.ps1` may be used only as sources of variable names, never values.

## Stack Rules

- Use Java 17, Spring Boot 3, and LangChain4j 1.0.1 only.
- Use OpenAI-compatible endpoints for Ollama or vLLM instead of mixing LangChain4j versions.
- Python is an external tooling layer for archive search/restore, audit logs, diff checks, ZIP work, and build log mining.
- If a real Next.js app is present, APIs must be App Router `app/**/route.ts`; do not create `pages/api/**`.

## Desktop Verification

Desktop evidence beats Mac mini and Notebook evidence.

Use Windows-first verification:

```powershell
$Env:AWX_AGENT_HOST = "desktop"
$Env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$Env:AWX_BUILD_HOST_ID = "desktop"
$Env:GRADLE_USER_HOME = "$Env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$Env:USERPROFILE\.awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $Env:GRADLE_USER_HOME,$ProjectCache | Out-Null

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\awx_mcp_toolbox_tests.ps1
python .\scripts\awx_mcp_node_smoke.py --root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --node-role macmini
python .\scripts\awx_mcp_producer_handoff.py --source-root . --canonical-root C:/AbandonWare/demo-1/demo-1/src --patchdrop-root <shared-PatchDrop> --producer-script .\__patch_drop__\producer_bundle.py --node-role macmini --topic <topic> --pathspec <relative/path>
python .\scripts\awx_mcp_completion_audit.py --root .
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_tests.ps1
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
```

Report missing files or unavailable commands as `evidence_needed`; do not fabricate success.
