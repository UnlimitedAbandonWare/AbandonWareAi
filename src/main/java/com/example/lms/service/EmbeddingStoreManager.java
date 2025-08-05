package com.example.lms.service;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Loads translation memory rows into the in‑memory embedding store at startup.
 *
 * <p>💡 <strong>Null/blank strings are filtered out first</strong> so that
 * {@code TextSegment.from()} never receives an empty value – this is what caused the
 * {@code IllegalArgumentException: text cannot be null or blank} you saw.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingStoreManager {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel              embeddingModel;
    private final TranslationMemoryRepository memoryRepo;

    @PostConstruct
    @Transactional(readOnly = true)
    public void init() {
        log.info("🗂️  Embedding Store 초기화를 시작합니다…");

        // 1️⃣  DB에서 TranslationMemory 전부 가져오기 → 문자열 추출
        List<TextSegment> segments = memoryRepo.findAll().stream()
                .map(TranslationMemory::getCorrected)          // 필요에 따라 getSourceHash 로 교체
                .filter(s -> s != null && !s.isBlank())        // ⚠️  방어 로직 (핵심!)
                .map(TextSegment::from)
                .toList();

        if (segments.isEmpty()) {
            log.info("Embedding Store에 추가할 유효한 문장이 없습니다.");
            return;
        }

        // 2️⃣  한꺼번에 임베딩 & 저장
        embeddingStore.addAll(embeddingModel.embedAll(segments).content());
        log.info("✅  {}개의 문장을 Embedding Store에 성공적으로 적재했습니다.", segments.size());
    }
}
