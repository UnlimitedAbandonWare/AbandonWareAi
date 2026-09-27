package com.example.lms.llm.spec;

import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelSpecRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void failedPersistPreservesThePreviousCompleteFile() throws Exception {
        TraceStore.clear();
        LlmGatewayProperties props = new LlmGatewayProperties();
        Path path = tempDir.resolve("model-spec.json");
        props.getSpecRegistry().setPath(path.toString());
        String previous = "{\"prior\":true}";
        Files.writeString(path, previous);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        ObjectWriter writer = mock(ObjectWriter.class);
        when(objectMapper.writerWithDefaultPrettyPrinter()).thenReturn(writer);
        doAnswer(invocation -> {
            File destination = invocation.getArgument(0);
            Files.writeString(
                    destination.toPath(),
                    "{",
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            throw new IOException("forced partial write");
        }).when(writer).writeValue(any(File.class), any());
        ModelSpecRegistry registry = new ModelSpecRegistry(objectMapper, props);

        registry.publish(snapshot("partial-write-model"));

        assertEquals(previous, Files.readString(path));
        try (var files = Files.list(tempDir)) {
            assertEquals(1L, files.count(), "failed publication must remove its owned temporary file");
        }
        TraceStore.clear();
    }

    @Test
    void concurrentPublishersCannotLetAnOlderSnapshotOverwriteANewerOne() throws Exception {
        LlmGatewayProperties props = new LlmGatewayProperties();
        Path path = tempDir.resolve("concurrent-model-spec.json");
        props.getSpecRegistry().setPath(path.toString());
        ObjectMapper realMapper = new ObjectMapper().findAndRegisterModules();
        ObjectMapper controlledMapper = mock(ObjectMapper.class);
        ObjectWriter controlledWriter = mock(ObjectWriter.class);
        when(controlledMapper.writerWithDefaultPrettyPrinter()).thenReturn(controlledWriter);
        AtomicInteger writerEntries = new AtomicInteger();
        CountDownLatch firstWriterEntered = new CountDownLatch(1);
        CountDownLatch secondWriterEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWriter = new CountDownLatch(1);
        doAnswer(invocation -> {
            int entry = writerEntries.incrementAndGet();
            if (entry == 1) {
                firstWriterEntered.countDown();
                if (!releaseFirstWriter.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("first writer release timed out");
                }
            } else if (entry == 2) {
                secondWriterEntered.countDown();
            }
            realMapper.writerWithDefaultPrettyPrinter().writeValue(
                    invocation.<File>getArgument(0),
                    invocation.getArgument(1));
            return null;
        }).when(controlledWriter).writeValue(any(File.class), any());
        ModelSpecRegistry registry = new ModelSpecRegistry(controlledMapper, props);
        Thread older = new Thread(
                () -> registry.publish(snapshot("older-model")),
                "model-spec-older-writer");
        Thread newer = new Thread(
                () -> registry.publish(snapshot("newer-model")),
                "model-spec-newer-writer");
        boolean reachedControlledOrdering = false;

        try {
            older.start();
            assertTrue(firstWriterEntered.await(5, TimeUnit.SECONDS));
            newer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (secondWriterEntered.getCount() == 0L || newer.getState() == Thread.State.BLOCKED) {
                    reachedControlledOrdering = true;
                    break;
                }
                Thread.onSpinWait();
            }
        } finally {
            releaseFirstWriter.countDown();
            older.join(TimeUnit.SECONDS.toMillis(5));
            newer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertTrue(reachedControlledOrdering, "the second publication must reach the controlled persistence boundary");
        assertFalse(older.isAlive());
        assertFalse(newer.isAlive());
        String persisted = Files.readString(path);
        assertTrue(persisted.contains("older-model"));
        assertTrue(persisted.contains("newer-model"));
    }

    @Test
    void storesRedactedSnapshotsWithoutSecretMetadata() throws Exception {
        TraceStore.clear();
        LlmGatewayProperties props = new LlmGatewayProperties();
        Path path = tempDir.resolve("model-spec.json");
        props.getSpecRegistry().setPath(path.toString());
        ModelSpecRegistry registry = new ModelSpecRegistry(new ObjectMapper().findAndRegisterModules(), props);

        registry.publish(ModelSpecSnapshot.of(
                "ollama",
                "qwen3-embedding:4b",
                "localhost",
                8192,
                2560,
                List.of("embedding"),
                Map.of("apiKey", "secret-value", "source", "ollama_show")));

        assertTrue(registry.snapshot("ollama", "qwen3-embedding:4b").isPresent());
        assertEquals(2560, registry.snapshot("ollama", "qwen3-embedding:4b").orElseThrow().embeddingDim());
        assertEquals(SafeRedactor.hashValue("qwen3-embedding:4b"), TraceStore.get("llm.gateway.spec.modelHash"));
        assertEquals("qwen3-embedding:4b".length(), TraceStore.get("llm.gateway.spec.modelLength"));
        assertEquals(null, TraceStore.get("llm.gateway.spec.model"));
        String json = Files.readString(path);
        assertFalse(json.contains("secret-value"));
        assertTrue(json.contains("qwen3-embedding:4b"));
        TraceStore.clear();
    }

    @Test
    void persistFailureUsesStableTraceLabel() {
        TraceStore.clear();
        LlmGatewayProperties props = new LlmGatewayProperties();
        props.getSpecRegistry().setPath("bad" + '\0' + "path");
        ModelSpecRegistry registry = new ModelSpecRegistry(new ObjectMapper(), props);

        registry.publish(ModelSpecSnapshot.of(
                "ollama",
                "qwen3-embedding:4b",
                "localhost",
                8192,
                2560,
                List.of("embedding"),
                Map.of("source", "ollama_show")));

        assertEquals("llm_gateway_spec_persist_failed", TraceStore.get("llm.gateway.spec.persistFailure"));
        assertFalse(String.valueOf(TraceStore.get("llm.gateway.spec.persistFailure"))
                .contains("InvalidPathException"));
        TraceStore.clear();
    }

    @Test
    void modelSpecRegistryDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/spec/ModelSpecRegistry.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "ModelSpecRegistry fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
    }

    @Test
    void modelSpecTelemetryCatchesUseDirectSafeFallbackLogs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/llm/spec/ModelSpecRegistry.java"));

        assertTrue(source.contains("Model spec registry telemetry skipped stage=publish_trace"));
        assertTrue(source.contains("Model spec registry telemetry skipped stage=persist_failure_trace"));
        assertTrue(source.contains("errorType=\" + errorType(traceError)"));
    }

    private static ModelSpecSnapshot snapshot(String model) {
        return ModelSpecSnapshot.of(
                "ollama",
                model,
                "localhost",
                8192,
                2560,
                List.of("embedding"),
                Map.of("source", "test"));
    }
}
