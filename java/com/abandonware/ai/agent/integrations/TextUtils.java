
package com.abandonware.ai.agent.integrations;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



public final class TextUtils {

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHangul}\\p{L}\\p{Nd}]{2,}", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DATE = Pattern.compile("(20\\d{2}|19\\d{2})-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])");

    private TextUtils() {}

    public static List<String> tokenize(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    public static Set<String> tokenizeSet(String text) {
        return new HashSet<>(tokenize(text));
    }

    public static String normalizeQueryKey(String query) {
        return String.join(" ", tokenize(query));
    }

    public static double titleOverlapBoost(String query, String title) {
        if (title == null || title.isBlank()) return 0.0;
        Set<String> q = tokenizeSet(query);
        if (q.isEmpty()) return 0.0;
        Set<String> t = tokenizeSet(title);
        if (t.isEmpty()) return 0.0;
        int overlap = 0;
        for (String s : q) if (t.contains(s)) overlap++;
        double ratio = overlap / Math.max(1.0, (double) q.size());
        return 0.7 * ratio;
    }

    public static double recencyBoost(String text) {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        Matcher m = DATE.matcher(text == null ? "" : text);
        if (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                LocalDate dt = LocalDate.of(y, mo, d);
                long days = Math.abs(ChronoUnit.DAYS.between(dt, now));
                return 0.5 * Math.exp(-days / 365.0);
            } catch (Exception ignore) {}
        }
        // no date -> default 0.5
        return 0.5;
    }

    public static String makeSnippet(String text, List<String> queryTokens) {
        if (text == null) return "";
        int max = text.length();
        // find first occurrence of any token
        int pos = Integer.MAX_VALUE;
        for (String tok : queryTokens) {
            int p = text.toLowerCase(Locale.ROOT).indexOf(tok);
            if (p >= 0) pos = Math.min(pos, p);
        }
        if (pos == Integer.MAX_VALUE) pos = 0;
        int start = Math.max(0, pos - 80);
        int end = Math.min(max, start + 200);
        String snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) snippet = "/* ... *&#47; " + snippet;
        if (end < max) snippet = snippet + " /* ... *&#47;";
        // ensure 160~220 approx
        if (snippet.length() < 160 && end < max) {
            end = Math.min(max, end + (180 - snippet.length()));
            snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
        }
        return snippet;
    }

    public static List<String> kShingles(String text, int k) {
        List<String> toks = tokenize(text);
        List<String> shingles = new ArrayList<>();
        for (int i = 0; i <= toks.size() - k; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < k; j++) {
                if (j > 0) sb.append(' ');
                sb.append(toks.get(i + j));
            }
            shingles.add(sb.toString());
        }
        return shingles;
    }

    public static double jaccard5Gram(String a, String b) {
        List<String> sa = kShingles(a, 5);
        List<String> sb = kShingles(b, 5);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        Set<String> A = new HashSet<>(sa);
        Set<String> B = new HashSet<>(sb);
        Set<String> inter = new HashSet<>(A);
        inter.retainAll(B);
        Set<String> uni = new HashSet<>(A);
        uni.addAll(B);
        return inter.size() / Math.max(1.0, (double) uni.size());
    }

    public static String sha1(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Objects.hashCode(s));
        }
    }
}