package com.example.lms.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;


import dev.langchain4j.store.embedding.filter.Filter;
/**
 * FederatedEmbeddingStore composes multiple {@link EmbeddingStore} instances
 * into a single store.  During search operations it consults the
 * {@link TopicRoutingSettings} to determine how many results to request
 * from each store based on the desired topK and the inferred topic.  It
 * then merges, normalises and deduplicates the results to produce a
 * unified list.  Write operations fan-out to all stores but failures in
 * any individual store are suppressed (fail-soft).
 */
@Component
@Primary
public class FederatedEmbeddingStore implements EmbeddingStore<TextSegment> {
    private static final Logger log = LoggerFactory.getLogger(FederatedEmbeddingStore.class);
    /** Simple wrapper of a named EmbeddingStore. */
    public record NamedStore(String id, EmbeddingStore<TextSegment> store) {}

    enum FederatedStoreWriteStatus {
        SUCCEEDED,
        DEADLINE_EXCEEDED,
        WORKER_UNFINISHED,
        FAILED
    }

    record FederatedWriteResult(Map<String, FederatedStoreWriteStatus> outcomes,
                                int succeededCount) {
        FederatedWriteResult {
            outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
            succeededCount = Math.max(0, succeededCount);
        }
    }

    private final List<NamedStore> stores;
    private final TopicRoutingSettings routing;
    private final long searchTimeoutMs;
    private final int maxParallelism;
    private final ExecutorService pool;
    private final Map<NamedStore, Semaphore> storeAdmissions;

    // Use a context-aware pool so MDC/GuardContext survives on pooled workers.
    // (Even if the vector store itself doesn't rely on GuardContext today, downstream
    // stores or tracing often do.)
    private static ExecutorService newPool(int maxParallelism) {
        int threads = Math.max(1, Math.min(64, maxParallelism));
        AtomicInteger seq = new AtomicInteger();
        return new ContextAwareExecutorService(
                Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "vec-federated-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }));
    }


    // MERGE_HOOK:PROJ_AGENT::FED_STORE_LIST_BEAN_INJECT_V1
    @Autowired
    public FederatedEmbeddingStore(
            @Qualifier("federatedEmbeddingStores") ObjectProvider<List<NamedStore>> storesProvider,
            TopicRoutingSettings routing,
            @Value("${vector.federated.search-timeout-ms:5000}") long searchTimeoutMs,
            @Value("${vector.federated.max-parallelism:8}") int maxParallelism
    ) {
        List<NamedStore> resolved = (storesProvider == null)
                ? Collections.emptyList()
                : storesProvider.getIfAvailable(Collections::emptyList);
        this.stores = (resolved == null) ? Collections.emptyList() : List.copyOf(resolved);
        this.routing = routing == null ? new TopicRoutingSettings(Collections.emptyMap(), 1) : routing;
        this.searchTimeoutMs = Math.max(50L, searchTimeoutMs);
        this.maxParallelism = Math.max(1, maxParallelism);
        this.pool = newPool(this.maxParallelism);
        this.storeAdmissions = newStoreAdmissions(this.stores);
    }

    FederatedEmbeddingStore(List<NamedStore> stores,
                            TopicRoutingSettings routing,
                            long searchTimeoutMs,
                            int maxParallelism) {
        this.stores = stores == null ? Collections.emptyList() : List.copyOf(stores);
        this.routing = routing == null ? new TopicRoutingSettings(Collections.emptyMap(), 1) : routing;
        this.searchTimeoutMs = Math.max(50L, searchTimeoutMs);
        this.maxParallelism = Math.max(1, maxParallelism);
        this.pool = newPool(this.maxParallelism);
        this.storeAdmissions = newStoreAdmissions(this.stores);
    }
    @PostConstruct
    void logInitialization() {
        if (stores == null || stores.isEmpty()) {
            log.warn("FederatedEmbeddingStore initialized with 0 underlying store(s). " +
                    "Vector search will always return empty results. " +
                    "Check FederatedVectorStorePatchConfig / EmbeddingStore beans.");
        } else {
            List<String> ids = safeStoreIds(stores.stream().map(NamedStore::id).toList());
            log.info("FederatedEmbeddingStore initialized with {} store(s): {} timeoutMs={} maxParallelism={}",
                    ids.size(), ids, searchTimeoutMs, maxParallelism);
        }
    }

    @PreDestroy
    void shutdownPool() {
        try {
            pool.shutdownNow();
        } catch (Exception ignore) {
            logSuppressed("pool.shutdown", ignore);
        }
    }

    /**
     * Return an immutable list of the identifiers for the underlying stores
     * composing this FederatedEmbeddingStore.  When no stores are present
     * an empty list is returned.  This method can be used for diagnostics
     * and logging of the vector routing configuration.
     *
     * @return list of store identifiers, never {@code null}
     */
    public List<String> describeStoreIds() {
        if (stores == null || stores.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return stores.stream().map(NamedStore::id).collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    @Override
    public String add(dev.langchain4j.data.embedding.Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding, null);
        return id;
    }

    @Override
    public void add(String id, dev.langchain4j.data.embedding.Embedding embedding) {
        add(id, embedding, null);
    }

    @Override
    public String add(dev.langchain4j.data.embedding.Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        add(id, embedding, embedded);
        return id;
    }

    @Override
    public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        addAll(ids, embeddings, Collections.nCopies(embeddings.size(), null));
        return ids;
    }

    @Override
    public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        addAll(ids, embeddings, segments);
        return ids;
    }

    private void add(String id, dev.langchain4j.data.embedding.Embedding embedding, TextSegment segment) {
        addAll(List.of(id), List.of(embedding), Collections.singletonList(segment));
    }
    @Override
    public void addAll(List<String> ids, List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
        FederatedWriteResult result = writeAllWithinDeadline(ids, embeddings, segments, searchTimeoutMs);
        if (result.succeededCount() == 0) {
            List<String> failed = result.outcomes().entrySet().stream()
                    .filter(entry -> entry.getValue() != FederatedStoreWriteStatus.SUCCEEDED)
                    .map(entry -> entry.getKey() + ":" + entry.getValue().name())
                    .toList();
            throw new IllegalStateException("Federated addAll failed on all stores: " + String.join(",", failed));
        }
    }

    FederatedWriteResult writeAllWithinDeadline(
            List<String> ids,
            List<dev.langchain4j.data.embedding.Embedding> embeddings,
            List<TextSegment> segments,
            long timeoutMs) {
        if (stores == null || stores.isEmpty()) {
            throw new IllegalStateException("FederatedEmbeddingStore has no upstream stores (stores=empty)");
        }

        List<String> admittedIds = stableList(ids);
        List<dev.langchain4j.data.embedding.Embedding> admittedEmbeddings = stableList(embeddings);
        List<TextSegment> admittedSegments = stableList(segments);
        long deadlineNanos = deadlineAfterMillis(timeoutMs);
        ExecutorCompletionService<WriteWorkerResult> completions = new ExecutorCompletionService<>(pool);
        List<WriteTask> tasks = new ArrayList<>();
        Map<Future<WriteWorkerResult>, WriteTask> tasksByFuture = new IdentityHashMap<>();
        Map<String, FederatedStoreWriteStatus> outcomes = new LinkedHashMap<>();
        int unfinishedCount = 0;

        for (NamedStore ns : stores) {
            String safeId = safeStoreId(ns.id());
            if (remainingNanos(deadlineNanos) <= 0L) {
                outcomes.put(safeId, FederatedStoreWriteStatus.DEADLINE_EXCEEDED);
                continue;
            }
            WorkerLease lease = tryAcquireWorkerLease(ns);
            if (lease == null) {
                outcomes.put(safeId, FederatedStoreWriteStatus.WORKER_UNFINISHED);
                unfinishedCount++;
                continue;
            }
            try {
                Future<WriteWorkerResult> future = completions.submit(() -> {
                    if (!lease.begin()) {
                        return new WriteWorkerResult(FederatedStoreWriteStatus.FAILED, System.nanoTime());
                    }
                    try {
                        FederatedStoreWriteStatus status = writeToStore(
                                ns, admittedIds, admittedEmbeddings, admittedSegments);
                        return new WriteWorkerResult(status, System.nanoTime());
                    } finally {
                        lease.finish();
                    }
                });
                WriteTask task = new WriteTask(ns.id(), lease, future);
                tasks.add(task);
                tasksByFuture.put(future, task);
            } catch (RejectedExecutionException rejected) {
                lease.cancelBeforeStart();
                outcomes.put(safeId, FederatedStoreWriteStatus.FAILED);
            }
        }

        Set<Future<WriteWorkerResult>> collected = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean callerInterrupted = false;
        while (collected.size() < tasks.size()) {
            long remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0L) {
                break;
            }
            Future<WriteWorkerResult> completedFuture;
            try {
                completedFuture = completions.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                callerInterrupted = true;
                break;
            }
            if (completedFuture == null) {
                break;
            }
            collected.add(completedFuture);
            WriteTask task = tasksByFuture.get(completedFuture);
            try {
                WriteWorkerResult completed = completedFuture.get();
                outcomes.put(safeStoreId(task.storeId()),
                        completed.completedNanos() - deadlineNanos <= 0L
                                ? completed.status()
                                : FederatedStoreWriteStatus.DEADLINE_EXCEEDED);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                callerInterrupted = true;
                break;
            } catch (CancellationException cancelled) {
                outcomes.put(safeStoreId(task.storeId()), FederatedStoreWriteStatus.DEADLINE_EXCEEDED);
            } catch (ExecutionException failed) {
                outcomes.put(safeStoreId(task.storeId()), FederatedStoreWriteStatus.FAILED);
            }
        }

        for (WriteTask task : tasks) {
            if (collected.contains(task.future())) {
                continue;
            }
            if (task.future().isDone() && !task.future().isCancelled()) {
                try {
                    WriteWorkerResult completed = task.future().get();
                    if (completed.completedNanos() - deadlineNanos <= 0L) {
                        outcomes.put(safeStoreId(task.storeId()), completed.status());
                        continue;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callerInterrupted = true;
                } catch (ExecutionException | CancellationException ignored) {
                    logSuppressed("write.collect", ignored);
                }
            }
            boolean workerUnfinished = cancelWithoutInterrupt(task.lease(), task.future());
            outcomes.put(safeStoreId(task.storeId()), FederatedStoreWriteStatus.DEADLINE_EXCEEDED);
            if (workerUnfinished) {
                unfinishedCount++;
            }
        }

        int succeededCount = (int) outcomes.values().stream()
                .filter(status -> status == FederatedStoreWriteStatus.SUCCEEDED)
                .count();
        trace("vector.federated.write.outcomes", outcomes.toString());
        trace("vector.federated.write.succeededCount", succeededCount);
        trace("vector.federated.write.unfinishedCount", unfinishedCount);
        trace("vector.federated.write.unfinishedReason", unfinishedCount > 0 ? "worker_unfinished" : "none");
        if (callerInterrupted) {
            trace("vector.federated.write.interrupted", true);
        }
        return new FederatedWriteResult(outcomes, succeededCount);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest req) {
        Embedding q = (req == null ? null : req.queryEmbedding());
        if (q == null || q.vector() == null || q.vector().length == 0) {
            log.warn("FederatedEmbeddingStore.search called with empty embedding; skipping all stores");
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }
        if (isAllZeroVector(q.vector())) {
            log.warn("FederatedEmbeddingStore.search called with all-zero embedding; skipping all stores");
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }
        String topic = extractTopicFromFilter(req.filter()).orElse("default");
        String topicTrace = safeTopic(topic);

        Map<String, Double> configuredWeights = routing.weightsFor(topic);
        Map<String, Double> weights = weightsMatchingStores(configuredWeights);
        Set<String> unmatchedWeightKeys = unmatchedWeightKeys(configuredWeights);
        if (!unmatchedWeightKeys.isEmpty()) {
            log.warn("[AWX2AF2][vector][route] unmatched vector.routing weight keys topic={} keys={} stores={}",
                    topicTrace, safeStoreIds(unmatchedWeightKeys), safeStoreIds(stores.stream().map(NamedStore::id).toList()));
            trace("vector.federated.unmatchedWeightKeys", safeStoreIds(unmatchedWeightKeys).toString());
        }
        int k = Math.max(1, req.maxResults());
        Map<String, Integer> split = allocateK(weights, k, routing.minPerStore());
        long deadlineNanos = deadlineAfterMillis(searchTimeoutMs);
        ExecutorCompletionService<SearchWorkerResult> completions = new ExecutorCompletionService<>(pool);
        List<SearchTask> tasks = new ArrayList<>();
        Map<Future<SearchWorkerResult>, SearchTask> tasksByFuture = new IdentityHashMap<>();
        List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int unfinishedCount = 0;

        for (NamedStore ns : stores) {
            int ki = split.getOrDefault(ns.id(), 0);
            if (ki <= 0) continue;
            if (remainingNanos(deadlineNanos) <= 0L) {
                diagnostics.add(safeStoreId(ns.id()) + ":deadline_exceeded:k=" + ki);
                continue;
            }
            WorkerLease lease = tryAcquireWorkerLease(ns);
            if (lease == null) {
                unfinishedCount++;
                diagnostics.add(safeStoreId(ns.id()) + ":worker_unfinished:k=" + ki);
                continue;
            }
            EmbeddingSearchRequest subReq = EmbeddingSearchRequest.builder()
                    .queryEmbedding(req.queryEmbedding())
                    .maxResults(ki)
                    .minScore(req.minScore())
                    .filter(req.filter())
                    .build();
            long started = System.nanoTime();
            try {
                Future<SearchWorkerResult> future = completions.submit(() -> {
                    if (!lease.begin()) {
                        return new SearchWorkerResult(
                                new EmbeddingSearchResult<>(Collections.emptyList()),
                                System.nanoTime());
                    }
                    try {
                        EmbeddingSearchResult<TextSegment> result;
                        try {
                            result = ns.store().search(subReq);
                        } catch (Exception e) {
                            log.warn("Federated search fail-soft on store {}. errorHash={} errorLength={}",
                                    safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                            result = new EmbeddingSearchResult<>(Collections.emptyList());
                        }
                        return new SearchWorkerResult(result, System.nanoTime());
                    } finally {
                        lease.finish();
                    }
                });
                SearchTask task = new SearchTask(ns.id(), ki, started, lease, future);
                tasks.add(task);
                tasksByFuture.put(future, task);
            } catch (RejectedExecutionException rejected) {
                lease.cancelBeforeStart();
                diagnostics.add(safeStoreId(ns.id()) + ":error:executor_rejected");
                log.warn("[AWX2AF2][vector][store-error] store={} requestedK={} errorType={}",
                        safeStoreId(ns.id()), ki, "executor_rejected");
            }
        }

        Set<Future<SearchWorkerResult>> collected = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean callerInterrupted = false;
        long completionCutoffNanos = deadlineNanos;
        while (collected.size() < tasks.size()) {
            long remaining = remainingNanos(deadlineNanos);
            if (remaining <= 0L) {
                break;
            }
            Future<SearchWorkerResult> completedFuture;
            try {
                completedFuture = completions.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                completionCutoffNanos = System.nanoTime();
                Thread.currentThread().interrupt();
                callerInterrupted = true;
                break;
            }
            if (completedFuture == null) {
                break;
            }
            collected.add(completedFuture);
            SearchTask task = tasksByFuture.get(completedFuture);
            try {
                SearchWorkerResult completed = completedFuture.get();
                if (completed.completedNanos() - deadlineNanos <= 0L) {
                    appendSearchResult(task, completed.result(), merged, diagnostics);
                }
            } catch (InterruptedException interrupted) {
                completionCutoffNanos = System.nanoTime();
                Thread.currentThread().interrupt();
                callerInterrupted = true;
                break;
            } catch (CancellationException cancelled) {
                diagnostics.add(safeStoreId(task.storeId()) + ":deadline_exceeded:k=" + task.requestedK());
            } catch (ExecutionException failed) {
                diagnostics.add(safeStoreId(task.storeId()) + ":error:worker_failed");
                log.warn("[AWX2AF2][vector][store-error] store={} requestedK={} errorType={}",
                        safeStoreId(task.storeId()), task.requestedK(), "worker_failed");
            }
        }

        for (SearchTask task : tasks) {
            if (collected.contains(task.future())) {
                continue;
            }
            if (task.future().isDone() && !task.future().isCancelled()) {
                try {
                    SearchWorkerResult completed = task.future().get();
                    if (completed.completedNanos() - completionCutoffNanos <= 0L) {
                        appendSearchResult(task, completed.result(), merged, diagnostics);
                        continue;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callerInterrupted = true;
                } catch (ExecutionException | CancellationException ignored) {
                    logSuppressed("search.collect", ignored);
                }
            }
            boolean workerUnfinished = cancelWithoutInterrupt(task.lease(), task.future());
            if (workerUnfinished) {
                unfinishedCount++;
                diagnostics.add(safeStoreId(task.storeId()) + ":worker_unfinished:k=" + task.requestedK());
            } else {
                diagnostics.add(safeStoreId(task.storeId()) + ":deadline_exceeded:k=" + task.requestedK());
            }
            if (!callerInterrupted) {
                recordTimeoutCancellation(task.storeId(), task.requestedK());
                log.warn("[AWX2AF2][vector][timeout] store={} requestedK={} timeoutMs={}",
                        safeStoreId(task.storeId()), task.requestedK(), searchTimeoutMs);
            }
        }
        if (callerInterrupted) {
            diagnostics.add("interrupted:cancelMode=no_interrupt");
        }
        trace("vector.federated.topic", topicTrace);
        trace("vector.federated.split", safeSplit(split).toString());
        trace("vector.federated.diagnostics", diagnostics.toString());
        trace("vector.federated.timeoutMs", searchTimeoutMs);
        trace("vector.federated.search.unfinishedCount", unfinishedCount);
        trace("vector.federated.search.unfinishedReason", unfinishedCount > 0 ? "worker_unfinished" : "none");
        if (merged.isEmpty()) {
            // MERGE_HOOK:PROJ_AGENT::FEDERATED_ROUTE_LABEL
            // Normalised log line used by GPU/RAG diagnostics; keep shape stable.
            log.info("ROUTE_LABEL topic={} weights={} split={} k={} stores={} diagnostics={}",
                    topicTrace, safeSplit(weights), safeSplit(split), k,
                    safeStoreIds(stores.stream().map(NamedStore::id).toList()), diagnostics);
            log.info("[AWX2AF2][vector][zero-results] topic={} returnedCount=0 stores={} split={} diagnostics={}",
                    topicTrace,
                    safeStoreIds(stores.stream().map(NamedStore::id).toList()), safeSplit(split), diagnostics);
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }

        double min = merged.stream().mapToDouble(EmbeddingMatch::score).min().orElse(0.0);
        double max = merged.stream().mapToDouble(EmbeddingMatch::score).max().orElse(1.0);
        Map<String, EmbeddingMatch<TextSegment>> dedup = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> m : merged) {
            double norm = (max > min) ? (m.score() - min) / (max - min) : m.score();
            String key = keyOf(m.embedded());
            EmbeddingMatch<TextSegment> prev = dedup.get(key);
            if (prev == null || norm > prev.score()) {
                dedup.put(key, new EmbeddingMatch<>(norm, m.embeddingId(), m.embedding(), m.embedded()));
            }
        }
        List<EmbeddingMatch<TextSegment>> sorted = dedup.values().stream()
                .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed())
                .limit(k)
                .collect(Collectors.toList());
        return new EmbeddingSearchResult<>(sorted);
    }

    private void recordTimeoutCancellation(String storeId, int requestedK) {
        try {
            TraceStore.put("vector.federated.cancelMode", "no_interrupt");
            TraceStore.put("vector.federated.timeout", true);
            TraceStore.inc("vector.federated.timeout.count");
            TraceStore.append("vector.federated.timeout.events", Map.of(
                    "store", safeStoreId(storeId),
                    "requestedK", Math.max(0, requestedK),
                    "timeoutMs", searchTimeoutMs,
                    "cancelMode", "no_interrupt"));
        } catch (Throwable ignore) {
            log.debug("[AWX2AF2][vector][timeout] timeout breadcrumb suppressed store={}",
                    safeStoreId(storeId));
        }
    }

    private static Map<NamedStore, Semaphore> newStoreAdmissions(List<NamedStore> stores) {
        Map<NamedStore, Semaphore> admissions = new ConcurrentHashMap<>();
        if (stores != null) {
            for (NamedStore store : stores) {
                admissions.putIfAbsent(store, new Semaphore(1, true));
            }
        }
        return Collections.unmodifiableMap(admissions);
    }

    private WorkerLease tryAcquireWorkerLease(NamedStore store) {
        Semaphore admission = storeAdmissions.get(store);
        if (admission == null || !admission.tryAcquire()) {
            return null;
        }
        return new WorkerLease(admission);
    }

    private static long deadlineAfterMillis(long timeoutMs) {
        long maxMs = TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE / 4L);
        long boundedMs = Math.max(1L, Math.min(timeoutMs, maxMs));
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundedMs);
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static <T> List<T> stableList(List<T> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static boolean cancelWithoutInterrupt(WorkerLease lease, Future<?> future) {
        boolean cancelledBeforeStart = lease.cancelBeforeStart();
        try {
            future.cancel(false);
        } catch (RuntimeException ignored) {
            logSuppressed("future.cancel", ignored);
        }
        return !cancelledBeforeStart && lease.isRunning();
    }

    private void appendSearchResult(SearchTask task,
                                    EmbeddingSearchResult<TextSegment> result,
                                    List<EmbeddingMatch<TextSegment>> merged,
                                    List<String> diagnostics) {
        long tookMs = elapsedMs(task.startedNanos());
        int returned = (result == null || result.matches() == null) ? 0 : result.matches().size();
        diagnostics.add(safeStoreId(task.storeId()) + ":ok:k=" + task.requestedK()
                + ":returned=" + returned + ":tookMs=" + tookMs);
        if (result != null && result.matches() != null) {
            merged.addAll(result.matches());
        }
    }

    private FederatedStoreWriteStatus writeToStore(
            NamedStore ns,
            List<String> ids,
            List<dev.langchain4j.data.embedding.Embedding> embeddings,
            List<TextSegment> segments) {
        try {
            ns.store().addAll(ids, embeddings, segments);
            return FederatedStoreWriteStatus.SUCCEEDED;
        } catch (dev.langchain4j.exception.UnsupportedFeatureException uf) {
            log.debug("Federated addAll unsupported ids path store={}", safeStoreId(ns.id()));
            // Anonymous writes cannot satisfy the caller-owned ID contract.
            return FederatedStoreWriteStatus.FAILED;
        } catch (Exception e) {
            log.warn("Federated addAll failed on store {}. errorHash={} errorLength={}",
                    safeStoreId(ns.id()), SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return FederatedStoreWriteStatus.FAILED;
        }
    }

    private record SearchTask(String storeId,
                              int requestedK,
                              long startedNanos,
                              WorkerLease lease,
                              Future<SearchWorkerResult> future) {
    }

    private record SearchWorkerResult(EmbeddingSearchResult<TextSegment> result,
                                      long completedNanos) {
    }

    private record WriteTask(String storeId,
                             WorkerLease lease,
                             Future<WriteWorkerResult> future) {
    }

    private record WriteWorkerResult(FederatedStoreWriteStatus status,
                                     long completedNanos) {
    }

    private static final class WorkerLease {
        private enum State {
            PENDING,
            RUNNING,
            FINISHED,
            CANCELLED_BEFORE_START
        }

        private final Semaphore admission;
        private State state = State.PENDING;

        private WorkerLease(Semaphore admission) {
            this.admission = admission;
        }

        private synchronized boolean begin() {
            if (state != State.PENDING) {
                return false;
            }
            state = State.RUNNING;
            return true;
        }

        private synchronized void finish() {
            if (state == State.RUNNING) {
                state = State.FINISHED;
                admission.release();
            }
        }

        private synchronized boolean cancelBeforeStart() {
            if (state != State.PENDING) {
                return false;
            }
            state = State.CANCELLED_BEFORE_START;
            admission.release();
            return true;
        }

        private synchronized boolean isRunning() {
            return state == State.RUNNING;
        }
    }

    private Map<String, Double> weightsMatchingStores(Map<String, Double> configuredWeights) {
        if (configuredWeights == null || configuredWeights.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> ids = stores.stream().map(NamedStore::id).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : configuredWeights.entrySet()) {
            if (e.getKey() != null && ids.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue() == null ? 0.0d : e.getValue());
            }
        }
        return out;
    }

    private Set<String> unmatchedWeightKeys(Map<String, Double> configuredWeights) {
        if (configuredWeights == null || configuredWeights.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> ids = stores.stream().map(NamedStore::id).collect(Collectors.toSet());
        Set<String> out = new LinkedHashSet<>();
        for (String key : configuredWeights.keySet()) {
            if (key != null && !ids.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private Map<String, Integer> allocateK(Map<String, Double> weights, int k, int minPerStore) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (weights == null || weights.isEmpty()) {
            int each = Math.max(minPerStore, k / Math.max(1, stores.size()));
            for (NamedStore ns : stores) {
                out.put(ns.id(), each);
            }
        } else {
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            int assigned = 0;
            for (NamedStore ns : stores) {
                double w = weights.getOrDefault(ns.id(), 0.0);
                int v = (int) Math.round(k * (total > 0 ? (w / total) : 0));
                out.put(ns.id(), v);
                assigned += v;
            }
            while (assigned < k) {
                String best = bestStoreByWeight(weights);
                out.put(best, out.getOrDefault(best, 0) + 1);
                assigned++;
            }
            while (assigned > k) {
                String worst = worstAllocatedStore(out, weights, 0);
                int cur = out.getOrDefault(worst, 0);
                if (cur > 0) {
                    out.put(worst, cur - 1);
                    assigned--;
                } else {
                    break;
                }
            }
        }
        if (minPerStore > 0) {
            int sum = out.values().stream().mapToInt(Integer::intValue).sum();
            for (NamedStore ns : stores) {
                out.put(ns.id(), Math.max(minPerStore, out.getOrDefault(ns.id(), 0)));
            }
            sum = out.values().stream().mapToInt(Integer::intValue).sum();
            while (sum > k) {
                String target = worstAllocatedStore(out, weights, minPerStore);
                int cur = out.getOrDefault(target, 0);
                if (cur > minPerStore) {
                    out.put(target, cur - 1);
                    sum--;
                } else {
                    Optional<String> alt = out.entrySet().stream().filter(e -> e.getValue() > minPerStore).map(Map.Entry::getKey).findFirst();
                    if (alt.isPresent()) {
                        out.put(alt.get(), out.get(alt.get()) - 1);
                        sum--;
                    } else {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private String bestStoreByWeight(Map<String, Double> weights) {
        return stores.stream()
                .map(NamedStore::id)
                .max(Comparator.comparingDouble(id -> weights.getOrDefault(id, 0.0d)))
                .orElse(stores.isEmpty() ? "default" : stores.get(0).id());
    }

    private String worstAllocatedStore(Map<String, Integer> allocated, Map<String, Double> weights, int floor) {
        return stores.stream()
                .map(NamedStore::id)
                .filter(id -> allocated.getOrDefault(id, 0) > floor)
                .min(Comparator.comparingDouble(id -> weights.getOrDefault(id, 0.0d)))
                .orElse(stores.isEmpty() ? "default" : stores.get(0).id());
    }

    private static String keyOf(TextSegment seg) {
        if (seg == null) return "";
        String text = seg.text();
        String source = "";
        try {
            if (seg.metadata() != null) {
                source = Optional.ofNullable(seg.metadata().getString("source")).orElse("");
            }
        } catch (Exception ignored) {
            logSuppressed("dedup.metadata", ignored);
        }
        return Integer.toHexString(Objects.hash(text, source));
    }
    /**
     * Safely extracts the value of a key (e.g., "topic") from a simple ComparisonFilter.
     * This helper method avoids ClassCastExceptions and handles nulls gracefully.
     * NOTE: It currently supports simple "key = value" filters and not complex logical operators.
     * @param filter The filter to inspect.
     * @return An Optional containing the value if found, otherwise empty.
     */
    /**
     * [HIGH-RISK] Extracts a metadata value from a LangChain4j 1.0.1 Filter object by parsing its toString() representation.
     * This is a brittle workaround due to the lack of introspection APIs for Filter objects in this specific library version.
     * It assumes a filter created like: {@code metadataKey("topic").isEqualTo("some-value")}.
     *
     * @param filter The Filter object to inspect.
     * @return An Optional containing the value for the 'topic' key if found.
     */
    private Optional<String> extractTopicFromFilter(Filter filter) {
        if (filter == null) {
            return Optional.empty();
        }

        // This regex pattern is designed to match the typical toString() output of a simple metadata filter in LangChain4j v1.0.1.
        // Example: "MetadataFilter { key = 'topic', condition = EQUAL_TO, value = 'some-value' }"
        Pattern pattern = Pattern.compile("key\\s*=\\s*'topic'\\s*,\\s*.*?value\\s*=\\s*'(.*?)'");
        Matcher matcher = pattern.matcher(filter.toString());

        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }

    private boolean isAllZeroVector(float[] v) {
        if (v == null || v.length == 0) {
            return true;
        }
        for (float x : v) {
            if (x != 0.0f) {
                return false;
            }
        }
        return true;
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void logSuppressed(String stage, Throwable ignored) {
        if (log.isDebugEnabled()) {
            log.debug("[vector-federated] suppressed stage={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        }
    }

    private static String safeTopic(String topic) {
        return SafeRedactor.traceLabelOrFallback(topic, "default");
    }

    private static String safeStoreId(String storeId) {
        return SafeRedactor.traceLabelOrFallback(storeId, "store");
    }

    private static List<String> safeStoreIds(Collection<String> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        return storeIds.stream().map(FederatedEmbeddingStore::safeStoreId).toList();
    }

    private static <N extends Number> Map<String, N> safeSplit(Map<String, N> split) {
        if (split == null || split.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, N> out = new LinkedHashMap<>();
        for (Map.Entry<String, N> entry : split.entrySet()) {
            if (entry == null) {
                continue;
            }
            out.put(safeStoreId(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static void trace(String key, Object value) {
        try {
            TraceStore.put(key, value);
        } catch (Exception ignore) {
            logSuppressed("trace.put", ignore);
        }
    }
}
