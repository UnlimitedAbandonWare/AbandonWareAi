package com.example.lms.service.rag.quality;

import java.util.List;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.config.NaverFilterProperties;
import org.springframework.beans.factory.annotation.Autowired;



/**
 * Guard responsible for determining whether chipset claims should be
 * treated as fact or speculation.  When the provided evidence set
 * lacks any document mentioning a chipset keyword (e.g. "snapdragon",
 * "스냅드래곤", "chipset", "칩셋") the guard requires that the assistant
 * phrase chipset related statements as rumours rather than definitive
 * claims.  More sophisticated logic could incorporate domain
 * credibility, but the current codebase does not expose such metadata
 * on {@link WebDocument}.
 */
public class EvidenceAwareGuard {

    /** Injected filter properties providing the official allowlist. May be null when not configured. */
    @Autowired(required = false)
    private NaverFilterProperties props;

    /**
     * Suffixes considered as news domains for chipset credibility.
     */
    private static final String[] NEWS_SUFFIXES = {
            "news.naver.com", "zdnet.co.kr", "itmedia.co.jp", "bbc.com", "cnn.com"
    };

    /**
     * Decide whether a definitive chipset claim is permitted based on
     * the supplied evidence documents.  At least one document must
     * mention a chipset keyword for claims to be considered safe.
     *
     * @param evidences a list of evidence documents; may be null
     * @return {@code true} if a chipset claim is allowed
     */
    public boolean allowChipsetClaim(List<WebDocument> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return false;
        }
        for (WebDocument d : evidences) {
            if (d == null) {
                continue;
            }
            // Only allow when the document contains a chipset keyword AND the domain
            // is considered official or news.
            if (containsChipsetKeyword(d)) {
                String url = d.getUrl();
                String host = null;
                if (url != null) {
                    try {
                        host = java.net.URI.create(url).getHost();
                    } catch (Exception ignore) {
                        host = null;
                    }
                }
                if (host != null) {
                    boolean official = false;
                    // When props available, treat any suffix in allowlist as official
                    if (props != null && props.getDomainAllowlist() != null) {
                        for (String suffix : props.getDomainAllowlist()) {
                            if (host.endsWith(suffix)) {
                                official = true;
                                break;
                            }
                        }
                    }
                    boolean news = false;
                    for (String suf : NEWS_SUFFIXES) {
                        if (host.endsWith(suf)) {
                            news = true;
                            break;
                        }
                    }
                    if (official || news) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check whether the document title or snippet contains a chipset
     * related keyword.  Performs a lower-case substring search on
     * concatenated title and snippet.  Null values are treated as empty
     * strings.
     *
     * @param d the document to inspect
     * @return true if a chipset keyword is present
     */
    private boolean containsChipsetKeyword(WebDocument d) {
        String title = d.getTitle();
        String snippet = d.getSnippet();
        String text = ((title == null ? "" : title) + " " + (snippet == null ? "" : snippet)).toLowerCase();
        return text.contains("snapdragon")
                || text.contains("스냅드래곤")
                || text.contains("chipset")
                || text.contains("칩셋");
    }
}