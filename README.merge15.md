    # src111_merge15 — merged src (clean)

    This snapshot was produced by an automated merge of:
    - /mnt/data/src111_merge15 - 2025-11-04T105854.859.zip
- /mnt/data/src111_merge15 - 2025-11-04T055947.888.zip

    ## What we kept (strengths)
    - Modular layout: `lms-core/**`, `app/**`, `agent_scaffold/**`, `docs/**`, `tools/**`, `.github/**`, `.internal/**`
    - Prompt scaffold promoted to top-level: `src/agent_scaffold/**` (resources-based scaffold migrated)
    - CI/auto-fix assets and build logic retained

    ## What we removed (weaknesses)
    - Legacy nested tree: `src/src/**`
    - Backups & temp: `**/backup/**`, `**/__backup*__/**`, `**/java_clean/**`, `**/build/**`, `**/out/**`, `**/tmp/**`
    - Nonstandard roots at `src/com/**`
    - Editor/OS artifacts

    ## Gradle SourceSet example
    ```kotlin
    sourceSets {
      main {
        java.setSrcDirs(listOf("lms-core/src/main/java", "app/src/main/java"))
      }
      test {
        java.setSrcDirs(listOf("lms-core/src/test/java", "app/src/test/java"))
      }
    }
    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
    }
    ```
