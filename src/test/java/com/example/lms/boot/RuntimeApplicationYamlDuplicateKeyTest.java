package com.example.lms.boot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApplicationYamlDuplicateKeyTest {

    private static final List<Path> RESOURCE_ROOTS = List.of(
            Path.of("main/resources"),
            Path.of("app/src/main/resources")
    );
    private static final Set<String> ROOT_PACKAGED_OUT_APPLICATION_FILES = Set.of(
            "app/resources/application-local.yml",
            "application-example.yml",
            "application-features-example.yml",
            "application-merge16.yml",
            "application-patch.yml",
            "application-recency30.yml",
            "application.disabled.yml"
    );
    private static final Set<String> ROOT_PROFILE_OVERLAY_FILES = Set.of(
            "application-merge16.yml",
            "application-patch.yml",
            "application-recency30.yml"
    );

    @Test
    void allApplicationYamlFilesRejectDuplicateKeys() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(options);

        StringBuilder failures = new StringBuilder();
        for (Path file : applicationFiles(".yml", ".yaml")) {
            try (var reader = Files.newBufferedReader(file)) {
                yaml.load(reader);
            } catch (Exception ex) {
                if (isProcessedRuntimeResource(file)) {
                    failures.append(file).append(": ")
                            .append(ex.getClass().getSimpleName()).append(": ")
                            .append(ex.getMessage()).append('\n');
                }
            }
        }

        assertTrue(failures.isEmpty(), () -> "YAML duplicate key failures:\n" + failures);
    }

    @Test
    void rootProfileOverlayYamlFilesRejectDuplicateKeys() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(options);

        StringBuilder failures = new StringBuilder();
        for (String fileName : ROOT_PROFILE_OVERLAY_FILES) {
            Path file = Path.of("main/resources").resolve(fileName);
            if (Files.notExists(file)) {
                continue;
            }
            try (var reader = Files.newBufferedReader(file)) {
                yaml.load(reader);
            } catch (Exception ex) {
                failures.append(file).append(": ")
                        .append(ex.getClass().getSimpleName()).append(": ")
                        .append(ex.getMessage()).append('\n');
            }
        }

        assertTrue(failures.isEmpty(), () -> "Profile overlay YAML duplicate key failures:\n" + failures);
    }

    @Test
    void allApplicationPropertiesFilesRejectDuplicateKeys() throws IOException {
        StringBuilder failures = new StringBuilder();
        for (Path file : applicationFiles(".properties")) {
            List<String> duplicates = duplicatePropertiesKeys(file);
            if (!duplicates.isEmpty() && isProcessedRuntimeResource(file)) {
                failures.append(file).append(": duplicate keys ")
                        .append(duplicates)
                        .append('\n');
            }
        }

        assertTrue(failures.isEmpty(), () -> "Properties duplicate key failures:\n" + failures);
    }

    @Test
    void rootNonRuntimeApplicationProfilesAreNotProcessedAsRuntimeResources() {
        Path processed = buildDir(null).resolve("resources/main");

        for (String fileName : ROOT_PACKAGED_OUT_APPLICATION_FILES) {
            assertTrue(Files.notExists(processed.resolve(fileName)),
                    () -> fileName + " must not be packaged into root runtime resources");
        }
    }

    @Test
    void appApplicationResourcesArePackagedOutOfEmbeddedAppJar() throws IOException {
        Path appJar = newestAppJar();

        try (JarFile jar = new JarFile(appJar.toFile())) {
            List<String> applicationEntries = jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.matches("application.*\\.(yml|yaml|properties)"))
                    .toList();

            assertTrue(applicationEntries.isEmpty(),
                    () -> ":app jar must not shadow root application resources: " + applicationEntries);
        }
    }

    private static List<Path> applicationFiles(String... suffixes) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : RESOURCE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> isApplicationFile(path, suffixes))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static boolean isApplicationFile(Path path, String... suffixes) {
        String name = path.getFileName().toString();
        if (!name.startsWith("application")) {
            return false;
        }
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProcessedRuntimeResource(Path source) {
        Path normalized = source.normalize();
        Path mainRoot = Path.of("main/resources").normalize();
        if (normalized.startsWith(mainRoot)) {
            return Files.exists(buildDir(null).resolve("resources/main").resolve(mainRoot.relativize(normalized)));
        }
        Path appRoot = Path.of("app/src/main/resources").normalize();
        if (normalized.startsWith(appRoot)) {
            return Files.exists(buildDir("app").resolve("resources/main").resolve(appRoot.relativize(normalized)));
        }
        return false;
    }

    private static List<String> duplicatePropertiesKeys(Path file) throws IOException {
        Map<String, Integer> firstLineByKey = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        List<String> lines = Files.readAllLines(file);

        StringBuilder logical = new StringBuilder();
        int logicalStartLine = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (logical.length() == 0) {
                logicalStartLine = i + 1;
            }
            logical.append(line);
            if (continues(line)) {
                logical.setLength(logical.length() - 1);
                continue;
            }
            String key = propertyKey(logical.toString());
            if (key != null) {
                Integer firstLine = firstLineByKey.putIfAbsent(key, logicalStartLine);
                if (firstLine != null) {
                    duplicates.add(key + " firstLine=" + firstLine + " duplicateLine=" + logicalStartLine);
                }
            }
            logical.setLength(0);
        }
        return duplicates.stream().sorted().toList();
    }

    private static boolean continues(String line) {
        int slashCount = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    private static String propertyKey(String logicalLine) {
        String trimmed = logicalLine.stripLeading();
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        int end = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '=' || ch == ':' || Character.isWhitespace(ch)) {
                end = i;
                break;
            }
        }
        String key = end < 0 ? trimmed : trimmed.substring(0, end);
        key = key.trim();
        return key.isBlank() ? null : key;
    }

    private static Path newestAppJar() throws IOException {
        Path libs = buildDir("app").resolve("libs");
        assertTrue(Files.isDirectory(libs),
                () -> ":app libs directory must exist; Gradle test should build :app:jar first: " + libs);
        try (Stream<Path> jars = Files.list(libs)) {
            return jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orElseThrow(() -> new IOException("No :app jar found under " + libs));
        }
    }

    @Test
    void buildDirMatchesGradleSplitOutputMapping() {
        Path absoluteRoot = Path.of(System.getProperty("java.io.tmpdir"), "awx-build-root").toAbsolutePath();
        Path relativeRoot = Path.of("relative-awx-build");

        assertEquals(Path.of("app", "build"),
                buildDir("app", " 1 ", "node", absoluteRoot.toString()));
        assertEquals(Path.of("app", "build", "local"),
                buildDir("app", "1", null, null));
        assertEquals(Path.of("app", "build", "host"),
                buildDir("app", "1", " ", null));
        assertEquals(absoluteRoot.resolve("node-name").resolve("app"),
                buildDir("app", "1", "node name", absoluteRoot.toString()));
        assertEquals(absoluteRoot.resolve("node-name").resolve("root"),
                buildDir(null, "1", "node name", absoluteRoot.toString()));
        assertEquals(Path.of("app").resolve(relativeRoot).resolve("node").resolve("app"),
                buildDir("app", "1", "node", relativeRoot.toString()));
        assertEquals(relativeRoot.resolve("node").resolve("root"),
                buildDir(null, "1", "node", relativeRoot.toString()));
    }

    private static Path buildDir(String module) {
        return buildDir(
                module,
                System.getenv("AWX_SPLIT_BUILD_OUTPUTS"),
                System.getenv("AWX_BUILD_HOST_ID"),
                System.getenv("AWX_BUILD_ROOT_DIR"));
    }

    private static Path buildDir(String module, String splitValue, String hostValue, String buildRootValue) {
        Path base = module == null ? Path.of("build") : Path.of(module).resolve("build");
        if (!truthy(splitValue)) {
            return base;
        }

        String hostId = sanitizeHostId(hostValue == null ? "local" : hostValue);
        if (buildRootValue != null && !buildRootValue.isBlank()) {
            Path externalRoot = Path.of(buildRootValue.trim());
            if (!externalRoot.isAbsolute() && module != null && !module.isBlank()) {
                externalRoot = Path.of(module.replaceFirst("^:+", "").replace(':', '/')).resolve(externalRoot);
            }
            String projectSegment = module == null
                    ? "root"
                    : module.replaceFirst("^:+", "").replace(':', '-');
            if (projectSegment.isBlank()) {
                projectSegment = "root";
            }
            return externalRoot.resolve(hostId).resolve(projectSegment);
        }
        return base.resolve(hostId);
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on");
    }

    private static String sanitizeHostId(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        return sanitized.isBlank() ? "host" : sanitized;
    }
}
