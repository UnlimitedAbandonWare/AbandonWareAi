package com.example.lms.api;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ChatAttachmentQuestionDetector {

    private static final String LEXICAL_CHARACTERS = "\\p{L}\\p{N}\\p{M}\\p{Pc}\\u200C\\u200D";
    private static final String SAFE_DELIMITER = "(?:[\\t ]|\\p{Zs}|[\\p{P}\\p{S}&&[^\\p{Pc}]])";
    private static final String HANGUL_PARTICLE =
            "(?:은|는|이|가|을|를|와|과|의|에|로|으로|에서|에게|부터|까지|만|도)";
    private static final String SAFE_SUFFIX =
            "(?=$|" + SAFE_DELIMITER
                    + "|" + HANGUL_PARTICLE + "(?=$|" + SAFE_DELIMITER + ")"
                    + "|좀(?=$|" + SAFE_DELIMITER + "))";

    private static final Pattern ENGLISH_ATTACHMENT_MARKER = Pattern.compile(
            "(?<![" + LEXICAL_CHARACTERS + "])"
                    + "(?:attachment|uploaded|upload|file|document|pdf)"
                    + SAFE_SUFFIX);

    private static final Pattern KOREAN_ATTACHMENT_MARKER = Pattern.compile(
            "(?<![" + LEXICAL_CHARACTERS + "])"
                    + "(?:첨부파일내용|첨부문서내용|파일내용|문서내용|첨부파일|첨부문서|전자문서|첨부(?:된|한)?|파일|문서)"
                    + SAFE_SUFFIX);

    private static final Pattern SUPPORTED_EXTENSION = Pattern.compile(
            "\\.(?:txt|md|pdf|doc|docx|pptx|xlsx|csv|json|xml|zip|hwpx|jpg|jpeg|png|gif|webp)"
                    + SAFE_SUFFIX);

    private ChatAttachmentQuestionDetector() {
    }

    public static boolean looksLikeAttachmentQuestion(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String s = msg.toLowerCase(Locale.ROOT);
        return ENGLISH_ATTACHMENT_MARKER.matcher(s).find()
                || KOREAN_ATTACHMENT_MARKER.matcher(s).find()
                || SUPPORTED_EXTENSION.matcher(s).find();
    }
}
