package com.example.lms.image;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;




/**
 * Simple heuristic detector for image generation intents.
 *
 * <p>The chat interface supports both explicit slash commands (such as
 * <code>/image</code> or <code>/img</code>) and natural language requests
 * like “로고 느낌으로 그려줘”.  This component inspects the user’s
 * message and returns {@code true} when it appears to request an image
 * rather than a text answer.  The regex deliberately includes both
 * English and Korean keywords to cover common user phrasings.  The
 * detection is case-insensitive.</p>
 */
@Component
public class ImageIntentDetector {

    /**
     * Pattern matching common image keywords.  The list includes
     * English terms (image, picture, illustration, render, concept art,
     * poster, logo, icon, wallpaper, thumbnail) as well as Korean
     * equivalents.  Word boundaries ensure that partial matches inside
     * other words do not trigger false positives.
     */
    private static final Pattern IMAGE_HINT = Pattern.compile(
            "(?i)(?:\\bimage\\b|\\bimg\\b|picture|illustration|render|concept art|poster|logo|icon|wallpaper|thumbnail|"
                    + "그림|이미지|그려줘|렌더|포스터|로고|아이콘|썸네일|벽지|배경화면|컨셉아트)"
    );

    /**
     * Determine whether the supplied query is likely an image request.
     *
     * <p>Empty or null strings never qualify as image intents.  When the
     * message begins with the slash command <code>/image</code> or
     * <code>/img</code> the method immediately returns {@code true}.
     * Otherwise the text is matched against {@link #IMAGE_HINT}.  If
     * any keyword is present anywhere in the query the detector
     * returns {@code true}.</p>
     *
     * @param query the raw user query; may be null
     * @return true if the query appears to request an image
     */
    public boolean isImageIntent(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.strip();
        // explicit slash commands take priority
        if (q.startsWith("/image") || q.startsWith("/img")) {
            return true;
        }
        return IMAGE_HINT.matcher(q).find();
    }
}