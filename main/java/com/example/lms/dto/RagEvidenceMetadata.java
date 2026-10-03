package com.example.lms.dto;

import com.example.lms.trace.SafeRedactor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, redacted citation metadata promoted from retrieval candidates.
 *
 * <p>Do not add raw snippets, raw queries, keys, or owner tokens here. Snippet
 * hashes belong in TraceStore diagnostics, not the client DTO.</p>
 */
public record RagEvidenceMetadata(
        String marker,
        String kind,
        String title,
        String source,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        Integer rank,
        Double confidence,
        String confidenceSource,
        AttachmentProvenance attachment
) {
    public RagEvidenceMetadata(String marker,String kind,String title,String source,String filePath,
            Integer lineStart,Integer lineEnd,Integer rank,Double confidence,String confidenceSource){
        this(marker,kind,title,source,filePath,lineStart,lineEnd,rank,confidence,confidenceSource,null);
    }

    /** Local provenance is not a web URL, filesystem path, or executable instruction. */
    public record AttachmentProvenance(String sourceId,long revision,String filename,String role,String locator){
        public AttachmentProvenance{
            if(com.example.lms.service.rag.graph.GeneralGraphSourceAuthority.sourceAttachmentId(sourceId)==null
                    ||revision<=0)throw new IllegalArgumentException("attachment_citation_identity");
            filename=clean(filename);role=clean(role);locator=clean(locator);
            if(filename==null||role==null||locator==null)throw new IllegalArgumentException("attachment_citation_locator");
            filename=filename.replace('\\','/');filename=filename.substring(filename.lastIndexOf('/')+1);
        }
        public String label(){return filename+" — "+role+"; rev "+revision+"; "+locator+"; DATA_ONLY";}
        public static AttachmentProvenance from(Map<String,Object> metadata){
            if(metadata==null||!"attachment".equals(metadata.get("source")))return null;
            try{
                Object revision=metadata.get("sourceRevision");
                if(!(revision instanceof Number number))return null;
                return new AttachmentProvenance((String)metadata.get("sourceId"),number.longValue(),
                    (String)metadata.get("displayName"),(String)metadata.get("documentRole"),(String)metadata.get("locator"));
            }catch(IllegalArgumentException|ClassCastException invalid){return null;}
        }
    }
    public RagEvidenceMetadata {
        marker = clean(marker);
        kind = clean(kind);
        title = clean(title);
        source = cleanSource(source);
        filePath = clean(filePath);
        confidenceSource = clean(confidenceSource);
        if (confidence != null && !Double.isFinite(confidence)) {
            confidence = null;
        }
    }

    public Map<String, Object> toTraceMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "marker", marker);
        put(out, "kind", kind);
        put(out, "title", title);
        put(out, "source", source);
        put(out, "filePath", filePath);
        put(out, "lineStart", lineStart);
        put(out, "lineEnd", lineEnd);
        put(out, "rank", rank);
        put(out, "confidence", confidence);
        put(out, "confidenceSource", confidenceSource);
        if(attachment!=null)out.put("attachment",Map.of("sourceId",attachment.sourceId(),"revision",attachment.revision(),
            "filename",attachment.filename(),"role",attachment.role(),"locator",attachment.locator()));
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        out.put(key, value);
    }

    private static String cleanSource(String value) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        String safe = SafeRedactor.safeMessage(s, 1000);
        if (s.isEmpty() || !s.equals(safe)) {
            return null;
        }
        try {
            java.net.URI.create(safe);
            return safe;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        String safe = SafeRedactor.safeMessage(s, 512);
        return safe == null || safe.isBlank() ? null : safe;
    }
}
