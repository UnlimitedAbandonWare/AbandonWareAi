package com.example.lms.integrations.n8n;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;



/**
 * Notifier responsible for delivering asynchronous task results back to
 * callers.  When the n8n integration is enabled it will POST payloads
 * to the configured notifyUrl.  Additionally, the {@link #notify(String, Map)}
 * method supports arbitrary callback URLs for ad-hoc notifications.  Errors
 * during delivery are logged but do not propagate.
 */
@Component
@RequiredArgsConstructor
public class N8nNotifier {

    private static final Logger log = LoggerFactory.getLogger(N8nNotifier.class);
    private final WebClient.Builder webClientBuilder;
    private final N8nProps props;

    /**
     * Notify the default n8n endpoint if enabled.  When disabled this
     * method does nothing.
     *
     * @param title   notification title (ignored by default implementation)
     * @param payload notification payload
     */
    public void notifyDefault(Map<String, Object> payload) {
        if (!props.isEnabled() || props.getNotifyUrl() == null || props.getNotifyUrl().isBlank()) {
            return;
        }
        notify(props.getNotifyUrl(), payload);
    }

    /**
     * Notify an arbitrary callback URL with the provided payload.  The
     * notification is sent as a POST request with a JSON body.  Errors
     * during delivery are logged and suppressed.
     *
     * @param callbackUrl the URL to invoke
     * @param payload     the JSON payload to send
     */
    public void notify(String callbackUrl, Map<String, Object> payload) {
        if (callbackUrl == null || callbackUrl.isBlank()) return;
        try {
            WebClient wc = webClientBuilder.build();
            wc.post().uri(callbackUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .doOnNext(b -> log.warn("n8n callback failed: status={} body={}", r.statusCode(), b))
                            .then(Mono.error(new RuntimeException("n8n callback"))))
                    .toBodilessEntity()
                    .doOnError(e -> log.warn("n8n callback error: {}", e.toString()))
                    .subscribe();
        } catch (Exception e) {
            log.warn("n8n callback invocation failed: {}", e.toString());
        }
    }
}