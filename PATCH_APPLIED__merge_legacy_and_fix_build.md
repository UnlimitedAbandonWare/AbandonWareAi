Patched at 2025-10-16T22:15:26.634015Z

# Build Error Pattern Summary (auto)
- Duplicate YAML key `retrieval:` in `app/src/main/resources/application.yml` → **fixed** by collapsing to a single block.
- Missing Gradle module include for `tools-scorecard` despite sources in `tools-scorecard/src/...` → **fixed** by adding include + module build file.
- 'gradlew-real' missing message in BUILD_LOG.txt is environment-specific and **not a source/build script issue**.
