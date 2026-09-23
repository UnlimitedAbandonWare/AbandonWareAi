package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Loopback WebSocket adapter. A node ACK proves local SDK acceptance, never provider receipt. */
final class SonioxNodeTransport implements ConversateAsrBridge.Transport,WebSocket.Listener {
    private final ObjectMapper json;
    private final Consumer<JsonNode> events;
    private final Consumer<String> failure;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<Void> ready=new CompletableFuture<>();
    private final CompletableFuture<Void> finished=new CompletableFuture<>();
    private final StringBuilder message=new StringBuilder();
    private final String captureId="sx-"+java.util.UUID.randomUUID();
    private volatile WebSocket socket;
    private volatile boolean published;
    private volatile boolean finishing;
    private volatile String failureReason="ASR_SIDECAR_FAILED";
    SonioxNodeTransport(SonioxSidecarManager.Endpoint endpoint,ObjectMapper json,Consumer<JsonNode> events,Consumer<String> failure,Duration timeout)throws IOException{
        this.json=json;this.events=events;this.failure=failure;
        var connecting=HttpClient.newBuilder().connectTimeout(timeout).build().newWebSocketBuilder().connectTimeout(timeout)
                .header("Authorization","Bearer "+endpoint.bearer()).buildAsync(endpoint.streamUri(),this);
        try{
            long end=System.nanoTime()+timeout.toNanos();
            socket=connecting.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            ready.get(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS);
            synchronized(this){if(closed.get())throw new IOException();
            published=true;events.accept(json.createObjectNode().put("type","ready").set("runtime",json.createObjectNode().put("provider","soniox").put("device","remote").put("reason","primary").put("transport","node_sdk")));}
        }catch(Exception unavailable){close();connecting.whenComplete((ws,e)->{if(ws!=null)ws.abort();});if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException(failureReason);}
    }
    @Override public void onOpen(WebSocket webSocket){socket=webSocket;if(closed.get())webSocket.abort();else webSocket.request(1);}
    @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
        if(closed.get())return null;
        try{
            if(message.length()+data.length()>16384)throw new IOException();message.append(data);
            if(last){var event=json.readTree(message.toString());message.setLength(0);
                String type=event.path("type").asText();
                if(type.equals("ready"))ready.complete(null);
                else if(type.equals("error"))fail(safeFailure(event.path("reason").asText()));
                else if(type.equals("finished")){
                    if(!finishing)fail("ASR_STREAM_ENDED");
                    else {closed.set(true);finished.complete(null);ws.sendClose(1000,"finished");}
                }
                else if(type.equals("ack")||type.equals("transcript")||type.equals("progress")){if(published){
                    if(type.equals("transcript")&&event instanceof com.fasterxml.jackson.databind.node.ObjectNode object){String id=object.path("utteranceId").asText();if(!id.matches("sx-[0-9]{1,10}"))throw new IOException();object.put("utteranceId",captureId+id.substring(2));}
                    events.accept(event);}}
                else fail();
            }
        }catch(Exception invalid){fail();}
        if(!closed.get())ws.request(1);return null;
    }
    @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){fail();return null;}
    @Override public void onError(WebSocket ws,Throwable error){fail();}
    private static String safeFailure(String reason){return java.util.Set.of("ASR_AUTH_FAILED","ASR_QUOTA_EXCEEDED","ASR_RATE_LIMITED","ASR_AUDIO_FORMAT_INVALID","ASR_PROVIDER_FAILED","ASR_PROVIDER_DISCONNECTED","ASR_CONNECT_TIMEOUT","ASR_CAPTURE_LIMIT","ASR_PROTOCOL_FAILED","ASR_TRANSCRIPT_LIMIT","ASR_BACKPRESSURE","ASR_FINISH_TIMEOUT","ASR_STREAM_ENDED").contains(reason)?reason:"ASR_PROVIDER_FAILED";}
    private void fail(){fail("ASR_SIDECAR_FAILED");}
    private void fail(String reason){boolean notify=false;synchronized(this){if(closed.compareAndSet(false,true)){failureReason=reason;ready.completeExceptionally(new IOException(reason));finished.completeExceptionally(new IOException(reason));var ws=socket;if(ws!=null)ws.abort();notify=published;}}if(notify)failure.accept(reason);}
    @Override public void send(String line)throws IOException{
        if(closed.get()||finishing||socket==null||line==null||line.length()>12000)throw new IOException("soniox_stream_unavailable");
        try{socket.sendText(line,true).get(1500,TimeUnit.MILLISECONDS);}
        catch(Exception failed){fail();throw new IOException("soniox_send_failed");}
    }
    @Override public synchronized CompletableFuture<Void> finish(){
        if(finishing)return finished;
        if(closed.get())return CompletableFuture.failedFuture(new IOException("soniox_closed"));
        finishing=true;
        socket.sendText("{\"type\":\"finish\"}",true).whenComplete((v,e)->{if(e!=null)fail();});
        CompletableFuture.delayedExecutor(4,TimeUnit.SECONDS).execute(()->{if(!finished.isDone())fail("ASR_FINISH_TIMEOUT");});
        return finished;
    }
    @Override public synchronized CompletableFuture<Void> close(){closed.set(true);ready.completeExceptionally(new IOException("soniox_closed"));finished.completeExceptionally(new IOException("soniox_closed"));var ws=socket;if(ws!=null)ws.abort();return CompletableFuture.completedFuture(null);}
    @Override public boolean alive(){return !closed.get();}
}
