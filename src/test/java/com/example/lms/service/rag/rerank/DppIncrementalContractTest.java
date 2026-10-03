package com.example.lms.service.rag.rerank;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class DppIncrementalContractTest {
    private record Doc(String id, String text, double score) { }
    private static final String[] TRACE_KEYS = {
            "dpp.rerank.inputCount", "dpp.rerank.outputCount", "dpp.rerank.diversityScore",
            "dpp.rerank.skipped", "dpp.rerank.skipReason", "hypernova.dppApplied",
            "hypernova.dppInputCount", "hypernova.dppOutputCount", "hypernova.dppK",
            "hypernova.dppDisabledReason", "dpp.extractor.fallback", "dpp.extractor.errorType",
            "dpp.relevance.fallback", "dpp.relevance.errorType",
            "dpp.stableKey.excluded", "dpp.stableKey.errorType"
    };
    @AfterEach void clear() { TraceStore.clear(); }

    @Test void kernelWorkIsBoundedBySelectedRows() throws Exception {
        int n = 96, k = 16;
        List<Doc> input = ordinary(n);
        Map<Doc, Double> rel = new IdentityHashMap<>();
        Map<Doc, String> keys = new IdentityHashMap<>();
        input.forEach(d -> { rel.put(d, d.score); keys.put(d, d.id); });
        AtomicInteger calls = new AtomicInteger();
        Function<Doc, Set<String>> shingles = d -> {
            calls.incrementAndGet();
            return Set.of(d.id);
        };
        Method selector = DppDiversityReranker.class.getDeclaredMethod("greedyDeterminantalSelect",
                List.class, int.class, double.class, Map.class, Function.class, Map.class);
        selector.setAccessible(true);
        List<?> selected = (List<?>) selector.invoke(null, input, k, 0.7d, rel, shingles, keys);
        assertEquals(k, selected.size());
        assertTrue(calls.get() <= 2 * n * k, "shingle accesses=" + calls.get());
    }

    @Test void seededGoldenCorpusPreservesOrderedIdentityAndExistingTrace() {
        Random random = new Random(20260928L);
        int fallbacks = 0;
        for (int sample = 0; sample < 450; sample++) {
            int n = 2 + random.nextInt(25), k = 1 + random.nextInt(n + 3);
            List<Doc> docs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String text = switch (sample % 8) {
                    case 0 -> "동일한 근거 거의 같은 문서 " + (i % 3);
                    case 1 -> i % 2 == 0 ? "" : "a";
                    case 2 -> null;
                    case 3 -> "duplicate text with identical shingles";
                    default -> "group " + (i % 5) + " token " + random.nextInt(99999);
                };
                double score = switch (sample % 7) {
                    case 0 -> 0.5d;
                    case 1 -> 0.7d + i * 1e-14d;
                    case 2 -> i % 3 == 0 ? Double.NaN : (i % 3 == 1 ? -1d : Double.POSITIVE_INFINITY);
                    case 3 -> 0.00001d + i * 0.000001d;
                    default -> 0.15d + 0.85d * random.nextDouble();
                };
                String id = sample % 11 == 0 && i % 4 == 0 ? null
                        : "key-" + (sample % 9 == 0 ? i % 3 : i);
                docs.add(new Doc(id, text, score));
            }
            if (sample % 6 == 0) docs.add(docs.get(0));
            Collections.shuffle(docs, random);
            double lambda = switch (sample % 4) { case 0 -> 0d; case 1 -> 1d; default -> 0.7d; };
            assertGolden(docs, k, lambda, sample % 13 == 0);
            if (Objects.equals(1, TraceStore.get("dpp.incremental.fallbackCount"))) fallbacks++;
        }
        System.out.println("F07_GOLDEN cases=450 orderedIdentity=equal trace=equal fallbacks=" + fallbacks);
    }

    @Test void nearSingularAndTieCasesUseBoundedReasonAndLegacyOrder() {
        List<Doc> docs = List.of(new Doc("a", "same shared document", 0.9d),
                new Doc("b", "same shared document", 0.9d),
                new Doc("c", "same shared document", 0.9d));
        assertGolden(docs, 3, 0d, false);
        assertEquals(1, TraceStore.get("dpp.incremental.fallbackCount"));
        assertTrue(Set.of("RESIDUAL_ILL_CONDITIONED", "SCORE_BOUNDARY")
                .contains(TraceStore.get("dpp.incremental.fallbackReason")));
    }

    @Test void separatedStageTimingAndAllocationAreReported() {
        com.sun.management.ThreadMXBean bean =
                ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean b ? b : null;
        if (bean != null && bean.isThreadAllocatedMemorySupported() && !bean.isThreadAllocatedMemoryEnabled())
            bean.setThreadAllocatedMemoryEnabled(true);
        for (int[] size : new int[][]{{8,4}, {60,10}, {100,16}, {160,20}}) {
            List<Doc> docs = ordinary(size[0]);
            for (int warm = 0; warm < 4; warm++) { run(docs, size[1], false); run(docs, size[1], true); }
            long[] oldTime = new long[9], newTime = new long[9], oldBytes = new long[9], newBytes = new long[9];
            for (int trial = 0; trial < 9; trial++) {
                for (int turn = 0; turn < 2; turn++) {
                    boolean incremental = (trial + turn) % 2 == 0;
                    long bytes = allocated(bean), start = System.nanoTime();
                    List<Doc> actual = run(docs, size[1], incremental);
                    long elapsed = System.nanoTime() - start, allocation = allocated(bean) - bytes;
                    assertEquals(size[1], actual.size());
                    (incremental ? newTime : oldTime)[trial] = elapsed;
                    (incremental ? newBytes : oldBytes)[trial] = allocation;
                }
            }
            System.out.printf(Locale.ROOT,
                    "F07_STAGE n=%d k=%d oldMedianMs=%.3f newMedianMs=%.3f oldBytes=%d newBytes=%d allocationObserved=%s%n",
                    size[0], size[1], median(oldTime)/1e6, median(newTime)/1e6,
                    median(oldBytes), median(newBytes), bean != null && bean.isThreadAllocatedMemorySupported());
        }
    }

    private static long allocated(com.sun.management.ThreadMXBean bean) {
        return bean == null || !bean.isThreadAllocatedMemorySupported() ? 0L
                : bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    private static long median(long[] values) { Arrays.sort(values); return values[values.length/2]; }
    private static List<Doc> ordinary(int n) {
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < n; i++)
            docs.add(new Doc(String.format(Locale.ROOT, "%04d", i),
                    Character.toString((char)(0x4000 + i)).repeat(12), 0.95d - 0.65d*i/n));
        return docs;
    }
    private static List<Doc> run(List<Doc> docs, int k, boolean incremental) {
        if (incremental) return new DppDiversityReranker().rerank(new DppDiversityReranker.Config(0.7d, k),
                docs, "query", k, Doc::text, Doc::score, Doc::id);
        return new LegacyDppPreimage().rerank(new LegacyDppPreimage.Config(0.7d, k),
                docs, "query", k, Doc::text, Doc::score, Doc::id);
    }
    private static Map<String,Object> trace() {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        for (String key : TRACE_KEYS) snapshot.put(key, TraceStore.get(key));
        return snapshot;
    }
    private static void assertGolden(List<Doc> docs, int k, double lambda, boolean exceptions) {
        List<Doc> before = new ArrayList<>(docs);
        Function<Doc,String> text = d -> { if (exceptions && d.text == null) throw new IllegalArgumentException(); return d.text; };
        java.util.function.ToDoubleFunction<Doc> relevance =
                d -> { if (exceptions && d.score < 0) throw new IllegalStateException(); return d.score; };
        Function<Doc,String> key = d -> { if (exceptions && d.id == null) throw new IllegalArgumentException(); return d.id; };
        TraceStore.clear();
        List<Doc> expected = new LegacyDppPreimage().rerank(new LegacyDppPreimage.Config(lambda,k),
                docs, "문서 token", k, text, relevance, key);
        Map<String,Object> expectedTrace = trace();
        TraceStore.clear();
        List<Doc> actual = new DppDiversityReranker().rerank(new DppDiversityReranker.Config(lambda,k),
                docs, "문서 token", k, text, relevance, key);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i), "ordinal=" + i);
        assertEquals(expectedTrace, trace());
        for (int i = 0; i < docs.size(); i++) assertSame(before.get(i), docs.get(i));
    }
}
