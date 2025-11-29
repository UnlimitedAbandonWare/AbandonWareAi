package com.example.lms.service.rag.pre;

import com.example.lms.location.LocationService;
import com.example.lms.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;



/**
 * Query preprocessor that rewrites location pronouns such as "여기", "여기서",
 * "이곳" and "근처" into concrete addresses when available.  This helps
 * search services disambiguate vague location queries and reduces
 * cross-session RAG contamination.  The preprocessor consults the
 * {@link LocationService} for the current user’s most recently resolved
 * address and, when present, replaces each pronoun occurrence with the
 * address followed by the suffix "에서" (meaning "from").  If no
 * address is cached or the query does not contain any pronouns, the
 * original query is returned unchanged.
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class LocationContextQueryPreprocessor implements QueryContextPreprocessor {

    private final LocationService locationService;

    @Override
    public String enrich(String q) {
        if (q == null || q.isBlank()) return q;
        String s = q.trim();
        // If no pronoun of interest is present, return as is.
        if (!s.matches(".*\\b(여기|여기서|이곳|근처)\\b.*")) return s;
        // Determine the current user id via the security context.  When
        // unavailable, bail out early.
        String userId = UserContext.currentUserId().orElse(null);
        if (userId == null || userId.isBlank()) return s;
        var addrOpt = locationService.getResolvedAddress(userId);
        if (addrOpt.isEmpty()) return s;
        var a = addrOpt.get();
        // Prefer road address when available; otherwise compose from city and district.
        String addr = null;
        if (a.road() != null && !a.road().isBlank()) {
            addr = a.road();
        } else {
            String city = a.city() == null ? "" : a.city();
            String district = a.district() == null ? "" : a.district();
            addr = (city + " " + district).trim();
        }
        if (addr == null || addr.isBlank()) return s;
        // Replace each pronoun with the address followed by "에서".
        return s.replaceAll("\\b(여기서|여기|이곳|근처)\\b", addr + "에서");
    }
}