package com.example.lms.conversation.archive;

import com.example.lms.guard.PiiSanitizer;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.trace.SafeRedactor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class ConversationTopicTimelineBuilder {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>()]+", Pattern.CASE_INSENSITIVE);
    private final PiiSanitizer piiSanitizer = new PiiSanitizer();

    public List<Chunk> build(String sessionId, List<ClassifiedRecord> records, int maxChars, int maxMessages) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        int chars = Math.max(600, maxChars);
        int messages = Math.max(1, maxMessages);
        List<Chunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Map<String, Object> meta = null;
        ConversationMessageKind currentKind = null;
        String currentAnchor = null;
        int messageCount = 0;
        int chunkIndex = 0;

        for (ClassifiedRecord item : records) {
            if (item == null || item.record() == null || item.decision() == null || !item.decision().ingestible()) {
                continue;
            }
            String safeText = sanitizeForIngest(item.record().message());
            if (safeText.isBlank()) {
                continue;
            }
            ConversationMessageKind kind = item.decision().kind();
            String anchor = anchorFor(kind, safeText);
            boolean rotate = currentKind != null
                    && (kind != currentKind || !anchor.equals(currentAnchor)
                    || buf.length() + safeText.length() + 8 > chars
                    || messageCount >= messages);
            if (rotate) {
                out.add(toChunk(sessionId, buf.toString(), meta, chunkIndex++));
                buf.setLength(0);
                meta = null;
                messageCount = 0;
            }
            if (meta == null) {
                meta = metadataFor(kind, anchor, item.record(), item.decision());
                currentKind = kind;
                currentAnchor = anchor;
            }
            if (!buf.isEmpty()) {
                buf.append("\n");
            }
            buf.append("- ").append(limit(safeText, Math.max(120, chars / 2)));
            messageCount++;
            meta.put("conversation.archive.message_count", messageCount);
        }
        if (!buf.isEmpty() && meta != null) {
            out.add(toChunk(sessionId, buf.toString(), meta, chunkIndex));
        }
        return out;
    }

    public String sanitizeForIngest(String text) {
        String t = text == null ? "" : text;
        t = piiSanitizer.apply(t);
        t = SafeRedactor.redact(t);
        return t == null ? "" : t.replace('\r', '\n').replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static Chunk toChunk(String sessionId, String text, Map<String, Object> meta, int chunkIndex) {
        Map<String, Object> m = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        m.put(VectorMetaKeys.META_CHUNK_INDEX, chunkIndex);
        m.put(VectorMetaKeys.META_CHUNK_COUNT, 1);
        m.put("conversation.archive.message_count", m.getOrDefault("conversation.archive.message_count", 0));
        String id = "conversation:" + SafeRedactor.hash12(String.valueOf(sessionId) + "|" + text + "|" + chunkIndex);
        m.put(VectorMetaKeys.META_DOC_ID, id);
        m.put(VectorMetaKeys.META_CHUNK_ID, id);
        return new Chunk(id, text, m);
    }

    private static Map<String, Object> metadataFor(
            ConversationMessageKind kind,
            String anchor,
            ConversationMessageRecord record,
            ConversationNoiseClassifier.Decision decision) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(VectorMetaKeys.META_SOURCE_TAG, "CONVERSATION_ARCHIVE");
        meta.put(VectorMetaKeys.META_ORIGIN, "USER");
        String sourcePathSummary = sourcePathSummary(record == null ? null : record.sourcePath());
        meta.put(VectorMetaKeys.META_SOURCE_PATH, "conversation_archive");
        putSourcePathDiagnostics(meta, sourcePathSummary);
        meta.put("conversation.archive.kind", kind.wireName());
        meta.put("conversation.archive.reason", safeLabel(decision == null ? "" : decision.reason(), "accepted"));
        meta.put("conversation.archive.message_count", 0);
        meta.put(VectorMetaKeys.META_SCOPE_ANCHOR_KEY, anchor);
        meta.put(VectorMetaKeys.META_SCOPE_KIND, "WHOLE");
        meta.put(VectorMetaKeys.META_SCOPE_CONF, 0.82d);
        meta.put(VectorMetaKeys.META_ENTITY, anchor);
        if (kind == ConversationMessageKind.HUMAN_MESSAGE) {
            meta.put(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
            meta.put(VectorMetaKeys.META_MEMORY_KIND, "conversation_discussion");
            meta.put(VectorMetaKeys.META_VERIFIED, "false");
            meta.put(VectorMetaKeys.META_VERIFICATION_NEEDED, "false");
        } else {
            meta.put(VectorMetaKeys.META_DOC_TYPE, "KB");
            meta.put(VectorMetaKeys.META_VERIFIED, "false");
            meta.put(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
            meta.put(VectorMetaKeys.META_KB_DOMAIN, "conversation_archive");
            if (kind == ConversationMessageKind.LINK_ARTIFACT) {
                meta.put(VectorMetaKeys.META_CITATION_URL_COUNT, 1);
            }
        }
        return meta;
    }

    private static String anchorFor(ConversationMessageKind kind, String text) {
        if (kind == ConversationMessageKind.LINK_ARTIFACT) {
            String host = firstHost(text);
            if (host != null && !host.isBlank()) {
                return "conversation_archive:" + host;
            }
        }
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3 && !isStop(token)) {
                return "conversation_archive:" + limit(token, 48);
            }
        }
        return "conversation_archive:discussion";
    }

    private static String firstHost(String text) {
        if (text == null) {
            return null;
        }
        var matcher = URL.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            URI uri = URI.create(matcher.group());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
            traceSuppressed("conversationArchive.firstHost", ignore);
            return null;
        }
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = SafeRedactor.traceLabelOrFallback(
                ignored == null ? null : ignored.getClass().getSimpleName(), "unknown");
        TraceStore.put("conversation.archive.suppressed.stage", safeStage);
        TraceStore.put("conversation.archive.suppressed.errorType", errorType);
        TraceStore.put("conversation.archive.suppressed." + safeStage, true);
        TraceStore.put("conversation.archive.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String sourcePathSummary(String path) {
        if (path == null || path.isBlank()) {
            return "conversation_archive";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return limit(name.replaceAll("[^A-Za-z0-9._ -]+", "_"), 120);
    }

    private static void putSourcePathDiagnostics(Map<String, Object> meta, String summary) {
        String value = summary == null ? "" : summary;
        if ("conversation_archive".equals(value)) {
            meta.put("conversation.archive.source_path_hash", "");
            meta.put("conversation.archive.source_path_length", 0);
            return;
        }
        meta.put("conversation.archive.source_path_hash", SafeRedactor.hashValue(value));
        meta.put("conversation.archive.source_path_length", value.length());
    }

    private static boolean isStop(String token) {
        return token.equals("the") || token.equals("and") || token.equals("for") || token.equals("this")
                || token.equals("that") || token.equals("with") || token.equals("http") || token.equals("https");
    }

    private static String safeLabel(String value, String fallback) {
        String raw = value == null || value.isBlank() ? fallback : value;
        return limit(raw.replaceAll("[^A-Za-z0-9_.:-]+", "_"), 80);
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    public record ClassifiedRecord(ConversationMessageRecord record, ConversationNoiseClassifier.Decision decision) {
    }

    public record Chunk(String id, String text, Map<String, Object> metadata) {
    }
}
