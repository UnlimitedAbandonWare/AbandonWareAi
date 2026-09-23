package com.example.lms.assist;

import com.example.lms.service.stt.SonioxSttService;
import com.fasterxml.jackson.databind.*;
import reactor.core.Disposables;
import reactor.core.publisher.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Existing PCM/ACK contract; all audio buffers are bounded and volatile. */
final class SonioxAsrTransport implements ConversateAsrBridge.Transport {
    private final ObjectMapper json;private final Consumer<JsonNode> events;private final Consumer<String> failure;
    private final Sinks.Many<byte[]> audio=Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(8));
    private final reactor.core.Disposable.Swap wire=Disposables.swap(),deadline=Disposables.swap();
    private final AtomicBoolean closed=new AtomicBoolean();private volatile boolean ready,finishing;
    private final CompletableFuture<Void> finished=new CompletableFuture<>();
    private final String captureId="sx-"+UUID.randomUUID();private long bytes,revision;
    SonioxAsrTransport(ObjectMapper json,SonioxSttService service,Consumer<JsonNode> events,Consumer<String> failure)throws IOException {
        if(service==null||!service.isConfigured())throw new IOException("asr_unavailable");
        this.json=json;this.events=events;this.failure=failure;
        deadline.update(Mono.delay(Duration.ofSeconds(600)).subscribe(ignored->fail("ASR_CAPTURE_LIMIT")));
        wire.update(service.transcribePcm16Mono(audio.asFlux().doOnDiscard(byte[].class,b->Arrays.fill(b,(byte)0)),()->{
            if(!closed.get()){ready=true;events.accept(json.createObjectNode().put("type","ready"));}
        }).subscribe(result->{if(!closed.get())events.accept(json.createObjectNode().put("type","transcript")
                .put("utteranceId",captureId+"-"+result.utterance()).put("revision",++revision).put("text",result.text()).put("final",result.isFinal()));},
                error->fail(error.getMessage()!=null&&error.getMessage().matches("soniox:[a-z_]+")?error.getMessage():"ASR_STREAM_FAILED"),()->{
                    if(finishing&&closed.compareAndSet(false,true)){deadline.dispose();finished.complete(null);}else fail("ASR_STREAM_ENDED");
                }));
    }
    public synchronized void send(String line)throws IOException {
        byte[] pcm=null;
        try {
            if(closed.get()||finishing||!ready)throw new IOException("asr_not_ready");
            if(line==null||line.length()>12000)throw new IOException("asr_audio_limit");
            var frame=json.readTree(line);
            if(frame==null||!frame.path("seq").isIntegralNumber()||frame.path("seq").asLong()<0||!frame.path("pcm").isTextual())throw new IOException("asr_audio_invalid");
            pcm=Base64.getDecoder().decode(frame.path("pcm").asText());
            if(pcm.length==0||pcm.length>7680||pcm.length%640!=0||pcm.length>DeepgramAsrTransport.MAX_AUDIO_BYTES-bytes)throw new IOException("asr_audio_limit");
            int count=pcm.length;
            if(audio.tryEmitNext(pcm)!=Sinks.EmitResult.OK)throw new IOException("asr_queue_unavailable");
            pcm=null;bytes+=count;
            if(!closed.get())events.accept(json.createObjectNode().put("type","ack").put("seq",frame.path("seq").asLong()).put("scope","local_pcm_accepted"));
        }catch(Exception invalid){fail("ASR_INPUT_UNAVAILABLE");throw new IOException("asr_input_unavailable");}
        finally{if(pcm!=null)Arrays.fill(pcm,(byte)0);}
    }
    private void fail(String reason){if(closed.compareAndSet(false,true)){finished.completeExceptionally(new IOException(reason));dispose();failure.accept(reason);}}
    private synchronized void dispose(){deadline.dispose();wire.dispose();audio.tryEmitComplete();}
    public synchronized CompletableFuture<Void> finish(){
        if(finishing)return finished;
        if(closed.get())return CompletableFuture.failedFuture(new IOException("asr_closed"));
        finishing=true;audio.tryEmitComplete();
        CompletableFuture.delayedExecutor(3,TimeUnit.SECONDS).execute(()->{if(!finished.isDone())fail("ASR_FINISH_TIMEOUT");});
        return finished;
    }
    public CompletableFuture<Void> close(){if(closed.compareAndSet(false,true)){finished.completeExceptionally(new IOException("asr_closed"));dispose();}return CompletableFuture.completedFuture(null);}
    public boolean alive(){return !closed.get();}
}
