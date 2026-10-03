package com.example.lms.routing;

import com.example.lms.service.ChatModelCatalogService;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutingRunSnapshotTest {
    @Test void flagOffNeverQueriesPolicyOrCatalog() {
        var service=mock(RoutingSettingsService.class);var catalog=mock(ChatModelCatalogService.class);
        var resolver=new RoutingProfileResolver(service,catalog,false);
        var snap=resolver.capture();assertFalse(snap.runtimeEnabled());assertTrue(snap.bindings().isEmpty());
        verifyNoInteractions(service,catalog);
    }
    @Test void disabledProfileIsInheritedWithoutCatalogLookup() {
        var service=mock(RoutingSettingsService.class);var catalog=mock(ChatModelCatalogService.class);
        when(service.read()).thenReturn(new RoutingSettingsService.State(3,"hash",RoutingProfile.parse(SettingsSnapshotCompatibilityTest.EMPTY)));
        var snap=new RoutingProfileResolver(service,catalog,true).capture();
        assertFalse(snap.profileEnabled());assertEquals(3,snap.profileRevision());verifyNoInteractions(catalog);
    }
    @Test void oneRunPinsRevisionAndJoinedContextSharesIt() {
        var resolver=mock(RoutingProfileResolver.class);
        var first=RunRoutingSnapshot.disabled();var second=RunRoutingSnapshot.inherited(9,"new");
        when(resolver.capture()).thenReturn(first,second);
        var registry=new ChatRunRegistry();registry.setRoutingProfileResolver(resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(registry,"replayCapacity",512);
        try {
            var a=registry.beginOrJoin(777L);var b=registry.beginOrJoin(777L);
            assertSame(first,a.context().routingSnapshot());assertSame(first,b.context().routingSnapshot());
            verify(resolver,times(1)).capture();
        } finally {org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry,"shutdown");}
    }
    @Test void missingPolicyAndDatabaseFailureAreDifferent() {
        var service=mock(RoutingSettingsService.class);var catalog=mock(ChatModelCatalogService.class);
        when(service.read()).thenThrow(new IllegalStateException("private db failure"));
        var resolver=new RoutingProfileResolver(service,catalog,true);
        var e=assertThrows(RoutingProfileResolver.Unavailable.class,resolver::capture);
        assertEquals("routing_policy_unavailable",e.getMessage());verifyNoInteractions(catalog);
    }
}
