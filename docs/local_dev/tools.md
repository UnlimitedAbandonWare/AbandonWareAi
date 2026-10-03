# aw-dev tool registry

Every case in `tools/local_dev/tool-registry.json` is a *delegation record*:
the child argv, allowed arguments, timeout, network class, required files,
and how aw-dev classifies the child's `run_verified_command` record.

## Registry schema (consumed fields)

| field | meaning |
|---|---|
| `id` | case id (`[a-z0-9][a-z0-9._-]{0,63}`) |
| `action` | `test` or `smoke` |
| `argv` | argv template; `py:`/`node:`/`bat:`/`ps1:` prefixes resolve launchers |
| `steps` | optional ordered sub-runs; first failing step stops the case |
| `allowedArgs` | whitelist of `--name` args (`token`/`int`/`fqcn`/`relpath` kinds) |
| `cwd` | child working dir, resolved under root |
| `timeoutS` | per-run timeout forwarded to `run_verified_command` |
| `network` | `none` / `loopback` (`remote-paid` is never registered) |
| `envPolicy` | `scrub` (default) or `inherit-min` |
| `envAdd` | extra env names injected into the child (never values from secrets) |
| `resultKind` | `exit` / `junit` / `json` |
| `xmlDir`,`suites`,`junitClasses` | JUnit evidence contract for `junit` kind |
| `jsonChecks` | `[{path,field,equals}]` evidence assertions for `json` kind |
| `testCountRegex` | regex with capture group 1 = executed count (e.g. `Ran (\d+) tests?`) |
| `requiredFiles`/`requiredTools` | preflight gate; missing → 10 (owner `aw-dev`) or 12 (upstream) |
| `upstreamOwner` | `aw-dev`/`devin`/`grok`; non-`aw-dev` missing → `PENDING_UPSTREAM` |
| `preCheck` | `no-gradle-test-busy` refuses a second Gradle `:test` JVM |
| `sources`/`fixtures` | hashed into `sourceManifestSha256`/`fixtureHash` |

Substitution tokens: `{root}` `{runDir}` `{buildHostId}` `{arg:<name>}`.

## Registered cases

| case | action | network | delegates to |
|---|---|---|---|
| `aw-selftest` | smoke | none | `tools/local_dev/tests/test_aw_dev.py` |
| `env-names-fixture` | smoke | none | `fixtures/env_names.py` (names only) |
| `jev-mock-smoke` | smoke | loopback | `scripts/jev_mock_gateway.py --smoke-out` (vercelCalls==0 asserted) |
| `fixture-child-fail` | test | none | `fixtures/exit_with.py 6` (exit passthrough) |
| `fixture-zero-tests` | test | none | `fixtures/zero_tests_xml.py` (→ INCONCLUSIVE 11) |
| `rag-verify` | test | loopback | `Verify-RAG.bat` (`AWX_AGENT=1 AWX_RAG_JSON=1`) |
| `java-jev-focused` | test | none | `gradlew.bat :test --tests <fqcn>` (split outputs, `--no-daemon`) |
| `jev-offline-selftest` | test | loopback | `test_jev_mock_gateway`, `test_jev_spend_ledger`, `test_jev_gateway_smoke`, `zdr_guard --vocab --strict` |
| `jev-campaign-offline` | test | loopback | Grok G-1/3/4/7 `test_jev_campaign_*` suites |
| `jev-report-check` | test | none | `scripts/jev_campaign_report_check.py --report <path>` |
| `jev-score` | test | none | `scripts/jev_campaign_score.py --results <path>` |
| `jev-payload-guard` | test | none | `scripts/jev_payload_guard.py <payload>` |
| `jev-tally` | test | none | `scripts/jev_spend_tally.py --json` (read-only) |

`start` is intentionally HOLD (`HOLD_NOT_ISOLATED`, exit 10): there is no
verified isolated launcher (mock provider + separate store + owned port);
`Start-RAG`-style shared-owner launchers are never invoked by aw-dev.

## Adding a case

- Register the case in `tool-registry.json` with an `upstreamOwner`.
- If the delegated tool is absent, the case reports `PENDING_UPSTREAM` (12)
  and records the missing path — never fabricate a green.
- `remote-paid` and live provider calls are out of scope for this toolkit.
