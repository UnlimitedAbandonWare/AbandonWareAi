package com.example.lms.service;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.dto.learning.MemorySnippet;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads translation memory rows into the embedding store at startup.
 *
 * <p>
 * Null/blank strings are filtered out first so that
 * {@link TextSegment#from(String)}
 * never receives an empty value.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class EmbeddingStoreManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreManager.class);

    // Evidence / citation detection (fail-soft)
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(W|V|D)\\d+\\]");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final TranslationMemoryRepository memoryRepo;

    @Value("${vector.bootstrap.startup.enabled:false}")
    private boolean startupBootstrapEnabled;

    @Value("${vector.bootstrap.startup.limit:200}")
    private int startupBootstrapLimit;

    // 동일 sid는 프로세스 lifetime 동안 1회만 lazy bootstrap
    private final ConcurrentHashMap<String, Boolean> bootstrapOnce = new ConcurrentHashMap<>();

    // MERGE_HOOK:PROJ_AGENT::EMBED_BOOTSTRAP_IDS_SID_V1
    private record IndexedSegment(String id, TextSegment segment) {
    }

    @PostConstruct
    @Transactional(readOnly = true)
    public void init() {
        log.info("🗂️  Embedding Store 초기화를 시작합니다/* ... */");

        if (!startupBootstrapEnabled) {
            log.info("🗂️  (startup bootstrap) disabled: vector.bootstrap.startup.enabled=false");
            return;
        }

        int cap = Math.max(1, Math.min(startupBootstrapLimit, 5000));
        List<TranslationMemory> bootMemories = memoryRepo.findAll(
                PageRequest.of(0, cap, Sort.by(Sort.Order.desc("lastUsedAt"), Sort.Order.desc("createdAt")))
        ).getContent();

        List<IndexedSegment> items = bootMemories.stream()
                .map(this::toIndexedSegment)
                .filter(Objects::nonNull)
                .toList();

        if (items.isEmpty()) {
            log.info("Embedding Store에 추가할 유효한 문장이 없습니다.");
            return;
        }

        // Batch embed & upsert with stable ids.
        int batchSize = 256;
        for (int from = 0; from < items.size(); from += batchSize) {
            List<IndexedSegment> batch = items.subList(from, Math.min(from + batchSize, items.size()));
            List<TextSegment> segments = batch.stream().map(IndexedSegment::segment).toList();
            List<String> ids = batch.stream().map(IndexedSegment::id).toList();
            var embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);
        }

        log.info("✅  {}개의 문장을 Embedding Store에 성공적으로 적재했습니다.", items.size());
    }


    /**
     * 필요 시점(검색 결과 비었을 때)에만 세션 메모리 제한적 부트스트랩.
     * 동일 sid는 프로세스 lifetime 동안 1회만 실행.
     */
    public boolean bootstrapForSession(String sid, int limit) {
        String key = (sid == null || sid.isBlank()) ? LangChainRAGService.GLOBAL_SID : sid.trim();
        if (bootstrapOnce.putIfAbsent(key, Boolean.TRUE) != null) {
            return false;
        }
        try {
            int cap = Math.max(1, Math.min(limit, 500));
            List<String> sids = buildSidVariants(sid);
            List<TranslationMemory> memories = loadForBootstrap(sids, cap);
            if (memories.isEmpty()) {
                bootstrapOnce.remove(key);
                return false;
            }
            List<IndexedSegment> items = memories.stream()
                    .map(this::toIndexedSegment)
                    .filter(Objects::nonNull)
                    .toList();
            if (items.isEmpty()) {
                bootstrapOnce.remove(key);
                return false;
            }
            batchEmbed(items);
            log.info("✅ (lazy bootstrap) sid={} indexed={}", key, items.size());
            return true;
        } catch (Exception e) {
            bootstrapOnce.remove(key);
            log.warn("(lazy bootstrap) fail-soft sid={}: {}", key, e.toString());
            return false;
        }
    }

    private List<TranslationMemory> loadForBootstrap(List<String> sessionIds, int limit) {
        if (sessionIds == null || sessionIds.isEmpty()) return List.of();
        int cap = Math.max(1, Math.min(limit, 500));
        try {
            return memoryRepo.findBySessionIdIn(
                    sessionIds,
                    PageRequest.of(0, cap, Sort.by(Sort.Order.desc("lastUsedAt"), Sort.Order.desc("createdAt")))
            ).getContent();
        } catch (Exception e) {
            log.debug("loadForBootstrap fail-soft: {}", e.toString());
            return List.of();
        }
    }

    private static List<String> buildSidVariants(String sid) {
        LinkedHashSet<String> sids = new LinkedHashSet<>();
        sids.add(LangChainRAGService.GLOBAL_SID);
        if (sid != null && !sid.isBlank()) {
            String s = sid.trim();
            sids.add(s);
            if (s.matches("\\d+")) sids.add("chat-" + s);
            else if (s.startsWith("chat-") && s.length() > 5) sids.add(s.substring(5));
        }
        return new ArrayList<>(sids);
    }

    private IndexedSegment toIndexedSegment(TranslationMemory tm) {
        if (tm == null) return null;

        String text = chooseIndexText(tm);
        if (text == null || text.isBlank()) return null;

        String sid = (tm.getSessionId() == null || tm.getSessionId().isBlank())
                ? LangChainRAGService.GLOBAL_SID
                : tm.getSessionId();

        String st = normalizeSourceTag(tm.getSourceTag());

        int cc = detectCitationCount(text);
        boolean verified = computeVerified(st, tm.getConfidenceScore(), cc);

        // assistant 기록은 verified=false면 인덱싱하지 않음 (환각/무근거 문장 축적 방지)
        boolean isAssistant = "ASSISTANT".equalsIgnoreCase(st);
        if (isAssistant && !verified) return null;

        Map<String, Object> meta = new HashMap<>();
        meta.put(LangChainRAGService.META_SID, sid);
        meta.put(VectorMetaKeys.META_SOURCE_TAG, st);
        meta.put(VectorMetaKeys.META_ORIGIN, deriveOrigin(st));
        meta.put(VectorMetaKeys.META_VERIFIED, String.valueOf(verified));
        meta.put(VectorMetaKeys.META_CITATION_COUNT, cc);
        if (tm.getId() != null) meta.put("tm_id", tm.getId());
        meta.put("tm_field", (tm.getCorrected() != null && !tm.getCorrected().isBlank()) ? "corrected" : "content");

        String stableId = (tm.getId() != null)
                ? "tm:" + tm.getId()
                : "tm:" + DigestUtils.sha256Hex(text);

        return new IndexedSegment(stableId, TextSegment.from(text, Metadata.from(meta)));
    }

    private void batchEmbed(List<IndexedSegment> items) {
        if (items == null || items.isEmpty()) return;

        int batchSize = 256;
        for (int from = 0; from < items.size(); from += batchSize) {
            List<IndexedSegment> batch = items.subList(from, Math.min(from + batchSize, items.size()));
            List<TextSegment> segments = batch.stream().map(IndexedSegment::segment).toList();
            List<String> ids = batch.stream().map(IndexedSegment::id).toList();
            var embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);
        }
    }

    /**
     * 신규 학습으로 생성된 메모리 스니펫을 벡터 DB에 인덱싱합니다.
     * 빈 목록이나 null 입력은 무시됩니다.
     */
    public void index(List<MemorySnippet> memories) {
        if (memories == null || memories.isEmpty())
            return;
        try {
            // [HARDENING] index new snippets with GLOBAL_SID metadata + contamination
            // tracking meta
            List<IndexedSegment> batch = new ArrayList<>();
            for (MemorySnippet ms : memories) {
                if (ms == null)
                    continue;
                String text = ms.text();
                if (text == null || text.isBlank())
                    continue;

                int cc = detectCitationCount(text);

                Map<String, Object> meta = new HashMap<>();
                meta.put(LangChainRAGService.META_SID, LangChainRAGService.GLOBAL_SID);
                meta.put(VectorMetaKeys.META_SOURCE_TAG, "SYSTEM");
                meta.put(VectorMetaKeys.META_ORIGIN, "SYSTEM");
                meta.put(VectorMetaKeys.META_VERIFIED, String.valueOf(cc > 0));
                meta.put(VectorMetaKeys.META_CITATION_COUNT, cc);
                if (ms.subject() != null && !ms.subject().isBlank()) {
                    meta.put("ms_subject", ms.subject());
                }

                String stableId = "ms:"
                        + DigestUtils.sha256Hex((ms.subject() == null ? "" : ms.subject()) + "|" + text);
                batch.add(new IndexedSegment(stableId, TextSegment.from(text, Metadata.from(meta))));
            }

            if (!batch.isEmpty()) {
                List<String> ids = batch.stream().map(IndexedSegment::id).toList();
                List<TextSegment> segments = batch.stream().map(IndexedSegment::segment).toList();
                var embeddings = embeddingModel.embedAll(segments).content();
                embeddingStore.addAll(ids, embeddings, segments);
            }
        } catch (Exception e) {
            log.debug("Failed to index memory snippets: {}", e.toString());
        }
    }

    private static String chooseIndexText(TranslationMemory tm) {
        if (tm == null)
            return null;
        String corrected = tm.getCorrected();
        if (corrected != null && !corrected.isBlank()) {
            return corrected;
        }
        String content = tm.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        return null;
    }

    /** Normalize various source tags into a small stable set. */
    private static String normalizeSourceTag(String tag) {
        if (tag == null)
            return "UNKNOWN";
        String t = tag.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "ASSISTANT", "LLM", "AI_GENERATED" -> "ASSISTANT";
            case "USER", "USER_CORRECTION" -> "USER";
            case "OFFICIAL" -> "OFFICIAL";
            case "SYSTEM" -> "SYSTEM";
            default -> "WEB";
        };
    }

    /** Derive origin based on normalized source tag. */
    private static String deriveOrigin(String sourceTag) {
        String st = (sourceTag == null) ? "UNKNOWN" : sourceTag.toUpperCase(Locale.ROOT);
        return switch (st) {
            case "ASSISTANT" -> "LLM";
            case "USER" -> "USER";
            case "OFFICIAL", "WEB" -> "WEB";
            case "SYSTEM" -> "SYSTEM";
            default -> "SYSTEM";
        };
    }

    /** Cheap citation signal detector: URLs + evidence markers. */
    private static int detectCitationCount(String text) {
        if (text == null || text.isBlank())
            return 0;
        int count = 0;
        try {
            var m = URL_PATTERN.matcher(text);
            while (m.find())
                count++;
        } catch (Exception ignore) {
        }
        try {
            var m = CITATION_MARKER_PATTERN.matcher(text);
            while (m.find())
                count++;
        } catch (Exception ignore) {
        }
        return count;
    }

    /**
     * Decide whether the chunk should be treated as verified.
     *
     * <p>
     * Fail-soft: this is only used to block clearly contaminated ASSISTANT chunks.
     * For other origins the value is mostly informational.
     * </p>
     */
    private static boolean computeVerified(String sourceTag, Double confidenceScore, int citationCount) {
        String st = (sourceTag == null) ? "UNKNOWN" : sourceTag.toUpperCase(Locale.ROOT);

        // Non-assistant content is treated as verified by default.
        if (!"ASSISTANT".equals(st)) {
            return true;
        }

        // Assistant content: require explicit evidence signal OR high confidence.
        if (citationCount > 0) {
            return true;
        }
        double conf = (confidenceScore == null) ? 0.0 : confidenceScore.doubleValue();
        return conf >= 0.85;
    }
}
