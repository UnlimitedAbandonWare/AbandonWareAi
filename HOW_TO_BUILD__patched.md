# How to build (patched)

- Prereq: Java 21 (or a compatible toolchain) and Gradle wrapper.
- Command: `./gradlew :app:bootJar`
- Notes: Only `:app` is included. Other modules remain intact in the repo but are not built.
