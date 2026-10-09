package com.example.lms.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NaverCredentialRedactionTest {
    @Test
    void credentialToStringNeverEmitsEitherAuthenticationValue() {
        var credential = new NaverCredentialBridge.Credential("synthetic-hub-id", "synthetic-hub-secret");
        assertFalse(credential.toString().contains(credential.id()));
        assertFalse(credential.toString().contains(credential.secret()));
        assertEquals("synthetic-hub-id", credential.id());
        assertEquals("synthetic-hub-secret", credential.secret());
    }

    @Test
    void accidentalJsonSerializationCannotExposeAuthenticationValues() throws Exception {
        var credential = new NaverCredentialBridge.Credential("synthetic-hub-id", "synthetic-hub-secret");
        assertEquals("{}", new ObjectMapper().writeValueAsString(credential));
    }
}
