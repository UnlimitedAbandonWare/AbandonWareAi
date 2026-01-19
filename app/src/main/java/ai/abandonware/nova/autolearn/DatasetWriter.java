package ai.abandonware.nova.autolearn;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends verified Q/A to a JSONL file.
 */
@Component
@ConditionalOnProperty(name = "autolearn.enabled", havingValue = "true")
public class DatasetWriter {

    private static final Logger log = LoggerFactory.getLogger(DatasetWriter.class);
    private final ReentrantLock lock = new ReentrantLock();

    @Value("${dataset.train-file-path:data/train_rag.jsonl}")
    private String trainFilePath;

    public boolean append(RagJsonlRecord rec) {
        lock.lock();
        try {
            File f = new File(trainFilePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (OutputStream out = new FileOutputStream(f, true);
                 OutputStreamWriter osw = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(osw)) {
                bw.write(rec.toJson());
                bw.write("\n");
            }
            log.info("[DatasetWriter] Appended one record to {}", f.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.warn("[DatasetWriter] Failed to append: {}", e.toString());
            return false;
        } finally {
            lock.unlock();
        }
    }
}