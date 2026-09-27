package com.example.lms.api;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Process-local concurrent-work admission for public chat generation.
 *
 * <p>The caller supplies a canonical SHA-256 actor hash. Raw owner identifiers
 * are rejected and are never retained by this component.</p>
 */
@Component
public final class PublicChatAdmissionGuard {

    private static final int DEFAULT_GLOBAL_LIMIT = 64;
    private static final int DEFAULT_PER_OWNER_LIMIT = 2;
    private static final int DEFAULT_OWNER_CAPACITY = 4_096;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final Object stateLock = new Object();
    private final Map<String, Integer> activeByOwner = new HashMap<>();

    @Value("${public.chat-admission.global-limit:64}")
    private int configuredGlobalLimit = DEFAULT_GLOBAL_LIMIT;
    @Value("${public.chat-admission.per-owner-limit:2}")
    private int configuredPerOwnerLimit = DEFAULT_PER_OWNER_LIMIT;
    @Value("${public.chat-admission.owner-capacity:4096}")
    private int configuredOwnerCapacity = DEFAULT_OWNER_CAPACITY;

    private volatile int globalLimit;
    private volatile int perOwnerLimit;
    private volatile int ownerCapacity;
    private volatile Semaphore globalPermits;
    private int activeLeaseCount;

    public PublicChatAdmissionGuard() {
        configure(DEFAULT_GLOBAL_LIMIT, DEFAULT_PER_OWNER_LIMIT, DEFAULT_OWNER_CAPACITY);
    }

    PublicChatAdmissionGuard(int globalLimit, int perOwnerLimit, int ownerCapacity) {
        configure(globalLimit, perOwnerLimit, ownerCapacity);
    }

    @PostConstruct
    void validateConfiguration() {
        configure(configuredGlobalLimit, configuredPerOwnerLimit, configuredOwnerCapacity);
    }

    public Optional<Lease> tryAcquire(String ownerHash) {
        if (ownerHash == null || !SHA256_HEX.matcher(ownerHash).matches()) {
            return Optional.empty();
        }

        Semaphore permits = globalPermits;
        if (!permits.tryAcquire()) {
            return Optional.empty();
        }

        synchronized (stateLock) {
            Integer current = activeByOwner.get(ownerHash);
            if (current == null && activeByOwner.size() >= ownerCapacity) {
                permits.release();
                return Optional.empty();
            }
            int activeForOwner = current == null ? 0 : current;
            if (activeForOwner >= perOwnerLimit) {
                permits.release();
                return Optional.empty();
            }
            activeByOwner.put(ownerHash, activeForOwner + 1);
            activeLeaseCount++;
        }
        return Optional.of(new Lease(this, ownerHash, permits));
    }

    public static Rejection rejection() {
        return new Rejection();
    }

    private void configure(int requestedGlobalLimit, int requestedPerOwnerLimit, int requestedOwnerCapacity) {
        if (requestedGlobalLimit < 1 || requestedGlobalLimit > 10_000) {
            throw new IllegalArgumentException("invalid_chat_admission_global_limit");
        }
        if (requestedPerOwnerLimit < 1 || requestedPerOwnerLimit > requestedGlobalLimit) {
            throw new IllegalArgumentException("invalid_chat_admission_per_owner_limit");
        }
        if (requestedOwnerCapacity < 1 || requestedOwnerCapacity > 100_000) {
            throw new IllegalArgumentException("invalid_chat_admission_owner_capacity");
        }
        synchronized (stateLock) {
            if (activeLeaseCount != 0) {
                throw new IllegalStateException("chat_admission_configuration_in_use");
            }
            activeByOwner.clear();
            globalLimit = requestedGlobalLimit;
            perOwnerLimit = requestedPerOwnerLimit;
            ownerCapacity = requestedOwnerCapacity;
            globalPermits = new Semaphore(requestedGlobalLimit, true);
        }
    }

    private void release(String ownerHash, Semaphore permits) {
        boolean releaseGlobal = false;
        synchronized (stateLock) {
            Integer current = activeByOwner.get(ownerHash);
            if (current != null && current > 0) {
                if (current == 1) {
                    activeByOwner.remove(ownerHash);
                } else {
                    activeByOwner.put(ownerHash, current - 1);
                }
                activeLeaseCount--;
                releaseGlobal = true;
            }
        }
        if (releaseGlobal) {
            permits.release();
        }
    }

    int activeLeaseCountForTest() {
        synchronized (stateLock) {
            return activeLeaseCount;
        }
    }

    int activeOwnerCountForTest() {
        synchronized (stateLock) {
            return activeByOwner.size();
        }
    }

    int availableGlobalPermitsForTest() {
        return globalPermits.availablePermits();
    }

    public static final class Lease implements AutoCloseable {
        private final PublicChatAdmissionGuard owner;
        private final String ownerHash;
        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(PublicChatAdmissionGuard owner, String ownerHash, Semaphore permits) {
            this.owner = owner;
            this.ownerHash = ownerHash;
            this.permits = permits;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(ownerHash, permits);
            }
        }
    }

    public static final class Rejection extends RuntimeException {
        private Rejection() {
            super("chat_admission_exceeded");
        }

        public HttpStatus status() {
            return HttpStatus.TOO_MANY_REQUESTS;
        }

        public String reasonCode() {
            return "chat_admission_exceeded";
        }
    }
}
