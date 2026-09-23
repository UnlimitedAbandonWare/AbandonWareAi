package com.example.lms.assist;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded process-local observations. Price and latency are heuristics, never quality or billing proof. */
final class ConversateSttRouting {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ConversateSttRouting.class);
    private final LongSupplier nanos;
    private final long cooldownNanos;
    private final Map<String,Stats> providers=new LinkedHashMap<>();
    ConversateSttRouting(LongSupplier nanos,Duration cooldown){
        this.nanos=nanos;cooldownNanos=cooldown.toNanos();
        providers.put("deepgram",new Stats());providers.put("soniox",new Stats());providers.put("groq",new Stats());providers.put("gemini",new Stats());
    }
    synchronized boolean admitted(String provider){return providers.get(provider).openUntil<=nanos.getAsLong();}
    synchronized String circuit(String provider){var s=providers.get(provider);return !admitted(provider)?"OPEN":s.consecutiveFailures>=2?"HALF_OPEN":"CLOSED";}
    synchronized int errors(String provider){return providers.get(provider).consecutiveFailures;}
    synchronized String reason(String provider){return providers.get(provider).reason;}
    synchronized List<String> order(List<String> configured,double seconds,Map<String,Long> rates){
        return configured.stream().filter(this::admitted).sorted(Comparator.comparingDouble(p->score(p,seconds,rates.get(p)))).toList();
    }
    private double score(String p,double seconds,long rate){
        var s=providers.get(p);double failureRate=s.outcomes.isEmpty()?0:s.outcomes.stream().filter(v->!v).count()/(double)s.outcomes.size();
        return seconds*rate/60.0+failureRate*10_000+(s.latencies.size()<3?0:p95(s.latencies)/10.0);
    }
    synchronized Attempt begin(String provider,long rate){return begin(provider,rate,"complete_utterance");}
    synchronized Attempt begin(String provider,long rate,String kind){var s=providers.get(provider);s.attempts++;s.lastStartedAt=System.currentTimeMillis();s.inputKind=kind;return new Attempt(provider,rate,nanos.getAsLong());}
    synchronized Map<String,Object> view(Map<String,Long> rates){
        var result=new LinkedHashMap<String,Object>();
        providers.forEach((p,s)->{
            var row=new LinkedHashMap<String,Object>();
            row.put("attempts",s.attempts);row.put("handshakes",s.handshakes);row.put("successes",s.successes);
            row.put("failures",s.failures);row.put("cancelled",s.cancelled);row.put("acceptedAudioMs",s.audioBytes/32);
            row.put("audioBytes",s.audioBytes);row.put("attemptWallMs",s.wallMs);row.put("connectedWallMs",s.connectedWallMs);
            row.put("lastStartedAt",s.lastStartedAt);row.put("lastConnectedAt",s.lastConnectedAt);row.put("lastClosedAt",s.lastClosedAt);row.put("inputKind",s.inputKind);
            row.put("estimatedMicros",s.estimatedMicros);row.put("estimatedMicrosPerMinute",rates.getOrDefault(p,p.equals("groq")?0L:8000L));
            row.put("latencySamples",s.latencies.size());row.put("firstFinalLatencyP95Ms",s.latencies.isEmpty()?-1:p95(s.latencies));
            row.put("failureRate",s.outcomes.isEmpty()?-1:s.outcomes.stream().filter(v->!v).count()/(double)s.outcomes.size());
            row.put("circuit",circuit(p));row.put("reason",s.reason);row.put("scope","process_since_start");
            row.put("audioEvidence","local_pcm_accepted_not_provider_receipt");row.put("costEvidence","configured_rate_estimate_not_invoice");
            row.put("estimateBasis",p.equals("soniox")?"max_attempt_wall_and_audio_duration_including_keepalive":"local_accepted_pcm_duration");
            result.put(p,Map.copyOf(row));
        });return Map.copyOf(result);
    }
    private static long p95(ArrayDeque<Long> samples){long[] sorted=samples.stream().mapToLong(Long::longValue).sorted().toArray();return sorted[(int)Math.ceil(sorted.length*.95)-1];}
    private static <T> void bounded(ArrayDeque<T> queue,T value){if(queue.size()==32)queue.removeFirst();queue.addLast(value);}
    final class Attempt implements AutoCloseable {
        private final String provider;private final long rate,started;
        private long bytes,connectedNanos;private boolean closed,connected,finalSeen,failed;
        Attempt(String provider,long rate,long started){this.provider=provider;this.rate=rate;this.started=started;}
        void connected(){synchronized(ConversateSttRouting.this){if(!closed&&!connected){connected=true;connectedNanos=nanos.getAsLong();var s=providers.get(provider);s.handshakes++;s.lastConnectedAt=System.currentTimeMillis();}}}
        void audio(long count){synchronized(ConversateSttRouting.this){if(!closed&&count>0)bytes+=count;}}
        void success(){synchronized(ConversateSttRouting.this){if(closed||finalSeen||failed)return;finalSeen=true;var s=providers.get(provider);s.consecutiveFailures=0;s.openUntil=0;s.reason="verified_transcript";bounded(s.latencies,Math.max(0,(nanos.getAsLong()-started)/1_000_000));}}
        void failure(String reason){synchronized(ConversateSttRouting.this){if(closed||failed)return;failed=true;var s=providers.get(provider);s.failures++;s.consecutiveFailures=Math.min(1000,s.consecutiveFailures+1);s.reason=reason;if(s.consecutiveFailures>=2)s.openUntil=nanos.getAsLong()+cooldownNanos;}}
        public void close(){synchronized(ConversateSttRouting.this){if(closed)return;closed=true;var s=providers.get(provider);long wall=Math.max(0,(nanos.getAsLong()-started)/1_000_000);s.audioBytes+=bytes;s.wallMs+=wall;if(connected)s.connectedWallMs+=Math.max(0,(nanos.getAsLong()-connectedNanos)/1_000_000);s.lastClosedAt=System.currentTimeMillis();
            double duration=provider.equals("soniox")?Math.max(wall,bytes/32.0):bytes/32.0;s.estimatedMicros+=(long)Math.ceil(duration*rate/60_000.0);if(failed)bounded(s.outcomes,false);else if(finalSeen){s.successes++;bounded(s.outcomes,true);}else s.cancelled++;
            LOG.info("stt.usage provider={} attempts={} handshakes={} successes={} failures={} cancelled={} acceptedAudioMs={} estimatedMicros={} scope=process reason={}",provider,s.attempts,s.handshakes,s.successes,s.failures,s.cancelled,s.audioBytes/32,s.estimatedMicros,s.reason);
        }}
    }
    private static final class Stats {
        long attempts,handshakes,successes,failures,cancelled,audioBytes,estimatedMicros,openUntil,wallMs,connectedWallMs,lastStartedAt,lastConnectedAt,lastClosedAt;
        String inputKind="not_observed";
        int consecutiveFailures;String reason="not_observed";
        final ArrayDeque<Long> latencies=new ArrayDeque<>();final ArrayDeque<Boolean> outcomes=new ArrayDeque<>();
    }
}
