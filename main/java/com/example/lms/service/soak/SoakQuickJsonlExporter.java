package com.example.lms.service.soak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;

/**
 * Append /internal/soak/quick responses to a JSONL file (dataset feedback loop).
 */
@Component
@ConditionalOnProperty(prefix = "soak.export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SoakQuickJsonlExporter {

    private static final Logger log = LoggerFactory.getLogger(SoakQuickJsonlExporter.class);

    private final ObjectMapper om = new ObjectMapper();

    @Value("${soak.export.dir:./data/soak}")
    private String dir;

    @Value("${soak.export.file:seed10.jsonl}")
    private String fileName;

    public synchronized void append(SoakQuickReport rep) {
        if (rep == null) return;
        try {
            File d = new File(dir);
            if (!d.exists() && !d.mkdirs()) {
                String path = d.getAbsolutePath();
                log.debug("[SOAK] export dir create failed pathHash={} pathLength={}",
                        SafeRedactor.hashValue(path), path == null ? 0 : path.length());
                return;
            }
            File exportRoot = d.getCanonicalFile();
            File f = new File(exportRoot, fileName).getCanonicalFile();
            if (!f.toPath().startsWith(exportRoot.toPath())) {
                log.debug("[SOAK] export path rejected fileNameHash={} fileNameLength={}",
                        SafeRedactor.hashValue(fileName), fileName == null ? 0 : fileName.length());
                return;
            }
            String line = om.writeValueAsString(rep);
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line);
                w.write("\n");
            }
        } catch (Exception e) {
            // fail-soft
            log.debug("[SOAK] jsonl export failed failureClass={} errorType={}",
                    NightmareBreaker.classify(e),
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
        }
    }
}
