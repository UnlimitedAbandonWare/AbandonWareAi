package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One primary-to-legacy transition. Never replays audio with an uncertain delivery outcome. */
final class FailoverAsrTransport implements ConversateAsrBridge.Transport {
    private final ObjectMapper json;
    private final ConversateAsrBridge.Factory fallback;
    private final Consumer<JsonNode> events;
    private final Consumer<String> failure;
    private final java.util.function.Predicate<String> retryable;
    private final ArrayDeque<String> queued=new ArrayDeque<>();
    private final Set<Long> acknowledged=new HashSet<>();
    private final Set<Long> inFlight=new LinkedHashSet<>();
    private ConversateAsrBridge.Transport transport;
    private int generation;
    private boolean closed,switching,legacyReady,finishing,draining;
    private long fallbackBegan;private boolean firstLegacyTranscript;
    private volatile String lastReason;
    private final java.util.List<ConversateAsrBridge.Transport> retired=new java.util.ArrayList<>();
    FailoverAsrTransport(ObjectMapper json,ConversateAsrBridge.Factory primary,ConversateAsrBridge.Factory fallback,Consumer<JsonNode> events,Consumer<String> failure)throws IOException{
        this(json,primary,fallback,events,failure,reason->true);
    }
    FailoverAsrTransport(ObjectMapper json,ConversateAsrBridge.Factory primary,ConversateAsrBridge.Factory fallback,Consumer<JsonNode> events,Consumer<String> failure,java.util.function.Predicate<String> retryable)throws IOException{
        this.json=json;this.fallback=fallback;this.events=events;this.failure=failure;this.retryable=retryable;
        try{launch(primary,0);}catch(IOException unavailable){note(unavailable.getMessage());if(!retryable.test(unavailable.getMessage())){close();throw unavailable;}switchToLegacy(0);}
    }
    /** Keep the last bounded ASR reason so terminal failure reports the real cause, not a generic error. */
    private void note(String raw){
        if(raw==null)return;
        String code=raw.matches("ASR_[A-Z_]{1,40}")?raw:
            raw.matches("stt_budget_[a-z_]{1,40}")?"ASR_"+raw.substring(4).toUpperCase(Locale.ROOT):
            raw.matches("asr_[a-z_]{1,40}")?"ASR_"+raw.substring(4).toUpperCase(Locale.ROOT):
            raw.matches("(deepgram|soniox):[a-z_0-9]{1,40}")?"ASR_"+raw.substring(raw.indexOf(':')+1).toUpperCase(Locale.ROOT).replace("PROVIDER_ERROR","PROVIDER_FAILED"):null;
        lastReason=code!=null?code:lastReason==null?raw:lastReason;
    }
    private void launch(ConversateAsrBridge.Factory factory,int version)throws IOException{
        if(factory==null)throw new IOException("asr_fallback_unavailable");
        var candidate=factory.launch(e->event(version,e),r->failed(version,r));
        boolean rejected;
        synchronized(this){rejected=closed||generation!=version;if(!rejected)transport=candidate;}
        if(rejected){candidate.close();return;}
        if(version>0)drainLegacy();
    }
    private void failed(int version,String reason){
        boolean terminal;
        synchronized(this){if(closed||generation!=version)return;note(reason);terminal=finishing||!retryable.test(reason);}
        if(terminal){close();failure.accept(reason);return;}
        switchToLegacy(version);
    }
    private void drainLegacy()throws IOException{
        synchronized(this){if(draining)return;draining=true;}
        try{while(true){
            ConversateAsrBridge.Transport current;String line;
            synchronized(this){
                if(!switching||!legacyReady||transport==null||closed)return;
                if(queued.isEmpty()){switching=false;return;}
                current=transport;line=queued.removeFirst();
            }
            current.send(line);
        }}finally{synchronized(this){draining=false;}}
    }
    private void event(int version,JsonNode event){
        JsonNode forwarded=event;boolean ready=false;
        synchronized(this){
            if(closed||generation!=version)return;
            if(version==0&&event.path("type").asText().equals("ack"))inFlight.remove(event.path("seq").asLong(-1));
            if(version>0&&event.path("type").asText().equals("ack")&&acknowledged.remove(event.path("seq").asLong(-1)))return;
            if(version>0&&event instanceof ObjectNode object){
                var copy=object.deepCopy();
                if(copy.path("type").asText().equals("transcript"))copy.put("utteranceId","legacy-"+copy.path("utteranceId").asText());
                var runtime=copy.path("runtime").isObject()?((ObjectNode)copy.path("runtime")).deepCopy():json.createObjectNode();
                if(copy.path("type").asText().equals("transcript")&&!firstLegacyTranscript){firstLegacyTranscript=true;runtime.put("fallbackGapMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-fallbackBegan));}
                runtime.put("reason","sidecar_failed_legacy");copy.set("runtime",runtime);forwarded=copy;
            }
            if(version>0&&event.path("type").asText().equals("ready")){
                legacyReady=true;ready=true;
            }
        }
        if(ready)try{drainLegacy();}catch(IOException unavailable){note(unavailable.getMessage());switchToLegacy(1);return;}
        // Capture/session callbacks can close this transport while holding their
        // own monitor. No callback or child operation may run under our monitor.
        events.accept(forwarded);
    }
    private void switchToLegacy(int version){
        ConversateAsrBridge.Transport old;var uncertainAcks=new ArrayList<JsonNode>();
        synchronized(this){
            if(closed||version!=generation||finishing)return;
            old=transport;transport=null;
            if(old!=null)retired.add(old);
            if(version>0||fallback==null){closed=true;queued.clear();acknowledged.clear();}
            else{generation=1;switching=true;legacyReady=false;fallbackBegan=System.nanoTime();
                // Already submitted PCM has an uncertain outcome. Do not replay it or
                // leave the HTTP chunk waiting for an ACK from a dead process.
                for(long seq:inFlight)uncertainAcks.add(json.createObjectNode().put("type","ack").put("seq",seq).put("scope","sidecar_delivery_uncertain"));
                inFlight.clear();}
        }
        if(old!=null)old.close();
        uncertainAcks.forEach(events);
        boolean terminalFailure;String terminalReason;
        synchronized(this){terminalFailure=closed;terminalReason=lastReason!=null?lastReason:fallback==null?"ASR_NO_FALLBACK":"ASR_FALLBACK_UNAVAILABLE";}
        // The callback can acquire an outer failover transport's monitor while
        // that transport is closing this child. Never hold this monitor here.
        if(terminalFailure){failure.accept(terminalReason);return;}
        CompletableFuture.delayedExecutor(30,TimeUnit.SECONDS).execute(()->{
            boolean expired;synchronized(this){expired=!closed&&switching;}
            if(expired){note("ASR_FALLBACK_TIMEOUT");switchToLegacy(1);}
        });
        Thread worker=new Thread(()->{try{launch(fallback,1);}catch(Exception unavailable){note(unavailable.getMessage());switchToLegacy(1);}},"soniox-legacy-transition");worker.setDaemon(true);worker.start();
    }
    @Override public void send(String line)throws IOException{
        ConversateAsrBridge.Transport current=null;int sendingGeneration=0;JsonNode bufferedAck=null;boolean overflow=false;
        synchronized(this){
            if(closed||finishing)throw new IOException("asr_closed");
            if(switching){
                overflow=line==null||line.length()>12000||queued.size()>=2;
                if(!overflow){
                    var frame=json.readTree(line);long seq=frame.path("seq").asLong(-1);
                    if(seq<0)throw new IOException("asr_protocol");queued.addLast(line);acknowledged.add(seq);
                    bufferedAck=json.createObjectNode().put("type","ack").put("seq",seq).put("scope","legacy_transition_buffered").put("queueLength",queued.size());
                }
            }else{
                if(transport==null)throw new IOException("asr_not_ready");
                if(generation==0){try{var frame=json.readTree(line);if(frame.path("seq").isIntegralNumber())inFlight.add(frame.path("seq").asLong());}catch(IOException ignored){}}
                current=transport;sendingGeneration=generation;
            }
        }
        if(overflow){note("asr_fallback_queue_limit");switchToLegacy(1);throw new IOException("asr_fallback_queue_limit");}
        if(bufferedAck!=null){events.accept(bufferedAck);return;}
        try{current.send(line);}catch(IOException unavailable){
            if(sendingGeneration>0)throw unavailable;
            note(unavailable.getMessage());switchToLegacy(0);synchronized(this){if(closed)throw unavailable;}
        }
    }
    @Override public CompletableFuture<Void> finish(){
        ConversateAsrBridge.Transport current;
        synchronized(this){current=closed||switching?null:transport;if(current!=null)finishing=true;}
        if(current==null)return close().thenCompose(v->CompletableFuture.failedFuture(new IOException("asr_finish_unavailable")));
        return current.finish().whenComplete((v,e)->close());
    }
    @Override public CompletableFuture<Void> close(){
        List<ConversateAsrBridge.Transport> children;
        synchronized(this){
            closed=true;queued.clear();acknowledged.clear();inFlight.clear();var old=transport;transport=null;
            if(old!=null)retired.add(old);children=List.copyOf(retired);
        }
        return CompletableFuture.allOf(children.stream().map(ConversateAsrBridge.Transport::close).toArray(CompletableFuture[]::new));
    }
    @Override public boolean alive(){
        List<ConversateAsrBridge.Transport> children;
        synchronized(this){if(!closed)return true;children=List.copyOf(retired);}
        return children.stream().anyMatch(ConversateAsrBridge.Transport::alive);
    }
}
