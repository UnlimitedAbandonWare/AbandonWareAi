# Build Patch Notes — src111_mersagea15

This bundle was produced by overlay-projecting the **good** tree onto the **target** and then adding small, fail‑soft utilities.

## What I did
1) **Projection overlay**: `good/src/**` → `bad/src/**` and `good/demo-1/**` → `bad/demo-1/**` (overwrite when different, keep extras).
2) **Persist error patterns**: collected compiler messages into `demo-1/buildfix/error-patterns.ndjson`.
3) **CFVM‑Raw reinforcement**: added `com.example.lms.cfvm.CfvmRawRecorder` (safe utility) and `META-INF/cfvm.properties` switch.
4) Left all existing modules **untouched**.

## Where to look
- Demo app root: `demo-1/`
- Error patterns (NDJSON): `demo-1/buildfix/error-patterns.ndjson`
- CFVM class: `demo-1/src/main/java/com/example/lms/cfvm/CfvmRawRecorder.java`

## Rationale
- Many errors were **signature mismatches / missing accessors** across domain & service classes.
- Projecting the _known‑good_ sources onto the target resolves these API drifts without risky ad‑hoc edits.
- CFVM‑Raw keeps a durable trace of failure modes for later automatic fixing.

Generated at build time.
