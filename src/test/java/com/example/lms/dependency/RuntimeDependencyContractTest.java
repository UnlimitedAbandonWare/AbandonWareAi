package com.example.lms.dependency;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDependencyContractTest {

    @Test
    void runtimeRagDependenciesAreCompileTimeContracts() {
        assertTrue(Driver.class.isInterface());
        assertTrue(ContentRetriever.class.isInterface());
        assertEquals("Query", Query.class.getSimpleName());
    }

    @Test
    void optionalNativeDependenciesStayFailSoftAndOutOfCompileClasspath() throws Exception {
        String onnx = Files.readString(Path.of("main/java/com/example/lms/service/onnx/OnnxRuntimeService.java"));
        String ocr = Files.readString(Path.of("main/java/com/abandonware/ai/service/ocr/BasicTesseractOcrService.java"));

        assertTrue(onnx.contains("onnxruntime_dependency_unavailable"));
        assertTrue(ocr.contains("tess4j_dependency_unavailable"));
        assertFalse(onnx.contains("import ai.onnxruntime"));
        assertFalse(ocr.contains("import net.sourceforge.tess4j"));
    }
}
