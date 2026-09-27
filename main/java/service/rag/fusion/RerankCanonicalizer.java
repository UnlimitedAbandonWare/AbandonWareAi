package service.rag.fusion;

import java.net.URI;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import service.rag.model.ScoredDoc;

/**
 * Helper for deriving a canonical key from a document identifier.
 *
 * <p>When provided with a URL it lowercases the host, removes certain tracking
 * parameters (notably those prefixed with <code>utm_</code>) and drops any
 * fragment portion.  If parsing fails the original identifier is returned
 * unchanged.  This is used prior to fusion to deduplicate results that are
 * essentially the same but differ by trivial URL variants.</p>
 */
public final class RerankCanonicalizer {
    private static final Logger log = Logger.getLogger(RerankCanonicalizer.class.getName());

    /**
     * Canonicalise the identifier of a scored document.
     *
     * @param d the document
     * @return canonical key
     */
    public String keyOf(ScoredDoc d) {
        return canonical(d.getId());
    }

    /**
     * Canonicalise a raw identifier string.  Attempts to parse as a URI and
     * remove common tracking parameters and fragment identifiers.  On
     * exception the original string is returned.
     *
     * @param raw the raw identifier
     * @return canonicalised identifier
     */
    public String canonical(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            // strip utm_* parameters
            query = query.replaceAll("(?i)(^|&)(utm_[^&=]+=[^&]*)", "");
            query = query.replaceAll("^&+|&+$", "");
            String base = host + path + (query.isEmpty() ? "" : "?" + query);
            return base.replaceAll("#.*$", "");
        } catch (Exception e) {
            logFailSoft("canonicalize", e);
            return raw;
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.fine("[AWX][rag][rerank-canonicalizer] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
