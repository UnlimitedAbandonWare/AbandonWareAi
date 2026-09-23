
package com.abandonware.ai.agent.integrations;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;



/**
 * Very small BM25 indexer and searcher for local repository files.
 * Scans repo paths (docs/, contract/, configs/, README*.md) and creates
 * chunk-level inverted index. Lazy-built on first query and rebuilt if
 * file set changes.
 */
public class Bm25Index {

    public static class Chunk {
        public final String id;
        public final String title;
        public final String body;
        public final String source;
        public final int length;
        public final long mtime;

        public Chunk(String id, String title, String body, String source, long mtime) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.source = source;
            requireNotInterrupted();
            this.length = TextUtils.tokenize(body).size();
            requireNotInterrupted();
            this.mtime = mtime;
        }
    }

    private final Path repoRoot;
    private volatile IndexState state = IndexState.empty();

    private static final List<String> SCAN_DIRS = List.of("docs", "contract", "configs");
    private static final List<String> EXT = List.of(".md", ".markdown", ".txt", ".yml", ".yaml", ".json");
    private static final Pattern TITLE_H1 = Pattern.compile("^#\\s*(.+)$", Pattern.MULTILINE);
    private static final long FINGERPRINT_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FINGERPRINT_PRIME = 0x100000001b3L;

    public Bm25Index(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public synchronized void ensureBuilt() throws IOException {
        CandidateSnapshot snapshot = snapshotCandidateFiles();
        IndexState current = state;
        if (!current.built()
                || snapshot.files().size() != current.fileCount()
                || snapshot.fingerprint() != current.fileFingerprint()) {
            IndexState rebuilt = buildState(snapshot);
            requireNotInterrupted();
            state = rebuilt;
        }
    }

    private CandidateSnapshot snapshotCandidateFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String d : SCAN_DIRS) {
            requireNotInterrupted();
            Path p = repoRoot.resolve(d);
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) {
                    Iterator<Path> iterator = s.iterator();
                    while (iterator.hasNext()) {
                        requireNotInterrupted();
                        Path candidate = iterator.next();
                        if (Files.isRegularFile(candidate) && isEligible(candidate)) {
                            files.add(candidate);
                        }
                    }
                }
            }
        }
        try (var s = Files.list(repoRoot)) {
            Iterator<Path> iterator = s.iterator();
            while (iterator.hasNext()) {
                requireNotInterrupted();
                Path candidate = iterator.next();
                String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(candidate) && name.startsWith("readme") && name.endsWith(".md")) {
                    files.add(candidate);
                }
            }
        }
        requireNotInterrupted();
        files.sort((left, right) -> {
            requireNotInterrupted();
            return relativeFingerprintPath(left).compareTo(relativeFingerprintPath(right));
        });
        requireNotInterrupted();

        long fingerprint = FINGERPRINT_OFFSET_BASIS;
        for (Path file : files) {
            requireNotInterrupted();
            fingerprint = mixFingerprint(fingerprint, relativeFingerprintPath(file));
            fingerprint = mixFingerprint(fingerprint, Files.size(file));
            fingerprint = mixFingerprint(fingerprint, Files.getLastModifiedTime(file).toMillis());
        }
        return new CandidateSnapshot(List.copyOf(files), fingerprint);
    }

    private boolean isEligible(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : EXT) if (name.endsWith(e)) return true;
        return false;
    }

    private IndexState buildState(CandidateSnapshot snapshot) throws IOException {
        List<Path> files = snapshot.files();
        List<Chunk> nextChunks = new ArrayList<>();

        for (Path f : files) {
            requireNotInterrupted();
            String text = Files.readString(f, StandardCharsets.UTF_8);
            requireNotInterrupted();
            long mtime = Files.getLastModifiedTime(f).toMillis();
            // split by sections (# headings) or by length 700 chars fall back
            List<String> sections = splitIntoChunks(text);
            int chunkIdx = 0;
            for (String sec : sections) {
                requireNotInterrupted();
                String title = extractTitle(sec);
                String id = f.toString() + "#chunk-" + (chunkIdx++);
                nextChunks.add(new Chunk(id, title, sec, repoRoot.relativize(f).toString(), mtime));
            }
        }

        // build postings
        Map<String, List<Posting>> nextPostings = new HashMap<>();
        Map<String, Integer> nextDf = new HashMap<>();
        double totalDl = 0.0;
        for (int docId = 0; docId < nextChunks.size(); docId++) {
            requireNotInterrupted();
            Chunk c = nextChunks.get(docId);
            List<String> toks = TextUtils.tokenize(c.body);
            requireNotInterrupted();
            totalDl += Math.max(1, toks.size());
            Map<String, Integer> tfMap = new HashMap<>();
            for (String t : toks) {
                requireNotInterrupted();
                tfMap.put(t, tfMap.getOrDefault(t, 0) + 1);
            }
            for (var e : tfMap.entrySet()) {
                requireNotInterrupted();
                nextPostings.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new Posting(docId, e.getValue()));
                nextDf.put(e.getKey(), nextDf.getOrDefault(e.getKey(), 0) + 1);
            }
        }
        double nextAvgDl = Math.max(1.0, totalDl / Math.max(1, nextChunks.size()));
        // precompute norms
        Map<Integer, Double> nextDocNorm = new HashMap<>();
        for (int i = 0; i < nextChunks.size(); i++) {
            requireNotInterrupted();
            int dl = nextChunks.get(i).length;
            double norm = 1.2 * (1 - 0.75 + 0.75 * dl / nextAvgDl);
            nextDocNorm.put(i, norm);
        }
        requireNotInterrupted();
        return IndexState.create(
                nextPostings,
                nextChunks,
                nextDf,
                nextAvgDl,
                nextDocNorm,
                Instant.now().toEpochMilli(),
                files.size(),
                snapshot.fingerprint());
    }

    private String relativeFingerprintPath(Path path) {
        return repoRoot.relativize(path).toString().replace('\\', '/');
    }

    private static long mixFingerprint(long fingerprint, String value) {
        long mixed = fingerprint;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte valueByte : bytes) {
            mixed = (mixed ^ (valueByte & 0xffL)) * FINGERPRINT_PRIME;
        }
        return mixed;
    }

    private static long mixFingerprint(long fingerprint, long value) {
        long mixed = fingerprint;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed = (mixed ^ ((value >>> shift) & 0xffL)) * FINGERPRINT_PRIME;
        }
        return mixed;
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("bm25_index_build_interrupted");
        }
    }

    private record CandidateSnapshot(List<Path> files, long fingerprint) {
    }

    private record Posting(int docId, int termFrequency) {
    }

    private record IndexState(Map<String, List<Posting>> postings,
                              List<Chunk> chunks,
                              Map<String, Integer> df,
                              double avgDl,
                              Map<Integer, Double> docNorm,
                              long builtAt,
                              int fileCount,
                              long fileFingerprint) {
        private static IndexState empty() {
            return new IndexState(Map.of(), List.of(), Map.of(), 1.0, Map.of(), 0L, -1, Long.MIN_VALUE);
        }

        private static IndexState create(Map<String, List<Posting>> postings,
                                         List<Chunk> chunks,
                                         Map<String, Integer> df,
                                         double avgDl,
                                         Map<Integer, Double> docNorm,
                                         long builtAt,
                                         int fileCount,
                                         long fileFingerprint) {
            Map<String, List<Posting>> frozenPostings = new HashMap<>();
            postings.forEach((term, values) -> frozenPostings.put(term, List.copyOf(values)));
            return new IndexState(
                    Map.copyOf(frozenPostings),
                    List.copyOf(chunks),
                    Map.copyOf(df),
                    avgDl,
                    Map.copyOf(docNorm),
                    builtAt,
                    fileCount,
                    fileFingerprint);
        }

        private boolean built() {
            return builtAt != 0L;
        }
    }

    private static List<String> splitIntoChunks(String text) {
        List<String> out = new ArrayList<>();
        // split by headings
        requireNotInterrupted();
        String[] parts = text.split("(?m)^# ");
        requireNotInterrupted();
        if (parts.length > 1) {
            for (String p : parts) {
                requireNotInterrupted();
                String s = p.trim();
                if (!s.isEmpty()) out.add("# " + s);
            }
        } else {
            int step = 700;
            for (int i = 0; i < text.length(); i += step) {
                requireNotInterrupted();
                int end = Math.min(text.length(), i + step);
                out.add(text.substring(i, end));
            }
        }
        requireNotInterrupted();
        return out;
    }

    private static String extractTitle(String sec) {
        var m = TITLE_H1.matcher(sec);
        if (m.find()) return m.group(1).trim();
        // else first line
        String[] lines = sec.split("\\R");
        for (String line : lines) {
            if (!line.isBlank()) return line.trim();
        }
        return "";
    }

    public static class SearchResult {
        public final int docId;
        public final double score;
        public final Chunk chunk;

        public SearchResult(int docId, double score) {
            this(docId, score, null);
        }

        public SearchResult(int docId, double score, Chunk chunk) {
            this.docId = docId;
            this.score = score;
            this.chunk = chunk;
        }
    }

    public List<SearchResult> search(String query, String domainFilter, int maxCandidates) {
        IndexState snapshot = state;
        List<String> qToks = TextUtils.tokenize(query);
        if (qToks.isEmpty()) return List.of();
        Map<Integer, Double> scores = new HashMap<>();
        int N = snapshot.chunks().size();
        for (String q : qToks) {
            List<Posting> posting = snapshot.postings().get(q);
            if (posting == null) continue;
            double idf = Math.log((N - snapshot.df().getOrDefault(q, 0) + 0.5)
                    / (snapshot.df().getOrDefault(q, 0) + 0.5) + 1.0);
            for (Posting pair : posting) {
                int docId = pair.docId();
                if (domainFilter != null && !domainFilter.isBlank()) {
                    String src = snapshot.chunks().get(docId).source.toLowerCase(Locale.ROOT);
                    if (!src.contains(domainFilter.toLowerCase(Locale.ROOT))) continue;
                }
                double tf = pair.termFrequency();
                double denom = snapshot.docNorm().getOrDefault(docId, 1.2);
                double bm25 = idf * (tf * 2.2) / (tf + denom); // k1=1.2 -> 1.2*(1-b+ b*dl/avg)
                scores.put(docId, scores.getOrDefault(docId, 0.0) + bm25);
            }
        }

        // add title boost and recency boost
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            Bm25Index.Chunk c = snapshot.chunks().get(e.getKey());
            double titleBoost = TextUtils.titleOverlapBoost(query, c.title);
            double recency = TextUtils.recencyBoost(c.body);
            e.setValue(e.getValue() + titleBoost + recency);
        }

        // to list
        List<SearchResult> result = new ArrayList<>();
        for (var e : scores.entrySet()) {
            result.add(new SearchResult(
                    e.getKey(), e.getValue(), snapshot.chunks().get(e.getKey())));
        }
        result.sort((a,b)-> Double.compare(b.score, a.score));
        if (result.size() > maxCandidates) {
            return new ArrayList<>(result.subList(0, maxCandidates));
        }
        return result;
    }

    public Chunk getChunk(int docId) {
        IndexState snapshot = state;
        return snapshot.chunks().get(docId);
    }

    public double getAvgDl() {
        IndexState snapshot = state;
        return snapshot.avgDl();
    }

    public int size() {
        IndexState snapshot = state;
        return snapshot.chunks().size();
    }
}
