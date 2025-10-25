package com.example.lms.service.rag.chain;

import com.example.lms.location.LocationService;
import lombok.RequiredArgsConstructor;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Intercepts queries that appear to ask for the user's current location
 * or address and attempts to provide a deterministic answer without
 * invoking the language model.  When no location can be determined
 * the request is passed through to the next link in the chain.
 */
@RequiredArgsConstructor
public class LocationInterceptHandler implements ChainLink {
    private static final Logger log = LoggerFactory.getLogger(LocationInterceptHandler.class);

    private final LocationService locationService;

    @Override
    public ChainOutcome handle(ChainContext ctx, Chain next) {
        try {
            // Lowercase the message for simple keyword matching.
            String userMsg = Optional.ofNullable(ctx.userMessage())
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            // Simple heuristics: look for Korean or English terms related to
            // location/address.  This intentionally errs on the side of
            // caution to avoid false positives.  A future version may
            // delegate to an intent classifier.
            boolean looksLikeAddressQ =
                    userMsg.contains("어디") || userMsg.contains("주소") ||
                    userMsg.contains("where am i") || userMsg.contains("my address");
            if (!looksLikeAddressQ) {
                return next.proceed(ctx);
            }
            // Attempt to resolve the last known address for the current user.
            Optional<String> answer = locationService.answerWhereAmI(ctx.userId());
            if (answer.isEmpty()) {
                return next.proceed(ctx);
            }
            // Emit an immediate assistant message when possible.  When SSE is
            // configured this may push the message directly to the client.
            ctx.emitAssistant(answer.get());
            // Also attach a system note to record the location for the
            // language model context.  Prompt builders may treat system
            // notes specially.
            ctx.withSystemNote("현재 위치 안내: " + answer.get());
            // Stop further processing since a direct response was generated.
            return ChainOutcome.SUCCESS_STOP;
        } catch (Exception e) {
            log.warn("LocationInterceptHandler failed, passing down: {}", e.toString());
            return next.proceed(ctx);
        }
    }
}