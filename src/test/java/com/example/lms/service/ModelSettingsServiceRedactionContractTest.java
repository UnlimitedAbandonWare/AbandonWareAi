package com.example.lms.service;

import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.repository.ModelEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelSettingsServiceRedactionContractTest {

    @Test
    void sourceDoesNotLogRawModelIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ModelSettingsService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("model={} provider={} allowRemote={}"));
        assertFalse(source.contains("refused remote-looking model={} provider={}"));
        assertFalse(source.contains("'{}' is embedding/legacy model"));
        assertFalse(source.contains("modelId, llmProvider"));
        assertFalse(source.contains("변경 시도: {}"));
        assertFalse(source.contains(" + modelId"));
        assertFalse(source.contains("modelId +"));
        assertFalse(source.contains(", modelId);"));
        assertTrue(source.contains("modelHash"));
        assertTrue(source.contains("modelLength"));
    }

    @Test
    void rejectedRemoteModelMessageDoesNotEchoRawModelId() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = service(modelRepo, currentRepo, "local");
        String rawModelId = "gpt-5-private-tenant-model";

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.changeCurrentModel(rawModelId));

        assertFalse(String.valueOf(ex.getMessage()).contains(rawModelId));
        verifyNoInteractions(modelRepo, currentRepo);
    }

    @Test
    void endpointMismatchMessageDoesNotEchoRawModelId() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = service(modelRepo, currentRepo, "openai");
        String rawModelId = "text-private-instruct-model";

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.changeCurrentModel(rawModelId));

        assertFalse(String.valueOf(ex.getMessage()).contains(rawModelId));
        verifyNoInteractions(modelRepo, currentRepo);
    }

    @Test
    void unknownModelMessageDoesNotEchoRawModelId() {
        ModelEntityRepository modelRepo = mock(ModelEntityRepository.class);
        CurrentModelRepository currentRepo = mock(CurrentModelRepository.class);
        ModelSettingsService service = service(modelRepo, currentRepo, "openai");
        String rawModelId = "private-local-candidate";
        when(modelRepo.existsById(rawModelId)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.changeCurrentModel(rawModelId));

        assertFalse(String.valueOf(ex.getMessage()).contains(rawModelId));
        verifyNoInteractions(currentRepo);
    }

    private static ModelSettingsService service(
            ModelEntityRepository modelRepo,
            CurrentModelRepository currentRepo,
            String provider) {
        ModelSettingsService service = new ModelSettingsService(modelRepo, currentRepo);
        ReflectionTestUtils.setField(service, "llmProvider", provider);
        ReflectionTestUtils.setField(service, "allowRemoteModelSelection", false);
        ReflectionTestUtils.setField(service, "endpointCompatSaveGuardMode", "BLOCK");
        return service;
    }
}
