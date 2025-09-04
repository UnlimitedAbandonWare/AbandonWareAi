package com.example.lms.moe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * experts.yml을 로드/핫리로드하여 {@link ExpertsConfig}를 제공하는 레지스트리.
 * - 디바운스(짧은 간격 중복 로드 방지)
 * - 실패 시 지수 백오프(최대 60초)
 * - 변경 감지: lastModified + SHA-256
 * - 로드 성공 시 환경 플래그(ai.moe.enabled.auto=true) 1순위 주입
 */
@Component
public class ExpertsRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExpertsRegistry.class);

    private static final long DEBOUNCE_MS = 300L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    private static final String DEFAULT_LOCATION = "classpath:experts.yml";
    private static final String LOCATION_PROP = "ai.moe.experts.location";

    private static final String PS_NAME = "aiMoeHotReloadFlag";
    private static final String ENABLED_AUTO_KEY = "ai.moe.enabled.auto";

    private final Environment env;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    private volatile ExpertsConfig cfg = new ExpertsConfig();

    private volatile long lastModified = -1L;
    private volatile String lastSha = null;

    private volatile long nextAllowedReloadAt = 0L;
    private long backoffMs = 2_000L;

    public ExpertsRegistry(Environment env, ResourceLoader resourceLoader) {
        this.env = env;
        this.resourceLoader = resourceLoader;
    }

    public ExpertsConfig get() { return cfg; }

    public boolean enabled() {
        String v = env.getProperty("ai.moe.enabled", "true");
        return Boolean.parseBoolean(v);
    }

    public void tick() { reload(false); }

    public void forceReload() { reload(true); }

    private synchronized void reload(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < nextAllowedReloadAt) return;

        Resource res = resolve();
        try {
            if (res == null || !res.exists()) {
                if (force) log.warn("[experts.yml] resource not found (location={})", location());
                return;
            }
            long lm = safeLastModified(res);
            byte[] bytes = readAllBytes(res);
            if (bytes == null || bytes.length == 0) {
                if (force) log.warn("[experts.yml] empty resource");
                return;
            }
            String sha = sha256(bytes);

            if (!force && lm == lastModified && Objects.equals(sha, lastSha)) {
                nextAllowedReloadAt = System.currentTimeMillis() + DEBOUNCE_MS;
                return;
            }

            ExpertsConfig next = yaml.readValue(bytes, ExpertsConfig.class);
            if (next == null) next = new ExpertsConfig();

            this.cfg = next;
            this.lastModified = lm;
            this.lastSha = sha;

            this.nextAllowedReloadAt = System.currentTimeMillis() + DEBOUNCE_MS;
            this.backoffMs = 2_000L;

            installEnabledFlag();

            if (log.isDebugEnabled()) {
                log.debug("[experts.yml] reloaded (lm={}, sha={})", lm, sha.substring(0, 8));
            }
        } catch (Exception e) {
            this.nextAllowedReloadAt = System.currentTimeMillis() + backoffMs;
            this.backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            log.warn("[experts.yml] reload failed: {} (backoff={}ms)", e.getMessage(), backoffMs);
        }
    }

    private String location() {
        String loc = env.getProperty(LOCATION_PROP);
        return (loc == null || loc.isBlank()) ? DEFAULT_LOCATION : loc;
    }

    private Resource resolve() {
        try { return resourceLoader.getResource(location()); }
        catch (Exception e) { log.warn("[experts.yml] resolve failed: {}", e.getMessage()); return null; }
    }

    private static long safeLastModified(Resource res) {
        try { return res.lastModified(); } catch (Exception e) { return -1L; }
    }

    private static byte[] readAllBytes(Resource res) {
        try (InputStream in = res.getInputStream()) { return in.readAllBytes(); }
        catch (Exception e) { return null; }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void installEnabledFlag() {
        if (!(env instanceof ConfigurableEnvironment ce)) return;
        MutablePropertySources mps = ce.getPropertySources();
        if (mps.contains(PS_NAME)) mps.remove(PS_NAME);
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put(ENABLED_AUTO_KEY, "true");
        mps.addFirst(new MapPropertySource(PS_NAME, flag));
    }
}