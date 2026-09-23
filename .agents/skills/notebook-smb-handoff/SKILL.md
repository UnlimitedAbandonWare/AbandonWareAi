---
name: notebook-smb-handoff
description: Use when a Notebook or Laptop agent must hand current task context, instructions
---

# Notebook SMB Handoff

## Overview

Write one lightweight handoff packet to `Y:\data\agent-handoff\notebook\`.
`Hand-off Done` means only that the exact named packet exists there as a
non-empty file. It does not prove that Desktop read, accepted, applied, or
verified the packet.

## Contract

- Owner: Notebook packet producer.
- Inputs: current task, instructions, explicit data paths, and optional notes or
  requested Desktop action. Do not invent missing facts; write
  `evidence_needed` when necessary.
- Output: exactly one direct-child `.json` or `.md` packet. Default to JSON; use
  Markdown when the caller requests an `@superpowers` packet.
- Mutation surface: only the caller-named packet file under the fixed handoff
  directory.
- Keep the packet concise and finish within five minutes. Do not add retry
  loops or supporting files.
- Never include raw credentials, tokens, cookies, authorization headers, or
  private environment values. Use redacted booleans, names, and paths instead.

Use a simple filename with no directory separators or `..`. Do not overwrite an
existing packet unless the caller explicitly authorizes that exact overwrite.

Do not use this skill for a sealed SourceDirective Canary, PatchDrop bundle,
application-source change, Desktop ACK, runtime proof, commit, push, or deploy.
Those workflows retain their own guards.

## Lightweight workflow

1. Confirm that `Y:\data\agent-handoff\notebook\` exists.
2. Summarize the supplied task, instructions, data paths, and Desktop action in
   one JSON file or one Markdown file. Start Markdown packets with
   `@superpowers`.
3. Write the selected file directly to the fixed handoff directory.
4. Check only that the exact file exists and is non-empty:

   ```powershell
   $packet = 'Y:\data\agent-handoff\notebook\<packet-name>.json'
   $handoffDone = (Test-Path -LiteralPath $packet -PathType Leaf) -and
       ((Get-Item -LiteralPath $packet).Length -gt 0)
   if ($handoffDone) {
       'Hand-off Done: PASS'
   } else {
       'Hand-off Done: HOLD (handoff-file-not-written)'
   }
   ```

Do not add SHA-256 hashes, directory locks, manifests, ready markers, strict
schema validation, acknowledgement waits, complex TDD, or elaborate exception
handling. They are outside this lightweight handoff contract.

## Suggested JSON shape

This is a writing aid, not a schema:

```json
{
  "from": "notebook",
  "to": "desktop",
  "currentTask": "Summarize the current work",
  "instructions": ["State the next Desktop action"],
  "dataPaths": ["Y:\\path\\to\\needed-data"],
  "notes": ["evidence_needed where facts are missing"]
}
```

For Markdown, use the same field names as headings after `@superpowers`.

## Result and rollback

Return `Hand-off Done: PASS` only when the exact named packet is a non-empty
file. Otherwise return `HOLD (handoff-file-not-written)` and stop; do not claim
Desktop receipt. Treat an unsafe filename or secret-bearing content as HOLD
before writing.

Rollback removes only the exact packet created by the current run, and only
when the caller requests removal or confirms that packet is invalid. Never
delete or rewrite the handoff directory.

## Falsifying check

Revise this skill if it reports PASS for a missing or empty packet, or if it
requires a hash, lock, schema, manifest, ready marker, or Desktop ACK after a
valid non-empty packet has been written.
