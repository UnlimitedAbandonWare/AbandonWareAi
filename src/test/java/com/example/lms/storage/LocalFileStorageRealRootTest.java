package com.example.lms.storage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalFileStorageRealRootTest {

    @TempDir
    Path tempDir;

    @Test
    void saveRejectsExistingLinkThatEscapesRealRootWithoutWritingOutside() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("uploads"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        createLinkOrSkip(root.resolve("escape"), outside);
        LocalFileStorageService service = service(root);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> service.save(file("proof.txt"), "escape"));

        assertTrue(rejected.getMessage().contains("허용되지 않은 업로드 경로"));
        try (var paths = Files.list(outside)) {
            assertEquals(0L, paths.count());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteRejectsIssuedPathThroughLinkAndPreservesOutsideSentinel() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("delete-root"));
        Path outside = Files.createDirectories(tempDir.resolve("delete-outside"));
        Path sentinel = outside.resolve("sentinel.txt");
        Files.writeString(sentinel, "outside-proof");
        createLinkOrSkip(root.resolve("escape"), outside);
        LocalFileStorageService service = service(root);
        Set<String> issued = (Set<String>) ReflectionTestUtils.getField(service, "issuedStoredPaths");
        assertTrue(issued.add("/uploads/escape/sentinel.txt"));

        assertFalse(service.delete("/uploads/escape/sentinel.txt"));

        assertEquals("outside-proof", Files.readString(sentinel));
    }

    @Test
    void ordinaryNestedSaveStaysInsideConfiguredRealRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("ordinary-root"));
        LocalFileStorageService service = service(root);

        String stored = service.save(file("proof.txt"), "chat/nested");

        assertTrue(stored.startsWith("/uploads/chat/nested/"));
        String relative = stored.substring("/uploads/".length());
        assertTrue(Files.isRegularFile(root.resolve(relative)));
    }

    @Test
    void saveRejectsWindowsJunctionThatResolvesOutsideRealRoot() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "Windows junction fixture only");
        Path root = Files.createDirectories(tempDir.resolve("junction-root"));
        Path outside = Files.createDirectories(tempDir.resolve("junction-outside"));
        Path junction = root.resolve("escape");
        createJunctionOrSkip(junction, outside);
        try {
            LocalFileStorageService service = service(root);

            assertThrows(IllegalArgumentException.class, () -> service.save(file("proof.txt"), "escape"));
            try (var paths = Files.list(outside)) {
                assertEquals(0L, paths.count());
            }
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void saveAndDeleteLogsNeverExposeConfiguredRootName() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("raw-root-name-attachment-secret"));
        LocalFileStorageService service = service(root);
        Logger logger = (Logger) LoggerFactory.getLogger(LocalFileStorageService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String stored = service.save(file("proof.txt"), "chat");
            assertTrue(service.delete(stored));

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(logs.contains(root.getFileName().toString()));
            assertTrue(logs.contains("rootHash="));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void deleteRuntimeFailureLogsOnlyHashedPathAndErrorEvidence() {
        String rootMarker = "raw-delete-root-marker";
        String pathMarker = "raw-delete-path-marker.txt";
        String storedPath = "/uploads/" + pathMarker;
        LocalFileStorageService service = service(tempDir.resolve("unused-root"));
        ReflectionTestUtils.setField(service, "rootDir", Character.toString(0) + rootMarker);
        Logger logger = (Logger) LoggerFactory.getLogger(LocalFileStorageService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(service.delete(storedPath));

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(logs.contains("파일 삭제 실패."));
            assertTrue(logs.contains("pathHash="));
            assertTrue(logs.contains("pathLength=" + storedPath.length()));
            assertTrue(logs.contains("errorHash="));
            assertTrue(logs.contains("errorLength="));
            assertFalse(logs.contains(rootMarker));
            assertFalse(logs.contains(pathMarker));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static LocalFileStorageService service(Path root) {
        LocalFileStorageService service = new LocalFileStorageService();
        ReflectionTestUtils.setField(service, "rootDir", root.toString());
        ReflectionTestUtils.setField(service, "maxBytes", 1_048_576L);
        return service;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "text/plain", new byte[] {1, 2, 3});
    }

    private static void createLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic-link fixture unavailable on this host");
        }
    }

    private static void createJunctionOrSkip(Path link, Path target) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder(
                    "cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException | SecurityException unavailable) {
            assumeTrue(false, "junction fixture unavailable on this host");
            return;
        }
        boolean completed;
        try {
            completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            process.getInputStream().readAllBytes();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        assumeTrue(completed
                        && process.exitValue() == 0
                        && Files.exists(link, LinkOption.NOFOLLOW_LINKS),
                "junction fixture unavailable on this host");
    }
}
