package ai.abandonware.nova.boot.embedding;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Normalizes embedding vectors into a configured target dimension.
 *
 * <p>
 * Motivation:
 * <ul>
 *   <li>Some embedding providers (e.g., Matryoshka-style) return larger vectors than configured, and slicing is expected.</li>
 *   <li>Occasionally providers return empty (0-dim) vectors; we pad to the configured dimension to keep downstream fail-soft.</li>
 * </ul>
 *
 * <p>
 * This decorator is intentionally defensive for dimension mismatches, but empty
 * provider output is treated as an upstream failure instead of a valid vector.
 */
public final class MatryoshkaEmbeddingNormalizer implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(MatryoshkaEmbeddingNormalizer.class);

    private static final AtomicBoolean SLICE_INFO_ONCE = new AtomicBoolean(false);

    private final EmbeddingModel delegate;
    private final int targetDim;

    public MatryoshkaEmbeddingNormalizer(EmbeddingModel delegate, int targetDim) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.targetDim = targetDim;
    }

    @Override
    public Response<Embedding> embed(String text) {
        Response<Embedding> r = delegate.embed(text);
        Embedding e = (r == null) ? null : r.content();
        float[] vec = (e == null) ? new float[0] : safeVector(e);
        float[] norm = normalize(vec, "query");
        return Response.from(Embedding.from(norm));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        Response<Embedding> r = delegate.embed(textSegment);
        Embedding e = (r == null) ? null : r.content();
        float[] vec = (e == null) ? new float[0] : safeVector(e);
        float[] norm = normalize(vec, "segment");
        return Response.from(Embedding.from(norm));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        Response<List<Embedding>> r = delegate.embedAll(textSegments);
        List<Embedding> in = (r == null) ? null : r.content();
        if (in == null || in.isEmpty()) {
            return Response.from(List.of());
        }
        List<Embedding> out = new ArrayList<>(in.size());
        for (Embedding e : in) {
            float[] vec = (e == null) ? new float[0] : safeVector(e);
            out.add(Embedding.from(normalize(vec, "batch")));
        }
        return Response.from(out);
    }

    private float[] safeVector(Embedding e) {
        try {
            float[] v = e.vector();
            return (v == null) ? new float[0] : v;
        } catch (Throwable ignore) {
            traceSuppressed("vector.read");
            return new float[0];
        }
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private float[] normalize(float[] raw, String tag) {
        if (targetDim <= 0) {
            return (raw == null) ? new float[0] : raw;
        }

        float[] v = (raw == null) ? new float[0] : raw;
        int actual = v.length;

        try {
            TraceStore.put("embed.targetDim", targetDim);
            TraceStore.put("embed.actualDim", actual);
            TraceStore.put("embed.sourceDim", actual);
            TraceStore.put("embed.sliceMethod", sliceMethod(actual));
            TraceStore.put("embed.normalizeApplied", actual != targetDim);
            TraceStore.put("embed.sliceReason", sliceReason(actual));
            TraceStore.put("embedding.rawDimension", actual);
            TraceStore.put("embedding.slicedDimension", targetDim);
            TraceStore.put("embedding.matryoshkaSliced", actual > targetDim);
            TraceStore.put("embedding.sliceSkipReason", actual <= 0 ? "empty_vector" : "");
        } catch (Throwable ignore) {
            traceSuppressed("trace.dimensions");
        }

        if (actual == targetDim) {
            return v;
        }

        if (actual <= 0) {
            try {
                TraceStore.inc("embed.matryoshka.empty.count");
                TraceStore.put("embed.matryoshka.empty.tagHash", SafeRedactor.hashValue(tag));
                TraceStore.put("embed.matryoshka.empty.tagLength", lengthOf(tag));
            } catch (Throwable ignore) {
                traceSuppressed("trace.empty");
            }
            throw new IllegalStateException("Embedding provider returned an empty vector");
        }

        if (actual > targetDim) {
            // Matryoshka slice (expected): truncate.
            if (SLICE_INFO_ONCE.compareAndSet(false, true)) {
                log.info("[EMBED_TRACE] matryoshka slice applied: actual={} target={} tagHash={} tagLength={}{}",
                        actual, targetDim, SafeRedactor.hashValue(tag), lengthOf(tag), LogCorrelation.suffix());
            }
            try {
                TraceStore.inc("embed.matryoshka.slice.count");
                TraceStore.put("embed.matryoshka.slice.actual", actual);
                TraceStore.put("embed.matryoshka.slice.target", targetDim);
                TraceStore.put("embed.matryoshka.slice.reductionRatio", reductionRatio(actual, targetDim));
                TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsRatio",
                        expectedDistanceOpsRatio(actual, targetDim));
                TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup",
                        expectedDistanceOpsSpeedup(actual, targetDim));
                TraceStore.put("embed.matryoshka.slice.tagHash", SafeRedactor.hashValue(tag));
                TraceStore.put("embed.matryoshka.slice.tagLength", lengthOf(tag));
            } catch (Throwable ignore) {
                traceSuppressed("trace.slice");
            }
            float[] out = new float[targetDim];
            System.arraycopy(v, 0, out, 0, targetDim);
            return out;
        }

        // actual < targetDim: pad with zeros.
        try {
            TraceStore.inc("embed.matryoshka.pad.count");
            TraceStore.put("embed.matryoshka.pad.actual", actual);
            TraceStore.put("embed.matryoshka.pad.target", targetDim);
            TraceStore.put("embed.matryoshka.pad.tagHash", SafeRedactor.hashValue(tag));
            TraceStore.put("embed.matryoshka.pad.tagLength", lengthOf(tag));
        } catch (Throwable ignore) {
            traceSuppressed("trace.pad");
        }
        float[] out = new float[targetDim];
        System.arraycopy(v, 0, out, 0, actual);
        return out;
    }

    private static void traceSuppressed(String stage) {
        try {
            TraceStore.inc("embed.matryoshka.suppressed.count");
            TraceStore.put("embed.matryoshka.suppressed.stage",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        } catch (RuntimeException traceError) {
            log.debug("[EMBED_TRACE] suppressed breadcrumb failed: errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(traceError.getClass().getName())),
                    lengthOf(traceError.getMessage()));
        }
        log.debug("[EMBED_TRACE] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }

    private String sliceMethod(int actual) {
        if (actual > targetDim) {
            return "MRL_PREFIX";
        }
        if (actual == targetDim) {
            return "NONE";
        }
        return "ZERO_PAD";
    }

    private String sliceReason(int actual) {
        if (actual > targetDim) {
            return "MRL";
        }
        if (actual == targetDim) {
            return "NONE";
        }
        if (actual <= 0) {
            return "EMPTY";
        }
        return "FALLBACK";
    }

    private static double reductionRatio(int actual, int target) {
        if (actual <= 0 || target <= 0 || actual <= target) {
            return 0.0d;
        }
        double value = 1.0d - (target / (double) actual);
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private static double expectedDistanceOpsRatio(int actual, int target) {
        if (actual <= 0 || target <= 0 || actual <= target) {
            return 1.0d;
        }
        return round4(target / (double) actual);
    }

    private static double expectedDistanceOpsSpeedup(int actual, int target) {
        if (actual <= 0 || target <= 0 || actual <= target) {
            return 1.0d;
        }
        return round4(actual / (double) target);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
