package com.example.lms.search;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative lexical constraints, not a semantic equivalence or authorization check. */
public final class SearchQueryConstraints {
    private static final Pattern EXPLICIT = Pattern.compile(
            "(?iu)(?:\\b(?:site|after|before|lang|language|region|country|scope|acl|tenant|filetype):\\S+"
            + "|(?<!\\S)-[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]*|\"[^\"]+\""
            + "|\\b[\\p{L}][\\p{L}\\p{N}_.+-]*\\s+v?\\d+(?:[.:-]\\d+)*"
            + "|\\b[\\p{L}\\p{N}_]*\\d[\\p{L}\\p{N}_.-]*\\b)");
    private static final Pattern OPAQUE_SCOPE = Pattern.compile(
            "(?iu)\\b(?:not|without|except|exclude|excluding|only|private|public|internal|korean|english)\\b"
            + "|아닌|아니라|말고|제외|한국어|영어|비공개|공개만|내부만|자료만");

    private SearchQueryConstraints() { }

    public static boolean hasConstraints(String original) {
        return original != null && (EXPLICIT.matcher(original).find() || OPAQUE_SCOPE.matcher(original).find());
    }

    public static boolean preserves(String original, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        if (original == null || original.isBlank()) return true;
        String source = normalize(original);
        String proposed = normalize(candidate);
        if (source.equals(proposed)) return true;
        // Natural-language exclusions/scope cannot safely be reconstructed from a token bag.
        // Keep the complete clause until an existing semantic constraint representation is available.
        if (OPAQUE_SCOPE.matcher(source).find()) return proposed.contains(source);
        Set<String> spans = new LinkedHashSet<>();
        var matcher = EXPLICIT.matcher(source);
        while (matcher.find()) {
            spans.add(matcher.group());
            if (spans.size() > 64) return proposed.contains(source);
        }
        for (String span : spans) {
            if (!Pattern.compile("(?<![\\p{L}\\p{N}_.-])" + Pattern.quote(span)
                    + "(?![\\p{L}\\p{N}_.-])").matcher(proposed).find()) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
