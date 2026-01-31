# BUILD FIX NOTES — src111_msaerge15
Generated: 2025-10-25T07:47:28.314483Z

## Summary
- Fixed Gradle compilation failure caused by duplicate classes coming from `src/main/java/_abandonware_backup/**`.
- Implemented **build-time exclusion** for the backup folder across all Gradle modules.
- Emitted **duplicate-class scan report** at `tools/build_error_patterns/duplicates.json`.
- Added operator memo: `docs/jammini_memory__src111_merge15.md`.

## What changed
1) Appended `sourceSets` exclusion to these files:
   - build.gradle.kts
   - app/build.gradle.kts
   - cfvm-raw/build.gradle.kts
   - lms-core/build.gradle.kts
   - tools-scorecard/build.gradle.kts

2) Wrote duplicate map (first 500 items) to:
   - tools/build_error_patterns/duplicates.json

## Why this fixes it
`javac` resolves types by **package name + top-level symbol**. Your backup sources are under the same `package` declarations
(e.g., `package com.abandonware...`) as the live sources, so they produce **duplicate type definitions**.
Excluding `**/_abandonware_backup/**` from the Java and Resources source sets removes the conflicting files from the compiler classpath.

## Next steps (optional)
- If you actually need to compile backup code, change their packages to a distinct namespace (e.g., `package backup.com.abandonware...`) or move them outside `src/main/java`.
- To raise the compiler error cap locally, you can run:
  - `./gradlew compileJava --warning-mode all -Dorg.gradle.jvmargs="-Xmx2g" -Pmaxerrs=2000`
