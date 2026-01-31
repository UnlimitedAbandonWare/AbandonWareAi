package com.example.lms.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quick runtime classpath probe.
 *
 * <p>
 * Purpose: when a merge/boundary accidentally drops a class from the runtime
 * JAR, it manifests as {@code ClassNotFoundException}/{@code NoClassDefFoundError}
 * during request handling. This endpoint helps confirm what's actually on the
 * classpath without attaching a debugger.
 * </p>
 */
@RestController
@RequestMapping("/api/diagnostics/debug")
public class ClasspathDiagnosticsController {

    @GetMapping(value = "/classpath", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> classpath() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("GuardContextHolder", probe("com.example.lms.service.guard.GuardContextHolder"));
        out.put("RuleBreakContext", probe("com.example.lms.guard.rulebreak.RuleBreakContext"));
        // NOTE: In this codebase we intentionally refactor away Lombok builders in
        // critical request-path contexts to avoid runtime CNFE on missing inner
        // classes (RuleBreakContext$RuleBreakContextBuilder).
        out.put("RuleBreakContextBuilder", probeOptional(
                "com.example.lms.guard.rulebreak.RuleBreakContext$RuleBreakContextBuilder",
                false,
                "Builder intentionally removed / not required at runtime"
        ));
        out.put("FaultMaskingLayerMonitor", probe("com.example.lms.infra.resilience.FaultMaskingLayerMonitor"));
        out.put("OllamaEmbeddingModel", probe("com.example.lms.service.embedding.OllamaEmbeddingModel"));
        return out;
    }

    private Map<String, Object> probe(String fqcn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", fqcn);
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> c = Class.forName(fqcn, false, cl);
            m.put("present", true);
            CodeSource cs = c.getProtectionDomain() == null ? null : c.getProtectionDomain().getCodeSource();
            URL loc = cs == null ? null : cs.getLocation();
            if (loc != null) m.put("location", String.valueOf(loc));
        } catch (Throwable t) {
            m.put("present", false);
            m.put("error", t.getClass().getName() + ":" + (t.getMessage() == null ? "" : t.getMessage()));
        }
        return m;
    }

    private Map<String, Object> probeOptional(String fqcn, boolean expectedPresent, String note) {
        Map<String, Object> m = probe(fqcn);
        m.put("expectedPresent", expectedPresent);
        if (note != null && !note.isBlank()) m.put("note", note);
        return m;
    }
}
