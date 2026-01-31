package service.rag.handler;

import java.util.Collections;
import java.util.List;

/**
 * Stub OCR retriever to be wired later.
 * Returns empty results when OCR is disabled/unavailable.
 */
public class OcrRetriever {

    public static class Input {
        public List<String> files;
        public Input(List<String> files){ this.files = files; }
    }

    public static class Document {
        public final String id;
        public final String text;
        public final double score;
        public Document(String id, String text, double score){
            this.id = id; this.text = text; this.score = score;
        }
    }

    public List<Document> retrieve(Input input) {
        // Placeholder: real implementation should call OcrService and embed/chunk.
        return Collections.emptyList();
    }
}