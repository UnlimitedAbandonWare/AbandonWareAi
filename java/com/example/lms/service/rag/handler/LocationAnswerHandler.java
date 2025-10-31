package com.example.lms.service.rag.handler;

import com.example.lms.location.LocationService;
import com.example.lms.location.intent.LocationIntent;
import com.example.lms.security.UserContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;




@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "abandonware.chain.enabled", havingValue = "true", matchIfMissing = true)
public class LocationAnswerHandler implements RetrievalHandler {

    private final LocationService locationService;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (query == null || locationService == null) return;

        String q = query.text() != null ? query.text() : "";
        try {
            LocationIntent intent = locationService.detectIntent(q);
            if (intent != null && intent != LocationIntent.NONE) {
                String uid = UserContext.currentUserId().orElse("anonymous");
                try {
                    java.util.Optional<String> answer = locationService.answerWhereAmI(uid);
                    if (answer != null && answer.isPresent()) {
                        String m = answer.get();
                        accumulator.add(Content.from(TextSegment.from(m)));
                    }
                } catch (Exception ignore) { /* fail-soft */ }
            }
        } catch (Exception ignore) { /* fail-soft */ }
    }
}