---
name: demo1-grok-subscription-review
description: Use when the user explicitly requests Grok
---

# Grok subscription review

Use the existing AWX Control Tower tool `grok_review_change`.
The pinned official CLI runner excludes model execution tools and enforces
owned-process cleanup and closed response validation. Real authenticated
Grok generation and cold-start selection still require live acceptance proof.
Do not describe installation, status or mock responses as model success.

For an explicit Grok request, check `mode=status` once. On `ready`, use one
`mode=review` request from the Desktop-verified acceptance window and validate
the findings against the supplied evidence. On `blocked`, record the reason
and continue the parent task; do not repeat an unchanged blocker or bypass
it with a direct CLI/API/BYOK/credit/provider fallback.

The initial runner permits at most three distinct acceptance requests using
a locally bound account, auth-file fingerprint, exact policy and executable.
Official UI billing evidence expires within 15 minutes. It does not enable
continuous unattended reviews. A new window requires current official
account/billing verification; caller/environment flags cannot enable it.
A missing/expired window is an operation-local hold, not a request to buy
credits, change subscriptions, or weaken the boundary.

Only parent-checked public/synthetic text may leave. Paths are labels, never
file-reading instructions. Limits: one concurrent call, 90 seconds, input
32 KiB, 8 evidence items/24 KiB, at most five findings. Parent Codex owns edits,
validates returned evidence IDs and independently tests any adopted proposal.
Preserve local Free SWE-2 priority and existing Gemini routing; never send
one logical review to multiple providers. Tiny wording changes need no call.

The initial implementation's V0-V7 acceptance and its exact limitations are
retained in `data/agent-handoff/codex/report/three-provider-mcp-20260914-01a09d05/verification.json`.
Consult that completion evidence before reopening initial setup; an old
directive-ready sidecar is historical provenance, not a new execution request.
For subsequent reviews, apply the current bounded acceptance-window rules above;
past completed acceptance never authorizes generation from an expired window.
Report source, CLI/MCP response, semantics, automatic selection and provider
wire evidence separately. `cli_model_usage` is CLI metadata, not a remote
provider attestation. Do not mark the whole directive complete before its
required authenticated and cold-start checks pass.
