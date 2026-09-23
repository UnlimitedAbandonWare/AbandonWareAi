package com.example.lms.service.rag.feedback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;




/**
 * A registry for per-session search feedback.  When a user downvotes a search
 * result the associated host, title and keywords are added to the session’s
 * blocklist.  Subsequent searches in the same session will exclude results
 * matching any blocked host, keyword or exact phrase.  Entries expire
 * automatically after a period of inactivity.
 */
@Component
public class FeedbackBlocklistRegistry {

    @Getter
    @AllArgsConstructor
    public static class Blocklist {
        private final Set<String> hosts;
        private final Set<String> keywords;
        private final Set<String> phrases;
    }

    private final Cache<String, Blocklist> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(2))
            .maximumSize(10_000)
            .build();

    /**
     * Retrieve or create a blocklist for the given session id.  When none
     * exists a new empty blocklist is created and stored.
     *
     * @param sid the session identifier
     * @return the associated Blocklist
     */
    public Blocklist get(String sid) {
        return cache.get(sid, k -> new Blocklist(
                Collections.synchronizedSet(new HashSet<>()),
                Collections.synchronizedSet(new HashSet<>()),
                Collections.synchronizedSet(new HashSet<>())
        ));
    }

    /**
     * Record a downvote for the given session.  The host, title (phrase) and
     * reason keywords are extracted and added to the session’s blocklist.
     *
     * @param sid    the session identifier
     * @param host   the host name of the downvoted result (may be null)
     * @param title  the title of the downvoted result (may be null)
     * @param url    the URL of the downvoted result (unused but accepted for API symmetry)
     * @param reason a comma or space delimited string of reasons
     */
    public void downvote(String sid, String host, String title, String url, String reason) {
        if (sid == null || sid.isBlank()) return;
        var bl = get(sid);
        if (host != null && !host.isBlank()) {
            bl.hosts.add(host.toLowerCase());
        }
        if (title != null && title.trim().length() >= 2) {
            bl.phrases.add(title.trim());
        }
        // Split on comma and whitespace
        Arrays.stream(Optional.ofNullable(reason).orElse("").split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> s.length() >= 2)
                .forEach(bl.keywords::add);
    }
}