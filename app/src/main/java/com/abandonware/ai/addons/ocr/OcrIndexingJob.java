package com.abandonware.ai.addons.ocr;

import com.abandonware.ai.addons.config.AddonsProperties;
import java.util.logging.Logger;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.ocr.OcrIndexingJob
 * Role: config
 * Dependencies: com.abandonware.ai.addons.config.AddonsProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.ocr.OcrIndexingJob
role: config
*/
public class OcrIndexingJob {
    private static final Logger log = Logger.getLogger(OcrIndexingJob.class.getName());

    private final AddonsProperties props;
    private final OcrRetriever.OcrIndexPort indexPort;

    public OcrIndexingJob(AddonsProperties props, OcrRetriever.OcrIndexPort indexPort) {
        this.props = props; this.indexPort = indexPort;
    }

    public void index(String id, String text, String source) {
        if (!props.getOcr().isEnabled()) return;
        indexPort.index(new OcrDocument(id, text, source));
        log.fine(() -> "OCR indexed: " + id);
    }
}