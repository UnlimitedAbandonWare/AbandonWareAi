package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLlmDesktopGpuProfileTest {

    @Test
    @SuppressWarnings("unchecked")
    void desktopGpuProfileExposesOptionalCudaUuidPin() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("main/resources/application-desktop-gpu-node.yml"))) {
            Map<String, Object> root = new Yaml().load(input);
            Map<String, Object> localLlm = (Map<String, Object>) root.get("local-llm");

            assertThat(localLlm).containsEntry("cuda-visible-device", "${LOCAL_LLM_CUDA_VISIBLE_DEVICE:}");
        }
    }
}
