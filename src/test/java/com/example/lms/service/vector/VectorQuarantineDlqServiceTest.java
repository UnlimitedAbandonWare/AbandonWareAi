package com.example.lms.service.vector;

import com.example.lms.entity.VectorQuarantineDlq;
import com.example.lms.repository.VectorQuarantineDlqRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorQuarantineDlqServiceTest {

    @Test
    void enabledDlqReportsWhenAutomaticRedriveIsDisabled() {
        VectorQuarantineDlqService service = new VectorQuarantineDlqService(
                mock(VectorQuarantineDlqRepository.class),
                new ObjectMapper(),
                mock(EmbeddingModel.class),
                mockEmbeddingStore(),
                mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "enabled", true);

        Map<String, Object> stats = service.stats();

        assertEquals(true, stats.get("enabled"));
        assertEquals(false, stats.get("redriveEnabled"));
        assertEquals("redrive_disabled", stats.get("disabledReason"));
    }

    @Test
    void statsErrorDoesNotExposeRawSecrets() {
        VectorQuarantineDlqRepository repo = mock(VectorQuarantineDlqRepository.class);
        String rawSecret = "test-token-vectorDlqStatsSecret";
        when(repo.countByStatus(VectorQuarantineDlq.Status.PENDING))
                .thenThrow(new IllegalStateException("db failed api_key=" + rawSecret));
        VectorQuarantineDlqService service = new VectorQuarantineDlqService(
                repo,
                new ObjectMapper(),
                mock(EmbeddingModel.class),
                mockEmbeddingStore(),
                mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "enabled", true);

        Map<String, Object> stats = service.stats();
        String error = String.valueOf(stats.get("error"));

        assertTrue((Boolean) stats.get("enabled"));
        assertTrue(error.startsWith("hash:"), error);
        assertFalse(error.contains(rawSecret));
        assertFalse(error.contains("api_key=" + rawSecret));
    }

    @Test
    void blockedReasonDoesNotPersistRawGuardMessage() {
        VectorQuarantineDlqRepository repo = mock(VectorQuarantineDlqRepository.class);
        VectorQuarantineDlqService service = new VectorQuarantineDlqService(
                repo,
                new ObjectMapper(),
                mock(EmbeddingModel.class),
                mockEmbeddingStore(),
                mock(PlatformTransactionManager.class));
        String rawSecret = "test-token-vectorDlqBlockedSecret";
        String rawReason = "poison_guard: private student retry api_key=" + rawSecret;
        VectorQuarantineDlq row = new VectorQuarantineDlq();

        ReflectionTestUtils.invokeMethod(service, "markBlocked", row, rawReason);

        assertTrue(row.getLastError().startsWith("hash:"), row.getLastError());
        assertFalse(row.getLastError().contains(rawReason), row.getLastError());
        assertFalse(row.getLastError().contains(rawSecret), row.getLastError());
        assertFalse(row.getLastError().contains("student"), row.getLastError());
        verify(repo).save(row);
    }

    @SuppressWarnings("unchecked")
    private static EmbeddingStore<TextSegment> mockEmbeddingStore() {
        return mock(EmbeddingStore.class);
    }
}
