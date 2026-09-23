package ai.abandonware.nova.boot.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.example.lms.search.TraceStore;
import com.example.lms.service.embedding.MatryoshkaAware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatryoshkaEmbeddingModelPostProcessorTest {

    @Test
    void normalizerDiagnosticsDoNotWriteRawTags() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingNormalizer.java"));

        assertFalse(source.contains("tag={}\""));
        assertFalse(source.contains("tag + \")\""));
        assertFalse(source.contains("embed.matryoshka.empty.tag\", tag"));
        assertFalse(source.contains("embed.matryoshka.slice.tag\", tag"));
        assertFalse(source.contains("embed.matryoshka.pad.tag\", tag"));
        assertTrue(source.contains("tagHash"));
        assertTrue(source.contains("tagLength"));
        assertTrue(source.contains("SafeRedactor.hashValue(tag)"));
    }

    @Test
    void normalizerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingNormalizer.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Matryoshka embedding normalizer needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void normalizerSuppressedHelperPublishesTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingNormalizer.java"));

        assertTrue(source.contains("embed.matryoshka.suppressed.count"));
        assertTrue(source.contains("embed.matryoshka.suppressed.stage"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
    }

    @Test
    void postProcessorIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingModelPostProcessor.java"))
                .replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(t);";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "Matryoshka post-processor integer parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Throwable"),
                "Matryoshka post-processor parser fallback must not swallow Throwable");
        assertTrue(window.contains("catch (NumberFormatException"),
                "Matryoshka post-processor parser fallback should catch only NumberFormatException");
    }

    @Test
    void defaultAllowListDoesNotWrapPrimaryEmbeddingModelAgain() {
        MatryoshkaEmbeddingModelPostProcessor postProcessor =
                new MatryoshkaEmbeddingModelPostProcessor(new MockEnvironment()
                        .withProperty("embedding.dimensions", "1536"));
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[] {1.0f, 2.0f}));
            }

            @Override
            public Response<Embedding> embed(TextSegment textSegment) {
                return embed(textSegment == null ? "" : textSegment.text());
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                return Response.from(List.of(Embedding.from(new float[] {1.0f, 2.0f})));
            }
        };

        Object result = postProcessor.postProcessAfterInitialization(model, "embeddingModel");

        assertSame(model, result);
    }

    @Test
    void allowlistedMatryoshkaAwareEmbeddingModelIsNotWrappedAgain() {
        MatryoshkaEmbeddingModelPostProcessor postProcessor =
                new MatryoshkaEmbeddingModelPostProcessor(new MockEnvironment()
                        .withProperty("embedding.dimensions", "1536")
                        .withProperty("nova.orch.embedding.matryoshka-shield.allow-names", "ollamaEmbeddingModel"));
        EmbeddingModel model = new MatryoshkaAwareEmbeddingModel(1536);

        Object result = postProcessor.postProcessAfterInitialization(model, "ollamaEmbeddingModel");

        assertSame(model, result);
    }

    @Test
    void invalidEmbeddingDimensionRecordsParseSuppressionBreadcrumb() {
        TraceStore.clear();
        MatryoshkaEmbeddingModelPostProcessor postProcessor =
                new MatryoshkaEmbeddingModelPostProcessor(new MockEnvironment()
                        .withProperty("embedding.dimensions", "not-a-number")
                        .withProperty("nova.orch.embedding.matryoshka-shield.allow-names", "embeddingModel"));
        EmbeddingModel model = new FixedEmbeddingModel(4);

        Object result = postProcessor.postProcessAfterInitialization(model, "embeddingModel");

        assertSame(model, result);
        assertEquals("embedding.dimensions",
                TraceStore.get("embed.matryoshka.config.parse.suppressed.stage"));
        assertEquals("invalid_number",
                TraceStore.get("embed.matryoshka.config.parse.suppressed.errorType"));
        TraceStore.clear();
    }

    @Test
    void normalizerSlicesMatryoshkaVectorToConfiguredDimension() {
        MatryoshkaEmbeddingNormalizer normalizer = new MatryoshkaEmbeddingNormalizer(new FixedEmbeddingModel(4), 2);

        Embedding embedding = normalizer.embed("ping").content();

        assertEquals(2, embedding.vector().length);
        assertEquals(1.0f, embedding.vector()[0]);
        assertEquals(2.0f, embedding.vector()[1]);
    }

    @Test
    void normalizerRecordsStandardDimensionTraceWhenSlicing() {
        TraceStore.clear();
        MatryoshkaEmbeddingNormalizer normalizer = new MatryoshkaEmbeddingNormalizer(new FixedEmbeddingModel(4), 2);

        Embedding embedding = normalizer.embed("ping").content();

        assertEquals(2, embedding.vector().length);
        assertEquals(4, TraceStore.get("embed.sourceDim"));
        assertEquals(2, TraceStore.get("embed.targetDim"));
        assertEquals("MRL_PREFIX", TraceStore.get("embed.sliceMethod"));
        assertEquals(Boolean.TRUE, TraceStore.get("embed.normalizeApplied"));
        assertEquals("MRL", TraceStore.get("embed.sliceReason"));
        assertEquals(0.5d, TraceStore.get("embed.matryoshka.slice.reductionRatio"));
        assertEquals(0.5d, TraceStore.get("embed.matryoshka.slice.expectedDistanceOpsRatio"));
        assertEquals(2.0d, TraceStore.get("embed.matryoshka.slice.expectedDistanceOpsSpeedup"));
        TraceStore.clear();
    }

    @Test
    void normalizerRecordsDesignClaimReductionFor4096To1536Slice() {
        TraceStore.clear();
        MatryoshkaEmbeddingNormalizer normalizer =
                new MatryoshkaEmbeddingNormalizer(new FixedEmbeddingModel(4096), 1536);

        Embedding embedding = normalizer.embed("ping").content();

        assertEquals(1536, embedding.vector().length);
        assertEquals(4096, TraceStore.get("embed.sourceDim"));
        assertEquals(1536, TraceStore.get("embed.targetDim"));
        assertEquals("MRL_PREFIX", TraceStore.get("embed.sliceMethod"));
        assertEquals("MRL", TraceStore.get("embed.sliceReason"));
        assertEquals(4096, TraceStore.get("embed.matryoshka.slice.actual"));
        assertEquals(1536, TraceStore.get("embed.matryoshka.slice.target"));
        assertEquals(0.625d, TraceStore.get("embed.matryoshka.slice.reductionRatio"));
        assertEquals(0.375d, TraceStore.get("embed.matryoshka.slice.expectedDistanceOpsRatio"));
        assertEquals(2.6667d, TraceStore.get("embed.matryoshka.slice.expectedDistanceOpsSpeedup"));
        assertEquals(4096, TraceStore.get("embedding.rawDimension"));
        assertEquals(1536, TraceStore.get("embedding.slicedDimension"));
        assertEquals(Boolean.TRUE, TraceStore.get("embedding.matryoshkaSliced"));
        assertEquals("", TraceStore.get("embedding.sliceSkipReason"));
        TraceStore.clear();
    }

    @Test
    void normalizerRejectsEmptyProviderVector() {
        MatryoshkaEmbeddingNormalizer normalizer = new MatryoshkaEmbeddingNormalizer(new FixedEmbeddingModel(0), 1536);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> normalizer.embed("ping"));

        assertFalse(ex.getMessage().contains("query"));
        assertEquals(0, TraceStore.get("embedding.rawDimension"));
        assertEquals(1536, TraceStore.get("embedding.slicedDimension"));
        assertEquals(Boolean.FALSE, TraceStore.get("embedding.matryoshkaSliced"));
        assertEquals("empty_vector", TraceStore.get("embedding.sliceSkipReason"));
    }

    private static class FixedEmbeddingModel implements EmbeddingModel {
        private final int dimension;

        private FixedEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Response<Embedding> embed(String text) {
            float[] vector = new float[dimension];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = i + 1.0f;
            }
            return Response.from(Embedding.from(vector));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return embed(textSegment == null ? "" : textSegment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return Response.from(List.of(embed("").content()));
        }
    }

    private static final class MatryoshkaAwareEmbeddingModel extends FixedEmbeddingModel implements MatryoshkaAware {
        private MatryoshkaAwareEmbeddingModel(int dimension) {
            super(dimension);
        }

        @Override
        public int indexDimensions() {
            return 1536;
        }
    }
}
