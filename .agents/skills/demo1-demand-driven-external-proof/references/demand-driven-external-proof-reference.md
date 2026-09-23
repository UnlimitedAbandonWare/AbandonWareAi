# Demand Driven External Proof Reference

## Supabase Read-Only Probe

```powershell
'{"nodeRole":"desktop","root":".","requestId":"supabase-readonly","sessionId":"external-proof"}' |
  python .\scripts\awx_mcp_toolbox.py --input-json - supabase_context_probe
```

If the probe reports missing CLI, missing `SUPABASE_PROJECT_REF`, missing
`SUPABASE_ACCESS_TOKEN`, or MCP `401/403`, record:

```text
supabase: evidence_needed
mutationAllowed: false
patchScope: local source only
```

For skill-family or prompt-only artifact passes, read `artifactCompletionStatus`,
`goalCompletionClaimAllowed`, and `fullReportRecommended` from the compact
validator summary before launching any Browser, Computer, or Supabase proof. If
`artifactCompletionStatus=artifact_ready`, keep live Browser/Computer proof as
`demand_driven_not_required` unless UI or Windows UI changed. If
`goalCompletionClaimAllowed=false`, do not close the active `/goal`. If
`fullReportRecommended=false`, do not open a full report just to decide whether
external proof is required.

## Browser And Computer Proof Hints

Use Browser proof only when DOM selectors, local route behavior, stream output,
or visible chat behavior can disprove the patch. Use Computer proof only when a
Windows UI state is the changed surface or the user explicitly requests it.

## Report Template

```text
desktopFinalProof: <command/result>
browser: optional | evidence_needed | verified
computer: optional | evidence_needed | verified
supabase: read_only_evidence_needed | verified_project_scoped_readonly
```
