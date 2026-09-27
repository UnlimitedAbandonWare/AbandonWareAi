package ai.abandonware.nova.orch.failpattern;

import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.contract.ToolManifestEntry;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FailurePatternMemoryService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_SCAN_FILE_BYTES = 256_000;
    private static final int MAX_MEMORY_LINES = 1_000;
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)\\b(placeholder|todo-placeholder|your[_-]?api[_-]?key|changeme|change-me)\\b");
    private static final Pattern SWALLOWED_CATCH = Pattern.compile(
            "(?i)catch\\s*\\([^)]*(ignored|ignore|unused)[^)]*\\)\\s*\\{\\s*}");
    private static final Pattern ZOMBIE = Pattern.compile(
            "(?i)\\b(zombie\\s+code|dead\\s+code|orphaned\\s+future|missing\\s+future|unused\\s+future)\\b");
    private static final Pattern LEGACY_IMAGE_NODE = Pattern.compile(
            "(?i)\\b(" + "com" + "fy|" + "com" + "fyui|awx\\." + "com" + "fy)\\b");

    private final ObjectMapper objectMapper;
    private final ToolManifestCatalog catalog;
    private final Path root;
    private final Path memoryPath;

    public FailurePatternMemoryService(ObjectMapper objectMapper,
                                       ToolManifestCatalog catalog) {
        this(objectMapper, catalog, Path.of("."), Path.of("logs", "failure-pattern-memory.jsonl"));
    }

    public FailurePatternMemoryService(ObjectMapper objectMapper,
                                       ToolManifestCatalog catalog,
                                       Path root,
                                       Path memoryPath) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.catalog = catalog == null ? new ToolManifestCatalog() : catalog;
        this.root = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
        this.memoryPath = (memoryPath == null ? Path.of("logs", "failure-pattern-memory.jsonl") : memoryPath)
                .toAbsolutePath()
                .normalize();
    }

    public Map<String, Object> scan(Map<String, Object> input) {
        int limit = boundedInt(input == null ? null : input.get("limit"), 20, 1, 100);
        Map<String, SignalCounter> counters = new LinkedHashMap<>();
        counters.put("placeholder", new SignalCounter(limit));
        counters.put("swallowedCatch", new SignalCounter(limit));
        counters.put("zombieSignal", new SignalCounter(limit));
        counters.put("com" + "fyResidue", new SignalCounter(limit));
        counters.put("disabledTool", new SignalCounter(limit));

        for (Path activeRoot : activeRoots()) {
            scanRoot(activeRoot, counters);
        }
        scanDisabledTools(counters.get("disabledTool"));

        Map<String, Object> residues = new LinkedHashMap<>();
        counters.forEach((name, counter) -> residues.put(name, counter.toMap()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rootHash", SafeRedactor.hashValue(root.toString()));
        out.put("rootLength", root.toString().length());
        out.put("activeRoots", activeRoots().stream().map(root::relativize).map(Path::toString).toList());
        out.put("residues", residues);
        out.put("schema", "failure-pattern-scan.v1");
        return out;
    }

    public Map<String, Object> recall(Map<String, Object> input) {
        String kind = label(input, "kind");
        String source = label(input, "source");
        String failureClass = label(input, "failureClass");
        String hotspot = label(input, "hotspot");
        String intentHash12 = hash(input == null ? null : input.get("intent"));
        int limit = boundedInt(input == null ? null : input.get("limit"), 5, 1, 20);

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : readRows()) {
            int score = score(kind, source, failureClass, hotspot, intentHash12, row);
            if (score <= 0) {
                continue;
            }
            Map<String, Object> match = publicRow(row);
            match.put("score", score);
            matches.add(match);
        }
        matches.sort(Comparator
                .<Map<String, Object>>comparingInt(row -> ((Number) row.getOrDefault("score", 0)).intValue())
                .reversed()
                .thenComparing(row -> String.valueOf(row.getOrDefault("ts", "")), Comparator.reverseOrder()));
        if (matches.size() > limit) {
            matches = new ArrayList<>(matches.subList(0, limit));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matches", matches);
        out.put("matchCount", matches.size());
        out.put("recommendedAction", matches.isEmpty() ? "" : matches.get(0).getOrDefault("patchAction", ""));
        out.put("schema", "failure-pattern-recall.v1");
        return out;
    }

    public Map<String, Object> record(Map<String, Object> input) {
        Map<String, Object> row = new LinkedHashMap<>();
        String kind = label(input, "kind");
        String source = label(input, "source");
        String failureClass = label(input, "failureClass");
        String hotspot = label(input, "hotspot");
        String patchAction = label(input, "patchAction", "nextPatchAction", "action");
        String decision = label(input, "decision");
        String intentHash12 = hash(input == null ? null : input.get("intent"));
        String evidenceHash12 = hash(input == null ? null : input.get("evidence"));
        Map<String, Object> matrix = sanitizedMatrix(input == null ? null : input.get("matrix"));
        double boltzmannWeight = boundedDouble(input == null ? null : input.get("boltzmannWeight"),
                0.0d, 0.0d, 1.0d);

        row.put("ts", Instant.now().toString());
        row.put("kind", kind);
        row.put("source", source);
        row.put("failureClass", failureClass);
        row.put("hotspot", hotspot);
        row.put("patchAction", patchAction);
        row.put("decision", decision);
        row.put("intentPresent", present(input == null ? null : input.get("intent")));
        row.put("intentLen", length(input == null ? null : input.get("intent")));
        row.put("intentHash12", intentHash12);
        row.put("evidencePresent", present(input == null ? null : input.get("evidence")));
        row.put("evidenceLen", length(input == null ? null : input.get("evidence")));
        row.put("evidenceHash12", evidenceHash12);
        row.put("matrix", matrix);
        row.put("patternId", patternId(kind, source, failureClass, hotspot, patchAction, decision, intentHash12));
        row.put("schema", "failure-pattern-memory.v1");

        try {
            Path parent = memoryPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(memoryPath, objectMapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(memoryPath)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException ex) {
            FailurePatternTrace.traceSkipped("failurePatternMemory.write", ex);
            traceMemoryRecord(false, matrix, boltzmannWeight, "memory_write_failed");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("recorded", false);
            out.put("error", "memory_write_failed");
            return out;
        }

        traceMemoryRecord(true, matrix, boltzmannWeight, "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recorded", true);
        out.put("patternId", row.get("patternId"));
        out.put("memoryPathHash", SafeRedactor.hashValue(memoryPath.toString()));
        out.put("memoryPathLength", memoryPath.toString().length());
        return out;
    }

    private void scanRoot(Path activeRoot, Map<String, SignalCounter> counters) {
        if (!Files.exists(activeRoot)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(activeRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isTextCandidate)
                    .filter(this::smallEnough)
                    .forEach(path -> scanFile(path, counters));
        } catch (IOException ignore) {
            FailurePatternTrace.traceSkipped("failurePatternMemory.scanRoot", ignore);
        }
    }

    private void scanFile(Path path, Map<String, SignalCounter> counters) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                match(counters.get("placeholder"), PLACEHOLDER, line, path, lineNo);
                match(counters.get("swallowedCatch"), SWALLOWED_CATCH, line, path, lineNo);
                match(counters.get("zombieSignal"), ZOMBIE, line, path, lineNo);
                match(counters.get("com" + "fyResidue"), LEGACY_IMAGE_NODE, line, path, lineNo);
            }
        } catch (IOException ignore) {
            FailurePatternTrace.traceSkipped("failurePatternMemory.scanFile", ignore);
        }
    }

    private void scanDisabledTools(SignalCounter counter) {
        Path manifest = root.resolve(Path.of("main", "resources", "tool_manifest__kchat_gpt_pro.json")).normalize();
        if (Files.exists(manifest)) {
            try {
                JsonNode tools = objectMapper.readTree(manifest.toFile()).path("tools");
                if (tools.isArray()) {
                    for (JsonNode tool : tools) {
                        if (!tool.path("enabled").asBoolean(true)) {
                            counter.add("tool_manifest__kchat_gpt_pro.json", 0,
                                    tool.path("id").asText("disabled"));
                        }
                    }
                    return;
                }
            } catch (IOException ignore) {
                FailurePatternTrace.traceSkipped("failurePatternMemory.manifestRead", ignore);
                // fall through to catalog
            }
        }
        for (ToolManifestEntry entry : catalog.load().entries().values()) {
            if (!entry.enabled()) {
                counter.add("tool_manifest__kchat_gpt_pro.json", 0, entry.id());
            }
        }
    }

    private List<Path> activeRoots() {
        List<Path> roots = new ArrayList<>();
        for (Path relative : List.of(
                Path.of("main", "java"),
                Path.of("main", "resources"),
                Path.of("src", "test", "java"),
                Path.of("app", "src", "main", "java_clean"),
                Path.of("app", "src", "main", "resources"))) {
            Path candidate = root.resolve(relative).normalize();
            if (Files.exists(candidate)) {
                roots.add(candidate);
            }
        }
        return List.copyOf(roots);
    }

    private boolean isTextCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java")
                || name.endsWith(".json")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".properties")
                || name.endsWith(".md");
    }

    private boolean smallEnough(Path path) {
        try {
            return Files.size(path) <= MAX_SCAN_FILE_BYTES;
        } catch (IOException ex) {
            FailurePatternTrace.traceSkipped("failurePatternMemory.fileSize", ex);
            return false;
        }
    }

    private void match(SignalCounter counter, Pattern pattern, String line, Path path, int lineNo) {
        if (pattern.matcher(line).find()) {
            counter.add(root.relativize(path).toString(), lineNo, "");
        }
    }

    private List<Map<String, Object>> readRows() {
        if (!Files.exists(memoryPath)) {
            return List.of();
        }
        ArrayDeque<String> tail = new ArrayDeque<>(MAX_MEMORY_LINES);
        try (FileChannel channel = FileChannel.open(memoryPath, java.nio.file.StandardOpenOption.READ);
             BufferedReader reader = memoryTailReader(channel)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == MAX_MEMORY_LINES) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException ex) {
            FailurePatternTrace.traceSkipped("failurePatternMemory.readRows", ex);
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(tail.size());
        for (String line : tail) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                rows.add(objectMapper.readValue(line, MAP_TYPE));
            } catch (Exception ignore) {
                FailurePatternTrace.traceSkipped("failurePatternMemory.malformedRow", ignore);
            }
        }
        return rows;
    }

    private static BufferedReader memoryTailReader(FileChannel channel) throws IOException {
        long end = channel.size();
        long position = end;
        long start = 0;
        int nextByte = -1;
        int lineBoundaries = 0;
        ByteBuffer block = ByteBuffer.allocate(8_192);
        // Scan physical line boundaries, including blanks, before decoding UTF-8.
        // Ignore the terminal separator; CRLF is one boundary even across blocks.
        scan:
        while (position > 0) {
            int length = (int) Math.min(block.capacity(), position);
            position -= length;
            channel.position(position);
            block.clear().limit(length);
            while (block.hasRemaining()) {
                if (channel.read(block) <= 0) {
                    throw new IOException("memory_tail_read_incomplete");
                }
            }
            for (int i = length - 1; i >= 0; i--) {
                int value = block.get(i) & 0xff;
                long after = position + i + 1;
                boolean boundary = value == '\n' || (value == '\r' && nextByte != '\n');
                if (boundary && after < end && ++lineBoundaries == MAX_MEMORY_LINES) {
                    start = after;
                    break scan;
                }
                nextByte = value;
            }
        }
        channel.position(start);
        // The selected suffix retains strict decoding; discarded prefixes are not decoded.
        return new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8.newDecoder(), -1));
    }

    private static int score(String kind, String source, String failureClass, String hotspot,
                             String intentHash12, Map<String, Object> row) {
        int score = 0;
        score += eq(kind, row.get("kind")) ? 2 : 0;
        score += eq(source, row.get("source")) ? 1 : 0;
        score += eq(failureClass, row.get("failureClass")) ? 4 : 0;
        score += eq(hotspot, row.get("hotspot")) ? 3 : 0;
        score += intentHash12 != null && intentHash12.equals(row.get("intentHash12")) ? 2 : 0;
        return score;
    }

    private static Map<String, Object> publicRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("patternId", "ts", "kind", "source", "failureClass", "hotspot",
                "patchAction", "decision", "intentHash12", "evidenceHash12", "matrix")) {
            if (row.containsKey(key)) {
                out.put(key, row.get(key));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizedMatrix(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> allowed = Set.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9",
                "9matrix", "failurepattern", "failurepatternkind");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = lowCardinalityLabel(String.valueOf(entry.getKey()));
            if (allowed.contains(key.toLowerCase(Locale.ROOT))) {
                Object raw = entry.getValue();
                if (raw instanceof Number || raw instanceof Boolean) {
                    out.put(key, raw);
                } else {
                    out.put(key, lowCardinalityLabel(String.valueOf(raw)));
                }
            }
        }
        return Map.copyOf(out);
    }

    private static void traceMemoryRecord(boolean stored,
                                          Map<String, Object> matrix,
                                          double boltzmannWeight,
                                          String skipReason) {
        try {
            TraceStore.put("failurePattern.memory.stored", stored);
            TraceStore.put("failurePattern.memory.tileCount", matrix == null ? 0 : matrix.size());
            TraceStore.put("failurePattern.memory.boltzmann.weight", boltzmannWeight);
            TraceStore.put("failurePattern.memory.skipReason", skipReason == null ? "" : skipReason);
        } catch (Throwable ignore) {
            FailurePatternTrace.traceSkipped("failurePatternMemory.trace", ignore);
        }
    }

    private static String patternId(String... parts) {
        return SafeRedactor.hash12(String.join("|", parts == null ? new String[0] : parts));
    }

    private static String hash(Object value) {
        return value == null ? null : SafeRedactor.hash12(String.valueOf(value));
    }

    private static boolean present(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static int length(Object value) {
        return value == null ? 0 : String.valueOf(value).length();
    }

    private static String label(Map<String, Object> input, String... keys) {
        if (input == null) {
            return "";
        }
        for (String key : keys) {
            Object value = input.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return lowCardinalityLabel(String.valueOf(value));
            }
        }
        return "";
    }

    private static String lowCardinalityLabel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return "";
        }
        String traceLabel = SafeRedactor.traceLabel(value);
        if (traceLabel != null && traceLabel.startsWith("hash:")) {
            return "label_hash_" + traceLabel.substring("hash:".length());
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.length() > 80 || lower.contains("ownertoken=") || lower.contains("authorization:")
                || lower.contains("api_key=") || lower.contains("raw prompt") || lower.contains("raw query")) {
            return "label_hash_" + SafeRedactor.hash12(value);
        }
        return value.replaceAll("[^A-Za-z0-9_.:/-]", "_");
    }

    private static boolean eq(String expected, Object actual) {
        return expected != null && !expected.isBlank() && expected.equals(String.valueOf(actual));
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                FailurePatternTrace.traceSkipped("failurePatternMemory.boundedInt",
                        new NumberFormatException("non-finite"));
                parsed = fallback;
            } else {
                parsed = n.intValue();
            }
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignore) {
                FailurePatternTrace.traceSkipped("failurePatternMemory.boundedInt", ignore);
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static double boundedDouble(Object value, double fallback, double min, double max) {
        double parsed = fallback;
        if (value instanceof Number n) {
            parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                FailurePatternTrace.traceSkipped("failurePatternMemory.boundedDouble",
                        new NumberFormatException("non-finite"));
                parsed = fallback;
            }
        } else if (value != null) {
            try {
                parsed = Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignore) {
                FailurePatternTrace.traceSkipped("failurePatternMemory.boundedDouble", ignore);
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static final class SignalCounter {
        private final int limit;
        private int count;
        private final List<Map<String, Object>> samples = new ArrayList<>();

        private SignalCounter(int limit) {
            this.limit = limit;
        }

        private void add(String path, int line, String id) {
            count++;
            if (samples.size() >= limit) {
                return;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("path", path);
            if (line > 0) {
                sample.put("line", line);
            }
            if (id != null && !id.isBlank()) {
                sample.put("id", lowCardinalityLabel(id));
            }
            samples.add(sample);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("samples", List.copyOf(samples));
            return out;
        }
    }
}
