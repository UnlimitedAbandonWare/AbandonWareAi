package com.example.lms.infra.upstash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real ConfigData and @Value binding; only the external HTTP exchange is synthetic. */
class UpstashRedisConfigurationFocusedTest {
    @TempDir Path directory;
    private final AtomicInteger attempts = new AtomicInteger();

    private ApplicationContextRunner runner(String profiles, Map<String, Object> environment,
                                             String expectedUrl, String expectedToken) throws Exception {
        for (String file : List.of("application.yml", "application-deepgram-local.yml")) {
            var config = new StringBuilder();
            for (var source : new YamlPropertySourceLoader().load("canonical",
                    new FileSystemResource("main/resources/" + file))) {
                if (!config.isEmpty()) config.append("#---\n");
                for (String name : List.of("spring.profiles.default", "spring.config.activate.on-profile",
                        "spring.config.import")) {
                    Object value = source.getProperty(name);
                    if (value != null) config.append(name).append('=').append(value.toString()
                            .replace("file:.env[.properties]", directory.resolve(".env").toUri() + "[.properties]")
                            .replace("classpath:application-deepgram-local.yml",
                                    directory.resolve("local-import.properties").toUri().toString())).append('\n');
                }
            }
            Files.writeString(directory.resolve(file.equals("application.yml") ? "application.properties"
                    : "local-import.properties"), config);
        }
        return new ApplicationContextRunner().withUserConfiguration(UpstashRedisClient.class)
                .withBean(WebClient.Builder.class, () -> WebClient.builder().exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    assertEquals(expectedUrl + "/pipeline", request.url().toString());
                    assertEquals("Bearer " + expectedToken, request.headers().getFirst("Authorization"));
                    assertNull(request.url().getQuery());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json").body("[{\"result\":[1,0]}]").build());
                }))
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove("systemEnvironment");
                    sources.remove("systemProperties");
                    sources.addFirst(new SystemEnvironmentPropertySource("syntheticEnvironment", environment));
                }).withPropertyValues("spring.profiles.active=" + profiles,
                        "spring.config.location=" + directory.toUri())
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    private void fileCredentials() throws Exception {
        Files.writeString(directory.resolve(".env"),
                "UPSTASH_REDIS_REST_URL=https://file.example.invalid\nUPSTASH_REDIS_REST_TOKEN=synthetic-file-token\n");
    }

    @ParameterizedTest @ValueSource(strings = {"local", "dev"})
    void fileOnlyNamesReachTheActualRedisClient(String profile) throws Exception {
        fileCredentials();
        runner(profile, Map.of(), "https://file.example.invalid", "synthetic-file-token").run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getEnvironment().getProperty("UPSTASH_REDIS_REST_TOKEN"));
            var client = context.getBean(UpstashRedisClient.class);
            assertTrue(client.enabled(), "loaded file credentials must enable the Redis client");
            assertEquals(List.of(1L, 0L), client.eval("return {1,0}", List.of(), List.of()).block());
            assertEquals(1, attempts.get());
        });
    }

    @Test void processEnvironmentWinsOverLocalFile() throws Exception {
        fileCredentials();
        runner("dev", Map.of("UPSTASH_REDIS_REST_URL", "https://process.example.invalid",
                "UPSTASH_REDIS_REST_TOKEN", "synthetic-process-token"),
                "https://process.example.invalid", "synthetic-process-token").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(List.of(1L, 0L), context.getBean(UpstashRedisClient.class)
                    .eval("return {1,0}", List.of(), List.of()).block());
            assertEquals(1, attempts.get());
        });
    }

    @Test void explicitInternalPropertiesKeepTheirPrecedence() throws Exception {
        fileCredentials();
        runner("dev", Map.of(), "https://explicit.example.invalid", "synthetic-explicit-token")
                .withPropertyValues("upstash.redis.rest-url=https://explicit.example.invalid",
                        "upstash.redis.rest-token=synthetic-explicit-token").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(List.of(1L, 0L), context.getBean(UpstashRedisClient.class)
                            .eval("return {1,0}", List.of(), List.of()).block());
                    assertEquals(1, attempts.get());
                });
    }

    @ParameterizedTest @ValueSource(strings = {"prod", "production", "local,prod", "dev,production", "local,verification"})
    void deploymentProfilesDoNotGainLocalSecrets(String profile) throws Exception {
        fileCredentials();
        runner(profile, Map.of(), "unused", "unused").run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(UpstashRedisClient.class).enabled());
            assertNull(context.getEnvironment().getProperty("UPSTASH_REDIS_REST_TOKEN"));
            assertEquals(0, attempts.get());
        });
    }

    @Test void explicitImportOverrideStillDisablesFileLoading() throws Exception {
        fileCredentials();
        runner("dev", Map.of("APP_CONFIG_IMPORT", ""), "unused", "unused").run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(UpstashRedisClient.class).enabled());
            assertEquals(0, attempts.get());
        });
    }
}
