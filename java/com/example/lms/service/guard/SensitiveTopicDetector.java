package com.example.lms.service.guard;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heuristic sensitive-topic detector used to enable stricter privacy boundaries.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li>Fail-soft: if disabled or no match, do nothing.</li>
 *   <li>No raw-text logging: only coarse tags are recorded to TraceStore.</li>
 *   <li>Minimal invasiveness: flags are carried via GuardContext so downstream gates can act.</li>
 * </ul>
 */
@Component
public class SensitiveTopicDetector {

    @Value("${privacy.sensitive.enabled:true}")
    private boolean enabled;

    @Value("${privacy.sensitive.answer-temperature:0.2}")
    private double answerTemp;

    @Value("${privacy.sensitive.explore-temp-cap:0.7}")
    private double exploreTempCap;

    @Value("${privacy.boundary.block-web-search-on-sensitive:false}")
    private boolean blockWebOnSensitive;

    private static final Pattern SELF_HARM = Pattern.compile("(자해|자살|극단\\s*선택|죽고\\s*싶|살기\\s*싫)");
    private static final Pattern ABUSE = Pattern.compile("(학대|폭력|성폭력|가정폭력|가스라이팅|스토킹)");
    private static final Pattern TRAUMA = Pattern.compile("(트라우마|PTSD|공황|우울|불안)");

    public void applyTo(GuardContext gctx, ChatRequestDto req) {
        if (!enabled || gctx == null) {
            return;
        }
        String text = (req == null) ? null : req.getMessage();
        if (!StringUtils.hasText(text)) {
            return;
        }

        List<String> tags = new ArrayList<>();
        if (SELF_HARM.matcher(text).find()) tags.add("self_harm");
        if (ABUSE.matcher(text).find()) tags.add("abuse");
        if (TRAUMA.matcher(text).find()) tags.add("trauma");
        if (tags.isEmpty()) {
            return;
        }

        gctx.setSensitiveTopic(true);

        // Memory: block load/save paths
        gctx.putPlanOverride("memory.forceOff", true);

        // Sampling hints (upper layers may choose to apply)
        gctx.putPlanOverride("llm.answer.temperature", answerTemp);
        gctx.putPlanOverride("llm.explore.temperature.max", exploreTempCap);

        // Web boundary: mask query and (optionally) block web search for sensitive topics
        gctx.putPlanOverride("privacy.boundary.mask-web-query", true);
        if (blockWebOnSensitive) {
            gctx.putPlanOverride("privacy.boundary.block-web-search-on-sensitive", true);
        }

        // TraceStore: never store raw text; tags only.
        try {
            TraceStore.put("privacy.sensitive", true);
            TraceStore.put("privacy.sensitive.tags", tags);
        } catch (Exception ignore) {
        }
    }
}
