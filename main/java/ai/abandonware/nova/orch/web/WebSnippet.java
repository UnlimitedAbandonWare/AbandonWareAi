package ai.abandonware.nova.orch.web;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small parser wrapper around a raw snippet string returned by the existing web search providers.
 */
public record WebSnippet(String raw, String url, String host, String lower) {

    private static final System.Logger LOG = System.getLogger(WebSnippet.class.getName());
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    public static WebSnippet parse(String raw) {
        String r = raw == null ? "" : raw;
        String url = extractUrl(r).orElse(null);
        String host = null;
        if (url != null) {
            try {
                host = URI.create(url).getHost();
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Web snippet host parse skipped stage=url_host errorType=" + errorType(ex));
            }
        }
        String lower = r.toLowerCase(Locale.ROOT);
        return new WebSnippet(r, url, host, lower);
    }

    private static String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error.getClass().getSimpleName();
    }

    private static Optional<String> extractUrl(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        Matcher m = URL.matcher(s);
        if (!m.find()) return Optional.empty();
        return Optional.ofNullable(m.group());
    }
}
