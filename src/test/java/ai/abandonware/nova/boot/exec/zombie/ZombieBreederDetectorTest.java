package ai.abandonware.nova.boot.exec.zombie;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZombieBreederDetectorTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void contaminatedRequiresBlockedThreadsAndDynamicSpawnPressure() {
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setDetectionWindowMs(60_000);
        props.setBlockedThreadThreshold(4);
        props.setSpawnRateThreshold(8);
        AtomicInteger blocked = new AtomicInteger(5);
        DefaultZombieBreederDetector detector = new DefaultZombieBreederDetector(props, blocked::get, () -> 1_000L);
        String secret = "sk-" + "zombieOwnerSecret1234567890";
        String owner = "owner-token=" + secret;

        ContaminationVerdict verdict = detector.assess(owner, 8);
        detector.recordVerdict(verdict, owner);

        assertEquals(ContaminationVerdict.CONTAMINATED, verdict);
        assertEquals("CONTAMINATED", TraceStore.get("zombie.detector.verdict"));
        assertEquals(5, TraceStore.get("zombie.detector.blockedCount"));
        assertEquals(8L, ((Number) TraceStore.get("zombie.detector.spawnRateInWindow")).longValue());
        assertTrue(String.valueOf(TraceStore.get("zombie.detector.ownerHash")).startsWith("hash:"));
        Map<String, Object> trace = TraceStore.getAll();
        assertFalse(trace.containsKey("zombie.detector.owner"));
        assertFalse(String.valueOf(trace).contains(secret));
        assertFalse(String.valueOf(trace).contains("owner-token="));
    }

    @Test
    void singleThresholdBreachIsOnlySuspect() {
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setDetectionWindowMs(60_000);
        props.setBlockedThreadThreshold(4);
        props.setSpawnRateThreshold(8);
        DefaultZombieBreederDetector detector = new DefaultZombieBreederDetector(props, () -> 5, () -> 1_000L);

        assertEquals(ContaminationVerdict.SUSPECT, detector.assess("extremez", 3));
    }

    @Test
    void cleanWhenResourcePressureStaysBelowThresholds() {
        ZombieBreederProperties props = new ZombieBreederProperties();
        props.setBlockedThreadThreshold(4);
        props.setSpawnRateThreshold(8);
        DefaultZombieBreederDetector detector = new DefaultZombieBreederDetector(props, () -> 2, () -> 1_000L);

        assertEquals(ContaminationVerdict.CLEAN, detector.assess("extremez", 2));
    }
}
