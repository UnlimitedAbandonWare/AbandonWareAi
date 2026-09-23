package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicConsentServiceTest {
    @Test
    void requiredScopesAreNotGrantedWhenConsentTokenIsMissing() {
        BasicConsentService service = new BasicConsentService();

        assertFalse(service.has(null, ToolScope.WEB_GET));
        assertThrows(ConsentRequiredException.class,
                () -> service.ensureGranted(null, new ToolScope[]{ToolScope.WEB_GET}, new ConsentContext(Map.of())));
    }

    @Test
    void requiredScopesAreNotGrantedWhenTokenSessionIsMissing() {
        BasicConsentService service = new BasicConsentService();

        assertDoesNotThrow(() -> assertFalse(service.has(new ConsentToken(null), ToolScope.WEB_GET)));
        assertDoesNotThrow(() -> assertFalse(service.has(new ConsentToken(" "), ToolScope.WEB_GET)));
    }

    @Test
    void issueRejectsMissingSessionIdBeforeWritingGrantMap() {
        BasicConsentService service = new BasicConsentService();

        assertThrows(IllegalArgumentException.class, () -> service.issue(null, Set.of(ToolScope.WEB_GET), 60));
        assertThrows(IllegalArgumentException.class, () -> service.issue(" ", Set.of(ToolScope.WEB_GET), 60));
    }

    @Test
    void issuedGrantAllowsRequiredScopeUntilExpiry() {
        BasicConsentService service = new BasicConsentService();

        service.issue("session-1", Set.of(ToolScope.WEB_GET), 60);

        assertTrue(service.has(new ConsentToken("session-1"), ToolScope.WEB_GET));
        assertFalse(service.has(new ConsentToken("session-1"), ToolScope.N8N_NOTIFY));
    }

    @Test
    void issueAndHasNormalizeSessionIdWhitespace() {
        BasicConsentService service = new BasicConsentService();

        service.issue(" session-1 ", Set.of(ToolScope.WEB_GET), 60);

        assertTrue(service.has(new ConsentToken("session-1"), ToolScope.WEB_GET));
        assertTrue(service.has(new ConsentToken(" session-1 "), ToolScope.WEB_GET));
    }

    @Test
    void issueTreatsNullScopesAsEmptyGrant() {
        BasicConsentService service = new BasicConsentService();

        Grant grant = service.issue("session-1", null, 60);

        assertTrue(grant.scopes().isEmpty());
        assertFalse(service.has(new ConsentToken("session-1"), ToolScope.WEB_GET));
    }
}
