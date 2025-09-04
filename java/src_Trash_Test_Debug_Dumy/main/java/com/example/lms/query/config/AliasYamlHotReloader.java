package com.example.lms.query.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Watches the alias.yml configuration file for changes and reloads it into the
 * Spring Environment when modified.  The contents of alias.yml are flattened
 * and prefixed with {@code ai.query.} before being registered as a
 * {@link MapPropertySource}.  Binding is then refreshed on the existing
 * {@link AiQueryProperties} bean via {@link Binder}.  This enables runtime
 * updates of query hygiene settings without restarting the application.
 */
@Component
public class AliasYamlHotReloader implements InitializingBean {

    private static final String PS_NAME = "alias-yml-prefixed";
    private static final String PREFIX = "ai.query.";
    private static final String EXTERNAL = "./config/alias.yml";
    private static final String CLASSPATH = "alias.yml";

    private final ConfigurableEnvironment environment;
    private final ApplicationContext context;
    private volatile long lastModified = -1L;
    private volatile String lastSha256 = "";

    /** Logger for reload events and errors. */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AliasYamlHotReloader.class);

    public AliasYamlHotReloader(ConfigurableEnvironment environment,
                                ApplicationContext context) {
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void afterPropertiesSet() {
        loadIfChanged(true);
    }

    @Scheduled(fixedDelay = 5000L)
    public void poll() {
        loadIfChanged(false);
    }

    private void loadIfChanged(boolean force) {
        try {
            Resource resource = resolveResource();
            if (!resource.exists()) {
                return;
            }
            long modified = safeLastModified(resource);
            String sha = sha256(resource);
            boolean changed = force || modified != lastModified || !Objects.equals(sha, lastSha256);
            if (!changed) {
                return;
            }
            Map<String, Object> flat = loadYamlAsFlatMap(resource);
            Map<String, Object> withPrefix = addPrefix(flat, PREFIX);
            // inject into environment
            MutablePropertySources sources = environment.getPropertySources();
            if (sources.contains(PS_NAME)) {
                sources.remove(PS_NAME);
            }
            sources.addFirst(new MapPropertySource(PS_NAME, withPrefix));
            // rebind existing AiQueryProperties bean with new values
            AiQueryProperties target = context.getBean(AiQueryProperties.class);
            Binder.get(environment).bind("ai.query", Bindable.ofInstance(target));
            lastModified = modified;
            lastSha256 = sha;
            log.info("[alias.yml] reloaded -> {}", resource.getDescription());
        } catch (Exception e) {
            log.warn("[alias.yml] reload failed: {}", e.getMessage());
        }
    }

    private Resource resolveResource() {
        Resource external = new FileSystemResource(EXTERNAL);
        return (external.exists() && external.isReadable()) ? external : new ClassPathResource(CLASSPATH);
    }

    private long safeLastModified(Resource resource) {
        try {
            return resource.lastModified();
        } catch (Exception e) {
            return -1L;
        }
    }

    private String sha256(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    private Map<String, Object> loadYamlAsFlatMap(Resource resource) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        var list = loader.load("alias-yml", resource);
        Map<String, Object> out = new LinkedHashMap<>();
        for (var ps : list) {
            for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) ps).getPropertyNames()) {
                out.put(name, ps.getProperty(name));
            }
        }
        return out;
    }

    private Map<String, Object> addPrefix(Map<String, Object> source, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>(source.size());
        source.forEach((k, v) -> out.put(prefix + k, v));
        return out;
    }
}