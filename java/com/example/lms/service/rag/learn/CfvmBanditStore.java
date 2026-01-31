package com.example.lms.service.rag.learn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CFVM 9-tile 별 (arm -> reward stats) 를 누적 저장하는 간단한 밴딧 상태 저장소.
 *
 * <p>목표:
 * <ul>
 *   <li>서버 재시작 후에도 학습 상태가 이어지도록 "작고 단순한" 영속화</li>
 *   <li>fail-soft: 파일 I/O 실패가 런타임을 깨지 않음</li>
 *   <li>high QPS에서도 I/O 폭발을 막기 위한 flush rate-limit</li>
 * </ul>
 */
@Component
public class CfvmBanditStore {

    private static final Logger log = LoggerFactory.getLogger(CfvmBanditStore.class);

    private final ObjectMapper om;
    private final CfvmKallocLearningProperties props;

    private final ConcurrentHashMap<String, TileStats> tiles = new ConcurrentHashMap<>();

    private final Object ioLock = new Object();
    private final AtomicLong lastFlushMs = new AtomicLong(0L);
    private volatile boolean loaded = false;

    public CfvmBanditStore(ObjectMapper om, CfvmKallocLearningProperties props) {
        this.om = om;
        this.props = props;
    }

    @PostConstruct
    void init() {
        loadBestEffort();
    }

    public TileStats tile(String key) {
        if (key == null || key.isBlank()) {
            key = "cfvm9:t?";
        }
        return tiles.computeIfAbsent(key, k -> new TileStats());
    }

    public ArmStats arm(String tileKey, String armName) {
        if (armName == null || armName.isBlank()) {
            armName = "BASE";
        }
        TileStats t = tile(tileKey);
        return t.arms.computeIfAbsent(armName, k -> new ArmStats());
    }

    public void update(String tileKey, String armName, double reward) {
        try {
            ArmStats st = arm(tileKey, armName);
            st.add(reward);
            maybeFlush();
        } catch (Exception ignored) {
            // fail-soft
        }
    }

    public Map<String, TileStats> snapshot() {
        return Map.copyOf(tiles);
    }

    /**
     * Flush는 rate-limit 한다.
     * - flushIntervalMs 보다 빠르면 skip
     * - 경쟁상황에서 한 스레드만 flush
     */
    public void maybeFlush() {
        long interval = Math.max(1_000L, props.getFlushIntervalMs());
        long now = System.currentTimeMillis();
        long prev = lastFlushMs.get();
        if (now - prev < interval) {
            return;
        }
        if (!lastFlushMs.compareAndSet(prev, now)) {
            return;
        }
        flushBestEffort();
    }

    // === persistence ===

    private Path storePath() {
        String p = props.getStorePath();
        if (p == null || p.isBlank()) {
            p = "cfvm-raw/records/kalloc_bandit.json";
        }
        try {
            return Path.of(p);
        } catch (Exception e) {
            return Path.of("cfvm-raw/records/kalloc_bandit.json");
        }
    }

    private void loadBestEffort() {
        if (loaded) {
            return;
        }
        synchronized (ioLock) {
            if (loaded) {
                return;
            }
            Path p = storePath();
            if (!Files.exists(p)) {
                loaded = true;
                return;
            }
            try {
                byte[] raw = Files.readAllBytes(p);
                if (raw.length == 0) {
                    loaded = true;
                    return;
                }
                Map<String, TileStats> m = om.readValue(raw, new TypeReference<>() {});
                if (m != null) {
                    // normalize maps to concurrent
                    for (Map.Entry<String, TileStats> e : m.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        TileStats t = e.getValue();
                        if (t.arms == null) {
                            t.arms = new ConcurrentHashMap<>();
                        } else if (!(t.arms instanceof ConcurrentHashMap)) {
                            t.arms = new ConcurrentHashMap<>(t.arms);
                        }
                        tiles.put(e.getKey(), t);
                    }
                }
                loaded = true;
                log.info("[CFVM] bandit store loaded: {} tiles from {}", tiles.size(), p);
            } catch (Exception e) {
                loaded = true;
                log.warn("[CFVM] bandit store load failed-soft: {}", e.toString());
            }
        }
    }

    private void flushBestEffort() {
        if (!props.isEnabled()) {
            return;
        }
        synchronized (ioLock) {
            try {
                Path p = storePath();
                Path parent = p.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] json = om.writerWithDefaultPrettyPrinter().writeValueAsBytes(tiles);
                Files.write(p, json);
            } catch (IOException e) {
                log.warn("[CFVM] bandit store flush failed-soft: {}", e.toString());
            } catch (Exception e) {
                log.warn("[CFVM] bandit store flush failed-soft: {}", e.toString());
            }
        }
    }

    // === DTOs ===

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TileStats {
        public Map<String, ArmStats> arms = new ConcurrentHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArmStats {
        public long n = 0L;
        public double rewardSum = 0.0;
        public double rewardSqSum = 0.0;
        public long lastUpdatedMs = 0L;

        public synchronized void add(double reward) {
            n += 1;
            rewardSum += reward;
            rewardSqSum += (reward * reward);
            lastUpdatedMs = System.currentTimeMillis();
        }

        public synchronized double mean() {
            return n <= 0 ? 0.0 : rewardSum / (double) n;
        }

        public synchronized double variance() {
            if (n <= 1) {
                return 0.0;
            }
            double mean = rewardSum / (double) n;
            double ex2 = rewardSqSum / (double) n;
            double v = ex2 - (mean * mean);
            return Math.max(0.0, v);
        }
    }
}
