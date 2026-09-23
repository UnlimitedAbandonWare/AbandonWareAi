package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpStatus.*;
import org.springframework.web.server.ResponseStatusException;

class DisplayRelayTest {
    static final String A="a".repeat(32),B="b".repeat(32),L="c".repeat(32);
    @Test void latestProducerWinsSubscribersAndTestChannelCannotTakeLiveOwnership(){
        var relay=new DisplayRelay(Clock.systemUTC());
        var a=relay.activate("live","owner-a",A,"assist-a");
        relay.publish("live",a,caption("first"),null);
        var first=relay.poll("live",L);
        relay.poll("test-1234567890abcdef",L);
        var test=relay.activate("test-1234567890abcdef","test-owner",B,"test-assist");
        relay.publish("test-1234567890abcdef",test,caption("TEST ONLY"),null);
        assertEquals(a,relay.active("live"));
        var b=relay.activate("live","owner-b",B,"assist-b");
        assertFalse(relay.publish("live",a,caption("late old producer"),null));
        assertTrue(relay.publish("live",b,caption("newest"),null));
        var latest=relay.poll("live",L);
        assertTrue(latest.eventId()>first.eventId());
        assertEquals("newest",latest.caption().text());
        assertEquals(b,relay.active("live"));
        relay.ack("live",L,latest.eventId());
        assertEquals(1,relay.debug("live",b).get("subscribers"));
        assertEquals(latest.eventId(),relay.debug("live",b).get("lastAckEventId"));
    }
    @Test void duplicatePollDoesNotGenerateEventsAndDisabledOutputClearsOnlyReceiver(){
        var relay=new DisplayRelay(Clock.systemUTC());var a=relay.activate("live","o",A,"s");
        var caption=caption("instant transcript");relay.publish("live",a,caption,null);
        var one=relay.poll("live",L);relay.publish("live",a,caption,null);
        assertEquals(one.eventId(),relay.poll("live",L).eventId());
        relay.configure("live",a,false,5);
        assertNull(relay.poll("live",L).caption());assertEquals(a,relay.active("live"));
        relay.configure("live",a,true,15);
        assertEquals("instant transcript",relay.poll("live",L).caption().text());
        assertThrows(ResponseStatusException.class,()->relay.configure("live",a,true,4));
        assertEquals(15,relay.debug("live",a).get("segmentSeconds"));
        relay.configure("live",a,true,0);
        assertEquals(0,relay.debug("live",a).get("segmentSeconds"));
        assertThrows(ResponseStatusException.class,()->relay.configure("live",a,true,61));
    }
    @Test void subscriberLeaseExpiresAndAckCannotClaimAnUnsentEvent(){
        var clock=new MutableClock();var relay=new DisplayRelay(clock);
        var a=relay.activate("live","o",A,"s");relay.poll("live",L);
        assertEquals(CONFLICT,assertThrows(ResponseStatusException.class,()->relay.ack("live",L,999)).getStatusCode());
        clock.now+=6001;assertEquals(0,relay.debug("live",a).get("subscribers"));
    }
    private static DisplayContentView.Transcript caption(String text){
        return DisplayContentView.caption(new ConversateSessionService.Caption("asr-1",1,true,text,null,java.util.List.of(),System.currentTimeMillis(),System.currentTimeMillis()+15000),System.currentTimeMillis());
    }
    static class MutableClock extends Clock {long now=100000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
}
