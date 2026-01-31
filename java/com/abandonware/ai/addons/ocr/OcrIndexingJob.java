package com.abandonware.ai.addons.ocr;

import com.abandonware.ai.addons.config.AddonsProperties;
import java.util.logging.Logger;



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