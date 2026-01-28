# Patch Notes — src111_merge15 (bean conflict fix)

## Summary
- Fixed Spring context startup failure: `ConflictingBeanDefinitionException` for bean `dynamicRetrievalHandlerChain`.

## Changes
1) **Component Scan Scoping**
   - `src/main/java/com/example/lms/config/RagLightAdapterConfig.java`
   - Wrapped with `@ConditionalOnProperty(adapter.rag-light.enabled=false by default)`.
   - Narrowed `basePackages` to canonical `com.example.lms.*` namespaces.
   - Rationale: avoid pulling legacy `service.*` mirrors which duplicate handler classes.

2) **Config Toggle**
   - Added adapter toggle to:
     - `src/main/resources/application.yml`
     - `src/main/resources/application-features-example.yml`

3) **Error Pattern Store**
   - Extended `BuildLogSlotExtractor` to detect `BeanNameConflict` (regex on `ConflictingBeanDefinitionException`).
   - Output tag: `bean_name_conflict`, captures conflicting `bean` name.

## Rollback
- To revert behavior and re-enable legacy mirrors:
  - Set `adapter.rag-light.enabled=true` in your active profile YAML.
  - Be aware this may resurface bean name conflicts if mirror packages contain duplicate component names.

## Validation
- Expected boot sequence:
  - Hibernate Validator log
  - Spring Boot banner
  - (Optional) Config server warning if `http://localhost:8888` is offline — **non-fatal**.
  - **No ConflictingBeanDefinitionException**.
