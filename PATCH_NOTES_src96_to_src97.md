# Patch Notes: src96 → src97

**Issue fixed**: Application failed to start with
`IllegalStateException: Manifest not found: configs/models.manifest.yaml` in `ModelManifestConfig`.

**What changed**
1) Copied project-level manifest `src_91/configs/models.manifest.yaml` into the application classpath at:
   `src_91/src/main/resources/configs/models.manifest.yaml`.
2) Updated Spring config (`application.yml`) to pin the manifest location to the classpath and allow
   override via `AGENT_MODELS_PATH`.

**Why this works**
`ModelManifestConfig` falls back to loading the manifest from the classpath when a file-system path is not found.
Packaging the file under `src/main/resources/configs/` guarantees it will be on the runtime classpath,
regardless of the working directory used by Gradle's `bootRun` or by the packaged JAR.

**How to override**
- To supply an external file at runtime: set environment variable
  `AGENT_MODELS_PATH=C:/path/to/models.manifest.yaml` (Windows) or
  `AGENT_MODELS_PATH=/path/to/models.manifest.yaml` (Linux/macOS).
- Or customize `agent.models.path` in `application.yml`.

