package com.example.lms.service.guard;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorDenylistRegistryCapacityTest {

    @Test
    void retainsAtMostTheEffectiveMaximumAfterEachUniqueBan() {
        VectorDenylistRegistry registry = new VectorDenylistRegistry();
        ReflectionTestUtils.setField(registry, "ttlMinutes", 60L);
        ReflectionTestUtils.setField(registry, "maxSize", 100);

        IntStream.rangeClosed(0, 100)
                .forEach(index -> registry.ban("embedding-" + index, "capacity-test"));

        assertEquals(100, registry.size());
        assertEquals(100, registry.snapshotReasons().size());
        assertTrue(registry.isBanned("embedding-100"));
    }

    @Test
    void addingAtNinetyNineRetainsEveryOriginalAndTheNewEntry() {
        VectorDenylistRegistry registry = registryWithEffectiveMaximum();
        IntStream.range(0, 99)
                .forEach(index -> registry.ban("original-" + index, "capacity-test"));

        registry.ban("new-entry", "capacity-test");

        assertEquals(100, registry.size());
        assertTrue(registry.isBanned("new-entry"));
        IntStream.range(0, 99)
                .forEach(index -> assertTrue(registry.isBanned("original-" + index)));
    }

    @Test
    void rebanningAtCapacityUpdatesTheEntryWithoutEvictingAnother() {
        VectorDenylistRegistry registry = registryWithEffectiveMaximum();
        IntStream.range(0, 100)
                .forEach(index -> registry.ban("embedding-" + index, "original"));
        Map<String, String> before = registry.snapshotReasons();

        registry.ban("embedding-50", "updated");

        Map<String, String> after = registry.snapshotReasons();
        assertEquals(100, registry.size());
        assertEquals(before.keySet(), after.keySet());
        assertEquals("updated", after.get("embedding-50"));
    }

    @Test
    void eachCompletedBanRetainsItsNewEntryAtCapacity() {
        IntStream.range(0, 256).forEach(candidate -> {
            VectorDenylistRegistry registry = registryWithEffectiveMaximum();
            IntStream.range(0, 100)
                    .forEach(index -> registry.ban("original-" + index, "original"));
            String newId = "candidate-" + candidate;

            registry.ban(newId, "new");

            assertEquals(100, registry.size());
            assertTrue(registry.isBanned(newId), () -> "new ban was pruned: " + newId);
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void expiredCleanupRestoresCapacityWithoutEvictingAnotherFreshEntry() throws Exception {
        VectorDenylistRegistry registry = registryWithEffectiveMaximum();
        IntStream.range(0, 99)
                .forEach(index -> registry.ban("fresh-" + index, "fresh"));
        Map entries = (Map) ReflectionTestUtils.getField(registry, "entries");
        Class<?> entryType = Class.forName(VectorDenylistRegistry.class.getName() + "$Entry");
        Constructor<?> constructor = entryType.getDeclaredConstructor(long.class, String.class);
        constructor.setAccessible(true);
        entries.put("expired", constructor.newInstance(System.currentTimeMillis() - 1L, "expired"));

        registry.ban("new-entry", "new");

        assertEquals(100, registry.size());
        assertTrue(registry.isBanned("new-entry"));
        assertFalse(entries.containsKey("expired"));
        IntStream.range(0, 99)
                .forEach(index -> assertTrue(registry.isBanned("fresh-" + index)));
    }

    private static VectorDenylistRegistry registryWithEffectiveMaximum() {
        VectorDenylistRegistry registry = new VectorDenylistRegistry();
        ReflectionTestUtils.setField(registry, "ttlMinutes", 60L);
        ReflectionTestUtils.setField(registry, "maxSize", 100);
        return registry;
    }
}
