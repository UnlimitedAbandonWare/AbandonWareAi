package com.example.lms.trial;

import jakarta.persistence.*;
import java.time.Instant;



/**
 * JPA entity representing a per-trial quota record.  Each unique trial
 * identifier corresponds to a single row in this table.  The {@code count}
 * column tracks the number of consumed requests within the current
 * {@code windowEnd}.  When the window expires the count resets and
 * windowEnd is advanced by the configured window length.  An optimistic
 * locking {@code version} column guards against concurrent updates.
 */
@Entity
@Table(name = "trial_ticket", indexes = {
        @Index(name = "ux_trial_ticket_trial_id", columnList = "trial_id", unique = true)
})
public class TrialTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trial_id", nullable = false, unique = true)
    private String trialId;

    @Column(name = "count", nullable = false)
    private int count;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Version
    private Long version;

    public TrialTicket() {
    }

    public TrialTicket(String trialId, int count, Instant windowEnd) {
        this.trialId = trialId;
        this.count = count;
        this.windowEnd = windowEnd;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTrialId() {
        return trialId;
    }

    public void setTrialId(String trialId) {
        this.trialId = trialId;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}