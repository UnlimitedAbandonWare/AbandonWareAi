# Patch Notes — Fix startup failure when image plugin disabled
**When:** 2025-10-21 21:50:29 UTC
**Module:** `com.example.lms.plugin.image.jobs.ImageJobService`
**Symptom:** Spring Boot fails at startup with:
```
UnsatisfiedDependencyException: required a bean of type 'com.example.lms.plugin.image.OpenAiImageService' that could not be found
```
**Root Cause:** `ImageJobService` was always created, but `OpenAiImageService` is behind `openai.image.enabled=true`. When disabled (or not configured), constructor injection fails.
**Fix:** Make `ImageJobService` conditional on presence of `OpenAiImageService` bean.
```diff
 @Service
+@ConditionalOnBean(com.example.lms.plugin.image.OpenAiImageService.class)
 @RequiredArgsConstructor
 public class ImageJobService Ellipsis
```
**Why safe:** Controller (`ImageGenerationPluginController`) and `OpenAiImageService` are already conditional on the same feature flag. Jobs should not exist when the feature is off.
**Ops note:** To enable the plugin later, set `openai.image.enabled=true` and provide API settings.
