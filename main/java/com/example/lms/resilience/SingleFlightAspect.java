package com.example.lms.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryUtils;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Array;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 14)
@Component
@ConditionalOnBean(SingleFlightManager.class)
@ConditionalOnProperty(name = "cache.singleflight.enabled", havingValue = "true", matchIfMissing = false)
public class SingleFlightAspect {

    private static final Logger LOG = LoggerFactory.getLogger(SingleFlightAspect.class);
    private static final String METHOD_ID = "WebSearchRetriever.retrieve(Query)";
    private static final String INTERNAL_SCOPE_TOKEN = "singleflight.request.scope";

    private final SingleFlightManager manager;

    @Autowired
    public SingleFlightAspect(SingleFlightManager manager, Environment environment) {
        this.manager = manager;
        long timeout = environment.getProperty("cache.singleflight.timeout-ms", Long.class, 15_000L);
        String strategy = environment.getProperty("cache.singleflight.key-strategy", "METHOD_AND_ARGS");
        LOG.info("[SingleFlight] aspect enabled (timeout={}ms, strategy={})", timeout, strategy);
    }

    @Around("execution(* com.example.lms.service.rag.WebSearchRetriever.retrieve(dev.langchain4j.rag.query.Query))")
    public Object dedupe(ProceedingJoinPoint pjp) throws Throwable {
        Query query = queryArgument(pjp.getArgs());
        String key = requestScopeToken() + '|' + METHOD_ID + '|' + queryDigest(query);
        return manager.run(key, () -> {
            try {
                return pjp.proceed();
            } catch (Throwable failure) {
                return throwUnchecked(failure);
            }
        });
    }

    private static Query queryArgument(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Query query) {
                return query;
            }
        }
        return null;
    }

    private static String requestScopeToken() {
        Map<String, Object> context = TraceStore.context();
        synchronized (context) {
            Object existing = TraceStore.get(INTERNAL_SCOPE_TOKEN);
            if (existing instanceof String token && !token.isBlank()) {
                return token;
            }
            String token = UUID.randomUUID().toString();
            TraceStore.putInternal(INTERNAL_SCOPE_TOKEN, token);
            return token;
        }
    }

    private static String queryDigest(Query query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, query == null ? "" : query.text());
            Map<String, Object> metadata = new TreeMap<>(QueryUtils.metadata(query));
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                updateDigest(digest, entry.getKey());
                updateDigest(digest, canonicalValue(entry.getValue()));
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(frame(canonicalValue(entry.getKey())) + frame(canonicalValue(entry.getValue())));
            }
            Collections.sort(entries);
            return sequence("map", entries);
        }
        if (value instanceof Set<?> set) {
            List<String> values = new ArrayList<>(set.size());
            for (Object element : set) {
                values.add(canonicalValue(element));
            }
            Collections.sort(values);
            return sequence("set", values);
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object element : iterable) {
                values.add(canonicalValue(element));
            }
            return sequence("iterable", values);
        }
        if (value.getClass().isArray()) {
            List<String> values = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(canonicalValue(Array.get(value, index)));
            }
            return sequence("array", values);
        }
        return value.getClass().getName() + ':' + value;
    }

    private static String sequence(String type, List<String> values) {
        StringBuilder canonical = new StringBuilder(type).append('[');
        for (String value : values) {
            canonical.append(frame(value));
        }
        return canonical.append(']').toString();
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }
}
