# Build Pattern Run — 2025-10-17

**Input:** Spring Boot `bootRun` failure log (user-supplied)

**Detected patterns (using cfvm extractor):**
- `BeanNameConflict` — duplicated Spring `@Component` bean name **dynamicRetrievalHandlerChain** registered from two packages:
  - `service.rag.handler.DynamicRetrievalHandlerChain`
  - `com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain`

**Fix applied:**
- Disabled legacy wide **RagLightAdapterConfig** component scan by default and scoped to canonical packages under `com.example.lms.*`, behind the toggle:
  ```yaml
  adapter:
    rag-light:
      enabled: false
  ```
- Kept an explicit opt-in to re-enable when needed.

**Why this works:**
- Default `@SpringBootApplication` scan already covers `com.example.lms.*`. The extra scan of `service.*` pulled in a second copy of the handler class, causing `ConflictingBeanDefinitionException`.

**Follow-ups captured in pattern store:**
- Added `BEAN_NAME_CONFLICT` regex to `BuildLogSlotExtractor` so future runs will auto-classify similar failures and suggest the same remedy.

**Next expected outcome:**
- `bootRun` should pass configuration phase (config server warning remains but is non-fatal). If any further bean conflicts appear, the extractor will now categorize them under `BeanNameConflict`.
