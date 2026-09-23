package com.example.lms.security;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.net.URI;
import java.util.Locale;



@Configuration
@EnableWebSecurity
public class ChatOpenSecurityConfig {
    private static final List<String> DISPLAY_TEXT = List.of("/api/assist/display/lens/link", "/api/assist/display/lens/text", "/api/assist/display/bootstrap", "/api/assist/display/input", "/api/assist/display/poll",
            "/api/assist/display/transcription", "/api/assist/display/phone-test", "/api/assist/display/context", "/api/assist/display/link/code", "/api/assist/display/link/join",
            "/api/assist/display/focus/settings/read", "/api/assist/display/focus/settings", "/api/assist/display/focus/history",
            "/api/assist/display/focus/open", "/api/assist/display/focus/input", "/api/assist/display/focus/input/status",
            "/api/assist/display/focus/close", "/api/assist/display/focus/rendered",
            "/api/assist/display/link/approve", "/api/assist/display/link/unlink", "/api/assist/display/hints", "/api/assist/display/ack", "/api/assist/display/lens", "/api/assist/display/lens/ack", "/api/assist/display/relay/poll", "/api/assist/display/relay/ack", "/api/assist/display/relay/settings", "/api/assist/display/relay/lens-settings", "/api/assist/display/relay/test", "/api/assist/display/relay/diagnostics");
    private static final List<String> DISPLAY_AUDIO = List.of("/api/assist/display/audio/start", "/api/assist/display/audio/chunk", "/api/assist/display/audio/stop");

    @Value("${demo.interview.enabled:false}")
    private boolean interviewDemo;

    @Value("${conversate.display.enabled:false}")
    private boolean publicDisplay;

    @Value("${conversate.display.audio.enabled:${CONVERSATE_DISPLAY_AUDIO_ENABLED:false}}")
    private boolean displayAudio;

    private boolean displayRequest(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return publicDisplay && "POST".equals(request.getMethod()) && (DISPLAY_TEXT.contains(path) || displayAudio && DISPLAY_AUDIO.contains(path));
    }

    /** Removes unused routes before any legacy authentication/HTTPS chain can run. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "demo.interview.enabled", havingValue = "true")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public org.springframework.boot.web.servlet.FilterRegistrationBean<InterviewDemoFilter> interviewDemoFilter(com.example.lms.assist.InterviewDemoPublicAddress address) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new InterviewDemoFilter(address::currentOrigin, () -> publicDisplay, () -> displayAudio));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    public static final class InterviewDemoFilter extends org.springframework.web.filter.OncePerRequestFilter {
        private final java.util.function.Supplier<String> publicOrigin;
        private final java.util.function.BooleanSupplier displayEnabled, audioEnabled;
        public InterviewDemoFilter() { this(() -> ""); }
        public InterviewDemoFilter(java.util.function.Supplier<String> publicOrigin) { this(publicOrigin, () -> false, () -> false); }
        InterviewDemoFilter(java.util.function.Supplier<String> publicOrigin,java.util.function.BooleanSupplier displayEnabled,java.util.function.BooleanSupplier audioEnabled) { this.publicOrigin=publicOrigin;this.displayEnabled=displayEnabled;this.audioEnabled=audioEnabled; }
        private static final List<org.springframework.security.web.util.matcher.IpAddressMatcher> LOCAL =
                List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7", "fe80::/10")
                        .stream().map(org.springframework.security.web.util.matcher.IpAddressMatcher::new).toList();

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            if (LOCAL.stream().noneMatch(m -> m.matches(request))) {
                response.setStatus(403); return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String method = request.getMethod();
            boolean read = method.equals("GET") || method.equals("HEAD");
            boolean page = List.of("/", "/index", "/index.html", "/chat", "/chat-ui", "/chat-ui.html", "/error", "/favicon.ico", "/actuator/health").contains(path);
            boolean asset = path.matches("/assets/(interview|display)/[a-z][a-z0-9-]*\\.(html|js|css|png|webmanifest)");
            boolean assistRead = path.equals("/api/assist/bootstrap") || path.matches("/api/assist/sessions/[a-f0-9-]{36}(/output(/poll)?)?");
            boolean assistWrite = path.equals("/api/assist/sessions") || path.matches("/api/assist/sessions/[a-f0-9-]{36}/(card|ack|control)");
            boolean displayWrite = displayEnabled.getAsBoolean() && (DISPLAY_TEXT.contains(path) || audioEnabled.getAsBoolean() && DISPLAY_AUDIO.contains(path));
            // This one observer route still goes through the existing ADMIN chain.
            boolean displayDiagnostics=displayEnabled.getAsBoolean() && path.equals("/api/diagnostics/display");
            if (!(read && (page || asset || assistRead || displayDiagnostics)) && !(method.equals("POST") && (path.equals("/api/chat/sync") || assistWrite || displayWrite))) {
                response.setStatus(404); return;
            }
            if (!read && !sameOrigin(request)) {
                response.setStatus(403); return;
            }
            chain.doFilter(request, response);
        }

        private boolean sameOrigin(jakarta.servlet.http.HttpServletRequest request) {
            String origin = request.getHeader("Origin");
            if (origin == null) return true; // Local native test clients do not send Origin.
            try {
                URI uri = URI.create(origin);
                // cloudflared terminates TLS and connects over loopback. Trust only its current
                // registered HTTPS origin, never arbitrary Forwarded/X-Forwarded-* headers.
                boolean loopback="127.0.0.1".equals(request.getRemoteAddr()) || "::1".equals(request.getRemoteAddr());
                if (loopback && origin.equals(publicOrigin.get()) && "https".equals(uri.getScheme())
                        && request.getServerName().equalsIgnoreCase(uri.getHost())) return true;
                int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                return request.getScheme().equals(uri.getScheme()) && request.getServerName().equalsIgnoreCase(uri.getHost())
                        && request.getServerPort() == port && uri.getRawUserInfo() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null && uri.getRawFragment() == null;
            } catch (IllegalArgumentException ignored) { return false; }
        }
    }

    @Value("${lms.cors.allow-credentials:${LMS_CORS_ALLOW_CREDENTIALS:false}}")
    private boolean corsAllowCredentials;

    @Value("${lms.cors.allowed-origins:${LMS_CORS_ALLOWED_ORIGINS:}}")
    private String corsAllowedOrigins;

    @Value("${lms.cors.allowed-origin-patterns:${LMS_CORS_ALLOWED_ORIGIN_PATTERNS:}}")
    private String corsAllowedOriginPatterns;

    @Value("${security.force-https:false}")
    private boolean forceHttps;

    @Value("${server.https-port:443}")
    private int httpsPort;

    @Value("${server.http-port:80}")
    private int httpPort;

    @Bean
    @Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain chatOpenChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(new OrRequestMatcher(
                this::displayRequest,
                request -> interviewDemo && request.getRequestURI().startsWith(request.getContextPath() + "/api/assist/"),
                AntPathRequestMatcher.antMatcher("/"),
                AntPathRequestMatcher.antMatcher("/index"),
                AntPathRequestMatcher.antMatcher("/index.html"),
                // Do not match the login endpoint here; let the main authentication chain handle it
                AntPathRequestMatcher.antMatcher("/error"),
                AntPathRequestMatcher.antMatcher("/favicon.ico"),
                AntPathRequestMatcher.antMatcher("/assets/**"),
                AntPathRequestMatcher.antMatcher("/css/**"),
                AntPathRequestMatcher.antMatcher("/js/**"),
                AntPathRequestMatcher.antMatcher("/images/**"),
                AntPathRequestMatcher.antMatcher("/chat"),
                AntPathRequestMatcher.antMatcher("/chat/**"),
                AntPathRequestMatcher.antMatcher("/chat-ui"),
                AntPathRequestMatcher.antMatcher("/chat-ui.html"),
                AntPathRequestMatcher.antMatcher("/chat-ui/**"),
                AntPathRequestMatcher.antMatcher("/chat:80"),
                AntPathRequestMatcher.antMatcher("/chat:80/**"),
                AntPathRequestMatcher.antMatcher("/actuator/**"),
                AntPathRequestMatcher.antMatcher("/ws/**"),
                AntPathRequestMatcher.antMatcher("/api/chat/**")
            ))
            .cors(cors -> { if (interviewDemo) cors.disable(); else cors.configurationSource(corsConfigurationSource()); })
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(
                    this::displayRequest,
                    request -> interviewDemo && request.getRequestURI().startsWith(request.getContextPath() + "/api/assist/"),
                    AntPathRequestMatcher.antMatcher("/api/chat/**"),
                    AntPathRequestMatcher.antMatcher("/ws/**"),
                    AntPathRequestMatcher.antMatcher("/actuator/health"),
                    AntPathRequestMatcher.antMatcher("/actuator/info")
                )
            )
            .authorizeHttpRequests(auth -> auth
                // Chat already owns its anonymous client/session boundary. Enabling the
                // independent Display surface must not change that admission policy.
                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/chat/**")).permitAll()
                .requestMatchers(request -> {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    return publicDisplay && !interviewDemo && !displayRequest(request)
                            && (path.startsWith("/api/") || path.equals("/ws") || path.startsWith("/ws/"));
                }).authenticated()
                .requestMatchers(
                    AntPathRequestMatcher.antMatcher("/actuator/health"),
                    AntPathRequestMatcher.antMatcher("/actuator/info")
                ).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/**")).denyAll()
                .anyRequest().permitAll()
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"auth_required\",\"stage\":\"authentication\",\"providerAttempted\":false}");
                })
                .accessDeniedHandler((request, response, error) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"forbidden\",\"stage\":\"authorization\",\"providerAttempted\":false}");
                }))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.ALWAYS))
            .anonymous(Customizer.withDefaults());

        if (forceHttps && !interviewDemo) {
            if (httpPort > 0 && httpsPort > 0 && httpPort != httpsPort) {
                http.portMapper(mapper -> mapper.http(httpPort).mapsTo(httpsPort));
            }
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowCredentials(corsAllowCredentials);
        for (String origin : normalizedCorsOrigins(corsAllowedOrigins)) {
            cfg.addAllowedOrigin(origin);
        }
        for (String pattern : csv(corsAllowedOriginPatterns)) {
            cfg.addAllowedOriginPattern(pattern);
        }
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static List<String> normalizedCorsOrigins(String raw) {
        return csv(raw).stream()
                .map(origin -> normalizeCorsOrigin(origin))
                .distinct()
                .toList();
    }

    static String normalizeCorsOrigin(String origin) {
        String trimmed = origin == null ? "" : origin.trim();
        if (trimmed.isBlank() || "*".equals(trimmed)) {
            return trimmed;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                URI originUri = URI.create(trimmed);
                String scheme = originUri.getScheme();
                String host = originUri.getHost();
                String ignoredPath = originUri.getRawPath();
                if (scheme != null && host != null && ignoredPath != null) {
                    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
                    String normalizedHost = host.toLowerCase(Locale.ROOT);
                    if (normalizedHost.contains(":") && !normalizedHost.startsWith("[")) {
                        normalizedHost = "[" + normalizedHost + "]";
                    }
                    String port = originUri.getPort() >= 0 ? ":" + originUri.getPort() : "";
                    return normalizedScheme + "://" + normalizedHost + port;
                }
            } catch (IllegalArgumentException ex) {
                traceCorsSuppressed("cors.origin", trimmed, ex);
                // Fall through to slash-only cleanup for invalid operator input.
            }
        }
        return trimmed.replaceAll("/+$", "");
    }

    private static void traceCorsSuppressed(String stage, String input, RuntimeException failure) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        TraceStore.put("security.cors.suppressed.stage", safeStage);
        TraceStore.put("security.cors.suppressed.errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        TraceStore.put("security.cors.suppressed." + safeStage, true);
        TraceStore.put("security.cors.suppressed.inputLength", input == null ? 0 : input.length());
        TraceStore.put("security.cors.suppressed.inputHash", SafeRedactor.hashValue(input));
    }
}
