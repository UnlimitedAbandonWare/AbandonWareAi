# PATCH — probe/retrieval keys in models.manifest.yaml → build fail fix

Date: 2025-10-27 20:29:39Z

**Symptom**  
`bootRun` fails at startup:
```
ConstructorException: Cannot create property=probe for JavaBean=com.example.lms.manifest.ModelsManifest
Unable to find property 'probe' on class: com.example.lms.manifest.ModelsManifest
```

**Root cause**  
`configs/models.manifest.yaml` (and variants) contained operational keys:
- `probe.search.enabled`, `probe.admin-token`
- `retrieval.vector.enabled`

The `ModelManifestConfig` loads this YAML **via SnakeYAML** into `ModelsManifest`, which had **no matching fields** → constructor fails and Spring context aborts.

**What changed**
- Java: `ModelsManifest` now includes defensive fields
  - `Map<String,Object> probe`, `Map<String,Object> retrieval` + getters/setters.
- YAML: Commented out the above keys in the *models.manifest.yaml* files.  
  They should live in **application.yml** where `ProbeProperties` (`@ConfigurationProperties(prefix="probe")`) already binds them.

**Where**
- `configs/models.manifest.yaml, src/main/resources/configs/models.manifest.yaml, app/src/main/resources/configs/models.manifest.yaml`

**Why this is safe**
- `application.yml` already holds the correct defaults:
  - `probe.search.enabled:false`, `probe.admin-token:''`
  - `retrieval.vector.enabled:true`
- Router/manifest stays purely about model declarations (bindings/aliases/models), avoiding future mapping drift.

**Next**
- If you ever need `probe` inside the manifest for tooling, keep it under a separate namespace (e.g., `tools:`) and extend the manifest bean accordingly.
