package com.example.lms.service.rag.learn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfvmBanditStoreDurabilityTest {

    @TempDir
    Path tempDir;

    @Test
    void shutdownFlushPersistsAnUpdateStillInsideTheRateLimit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path storePath = tempDir.resolve("bandit.json");
        CfvmKallocLearningProperties properties = enabledProperties(storePath);
        properties.setFlushIntervalMs(Long.MAX_VALUE);
        CfvmBanditStore store = new CfvmBanditStore(objectMapper, properties);

        store.update("cfvm9:t1", "WEB_HEAVY", 0.75d);
        assertFalse(Files.exists(storePath), "the rate limit should leave this update pending");

        store.flushOnShutdown();

        JsonNode persisted = objectMapper.readTree(storePath.toFile());
        assertEquals(1L, persisted.path("cfvm9:t1").path("arms").path("WEB_HEAVY").path("n").asLong());
        assertEquals(0.75d,
                persisted.path("cfvm9:t1").path("arms").path("WEB_HEAVY").path("rewardSum").asDouble());
    }

    @Test
    void persistenceUsesForcedSameDirectoryTemporaryFileAndAtomicReplacement() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java"));

        assertTrue(source.contains("Files.createTempFile(parent, temporaryPrefix(p), \".tmp\")"));
        assertTrue(source.contains("FileChannel.open("));
        assertTrue(source.contains("channel.force(true)"));
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertTrue(source.contains("catch (AtomicMoveNotSupportedException unsupported)"));
        assertTrue(source.contains("Files.deleteIfExists(temporary)"));
        assertFalse(source.contains("Files.write(p, json)"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MOVE_BEFORE", "FORCE_BEFORE"})
    @org.junit.jupiter.api.Timeout(30)
    void injectedWritePhaseFailurePreservesCanonicalStateAndRestarts(String phase) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("bandit.json").toAbsolutePath().normalize();
        CfvmKallocLearningProperties properties = enabledProperties(target);
        properties.setFlushIntervalMs(Long.MAX_VALUE);
        CfvmBanditStore store = new CfvmBanditStore(mapper, properties);
        store.update("cfvm9:t1", "WEB_HEAVY", 0.25d);
        store.update("cfvm9:t2", "VECTOR_HEAVY", -0.5d);
        store.flushOnShutdown();
        String oldFileHash = byteHash(Files.readAllBytes(target));
        String oldAggregateHash = aggregateHash(mapper, store);
        store.update("cfvm9:t1", "WEB_HEAVY", 0.75d);
        String pendingAggregateHash = aggregateHash(mapper, store);
        assertEquals(oldFileHash, byteHash(Files.readAllBytes(target)), "rate limit must leave the new update pending");
        org.junit.jupiter.api.Assertions.assertNotEquals(oldAggregateHash, pendingAggregateHash);
        var injectedFaults = new java.util.concurrent.atomic.AtomicInteger();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var forces = new java.util.concurrent.atomic.AtomicInteger();
        var moves = new java.util.concurrent.atomic.AtomicInteger();
        var openedChannels = new java.util.ArrayList<java.nio.channels.FileChannel>();
        try (var fileCalls = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
                    if ("move".equals(invocation.getMethod().getName())
                            && target.equals(invocation.getArgument(1))) {
                        moves.incrementAndGet();
                        if ("MOVE_BEFORE".equals(phase)) {
                            injectedFaults.incrementAndGet();
                            throw new java.io.IOException("synthetic-move-before");
                        }
                    }
                    return invocation.callRealMethod();
                });
                var channelCalls = org.mockito.Mockito.mockStatic(java.nio.channels.FileChannel.class, invocation -> {
                    if ("open".equals(invocation.getMethod().getName())
                            && invocation.getMethod().getParameterCount() == 2
                            && invocation.getArgument(0) instanceof Path opened
                            && target.getParent().equals(opened.getParent())
                            && opened.getFileName().toString().startsWith(".bandit.json.")) {
                        var real = (java.nio.channels.FileChannel) invocation.callRealMethod();
                        openedChannels.add(real);
                        org.mockito.stubbing.Answer<Object> delegate = org.mockito.AdditionalAnswers.delegatesTo(real);
                        return org.mockito.Mockito.mock(java.nio.channels.FileChannel.class, channelInvocation -> {
                            String method = channelInvocation.getMethod().getName();
                            if ("write".equals(method) && channelInvocation.getArguments().length == 1) writes.incrementAndGet();
                            if ("force".equals(method)) {
                                forces.incrementAndGet();
                                if ("FORCE_BEFORE".equals(phase)) {
                                    injectedFaults.incrementAndGet();
                                    throw new java.io.IOException("synthetic-force-before");
                                }
                            }
                            return delegate.answer(channelInvocation);
                        });
                    }
                    return invocation.callRealMethod();
                })) {
            store.flushOnShutdown();
        } finally {
            for (var channel : openedChannels) if (channel.isOpen()) channel.close();
        }
        assertEquals(1, injectedFaults.get(), "the named actual writer phase must have been intercepted");
        assertTrue(writes.get() > 0, "the real channel must receive bytes before this injected phase");
        assertEquals(1, forces.get());
        assertEquals("MOVE_BEFORE".equals(phase) ? 1 : 0, moves.get());
        assertEquals(oldFileHash, byteHash(Files.readAllBytes(target)), "failed promotion must preserve canonical bytes");
        try (var files = Files.list(target.getParent())) {
            assertEquals(0L, files.filter(file -> file.getFileName().toString().endsWith(".tmp")).count());
        }
        CfvmBanditStore recovered = new CfvmBanditStore(mapper, properties);
        recovered.init();
        assertEquals(oldAggregateHash, aggregateHash(mapper, recovered));
        store.flushOnShutdown();
        CfvmBanditStore recoveredPending = new CfvmBanditStore(mapper, properties);
        recoveredPending.init();
        assertEquals(pendingAggregateHash, aggregateHash(mapper, recoveredPending),
                "successful shutdown before the rate-limit interval must recover the pending update");
        System.out.println("STKG10_PHASE_COUNTS phase=" + phase + " injectedFaults=" + injectedFaults.get()
                + " writes=" + writes.get() + " forces=" + forces.get() + " moves=" + moves.get()
                + " oldAggregateHash=" + oldAggregateHash + " pendingAggregateHash=" + pendingAggregateHash
                + " oldRecovered=true pendingRecovered=true");
    }

    enum CrashPhase {
        PARENT_CREATE_BEFORE, PARENT_CREATE_AFTER, TEMP_CREATE_BEFORE, TEMP_CREATE_AFTER,
        CHANNEL_OPEN_BEFORE, CHANNEL_OPEN_AFTER, WRITE_BEFORE, WRITE_PARTIAL, WRITE_AFTER,
        FORCE_BEFORE, FORCE_AFTER, CLOSE_BEFORE, CLOSE_AFTER,
        ATOMIC_MOVE_BEFORE, ATOMIC_MOVE_AFTER, FALLBACK_MOVE_BEFORE, FALLBACK_MOVE_AFTER,
        TEMP_CLEANUP_BEFORE, TEMP_CLEANUP_AFTER, NONE
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CrashPhase.class)
    @org.junit.jupiter.api.Timeout(90)
    void abruptChildJvmExitAtWritePhaseRestartsWithCompleteAggregate(CrashPhase phase) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path target = tempDir.resolve("bandit.json").toAbsolutePath().normalize();
        var properties = enabledProperties(target);
        properties.setFlushIntervalMs(Long.MAX_VALUE);
        var initial = new CfvmBanditStore(mapper, properties);
        initial.update("cfvm9:t1", "WEB_HEAVY", 0.25d);
        initial.update("cfvm9:t2", "VECTOR_HEAVY", -0.5d);
        initial.flushOnShutdown();
        String oldAggregate = aggregateHash(mapper, initial);
        String oldBytes = byteHash(Files.readAllBytes(target));
        String writerOutput = runChildProbe("write", target, phase, phase == CrashPhase.NONE ? 0 : 73);
        String pendingAggregate = markerHash(writerOutput, "STKG10_PENDING aggregateHash=");
        assertTrue(writerOutput.contains("STKG10_RATE_LIMIT pending=true"));
        if (phase != CrashPhase.NONE) assertTrue(writerOutput.contains("STKG10_PHASE_HALT phase=" + phase));
        if (phase == CrashPhase.WRITE_PARTIAL) assertTrue(writerOutput.contains("STKG10_PARTIAL_WRITE observed=true"));
        String restartOutput = runChildProbe("read", target, phase, 0);
        String recoveredAggregate = markerHash(restartOutput, "STKG10_RESTART aggregateHash=");
        boolean promoted = phase == CrashPhase.ATOMIC_MOVE_AFTER || phase == CrashPhase.FALLBACK_MOVE_AFTER
                || phase == CrashPhase.NONE;
        assertEquals(promoted ? pendingAggregate : oldAggregate, recoveredAggregate);
        if (!promoted) assertEquals(oldBytes, byteHash(Files.readAllBytes(target)));
        long orphanCount;
        try (var files = Files.list(target.getParent())) {
            orphanCount = files.filter(file -> file.getFileName().toString().startsWith(".bandit.json.")
                    && file.getFileName().toString().endsWith(".tmp")).count();
        }
        boolean noOrphan = promoted || phase == CrashPhase.PARENT_CREATE_BEFORE
                || phase == CrashPhase.PARENT_CREATE_AFTER || phase == CrashPhase.TEMP_CREATE_BEFORE
                || phase == CrashPhase.TEMP_CLEANUP_AFTER;
        assertEquals(noOrphan ? 0L : 1L, orphanCount, "abrupt exit must bypass normal cleanup at the selected phase");
        System.out.println("STKG10_PROCESS_COUNTS phase=" + phase + " writerExit=" + (phase == CrashPhase.NONE ? 0 : 73)
                + " restartExit=0 aggregate=" + (promoted ? "pending" : "old") + " orphanFiles=" + orphanCount
                + " recoveredAggregateHash=" + recoveredAggregate + " expectedAggregateHash="
                + (promoted ? pendingAggregate : oldAggregate));
    }

    private String runChildProbe(String mode, Path target, CrashPhase phase, int expectedExit) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        String classPath = java.util.Arrays.stream(System.getProperty("java.class.path")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(entry -> Path.of(entry).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" "));
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH, classPath);
        Path classPathJar = Files.createTempFile(tempDir, "cfvm-probe-classpath-", ".jar");
        try (var ignored = new java.util.jar.JarOutputStream(Files.newOutputStream(classPathJar), manifest)) {
            // Reuse the repository's manifest-classpath pattern to bound Windows command length.
        }
        Path childTemp = Files.createDirectories(tempDir.resolve("child-temp"));
        Path output = tempDir.resolve(phase + "-" + mode + ".log");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Djava.io.tmpdir=" + childTemp,
                "-cp", classPathJar.toString(), ProcessProbe.class.getName(), mode, target.toString(), phase.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "owned child probe timed out");
            assertEquals(expectedExit, process.exitValue(), "child phase=" + phase + " mode=" + mode);
            return Files.readString(output);
        } finally {
            if (process.isAlive()) {
                var descendants = process.descendants().toList();
                descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "owned child must terminate");
            }
            Files.deleteIfExists(classPathJar);
        }
    }

    private static String markerHash(String output, String prefix) {
        var values = output.lines().filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length())).toList();
        assertEquals(1, values.size(), "one child hash marker is required");
        assertTrue(values.get(0).matches("[a-f0-9]{64}"));
        return values.get(0);
    }

    public static final class ProcessProbe {
        public static void main(String[] args) throws Exception {
            if (args.length != 3) throw new IllegalArgumentException("expected mode, owned target and phase");
            Path target = Path.of(args[1]).toAbsolutePath().normalize();
            CrashPhase phase = CrashPhase.valueOf(args[2]);
            ObjectMapper mapper = new ObjectMapper();
            var properties = enabledProperties(target);
            properties.setFlushIntervalMs(Long.MAX_VALUE);
            var store = new CfvmBanditStore(mapper, properties);
            store.init();
            if ("read".equals(args[0])) {
                System.out.println("STKG10_RESTART aggregateHash=" + aggregateHash(mapper, store));
                return;
            }
            if (!"write".equals(args[0])) throw new IllegalArgumentException("invalid probe mode");
            String before = byteHash(Files.readAllBytes(target));
            store.update("cfvm9:t1", "WEB_HEAVY", 0.75d);
            if (!before.equals(byteHash(Files.readAllBytes(target)))) throw new IllegalStateException("update escaped rate limit");
            System.out.println("STKG10_RATE_LIMIT pending=true");
            System.out.println("STKG10_PENDING aggregateHash=" + aggregateHash(mapper, store));
            System.out.flush();
            try (var fileCalls = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
                        String method = invocation.getMethod().getName();
                        if ("createDirectories".equals(method) && target.getParent().equals(invocation.getArgument(0))) {
                            crashAt(phase, CrashPhase.PARENT_CREATE_BEFORE);
                            Object result = invocation.callRealMethod();
                            crashAt(phase, CrashPhase.PARENT_CREATE_AFTER);
                            return result;
                        }
                        if ("createTempFile".equals(method) && target.getParent().equals(invocation.getArgument(0))) {
                            crashAt(phase, CrashPhase.TEMP_CREATE_BEFORE);
                            Object result = invocation.callRealMethod();
                            crashAt(phase, CrashPhase.TEMP_CREATE_AFTER);
                            return result;
                        }
                        if ("move".equals(method) && target.equals(invocation.getArgument(1))) {
                            boolean atomic = java.util.Arrays.asList(invocation.getArguments())
                                    .contains(java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            if (phase == CrashPhase.TEMP_CLEANUP_BEFORE || phase == CrashPhase.TEMP_CLEANUP_AFTER)
                                throw new java.io.IOException("synthetic-cleanup-entry");
                            if (atomic && (phase == CrashPhase.FALLBACK_MOVE_BEFORE || phase == CrashPhase.FALLBACK_MOVE_AFTER))
                                throw new java.nio.file.AtomicMoveNotSupportedException("synthetic-temp", "synthetic-target", "force fallback branch");
                            crashAt(phase, atomic ? CrashPhase.ATOMIC_MOVE_BEFORE : CrashPhase.FALLBACK_MOVE_BEFORE);
                            Object result = invocation.callRealMethod();
                            crashAt(phase, atomic ? CrashPhase.ATOMIC_MOVE_AFTER : CrashPhase.FALLBACK_MOVE_AFTER);
                            return result;
                        }
                        if ("deleteIfExists".equals(method) && invocation.getArgument(0) instanceof Path path
                                && target.getParent().equals(path.getParent()) && path.getFileName().toString().startsWith(".bandit.json.")) {
                            crashAt(phase, CrashPhase.TEMP_CLEANUP_BEFORE);
                            Object result = invocation.callRealMethod();
                            crashAt(phase, CrashPhase.TEMP_CLEANUP_AFTER);
                            return result;
                        }
                        return invocation.callRealMethod();
                    });
                    var channels = org.mockito.Mockito.mockStatic(java.nio.channels.FileChannel.class, invocation -> {
                        if ("open".equals(invocation.getMethod().getName()) && invocation.getMethod().getParameterCount() == 2
                                && invocation.getArgument(0) instanceof Path path && target.getParent().equals(path.getParent())
                                && path.getFileName().toString().startsWith(".bandit.json.")) {
                            crashAt(phase, CrashPhase.CHANNEL_OPEN_BEFORE);
                            var real = (java.nio.channels.FileChannel) invocation.callRealMethod();
                            crashAt(phase, CrashPhase.CHANNEL_OPEN_AFTER);
                            org.mockito.stubbing.Answer<Object> delegate = org.mockito.AdditionalAnswers.delegatesTo(real);
                            return org.mockito.Mockito.mock(java.nio.channels.FileChannel.class, call -> {
                                String method = call.getMethod().getName();
                                if ("write".equals(method) && call.getArguments().length == 1
                                        && call.getArgument(0) instanceof java.nio.ByteBuffer buffer) {
                                    crashAt(phase, CrashPhase.WRITE_BEFORE);
                                    if (phase == CrashPhase.WRITE_PARTIAL) {
                                        int limit = buffer.limit();
                                        buffer.limit(buffer.position() + Math.max(1, buffer.remaining() / 2));
                                        int written = (Integer) delegate.answer(call);
                                        buffer.limit(limit);
                                        if (written <= 0 || !buffer.hasRemaining()) throw new IllegalStateException("partial write not observed");
                                        System.out.println("STKG10_PARTIAL_WRITE observed=true");
                                        crashAt(phase, CrashPhase.WRITE_PARTIAL);
                                    }
                                    Object result = delegate.answer(call);
                                    crashAt(phase, CrashPhase.WRITE_AFTER);
                                    return result;
                                }
                                if ("force".equals(method)) {
                                    crashAt(phase, CrashPhase.FORCE_BEFORE);
                                    Object result = delegate.answer(call);
                                    crashAt(phase, CrashPhase.FORCE_AFTER);
                                    return result;
                                }
                                if ("close".equals(method)) {
                                    crashAt(phase, CrashPhase.CLOSE_BEFORE);
                                    Object result = delegate.answer(call);
                                    crashAt(phase, CrashPhase.CLOSE_AFTER);
                                    return result;
                                }
                                return delegate.answer(call);
                            });
                        }
                        return invocation.callRealMethod();
                    })) {
                store.flushOnShutdown();
            }
            if (phase != CrashPhase.NONE) throw new IllegalStateException("requested crash phase was not reached");
        }

        private static void crashAt(CrashPhase selected, CrashPhase current) {
            if (selected != current) return;
            System.out.println("STKG10_PHASE_HALT phase=" + current);
            System.out.flush();
            Runtime.getRuntime().halt(73); // This entry point is launched only in the task-owned child JVM.
            throw new AssertionError("halt returned");
        }
    }

    private static String aggregateHash(ObjectMapper mapper, CfvmBanditStore store) throws Exception {
        return byteHash(mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(store.snapshot()));
    }

    private static String byteHash(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static CfvmKallocLearningProperties enabledProperties(Path storePath) {
        CfvmKallocLearningProperties properties = new CfvmKallocLearningProperties();
        properties.setEnabled(true);
        properties.setStorePath(storePath.toString());
        return properties;
    }
}
