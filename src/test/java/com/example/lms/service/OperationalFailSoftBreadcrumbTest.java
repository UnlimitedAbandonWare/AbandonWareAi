package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalFailSoftBreadcrumbTest {

    @Test
    void ocrOnnxTokenizerAndOpsFallbacksLeaveStageBreadcrumbs() throws Exception {
        String ocr = read("main/java/com/example/lms/service/ocr/BasicTesseractOcrService.java");
        String tokenizer = read("main/java/com/example/lms/service/onnx/tokenizer/WordpieceTokenizer.java");
        String onnx = read("main/java/com/example/lms/service/onnx/OnnxRuntimeService.java");
        String ops = read("main/java/com/example/lms/service/ops/RagOpsLedgerService.java");

        assertSystemStage(ocr, "BasicTesseractOcrService", "extract");
        assertSystemStage(tokenizer, "WordpieceTokenizer", "loadVocab");

        assertSlf4jStage(onnx, "ONNX", "session.create");
        assertSlf4jStage(onnx, "ONNX", "open");
        assertSlf4jStage(onnx, "ONNX", "encodePair");
        assertSlf4jStage(onnx, "ONNX", "score.inference");

        assertOpsStage(ops, "refreshBlackboxMatrixIfMissing");
        assertOpsStage(ops, "countQuietly");
        assertOpsStage(ops, "toJson");
        assertOpsStage(ops, "fromJsonMap");
        assertOpsStage(ops, "asDouble");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void assertSystemStage(String source, String component, String stage) {
        assertTrue(source.contains("[" + component + "] fail-soft stage={0}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }

    private static void assertSlf4jStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }

    private static void assertOpsStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[AWX2AF2][ops-ledger] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing ops-ledger fail-soft stage: " + stage);
    }
}
