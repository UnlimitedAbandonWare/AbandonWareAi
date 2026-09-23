package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import static java.nio.file.StandardOpenOption.*;

/** Application-scoped conservative reservations, not a provider invoice or a free-credit balance. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public final class ConversateSttBudget {
    // USD 0.008/min exceeds the observed Nova-3 monolingual regular rate (2026-09-14).
    static final long MICROS_PER_MINUTE=8_000;
    private final ObjectMapper json; private final Path ledger; private final Clock clock;
    private final boolean enabled,verification,validPolicy; private final long verificationCap,monthlyCap;
    @Value("${conversate.cost.enforce-limits:true}") private boolean enforceLimits=true;
    private Usage processUsage;
    private String ledgerState="not_observed";
    public record Usage(String month,long verificationMicros,long monthlyMicros,long nextMonthMicros,
                        long totalMicros,long requests,long reservedSeconds) { }
    public record View(String mode,long verificationCapMicros,long monthlyCapMicros,Usage reserved,
                       String accounting,String freeCredit,boolean enforced,String ledgerState) {
        public View(String mode,long verificationCapMicros,long monthlyCapMicros,Usage reserved,String accounting,String freeCredit){
            this(mode,verificationCapMicros,monthlyCapMicros,reserved,accounting,freeCredit,true,"recorded");
        }
    }
    @Autowired
    public ConversateSttBudget(ObjectMapper json,
            @Value("${conversate.asr.cloud.enabled:false}") boolean enabled,
            @Value("${conversate.asr.cloud.ledger:}") String ledger,
            @Value("${conversate.asr.cloud.mode:verification}") String mode,
            @Value("${conversate.asr.cloud.verification-usd:1}") String verificationUsd,
            @Value("${conversate.asr.cloud.monthly-usd:5}") String monthlyUsd) {
        this(json,enabled,ledger,mode,verificationUsd,monthlyUsd,Clock.systemUTC());
    }
    ConversateSttBudget(ObjectMapper json,boolean enabled,String ledger,String mode,String verificationUsd,String monthlyUsd,Clock clock) {
        this.json=json;this.clock=clock;this.verification="verification".equals(mode);
        Path path=null;long v=0,m=0;
        try {path=Path.of(ledger);v=micros(verificationUsd,1);m=micros(monthlyUsd,5);}catch(RuntimeException invalid){ }
        this.ledger=path;this.verificationCap=v;this.monthlyCap=m;
        this.enabled=enabled;this.validPolicy=(verification||"operation".equals(mode))&&v>0&&m>0;
        this.processUsage=empty(YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)));
    }
    private static long micros(String value,int maximum) {
        var amount=new BigDecimal(value);if(amount.signum()<=0||amount.compareTo(BigDecimal.valueOf(maximum))>0)throw new IllegalArgumentException();
        return amount.movePointRight(6).longValueExact();
    }
    public boolean configured() {
        return enabled&&(!enforceLimits||validPolicy&&ledgerConfigured());
    }
    private boolean ledgerConfigured() {
        if(ledger==null||!ledger.isAbsolute()||ledger.getParent()==null||!Files.isDirectory(ledger.getParent()))return false;
        for(Path p=ledger;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))return false;
        return !Files.exists(ledger)||Files.isRegularFile(ledger,LinkOption.NOFOLLOW_LINKS);
    }
    /** Reserve the entire connection deadline before opening a socket. Unknown charges are never refunded. */
    synchronized Usage reserve(long maximumSeconds) throws IOException {
        if(!configured()||maximumSeconds<1||maximumSeconds>601)throw new IOException("stt_budget_unavailable");
        if(enforceLimits)return reservePersistent(maximumSeconds);
        long cost=(maximumSeconds*MICROS_PER_MINUTE+59)/60;
        processUsage=new Usage(YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).toString(),0,0,0,
                processUsage.totalMicros()+cost,processUsage.requests()+1,processUsage.reservedSeconds()+maximumSeconds);
        // Optional accounting must never become an admission gate in device testing.
        // Preserve an invalid/full/locked ledger; process counters remain available.
        try{var usage=reservePersistent(maximumSeconds);ledgerState="recorded";return usage;}
        catch(IOException|RuntimeException unavailable){ledgerState="unavailable_process_only";return processUsage;}
    }
    private Usage reservePersistent(long maximumSeconds)throws IOException {
        if(!ledgerConfigured())throw new IOException("stt_budget_unavailable");
        long cost=(maximumSeconds*MICROS_PER_MINUTE+59)/60;
        boolean created=false;FileChannel opened;
        try {opened=FileChannel.open(ledger,CREATE_NEW,READ,WRITE,LinkOption.NOFOLLOW_LINKS);created=true;}
        catch(FileAlreadyExistsException exists){opened=FileChannel.open(ledger,READ,WRITE,LinkOption.NOFOLLOW_LINKS);}
        try(var channel=opened;var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("stt_budget_busy");
            Instant now=clock.instant();var month=YearMonth.from(now.atZone(ZoneOffset.UTC));
            Usage old=read(channel,created,month);long forward=old.nextMonthMicros();
            if(YearMonth.from(now.plusSeconds(maximumSeconds).atZone(ZoneOffset.UTC)).isAfter(month))forward=Math.addExact(forward,cost);
            long v=Math.addExact(old.verificationMicros(),verification?cost:0),m=Math.addExact(old.monthlyMicros(),cost);
            if(enforceLimits&&(v>verificationCap&&verification||m>monthlyCap||forward>monthlyCap))throw new IOException("stt_budget_exhausted");
            Usage next=new Usage(month.toString(),v,m,forward,Math.addExact(old.totalMicros(),cost),Math.addExact(old.requests(),1),Math.addExact(old.reservedSeconds(),maximumSeconds));
            byte[] payload=json.writeValueAsBytes(next);
            var row=json.createObjectNode().put("schema",1).put("sha256",digest(payload));row.set("usage",json.readTree(payload));
            byte[] bytes=(json.writeValueAsString(row)+"\n").getBytes(StandardCharsets.UTF_8);
            if(channel.size()+bytes.length>4_194_304)throw new IOException("stt_budget_ledger_limit");
            channel.position(channel.size());var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            return next;
        } catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("stt_budget_busy");}
        catch(ArithmeticException invalid){throw new IOException("stt_budget_invalid");}
    }
    public synchronized View view() throws IOException {
        if(!configured())throw new IOException("stt_budget_unavailable");
        var month=YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));Usage usage=empty(month);
        try{
            if(!ledgerConfigured())throw new IOException("stt_budget_unavailable");
            if(Files.exists(ledger))try(var channel=FileChannel.open(ledger,READ,LinkOption.NOFOLLOW_LINKS)){usage=read(channel,false,month);}
            ledgerState="recorded";
        }catch(IOException|RuntimeException unavailable){
            if(enforceLimits)throw new IOException("stt_budget_unavailable");
            usage=processUsage;ledgerState="unavailable_process_only";
        }
        return new View(enforceLimits?(verification?"verification":"operation"):"observational",enforceLimits?verificationCap:0,enforceLimits?monthlyCap:0,usage,"conservative_reservation_usd_micros","not_assumed",enforceLimits,ledgerState);
    }
    private Usage read(FileChannel channel,boolean created,YearMonth month)throws IOException {
        if(channel.size()==0){if(created)return empty(month);throw new IOException("stt_budget_invalid");}
        if(channel.size()>4_194_304)throw new IOException("stt_budget_ledger_limit");
        byte[] bytes=new byte[(int)channel.size()];var data=ByteBuffer.wrap(bytes);channel.position(0);
        while(data.hasRemaining())if(channel.read(data)<0)throw new IOException("stt_budget_invalid");
        // A torn final append is an explicit stop, never a reset to an earlier cheaper record.
        if(bytes[bytes.length-1]!='\n')throw new IOException("stt_budget_invalid");
        int begin=bytes.length-2;while(begin>=0&&bytes[begin]!='\n')begin--;
        try {
            var row=json.readTree(new String(bytes,begin+1,bytes.length-begin-2,StandardCharsets.UTF_8));
            if(row.path("schema").asInt()!=1||!row.path("sha256").asText().equals(digest(json.writeValueAsBytes(row.path("usage")))))throw new IOException();
            Usage old=json.treeToValue(row.path("usage"),Usage.class);
            if(old.verificationMicros()<0||old.monthlyMicros()<0||old.nextMonthMicros()<0||old.totalMicros()<0||old.requests()<1||old.reservedSeconds()<1)throw new IOException();
            YearMonth recorded=YearMonth.parse(old.month());if(month.isBefore(recorded))throw new IOException();
            if(month.equals(recorded))return old;
            return new Usage(month.toString(),old.verificationMicros(),month.equals(recorded.plusMonths(1))?old.nextMonthMicros():0,0,old.totalMicros(),old.requests(),old.reservedSeconds());
        }catch(Exception invalid){throw new IOException("stt_budget_invalid");}
    }
    private static Usage empty(YearMonth month){return new Usage(month.toString(),0,0,0,0,0,0);}
    private static String digest(byte[] bytes)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception unavailable){throw new IOException("stt_budget_unavailable");}
    }
}
