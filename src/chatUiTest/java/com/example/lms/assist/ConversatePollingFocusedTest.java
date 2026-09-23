package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversatePollingFocusedTest {
    private static final class MutableClock extends Clock {
        long now=10000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    @Test void pollingOutputCanAcknowledgeWithoutSseAndExpiresWhenItStops() {
        var clock=new MutableClock();try(var service=new ConversateSessionService(clock)){
            var start=service.start("demo");String client="a".repeat(32);
            var connected=service.pollOutput("demo",start.assistId(),start.epoch(),client);assertEquals(1,connected.outputConnections());
            assertTrue(service.publish("demo",start.assistId(),start.epoch(),new ConversateSessionService.Card("SHOW","HINT","근거를 확인하세요.",List.of(),clock.now+60000)));
            var card=service.status("demo",start.assistId());var ack=service.acknowledge("demo",start.assistId(),start.epoch(),card.version());assertEquals(1,ack.metrics().outputAcks());
            clock.now+=5001;service.maintain();assertEquals(0,service.status("demo",start.assistId()).outputConnections());
            clock.now+=5001;service.maintain();assertEquals("PAUSED",service.status("demo",start.assistId()).state());
            assertThrows(ResponseStatusException.class,()->service.pollOutput("demo",start.assistId(),start.epoch(),client));
        }
    }
    @Test void pollingClientsAreBoundedAndRepeatedHeartbeatDoesNotInflateCount() {
        try(var service=new ConversateSessionService()){
            var start=service.start("demo");
            for(int i=1;i<=4;i++)service.pollOutput("demo",start.assistId(),start.epoch(),Integer.toString(i).repeat(32));
            assertEquals(4,service.pollOutput("demo",start.assistId(),start.epoch(),"1".repeat(32)).outputConnections());
            assertEquals(429,assertThrows(ResponseStatusException.class,()->service.pollOutput("demo",start.assistId(),start.epoch(),"5".repeat(32))).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.pollOutput("demo",start.assistId(),start.epoch(),"bad")).getStatusCode().value());
        }
    }
}
