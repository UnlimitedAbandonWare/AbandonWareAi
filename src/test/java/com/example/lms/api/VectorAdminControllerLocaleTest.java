package com.example.lms.api;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.EmbeddingStoreManager;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.VectorSidService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorAdminControllerLocaleTest {

    @Test
    void supportedQuarantineStatusParsingIsIndependentOfDefaultLocale() {
        TranslationMemoryRepository repository = mock(TranslationMemoryRepository.class);
        TranslationMemory memory = new TranslationMemory();
        memory.setId(7L);
        memory.setStatus(TranslationMemory.MemoryStatus.QUARANTINED);
        when(repository.findById(7L)).thenReturn(Optional.of(memory));
        VectorAdminController controller = new VectorAdminController(
                mock(VectorSidService.class),
                mock(VectorStoreService.class),
                mock(EmbeddingStoreManager.class),
                repository);

        ResponseEntity<Map<String, Object>> response;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            response = controller.updateQuarantine(
                    7L,
                    new VectorAdminController.QuarantineUpdate("pending"));
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals(TranslationMemory.MemoryStatus.PENDING, memory.getStatus());
        assertNotNull(response.getBody());
        assertEquals("PENDING", response.getBody().get("status"));
        verify(repository).save(memory);
    }
}
