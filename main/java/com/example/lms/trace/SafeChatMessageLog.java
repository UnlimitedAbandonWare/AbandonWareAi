package com.example.lms.trace;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Count- and enum-only logging boundary for LangChain4j chat messages. */
public final class SafeChatMessageLog {

    private SafeChatMessageLog() {
    }

    public static Summary summarize(List<? extends ChatMessage> messages) {
        List<String> contentTypes = new ArrayList<>();
        long decodedImageBytes = 0L;
        long textChars = 0L;
        ImageMediaType imageMediaType = ImageMediaType.NONE;
        boolean imagePresent = false;

        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message instanceof SystemMessage systemMessage) {
                    contentTypes.add("SYSTEM_TEXT");
                    textChars = safeAdd(textChars, safeLength(systemMessage.text()));
                } else if (message instanceof AiMessage aiMessage) {
                    contentTypes.add("ASSISTANT_TEXT");
                    textChars = safeAdd(textChars, safeLength(aiMessage.text()));
                } else if (message instanceof UserMessage userMessage) {
                    List<Content> contents = userMessage.contents();
                    if (contents == null || contents.isEmpty()) {
                        contentTypes.add("USER_EMPTY");
                        continue;
                    }
                    for (Content content : contents) {
                        if (content instanceof TextContent textContent) {
                            contentTypes.add("TEXT");
                            textChars = safeAdd(textChars, safeLength(textContent.text()));
                        } else if (content instanceof ImageContent imageContent) {
                            contentTypes.add("IMAGE");
                            imagePresent = true;
                            Image image = imageContent.image();
                            if (image != null) {
                                decodedImageBytes = safeAdd(
                                        decodedImageBytes,
                                        decodedBase64Bytes(image.base64Data()));
                                imageMediaType = merge(
                                        imageMediaType,
                                        imageMediaType(image.mimeType()));
                            }
                        } else {
                            contentTypes.add("OTHER");
                        }
                    }
                } else if (message != null) {
                    contentTypes.add("OTHER_MESSAGE");
                }
            }
        }

        return new Summary(
                List.copyOf(contentTypes),
                imagePresent,
                decodedImageBytes,
                imageMediaType,
                textChars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) textChars);
    }

    public static int safeTextChars(List<? extends ChatMessage> messages) {
        return summarize(messages).textChars();
    }

    public static void traceDraft(Logger logger, List<? extends ChatMessage> messages) {
        if (logger == null || !logger.isTraceEnabled()) {
            return;
        }
        Summary summary = summarize(messages);
        logger.trace(
                "[LC] final messages for draft. contentTypes={} imagePresent={} decodedImageBytes={} imageMediaType={}",
                summary.contentTypes(),
                summary.imagePresent(),
                summary.decodedImageBytes(),
                summary.imageMediaType());
    }

    private static int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private static long decodedBase64Bytes(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return 0L;
        }
        int length = encoded.length();
        int padding = 0;
        if (encoded.charAt(length - 1) == '=') {
            padding++;
        }
        if (length > 1 && encoded.charAt(length - 2) == '=') {
            padding++;
        }
        return Math.max(0L, ((long) length * 3L) / 4L - padding);
    }

    private static ImageMediaType imageMediaType(String mimeType) {
        if (mimeType == null) {
            return ImageMediaType.OTHER;
        }
        return switch (mimeType.trim().toLowerCase(Locale.ROOT)) {
            case "image/png" -> ImageMediaType.PNG;
            case "image/jpeg" -> ImageMediaType.JPEG;
            case "image/webp" -> ImageMediaType.WEBP;
            default -> ImageMediaType.OTHER;
        };
    }

    private static ImageMediaType merge(ImageMediaType current, ImageMediaType next) {
        if (current == ImageMediaType.NONE) {
            return next;
        }
        return current == next ? current : ImageMediaType.MULTIPLE;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum ImageMediaType {
        NONE,
        PNG,
        JPEG,
        WEBP,
        OTHER,
        MULTIPLE
    }

    public record Summary(
            List<String> contentTypes,
            boolean imagePresent,
            long decodedImageBytes,
            ImageMediaType imageMediaType,
            int textChars) {
    }
}
