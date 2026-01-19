package com.example.lms.scheduler;

import com.example.lms.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * VectorStoreService 버퍼를 30초마다 flush 합니다.
 * cron 값은 application.yml 의 indexing.cron 과 별도로
 * `vector.flush.cron` 으로 오버라이드 가능합니다.
 */
@Component
@RequiredArgsConstructor
public class VectorStoreBufferScheduler {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreBufferScheduler.class);

    private final VectorStoreService vectorStoreService;

    /** 기본: 30초마다 flush (초/분/시/일/월/요일) */
    @Scheduled(cron = "${vector.flush.cron:0/30 * * * * *}")
    public void flushVectorBuffer() {
        try {
            vectorStoreService.flush();
        } catch (Exception e) {
            log.warn("[VectorStore] flush 실패", e);
        }
    }
}