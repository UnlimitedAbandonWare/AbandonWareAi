Commit 1 — 중복 제거(필수)
DEL: app/src/main/java/com/abandonware/ai/agent/integrations/HybridRetriever.java

Commit 2 — 가드·폴백 선적용
ADD: src/main/java/com/example/lms/config/PasswordEncoderFallbackConfig.java
ADD: src/main/java/com/example/lms/config/VectorStoreFallbackConfig.java

Commit 3 — LLM 설정 통합
ADD: src/main/java/com/example/lms/config/LlmConfig.java
MOD: src/main/resources/application.yml (openai alias)

Commit 4 — 웹·보안·부트스트랩
ADD: src/main/java/com/example/lms/web/OwnerKeyBootstrapFilter.java
ADD: src/main/java/com/example/lms/api/SessionBootstrapController.java
ADD: src/main/java/com/example/lms/config/PkiValidationStaticConfig.java
ADD: src/main/java/com/example/lms/config/AcmeChallengeStaticConfig.java

Commit 5 — 리소스/마이그레이션
ADD: src/main/resources/db/migration/V99__index_owner_key.sql
ADD: src/main/resources/schema.sql
ADD: src/main/resources/data.sql

Commit 6 — 비밀 분리/프록시
MOD: src/main/resources/application.properties (secrets import, profile, PKI dir)
ADD: src/main/resources/application-example.yml
ADD: src/main/resources/application-local.yml
ADD: src/main/resources/application-proxy.properties
ADD: src/main/resources/bootstrap.properties
ADD: src/main/resources/keystore.p12.disabled

Commit 7 — Gradle 최소 패치
MOD: app/build.gradle.kts (Retrofit/OkHttp implementation 승격)

Commit 8 — 프롬프트 스캐폴드
ADD: agents/src83_port/system_ko.md
ADD: traits/ops_quality_ko.md
ADD: prompts.manifest.yaml
