# Ox Alpha read-only delegate

This tool gives Codex an optional, fail-open analysis lane backed by OpenCode
Zen's `opencode/x-preview-f-free`. It does not change the Codex default model,
apply model output, edit source, or expose an MCP server.

## Contract

Send one UTF-8 JSON object on stdin:

```json
{
  "prompt": "Find defect candidates, counterexamples, required tests, and likely side effects.",
  "workingDirectory": "C:\\path\\to\\checkout",
  "allowlistedPaths": ["src/example.py", "docs/behavior.md"],
  "timeoutSeconds": 180,
  "requestId": "analysis-001"
}
```

The process writes one JSON object on stdout with exactly these fields:

- `status`
- `summary`
- `findings[]`
- `evidence[]`
- `proposedTests[]`
- `elapsedMs`
- `exitCode`
- `model`
- `stderrTail`
- `changedFiles[]`
- `fallbackUsed`, `cliVersion`
- `firstEventMs`, `firstToolEventMs`, `lastToolEventMs`, `firstTextEventMs`
- `modelTextBytes`, `terminationReason`, `outputContractExceeded`

Run it from the repository root:

```powershell
$request = @{
    prompt = "Find defect candidates; do not propose a patch."
    workingDirectory = (Get-Location).Path
    allowlistedPaths = @("tools/build_error_guard.py", "tools/README.build_guard.md")
    timeoutSeconds = 180
    requestId = "analysis-001"
} | ConvertTo-Json -Compress
$request | python tools\ox-alpha-delegate\ox_alpha_delegate.py
```

Analyzer failures are returned as data and the CLI exits `0`, allowing Codex to
continue its existing local workflow. Codex must independently reopen every
reported file and line and run the proposed tests before using a finding.

Before any model command, the delegate runs `opencode --version` in the isolated
child profile. Only the exact version `1.15.12` may proceed. Every mismatch or
unrecognized version returns `status=unsupported_cli_version` and
`fallbackUsed=true` without starting `opencode run`; the delegate never installs,
upgrades, or downgrades OpenCode. On Windows, resolution is limited to the
approved user-global `opencode-ai` installation instead of the current directory
or an arbitrary `PATH` hit. Its SHA-256 identity is compared before and after the
version check, before the API key can enter the model child environment.

## Isolation and permissions

- The real `workingDirectory` is never passed to OpenCode. Only explicitly
  allowlisted UTF-8 files are copied into a temporary workspace.
- Directory allowlists omit `.env`, `.env.*`, `apikey.txt`, names containing
  `secret`, `token`, `opnessl`, or `openssl`, private-key/certificate formats,
  Git metadata, Codex configuration, OpenCode/MCP project configuration,
  symlinks, and Windows reparse points.
- Explicitly allowlisting a prohibited path rejects the request before a child
  process starts. Secret-like prompt or file content is also rejected.
- Every valid request ID is represented in diagnostics only as a fixed-length
  SHA-256-derived identifier; an ID containing the active API key is rejected
  before a child starts.
- OpenCode is invoked directly, without a shell, as
  `opencode --pure run --model opencode/x-preview-f-free --format json
  --title codex-ox-alpha-probe --dir <temporary-workspace>`.
- `--auto` and `--share` are never used. Sharing is disabled in the child-only
  configuration.
- The permission map denies `*`, then allows only `read`, `glob`, and `lsp`;
  `external_directory` is explicitly denied. Edit, shell/bash, task/subagent,
  skill, web fetch/search, and every unnamed permission remain denied.
- The same five-entry permission map is injected through both the retained
  `OPENCODE_CONFIG_CONTENT` and the final child-only `OPENCODE_PERMISSION`.
  `OPENCODE_DISABLE_PROJECT_CONFIG=1`,
  `OPENCODE_DISABLE_DEFAULT_PLUGINS=1`,
  `OPENCODE_DISABLE_LSP_DOWNLOAD=1`, and
  `OPENCODE_DISABLE_CLAUDE_CODE=1` are also child-only. No user-level environment
  variable is written.
- Data, cache, config, state, database, npm cache, and temporary paths are all
  redirected under the one disposable child profile. The delegate does not
  read or copy the user's OpenCode authentication store.
- If `OPENCODE_API_KEY` already exists in the parent environment, it is passed
  only in the child environment. The value is never written to source,
  configuration, stdout, or diagnostics. With no usable authentication, the
  OpenCode failure remains a normal fail-open result.
- Pre/post SHA-256 manifests cover both the sanitized copies and every original
  allowlisted file. Any change suppresses findings and returns
  `status=workspace_changed` with relative paths only.

One absolute timeout deadline includes the stdin write and process wait. Stdin is
written asynchronously, stdout and stderr are retained only to fixed byte
limits, and timeouts or stream-limit violations terminate the child process tree.
On Windows, each child is created suspended, assigned to a kill-on-close Job
Object, and resumed only after containment succeeds; `taskkill /T /F` remains a
fallback for terminating an uncontained suspended process.
The delegate distinguishes timeout, rate limit, nonzero process exit, oversized
process output, malformed JSONL, malformed model JSON, empty response, permission
violation, sensitive output, insufficient file-line evidence, and workspace
mutation.

A successful model contract is bounded to a 500-character summary, 1-3
findings, 3-6 evidence entries, 1-4 proposed tests, and 300 characters for each
individual description field. The complete concatenated model text is limited
to 8192 UTF-8 bytes. Any upper-bound violation is discarded without truncation
as `status=contract_exceeded`; timeout and incomplete JSON are never recovered
as success. The prompt stops exploration after three important verifiable
defects and removes duplicate evidence. Findings must be descriptive objects,
evidence lines must be real JSON integers, and proposed tests must be nonblank
strings. Non-finite numbers and unpaired Unicode surrogates in values or nested
object keys are rejected as malformed responses, and emitted JSON uses strict
finite-value serialization.

## Redacted diagnostics

One compact JSON diagnostic is written to stderr. Its allowlisted fields are:

- `request_id`, `model`, `started_at`, `elapsed_ms`, `exit_code`
- `parsed_event_count`, `stdout_bytes`, `stderr_bytes`, `timeout`
- `changed_files`, `redacted_secret_count`, `fallback_used`, `final_reason`
- `cli_version`, `first_event_ms`, `first_tool_event_ms`, `last_tool_event_ms`
- `first_text_event_ms`, `model_text_bytes`, `termination_reason`
- `output_contract_exceeded`

The diagnostic never contains the prompt, source text, Authorization headers,
or a credential value. `stderrTail` preserves its existing field name but holds
only an empty value or the fixed marker `[REDACTED_CHILD_STDERR]`; raw child
stderr is never returned. Any detected sensitive model output suppresses all
findings.

## Verification and Phase 3 boundary

```powershell
python scripts\test_ox_alpha_delegate.py
python -m py_compile tools\ox-alpha-delegate\ox_alpha_delegate.py scripts\test_ox_alpha_delegate.py
```

The stdio MCP wrapper and Codex user-level MCP registration are deliberately not
part of this directory. They may be added only after one declared set of three
identical live probes passes all integrity, JSON, evidence, and redaction gates.
Zen automatic reload and monthly spending limits remain user-controlled billing
settings; this tool never reads or changes them.
