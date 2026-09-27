package com.example.lms.nova.burst;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.SelfAskPlanner;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class QueryBurstExpander {
    private static final String KO_GALAXY = text(0xAC24, 0xB7ED, 0xC2DC);
    private static final String KO_TRIFOLD = text(0xD2B8, 0xB77C, 0xC774, 0xD3F4, 0xB4DC);
    private static final String KO_RUMOR = text(0xB8E8, 0xBA38);
    private static final List<LaneVariant> LANE_VARIANTS = List.of(
            new LaneVariant("conservative", "STRICT", "conservative official source"),
            new LaneVariant("evidence-first", "VERIFY", "evidence first source verification"),
            new LaneVariant("contradiction-check", "COUNTEREXAMPLE", "contradiction check counterexample"));
    private static final List<String> LANE_PROFILES = LANE_VARIANTS.stream()
            .map(LaneVariant::profile)
            .toList();
    private final SelfAskPlanner planner;

    public QueryBurstExpander() {
        this(null);
    }

    public QueryBurstExpander(SelfAskPlanner planner) {
        this.planner = planner;
    }

    public List<String> expand(String seed, int minN, int maxN) {
        int min = Math.max(1, Math.min(minN, 32));
        int max = Math.max(min, Math.min(maxN, 32));

        String base = sanitize(seed);
        if (base.isBlank()) {
            traceExtremeZBurst(0, min, max, "blank_query", List.of());
            return List.of();
        }

        if (planner != null) {
            return expandWithPlanner(base, min, max);
        }

        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        out.add(stripTrailingFiller(base));

        boolean hasKo = containsHangul(base);
        boolean hasEn = base.matches(".*[A-Za-z].*");
        boolean primaryKo = hasKo;
        if (hasKo && hasEn) {
            long koCount = base.codePoints().filter(QueryBurstExpander::isHangul).count();
            long enCount = base.chars()
                    .filter(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
                    .count();
            primaryKo = koCount >= enCount * 0.5d;
        }

        String lower = base.toLowerCase(Locale.ROOT);
        if (base.contains(KO_TRIFOLD)
                || lower.contains("trifold")
                || lower.contains("tri-fold")
                || lower.contains("three-fold")) {
            out.add(KO_GALAXY + " " + KO_TRIFOLD + " " + KO_RUMOR);
            out.add("Galaxy trifold rumor");
            out.add("tri-fold foldable phone rumor");
        }

        if (hasKo) {
            addSuffixes(out, base, koreanSuffixes(), max);
            addLaneVariants(out, base, max);
        } else {
            addLaneVariants(out, base, max);
        }
        if (hasEn && !primaryKo) {
            addSuffixes(out, base, List.of("official", "announcement", "release", "release date",
                    "price", "specs", "review", "rumor", "vs"), max);
        } else if (hasEn) {
            addSuffixes(out, base, List.of("official", "specs"), Math.min(max, out.size() + 2));
        }

        if (out.size() < min) {
            if (hasKo) {
                out.add(base + " " + text(0xC815, 0xBCF4));
                out.add(base + " " + text(0xC815, 0xB9AC));
            } else {
                out.add(base + " info");
                out.add(base + " summary");
            }
        }

        List<String> list = new ArrayList<>();
        for (String s : out) {
            if (s == null) continue;
            String t = sanitize(s);
            if (!t.isBlank()) {
                list.add(t);
            }
            if (list.size() >= max) break;
        }
        if (list.size() > max) {
            return list.subList(0, max);
        }
        traceExtremeZBurst(list.size(), min, max, "", list);
        return list;
    }

    private List<String> expandWithPlanner(String base, int min, int max) {
        try {
            List<String> planned = planner.plan(base, max);
            List<String> normalized = normalize(planned, max);
            if (!normalized.isEmpty()) {
                traceExtremeZBurst(normalized.size(), min, max, "", normalized);
                return normalized;
            }
            traceExtremeZBurst(1, min, max, "planner_empty", List.of(base));
            return List.of(base);
        } catch (RuntimeException e) {
            traceExtremeZBurst(1, min, max,
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "planner_error"),
                    List.of(base));
            return List.of(base);
        }
    }

    private static List<String> normalize(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = sanitize(value);
            if (!candidate.isBlank()) {
                out.add(candidate);
            }
            if (out.size() >= max) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static void traceExtremeZBurst(int count, int min, int max, String bypassReason, List<String> variants) {
        TraceStore.put("extremeZ.burstExpand.count", Math.max(0, count));
        TraceStore.put("extremeZ.burstExpand.min", Math.max(1, min));
        TraceStore.put("extremeZ.burstExpand.max", Math.max(1, max));
        TraceStore.put("extremeZ.burstExpand.laneProfiles", LANE_PROFILES);
        TraceStore.put("extremeZ.burstExpand.laneVariantProfiles", laneVariantProfiles(variants));
        TraceStore.put("extremeZ.burstExpand.bypassReason",
                SafeRedactor.traceLabelOrFallback(bypassReason, ""));
    }

    private static void addLaneVariants(Set<String> out, String base, int max) {
        if (out.size() >= max || base == null || base.isBlank()) {
            return;
        }
        for (LaneVariant variant : LANE_VARIANTS) {
            if (out.size() >= max) {
                break;
            }
            out.add(base + " " + variant.suffix());
        }
    }

    private static List<Map<String, Object>> laneVariantProfiles(List<String> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String variant : variants) {
            String safeVariant = sanitize(variant);
            if (safeVariant.isBlank()) {
                continue;
            }
            String lower = safeVariant.toLowerCase(Locale.ROOT);
            for (LaneVariant laneVariant : LANE_VARIANTS) {
                if (!lower.endsWith(laneVariant.suffix())) {
                    continue;
                }
                String hash = SafeRedactor.hash12(safeVariant);
                String key = laneVariant.profile() + "|" + String.valueOf(hash);
                if (!seen.add(key)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", out.size() + 1);
                row.put("profile", laneVariant.profile());
                row.put("role", laneVariant.role());
                row.put("queryHash12", hash == null ? "" : hash);
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> koreanSuffixes() {
        return List.of(
                text(0xACF5, 0xC2DD),
                text(0xBC1C, 0xD45C),
                text(0xCD9C, 0xC2DC),
                text(0xAC00, 0xACA9),
                text(0xC2A4, 0xD399),
                text(0xC0AC, 0xC591),
                text(0xBE44, 0xAD50),
                text(0xB9AC, 0xBDF0),
                text(0xD6C4, 0xAE30),
                KO_RUMOR,
                text(0xB17C, 0xBB38));
    }

    private static void addSuffixes(Set<String> out, String base, List<String> suffixes, int max) {
        if (out.size() >= max) return;
        for (String suffix : suffixes) {
            if (out.size() >= max) break;
            if (suffix == null || suffix.isBlank()) continue;
            if (base.contains(suffix)) continue;
            out.add(base + " " + suffix);
        }
    }

    private static String stripTrailingFiller(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = stripSuffix(t, List.of(
                text(0xC54C, 0xB824, 0xC918),
                text(0xC54C, 0xB824, 0x20, 0xC918),
                text(0xC124, 0xBA85, 0xD574, 0xC918),
                text(0xC815, 0xB9AC, 0xD574, 0xC918),
                text(0xCC3E, 0xC544, 0xC918),
                text(0xCD94, 0xCC9C, 0xD574, 0xC918),
                text(0xD574, 0xC918),
                text(0xD574, 0xC8FC, 0xC138, 0xC694)));
        t = t.replaceAll("(?i)\\s+(tell me|explain|summarize|find|search)\\s*$", "");
        return sanitize(t);
    }

    private static String stripSuffix(String value, List<String> suffixes) {
        String out = value == null ? "" : value.trim();
        for (String suffix : suffixes) {
            if (suffix == null || suffix.isBlank()) continue;
            if (out.endsWith(suffix)) {
                return out.substring(0, out.length() - suffix.length()).trim();
            }
        }
        return out;
    }

    private static boolean containsHangul(String value) {
        return value != null && value.codePoints().anyMatch(QueryBurstExpander::isHangul);
    }

    private static boolean isHangul(int codePoint) {
        return codePoint >= 0xAC00 && codePoint <= 0xD7A3;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ");
        t = t.replaceAll("\\s{2,}", " ").trim();
        if (t.length() > 200) {
            t = t.substring(0, 200).trim();
        }
        return t;
    }

    private static String text(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private record LaneVariant(String profile, String role, String suffix) {
    }
}
