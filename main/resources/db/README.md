# DB schema notes

This project currently relies on **Hibernate DDL auto-update** (`spring.jpa.hibernate.ddl-auto`) in the default profile.

There is **no Flyway dependency** in the Gradle build by default, so `db/migration/` is intentionally left empty.

For production MySQL/MariaDB environments where you want explicit schema control, use the scripts under:

- `db/ddl/` (MySQL/MariaDB)

The patch file `db/ddl/V20251230__learning_efficiency.sql` contains:

- translation_memory index cleanup + composite indexes
- pending soak lease columns (`locked_at`, `locked_by`)
- translation_samples columns for learning loop (`trained_at`, `needs_review`)
