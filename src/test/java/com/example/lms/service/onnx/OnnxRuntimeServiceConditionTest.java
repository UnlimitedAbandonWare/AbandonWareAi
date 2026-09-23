package com.example.lms.service.onnx;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxRuntimeServiceConditionTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OnnxRuntimeService.class);

    @Test
    void backendAloneDoesNotCreateOnnxRuntimeService() {
        contextRunner
                .withPropertyValues("abandonware.reranker.backend=onnx-runtime")
                .run(context -> assertThat(context).doesNotHaveBean(OnnxRuntimeService.class));
    }

    @Test
    void runtimeEnabledCreatesServiceWithoutLoadingModelWhenPathMissing() {
        contextRunner
                .withPropertyValues(
                        "abandonware.reranker.backend=onnx-runtime",
                        "abandonware.reranker.onnx.runtime-enabled=true",
                        "abandonware.reranker.onnx.execution-provider=cpu")
                .run(context -> {
                    assertThat(context).hasSingleBean(OnnxRuntimeService.class);
                    assertThat(context.getBean(OnnxRuntimeService.class).available()).isFalse();
                });
    }

    @Test
    void placeholderModelDisablesRuntimeWithTraceReasonWhenFallbackEnabled() {
        TraceStore.clear();
        contextRunner
                .withPropertyValues(
                        "abandonware.reranker.backend=onnx-runtime",
                        "abandonware.reranker.onnx.runtime-enabled=true",
                        "abandonware.reranker.onnx.execution-provider=cpu",
                        "abandonware.reranker.onnx.model-path=classpath:models/your-cross-encoder.onnx",
                        "abandonware.reranker.onnx.fallback-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OnnxRuntimeService.class);
                    assertThat(context.getBean(OnnxRuntimeService.class).available()).isFalse();
                    assertThat(context.getBean(OnnxRuntimeService.class).getDisabledReason())
                            .isEqualTo("placeholder_or_too_small_model");
                    assertThat(TraceStore.get("rerank.onnx.disabledReason"))
                            .isEqualTo("placeholder_or_too_small_model");
                    assertThat(TraceStore.get("onnx.status")).isEqualTo("disabled");
                    assertThat(TraceStore.get("onnx.disabledReason"))
                            .isEqualTo("placeholder_or_too_small_model");
                });
        TraceStore.clear();
    }

    @Test
    void placeholderModelFailsClosedWhenFallbackDisabled() {
        contextRunner
                .withPropertyValues(
                        "abandonware.reranker.backend=onnx-runtime",
                        "abandonware.reranker.onnx.runtime-enabled=true",
                        "abandonware.reranker.onnx.execution-provider=cpu",
                        "abandonware.reranker.onnx.model-path=classpath:models/your-cross-encoder.onnx",
                        "abandonware.reranker.onnx.fallback-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("ONNX placeholder_or_too_small_model and fallback disabled");
                });
    }

    @Test
    void placeholderPredicateRejectsTinyAndMarkerBodies() {
        assertThat(OnnxRuntimeService.isPlaceholderOrTooSmallModel("ONNXPLACEHOLDER".getBytes())).isTrue();
        assertThat(OnnxRuntimeService.isPlaceholderOrTooSmallModel(new byte[999_999])).isTrue();
        assertThat(OnnxRuntimeService.isPlaceholderOrTooSmallModel(new byte[1_000_000])).isFalse();
    }

    @Test
    void invalidLargeModelDisablesRuntimeWithRedactedSessionTraceWhenFallbackEnabled() throws Exception {
        TraceStore.clear();
        Path model = tempDir.resolve("invalid-large.onnx");
        byte[] invalidModel = new byte[1_000_000];
        invalidModel[0] = 8;
        Files.write(model, invalidModel);

        contextRunner
                .withPropertyValues(
                        "abandonware.reranker.backend=onnx-runtime",
                        "abandonware.reranker.onnx.runtime-enabled=true",
                        "abandonware.reranker.onnx.execution-provider=cpu",
                        "abandonware.reranker.onnx.model-path=" + model,
                        "abandonware.reranker.onnx.fallback-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OnnxRuntimeService.class);
                    assertThat(context.getBean(OnnxRuntimeService.class).available()).isFalse();
                    assertThat(TraceStore.get("rerank.onnx.ready")).isEqualTo(false);
                    assertThat(TraceStore.get("rerank.onnx.disabledReason"))
                            .isEqualTo("session_create_failed");
                    assertThat(TraceStore.get("rerank.onnx.sessionFailed")).isEqualTo(true);
                    assertThat(TraceStore.get("rerank.onnx.sessionFailureClass")).isNotNull();
                    assertThat(TraceStore.get("rerank.onnx.modelBytes")).isEqualTo(1_000_000);
                    assertThat(String.valueOf(TraceStore.get("rerank.onnx.disabledReason")))
                            .doesNotContain(model.toString());
                });
        TraceStore.clear();
    }

    @Test
    void disabledReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/onnx/OnnxRuntimeService.java"));

        assertThat(source).doesNotContain("TraceStore.put(\"rerank.onnx.disabledReason\", reason);");
        assertThat(source).doesNotContain("TraceStore.put(\"rerank.onnx.disabledReason\", SafeRedactor.safeMessage(reason, 120));");
        assertThat(source).contains("this.disabledReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");");
        assertThat(source).contains("TraceStore.put(\"rerank.onnx.disabledReason\", this.disabledReason);");
        assertThat(source).contains("return SafeRedactor.traceLabelOrFallback(disabledReason, \"\");");
    }

    @Test
    void initializationFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/onnx/OnnxRuntimeService.java"));

        assertThat(source).doesNotContain("reason={}\",\n                    fallbackEnabled, t.toString())");
        assertThat(source).doesNotContain("SafeRedactor.safeMessage(String.valueOf(t), 180)");
        assertThat(source).contains("provider={} action=cpu_fallback errorHash={} errorLength={}");
        assertThat(source).contains("action=initialisation_failed fallbackEnabled={} errorHash={} errorLength={}");
        assertThat(source).contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)");
    }

    @Test
    void scorePairInferenceFallbackLeavesRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/onnx/OnnxRuntimeService.java"));

        assertThat(source).doesNotContain("catch (Throwable ignore) {\n                // ignore and fall back\n            }");
        assertThat(source).contains("traceInferenceFallback(t, query);");
        assertThat(source).contains("TraceStore.put(\"rerank.onnx.inferenceFailed\", true);");
        assertThat(source).contains("TraceStore.put(\"rerank.onnx.inferenceFallback\", \"lexical_inversion\");");
        assertThat(source).contains(
                "TraceStore.put(\"rerank.onnx.inferenceFailureClass\", SafeRedactor.traceLabelOrFallback(");
        assertThat(source).contains("TraceStore.put(\"rerank.onnx.queryHash12\", SafeRedactor.hash12(query));");
        assertThat(source).contains("TraceStore.put(\"rerank.onnx.queryLength\", query == null ? 0 : query.length());");
        assertThat(source).doesNotContain("TraceStore.put(\"rerank.onnx.inferenceFailureMessage\"");
    }
}
