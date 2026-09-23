# Shared API, database and tool context

## Start here on each device

From the verified checkout (`C:\AbandonWare\demo-1\demo-1\src` on Desktop,
canonical `Y:\` on Notebook), run:

```powershell
python -B scripts/awx_device_bus.py start
python -B scripts/awx_device_bus.py status
python -B scripts/awx_device_bus.py inbox
```

On the Mac mini, use its verified existing checkout/shared read surface and
explicitly select its role with `python3 -B scripts/awx_device_bus.py start --device macmini`.
This designation does not authorize source writes or prove remote access.

`start` publishes a new immutable observation. `status` reads all device registry
references and labels each `fresh`, `stale`, or `not_observed`. Read the exact
returned `registryRef`; do not select files by directory order or infer another
device's availability from the Desktop observation. Default TTL is 300 seconds.
Peer observations remain reports, not grants of authority or authenticated remote
service access. `inbox` returns queued event references, not ACKs.

## What is refreshed automatically

The existing project `UserPromptSubmit` command calls `awx_device_bus.py hook`.
Every invocation rereads inputs and publishes current metadata; denied sharing
does not reuse an old five-minute result. The hook definition is unchanged.
Automatic invocation in a new Codex session still requires that session to trust
the existing exact hook command. Directly executing `hook` verifies the command,
not the Codex UI trust setting.

The current Windows precedence is **User > Machine > Process > `.env` >
`shared.env`**. Only `config/project-resources.json` names are eligible. Persistent
environment reads are limited to those names; legacy files are read-only
fallbacks. Quoted values are literal, and shell expressions are never executed.
The original files, old provider entries, and protected openssl/opnessl material
are retained. A conflicting shared/local edit remains a conflict; absence never
deletes a shared value.

`sync` performs this same refresh and reports both a new registry and sharing
status. Unlike `start`, it exits `2` when secret synchronization is unavailable
or conflicted. Use it when successful synchronization is required:

```powershell
python -B scripts/awx_device_bus.py sync --device desktop
```

`awx_host_runtime.py` rereads current allowlisted Windows values when starting a
child and reconciles project settings before loading shared values. It passes
`AWX_CAPABILITY_REGISTRY_ROOT` and a reference-only `AWX_CAPABILITY_CONTEXT` to the
child. The context contains age/TTL and can become stale: rerun `status` for the
latest references. Existing unrelated processes need their owners to restart
them; a registry refresh cannot change an already-running process's environment.

This runs at task submission, explicit sharing, and child launch. It is not a
continuous daemon, file watcher, or newly scheduled paid loop.

## Reading the registry

| Field | Meaning |
| --- | --- |
| `configuredNames` / `valueSources` | Which expected names were found and whether they came from current environment, a legacy file, or the protected shared store |
| `configurationRefs` | Existing application configuration paths, property names, and variable references; profiles and effective runtime activation are explicitly unverified |
| `secretRefs` | References only; real values require the existing protected store and its access checks |
| `inputSummary.legacyDifferentNames` | Legacy values differing from current environment; names only, with original files preserved |
| `mcpConfiguration.servers` | Server names, enabled flags, transport types, and environment names; commands, URLs, credentials, and OAuth sessions are not copied |
| `status` / `reason` | Result and scope of the specific bounded probe; a configuration declaration alone is `not_probed` |
| `generationStatus` | `not_observed`: catalog/project listing is not generated text or successful transcription |
| `remoteUsability` | `not_attested` until the consuming device provides its own evidence |
| `sharedSecrets` | Actual sharing result; startup success alone does not prove synchronization |

Read-only provider probes are bounded by the existing 3-second request / 20-second
provider budget. OpenAI/Gemini/Groq/Soniox list models; Deepgram lists projects.
Ollama lists local models and AWX verifies MCP initialization plus tool listing.
Browser executable presence is distinguished from browser session control.
No audio, prompts, DB writes, or model generation are sent by these probes.

Official speech-provider references: [Soniox model list](https://soniox.com/docs/api-reference/stt/get_models)
and [Deepgram project list](https://developers.deepgram.com/reference/manage/projects/list).
Non-US Soniox regions require an attested regional route before probing.

## User-selected direct snapshot and skill use

The existing `.env` and `shared.env` can explicitly select the saved OpenAI key
without carrying its value:

```dotenv
OPENAI_API_KEY=.secrets/providers.json#/values/OPENAI_API_KEY
```

The project input loader resolves only this exact OpenAI reference through the
existing manual `SecretStore` reader on every launch. This selected snapshot
overrides a stale inherited OpenAI environment value, including on Notebook/Mac
consumers. Other provider settings retain their existing precedence. Missing,
invalid or unusable referenced values stop the load with a redacted reason;
the reference is never sent as a credential. This is explicit file selection,
not automatic transport-attested synchronization. The existing mount and file
access must already be available. Generic dotenv tools do not resolve this JSON
reference; launch through the existing project runtime or manual loader.

An explicit Desktop `refresh` still imports the current Windows User environment
into the saved snapshot, so the reference cannot pin a previous key during the
next authorized rotation. It does not copy values back into either legacy file.

When the user selects direct in-project API settings, Desktop updates the existing
Git-ignored, untracked `.secrets/providers.json` using its current local ACL and
the same three-way merge/recovery rules:

```powershell
python -B scripts/awx_project_keys.py refresh
python -B scripts/awx_project_keys.py refresh --apply
```

The first command plans without writing; the second performs the authorized
snapshot update. It adds or refreshes usable allowlisted inputs, retains absent
and legacy entries, and never writes to `.env` or `shared.env`. The exact Desktop
root, local owner/restricted ACL, ignored/untracked state, and preimages are
checked. This explicit direct mode does not change share/account permissions or
assert encrypted transport. Its actual values and recovery remain in `.secrets`.

The existing `demo1-mcp-control-tower` skill links this contract. On Notebook, run
the following in one PowerShell process after verifying its canonical `Y:\` root:

```powershell
. ./scripts/use_project_keys.ps1
python -B scripts/awx_project_keys.py check
python -B scripts/awx_device_bus.py start --device notebook
```

`check` compares process values with the file in memory and returns only matched,
missing, and different name counts/lists. A loader marker bound to this source
root preserves explicitly loaded values in child runtimes and the registry;
stale persistent User settings do not override the selected snapshot. Reload the
file before a new task to receive changes. The marker records selection, not
transport attestation or successful provider generation.

Mac agents can read the same reference and use the existing file-backed
`SecretStore` representation inside an explicitly authorized consuming process;
never serialize values to shell commands, logs, prompts, or the public registry.
The Windows direct refresh is Desktop-owned; a Mac mount is not a refresh target.

## Optional automatic transport-verified sharing

All actual shared project values, three-way baselines, and recovery stay in
`.secrets/`. Automatic read/write continues to require the unchanged live account,
NTFS, encrypted SMB, and approved-device checks:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/awx_secrets_acl.ps1 -Action Verify
```

Missing enrollment/encryption does not stop metadata refresh, the separately
selected direct snapshot, or existing local runtime values. It stops the automatic
transport-verified path. `scripts/use_project_keys.ps1` remains current-process
only and does not itself refresh missing entries or attest automatic sharing.

To prepare enrollment, supply verified SMB account names and exact Notebook/Mac
device addresses to the existing `-Action Plan -AccountName ... -DeviceAddress ...`.
The configuration operation affects encryption of related shares and creates a
**host-wide** inbound TCP 139/445 restriction for other devices; its final apply
requires the existing administrator and explicit acknowledgement conditions.
Do not guess devices, weaken the access gate, or copy personal sessions.

## Portable tools and communication

Producer kits now include the resource catalog and all resource/runtime module
dependencies. Their installer permits exactly `config/project-resources.json`
as the new config artifact; `.env`, `shared.env`, `.secrets`, and unrelated config
files are excluded. A local kit install/handshake test is not peer consumption.
Use the existing immutable event queue for a task's report references; only a
separate peer `acknowledged` event with the same task UUID proves consumption.
