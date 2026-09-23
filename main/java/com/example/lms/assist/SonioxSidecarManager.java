package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.config.ConfigValueGuards;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;

/** Owns only its Node/install children. Health never opens a billable speech session. */
@Component
@ConditionalOnProperty(name="conversate.enabled", havingValue="true")
public final class SonioxSidecarManager implements SmartLifecycle {
    private static final List<String> RESOURCES=List.of("package.json","package-lock.json","server.mjs","session.mjs");
    private final Environment env;
    private final ObjectMapper json;
    private final ConversateSttBudget budget;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    private final Object ownership=new Object();
    private final AtomicReference<Process> child=new AtomicReference<>(), installer=new AtomicReference<>();
    private final Deque<Long> starts=new ArrayDeque<>();
    private ScheduledExecutorService monitor;
    private volatile boolean running,stopping,providerConfigured;
    private volatile String state="STOPPED",reason="not_observed";
    private volatile String providerDisabledReason="missing_key";
    private volatile Endpoint endpoint;
    private volatile int restartCount;
    private int missedHealth;
    private long retryAt;

    record Endpoint(int port,String bearer) {
        URI httpUri(String path){return URI.create("http://127.0.0.1:"+port+path);}
        URI streamUri(){return URI.create("ws://127.0.0.1:"+port+"/stream");}
        @Override public String toString(){return "SonioxEndpoint[loopback]";}
    }
    public SonioxSidecarManager(Environment env,ObjectMapper json,ConversateSttBudget budget){this.env=env;this.json=json;this.budget=budget;}
    private long setting(String name,long fallback,long minimum,long maximum){
        try{return Math.max(minimum,Math.min(maximum,Long.parseLong(env.getProperty("conversate.asr.sidecar."+name,Long.toString(fallback)))));}
        catch(RuntimeException invalid){return fallback;}
    }
    boolean enabled(){return env.getProperty("conversate.asr.sidecar.enabled",Boolean.class,true);}
    private String startupDisabledReason(){
        try{
            if(!enabled())return "disabled";
            if(!"soniox".equals(env.getProperty("conversate.asr.provider","local")))return "not_selected";
            if(!env.getProperty("conversate.asr.enabled",Boolean.class,false))return "capture_disabled";
            if(!providerEnabled())return "disabled";
            if(ConfigValueGuards.isMissing(env.getProperty("soniox.api-key",env.getProperty("SONIOX_API_KEY",""))))return "missing_key";
            if(!Set.of("us","eu","jp","in").contains(env.getProperty("soniox.stt.region",env.getProperty("SONIOX_STT_REGION","us"))))return "invalid_region";
            if(!Set.of("stt-rt-v5","stt-rt-v4").contains(env.getProperty("soniox.stt.model",env.getProperty("SONIOX_STT_MODEL","stt-rt-v5"))))return "invalid_model";
            return budget==null||!budget.configured()?"budget_unavailable":"";
        }catch(RuntimeException invalid){return "invalid_configuration";}
    }
    @Override public void start(){
        synchronized(ownership){
            if(running)return;
            stopping=false;running=true;
            String disabled=startupDisabledReason();
            if(!disabled.isEmpty()){state="DISABLED";reason=disabled;return;}
            state="STARTING";
            monitor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"soniox-sidecar-supervisor");t.setDaemon(true);return t;});
            monitor.scheduleWithFixedDelay(this::tick,0,setting("health-interval-ms",2000,100,30000),TimeUnit.MILLISECONDS);
        }
    }
    private void tick(){
        if(stopping)return;
        try{
            String disabled=startupDisabledReason();
            if(!disabled.isEmpty()){
                stopOwned(installer.getAndSet(null));stopOwned(child.getAndSet(null));
                endpoint=null;providerConfigured=false;state="DISABLED";reason=disabled;return;
            }
            Process process=child.get();
            if(process!=null&&process.isAlive()&&endpoint!=null){
                if(probe(endpoint)){if(stopping)return;missedHealth=0;state="READY";reason=providerConfigured?"local_ready":providerDisabledReason;return;}
                state="DEGRADED";reason="health_failed";
                if(++missedHealth<3)return;
            }
            if(process!=null){child.compareAndSet(process,null);stopOwned(process);endpoint=null;providerConfigured=false;}
            long now=System.nanoTime();
            if(now<retryAt)return;
            long window=TimeUnit.SECONDS.toNanos(60);
            while(!starts.isEmpty()&&now-starts.peekFirst()>window)starts.removeFirst();
            if(starts.size()>=3){state="COOLDOWN";reason="restart_rate_limited";retryAt=starts.peekFirst()+window;return;}
            starts.addLast(now);restartCount++;
            state="STARTING";
            Path directory=prepareBundle();
            if(stopping)return;
            String seed=UUID.randomUUID().toString()+UUID.randomUUID();
            String credential=env.getProperty("soniox.api-key",env.getProperty("SONIOX_API_KEY",""));
            var builder=command(directory,List.of(env.getProperty("conversate.asr.sidecar.node","node"),"server.mjs"));
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            builder.environment().put("AWX_SONIOX_SIDECAR_TOKEN",seed);
            builder.environment().put("SONIOX_API_KEY",credential);
            builder.environment().put("SONIOX_STT_ENABLED",Boolean.toString(providerEnabled()));
            builder.environment().put("SONIOX_STT_REGION",env.getProperty("soniox.stt.region",env.getProperty("SONIOX_STT_REGION","us")));
            builder.environment().put("SONIOX_MODEL",env.getProperty("soniox.stt.model",env.getProperty("SONIOX_STT_MODEL","stt-rt-v5")));
            Process launched=launch(builder,child);
            var announcement=new CompletableFuture<String>();
            Thread reader=new Thread(()->{try(var input=launched.inputReader(StandardCharsets.UTF_8)){
                var line=new StringBuilder();int c;while((c=input.read())!=-1&&c!='\n'){if(line.length()>=1024)throw new IOException();line.append((char)c);}announcement.complete(line.toString());
            }catch(Exception failure){announcement.completeExceptionally(new IOException("sidecar_start_failed"));}},"soniox-sidecar-announcement");
            reader.setDaemon(true);reader.start();
            var ready=json.readTree(announcement.get(setting("startup-timeout-ms",10000,1000,30000),TimeUnit.MILLISECONDS));
            int port=ready.path("port").asInt();
            if(ready.path("protocol").asInt()!=1||port<1||port>65535)throw new IOException("sidecar_protocol");
            Endpoint observed=new Endpoint(port,seed);
            if(stopping||!probe(observed))throw new IOException("sidecar_health");
            synchronized(ownership){if(stopping)return;endpoint=observed;missedHealth=0;state="READY";reason=providerConfigured?"local_ready":providerDisabledReason;}
        }catch(Exception failure){
            stopOwned(child.getAndSet(null));endpoint=null;providerConfigured=false;
            if(!stopping){state="DEGRADED";reason="startup_or_health_failed";retryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(setting("restart-delay-ms",2000,100,60000));}
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
        }
    }
    boolean probe(Endpoint candidate){
        long timeout=setting("health-timeout-ms",1000,100,5000);
        var request=HttpRequest.newBuilder(candidate.httpUri("/health")).timeout(Duration.ofMillis(timeout))
                .header("Authorization","Bearer "+candidate.bearer()).GET().build();
        var pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try{
            var response=pending.get(timeout,TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200||response.body().length()>1024)return false;
            var data=json.readTree(response.body());
            boolean ok=data.path("protocol").asInt()==1&&data.path("status").asText().equals("ready");
            if(ok){providerConfigured=data.path("configured").asBoolean();String disabled=data.path("disabledReason").asText();
                providerDisabledReason=Set.of("disabled","missing_key","invalid_region","invalid_model").contains(disabled)?disabled:"unconfigured";}return ok;
        }catch(Exception unavailable){pending.cancel(true);return false;}
    }
    private Path prepareBundle()throws Exception{
        var contents=new LinkedHashMap<String,byte[]>();var digest=MessageDigest.getInstance("SHA-256");
        for(String name:RESOURCES){try(var input=new ClassPathResource("soniox-sidecar/"+name).getInputStream()){byte[] bytes=input.readAllBytes();contents.put(name,bytes);digest.update(bytes);}}
        Path root=Path.of(env.getProperty("conversate.asr.sidecar.runtime-directory","var/codex-runtime/soniox-sidecar")).toAbsolutePath().normalize();
        Path directory=root.resolve(HexFormat.of().formatHex(digest.digest()).substring(0,24));
        noLinks(directory);Files.createDirectories(directory);noLinks(directory);
        try(var channel=FileChannel.open(directory.resolve("install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            var lock=channel.tryLock()){
            if(lock==null)throw new IOException("sidecar_install_busy");
            for(var entry:contents.entrySet()){
                Path file=directory.resolve(entry.getKey());noLinks(file);
                if(Files.exists(file)){if(!Arrays.equals(Files.readAllBytes(file),entry.getValue()))throw new IOException("sidecar_bundle_changed");}
                else Files.write(file,entry.getValue(),StandardOpenOption.CREATE_NEW);
            }
            Path stamp=directory.resolve("dependencies.ready");
            if(!Files.isRegularFile(stamp,LinkOption.NOFOLLOW_LINKS)){
                if(!env.getProperty("conversate.asr.sidecar.bootstrap",Boolean.class,true))throw new IOException("sidecar_dependencies_missing");
                List<String> args=new ArrayList<>();
                if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))args.addAll(List.of("cmd.exe","/d","/c","npm.cmd"));else args.add("npm");
                args.addAll(List.of("ci","--ignore-scripts","--no-audit","--no-fund","--omit=dev","--registry=https://registry.npmjs.org"));
                Process install=launch(command(directory,args),installer);
                try{if(!install.waitFor(setting("bootstrap-timeout-ms",90000,1000,120000),TimeUnit.MILLISECONDS)||install.exitValue()!=0)throw new IOException("sidecar_install_failed");}
                finally{installer.compareAndSet(install,null);stopOwned(install);}
                Files.writeString(stamp,"locked-dependencies-installed\n",StandardOpenOption.CREATE_NEW);
            }
        }
        return directory;
    }
    private static void noLinks(Path path)throws IOException{
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("sidecar_path_link");
    }
    private ProcessBuilder command(Path directory,List<String> args){
        var builder=new ProcessBuilder(args).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Set<String> allowed=Set.of("PATH","PATHEXT","SYSTEMROOT","WINDIR","TEMP","TMP","HOME","USERPROFILE","APPDATA","LOCALAPPDATA","PROGRAMFILES","PROGRAMFILES(X86)");
        builder.environment().keySet().removeIf(k->!allowed.contains(k.toUpperCase(Locale.ROOT)));
        builder.environment().put("NPM_CONFIG_UPDATE_NOTIFIER","false");
        return builder;
    }
    private Process launch(ProcessBuilder builder,AtomicReference<Process> slot)throws IOException{
        synchronized(ownership){if(stopping)throw new IOException("sidecar_stopping");var process=builder.start();slot.set(process);return process;}
    }
    private static void stopOwned(Process process){
        if(process==null)return;
        var descendants=process.descendants().toList();
        try{process.getOutputStream().close();}catch(IOException ignored){}
        try{if(!process.waitFor(1500,TimeUnit.MILLISECONDS)){for(var p:descendants)if(p.isAlive())p.destroyForcibly();process.destroyForcibly();process.waitFor(1000,TimeUnit.MILLISECONDS);}}
        catch(InterruptedException interrupted){for(var p:descendants)if(p.isAlive())p.destroyForcibly();process.destroyForcibly();Thread.currentThread().interrupt();}
    }
    @Override public void stop(){
        Process owned,install;ScheduledExecutorService executor;
        synchronized(ownership){stopping=true;running=false;endpoint=null;providerConfigured=false;executor=monitor;monitor=null;owned=child.getAndSet(null);install=installer.getAndSet(null);}
        if(executor!=null)executor.shutdownNow();stopOwned(install);stopOwned(owned);state="STOPPED";reason="stopped";
    }
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return -100;}
    boolean ready(){var process=child.get();return !stopping&&state.equals("READY")&&endpoint!=null&&process!=null&&process.isAlive();}
    private boolean providerEnabled(){return env.getProperty("soniox.stt.enabled",Boolean.class,Boolean.parseBoolean(env.getProperty("SONIOX_STT_ENABLED","false")));}
    boolean configured(){return providerEnabled()&&ready()&&providerConfigured&&budget!=null&&budget.configured();}
    Endpoint endpoint()throws IOException{if(!ready())throw new IOException("soniox_sidecar_unavailable");return endpoint;}
    long childPid(){var process=child.get();return process==null?0:process.pid();}
    public Map<String,Object> diagnostics(){return Map.of("state",state,"reason",reason,"localReady",ready(),"configured",configured(),"starts",restartCount,"providerAttempt","not_observed");}
    /** CloudStt owns admission, routing and one reservation per connection before calling this seam. */
    ConversateAsrBridge.Transport connectAdmittedStream(Consumer<JsonNode> events,Consumer<String> failure)throws IOException{
        if(!configured())throw new IOException("soniox_sidecar_unconfigured");
        return new SonioxNodeTransport(endpoint(),json,events,failure,Duration.ofSeconds(10));
    }
}
