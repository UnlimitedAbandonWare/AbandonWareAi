package com.example.lms.api;

import com.example.lms.infra.upstash.UpstashRedisClient;
import com.example.lms.web.ClientOwnerKeyResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Distributed cost admission, after authentication and bounded body intake, before generation. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "chat.admission.enabled", havingValue = "true", matchIfMissing = true)
public final class ChatGenerationAdmissionFilter extends OncePerRequestFilter {
    private static final long RETENTION_MS = Duration.ofHours(24).toMillis();
    private static final Set<String> GENERATION = Set.of("/api/chat", "/api/chat/sync", "/api/chat/stream");
    private final UpstashRedisClient redis;
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final String COMPLETION_ATTRIBUTE = ChatGenerationAdmissionFilter.class.getName()+".completion";
    private final ClientOwnerKeyResolver owners;
    private final String script;
    @Value("${demo.mode:${DEMO_MODE:false}}") private boolean demoMode;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private boolean demoAdmission() {
        return demoMode || (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local")));
    }
    /** Request-scoped approval only. It retains no question, answer, credential or remote claim. */
    public static final class DemoPermit implements java.util.function.Consumer<Object>, Runnable {
        private final AtomicBoolean completed = new AtomicBoolean();
        public boolean accepted() { return true; }
        public boolean completed() { return completed.get(); }
        @Override public void accept(Object ignored) { completed.set(true); }
        @Override public void run() { /* Explicit local/demo cost approval. */ }
    }
    @Value("${chat.admission.user-capacity:20}") private int userCapacity = 20;
    @Value("${chat.admission.user-per-minute:20}") private int userPerMinute = 20;
    @Value("${chat.admission.ip-capacity:60}") private int ipCapacity = 60;
    @Value("${chat.admission.ip-per-minute:60}") private int ipPerMinute = 60;

    public ChatGenerationAdmissionFilter(UpstashRedisClient redis, DataSource dataSource, ClientOwnerKeyResolver owners) {
        this.redis = redis; this.owners = owners; this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        this.transactions.setTimeout(2);
        this.jdbc.setQueryTimeout(2);
        try { this.script = new ClassPathResource("redis/chat_admission.lua").getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("chat_admission_script_missing"); }
    }
    @jakarta.annotation.PostConstruct void validate() {
        if (userCapacity < 1 || userPerMinute < 1 || ipCapacity < 1 || ipPerMinute < 1
                || userCapacity > 100_000 || userPerMinute > 100_000 || ipCapacity > 100_000 || ipPerMinute > 100_000)
            throw new IllegalStateException("chat_admission_invalid_limits");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !"POST".equals(request.getMethod()) || !GENERATION.contains(path)
                || ("/api/chat/stream".equals(path) && Boolean.parseBoolean(request.getParameter("attach")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Idempotency-Key");
        if (key != null && !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            reject(response, 400, "invalid_idempotency_key", 0); return;
        }
        if (demoAdmission()) {
            var permit = new DemoPermit();
            request.setAttribute("chat.admission.demoPermit", permit);
            request.setAttribute(COMPLETION_ATTRIBUTE, permit);
            response.setHeader("X-Admission-Mode", "demo-memory");
            org.slf4j.LoggerFactory.getLogger(ChatGenerationAdmissionFilter.class)
                    .debug("interview.admission mode=demo-memory accepted=true");
            chain.doFilter(request, response);
            return;
        }
        Claim claim = null;
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            String owner = DigestUtils.sha256Hex(auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                    ? "user:" + auth.getName() : "owner:" + owners.ownerKey());
            String ip = owners.clientIpHash(request);
            if (ip == null) throw new IllegalStateException("ip_identity_unavailable");
            Object bodyHash = request.getAttribute("chat.admission.bodySha256");
            if (!(bodyHash instanceof String hash) || !hash.matches("[a-fA-F0-9]{64}"))
                throw new IllegalStateException("bounded_body_required");
            String canonicalHash = Objects.toString(request.getAttribute("chat.admission.semanticSha256"), (String) bodyHash);
            String fingerprint = SemanticRequestFingerprint.request(request, path(request), canonicalHash);
            String keyHash = key == null ? "unused" : DigestUtils.sha256Hex("v2\n" + owner + "\n" + path(request) + "\n" + key);
            String token = UUID.randomUUID().toString();
            if (key != null) {
                jdbc.update("DELETE FROM awx_chat_requests WHERE key_hash=? AND expires_at<=?", keyHash, System.currentTimeMillis());
                if (rejectExisting(response, keyHash, fingerprint)) return;
                // Retain pre-upgrade fences conservatively until their original retention ends.
                String legacyKey = DigestUtils.sha256Hex(owner + ":" + key);
                jdbc.update("DELETE FROM awx_chat_requests WHERE key_hash=? AND expires_at<=?", legacyKey, System.currentTimeMillis());
                String legacyFingerprint = DigestUtils.sha256Hex(request.getMethod()+"\n"+path(request)+"\n"+Objects.toString(request.getQueryString(),"")+"\n"+bodyHash);
                if (rejectExisting(response, legacyKey, legacyFingerprint)) return;
            }
            if (!redis.enabled()) throw new IllegalStateException("redis_unavailable");
            List<Long> result = redis.eval(script,
                    List.of("chat:{admission}:user:" + owner, "chat:{admission}:ip:" + ip, "chat:{admission}:key:" + keyHash),
                    List.of(Integer.toString(userCapacity), Double.toString(userPerMinute / 60.0),
                            Integer.toString(ipCapacity), Double.toString(ipPerMinute / 60.0), fingerprint, token,
                            key == null ? "0" : "1", Long.toString(RETENTION_MS)))
                    .block(Duration.ofSeconds(2));
            if (result == null || result.size() != 2) throw new IllegalStateException("invalid_admission_reply");
            if (result.get(0) == 0L) { reject(response, 429, "chat_rate_limited", result.get(1)); return; }
            if (result.get(0) == 2L) { reject(response, 409, "idempotency_duplicate", 0); return; }
            if (result.get(0) == 3L) { reject(response, 409, "idempotency_payload_mismatch", 0); return; }
            if (result.get(0) != 1L) throw new IllegalStateException("invalid_admission_reply");
            if (key != null) {
                try {
                    jdbc.update("INSERT INTO awx_chat_requests(key_hash,owner_hash,fingerprint,claim_token,state,created_at) VALUES(?,?,?,?,'IN_PROGRESS',?)",
                            keyHash, owner, fingerprint, token, System.currentTimeMillis());
                } catch (DuplicateKeyException duplicate) {
                    if (rejectExisting(response, keyHash, fingerprint)) return;
                    throw duplicate;
                }
                claim = new Claim(keyHash, token, "/api/chat/stream".equals(path(request)));
                request.setAttribute(COMPLETION_ATTRIBUTE, (java.util.function.Consumer<Object>) claim::complete);
            }
        } catch (RuntimeException unavailable) {
            reject(response, 503, "chat_admission_unavailable", 1000); return;
        }
        try {
            chain.doFilter(request, response);
            if (claim != null) {
                if (request.isAsyncStarted()) {
                    try { request.getAsyncContext().addListener(claim); }
                    catch (IllegalStateException alreadyCompleted) { claim.finish(false); }
                } else claim.finish(response.getStatus() < 500);
            }
        } catch (IOException | ServletException | RuntimeException failed) {
            if (claim != null) claim.finish(false);
            throw failed;
        }
    }
    private boolean rejectExisting(HttpServletResponse response, String key, String fingerprint) throws IOException {
        var rows = jdbc.query("SELECT r.fingerprint,r.state,x.result_json,x.content_type FROM awx_chat_requests r LEFT JOIN awx_chat_request_results x ON x.key_hash=r.key_hash WHERE r.key_hash=?",
                (rs, row) -> new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)}, key);
        if (rows.isEmpty()) return false;
        var prior=rows.get(0);boolean same = prior[0].equals(fingerprint);
        if (same && "COMPLETED".equals(prior[1]) && prior[2]!=null) {
            response.setStatus(200);response.setCharacterEncoding("UTF-8");response.setContentType(prior[3]);
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Idempotent-Replay","true");
            response.setHeader("Access-Control-Expose-Headers","X-Idempotent-Replay");
            response.getWriter().write("text/event-stream".equals(prior[3])?"event: final\ndata: "+prior[2]+"\n\n":prior[2]);
            return true;
        }
        reject(response, 409, same ? "idempotency_duplicate" : "idempotency_payload_mismatch", 0);
        return true;
    }
    /** Capture only hashes; the returned function never retains a servlet request or live content. */
    public Runnable costCheck(HttpServletRequest request) {
        if (demoAdmission()) return new DemoPermit();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "authentication_required");
        String owner = DigestUtils.sha256Hex("user:" + auth.getName());
        String ip = owners.clientIpHash(request);
        if (ip == null || !ip.matches("[a-fA-F0-9]{64}")) throw admissionUnavailable();
        return () -> checkCost(owner, ip);
    }
    public Runnable costCheckCurrentRequest() {
        if (demoAdmission()) return new DemoPermit();
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) throw admissionUnavailable();
        return costCheck(servlet.getRequest());
    }
    private void checkCost(String owner, String ip) {
        try {
            if (!redis.enabled()) throw admissionUnavailable();
            var result = redis.eval(script, List.of("chat:{admission}:user:"+owner,"chat:{admission}:ip:"+ip,"chat:{admission}:key:unused"),
                    List.of(Integer.toString(userCapacity),Double.toString(userPerMinute/60.0),Integer.toString(ipCapacity),Double.toString(ipPerMinute/60.0),"unused","unused","0",Long.toString(RETENTION_MS))).block(Duration.ofSeconds(2));
            if (result == null || result.size()!=2) throw admissionUnavailable();
            if (result.get(0)==0L) {
                throw new RateRejection(result.get(1));
            }
            if (result.get(0)!=1L) throw admissionUnavailable();
        } catch (org.springframework.web.server.ResponseStatusException classified) { throw classified; }
        catch (RuntimeException unavailable) { throw admissionUnavailable(); }
    }
    private static org.springframework.web.server.ResponseStatusException admissionUnavailable() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"chat_admission_unavailable");
    }
    private static final class RateRejection extends org.springframework.web.server.ResponseStatusException {
        private final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        RateRejection(long retryMs) {
            super(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"chat_rate_limited");
            headers.set("Retry-After",Long.toString(Math.max(1,(retryMs+999)/1000)));
        }
        @Override public org.springframework.http.HttpHeaders getHeaders() {return headers;}
    }
    @Scheduled(fixedDelay = 60_000) public void purgeExpired() {
        if (demoAdmission()) return;
        try { jdbc.update("DELETE FROM awx_chat_requests WHERE expires_at<=? LIMIT 1000", System.currentTimeMillis()); }
        catch (RuntimeException unavailable) { /* Admission itself fails closed; preserve unknown claims. */ }
    }
    private final class Claim implements AsyncListener {
        private final String key, token;
        private final boolean stream;
        private final AtomicBoolean finished = new AtomicBoolean();
        Claim(String key, String token, boolean stream) { this.key = key; this.token = token; this.stream=stream; }
        void complete(Object result) {
            boolean valid = stream ? result instanceof com.example.lms.dto.ChatStreamEvent event && "final".equals(event.type())
                    : result instanceof com.example.lms.dto.ChatResponseDto;
            if (!valid || !finished.compareAndSet(false,true)) return;
            try {
                byte[] encoded=json.writeValueAsBytes(result);
                if(encoded.length>2*1024*1024)throw new IllegalStateException("result_limit");
                String body=new String(encoded,StandardCharsets.UTF_8);long now=System.currentTimeMillis();
                transactions.executeWithoutResult(status->{
                    int changed=jdbc.update("UPDATE awx_chat_requests SET state='COMPLETED',completed_at=?,expires_at=? WHERE key_hash=? AND claim_token=? AND state='IN_PROGRESS'",now,now+RETENTION_MS,key,token);
                    if(changed==1)jdbc.update("INSERT INTO awx_chat_request_results(key_hash,result_json,content_type) VALUES(?,?,?)",key,body,stream?"text/event-stream":"application/json");
                });
            } catch(Exception unavailable) { markUnknown(); }
        }
        void finish(boolean completed) {
            if (!finished.compareAndSet(false, true)) return;
            // HTTP/SSE termination alone does not certify an exact semantic result.
            markUnknown();
        }
        private void markUnknown() {
            try { jdbc.update("UPDATE awx_chat_requests SET state='OUTCOME_UNKNOWN',completed_at=NULL,expires_at=NULL WHERE key_hash=? AND claim_token=? AND state='IN_PROGRESS'",key,token); }
            catch (RuntimeException unavailable) { /* Keep the non-expiring fence; never retry inference. */ }
        }
        public void onComplete(AsyncEvent event) { finish(((HttpServletResponse) event.getAsyncContext().getResponse()).getStatus() < 500); }
        public void onTimeout(AsyncEvent event) { finish(false); }
        public void onError(AsyncEvent event) { finish(false); }
        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
    }
    @SuppressWarnings("unchecked")
    public static java.util.function.Consumer<Object> completion(HttpServletRequest request) {
        Object receipt=request==null?null:request.getAttribute(COMPLETION_ATTRIBUTE);
        return receipt instanceof java.util.function.Consumer<?>?(java.util.function.Consumer<Object>)receipt:ignored->{};
    }
    private static String path(HttpServletRequest request) {
        String servlet = request.getServletPath();
        return servlet == null || servlet.isEmpty() ? request.getRequestURI().substring(request.getContextPath().length()) : servlet;
    }
    private static void reject(HttpServletResponse response, int status, String reason, long retryMs) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        if (retryMs > 0) response.setHeader("Retry-After", Long.toString(Math.max(1, (retryMs + 999) / 1000)));
        response.getWriter().write("{\"error\":\"" + reason + "\"}");
    }
}
