package com.example.lms.service.rag.chain;

import com.example.lms.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Attempts to enrich the prompt context with any user attachments
 * associated with the current session.  In the absence of a proper
 * attachment query service this handler is implemented as a pass‑through.
 */
@Slf4j
@RequiredArgsConstructor
public class AttachmentContextHandler implements ChainLink {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttachmentContextHandler.class);


    private final AttachmentService attachmentService;

    @Override
    public ChainOutcome handle(ChainContext ctx, Chain next) {
        try {
            // Look up any attachments associated with the current session.  When
            // attachments exist, invoke ctx.withAttachment for each so that
            // downstream prompt builders can incorporate summaries or previews.
            var atts = attachmentService.findBySession(ctx.sessionId());
            if (atts != null) {
                for (var att : atts) {
                    try {
                        ctx.withAttachment(att);
                    } catch (Exception ignore) {
                        // ignore per-attachment failures
                    }
                }
            }
            return next.proceed(ctx);
        } catch (Exception e) {
            log.warn("AttachmentContextHandler failed, passing down: {}", e.toString());
            return next.proceed(ctx);
        }
    }
}