package com.example.lms.vector;

import com.example.lms.search.TraceStore;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator for {@link EmbeddingStore} that:
 * <ol>
 * <li>stamps every stored {@link TextSegment} with the current embedding
 * fingerprint metadata</li>
 * <li>filters retrieval results to only return segments matching the current
 * fingerprint</li>
 * </ol>
 *
 * <p>
 * This prevents cross-embedding-model contamination (e.g. an index built with
 * OpenAI embeddings
 * queried with Ollama embeddings) which otherwise produces seemingly random,
 * off-topic RAG context.
 * </p>
 */
public class FingerprintAwareEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(FingerprintAwareEmbeddingStore.class);

    private final EmbeddingStore<TextSegment> delegate;
    private final EmbeddingFingerprint fingerprint;

    private static final long FAIL_SOFT_WARN_INTERVAL_MS = 60_000L;
    private final AtomicLong lastFailSoftWarnAtMs = new AtomicLong(0L);


    public FingerprintAwareEmbeddingStore(EmbeddingStore<TextSegment> delegate, EmbeddingFingerprint fingerprint) {
        this(delegate, fingerprint, null, null);
    }

    public FingerprintAwareEmbeddingStore(
            EmbeddingStore<TextSegment> delegate,
            EmbeddingFingerprint fingerprint,
            EmbeddingStore<TextSegment> writerStore) {
        this(delegate, fingerprint, writerStore, null);
    }

    public FingerprintAwareEmbeddingStore(
            EmbeddingStore<TextSegment> delegate,
            EmbeddingFingerprint fingerprint,
            EmbeddingStore<TextSegment> writerStore,
            SoakMetricRegistry metricRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }


    @Override
    public String add(Embedding embedding) {
        return delegate.add(embedding);
    }

    @Override
    public void add(String id, Embedding embedding) {
        delegate.add(id, embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        return delegate.add(embedding, stamp(embedded));
    }

    // Note: add(String id, Embedding embedding, TextSegment embedded) is NOT part
    // of
    // the standard EmbeddingStore interface, so we do NOT override it here.
    // Use addAll(ids, embeddings, textSegments) instead if you need to add with
    // IDs.

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return delegate.addAll(embeddings);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        if (embedded == null || embedded.isEmpty()) {
            return delegate.addAll(embeddings, embedded);
        }
        List<TextSegment> stamped = new ArrayList<>(embedded.size());
        for (TextSegment s : embedded) {
            stamped.add(stamp(s));
        }
        return delegate.addAll(embeddings, stamped);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        if (embedded == null || embedded.isEmpty()) {
            delegate.addAll(ids, embeddings, embedded);
            return;
        }
        List<TextSegment> stamped = new ArrayList<>(embedded.size());
        for (TextSegment s : embedded) {
            stamped.add(stamp(s));
        }
        delegate.addAll(ids, embeddings, stamped);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        EmbeddingSearchResult<TextSegment> raw = delegate.search(request);
        if (raw == null || raw.matches() == null || raw.matches().isEmpty()) {
            return raw;
        }

        // Keep a stable reference to the raw matches list.
        final List<EmbeddingMatch<TextSegment>> rawMatches = raw.matches();

        String want = fingerprint.fingerprint();
        boolean allowLegacy = fingerprint.allowLegacy();

        List<EmbeddingMatch<TextSegment>> kept = new ArrayList<>();
        int legacy = 0;
        int dropped = 0;

        for (EmbeddingMatch<TextSegment> m : rawMatches) {
            if (m == null)
                continue;
            TextSegment seg = m.embedded();
            if (seg == null) {
                dropped++;
                continue;
            }

            String fp = null;
            try {
                Metadata md = seg.metadata();
                if (md != null) {
                    Map<String, Object> map = md.toMap();
                    if (map != null) {
                        Object v = map.get(EmbeddingFingerprint.META_EMB_FP);
                        fp = (v == null) ? null : String.valueOf(v);
                    }
                }
            } catch (Exception ignored) {
                log.debug("[AWX2AF2][vector][fingerprint] metadata read skipped errorType={}",
                        SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown"));
            }

            if (fp == null || fp.isBlank()) {
                if (allowLegacy) {
                    legacy++;
                    kept.add(m);
                } else {
                    dropped++;
                }
                continue;
            }

            if (Objects.equals(want, fp.trim())) {
                kept.add(m);
            } else {
                dropped++;
            }
        }

        if (!kept.isEmpty() && (dropped > 0 || legacy > 0)) {
            log.debug("[VectorFP] filtered matches: kept={}, dropped={}, legacyAllowed={}", kept.size(), dropped,
                    allowLegacy);
        }

        TraceStore.put("vector.fp.bypassed", false);
        TraceStore.put("vector.fp.dropped", dropped);
        TraceStore.put("vector.fp.blockedReason", kept.isEmpty() && dropped > 0 ? "embedding_space_unverified" : "none");
        if (kept.isEmpty() && dropped > 0) {
            TraceStore.put("vector.fp.wantHash", SafeRedactor.hashValue(want));
            TraceStore.put("vector.fp.wantLength", lengthOf(want));
            warnIncompatible(want, dropped);
        }

        return new EmbeddingSearchResult<>(kept);
    }


    private void warnIncompatible(String want, int dropped) {
        long now = System.currentTimeMillis();
        long last = lastFailSoftWarnAtMs.get();
        if (now - last > FAIL_SOFT_WARN_INTERVAL_MS && lastFailSoftWarnAtMs.compareAndSet(last, now)) {
            log.warn("[VectorFP] incompatible vector evidence omitted. wantHash={} wantLength={} dropped={}",
                    SafeRedactor.hashValue(want), lengthOf(want), dropped);
        }
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private TextSegment stamp(TextSegment seg) {
        Map<String, Object> base = new LinkedHashMap<>();
        String text = "";

        if (seg != null) {
            try {
                text = seg.text() == null ? "" : seg.text();
            } catch (Exception ignored) {
                log.debug("[AWX2AF2][vector][fingerprint] segment text read skipped");
                text = "";
            }

            try {
                if (seg.metadata() != null) {
                    Map<String, Object> m = seg.metadata().toMap();
                    if (m != null) {
                        base.putAll(m);
                    }
                }
            } catch (Exception ignored) {
                log.debug("[AWX2AF2][vector][fingerprint] stamp metadata read skipped");
            }
        }

        base.put(EmbeddingFingerprint.META_EMB_FP, fingerprint.fingerprint());
        base.put(EmbeddingFingerprint.META_EMB_ID, fingerprint.embId());
        base.put(EmbeddingFingerprint.META_EMB_PROVIDER, fingerprint.provider());
        base.put(EmbeddingFingerprint.META_EMB_MODEL, fingerprint.model());
        base.put(EmbeddingFingerprint.META_EMB_DIM, fingerprint.dimensions());

        return TextSegment.from(text, Metadata.from(base));
    }
}
