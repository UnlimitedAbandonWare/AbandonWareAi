package ai.abandonware.nova.boot.embedding;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
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
 * This decorator is intentionally defensive and avoids throwing on dimension mismatches.
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
            return new float[0];
        }
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
        } catch (Throwable ignore) {
        }

        if (actual == targetDim) {
            return v;
        }

        if (actual <= 0) {
            // Empty vector: pad to target to avoid downstream dimension mismatch explosions.
            try {
                TraceStore.inc("embed.matryoshka.pad.empty.count");
                TraceStore.put("embed.matryoshka.pad.empty.tag", tag);
            } catch (Throwable ignore) {
            }
            return new float[targetDim];
        }

        if (actual > targetDim) {
            // Matryoshka slice (expected): truncate.
            if (SLICE_INFO_ONCE.compareAndSet(false, true)) {
                log.info("[EMBED_TRACE] matryoshka slice applied: actual={} target={} tag={}{}",
                        actual, targetDim, tag, LogCorrelation.suffix());
            }
            try {
                TraceStore.inc("embed.matryoshka.slice.count");
                TraceStore.put("embed.matryoshka.slice.actual", actual);
                TraceStore.put("embed.matryoshka.slice.target", targetDim);
                TraceStore.put("embed.matryoshka.slice.tag", tag);
            } catch (Throwable ignore) {
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
            TraceStore.put("embed.matryoshka.pad.tag", tag);
        } catch (Throwable ignore) {
        }
        float[] out = new float[targetDim];
        System.arraycopy(v, 0, out, 0, actual);
        return out;
    }
}
