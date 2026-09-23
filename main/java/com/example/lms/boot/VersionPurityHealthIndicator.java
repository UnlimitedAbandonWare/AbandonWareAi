// src/main/java/com/example/lms/boot/VersionPurityHealthIndicator.java
package com.example.lms.boot;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class VersionPurityHealthIndicator implements HealthIndicator {
    private static final System.Logger LOG = System.getLogger(VersionPurityHealthIndicator.class.getName());
    private static final String EXPECTED_VERSION = "1.0.1";
    private static final Pattern LANGCHAIN4J_JAR =
            Pattern.compile("^(langchain4j(?:-[A-Za-z0-9_.]+)*)-(\\d+\\.\\d+\\.\\d+(?:[-A-Za-z0-9.]+)?)\\.jar$");

    @Override
    public Health health() {
        String cp = System.getProperty("java.class.path", "");
        Set<String> versions = new TreeSet<>();
        List<String> artifacts = new ArrayList<>();

        for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String fileName;
            try {
                fileName = Path.of(entry).getFileName().toString();
            } catch (Exception ignore) {
                traceSuppressed("versionPurity.classpathEntry", ignore);
                fileName = entry;
            }
            Matcher matcher = LANGCHAIN4J_JAR.matcher(fileName);
            if (!matcher.matches()) {
                continue;
            }
            String artifact = matcher.group(1);
            String version = matcher.group(2);
            versions.add(version);
            artifacts.add(artifact + ":" + version);
        }

        boolean mismatch = versions.stream().anyMatch(v -> !EXPECTED_VERSION.equals(v));
        Health.Builder builder = mismatch ? Health.down() : Health.up();
        builder.withDetail("expectedLangChain4jVersion", EXPECTED_VERSION)
                .withDetail("langchain4jVersions", versions)
                .withDetail("langchain4jArtifacts", artifacts);
        if (mismatch) {
            builder.withDetail("langchain4jVersion", "MISMATCH");
            builder.withDetail("reason", "langchain4j-version-purity");
            TraceStore.put("llm.versionPurity", "FAIL");
            TraceStore.put("llm.versionPurity.mismatch", String.join(",", versions));
        } else {
            builder.withDetail("langchain4jVersion", "OK");
            TraceStore.put("llm.versionPurity", "PASS");
            TraceStore.put("llm.versionPurity.mismatch", "");
        }
        return builder.build();
    }

    private static void traceSuppressed(String stage, Exception failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("version.purity.suppressed.stage", safeStage);
        TraceStore.put("version.purity.suppressed.errorType", safeErrorType);
        TraceStore.put("version.purity.suppressed." + safeStage, true);
        TraceStore.put("version.purity.suppressed." + safeStage + ".errorType", safeErrorType);
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Version purity classpath fallback stage={0} errorType={1}",
                    safeStage,
                    safeErrorType);
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
