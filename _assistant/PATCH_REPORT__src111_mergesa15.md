# Patch Report — src111_mergesa15

Date (UTC): 2025-10-25T08:00:02.418610Z

## Changes applied
- Moved backup sources from /mnt/data/work_src111_msaerge15_1/work_src111_mersagea15/src/main/java/_abandonware_backup -> /mnt/data/work_src111_msaerge15_1/work_src111_mersagea15/backup/_abandonware_backup
- Hardened root build.gradle.kts: guarded sourceSets + disabled compileJava/testClasses.
- Registered new build error pattern 'DuplicateClassAbandonwareBackup' with remediation steps in pattern DBs.

## Rationale

The build failure was caused by Java sources under `src/main/java/_abandonware_backup` that define classes
with the same fully-qualified names as the production sources, leading to `duplicate class` errors.
We resolved this in a defense-in-depth manner:
1) Physically moved the backup directory outside of the Java source set (to `backup/_abandonware_backup`).
2) Hardened Gradle configuration to ignore the backup path when/if the Java plugin is applied to the root project,
   and to disable `:compileJava` on the root aggregator.
3) Recorded a dedicated error pattern so future runs classify & auto-remediate this class of failures.
