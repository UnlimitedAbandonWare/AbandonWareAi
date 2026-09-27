package com.example.lms.search.policy;

import java.util.Locale;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Identifies consumer-offer research, never account eligibility or purchase authority. */
public final class GrokPromotionDiscovery {
    private static final Pattern BRAND = Pattern.compile("(?i)\\b(?:supergrok|grok|xai)\\b|\\bx\\.ai\\b");
    private static final Pattern OFFER = Pattern.compile(
            "(?i)\\b(?:discount|promo(?:tion)?s?|offers?|retention|win[ -]?back|subscription|subscribe|pricing|billing)\\b");

    public static final String DISCOVERY_SUFFIX = " targeted offer retention event promotion";
    public static final String VERIFICATION_SUFFIX = " promotion terms eligibility renewal billing";

    private GrokPromotionDiscovery() { }

    public static boolean matches(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean brand = BRAND.matcher(q).find() || q.contains("\uadf8\ub85d");
        if (!brand) return false;
        boolean consumer = q.contains("supergrok") || q.contains("heavy") || q.contains("plus")
                || q.contains("subscription") || q.contains("\uad6c\ub3c5");
        if (Pattern.compile("\\bapi\\b").matcher(q).find() && !consumer) return false;
        // A mention or negation of hacking is not itself evidence of abuse.
        // Existing security policies still apply; this only adds lawful discovery paths.
        return OFFER.matcher(q).find() || q.contains("targeted-offer")
                || q.contains("\ud560\uc778") || q.contains("\ud504\ub85c\ubaa8\uc158")
                || q.contains("\ub9ac\ud150\uc158") || q.contains("\uad6c\ub3c5")
                || q.contains("\uc774\ubca4\ud2b8") || q.contains("\uc624\ud37c")
                || q.contains("\uac31\uc2e0") || q.contains("\uc694\uae08\uc81c");
    }

    /** Round-robin distinct evidence in caller-specified lane priority, never eligibility. */
    public static List<String> mergeEvidence(List<List<String>> lanes, int topK) {
        if (lanes == null || topK <= 0) return List.of();
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (int row = 0; merged.size() < topK; row++) {
            boolean hasRow = false;
            for (List<String> lane : lanes) {
                if (lane == null || row >= lane.size()) continue;
                hasRow = true;
                String snippet = lane.get(row);
                if (snippet != null && !snippet.isBlank()) merged.add(snippet);
                if (merged.size() >= topK) break;
            }
            if (!hasRow) break;
        }
        return List.copyOf(merged);
    }

    public static String instructions() {
        return """
                ### LAWFUL PROMOTION DISCOVERY
                - Explore normally service-provided event, targeted offer, upgrade, retention and win-back promotions, including offers for existing accounts. A non-public listing or account-specific offer is not by itself hacking or payment bypass.
                - Preserve the user's requested plan and purchase platform throughout search and comparison. Keep SuperGrok, SuperGrok Plus and SuperGrok Heavy distinct; a cheaper Plus price does not establish a Heavy discount.
                - Treat historical/user-reported offers as discovery leads, labeled with their source and date. A public pricing page or one account showing no offer is not proof of global expiry. Scope negative observations to the inspected account, platform, page and observation time; continue other authorized discovery paths.
                - Before asserting applicability, verify each offer's plan identity, purchase platform, amount due today, currency, billing cadence, discount duration, eligibility conditions, new/existing subscriber status, renewal price and renewal date from current official terms and the authorized account/checkout UI. Record a source and observation time; mark each missing or conflicting field evidence_needed.
                - A URL slug, discount headline, page load, historical price or percentage arithmetic alone cannot verify a current offer. Separate the quoted recurring price from today's proration, credits and taxes; separate an annual total from its monthly equivalent. Do not assume monthly discounts carry over to yearly billing or a future renewal.
                - Follow the actual purchase platform: Google Play, Apple App Store or web/Stripe. Do not assume a Stripe management route exists for an app-store subscription. Reading another platform's offer does not prove transfer eligibility.
                - Missing checkout or eligibility evidence blocks an applicability claim, not lawful research. Report observed terms, historical leads, account-local absence and evidence_needed separately, with the next normal verification step. Respect retrieval-off settings and existing search budgets.
                - Do not fabricate eligibility, alter payment tokens or requests, spoof account/region conditions, exploit billing defects or evade access controls. Discovery does not authorize purchases, cancellation, plan changes or recurring payment commitments.
                """;
    }
}
