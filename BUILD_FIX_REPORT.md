# BUILD FIX REPORT — src111_merge15

Date: 2025-10-12T22:30:31.495902Z

Detected common error patterns from in-repo scan:

- PACKAGE_NOT_FOUND: `org.apache.lucene.analysis.ko.*`, `com.networknt.schema.*`

- MISSING_SYMBOL: `NoriTokenizer`, `JsonSchema`, `PlanLoader`


Applied dependency injections:

- app/build.gradle.kts: +Redis, +Lettuce, +Commons-Pool2, +NetworkNT JSON Schema, +Jackson YAML, +project deps
- lms-core/build.gradle.kts: +Lucene core/common/nori, +Jackson databind/yaml
- cfvm-raw/build.gradle.kts: +Jackson databind/yaml

Notes:
- These are compile-time fixes only. Runtime properties (Redis/Kafka endpoints etc.) remain feature-flagged.
