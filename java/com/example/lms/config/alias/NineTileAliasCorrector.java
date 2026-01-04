package com.example.lms.config.alias;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import com.acme.aicore.domain.ports.LightweightNlpPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;

/**
 * NineTileAliasCorrector - context-aware alias normalizer (9 tiles).
 * Overlay-safe: returns original text if confidence is low or no rule matches.
 */
@Component
public class NineTileAliasCorrector {
    public static final List<String> TILES = Arrays.asList("animals","games","finance","science","law","media","tech","health","misc");
    private final Map<String, Map<String,String>> tileDict = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(NineTileAliasCorrector.class);

    @Autowired(required = false)
    private LightweightNlpPort lightweightNlp;

    @Value("${alias.thumbnail.enabled:true}")
    private boolean thumbnailEnabled;

    @Value("${alias.thumbnail.max-chars:256}")
    private int thumbnailMaxChars = 256;

    @Value("${alias.thumbnail.timeout-ms:1000}")
    private long thumbnailTimeoutMs = 1000L;


    @Autowired(required = false)
@Qualifier("virtualPointService")
private Object virtualPointService; // optional

    public NineTileAliasCorrector() {
	    Map<String,String> animals = new HashMap<>();
	    // Do NOT force "스커크" -> "스컹크" here. It is frequently a Genshin proper noun.
	    // Keep animal corrections strictly animal-related.

	    Map<String,String> games = new HashMap<>();
	    // Game tile reverse-corrections (prevent animal drift)
	    games.put("스컹크", "스커크");
	    games.put("마키바", "마비카");
	    tileDict.put("animals", animals);
	    tileDict.put("games", games);
    }

        public String correct(String text, Locale locale, Map<String, Object> context) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String ruleBased = applyRuleBasedAlias(text, locale, context);
        return maybeRewriteWithMiniLlm(ruleBased, locale, context);
    }

    private String applyRuleBasedAlias(String text, Locale locale, Map<String, Object> context) {
        if (text == null || text.isBlank()) return text;
        double[] w = new double[TILES.size()];
        Arrays.fill(w, 1.0 / TILES.size());

	    String rawHint = context != null ? Objects.toString(context.getOrDefault("vp.topTile", context.get("intent.domain")), "") : "";
	    String hint = normalizeHintToTile(rawHint, text);
	    int hi = TILES.indexOf(hint);
        if (hi >= 0) {
            Arrays.fill(w, 0.05);
            w[hi] = 0.6;
        }

        String best = text; double bestScore = 0.0;
        for (int i=0;i<TILES.size();i++) {
            String tile = TILES.get(i);
            Map<String,String> dict = tileDict.get(tile);
            if (dict == null) continue;
            for (Map.Entry<String,String> e : dict.entrySet()) {
                String from = e.getKey();
                if (text.contains(from)) {
                    double score = w[i];
                    if (score > bestScore) {
                        best = text.replace(from, e.getValue());
                        bestScore = score;
                    }
                }
            }
        }
        if (bestScore < 0.2) return text;
        return best;
    }

    /**
     * Normalize domain/tile hints to a valid tile id.
     * The metadata may include domain labels (e.g., GENSHIN/GAME/IT_KNOWLEDGE) rather than exact tile ids.
     */
    private static String normalizeHintToTile(String rawHint, String text) {
        String h = rawHint == null ? "" : rawHint.strip();
        if (!h.isEmpty()) {
            String lower = h.toLowerCase(Locale.ROOT);
            if (TILES.contains(lower)) return lower;

            String u = h.toUpperCase(Locale.ROOT);
            if (u.contains("GENSHIN") || u.contains("GAME") || u.contains("SUBCULTURE")) return "games";
            if (u.contains("LIVING") || u.contains("ANIMAL") || u.contains("PET")) return "animals";
            if (u.contains("FINANCE") || u.contains("STOCK") || u.contains("ECON")) return "finance";
            if (u.contains("SCI") || u.contains("MATH")) return "science";
            if (u.contains("LAW") || u.contains("REGULATION")) return "law";
            if (u.contains("MEDIA") || u.contains("ENT") || u.contains("NEWS") || u.contains("CULTURE")) return "media";
            if (u.contains("TECH") || u.contains("IT") || u.contains("DEV") || u.contains("CODE") || u.contains("DEVICE")) return "tech";
            if (u.contains("HEALTH") || u.contains("MED")) return "health";
        }

        // Very light text-based fallback (avoid aggressive remapping)
        String t = text == null ? "" : text;
        String tl = t.toLowerCase(Locale.ROOT);
        if (tl.contains("원신") || tl.contains("genshin") || tl.contains("가챠") || tl.contains("성유물") || tl.contains("파티")) return "games";
        if (tl.contains("강아지") || tl.contains("고양이") || tl.contains("동물") || tl.contains("펫")) return "animals";
        if (tl.contains("주식") || tl.contains("코인") || tl.contains("환율") || tl.contains("금리")) return "finance";
        if (tl.contains("법") || tl.contains("판례") || tl.contains("계약")) return "law";
        if (tl.contains("병원") || tl.contains("증상") || tl.contains("약")) return "health";
        if (tl.contains("코드") || tl.contains("버그") || tl.contains("에러") || tl.contains("api") || tl.contains("서버")) return "tech";
        return "misc";
    }

    private String maybeRewriteWithMiniLlm(String input, java.util.Locale locale, Map<String, Object> context) {
        if (!thumbnailEnabled) {
            return input;
        }
        if (lightweightNlp == null) {
            return input;
        }
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return input;
        }
        if (trimmed.length() > thumbnailMaxChars) {
            return input;
        }
        if (!containsHangul(trimmed)) {
            return input;
        }

        String prompt = buildThumbnailPrompt(trimmed, locale, context);
        try {
            String out = lightweightNlp
                    .rewrite(prompt)
                    .block(Duration.ofMillis(thumbnailTimeoutMs));
            if (out == null) {
                return input;
            }
            String cleaned = postProcessMiniLlmOutput(out);
            if (cleaned == null || cleaned.isBlank()) {
                return input;
            }
            return cleaned;
        } catch (Exception e) {
            log.debug("NineTileAliasCorrector: mini LLM thumbnail rewrite failed, fallback to rule-based only", e);
            return input;
        }
    }


    private boolean containsHangul(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uAC00' && c <= '\uD7AF') {
                return true;
            }
        }
        return false;
    }

    private boolean isKoreanLikely(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // Hangul syllables, Jamo, compatibility Jamo
            if ((ch >= '\uAC00' && ch <= '\uD7A3')
                    || (ch >= '\u1100' && ch <= '\u11FF')
                    || (ch >= '\u3130' && ch <= '\u318F')) {
                return true;
            }
        }
        return false;
    }

    private String buildThumbnailPrompt(String sentence, java.util.Locale locale, Map<String, Object> context) {
        return "너는 한국어 문장을 '최소한으로' 교정하는 도우미야.\n"
                + "규칙:\n"
                + "- 문장 앞뒤 맥락이 어색하거나, 같은 말을 두 번 반복한 부분만 고쳐.\n"
                + "- 고유명사(게임 제목, 캐릭터명, 기기 모델명 등)는 그대로 두고 오타만 바로잡아.\n"
                + "- 의미, 사실, 숫자, 수치는 절대 바꾸지 마.\n"
                + "- 요약하지 말고, 입력과 거의 비슷한 길이의 한 문장만 돌려줘.\n"
                + "- 설명, 해설, 따옴표 없이 교정된 문장만 출력해.\n\n"
                + "입력 문장: " + sentence;
    }

    private String postProcessMiniLlmOutput(String out) {
        if (out == null) {
            return "";
        }
        String trimmed = out.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("“") && trimmed.endsWith("”"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

}