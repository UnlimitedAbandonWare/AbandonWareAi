package com.example.lms.trial;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Optional;




/**
 * Service responsible for enforcing the anonymous trial quota.  Each trial
 * identifier is mapped onto a {@link TrialTicket} row that records the
 * number of consumed requests and the time when the current window ends.
 * The quota is decremented on each successful call via {@link #consume}.
 */
@Service
public class TrialQuotaService {
    private final TrialProperties props;
    private final TrialTicketRepository repo;

    public TrialQuotaService(TrialProperties props, TrialTicketRepository repo) {
        this.props = props;
        this.repo = repo;
    }

    /**
     * Attempt to consume one allowance for the given trial ID.  If the
     * current window has expired the count resets to zero and the window
     * end is advanced.  Returns a {@link Result} object indicating
     * whether the request is allowed and the remaining quota.  This method
     * executes in a transaction to ensure optimistic locking is honoured.
     *
     * @param trialId the opaque trial identifier
     * @return result containing allowance state and remaining slots
     */
    @Transactional
    public Result consume(String trialId) {
        if (!props.isEnabled() || props.getLimit() <= 0) {
            return new Result(true, Integer.MAX_VALUE);
        }
        Instant now = Instant.now();
        TrialTicket ticket = repo.findByTrialId(trialId).orElse(null);
        if (ticket == null) {
            // first use for this ID
            ticket = new TrialTicket(trialId, 0, now.plus(props.getWindow()));
        } else if (now.isAfter(ticket.getWindowEnd())) {
            // window expired - reset count and extend window
            ticket.setCount(0);
            ticket.setWindowEnd(now.plus(props.getWindow()));
        }
        int count = ticket.getCount();
        int limit = props.getLimit();
        boolean allowed = count < limit;
        if (allowed) {
            ticket.setCount(count + 1);
            repo.save(ticket);
        }
        int remaining = allowed ? (limit - ticket.getCount()) : 0;
        return new Result(allowed, remaining);
    }

    /**
     * Container for the result of a quota consumption attempt.
     */
    public record Result(boolean allowed, int remaining) {}
}