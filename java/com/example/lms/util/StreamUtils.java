package com.example.lms.util;

import com.example.lms.dto.ChatStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;



/** SSE 토큰 방출 유틸 */
public final class StreamUtils {
    private StreamUtils() {}

    public static void emitHtmlAsFirstTokens(Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink,
                                             String html, int chunkSize) {
        if (html == null || html.isBlank()) return;
        int n = Math.max(1, chunkSize);
        for (int i = 0; i < html.length(); i += n) {
            String part = html.substring(i, Math.min(html.length(), i + n));
            sink.tryEmitNext(ServerSentEvent.<ChatStreamEvent>
                            builder(ChatStreamEvent.token(part))
                    .event("token")
                    .build());
        }
    }
}