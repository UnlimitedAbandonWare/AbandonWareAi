// src/main/java/com/example/lms/service/MemoryReinforcementService.java
package com.example.lms.service;

import com.example.lms.repository.MemoryRepository;
import com.example.lms.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryReinforcementService {

    private final MemoryRepository memoryRepository;

    /**
     * ⚠️ Proxy-self 주입(순환 의존 방지를 위해 @Lazy).
     * - 외부에서 `reinforceAsync()` 를 부르면 Spring AOP proxy 를 거쳐
     *   `incrementHitCount()` 가 **새 트랜잭션** 안에서 실행된다.
     */
    @Lazy
    private final MemoryReinforcementService self = this;

    /* ───────────────────────────────────────────
     * 1) PUBLIC 진입점 - 동기로 호출 → 내부에서 비동기 위임
     * ─────────────────────────────────────────── */
    public void reinforceMemoryWithText(String text) {
        if (!StringUtils.hasText(text)) return;
        // 비동기 실행은 proxy-self 에게 위임
        self.reinforceAsync(text);
    }

    /* ───────────────────────────────────────────
     * 2) 실제 비동기 작업  (@Async  +  새 트랜잭션)
     * ─────────────────────────────────────────── */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceAsync(String text) {
        final String hash = HashUtil.sha256(text);

        try {
            int rows = memoryRepository.incrementHitCountBySourceHash(hash);
            if (rows > 0) {
                log.debug("[Memory] hitCount+1  ✅  hash={}...", hash.substring(0,12));
            } else {
                log.warn ("[Memory] 대상 없음      ⚠️  hash={}...", hash.substring(0,12));
            }

            // TODO: qValue·rewardM2 등 추가 보상로직이 있으면 여기서 한-방 UPDATE
        } catch (Exception e) {
            log.error("[Memory] hitCount 업데이트 실패  ❌  hash={}...", hash.substring(0,12), e);
            /* 예외를 던지면 REQUIRES_NEW 트랜잭션은 자동 Rollback */
            throw e;
        }
    }
}
