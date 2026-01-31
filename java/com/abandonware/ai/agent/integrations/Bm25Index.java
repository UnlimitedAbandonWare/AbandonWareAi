
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
            this.length = TextUtils.tokenize(body).size();
            this.mtime = mtime;
        }
    }

    private final Map<String, List<int[]>> postings = new HashMap<>(); // term -> list of [docId, tf]
    private final List<Chunk> chunks = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgDl = 1.0;
    private final Map<Integer, Double> docNorm = new HashMap<>(); // precomputed length component
    private final Path repoRoot;

    private long builtAt = 0L;
    private int lastFileCount = -1;

    private static final List<String> SCAN_DIRS = List.of("docs", "contract", "configs");
    private static final List<String> EXT = List.of(".md", ".markdown", ".txt", ".yml", ".yaml", ".json");
    private static final Pattern TITLE_H1 = Pattern.compile("^#\\s*(.+)$", Pattern.MULTILINE);

    public Bm25Index(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public synchronized void ensureBuilt() throws IOException {
        // simple re-build if file count changed since last build
        int count = countCandidateFiles();
        if (builtAt == 0L || count != lastFileCount) {
            build();
        }
    }

    private int countCandidateFiles() throws IOException {
        int c = 0;
        // dirs
        for (String d : SCAN_DIRS) {
            Path p = repoRoot.resolve(d);
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) {
                    c += (int) s.filter(Files::isRegularFile).filter(this::isEligible).count();
                }
            }
        }
        // README*.md in root
        try (var s = Files.list(repoRoot)) {
            c += (int) s.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().toLowerCase().startsWith("readme") && f.getFileName().toString().toLowerCase().endsWith(".md")).count();
        }
        return c;
    }

    private boolean isEligible(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : EXT) if (name.endsWith(e)) return true;
        return false;
    }

    private void build() throws IOException {
        postings.clear();
        chunks.clear();
        df.clear();
        docNorm.clear();

        List<Path> files = new ArrayList<>();
        for (String d : SCAN_DIRS) {
            Path p = repoRoot.resolve(d);
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) {
                    s.filter(Files::isRegularFile).filter(this::isEligible).forEach(files::add);
                }
            }
        }
        try (var s = Files.list(repoRoot)) {
            s.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().toLowerCase().startsWith("readme") && f.getFileName().toString().toLowerCase().endsWith(".md")).forEach(files::add);
        }

        int idCounter = 0;
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            long mtime = Files.getLastModifiedTime(f).toMillis();
            // split by sections (# headings) or by length 700 chars fall back
            List<String> sections = splitIntoChunks(text);
            int chunkIdx = 0;
            for (String sec : sections) {
                String title = extractTitle(sec);
                String id = f.toString() + "#chunk-" + (chunkIdx++);
                chunks.add(new Chunk(id, title, sec, repoRoot.relativize(f).toString(), mtime));
            }
        }

        // build postings
        avgDl = 0.0;
        for (int docId = 0; docId < chunks.size(); docId++) {
            Chunk c = chunks.get(docId);
            List<String> toks = TextUtils.tokenize(c.body);
            avgDl += Math.max(1, toks.size());
            Map<String, Integer> tfMap = new HashMap<>();
            for (String t : toks) tfMap.put(t, tfMap.getOrDefault(t, 0) + 1);
            for (var e : tfMap.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new int[]{docId, e.getValue()});
                df.put(e.getKey(), df.getOrDefault(e.getKey(), 0) + 1);
            }
        }
        avgDl = Math.max(1.0, avgDl / Math.max(1, chunks.size()));
        // precompute norms
        for (int i = 0; i < chunks.size(); i++) {
            int dl = chunks.get(i).length;
            double norm = 1.2 * (1 - 0.75 + 0.75 * dl / avgDl);
            docNorm.put(i, norm);
        }
        this.builtAt = Instant.now().toEpochMilli();
        this.lastFileCount = chunks.size();
    }

    private static List<String> splitIntoChunks(String text) {
        List<String> out = new ArrayList<>();
        // split by headings
        String[] parts = text.split("(?m)^# ");
        if (parts.length > 1) {
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) out.add("# " + s);
            }
        } else {
            int step = 700;
            for (int i = 0; i < text.length(); i += step) {
                int end = Math.min(text.length(), i + step);
                out.add(text.substring(i, end));
            }
        }
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
        public SearchResult(int docId, double score) {
            this.docId = docId; this.score = score;
        }
    }

    public List<SearchResult> search(String query, String domainFilter, int maxCandidates) {
        List<String> qToks = TextUtils.tokenize(query);
        if (qToks.isEmpty()) return List.of();
        Map<Integer, Double> scores = new HashMap<>();
        int N = chunks.size();
        for (String q : qToks) {
            List<int[]> posting = postings.get(q);
            if (posting == null) continue;
            double idf = Math.log((N - df.getOrDefault(q, 0) + 0.5) / (df.getOrDefault(q, 0) + 0.5) + 1.0);
            for (int[] pair : posting) {
                int docId = pair[0];
                if (domainFilter != null && !domainFilter.isBlank()) {
                    String src = chunks.get(docId).source.toLowerCase(Locale.ROOT);
                    if (!src.contains(domainFilter.toLowerCase(Locale.ROOT))) continue;
                }
                double tf = pair[1];
                double denom = docNorm.getOrDefault(docId, 1.2);
                double bm25 = idf * (tf * 2.2) / (tf + denom); // k1=1.2 -> 1.2*(1-b+ b*dl/avg)
                scores.put(docId, scores.getOrDefault(docId, 0.0) + bm25);
            }
        }

        // add title boost and recency boost
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            Bm25Index.Chunk c = chunks.get(e.getKey());
            double titleBoost = TextUtils.titleOverlapBoost(query, c.title);
            double recency = TextUtils.recencyBoost(c.body);
            e.setValue(e.getValue() + titleBoost + recency);
        }

        // to list
        List<SearchResult> result = new ArrayList<>();
        for (var e : scores.entrySet()) result.add(new SearchResult(e.getKey(), e.getValue()));
        result.sort((a,b)-> Double.compare(b.score, a.score));
        if (result.size() > maxCandidates) {
            return new ArrayList<>(result.subList(0, maxCandidates));
        }
        return result;
    }

    public Chunk getChunk(int docId) { return chunks.get(docId); }

    public double getAvgDl() { return avgDl; }

    public int size() { return chunks.size(); }
}