<!-- moved-from: AGENTS.md L258-L261 sha256=1e39634e2368d907565a197acee594a64fa624880394f4024dd962a7eab62cc9 movedAt=2026-10-06T07:15:00+00:00 -->
# Evidence And Verification — moved detail (AGENTS.md stub 목적지)

`## Evidence And Verification`(AGENTS.md 필수 헤딩) 본문에서 예산 복구로 이관된
규칙 전문이다. 헤딩과 lane-local blocker bullet은 AGENTS.md에 그대로 남아 있다.

- Existing repo files and real command output beat prompt assumptions; official vendor docs beat memory for external API/CLI/library behavior. If evidence is insufficient, record `evidence_needed: <artifact> / verify with <command>` instead of inventing files, routes, keys, or results.
- Report PASS only for the subset actually run (name suites + counts). A full `:test` run with failures is reported as counts plus per-failure classification — `pre-existing` requires a same-failure preimage/baseline run as evidence; never blanket-declare suite failures "all pre-existing", and never widen a scoped pass into whole-suite health.
- With `AWX_SPLIT_BUILD_OUTPUTS=1`/`AWX_BUILD_HOST_ID=desktop`, use `build\desktop\...` for boot proof; broad-test `NoClassDefFoundError` storms with classes present -> `scripts\verify_full_test_refresh.ps1`. Topology: `scripts\verify_control_plane_topology.ps1`. Do not parallelize `bootRun` smokes on the same host/cache dir.
