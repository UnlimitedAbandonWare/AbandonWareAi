package com.example.lms.assist;

import java.time.Clock;
import java.util.*;
import static com.example.lms.assist.ConversateSessionService.error;
import static org.springframework.http.HttpStatus.*;

/** Volatile single-user display fan-out. No session, credential or context persistence. */
final class DisplayRelay {
    static final String VERSION="fold-meta-relay-20260916.1";
    record Producer(String owner,String client,String assistId) {}
    record Event(String serverId,long eventId,long generation,long sentAt,boolean enabled,boolean producerConnected,
                 DisplayContentView.Transcript caption,DisplayContentView.TextCard hint,LensDisplayPrefs display,NovaFocusState.View focus) {}
    private final Clock clock;
    private final String serverId=UUID.randomUUID().toString();
    private final Map<String,Channel> channels=new HashMap<>();
    DisplayRelay(Clock clock){this.clock=clock;}
    private Channel channel(String key){
        if(!"live".equals(key)&&!key.matches("test-[a-f0-9]{16,32}"))throw error(BAD_REQUEST,"invalid_test_channel");
        long now=clock.millis();
        channels.entrySet().removeIf(e->!e.getKey().equals("live")&&now-e.getValue().touched>3_600_000);
        Channel c=channels.get(key);
        if(c==null){if(channels.size()>=8)throw error(TOO_MANY_REQUESTS,"display_channel_capacity");c=new Channel();channels.put(key,c);}
        c.touched=now;c.subscribers.entrySet().removeIf(e->now-e.getValue().seen>6000);return c;
    }
    synchronized Producer active(String key){return channel(key).producer;}
    synchronized boolean enabled(String key){return channel(key).enabled;}
    synchronized Producer activate(String key,String owner,String client,String assistId){
        Channel c=channel(key);var producer=new Producer(owner,client,assistId);
        c.producer=producer;c.generation++;c.eventId++;c.caption=null;c.hint=null;c.focus=null;c.lastSourceVersion=-1;
        c.connectedAt=c.changedAt=c.lastProducerSeen=clock.millis();return producer;
    }
    synchronized void presence(String key,Producer producer){Channel c=channel(key);if(producer.equals(c.producer))c.lastProducerSeen=clock.millis();}
    synchronized boolean publish(String key,Producer producer,DisplayContentView.Transcript caption,DisplayContentView.TextCard hint){
        return publish(key,producer,caption,hint,null);
    }
    synchronized boolean publish(String key,Producer producer,DisplayContentView.Transcript caption,DisplayContentView.TextCard hint,NovaFocusState.View focus){
        Channel c=channel(key);if(!producer.equals(c.producer))return false;
        if(!Objects.equals(c.caption,caption)||!Objects.equals(c.hint,hint)||!Objects.equals(c.focus,focus)){
            if(caption!=null&&!Objects.equals(c.caption,caption))c.lastTranscriptAt=clock.millis();
            c.caption=caption;c.hint=hint;c.focus=focus;c.eventId++;
        }
        return true;
    }
    synchronized void configure(String key,Producer producer,boolean enabled,int seconds){
        Channel c=channel(key);require(c,producer);
        if(seconds!=0&&(seconds<5||seconds>60))throw error(BAD_REQUEST,"invalid_segment_seconds");
        if(c.enabled!=enabled){c.enabled=enabled;c.eventId++;}c.segmentSeconds=seconds;
    }
    synchronized Event poll(String key,String subscriber){return poll(key,subscriber,null);}
    synchronized Event poll(String key,String subscriber,LensDisplayPrefs display){
        Channel c=channel(key);long now=clock.millis();
        var sub=c.subscribers.get(subscriber);
        if(sub==null){if(c.subscribers.size()>=4)throw error(TOO_MANY_REQUESTS,"display_subscriber_capacity");sub=new Subscriber();c.subscribers.put(subscriber,sub);}
        sub.seen=now;
        if(c.caption!=null&&c.caption.expiresAt()<=now){c.caption=null;c.eventId++;}
        if(c.hint!=null&&c.hint.expiresAt()<=now){c.hint=null;c.eventId++;}
        if(sub.sent!=c.eventId){sub.sent=c.eventId;c.lastSentAt=now;}
        boolean connected=c.producer!=null&&now-c.lastProducerSeen<=10000;
        return new Event(serverId,c.eventId,c.generation,now,c.enabled,connected,c.enabled?c.caption:null,c.enabled?c.hint:null,display,c.enabled&&connected?c.focus:null);
    }
    synchronized void ack(String key,String subscriber,long eventId){
        Channel c=channel(key);Subscriber sub=c.subscribers.get(subscriber);
        if(sub==null||eventId<1||eventId>sub.sent)throw error(CONFLICT,"invalid_display_ack");
        if(eventId>c.lastAckEvent){c.lastAckEvent=eventId;c.lastAckAt=clock.millis();}
    }
    synchronized Map<String,Object> debug(String key,Producer caller){
        Channel c=channel(key);var out=new LinkedHashMap<String,Object>();
        out.put("eventOwner",Objects.equals(c.producer,caller)?"THIS DEVICE":"OTHER CLIENT");
        out.put("activeEventOwner",c.producer==null?"none":c.producer.client());out.put("clientId",caller.client());
        out.put("session",caller.assistId());out.put("clientRole",key.equals("live")?"control":"test");
        out.put("lastConnectedAt",c.connectedAt);out.put("ownershipChangedAt",c.changedAt);out.put("lastProducerSeenAt",c.lastProducerSeen);
        out.put("subscribers",c.subscribers.size());out.put("lastTranscriptAt",c.lastTranscriptAt);
        out.put("lastDisplayPushAt",c.lastSentAt);out.put("lastDisplayAckAt",c.lastAckAt);out.put("lastAckEventId",c.lastAckEvent);
        out.put("lastEventId",c.eventId);out.put("generation",c.generation);out.put("enabled",c.enabled);
        out.put("segmentSeconds",c.segmentSeconds);out.put("transport","HTTPS_POLL_1S");out.put("webSocketSse","not_used");
        out.put("deploymentVersion",VERSION);out.put("hardwareVisibility","not_observed");return out;
    }
    private static void require(Channel c,Producer p){if(!Objects.equals(c.producer,p))throw error(FORBIDDEN,"event_owner_required");}
    private static final class Subscriber {long seen,sent;}
    private static final class Channel {
        Producer producer;boolean enabled=true;int segmentSeconds=0;
        long eventId=1,generation,connectedAt,changedAt,lastProducerSeen,lastTranscriptAt,lastSentAt,lastAckAt,lastAckEvent,touched,lastSourceVersion;
        DisplayContentView.Transcript caption;DisplayContentView.TextCard hint;NovaFocusState.View focus;
        final Map<String,Subscriber> subscribers=new HashMap<>();
    }
}
