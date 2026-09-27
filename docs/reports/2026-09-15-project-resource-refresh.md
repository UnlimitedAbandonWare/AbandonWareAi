# Desktop shared resource refresh — 2026-09-15

Task: `01a0a498-2606-7d92-8003-47a428cc13e7`

## Result

The user-selected existing `.secrets/providers.json` was refreshed using actual
Desktop allowlisted environment/configuration inputs. It now contains 29 settings;
all 29 matched when loaded and checked in the same Desktop PowerShell process at
2026-09-15 20:01 KST. No values are included in this report.

Seven missing names were added:

- `ANTHROPIC_API_KEY`
- `SONIOX_API_KEY`, `SONIOX_STT_ENABLED`, `SONIOX_STT_MODEL`, `SONIOX_STT_REGION`
- `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`

Existing stored values already matched the resolved current inputs, including
`GEMINI_API_KEY`. No fabricated replacement key was used. Older `.env` and
`shared.env` Gemini/OpenAI values remain preserved as legacy fallbacks; current
Windows User values take precedence in the normal environment-driven path.
The discovered service is Upstash Redis. No separate Reddit or Upstage environment
names were observed in the bounded project API-name inventory.

The store's before/after ACL comparison matched. Existing transactional recovery
and baselines remain under `.secrets/`; originals were not copied into reports,
source diffs, agent packets or public registries. SMB/account/firewall permissions
and openssl/opnessl material were not changed.

## Behavior now available

- Every existing bus hook invocation rereads allowlisted inputs and emits a new
  timestamped registry. A previous five-minute cache no longer hides a changed
  environment after denied secret synchronization. Direct hook execution was
  verified; automatic invocation depends on the consuming Codex session trusting
  its unchanged existing hook command.
- Registry rows include environment-name provenance, bounded configuration
  declaration references and explicit probe scope. `status` distinguishes fresh,
  stale and absent device observations. It does not claim another device works.
- The existing control tower skill routes Desktop snapshot refresh and Notebook
  process loading/checking through the shared contract. The manual loader's
  root-bound marker prevents stale Notebook User values from overwriting the
  explicitly loaded snapshot in child runtimes.
- The catalog includes the current Soniox, Deepgram, Upstash aliases, Gemini
  gateway names, Anthropic and database configuration declarations. Original
  provider entries are retained.
- Portable producer kits include the resource catalog and required runtime
  modules. Installation permits the exact new catalog path and excludes actual
  secret/environment files.

Desktop snapshot updates use `python -B scripts/awx_project_keys.py refresh --apply`.
The command has a dry-run default, checks the existing ignored/untracked store and
restricted local ownership/ACL, and uses the existing conflict-aware merge.
It does not run as a new watcher. Consumers reload the selected snapshot before
their next task; an already running unrelated process cannot inherit later changes.

## Observed capability results

Observation: 2026-09-15 20:01:31 KST. These results expire after the registry TTL;
run `python -B scripts/awx_device_bus.py status` to obtain current references.

| Capability | Observed result | Scope |
| --- | --- | --- |
| OpenAI | Available; model count 128, capped | Read-only model listing |
| Deepgram | Available; project count 1 | Read-only project listing |
| Soniox | Available; model count 11 | Read-only model listing |
| Ollama | Available; model count 19 | Local model listing |
| AWX Control Tower | Available; tool count 27 | MCP initialization and tool listing |
| Gemini | Unavailable; HTTP 400 | Model-list request with the current key |
| Groq | Auth-failed classification; HTTP 403 | Model-list request only |
| Upstash Redis/Vector, other APIs and DB declarations | Configured or declared; not probed | No authenticated DB success claim |

Deepgram and Soniox probes follow their official
[project-list](https://developers.deepgram.com/reference/manage/projects/list)
and [model-list](https://soniox.com/docs/api-reference/stt/get_models) interfaces.
Generated text, audio transcription and paid provider generation remain
`not_observed`; generation requests made for this task: zero.

## Verification

- Focused Python suite: **95 tests; 93 passed, 2 skipped**, exit 0.
  The skipped cases are POSIX shell fixtures unavailable on Windows.
- Suites: `test_awx_resource_freshness`, `test_awx_project_resources`,
  `test_awx_multi_device`, `test_awx_device_work`,
  `test_awx_device_work_boundaries`, `test_awx_device_work_integration`.
- Live Desktop direct load/check: **29/29**, missing 0, different 0,
  manual loader active. No raw values printed.
- Skill quick validation passed. Current family validator self-test passed;
  selected skill validation: `ok=true`, 1 valid, 0 invalid, `artifact_ready`.
- Independent read-only direct-snapshot review: no actionable findings.
- Synthetic producer-kit installation and MCP handshake passed within the suite.
- Final task-only diff, postimage hashes and count-only privacy scan are retained
  under `data/agent-handoff/codex-autonomy/resource-refresh-20260915/`.

## Device receipt and remaining evidence

The Notebook verification request is
[project-resource-recognition-20260915.md](../../data/agent-handoff/notebook/project-resource-recognition-20260915.md).
It verifies canonical `Y:\` identity, loads the snapshot, compares values only
in memory and publishes a count-only receipt plus a same-task ACK.

Desktop observation cannot establish Notebook/Mac execution. Their registry and
actual recognition were `not_observed` at verification time. Queue delivery is
reported separately from acknowledgement; inspect `dispatch-proof.json` in the
task evidence directory for the actual event references.

The separate automatic transport-verified secret path still reports
`device-enrollment-evidence-needed`. The user selected direct snapshot use;
that successful manual path does not assert SMB encryption or device enrollment.

## Entry points and recovery

- [Shared resource contract](../project-resource-context.md)
- [Existing control tower skill](../../.agents/skills/demo1-mcp-control-tower/SKILL.md)
- [Direct recognition proof](../../data/agent-handoff/codex-autonomy/resource-refresh-20260915/direct-recognition-proof.json)
- [Final registry reference](../../data/agent-handoff/codex-autonomy/resource-refresh-20260915/final-registry-start.json)
- [Skill validation](../../data/agent-handoff/codex-autonomy/resource-refresh-20260915/skill-validation.json)
- [Task diff and source preimages](../../data/agent-handoff/codex-autonomy/resource-refresh-20260915/)

Source recovery contains only task-scoped non-secret originals. Existing user
changes and the unrelated Git index lock were preserved; no Git index/ref writes,
commit, push or deployment was performed.
