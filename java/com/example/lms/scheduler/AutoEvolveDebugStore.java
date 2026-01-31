package com.example.lms.scheduler;

import com.example.lms.moe.RgbMoeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded in-memory ring-buffer for recent auto-evolve debug records.
 *
 * <p>This is intentionally simple: synchronized deque + AtomicReference for last.
 * It is used by internal endpoints (/internal/autoevolve/status, /history) to aid
 * debugging without scraping logs.</p>
 */
@Component
public class AutoEvolveDebugStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RgbMoeProperties props;
    private final ObjectMapper om;
    private final AtomicReference<AutoEvolveRunDebug> last = new AtomicReference<>();
    private final Deque<AutoEvolveRunDebug> ring = new ArrayDeque<>();
    private final Object lock = new Object();

    // persistence (optional)
    private final boolean persistEnabled;
    private final Path persistDir;
    private final String ndjsonFileName;
    private final String indexFileName;
    private final long ndjsonMaxBytes;
    private final int ndjsonMaxFiles;

    public AutoEvolveDebugStore(RgbMoeProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;

        RgbMoeProperties.Debug cfg = props == null ? null : props.getDebug();
        this.persistEnabled = cfg != null && cfg.isEnabled() && cfg.isPersistEnabled();
        this.persistDir = Path.of(cfg == null || cfg.getPersistDir() == null ? "./autoevolve_debug" : cfg.getPersistDir());
        this.ndjsonFileName = cfg == null || cfg.getNdjsonFileName() == null ? "autoevolve.ndjson" : cfg.getNdjsonFileName();
        this.indexFileName = cfg == null || cfg.getIndexFileName() == null ? "autoevolve_index.json" : cfg.getIndexFileName();
        this.ndjsonMaxBytes = cfg == null ? 5_000_000L : Math.max(0L, cfg.getNdjsonMaxBytes());
        this.ndjsonMaxFiles = cfg == null ? 10 : Math.max(1, cfg.getNdjsonMaxFiles());

        // Best-effort: load persisted history into memory on startup.
        loadFromDiskIfEnabled();
    }

    public void record(AutoEvolveRunDebug d) {
        if (d == null) return;
        RgbMoeProperties.Debug cfg = props == null ? null : props.getDebug();
        if (cfg != null && !cfg.isEnabled()) return;

        addToRing(d, cfg);
        persistIfEnabled(d);
    }

    public AutoEvolveRunDebug last() {
        return last.get();
    }

    /**
     * Returns most recent first.
     */
    public List<AutoEvolveRunDebug> recent(int limit) {
        int lim = Math.max(1, Math.min(limit, 200));
        synchronized (lock) {
            ArrayList<AutoEvolveRunDebug> out = new ArrayList<>(Math.min(lim, ring.size()));
            int i = 0;
            for (var it = ring.descendingIterator(); it.hasNext() && i < lim; i++) {
                out.add(it.next());
            }
            return out;
        }
    }

    /**
     * Lightweight view for status UIs (no heavy nested payloads required).
     *
     * <p>Newest-first.</p>
     */
    public List<AutoEvolveRunIndexEntry> recentIndex(int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        synchronized (lock) {
            ArrayList<AutoEvolveRunIndexEntry> out = new ArrayList<>(Math.min(lim, ring.size()));
            int i = 0;
            for (var it = ring.descendingIterator(); it.hasNext() && i < lim; i++) {
                AutoEvolveRunDebug d = it.next();
                out.add(AutoEvolveRunIndexEntry.from(d, ndjsonFileName));
            }
            return out;
        }
    }

    public boolean isPersistEnabled() {
        return persistEnabled;
    }

    public String persistDirectory() {
        return persistDir == null ? null : persistDir.toString();
    }

    public String ndjsonPath() {
        return persistDir == null ? null : persistDir.resolve(ndjsonFileName).toString();
    }

    public String indexPath() {
        return persistDir == null ? null : persistDir.resolve(indexFileName).toString();
    }

    public int size() {
        synchronized (lock) {
            return ring.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            ring.clear();
        }
        last.set(null);
    }

    // -------------------------- internal helpers --------------------------

    private void addToRing(AutoEvolveRunDebug d, RgbMoeProperties.Debug cfg) {
        last.set(d);
        int cap = 30;
        if (cfg != null) {
            cap = Math.max(1, cfg.getRingSize());
        }
        synchronized (lock) {
            ring.addLast(d);
            while (ring.size() > cap) {
                ring.removeFirst();
            }
        }
    }

    private void loadFromDiskIfEnabled() {
        if (!persistEnabled) return;
        if (om == null) return;

        try {
            Files.createDirectories(persistDir);
        } catch (Exception ignore) {
            return;
        }

        int cap = 30;
        RgbMoeProperties.Debug cfg = props == null ? null : props.getDebug();
        if (cfg != null) cap = Math.max(1, cfg.getRingSize());

        try {
            List<AutoEvolveRunDebug> loaded = loadRecentNdjson(cap);
            if (loaded.isEmpty()) return;
            for (AutoEvolveRunDebug d : loaded) {
                if (d == null) continue;
                // Avoid re-persisting the persisted records.
                addToRing(d, cfg);
            }
            // Refresh index file for convenience.
            writeIndexFile();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private void persistIfEnabled(AutoEvolveRunDebug d) {
        if (!persistEnabled) return;
        if (om == null) return;

        try {
            Files.createDirectories(persistDir);
        } catch (Exception e) {
            return;
        }

        try {
            Path ndjson = persistDir.resolve(ndjsonFileName);
            rotateNdjsonIfNeeded(ndjson);
            String line = om.writeValueAsString(d);
            Files.writeString(ndjson, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // best-effort
        }

        try {
            writeIndexFile();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private void rotateNdjsonIfNeeded(Path active) {
        if (ndjsonMaxBytes <= 0L) return;
        try {
            if (!Files.exists(active)) return;
            long size = Files.size(active);
            if (size < ndjsonMaxBytes) return;

            String base = stripExt(ndjsonFileName);
            String rotatedName = base + "-" + TS.format(LocalDateTime.now()) + ".ndjson";
            Path rotated = persistDir.resolve(rotatedName);
            Files.move(active, rotated, StandardCopyOption.REPLACE_EXISTING);
            cleanupOldRotations(base);
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private void cleanupOldRotations(String basePrefix) {
        try {
            if (!Files.isDirectory(persistDir)) return;

            List<Path> files = Files.list(persistDir)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(basePrefix + "-") && n.endsWith(".ndjson");
                    })
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .toList();

            if (files.size() <= ndjsonMaxFiles) return;
            for (int i = ndjsonMaxFiles; i < files.size(); i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (Exception ignore) {
                    // ignore
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private void writeIndexFile() {
        int cap = 30;
        RgbMoeProperties.Debug cfg = props == null ? null : props.getDebug();
        if (cfg != null) {
            cap = Math.max(1, cfg.getRingSize());
            if (cfg.getIndexMaxEntries() > 0) {
                cap = Math.max(1, cfg.getIndexMaxEntries());
            }
        }

        List<AutoEvolveRunIndexEntry> idx = recentIndex(cap);
        Path out = persistDir.resolve(indexFileName);
        try {
            om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), idx);
        } catch (Exception ignore) {
            // ignore
        }
    }

    private List<AutoEvolveRunDebug> loadRecentNdjson(int cap) {
        if (cap <= 0) return List.of();
        if (!Files.isDirectory(persistDir)) return List.of();

        List<Path> ndjsons;
        try {
            ndjsons = Files.list(persistDir)
                    .filter(p -> p.getFileName().toString().endsWith(".ndjson"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }

        Deque<AutoEvolveRunDebug> collected = new ArrayDeque<>();
        for (Path f : ndjsons) {
            int need = cap - collected.size();
            if (need <= 0) break;

            List<String> lines = tailLines(f, Math.max(need, 50));
            if (lines.isEmpty()) continue;

            ArrayList<AutoEvolveRunDebug> parsed = new ArrayList<>();
            for (String line : lines) {
                String t = line == null ? null : line.trim();
                if (t == null || t.isEmpty()) continue;
                try {
                    parsed.add(om.readValue(t, AutoEvolveRunDebug.class));
                } catch (Exception ignore) {
                    // ignore broken lines
                }
            }

            // Add in reverse to preserve chronological order across files.
            for (int i = parsed.size() - 1; i >= 0 && collected.size() < cap; i--) {
                AutoEvolveRunDebug d = parsed.get(i);
                if (d != null) {
                    collected.addFirst(d);
                }
            }
        }

        return new ArrayList<>(collected);
    }

    private static String stripExt(String name) {
        if (name == null) return "autoevolve";
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    /**
     * Read last N lines efficiently (best-effort). Falls back to full read when needed.
     */
    private static List<String> tailLines(Path file, int maxLines) {
        if (file == null || maxLines <= 0) return List.of();
        try {
            long fileSize = Files.size(file);
            if (fileSize <= 0) return List.of();

            int maxBytes = (int) Math.min(fileSize, 1_048_576L);
            byte[] buf = new byte[maxBytes];

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(fileSize - maxBytes);
                raf.readFully(buf);
            }

            String txt = new String(buf, StandardCharsets.UTF_8);
            String[] arr = txt.split("\\r?\\n");
            ArrayList<String> out = new ArrayList<>();
            for (int i = Math.max(0, arr.length - maxLines); i < arr.length; i++) {
                out.add(arr[i]);
            }
            return out;

        } catch (Exception e) {
            try {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                int from = Math.max(0, all.size() - maxLines);
                return all.subList(from, all.size());
            } catch (Exception ignore) {
                return List.of();
            }
        }
    }
}
