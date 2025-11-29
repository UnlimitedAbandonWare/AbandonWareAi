package com.example.lms.search;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;




/**
 * A utility component that trims polite suffixes, leading descriptors and
 * excessive spacing from user provided queries.  In addition, simple
 * stop-word pruning is applied on a per token basis.  This centralised
 * cleaning helper allows disparate parts of the system to share a single
 * normalisation algorithm which reduces duplicated logic and makes it
 * easier to evolve stopword lists or regular expressions in one place.
 */
@Component
public class NoiseClipper {

    /**
     * Korean stop words that are removed when appearing as separate tokens.
     * The list is intentionally left small; additional terms can be added
     * as needed by the project.  See README for guidance on editing.
     */
    private static final List<String> STOPWORDS_KO = List.of(
            /* 예: "및", "그리고", "요", "주세요" 등 */
    );

    /** Pattern matching polite or filler phrases at the end of a query. */
    private static final Pattern TRAILING = Pattern.compile("(알려줘|뭐지\\?|뭐야\\?|좀\\s*찾아와|입니다)\\s*$");
    /** Pattern matching labels at the beginning of a query (e.g. "검색어:"). */
    private static final Pattern LEADING  = Pattern.compile("^(검색어\\s*:\\s*|질문\\s*:\\s*)");
    /** Pattern matching two or more consecutive whitespace characters. */
    private static final Pattern MULTISPC = Pattern.compile("\\s{2,}");
    /** ASCII/유니코드 따옴표 제거용 패턴: “ ” ‘ ’ ` ´ " ' */
    private static final Pattern QUOTE_CHARS =
            Pattern.compile("[\\u201C\\u201D\\u2018\\u2019\\u0060\\u00B4\"']");


    /**
     * Clean a raw user query by trimming, removing known leading/trailing
     * decorators, normalising whitespace, stripping common quote characters
     * and pruning stop words.  If the input is {@code null} or blank an
     * empty string is returned.
     *
     * @param raw the original query
     * @return a cleaned version of the query
     */
    public String clip(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.strip();
        // Remove labels like "검색어:" at the front
        s = LEADING.matcher(s).replaceAll("");
        // Remove polite suffixes or trailing filler words
        s = TRAILING.matcher(s).replaceAll("");
        // Collapse repeated whitespace
        s = MULTISPC.matcher(s).replaceAll(" ");
        // Remove common quotes (ASCII  Unicode quotes)
        s = QUOTE_CHARS.matcher(s).replaceAll("");
        // Simple stop word pruning: split on whitespace and drop tokens that match
        String[] tokens = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String tok : tokens) {
            boolean isStop = false;
            for (String sw : STOPWORDS_KO) {
                if (sw.equalsIgnoreCase(tok)) {
                    isStop = true;
                    break;
                }
            }
            if (!isStop) {
                if (out.length() > 0) out.append(' ');
                out.append(tok);
            }
        }
        return out.toString().trim();
    }
}