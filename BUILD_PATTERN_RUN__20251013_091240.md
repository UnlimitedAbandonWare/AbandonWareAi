# Build Pattern Run — 2025-10-13 09:12:40 UTC+09:00

**Input:** Provided bootRun failure (ConflictingBeanDefinitionException for `selfAskPlanner`)

**Detected pattern(s):**
- **BeanNameConflict** — duplicate bean names from multiple `@Component` classes with the same simple class name.

**Detector:** `cfvm-raw/src/main/java/com/example/lms/cfvm/BuildLogSlotExtractor.java`

**Evidence:**
- `service.rag.planner.SelfAskPlanner` vs `com.example.lms.service.rag.SelfAskPlanner`
- Both annotated `@Component` ⇒ default bean name `selfAskPlanner`.

**Remediation applied:**
- Excluded legacy `service.rag.planner.SelfAskPlanner` from component scanning via `RagLightAdapterConfig` to remove the collision while keeping other `service.rag.*` beans available.

**Next actions (optional):**
- If any consumers rely on the legacy class as a bean, re-introduce it as `@Component("legacySelfAskPlanner")` and inject by `@Qualifier("legacySelfAskPlanner")`.
