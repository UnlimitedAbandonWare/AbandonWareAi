# src111_merge15 — patched (build fix for FindBugs annotations & Lombok)

This package was produced from: src111_merge15 - 2025-11-04T113809.154.zip

## What changed
- Unified prompt scaffold under `src/agent_scaffold/**`
- Patched Gradle build files to include:
  - `org.projectlombok:lombok:1.18.34` (compileOnly + annotationProcessor for main/test)
  - `com.google.code.findbugs:annotations:3.0.1` (compileOnly)
- Strengthened build-error miner:
  - `src/.internal/build_error_patterns/patterns.json` updated with rule `fix-missing-findbugs-annotations-and-lombok`
  - `src/scripts/build_error_mitigator.py` added
  - sample log saved at `src/.build/error_samples/2025-11-04T024501___build_error.txt`

## Usage (local)
```bash
# After a failed build generates build.log
python3 src/scripts/build_error_mitigator.py src/.internal/build_error_patterns/patterns.json build.log
```

## Notes
- The fix is idempotent and safe to re-run.
- Demo module `demo-1` gets a minimal Gradle if missing.
