package com.example.lms.service.soak;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                log.debug("[SOAK] export dir create failed: {}", d.getAbsolutePath());
                return;
            }
            File f = new File(d, fileName);
            String line = om.writeValueAsString(rep);
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line);
                w.write("\n");
            }
        } catch (Exception e) {
            // fail-soft
            log.debug("[SOAK] jsonl export failed: {}", e.toString());
        }
    }
}
