package com.example.lms.service.rag.extract;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.TimeUnit;




@Component
public class PageContentScraper {
    private static final Logger log = LoggerFactory.getLogger(PageContentScraper.class);
    private static final int MAX_REDIRECTS = 5;

    @Value("${search.budget.per-page-ms:3500}")
    private int defaultPerPageMs;

    /**
     * URL의 HTML을 받아 본문 텍스트만 최대 길이로 깔끔히 반환.
     */
    public String fetchText(String url) {
        return fetchText(url, defaultPerPageMs);
    }

    /**
     * Fetches the text contents of the given URL within a configurable timeout.
     * When the timeout is reached or any error occurs, this method returns
     * {@code null} to signal that the caller should continue gracefully.
     *
     * @param url       the page URL to scrape
     * @param perPageMs the timeout in milliseconds applied to the request
     * @return the extracted and trimmed text, or {@code null} if the page could not be fetched
     */
    public String fetchText(String url, int perPageMs) {
        int timeoutMs = Math.max(1000, perPageMs);
        // One cumulative deadline covers the whole redirect chain; when a
        // request-scoped TimeBudget exists it caps it further. Each hop gets
        // only the remaining budget — never the full perPage timeout again —
        // and an exhausted budget stops the loop instead of passing
        // timeout(0) (infinite) to Jsoup.
        long pageDeadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean wireAttemptObserved = false;
        int redirectCount = 0;
        try {
            URI current = parseTarget(url);
            Document doc;
            while (true) {
                validatePublicTarget(current);
                long hopTimeoutMs = remainingBudgetMillis(pageDeadlineNanos);
                if (hopTimeoutMs <= 0L) {
                    throw new BudgetExhaustedException(wireAttemptObserved
                            ? "page_budget_exhausted"
                            : "request_budget_exhausted");
                }
                com.example.lms.search.DeadlineProbe.enter("page.hop");
                int appliedTimeoutMs = (int) Math.min(Integer.MAX_VALUE, hopTimeoutMs);
                // appliedTimeoutMs is the value actually handed to Jsoup —
                // recorded so a timeout(0) (infinite) can never hide here.
                com.example.lms.search.RequestTrace.emit("page.wire", "attempt",
                        "hop=" + redirectCount + ";timeout_ms=" + appliedTimeoutMs);
                Connection connection = openConnection(current.toASCIIString())
                        .userAgent("Mozilla/5.0 (compatible; AbandonWareBot/1.0)")
                        .timeout(appliedTimeoutMs)
                        .followRedirects(false);
                wireAttemptObserved = true;
                long wireStartedNs = System.nanoTime();
                Connection.Response response;
                try {
                    response = connection.execute();
                } catch (Exception wireFailure) {
                    com.example.lms.search.RequestTrace.emit("page.wire", "error",
                            "hop=" + redirectCount + ";type="
                                    + wireFailure.getClass().getSimpleName());
                    throw wireFailure;
                }
                long wireMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wireStartedNs);
                TimeBudget budgetAfterWire = TimeBudgetContext.get();
                boolean lateAfterCancel = budgetAfterWire != null && budgetAfterWire.cancelled();
                int statusCode = response.statusCode();
                // A call that was already in flight when the budget was
                // cancelled is a late return, not a post-cancel new call.
                com.example.lms.search.RequestTrace.emit("page.wire", "return",
                        "hop=" + redirectCount + ";status=" + statusCode + ";ms=" + wireMs
                                + (lateAfterCancel ? ";late_after_cancel" : ""));
                if (statusCode >= 300 && statusCode < 400) {
                    String location = response.header("Location");
                    closeResponseBody(response);
                    if (location == null || location.isBlank()) {
                        throw new OutboundPolicyException("redirect_location_missing");
                    }
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw new OutboundPolicyException("redirect_limit");
                    }
                    current = resolveRedirect(current, location);
                    redirectCount++;
                    continue;
                }
                doc = response.parse();
                break;
            }
            String text = doc.text();
            traceFetchSuccess(redirectCount);
            return (text != null) ? text.strip() : null;
        } catch (BudgetExhaustedException e) {
            com.example.lms.search.DeadlineProbe.skip("page.fetch", e.reason());
            traceBudgetExhausted(url, timeoutMs, e.reason(), wireAttemptObserved, redirectCount);
            return null;
        } catch (OutboundPolicyException e) {
            tracePolicyBlock(url, timeoutMs, e.reason(), wireAttemptObserved, redirectCount);
            return null;
        } catch (Exception e) {
            // Allow partial success by returning null on any failure
            traceFetchFailure(url, timeoutMs, e, wireAttemptObserved, redirectCount);
            return null;
        }
    }

    /**
     * Remaining milliseconds for the next redirect hop: the smaller of the
     * page-level deadline and the request-scoped {@link TimeBudget} (when a
     * request context exists). A cancelled or expired request budget yields 0.
     */
    static long remainingBudgetMillis(long pageDeadlineNanos) {
        long pageRemaining = TimeUnit.NANOSECONDS
                .toMillis(Math.max(0L, pageDeadlineNanos - System.nanoTime()));
        TimeBudget requestBudget = TimeBudgetContext.get();
        long requestRemaining = requestBudget != null
                ? requestBudget.remainingMillis()
                : Long.MAX_VALUE;
        return Math.min(pageRemaining, requestRemaining);
    }

    /** Seam for tests: builds the Jsoup connection for one hop. */
    protected Connection openConnection(String targetUrl) {
        return Jsoup.connect(targetUrl);
    }

    private static URI parseTarget(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("invalid url");
        }
        return new URI(url.strip());
    }

    private static URI resolveRedirect(URI current, String location) throws OutboundPolicyException {
        try {
            return current.resolve(new URI(location.strip()));
        } catch (Exception e) {
            throw new OutboundPolicyException("redirect_invalid");
        }
    }

    protected void validatePublicTarget(URI target) throws OutboundPolicyException {
        String scheme = target.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new OutboundPolicyException("scheme_not_allowed");
        }
        if (target.getRawUserInfo() != null) {
            throw new OutboundPolicyException("userinfo_not_allowed");
        }
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw new OutboundPolicyException("host_missing");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw new OutboundPolicyException("non_public_address");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new OutboundPolicyException("dns_unresolved");
            }
            for (InetAddress address : addresses) {
                if (isNonPublic(address)) {
                    throw new OutboundPolicyException("non_public_address");
                }
            }
        } catch (OutboundPolicyException e) {
            throw e;
        } catch (Exception e) {
            throw new OutboundPolicyException("dns_unresolved");
        }
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || first >= 224;
        }
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
    }

    private static void closeResponseBody(Connection.Response response) {
        try {
            var body = response.bodyStream();
            if (body != null) {
                body.close();
            }
        } catch (Exception closeFailure) {
            traceRedirectBodyCloseFailure(closeFailure);
        }
    }

    private static void traceRedirectBodyCloseFailure(Exception closeFailure) {
        String errorType = closeFailure == null
                ? "unknown"
                : closeFailure.getClass().getSimpleName();
        try {
            TraceStore.put("web.pageScraper.redirectBodyCloseFailed", true);
            TraceStore.inc("web.pageScraper.redirectBodyCloseFailed.count");
            TraceStore.put("web.pageScraper.redirectBodyCloseFailed.errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[PageContentScraper] fail-soft trace skipped stage={} errorType={}",
                    "redirectBodyClose", traceFailure.getClass().getSimpleName());
        }
        log.debug("[PageContentScraper] fail-soft stage={} errorType={}",
                "redirectBodyClose", errorType);
    }

    private static void traceFetchSuccess(int redirectCount) {
        TraceStore.put("web.pageScraper.fetchText.blocked", false);
        TraceStore.put("web.pageScraper.fetchText.blockedReason", null);
        TraceStore.put("web.pageScraper.fetchText.wireAttemptObserved", true);
        TraceStore.put("web.pageScraper.fetchText.redirectCount", Math.max(0, redirectCount));
    }

    private static void tracePolicyBlock(String url,
                                         int timeoutMs,
                                         String reason,
                                         boolean wireAttemptObserved,
                                         int redirectCount) {
        TraceStore.put("web.pageScraper.fetchText.failed", true);
        TraceStore.inc("web.pageScraper.fetchText.failed.count");
        TraceStore.put("web.pageScraper.fetchText.stage", "urlPolicy");
        TraceStore.put("web.pageScraper.fetchText.errorType", "outbound_policy_blocked");
        TraceStore.put("web.pageScraper.fetchText.blocked", true);
        TraceStore.put("web.pageScraper.fetchText.blockedReason",
                SafeRedactor.traceLabelOrFallback(reason, "policy_blocked"));
        TraceStore.put("web.pageScraper.fetchText.wireAttemptObserved", wireAttemptObserved);
        TraceStore.put("web.pageScraper.fetchText.redirectCount", Math.max(0, redirectCount));
        TraceStore.put("web.pageScraper.fetchText.urlHash12", SafeRedactor.hash12(url));
        TraceStore.put("web.pageScraper.fetchText.timeoutMs", Math.max(1000, timeoutMs));
        log.debug("[PageContentScraper] fail-soft stage={}", "urlPolicy");
    }

    private static void traceFetchFailure(String url,
                                          int timeoutMs,
                                          Exception ex,
                                          boolean wireAttemptObserved,
                                          int redirectCount) {
        String errorType = ex == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("web.pageScraper.fetchText.failed", true);
        TraceStore.inc("web.pageScraper.fetchText.failed.count");
        TraceStore.put("web.pageScraper.fetchText.stage", "fetchText");
        TraceStore.put("web.pageScraper.fetchText.errorType", errorType);
        TraceStore.put("web.pageScraper.fetchText.blocked", false);
        TraceStore.put("web.pageScraper.fetchText.blockedReason", null);
        TraceStore.put("web.pageScraper.fetchText.wireAttemptObserved", wireAttemptObserved);
        TraceStore.put("web.pageScraper.fetchText.redirectCount", Math.max(0, redirectCount));
        TraceStore.put("web.pageScraper.fetchText.urlHash12", SafeRedactor.hash12(url));
        TraceStore.put("web.pageScraper.fetchText.timeoutMs", Math.max(1000, timeoutMs));
        log.debug("[PageContentScraper] fail-soft stage={}", "fetchText");
        log.debug("[PageContentScraper] fail-soft errorType={}", errorType);
    }

    private static void traceBudgetExhausted(String url,
                                             int timeoutMs,
                                             String reason,
                                             boolean wireAttemptObserved,
                                             int redirectCount) {
        TraceStore.put("web.pageScraper.fetchText.failed", true);
        TraceStore.inc("web.pageScraper.fetchText.failed.count");
        TraceStore.put("web.pageScraper.fetchText.stage", "budget");
        TraceStore.put("web.pageScraper.fetchText.errorType",
                SafeRedactor.traceLabelOrFallback(reason, "budget_exhausted"));
        TraceStore.put("web.pageScraper.fetchText.blocked", false);
        TraceStore.put("web.pageScraper.fetchText.blockedReason", null);
        TraceStore.put("web.pageScraper.fetchText.wireAttemptObserved", wireAttemptObserved);
        TraceStore.put("web.pageScraper.fetchText.redirectCount", Math.max(0, redirectCount));
        TraceStore.put("web.pageScraper.fetchText.urlHash12", SafeRedactor.hash12(url));
        TraceStore.put("web.pageScraper.fetchText.timeoutMs", Math.max(1000, timeoutMs));
        log.debug("[PageContentScraper] fail-soft stage={} reason={}", "budget", reason);
    }

    private static final class BudgetExhaustedException extends Exception {
        private final String reason;

        private BudgetExhaustedException(String reason) {
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }

    private static final class OutboundPolicyException extends Exception {
        private final String reason;

        private OutboundPolicyException(String reason) {
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }
}
