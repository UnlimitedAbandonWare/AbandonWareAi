# Auto Patch Summary — src111_merge15

## Build error patterns (from repository memory):

- (?i)cannot\s+find\s+symbol
- (?i)package\s+[\w\.]+\s+does\s+not\s+exist
- (?i)duplicate\s+class
- (?i)incompatible\s+types
- (?i)compilation\s+failed

## Actions applied:

- rewrite_gradle → app/build.gradle.kts
- rewrite_gradle → lms-core/build.gradle.kts
- minimalize_broken_java → app/src/main/java/com/abandonware/ai/agent/consent/BasicConsentService.java
- minimalize_broken_java → app/src/main/java/com/abandonware/ai/agent/consent/ConsentService.java
- minimalize_broken_java → app/src/main/java/com/abandonware/ai/agent/web/RoomController.java
- minimalize_broken_java → app/src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java
- Regex escape normalization applied to 0 files under app/src/main/java.
- include_module_cfvm_raw → settings.gradle
- javax→jakarta import migration on 1 files.