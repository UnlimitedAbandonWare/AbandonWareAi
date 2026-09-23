package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ConversateRagCardFocusedTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private ConversateController.DisplayCard requestStatus(long epoch,long sequence,String id,String state) throws Exception {
        return json.readValue("{\"epoch\":"+epoch+",\"kind\":\"status\",\"requestId\":\""+id
                +"\",\"requestState\":\""+state+"\",\"requestSequence\":"+sequence+"}",ConversateController.DisplayCard.class);
    }

    @Test void sameRequestStatusUsesExistingOutputWithoutPublishingUnreviewedAnswer() throws Exception {
        try(var sessions=new ConversateSessionService()){
            var controller=new ConversateController(sessions);ReflectionTestUtils.setField(controller,"interviewDemo",true);
            var own=controller.start(null).getBody();
            var pending=controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),1,"request-1","LOADING")).getBody();
            assertEquals("STATUS",pending.card().kind());assertEquals("LOADING",pending.card().decision());
            assertTrue(pending.card().sourceTitles().isEmpty());
            var limited=controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),1,"request-1","rate-limited")).getBody();
            assertEquals("rate-limited",limited.card().decision());assertEquals("request-1",limited.card().requestId());
            assertEquals(0,limited.metrics().generationAttempts());assertEquals(0,limited.metrics().searchAttempts());
        }
    }

    @Test void lateStatusCannotReplaceNewRequestOrReviewedCard() throws Exception {
        try(var sessions=new ConversateSessionService()){
            var controller=new ConversateController(sessions);ReflectionTestUtils.setField(controller,"interviewDemo",true);
            var own=controller.start(null).getBody();
            controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),2,"request-2","LOADING"));
            assertThrows(ResponseStatusException.class,()->controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),1,"request-1","RESULT")));
            controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),2,"request-2","RESULT"));
            assertThrows(ResponseStatusException.class,()->controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),2,"request-2","LOADING")));
            var reviewed=new ConversateController.DisplayCard(own.epoch(),"answer","Reviewed fixture","request-2",java.util.List.of("[1] Fixture"));
            controller.displayCard(null,own.assistId(),reviewed);
            var repeated=controller.displayCard(null,own.assistId(),requestStatus(own.epoch(),2,"request-2","RESULT")).getBody();
            assertEquals("Reviewed fixture",repeated.card().text());assertEquals("ANSWER",repeated.card().kind());
            assertThrows(ResponseStatusException.class,()->controller.displayCard(null,own.assistId(),new ConversateController.DisplayCard(own.epoch(),"answer","Old fixture","request-1",java.util.List.of())));
        }
    }

    @Test void statusCannotCarryTextOrBypassExistingOwnerAndEpoch() throws Exception {
        try(var sessions=new ConversateSessionService()){
            var controller=new ConversateController(sessions);ReflectionTestUtils.setField(controller,"interviewDemo",true);
            var own=controller.start(null).getBody();var foreign=sessions.start("foreign");
            var status=requestStatus(own.epoch(),1,"request-1","LOADING");
            assertThrows(ResponseStatusException.class,()->controller.displayCard(null,foreign.assistId(),status));
            assertThrows(ResponseStatusException.class,()->controller.displayCard(null,own.assistId(),requestStatus(own.epoch()+1,1,"request-1","LOADING")));
            var payload=json.readValue("{\"epoch\":"+own.epoch()+",\"kind\":\"status\",\"text\":\"unreviewed\",\"requestId\":\"request-1\",\"requestState\":\"LOADING\",\"requestSequence\":1}",ConversateController.DisplayCard.class);
            var invalid=assertThrows(ResponseStatusException.class,()->controller.displayCard(null,own.assistId(),payload));
            assertEquals(400,invalid.getStatusCode().value());assertNull(controller.status(null,own.assistId()).getBody().card());
        }
    }

    private ConversateController.DisplayCard card(long epoch) throws Exception {
        return json.readValue("{\"epoch\":" + epoch + ",\"kind\":\"answer\","
                + "\"text\":\"The source states a two-year warranty.\","
                + "\"requestId\":\"synthetic-rag-1\",\"sourceTitles\":[\"[1] Warranty policy\"]}",
                ConversateController.DisplayCard.class);
    }

    @Test void completedChatMetadataUsesTheExistingOutputWithoutGeneration() throws Exception {
        try (var sessions = new ConversateSessionService()) {
            var controller = new ConversateController(sessions);
            ReflectionTestUtils.setField(controller, "interviewDemo", true);
            var started = controller.start(null).getBody();
            var receiver = controller.output(null, started.assistId(), started.epoch()).getBody().subscribe();
            try {
                var accepted = controller.displayCard(null, started.assistId(), card(started.epoch())).getBody();
                var output = json.valueToTree(accepted).path("card");
                assertEquals("synthetic-rag-1", output.path("requestId").asText());
                assertEquals("[1] Warranty policy", output.path("sourceTitles").get(0).asText());
                assertEquals(0, accepted.metrics().generationAttempts());
                assertEquals(0, accepted.metrics().searchAttempts());
                assertEquals(0, accepted.metrics().outputAcks());
                assertFalse(accepted.card().toString().contains("Warranty"));
            } finally { receiver.dispose(); }
        }
    }

    @Test void requestIdentifierDoesNotAuthorizeAnotherOwnerOrEpoch() throws Exception {
        try (var sessions = new ConversateSessionService()) {
            var controller = new ConversateController(sessions);
            ReflectionTestUtils.setField(controller, "interviewDemo", true);
            var own = controller.start(null).getBody();
            var foreign = sessions.start("different-owner");
            var request = card(own.epoch());
            assertThrows(ResponseStatusException.class, () -> controller.displayCard(null, foreign.assistId(), request));
            var stale = card(own.epoch() + 1);
            assertThrows(ResponseStatusException.class, () -> controller.displayCard(null, own.assistId(), stale));
            assertNull(controller.status(null, own.assistId()).getBody().card());
            assertNull(sessions.status("different-owner", foreign.assistId()).card());
        }
    }

    @Test void oldManualCardContractStillPublishesWithoutInventedProvenance() {
        try (var sessions = new ConversateSessionService()) {
            var controller = new ConversateController(sessions);
            ReflectionTestUtils.setField(controller, "interviewDemo", true);
            var started = controller.start(null).getBody();
            var accepted = controller.displayCard(null, started.assistId(),
                    new ConversateController.DisplayCard(started.epoch(), "hint", "Operator text")).getBody();
            var output = json.valueToTree(accepted).path("card");
            assertTrue(output.path("requestId").isNull());
            assertEquals(0, output.path("sourceTitles").size());
            assertEquals(0, accepted.metrics().generationAttempts());
        }
    }

    @Test void malformedSourceMetadataIsRejectedAsClientInputBeforePublication() throws Exception {
        try (var sessions = new ConversateSessionService()) {
            var controller = new ConversateController(sessions);
            ReflectionTestUtils.setField(controller, "interviewDemo", true);
            var started = controller.start(null).getBody();
            var malformed = json.readValue("{\"epoch\":" + started.epoch()
                    + ",\"kind\":\"answer\",\"text\":\"Safe fixture\",\"requestId\":\"synthetic-rag-1\",\"sourceTitles\":[null]}",
                    ConversateController.DisplayCard.class);
            var failure = assertThrows(ResponseStatusException.class,
                    () -> controller.displayCard(null, started.assistId(), malformed));
            assertEquals(400, failure.getStatusCode().value());
            assertNull(controller.status(null, started.assistId()).getBody().card());
        }
    }
}
