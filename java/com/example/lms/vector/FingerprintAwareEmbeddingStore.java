package com.example.lms.vector;

import com.example.lms.search.TraceStore;
import com.example.lms.service.soak.metrics.SoakMetricRegistry;
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
import java.util.Collections;
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
    private final EmbeddingStore<TextSegment> writerStore;
    private final SoakMetricRegistry metricRegistry;

    private static final long FAIL_SOFT_WARN_INTERVAL_MS = 60_000L;
    private final AtomicLong lastFailSoftWarnAtMs = new AtomicLong(0L);

    private static final long NO_FP_BYPASS_WARN_INTERVAL_MS = 300_000L;
    private final AtomicLong lastNoFpBypassWarnAtMs = new AtomicLong(0L);

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
        this.writerStore = writerStore;
        this.metricRegistry = metricRegistry;
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
                // fail-soft
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

        if (kept.isEmpty() && dropped > 0 && !rawMatches.isEmpty()) {
            // Fail-soft: when fingerprint filtering drops everything, fall back to a dominant fingerprint (or raw top-k)
            int topK = Math.max(1, request.maxResults());

            Map<String, Long> fpCounts = new LinkedHashMap<>();
            String gotFpSample = null;

            for (EmbeddingMatch<TextSegment> m : rawMatches) {
                if (m == null || m.embedded() == null) continue;
                String fp = null;
                try {
                    Metadata md = m.embedded().metadata();
                    if (md != null) {
                        Object v = md.toMap().get(EmbeddingFingerprint.META_EMB_FP);
                        fp = (v == null) ? null : String.valueOf(v);
                    }
                } catch (Exception ignored) {
                    fp = null;
                }
                if (fp != null && !fp.isBlank()) {
                    String fpTrim = fp.trim();
                    fpCounts.put(fpTrim, fpCounts.getOrDefault(fpTrim, 0L) + 1L);
                    if (gotFpSample == null) {
                        gotFpSample = fpTrim;
                    }
                }
            }

            // If the backing store does not preserve metadata, fingerprints may be missing for all matches.
            // In that case fingerprint filtering is impossible; optionally bypass to legacy mode and avoid warn spam.
            if (fpCounts.isEmpty()) {
                // Empty + Writer fallback (quality first)
                TraceStore.put("vector.fp.bypassed", true);
                TraceStore.put("vector.fp.want", want);

                // warn rate-limit (1/min) to avoid log spam
                long now = System.currentTimeMillis();
                long last = lastNoFpBypassWarnAtMs.get();
                if (now - last > NO_FP_BYPASS_WARN_INTERVAL_MS && lastNoFpBypassWarnAtMs.compareAndSet(last, now)) {
                    log.warn("[VectorFP] No fp metadata. Falling back to writer. want='{}'", want);
                }

                if (metricRegistry != null) {
                    metricRegistry.incFpFilterLegacyBypass();
                }

                if (writerStore != null) {
                    return writerStore.search(request);
                }
                return new EmbeddingSearchResult<>(Collections.emptyList());
            }

            String dominantFp = null;
            long best = -1L;
            for (Map.Entry<String, Long> e : fpCounts.entrySet()) {
                long count = e.getValue() == null ? 0L : e.getValue();
                if (count > best) {
                    best = count;
                    dominantFp = e.getKey();
                }
            }

            if (dominantFp != null) {
                for (EmbeddingMatch<TextSegment> m : rawMatches) {
                    if (kept.size() >= topK) break;
                    if (m == null || m.embedded() == null) continue;

                    String fp = null;
                    try {
                        Metadata md = m.embedded().metadata();
                        if (md != null) {
                            Object v = md.toMap().get(EmbeddingFingerprint.META_EMB_FP);
                            fp = (v == null) ? null : String.valueOf(v);
                        }
                    } catch (Exception ignored) {
                        fp = null;
                    }

                    if (fp != null && Objects.equals(dominantFp, fp.trim())) {
                        kept.add(m);
                    }
                }
            }

            if (kept.isEmpty()) {
                // As a last resort, return the raw top-k (skipping nulls)
                for (EmbeddingMatch<TextSegment> m : rawMatches) {
                    if (kept.size() >= topK) break;
                    if (m == null || m.embedded() == null) continue;
                    kept.add(m);
                }
            }

            warnFailSoft(dominantFp, want, gotFpSample);
        } else if (kept.isEmpty() && dropped > 0) {
            log.warn("[VectorFP] All vector matches were filtered out by embedding fingerprint. want='{}'. " +
                            "This usually means the vector store contains embeddings from a different model/provider. " +
                            "Re-ingest/re-index under the current embedding config, or set vector.fingerprint.allow-legacy=true (NOT recommended).",
                    want);
        }

        return new EmbeddingSearchResult<>(kept);
    }


    private void warnFailSoft(String dominantFp, String want, String gotSample) {
        long now = System.currentTimeMillis();
        long last = lastFailSoftWarnAtMs.get();
        if (now - last > FAIL_SOFT_WARN_INTERVAL_MS && lastFailSoftWarnAtMs.compareAndSet(last, now)) {
            log.warn("[VectorFP] fail-soft: using dominant fp='{}' or raw top-k. want='{}', gotSample='{}'",
                    dominantFp, want, gotSample);
        } else {
            log.debug("[VectorFP] fail-soft: using dominant fp='{}' or raw top-k. want='{}', gotSample='{}'",
                    dominantFp, want, gotSample);
        }
    }

    private TextSegment stamp(TextSegment seg) {
        Map<String, Object> base = new LinkedHashMap<>();
        String text = "";

        if (seg != null) {
            try {
                text = seg.text() == null ? "" : seg.text();
            } catch (Exception ignored) {
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
                // Metadata.toMap should be safe, but keep this fail-soft.
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
