package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentValueObjectTest {

    @Test
    void grantNormalizesNullScopesAndExpiry() {
        Grant grant = new Grant("session", null, null);

        assertTrue(grant.scopes().isEmpty());
        assertEquals(Instant.EPOCH, grant.expiresAt());
        assertTrue(grant.isExpired());
    }

    @Test
    void consentRequiredExceptionCopiesMissingScopes() {
        List<ToolScope> scopes = new ArrayList<>();
        scopes.add(ToolScope.WEB_GET);

        ConsentRequiredException exception = new ConsentRequiredException(scopes);
        scopes.clear();

        assertEquals(List.of(ToolScope.WEB_GET), exception.getMissingScopes());
        assertThrows(UnsupportedOperationException.class,
                () -> exception.getMissingScopes().add(ToolScope.N8N_NOTIFY));
    }

    @Test
    void consentRequiredExceptionTreatsNullScopesAsEmpty() {
        ConsentRequiredException exception = new ConsentRequiredException(null);

        assertTrue(exception.getMissingScopes().isEmpty());
    }
}
