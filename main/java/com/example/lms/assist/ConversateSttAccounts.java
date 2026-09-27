package com.example.lms.assist;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.config.DeepgramProperties;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Read-only, opt-in account reconciliation. Never returns credentials, project IDs or account balances. */
@Service
public final class ConversateSttAccounts {
    interface Fetch {JsonNode get(URI uri,String authorization)throws Exception;}
    private final boolean enabled;private final String deepgramKey,project,sonioxKey;
    private final Clock clock;private final Fetch fetch;
    private long refreshedAt=-1;
    private Map<String,Object> deepgram=unknown("not_observed"),soniox=unknown("not_observed");
    @Autowired
    public ConversateSttAccounts(ObjectMapper json,ObjectProvider<DeepgramProperties> properties,
            @Value("${conversate.asr.cloud.accounts.enabled:false}") boolean enabled,
            @Value("${deepgram.project-id:${DEEPGRAM_PROJECT_ID:}}") String project,
            @Value("${soniox.api-key:${SONIOX_API_KEY:}}") String sonioxKey){
        this(enabled,properties.getIfAvailable()==null?"":properties.getIfAvailable().getApiKey(),project,sonioxKey,Clock.systemUTC(),http(json));
    }
    ConversateSttAccounts(boolean enabled,String deepgramKey,String project,String sonioxKey,Clock clock,Fetch fetch){
        this.enabled=enabled;this.deepgramKey=clean(deepgramKey);this.project=Objects.toString(project,"");this.sonioxKey=clean(sonioxKey);this.clock=clock;this.fetch=fetch;
    }
    private static String clean(String key){return ConfigValueGuards.isMissing(key)?"":key.trim();}
    private static Fetch http(ObjectMapper json){
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri,auth)->{
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("Authorization",auth).header("Accept","application/json").GET().build();
            // Body completion is covered by the same bounded request timeout.
            var pending=client.sendAsync(request,limitedBody());
            HttpResponse<byte[]> response;
            try{response=pending.get(3,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){pending.cancel(true);throw e;}
            if(response.statusCode()!=200)throw new IOException("http_"+response.statusCode());
            return json.readTree(response.body());
        };
    }
    private static HttpResponse.BodyHandler<byte[]> limitedBody(){
        return ignored->new HttpResponse.BodySubscriber<>(){
            final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
            final ByteArrayOutputStream bytes=new ByteArrayOutputStream();java.util.concurrent.Flow.Subscription subscription;
            public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
            public void onNext(List<java.nio.ByteBuffer> chunks){for(var b:chunks){if(bytes.size()+b.remaining()>262144){subscription.cancel();result.completeExceptionally(new IOException("response_limit"));return;}byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}subscription.request(1);}
            public void onError(Throwable error){result.completeExceptionally(new IOException("transport_error"));}
            public void onComplete(){result.complete(bytes.toByteArray());}
        };
    }
    synchronized void refreshIfDue(){
        long now=clock.millis();if(!enabled||(refreshedAt>=0&&now-refreshedAt>=0&&now-refreshedAt<300_000))return;
        refreshedAt=now; // Cache failures too. No retry loop, startup call or scheduler.
        deepgram=unknown("not_configured");soniox=unknown("not_configured");
        if(!deepgramKey.isBlank()&&project.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")){
            try{deepgram=parseBalance(fetch.get(URI.create("https://api.deepgram.com/v1/projects/"+project+"/balances"),"Token "+deepgramKey));}
            catch(Exception e){deepgram=unknown(reason(e));if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        }
        if(!sonioxKey.isBlank()&&!Thread.currentThread().isInterrupted()){
            var start=YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            // Whole UTC days: exclude the current incomplete day to keep the comparison stable.
            var end=clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            if(end.isAfter(start))try{soniox=parseUsage(fetch.get(URI.create("https://api.soniox.com/v1/usage/summary?start_time="+start+"&end_time="+end),"Bearer "+sonioxKey));}
            catch(Exception e){soniox=unknown(reason(e));if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        }
    }
    static Map<String,Object> parseBalance(JsonNode root)throws IOException{
        var balances=root.path("balances");if(!balances.isArray()||balances.size()==0||balances.size()>128)throw new IOException("invalid_response");
        BigDecimal amount=BigDecimal.ZERO;
        for(var b:balances){if(!"USD".equals(b.path("units").asText())||!b.path("amount").isNumber())throw new IOException("invalid_response");amount=amount.add(b.path("amount").decimalValue());}
        return Map.of("state","observed","funds",amount.signum()>0?"available":"exhausted","freeCredit","not_distinguished_by_balance_api");
    }
    static Map<String,Object> parseUsage(JsonNode root)throws IOException{
        var total=root.path("total");var count=total.path("total_num_requests");var ms=total.path("total_input_audio_duration_ms");
        if(!count.isIntegralNumber()||!count.canConvertToLong()||count.asLong()<0||!ms.isIntegralNumber()||!ms.canConvertToLong()||ms.asLong()<0||!total.path("total_cost_usd").isTextual())throw new IOException("invalid_response");
        try{if(new BigDecimal(total.path("total_cost_usd").asText()).signum()<0)throw new NumberFormatException();}catch(NumberFormatException e){throw new IOException("invalid_response");}
        return Map.of("state","observed","funds","not_observed","requests",count.asLong(),"inputAudioMs",ms.asLong(),"costObserved",true,"scope","project_all_models_completed_utc_days");
    }
    synchronized boolean exhausted(String provider){return fresh()&&("deepgram".equals(provider)&&"exhausted".equals(deepgram.get("funds"))||"soniox".equals(provider)&&"exhausted".equals(soniox.get("funds")));}
    private boolean fresh(){long age=clock.millis()-refreshedAt;return enabled&&refreshedAt>=0&&age>=0&&age<300_000;}
    public synchronized Map<String,Object> diagnostics(){return Map.of("enabled",enabled,"fresh",fresh(),"observedAt",Math.max(0,refreshedAt),"deepgram",deepgram,"soniox",soniox);}
    private static Map<String,Object> unknown(String reason){return Map.of("state",reason,"funds","not_observed");}
    private static String reason(Exception e){String r=e.getMessage();return r!=null&&r.matches("http_[0-9]{3}|invalid_response|response_limit")?r:"transport_error";}
    @Override public String toString(){return "ConversateSttAccounts[redacted]";}
}
