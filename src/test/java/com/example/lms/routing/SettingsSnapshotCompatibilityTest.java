package com.example.lms.routing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsSnapshotCompatibilityTest {
    static final String EMPTY = "{\"schemaVersion\":1,\"enabled\":false,\"bindings\":{},\"additionalPaidAllowed\":false,\"additionalCostCapUsd\":0}";
    @Test void v2ProfileRoundTripsUnchanged() { var p=RoutingProfile.parse(EMPTY); assertEquals(p,RoutingProfile.parse(p.json())); }
    @Test void descriptorFieldsNeverEnterStoredPolicy() {
        for(String field:new String[]{"settingsView","pipelineView","provider","observedValue","jev"})
            assertThrows(IllegalArgumentException.class,()->RoutingProfile.parse(EMPTY.replace("\"bindings\":{}","\"bindings\":{},\""+field+"\":{}")));
    }
    @Test void duplicateKeysAndUnknownRolesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->RoutingProfile.parse(EMPTY.replace("\"enabled\":false","\"enabled\":false,\"enabled\":true")));
        assertThrows(IllegalArgumentException.class,()->RoutingProfile.parse(EMPTY.replace("\"bindings\":{}","\"bindings\":{\"NEW_ROLE\":{}}")));
    }
    @Test void paidAndOversizedDocumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->RoutingProfile.parse(EMPTY.replace("\"additionalPaidAllowed\":false","\"additionalPaidAllowed\":true")));
        assertThrows(IllegalArgumentException.class,()->RoutingProfile.parse(EMPTY+" ".repeat(16385)));
    }
    @Test void roleBindingIsImmutableAndBounded() {
        String binding="\"bindings\":{\"SELFASK_BQ\":{\"role\":\"SELFASK_BQ\",\"selection\":\"registered-route\",\"target\":\"llmrouter.a\",\"orderedFallbacks\":[],\"maxExtraFallbackCalls\":0}}";
        var p=RoutingProfile.parse(EMPTY.replace("\"bindings\":{}",binding));
        assertEquals("llmrouter.a",p.bindings().get(RoutingProfile.Role.SELFASK_BQ).target());
        assertThrows(UnsupportedOperationException.class,()->p.bindings().clear());
    }
}
