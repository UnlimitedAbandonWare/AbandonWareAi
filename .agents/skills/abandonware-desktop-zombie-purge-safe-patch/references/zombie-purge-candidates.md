# Zombie Purge Candidate Reference

This reference defines safe candidate families for AbandonWareX/demo-1 Desktop zombie-code cleanup. It is not a delete list. Every candidate still needs live sourceSet, usage, and verification proof.

## Candidate Families

### Deprecated alias

A deprecated alias is a class that exists only to preserve an old package or symbol while the active implementation lives elsewhere.

Evidence required:

- Candidate has `@Deprecated`, alias wording, bridge wording, or compatibility wording.
- Candidate delegates directly to a canonical active class, or has no active references.
- Candidate is not registered as a Spring bean, auto-configuration import, service loader entry, controller, aspect, or runtime guard.
- Deleting it does not remove the only public API used by tests, prompts, scripts, or generated config.

Common review zones:

- `main/java/com/abandonware/ai/service/rag/**` aliases for active `com.example.lms.service.rag/**`.
- `main/java/com/example/lms/extreme/**` and `main/java/com/example/lms/nova/extremez/**` aliases for active ExtremeZ burst handling.
- `app/src/main/java_clean/**` legacy stubs that shadow root `main/java` classes.

### Dead package

A dead package is a package tree with no active compile/runtime references after excluding archives, generated output, and the candidate files themselves.

Evidence required:

- All files are in an active source root or are explicitly scoped as reference-only cleanup.
- No imports, FQCN string references, Spring metadata, resource references, or reflection hints point to the package.
- No Gradle task, script, prompt pack, or MCP/control-tower contract expects the package.
- Deletion preserves duplicate-FQCN count at zero.

### Shadow implementation

A shadow implementation has the same simple class name as a canonical active implementation but lives in another package or root.

Evidence required:

- Compare by package plus FQCN, never by simple name alone.
- The shadow has no imports/usages outside itself.
- The canonical implementation is present and verified in the active root.
- The shadow is not a Spring component, aspect, configuration, serializer, deserializer, converter, or public API type.

## Non-Candidates Without Stronger Proof

Do not delete these just because they look duplicated:

- `main/java/com/example/lms/prompt/PromptBuilder.java`
- `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`
- `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`
- `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java`
- `main/java/com/nova/protocol/fusion/CvarAggregator.java`
- `main/java/com/nova/protocol/fusion/NovaNextFusionService.java`
- `main/java/com/example/lms/cfvm/**`
- `main/java/com/example/lms/service/guard/PIISanitizer.java`
- `main/java/com/example/lms/guard/PiiSanitizer.java`
- `HybridWebSearchProvider`, `NaverSearchService`, `BraveSearchService`, `SerpApiProvider`, `NightmareBreaker`, `QueryTransformer`, `TraceStore`, and `DebugEventStore`.

## Candidate Manifest Formats

Markdown or text:

```md
- `main/java/com/example/lms/legacy/OldAlias.java`
- main/java/com/example/lms/legacy/UnusedShadow.java
```

JSON:

```json
[
  "main/java/com/example/lms/legacy/OldAlias.java",
  {"path": "main/java/com/example/lms/legacy/UnusedShadow.java", "reason": "no active references"}
]
```

## Minimum Verification Ladder

Use the narrowest useful proof first:

1. `python <skill>\scripts\zombie_candidate_audit.py --root . --candidates candidates.md --format markdown`
2. `python <skill>\scripts\zombie_candidate_audit.py --root . --candidates candidates.md --format json`
3. YAML duplicate-key scan if resource files changed.
4. Duplicate-FQCN scan on active roots.
5. `__patch_drop__\janitor_tests.ps1 -Suite CoreGuards` when PatchDrop/SMB safety is involved.
6. `.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir <desktop-cache>`
7. `.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir <desktop-cache>`
8. `.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir <desktop-cache>`
9. `.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir <desktop-cache>`

## Failure Classes

Use one primary class:

- `wrong-sourceset`
- `duplicate-class-fqcn`
- `cannot-find-symbol`
- `spring-bean`
- `prompt-rule-violation`
- `secret-leak-risk`
- `patch-drop-pending`
- `smb-conflict-risk`
- `index-lock-conflict`
- `gradle-cache-collision`
- `langchain4j-version-purity`
- `other`
