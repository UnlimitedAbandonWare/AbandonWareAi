package com.example.lms.assist;

import com.example.lms.web.ClientOwnerKeyResolver;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayStickyLensTest {
    @TempDir Path directory;
    final String client="12345678123442348234123456789abc";
    ConversateSessionService sessions;
    ClientOwnerKeyResolver owners;
    DisplayConversateController controller;
    MockHttpServletRequest http;
    final MutableClock clock=new MutableClock();
    @BeforeEach void setup(){
        owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-producer");
        http=new MockHttpServletRequest();http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);
        http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");restart(true);
    }
    void restart(boolean enabled){
        if(sessions!=null)sessions.close();sessions=new ConversateSessionService();
        controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress(),clock);
        ReflectionTestUtils.setField(controller,"stickyLensEnabled",enabled);
        ReflectionTestUtils.setField(controller,"stickyLensPath",directory.resolve("sticky.json").toString());
    }
    DisplayConversateController.Connection connect(){
        var view=controller.transcription(new DisplayConversateController.Connection(null,0,client),http).getBody();
        return new DisplayConversateController.Connection(view.assistId(),view.epoch(),client);
    }
    DisplayConversateController.LensText read(String token){return controller.lensText(new DisplayConversateController.LensRead(token),http).getBody();}
    @AfterEach void close(){sessions.close();}
    @Test void sameOwnerReusesTheLastLinkAndExtendsItsValidity(){
        var connection=connect();var first=controller.lensLink(connection,http).getBody();clock.advance(1000);
        var second=controller.lensLink(connection,http).getBody();
        assertEquals(first.token(),second.token());assertTrue(second.expiresAt()>first.expiresAt());
        assertNotNull(read(first.token()));assertNotNull(read(second.token()));
    }
    @Test void restartedServerWaitsForOriginalProducerThenReusesTheSameLink(){
        var first=controller.lensLink(connect(),http).getBody();restart(true);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->read(first.token())).getStatusCode().value());
        var second=controller.lensLink(connect(),http).getBody();
        assertEquals(first.token(),second.token());assertNotNull(read(first.token()));
    }
    @Test void defaultOffKeepsRotationAndNeverReadsOrWritesTheDebugStore()throws Exception{
        restart(false);Files.writeString(directory.resolve("sticky.json"),"corrupt debug record");
        var connection=connect();var first=controller.lensLink(connection,http).getBody();var second=controller.lensLink(connection,http).getBody();
        assertFalse(first.sticky());assertNotEquals(first.token(),second.token());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->read(first.token())).getStatusCode().value());
        assertEquals("corrupt debug record",Files.readString(directory.resolve("sticky.json")));
    }
    @Test void optOutDoesNotExposePersistedDebugLinks(){
        var first=controller.lensLink(connect(),http).getBody();restart(false);connect();
        assertEquals(404,assertThrows(ResponseStatusException.class,()->read(first.token())).getStatusCode().value());
    }
    @Test void aSecondOwnerCannotReplaceTheOriginalLink()throws Exception{
        var first=controller.lensLink(connect(),http).getBody();
        when(owners.ownerKey()).thenReturn("synthetic-stranger");
        var other=controller.lensLink(connect(),http).getBody();assertNotEquals(first.token(),other.token());
        when(owners.ownerKey()).thenReturn("synthetic-producer");
        assertEquals(first.token(),controller.lensLink(connect(),http).getBody().token());
        var saved=new StickyLensGrantStore(directory.resolve("sticky.json").toString()).find(first.token());
        assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-producer"),saved.owner());
    }
    @Test void corruptStorageFailsClosedWithoutReplacingItsBytes()throws Exception{
        controller.lensLink(connect(),http);Path file=directory.resolve("sticky.json");Files.writeString(file,"corrupt");
        byte[] before=Files.readAllBytes(file);restart(true);
        var connection=connect();
        assertEquals(503,assertThrows(ResponseStatusException.class,()->controller.lensLink(connection,http)).getStatusCode().value());
        assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void persistenceFailureDoesNotInvalidateThePriorLiveGrant(){
        restart(false);var connection=connect();var first=controller.lensLink(connection,http).getBody();
        ReflectionTestUtils.setField(controller,"stickyLensEnabled",true);
        ReflectionTestUtils.setField(controller,"stickyLensPath",directory.resolve("absent/sticky.json").toString());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->controller.lensLink(connection,http)).getStatusCode().value());
        ReflectionTestUtils.setField(controller,"stickyLensEnabled",false);assertNotNull(read(first.token()));
    }
    @Test void aLiveSameOwnerGrantCanMigrateWithoutChangingTheUrl(){
        restart(false);var connection=connect();var first=controller.lensLink(connection,http).getBody();
        ReflectionTestUtils.setField(controller,"stickyLensEnabled",true);
        var sticky=controller.lensLink(connection,http).getBody();assertTrue(sticky.sticky());assertEquals(first.token(),sticky.token());
        restart(true);connect();assertNotNull(read(first.token()));
    }
    @Test void durableLinksSurviveTheOldTwelveHourLimitButExpiredLinksAreNotRevived(){
        var first=controller.lensLink(connect(),http).getBody();clock.advance(13*60*60*1000L);restart(true);connect();assertNotNull(read(first.token()));
        clock.advance(31L*24*60*60*1000);restart(true);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->read(first.token())).getStatusCode().value());
        assertNotEquals(first.token(),controller.lensLink(connect(),http).getBody().token());
    }
    @Test void durableStateContainsNoSessionOrConversationData()throws Exception{
        var connection=connect();controller.lensLink(connection,http);
        String bytes=Files.readString(directory.resolve("sticky.json"));
        assertFalse(bytes.contains(connection.assistId()));assertFalse(bytes.contains("synthetic-producer"));
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
        assertEquals(java.util.Set.of("schemaVersion","grants"),new com.fasterxml.jackson.databind.ObjectMapper().convertValue(tree,java.util.Map.class).keySet());
        assertEquals(3,tree.path("grants").get(0).size());
    }
    static class MutableClock extends Clock {
        long now=Instant.parse("2026-09-18T00:00:00Z").toEpochMilli();
        void advance(long ms){now+=ms;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
}
