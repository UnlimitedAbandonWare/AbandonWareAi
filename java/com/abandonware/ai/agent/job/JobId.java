package com.abandonware.ai.agent.job;

import java.util.Objects;
import java.util.UUID;



/**
 * Simple wrapper around a job identifier string.  Job identifiers are
 * generated using UUIDs to ensure uniqueness.
 */
public final class JobId {
    private final String value;

    public JobId() {
        this.value = UUID.randomUUID().toString();
    }

    public JobId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JobId jobId = (JobId) o;
        return Objects.equals(value, jobId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}