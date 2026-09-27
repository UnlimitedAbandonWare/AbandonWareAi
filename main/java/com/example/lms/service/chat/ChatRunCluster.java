package com.example.lms.service.chat;

import com.example.lms.dto.ChatStreamEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.codec.ServerSentEvent;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Opt-in exact owner relay. No user credential forwarding, discovery, redirects, or retries. */
@Component
@ConditionalOnProperty(name="chat.cluster.enabled",havingValue="true")
public final class ChatRunCluster {
    public record Command(Long sessionId, String runToken) { }
    public record Accepted(boolean accepted) { }
    private static final Set<String> ACTIONS=Set.of("attach","cancel","ready","final","recovery","state","delete");
    private static final String TIME="X-Chat-Peer-Time", NONCE="X-Chat-Peer-Nonce", SIGNATURE="X-Chat-Peer-Signature";
    private final ChatRunOwnerDirectory directory;
    private final Map<String,URI> peers;
    private final byte[] secret;
    private final Map<String,Long> seenNonces=new HashMap<>();
    private final WebClient http;
    private final WebClient streamHttp;
    private final reactor.netty.resources.ConnectionProvider streamConnections;
    private final reactor.netty.resources.ConnectionProvider controlConnections;

    public ChatRunCluster(DataSource source, String instance, String peers, String key) {
        this(source,instance,peers,key,128);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatRunCluster(DataSource source, @Value("${chat.cluster.instance-id:}") String instance,
                          @Value("${chat.cluster.peers:}") String configuredPeers,
                          @Value("${chat.cluster.secret:${CHAT_CLUSTER_SECRET:}}") String key,
                          @Value("${chat.cluster.max-relay-streams:128}") int maxStreams) {
        if(key==null || key.getBytes(StandardCharsets.UTF_8).length<32 || key.length()>256
                || key.contains("${")) throw new IllegalArgumentException("chat_cluster_secret_required");
        secret=key.getBytes(StandardCharsets.UTF_8);
        var allowed=new HashMap<String,URI>();
        for(String entry:configuredPeers.split(";")) {
            String[] pair=entry.trim().split("=",2);
            if(pair.length!=2 || !pair[0].matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("chat_cluster_peers_invalid");
            URI uri;
            try { uri=URI.create(pair[1]); } catch(IllegalArgumentException invalid){throw new IllegalArgumentException("chat_cluster_peer_invalid");}
            boolean loopback=Set.of("127.0.0.1","[::1]","::1").contains(Objects.toString(uri.getHost(),""));
            if(uri.getHost()==null || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme())))
                    || uri.getPort()==0 || uri.getPort()>65535
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())) || allowed.putIfAbsent(pair[0],uri)!=null)
                throw new IllegalArgumentException("chat_cluster_peer_invalid");
        }
        if(!allowed.containsKey(instance) || allowed.size()>32)throw new IllegalArgumentException("chat_cluster_instance_not_configured");
        peers=Map.copyOf(allowed);directory=new ChatRunOwnerDirectory(source,instance);
        if(maxStreams<1 || maxStreams>128)throw new IllegalArgumentException("chat_cluster_stream_limit_invalid");
        streamConnections=reactor.netty.resources.ConnectionProvider.builder("chat-owner-stream")
                .maxConnections(maxStreams).pendingAcquireMaxCount(64).pendingAcquireTimeout(Duration.ofSeconds(1)).build();
        controlConnections=reactor.netty.resources.ConnectionProvider.builder("chat-owner-control")
                .maxConnections(16).pendingAcquireMaxCount(32).pendingAcquireTimeout(Duration.ofSeconds(1)).build();
        http=client(controlConnections);streamHttp=client(streamConnections);
    }
    private static WebClient client(reactor.netty.resources.ConnectionProvider connections) {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create(connections)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,1500)
                .responseTimeout(Duration.ofSeconds(2)).followRedirect(false)))
                .codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024)).build();
    }
    @jakarta.annotation.PreDestroy public void close() {
        streamConnections.dispose();controlConnections.dispose();
        synchronized(seenNonces){seenNonces.clear();}
        Arrays.fill(secret,(byte)0);
    }
    public ChatRunOwnerDirectory directory(){return directory;}
    private static void validate(String action, Command command) {
        if(!ACTIONS.contains(action) || command==null || command.sessionId()==null || command.sessionId()<1
                || command.runToken()==null || !command.runToken().matches("[a-f0-9-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"run_identity_invalid");
    }
    private byte[] mac(String action,Command command,String time,String nonce) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal((action+"\n"+command.sessionId()+"\n"+command.runToken()+"\n"+time+"\n"+nonce).getBytes(StandardCharsets.UTF_8));
        } catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException("chat_cluster_crypto_unavailable");}
    }
    HttpHeaders sign(String action, Command command) {
        validate(action,command);var headers=new HttpHeaders();
        String time=Long.toString(System.currentTimeMillis()),nonce=UUID.randomUUID().toString();
        headers.set(TIME,time);headers.set(NONCE,nonce);headers.set(SIGNATURE,HexFormat.of().formatHex(mac(action,command,time,nonce)));
        return headers;
    }
    /** The public controller must perform its normal ACL before entering this peer route. */
    public void authorizePeer(String action, Command command, HttpHeaders headers) {
        validate(action,command);
        String time=headers.getFirst(TIME),nonce=headers.getFirst(NONCE),signature=headers.getFirst(SIGNATURE);
        boolean valid=false;long stamp=0;
        try {
            stamp=Long.parseLong(time);
            valid=nonce!=null && nonce.matches("[a-f0-9-]{36}") && signature!=null && signature.length()==64
                    && stamp>=System.currentTimeMillis()-10_000 && stamp<=System.currentTimeMillis()+10_000
                    && MessageDigest.isEqual(mac(action,command,time,nonce),HexFormat.of().parseHex(signature));
        } catch(RuntimeException invalid){ /* Fixed rejection below; no request material in diagnostics. */ }
        if(!valid)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"peer_auth_required");
        synchronized(seenNonces) {
            long now=System.currentTimeMillis();seenNonces.values().removeIf(expiry->expiry<now);
            if(seenNonces.containsKey(nonce) || seenNonces.size()>=8192)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"peer_auth_replayed_or_full");
            seenNonces.put(nonce,stamp+10_000);
        }
        var owner=directory.find(command.sessionId(),command.runToken()).orElseThrow(ChatRunOwnerDirectory::unavailable);
        if(!directory.isLocal(owner) || !directory.isAvailable(owner))throw ChatRunOwnerDirectory.unavailable();
    }
    private Optional<URI> remote(Long session,String token) {
        var found=directory.find(session,token);
        if(found.isEmpty())return Optional.empty();
        var owner=found.get();
        if(!directory.isAvailable(owner))throw ChatRunOwnerDirectory.unavailable();
        if(directory.isLocal(owner))return Optional.empty();
        URI peer=peers.get(owner.instanceId());
        if(peer==null)throw ChatRunOwnerDirectory.unavailable();
        return Optional.of(peer);
    }
    private WebClient.RequestHeadersSpec<?> request(URI peer,String action,Command command) {
        return ("attach".equals(action)?streamHttp:http).post().uri(peer.resolve("/api/chat/cluster/"+action)).headers(h->h.addAll(sign(action,command)))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(command);
    }
    public Optional<Flux<ServerSentEvent<ChatStreamEvent>>> attach(Long session,String token) {
        return remote(session,token).map(peer -> Flux.defer(() -> request(peer,"attach",new Command(session,token))
                .exchangeToFlux(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<ChatStreamEvent>>() {})
                        : response.releaseBody().thenMany(Flux.error(ChatRunOwnerDirectory.unavailable()))))
                .timeout(Duration.ofSeconds(3))
                .onErrorMap(failure -> ChatRunOwnerDirectory.unavailable()));
    }
    public boolean control(String action,Long session,String token) {
        return remote(session,token).map(peer -> {
            try {
                var reply=request(peer,action,new Command(session,token)).exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(Accepted.class) : response.releaseBody().then(Mono.error(ChatRunOwnerDirectory.unavailable())))
                        .block(Duration.ofSeconds(3));
                return reply!=null && reply.accepted();
            } catch(RuntimeException unavailable){throw ChatRunOwnerDirectory.unavailable();}
        }).orElse(false);
    }
    public Optional<ChatRunRegistry.RunView> describe(Long session,String token) {
        return remote(session,token).flatMap(peer -> {
            try {
                return Optional.ofNullable(request(peer,"state",new Command(session,token)).exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(ChatRunRegistry.RunView.class) : response.releaseBody().then(Mono.error(ChatRunOwnerDirectory.unavailable())))
                        .block(Duration.ofSeconds(3)));
            } catch(RuntimeException unavailable){throw ChatRunOwnerDirectory.unavailable();}
        });
    }
    public void fenceDeletion(Long session) {
        var owner=directory.freezeForDeletion(session);
        if(owner.isPresent() && !directory.isLocal(owner.get()) && !control("delete",session,owner.get().runToken()))
            throw ChatRunOwnerDirectory.unavailable();
    }
}
