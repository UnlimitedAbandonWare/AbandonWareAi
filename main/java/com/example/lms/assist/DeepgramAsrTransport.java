package com.example.lms.assist;

import com.example.lms.service.stt.DeepgramSttService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Adapts the existing service. ACK means bounded local PCM acceptance, not provider receipt. */
final class DeepgramAsrTransport implements ConversateAsrBridge.Transport {
    static final long MAX_AUDIO_BYTES=600L*16_000*2;
    private final ObjectMapper json;
    private final Consumer<JsonNode> events;
    private final Consumer<String> failure;
    private final Sinks.Many<byte[]> audio=Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(8));
    private final Disposable.Swap wire=Disposables.swap(), deadline=Disposables.swap();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<Void> finished=new CompletableFuture<>();
    private boolean finishing;
    private final LinkedHashMap<String,String> segments=new LinkedHashMap<>();
    private final LinkedHashMap<String,List<DeepgramSttService.Word>> segmentWords=new LinkedHashMap<>();
    private double confirmedThrough=-1,confirmedWordThrough=-1;
    private final long byteLimit;
    private volatile boolean ready;
    private long acceptedBytes,utterance,revision;
    private double closedThrough=-1;
    private String lastUntimedFinal="";

    DeepgramAsrTransport(ObjectMapper json,DeepgramSttService service,Consumer<JsonNode> events,Consumer<String> failure)throws IOException {
        this(json,service,events,failure,Duration.ofSeconds(600),MAX_AUDIO_BYTES);
    }
    DeepgramAsrTransport(ObjectMapper json,DeepgramSttService service,Consumer<JsonNode> events,Consumer<String> failure,Duration wallLimit,long byteLimit)throws IOException {
        if(service==null||!service.isConfigured())throw new IOException("asr_unavailable");
        if(wallLimit.isNegative()||wallLimit.isZero()||wallLimit.compareTo(Duration.ofSeconds(600))>0||byteLimit<1||byteLimit>MAX_AUDIO_BYTES)throw new IllegalArgumentException("asr_limit");
        this.json=json;this.events=events;this.failure=failure;this.byteLimit=byteLimit;
        deadline.update(Mono.delay(wallLimit).subscribe(ignored->fail("ASR_CAPTURE_LIMIT")));
        wire.update(service.transcribePcm16Mono(audio.asFlux(),16000,"ko",this::connected)
                .subscribe(this::transcript,error->fail(failureReason(error)),this::completed));
    }
    private static String failureReason(Throwable error){
        String message=error.getMessage();
        if(message==null)return "ASR_STREAM_FAILED";
        return switch(message){
            case "deepgram:http_401","deepgram:http_403" -> "ASR_AUTH_FAILED";
            case "deepgram:http_402" -> "ASR_QUOTA_EXCEEDED";
            case "deepgram:http_429" -> "ASR_RATE_LIMITED";
            case "deepgram:http_400" -> "ASR_AUDIO_FORMAT_INVALID";
            case "deepgram:provider_error" -> "ASR_PROVIDER_FAILED";
            default -> message.matches("deepgram:[a-z_]{1,36}")
                    ?"ASR_"+message.substring(9).toUpperCase(Locale.ROOT):"ASR_STREAM_FAILED";
        };
    }
    private synchronized void connected(){
        if(closed.get()||finishing)return;
        ready=true;var event=json.createObjectNode().put("type","ready");
        event.putObject("runtime").put("provider","deepgram").put("transport","java_ws").put("device","remote").put("reason","primary");events.accept(event);
    }
    @Override public synchronized void send(String line)throws IOException {
        if(closed.get()||finishing||!ready)throw new IOException("asr_not_ready");
        try {
            if(line==null||line.length()>12000)throw new IOException("asr_audio_limit");
            var frame=json.readTree(line);
            if(frame==null||!frame.path("seq").isIntegralNumber()||frame.path("seq").asLong()<0||!frame.path("pcm").isTextual())throw new IOException("asr_audio_invalid");
            byte[] bytes=Base64.getDecoder().decode(frame.path("pcm").asText());
            if(bytes.length==0||bytes.length>7680||bytes.length%640!=0||bytes.length>byteLimit-acceptedBytes)throw new IOException("asr_audio_limit");
            if(closed.get()||audio.tryEmitNext(bytes)!=Sinks.EmitResult.OK)throw new IOException("asr_queue_unavailable");
            acceptedBytes+=bytes.length;
            if(!closed.get())events.accept(json.createObjectNode().put("type","ack").put("seq",frame.path("seq").asLong()).put("scope","local_pcm_accepted"));
        } catch(Exception invalid) {fail("ASR_INPUT_UNAVAILABLE");throw new IOException("asr_input_unavailable");}
    }
    private synchronized void transcript(DeepgramSttService.Transcript result) {
        if(closed.get())return;
        if("SpeechStarted".equals(result.eventType()))return;
        if("UtteranceEnd".equals(result.eventType())){
            if(!segments.isEmpty()&&result.start()>=confirmedWordThrough&&confirmedThrough>closedThrough){
                var words=segmentWords.values().stream().flatMap(List::stream).toList();
                emitTranscript(String.join(" ",segments.values()),true,null,words);utterance++;
                closedThrough=Math.max(closedThrough,confirmedThrough);segments.clear();segmentWords.clear();
            }
            return;
        }
        String text=result.transcript();
        if(text==null||text.length()>8192){fail("ASR_TRANSCRIPT_LIMIT");return;}
        boolean timed=Double.isFinite(result.start())&&Double.isFinite(result.duration())&&result.start()>=0&&result.duration()>=0;
        double end=result.start()+result.duration();
        if(timed&&end<=closedThrough)return;
        boolean confirmed=result.isFinal()||result.speechFinal();
        String identity=timed?result.start()+":"+result.duration():"untimed:"+text;
        if(confirmed&&!text.isBlank()){
            segments.putIfAbsent(identity,text);segmentWords.putIfAbsent(identity,result.words());
            if(timed){confirmedThrough=Math.max(confirmedThrough,end);confirmedWordThrough=Math.max(confirmedWordThrough,result.words().stream().mapToDouble(DeepgramSttService.Word::end).max().orElse(end));}
        }
        if(segments.size()>128){fail("ASR_TRANSCRIPT_LIMIT");return;}
        String assembled=String.join(" ",segments.values());
        if(!confirmed&&!text.isBlank())assembled=assembled.isBlank()?text:assembled+" "+text;
        if(assembled.length()>8192){fail("ASR_TRANSCRIPT_LIMIT");return;}
        var words=new ArrayList<DeepgramSttService.Word>();segmentWords.values().forEach(words::addAll);
        if(!confirmed)words.addAll(result.words());
        if(words.size()>1024){fail("ASR_TRANSCRIPT_LIMIT");return;}
        Double confidence=assembled.equals(text)?result.confidence():null;
        if(result.speechFinal()){
            if(!assembled.isBlank()&&(timed||!assembled.equals(lastUntimedFinal))){emitTranscript(assembled,true,confidence,words);utterance++;lastUntimedFinal=assembled;}
            segments.clear();segmentWords.clear();if(timed)closedThrough=Math.max(closedThrough,end);
        }else if(!assembled.isBlank()){if(!confirmed)lastUntimedFinal="";emitTranscript(assembled,false,confidence,words);}
    }
    private void emitTranscript(String text,boolean complete,Double confidence,List<DeepgramSttService.Word> words){
        if(!closed.get()){
            var event=json.createObjectNode().put("type","transcript").put("utteranceId","dg-"+utterance).put("revision",++revision).put("final",complete).put("text",text);
            if(confidence!=null)event.put("confidence",confidence);
            event.set("words",json.valueToTree(words));events.accept(event);
        }
    }
    @Override public synchronized CompletableFuture<Void> finish(){
        if(finishing||closed.get())return finished;
        if(!ready){fail("ASR_FINISH_UNCONFIRMED");return finished;}
        finishing=true;
        deadline.update(Mono.delay(Duration.ofSeconds(3)).subscribe(ignored->fail("ASR_FINISH_TIMEOUT")));
        // Completing input invokes the existing service's CloseStream and terminal drain.
        if(audio.tryEmitComplete()!=Sinks.EmitResult.OK)fail("ASR_FINISH_UNCONFIRMED");
        return finished;
    }
    private synchronized void completed(){
        if(closed.get())return;
        if(!finishing){fail("ASR_STREAM_ENDED");return;}
        // A drained final segment need not carry speech_final. Never promote an interim.
        if(!segments.isEmpty()){
            emitTranscript(String.join(" ",segments.values()),true,null,segmentWords.values().stream().flatMap(List::stream).toList());
            utterance++;
        }
        if(closed.compareAndSet(false,true)){dispose();finished.complete(null);}
    }
    private synchronized void fail(String reason){if(closed.compareAndSet(false,true)){dispose();finished.completeExceptionally(new IOException(reason));failure.accept(reason);}}
    private synchronized void dispose(){deadline.dispose();wire.dispose();audio.tryEmitComplete();segments.clear();segmentWords.clear();}
    @Override public synchronized CompletableFuture<Void> close(){if(closed.compareAndSet(false,true)){dispose();finished.completeExceptionally(new IOException("ASR_CANCELLED"));}return CompletableFuture.completedFuture(null);}
    @Override public boolean alive(){return !closed.get();}
    long acceptedBytes(){return acceptedBytes;}
}
