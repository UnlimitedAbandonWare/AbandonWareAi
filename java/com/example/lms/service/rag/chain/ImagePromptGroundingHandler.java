package com.example.lms.service.rag.chain;

import com.example.lms.image.GroundedImagePromptBuilder;
import lombok.RequiredArgsConstructor;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Detects simple image generation intents and prepares a grounded
 * prompt along with image-specific metadata.  The grounded prompt is
 * computed using the provided {@link GroundedImagePromptBuilder}
 * applied to the current prompt context.  Metadata entries with
 * keys starting with "image." are reserved for the image generation
 * plugin.
 */
@RequiredArgsConstructor
public class ImagePromptGroundingHandler implements ChainLink {
    private static final Logger log = LoggerFactory.getLogger(ImagePromptGroundingHandler.class);

    private final GroundedImagePromptBuilder groundedImagePromptBuilder;

    @Override
    public ChainOutcome handle(ChainContext ctx, Chain next) {
        try {
            String msg = (ctx.userMessage() == null) ? "" : ctx.userMessage().toLowerCase(Locale.ROOT);
            // Simple rules to detect image intent: recognise slash commands
            // like /img as well as Korean and English phrases.
            boolean imgIntent =
                    msg.startsWith("/img") || msg.contains("이미지 생성") ||
                    msg.startsWith("image:") || msg.contains("그림 그려");
            if (!imgIntent) {
                return next.proceed(ctx);
            }
            // Build a grounded prompt using the builder.  The builder may
            // incorporate memory, web and other context.  When the builder
            // throws an exception fall back to the raw user message.
            String grounded;
            try {
                // Use buildTask to obtain an ImageTask; extract the prompt field.
                var task = groundedImagePromptBuilder.buildTask(ctx.userMessage(), ctx.promptContext());
                grounded = (task != null && task.getPrompt() != null)
                        ? task.getPrompt()
                        : ctx.userMessage();
            } catch (Exception e) {
                grounded = ctx.userMessage();
            }
            // Store image metadata in the context so that downstream
            // components (e.g. OpenAiImageService) can read it.  Provide
            // sensible defaults for model and size.
            ctx.putMeta("image.model", "gpt-image-1");
            ctx.putMeta("image.prompt", grounded);
            ctx.putMeta("image.size", "1024x1024");
            // Optionally note the image intent in the assistant side notes.
            ctx.withAssistantSideNote("이미지 생성을 위한 프롬프트를 접지했습니다. (model=gpt-image-1)");
            // Log the hash of the grounded prompt for observability.  Use
            // hashCode() for a lightweight correlation.  Catch any
            // exceptions to avoid breaking chain execution.
            try {
                log.info("image.intent groundedPromptHash={}", grounded.hashCode());
            } catch (Exception ignore) {
                // ignore
            }
            return next.proceed(ctx);
        } catch (Exception e) {
            log.warn("ImagePromptGroundingHandler failed, passing down: {}", e.toString());
            return next.proceed(ctx);
        }
    }
}