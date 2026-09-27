package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RootDotEnvInjectionTest {
    @TempDir Path directory;
    private static final String[] NAMES = {
        "ANTHROPIC_API_KEY", "BRAVE_API_KEY", "BRAVE_API_KEY_FREE", "DEEPGRAM_API_KEY",
        "GEMINI_API_KEY", "GROQ_API_KEY", "KAKAO_REST_API_KEY",
        "NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET", "NAVER_KEYS", "OPENAI_API_KEY", "OPENCODE_API_KEY",
        "PINECONE_API_KEY", "SERPAPI_API_KEY", "TAVILY_API_KEY", "UPSTASH_VECTOR_API_KEY", "ZAI_API_KEY"
    };

    @Test
    void everyMigratedNameLoadsAndResolvesWithoutChangingValue() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        StringBuilder dotenv = new StringBuilder();
        StringBuilder config = new StringBuilder("spring.config.import=optional:" + directory.resolve(".env").toUri() + "[.properties]\n");
        for (int i = 0; i < NAMES.length; i++) {
            String value = "synthetic_" + i + "-part:with/slash+plus=equals#hash";
            expected.put(NAMES[i], value);
            dotenv.append(NAMES[i]).append('=').append(value).append('\n');
            config.append("migration.values.key-").append(i).append("=${").append(NAMES[i]).append("}\n");
        }
        Files.writeString(directory.resolve(".env"), dotenv);
        Files.writeString(directory.resolve("application.properties"), config);
        runner(Map.of()).withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            assertNull(context.getStartupFailure());
            var bound = Binder.get(context.getEnvironment()).bind("migration.values", Bindable.mapOf(String.class, String.class)).get();
            for (int i = 0; i < NAMES.length; i++) {
                assertEquals(expected.get(NAMES[i]), context.getEnvironment().getProperty(NAMES[i]));
                assertEquals(expected.get(NAMES[i]), bound.get("key-" + i));
            }
        });
    }

    @Test
    void processEnvironmentWinsOverImportedFile() throws Exception {
        Files.writeString(directory.resolve(".env"), "OPENAI_API_KEY=synthetic-file\n");
        Files.writeString(directory.resolve("application.properties"),
                "spring.config.import=optional:" + directory.resolve(".env").toUri() + "[.properties]\nopenai.api.key=${OPENAI_API_KEY:}\n");
        runner(Map.of("OPENAI_API_KEY", "synthetic-process"))
                .withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("synthetic-process", context.getEnvironment().getProperty("openai.api.key"));
                });
    }

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner().withPropertyValues("spring.config.location=" + directory.toUri())
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove("systemEnvironment");
                    sources.remove("systemProperties");
                    sources.addFirst(new SystemEnvironmentPropertySource("syntheticEnvironment", environment));
                });
    }
}
