package ai.abandonware.nova.boot.exec.zombie;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanPoolFactoryTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void cleanPoolSizeUsesCurrentInflightCpuAndConfiguredCap() {
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setMaxContainmentPoolSize(8);
        CleanPoolFactory factory = new CleanPoolFactory(props, () -> 16);

        assertEquals(1, factory.choosePoolSize(0));
        assertEquals(3, factory.choosePoolSize(3));
        assertEquals(8, factory.choosePoolSize(64));
    }

    @Test
    void lowCpuHostClampsCleanPoolBelowConfiguredCap() {
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setMaxContainmentPoolSize(8);
        CleanPoolFactory factory = new CleanPoolFactory(props, () -> 2);

        assertEquals(1, factory.choosePoolSize(8));
    }

    @Test
    void createCleanPoolRecordsOnlyHashedOwnerAndRunsCallable() throws Exception {
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setMaxContainmentPoolSize(4);
        props.setDrainTimeoutMs(500);
        CleanPoolFactory factory = new CleanPoolFactory(props, () -> 8);
        String secret = "sk-" + "zombiePoolSecret1234567890";

        CancelShieldExecutorService cleanPool = factory.createCleanPool(
                "owner-token=" + secret, 2);
        try {
            Future<String> future = cleanPool.submit((Callable<String>) () -> "ok");
            assertEquals("ok", future.get(1, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, TraceStore.get("zombie.cleanPool.created"));
            assertEquals(2, TraceStore.get("zombie.cleanPool.maxSize"));
            assertTrue(String.valueOf(TraceStore.get("zombie.cleanPool.poolTag")).startsWith("cleanPool:hash:"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("owner-token="));
        } finally {
            factory.retire(cleanPool, String.valueOf(TraceStore.get("zombie.cleanPool.poolTag")));
        }

        assertEquals(Boolean.TRUE, TraceStore.get("zombie.cleanPool.retired"));
        assertNotNull(TraceStore.get("zombie.cleanPool.drainedOk"));
    }

    @Test
    void zombiePackageDoesNotEncodeNineHourRuntimeBudgetOrInterruptiveCancel() throws Exception {
        Path root = Path.of("main/java/ai/abandonware/nova/boot/exec/zombie");
        StringBuilder source = new StringBuilder();
        if (Files.exists(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            try {
                                source.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
            }
        }

        String text = source.toString();
        assertFalse(text.contains("Duration.ofHours(9)"));
        assertFalse(text.contains("32400000"));
        assertFalse(text.contains("9-hour"));
        assertFalse(text.contains("9 hours"));
        assertFalse(text.contains("9시간"));
        assertFalse(text.contains("cancel(true)"));
        assertFalse(text.contains("shutdownNow("));
    }
}
