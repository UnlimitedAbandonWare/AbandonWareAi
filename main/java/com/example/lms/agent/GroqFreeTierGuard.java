package com.example.lms.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static java.nio.file.StandardOpenOption.*;

/** Mandatory Groq admission. Shared reservations are app estimates, never organization balances. */
@Component
public final class GroqFreeTierGuard {
    public static final String LIMIT_SOURCE="https://console.groq.com/docs/rate-limits";
    public static final String LIMIT_CHECKED="2026-09-16";
    private static final long DAY=86_400_000L, MAX_LEDGER=4_194_304;
    private static final Map<String,long[]> PUBLISHED=Map.of(
            "whisper-large-v3-turbo",new long[]{20,2000,0,0,7200,28800},
            "openai/gpt-oss-20b",new long[]{30,1000,8000,200000,0,0},
            "openai/gpt-oss-120b",new long[]{30,1000,8000,200000,0,0});
    private static final String[] DIMENSIONS={"rpm","rpd","tpm","tpd","ash","asd"};
    private final Environment env; private final ObjectMapper json; private final Clock clock;
    private volatile String failedClosed="";
    public record Reservation(String model,long sequence,long at,String organization) {}
    @Autowired public GroqFreeTierGuard(Environment env){this(env,new ObjectMapper(),Clock.systemUTC());}
    public GroqFreeTierGuard(Environment env,ObjectMapper json,Clock clock){this.env=env;this.json=json;this.clock=clock;}

    public boolean eligible(String model,String key){try{proof(model,key);return failedClosed.isEmpty();}catch(IOException|RuntimeException denied){return false;}}
    public String disabledReason(String model,String key){try{proof(model,key);return failedClosed;}catch(IOException|RuntimeException denied){return "groq_free_account_evidence_needed";}}
    public boolean speechVerified(String key){try{return proof("whisper-large-v3-turbo",key).path("speechVerified").asBoolean(false);}catch(Exception denied){return false;}}
    public static boolean isGroq(String url){try{return "api.groq.com".equalsIgnoreCase(java.net.URI.create(url).getHost());}catch(Exception ignored){return false;}}
    public static String keyHash(String key){return org.apache.commons.codec.digest.DigestUtils.sha256Hex(Objects.toString(key,""));}
    public static String ledgerPathHash(String value)throws IOException{
        String path=safePath(value).toString().replace('\\','/');
        return keyHash(java.io.File.separatorChar=='\\'?path.toLowerCase(Locale.ROOT):path);
    }
    private JsonNode proof(String model,String key)throws IOException{
        if(!failedClosed.isEmpty())throw denied(failedClosed);
        if(!PUBLISHED.containsKey(model)||key==null||key.isBlank())throw denied("groq_model_or_key_unverified");
        Path path=safePath(env.getProperty("groq.free-tier.evidence","data/usage/groq-free-plan.json"));
        if(!Files.isRegularFile(path)||Files.size(path)>32768)throw denied("groq_free_account_evidence_needed");
        JsonNode p=json.readTree(Files.readAllBytes(path));long now=clock.millis(),at=p.path("verifiedAtMs").asLong(0);
        if(!"free".equals(p.path("plan").asText())||at<=0||now<at||now-at>DAY||p.path("expiresAtMs").asLong(0)<now
                ||!p.path("keySha256").asText().equals(keyHash(key))||!p.path("organizationHash").asText().matches("[a-f0-9]{64}")
                ||!"shared_ledger".equals(p.path("coordination").asText())
                ||!p.path("ledgerPathSha256").asText().equals(ledgerPathHash(env.getProperty("groq.free-tier.ledger","data/usage/groq-free-reservations.jsonl"))))
            throw denied("groq_free_account_evidence_needed");
        for(int i=0;i<DIMENSIONS.length;i++)if(PUBLISHED.get(model)[i]>0&&p.path("limits").path(model).path(DIMENSIONS[i]).asLong(0)<=0)
            throw denied("groq_account_limits_unverified");
        return p;
    }
    /** Reserve once before each actual wire attempt. No refund for a timeout or ambiguous delivery. */
    public Reservation reserve(String model,String key,long tokens,long audioSeconds)throws IOException{
        JsonNode p=proof(model,key);long now=clock.millis();
        if(tokens<0||tokens>1_000_000||audioSeconds<0||audioSeconds>16
                ||(model.startsWith("whisper")?(audioSeconds==0||tokens!=0):(tokens==0||audioSeconds!=0)))throw denied("groq_request_dimensions_invalid");
        double margin=env.getProperty("groq.free-tier.safety-margin",Double.class,.1);
        if(!Double.isFinite(margin)||margin<0||margin>=1)throw denied("groq_margin_invalid");
        try(Ledger ledger=open()){
            List<JsonNode> rows=ledger.rows;String organization=p.path("organizationHash").asText();
            // A first denied reservation must not leave an ambiguous empty ledger.
            if(rows.isEmpty())ledger.append(json.createObjectNode().put("kind","observe").put("at",now)
                    .put("organization",organization).put("model",model).put("status",0));
            for(JsonNode row:rows){
                if(!organization.equals(row.path("organization").asText()))throw denied("groq_ledger_organization_mismatch");
                if(row.path("at").asLong()>now)throw denied("groq_clock_rollback");
                if(row.path("status").asInt()==401||row.path("status").asInt()==403)throw denied("groq_auth_latched");
                if(row.path("model").asText().equals(model)&&row.path("retryUntil").asLong()>now)throw denied("groq_retry_after");
            }
            long[] windows={60_000,DAY,60_000,DAY,3_600_000,DAY};
            long seconds=audioSeconds==0?0:Math.max(10,audioSeconds); // conservative minimum; never claim billed usage
            long[] amount={1,1,tokens,tokens,seconds,seconds};
            for(int i=0;i<DIMENSIONS.length;i++){
                if(PUBLISHED.get(model)[i]==0)continue;
                long cap=(long)Math.floor(Math.min(PUBLISHED.get(model)[i],p.path("limits").path(model).path(DIMENSIONS[i]).asLong())*(1-margin));
                String field=i<2?"requests":i<4?"tokens":"audioSeconds";
                long used=0;for(JsonNode row:rows)if(row.path("kind").asText().equals("reserve")&&row.path("model").asText().equals(model)
                        &&now-row.path("at").asLong()<windows[i])used=Math.addExact(used,row.path(field).asLong());
                if(amount[i]>cap-used)throw denied("groq_"+DIMENSIONS[i]+"_exhausted");
            }
            for(JsonNode row:rows)if(row.path("model").asText().equals(model)&&"observe".equals(row.path("kind").asText())){
                for(String field:List.of("requests","tokens")){
                    if(!row.has(field+"Remaining")||row.path(field+"Until").asLong()<=now)continue;
                    long spent=0;for(int i=(int)row.path("origin").asLong()+1;i<rows.size();i++){
                        JsonNode other=rows.get(i);if("reserve".equals(other.path("kind").asText())&&model.equals(other.path("model").asText()))spent+=other.path(field).asLong();
                    }
                    if((field.equals("requests")?1:tokens)>row.path(field+"Remaining").asLong()-spent)throw denied("groq_header_"+field+"_exhausted");
                }
            }
            long seq=rows.size();
            ledger.append(json.createObjectNode().put("kind","reserve").put("at",now).put("organization",organization).put("model",model)
                    .put("requests",1).put("tokens",tokens).put("audioSeconds",seconds));
            return new Reservation(model,seq,now,organization);
        }catch(ArithmeticException invalid){throw denied("groq_ledger_invalid");}
    }
    public void observe(Reservation reservation,int status,Map<String,List<String>> headers){
        if(reservation==null)return;
        try(Ledger ledger=open()){
            long now=clock.millis();
            ObjectNode row=json.createObjectNode().put("kind","observe").put("at",now).put("organization",reservation.organization())
                    .put("model",reservation.model()).put("origin",reservation.sequence()).put("status",status);
            if(status==429)row.put("retryUntil",now+retryAfter(headers,now));
            for(String field:List.of("requests","tokens")){
                String remaining=header(headers,"x-ratelimit-remaining-"+field);
                if(remaining.matches("[0-9]{1,9}")){
                    row.put(field+"Remaining",Long.parseLong(remaining));
                    row.put(field+"Until",now+duration(header(headers,"x-ratelimit-reset-"+field),field.equals("requests")?DAY:60000));
                }
            }
            ledger.append(row);
        }catch(Exception unavailable){failedClosed="groq_ledger_observation_failed";}
    }
    static long retryAfter(Map<String,List<String>> headers,long now){
        String text=header(headers,"retry-after");
        try{double seconds=Double.parseDouble(text);if(Double.isFinite(seconds)&&seconds>=0)return Math.max(1000,Math.min(DAY,(long)Math.ceil(seconds*1000)));}catch(Exception ignored){}
        try{return Math.max(1000,Math.min(DAY,ZonedDateTime.parse(text,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now));}catch(Exception ignored){return 60000;}
    }
    private static long duration(String text,long fallback){
        var matcher=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)").matcher(text);long total=0;int end=0;
        while(matcher.find()){if(matcher.start()!=end)return fallback;double factor=switch(matcher.group(2)){case "h"->3600000;case "m"->60000;case "s"->1000;default->1;};total+=(long)Math.ceil(Double.parseDouble(matcher.group(1))*factor);end=matcher.end();}
        return end==text.length()&&total>0?Math.min(DAY,total):fallback;
    }
    private static String header(Map<String,List<String>> headers,String key){
        if(headers==null)return "";return headers.entrySet().stream().filter(e->key.equalsIgnoreCase(e.getKey())&&e.getValue()!=null&&!e.getValue().isEmpty()).map(e->Objects.toString(e.getValue().get(0),"")).findFirst().orElse("");
    }
    private static IOException denied(String reason){return new IOException(reason);}
    private static Path safePath(String value)throws IOException{
        Path path=Path.of(value).toAbsolutePath().normalize();for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw denied("groq_unsafe_path");return path;
    }
    private Ledger open()throws IOException{
        Path path=safePath(env.getProperty("groq.free-tier.ledger","data/usage/groq-free-reservations.jsonl"));
        Files.createDirectories(path.getParent());return new Ledger(path);
    }
    private final class Ledger implements AutoCloseable{
        final FileChannel channel;java.nio.channels.FileLock lock;final List<JsonNode> rows=new ArrayList<>();
        Ledger(Path path)throws IOException{
            boolean created=false;FileChannel file;
            try{file=FileChannel.open(path,CREATE_NEW,READ,WRITE,LinkOption.NOFOLLOW_LINKS);created=true;}
            catch(FileAlreadyExistsException exists){file=FileChannel.open(path,READ,WRITE,LinkOption.NOFOLLOW_LINKS);}
            channel=file;
            try{
                lock=channel.tryLock();if(lock==null)throw denied("groq_ledger_busy");
                if(channel.size()>MAX_LEDGER||channel.size()==0&&!created)throw denied("groq_ledger_invalid");
                byte[] bytes=new byte[(int)channel.size()];ByteBuffer buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining())if(channel.read(buffer)<0)throw denied("groq_ledger_invalid");
                if(bytes.length>0&&bytes[bytes.length-1]!='\n')throw denied("groq_ledger_torn");
                for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n"))if(!line.isEmpty()){
                    JsonNode wrapper=json.readTree(line),row=wrapper.path("entry");
                    if(!wrapper.path("sha256").asText().equals(keyHash(json.writeValueAsString(row)))||row.path("at").asLong()<=0
                            ||!Set.of("reserve","observe").contains(row.path("kind").asText()))throw denied("groq_ledger_invalid");rows.add(row);
                }
            }catch(Exception invalid){close();throw denied(invalid instanceof java.nio.channels.OverlappingFileLockException?"groq_ledger_busy":"groq_ledger_invalid");}
        }
        void append(ObjectNode row)throws IOException{
            ObjectNode wrapper=json.createObjectNode().put("sha256",keyHash(json.writeValueAsString(row)));wrapper.set("entry",row);
            byte[] bytes=(json.writeValueAsString(wrapper)+"\n").getBytes(StandardCharsets.UTF_8);
            if(channel.size()+bytes.length>MAX_LEDGER)throw denied("groq_ledger_full");
            channel.position(channel.size());ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
            rows.add(row);
        }
        public void close()throws IOException{try{if(lock!=null)lock.close();}finally{channel.close();}}
    }
}
