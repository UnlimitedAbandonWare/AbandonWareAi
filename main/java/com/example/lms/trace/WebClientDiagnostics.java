// src/main/java/com/example/lms/trace/WebClientDiagnostics.java
package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.net.URI;


@Configuration
@ConditionalOnProperty(name = "lms.trace.http.enabled", havingValue = "true")
public class WebClientDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(WebClientDiagnostics.class);

	private static final String HDR_REQUEST_ID = "X-Request-Id";
	private static final String HDR_SESSION_ID = "X-Session-Id";

    private ExchangeFilterFunction requestLogger() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            URI uri = null;
            try {
				String sid = MDC.get("sid");
				String trace = MDC.get("trace");

				ClientRequest.Builder b = ClientRequest.from(req);
				if (sid != null && !sid.isBlank() && !req.headers().containsKey(HDR_SESSION_ID)) {
					b.header(HDR_SESSION_ID, sid);
				}
				if (trace != null && !trace.isBlank() && !req.headers().containsKey(HDR_REQUEST_ID)) {
					b.header(HDR_REQUEST_ID, trace);
				}

				ClientRequest out = b.build();
				uri = out.url();
				String rawQuery = uri == null ? null : uri.getRawQuery();
				log.debug("[AWX][trace][webclient][req] method={} target={} hasQuery={} queryHash={} sidHash={} traceHash={}",
						out.method(),
						hostPath(uri),
						rawQuery != null && !rawQuery.isBlank(),
						rawQuery == null || rawQuery.isBlank() ? "" : SafeRedactor.hashValue(rawQuery),
						sid == null || sid.isBlank() ? "" : SafeRedactor.hashValue(sid),
						trace == null || trace.isBlank() ? "" : SafeRedactor.hashValue(trace));
				return Mono.just(out);
            } catch (Exception requestEx) {
				TraceStore.put("trace.webclient.suppressed.request", true);
				TraceStore.put("trace.webclient.suppressed.request.errorType",
						SafeRedactor.traceLabelOrFallback(requestEx.getClass().getSimpleName(), "unknown"));
				traceWebClientDiagnosticsFailure("request", uri, requestEx);
			}
            return Mono.just(req);
        });
    }

    private ExchangeFilterFunction responseLogger() {
        return (req, next) -> next.exchange(req).flatMap(res -> {
			URI uri = req.url();
            try {
                if (res.statusCode().isError()) {
					String rawQuery = uri == null ? null : uri.getRawQuery();
                    return res.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
								String sid = MDC.get("sid");
								String trace = MDC.get("trace");
								log.debug("[AWX][trace][webclient][res] method={} target={} hasQuery={} queryHash={} status={} bodyHash={} bodyLength={} sidHash={} traceHash={}",
										req.method(),
										hostPath(uri),
										rawQuery != null && !rawQuery.isBlank(),
										rawQuery == null || rawQuery.isBlank() ? "" : SafeRedactor.hashValue(rawQuery),
										res.statusCode().value(),
										body == null || body.isBlank() ? "" : SafeRedactor.hashValue(body),
										body == null ? 0 : body.length(),
										sid == null || sid.isBlank() ? "" : SafeRedactor.hashValue(sid),
										trace == null || trace.isBlank() ? "" : SafeRedactor.hashValue(trace));
                                // Rebuild response with the consumed body to avoid downstream breakage.
								ClientResponse mutated = ClientResponse.from(res).body(body).build();
                                return Mono.just(mutated);
                            });
                }
            } catch (Exception responseEx) {
				TraceStore.put("trace.webclient.suppressed.response", true);
				TraceStore.put("trace.webclient.suppressed.response.errorType",
						SafeRedactor.traceLabelOrFallback(responseEx.getClass().getSimpleName(), "unknown"));
				traceWebClientDiagnosticsFailure("response", uri, responseEx);
			}
            return Mono.just(res);
        });
    }

	private static String hostPath(URI uri) {
		if (uri == null) {
			return "";
		}
		String host = uri.getHost();
		String path = uri.getRawPath();
		if (path == null || path.isBlank()) {
			path = "/";
		}
		if (host == null || host.isBlank()) {
			return path;
		}
		return host + path;
	}

	private static void traceWebClientDiagnosticsFailure(String stage, URI uri, Throwable error) {
		String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
		String safeReason = error == null
				? "unknown"
				: SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
		String target = hostPath(uri);
		TraceStore.inc("webclient.diagnostics." + safeStage + ".failed");
		TraceStore.putIfAbsent("webclient.diagnostics.failed", true);
		TraceStore.putIfAbsent("webclient.diagnostics.failureStage", safeStage);
		TraceStore.putIfAbsent("webclient.diagnostics.failureReason", safeReason);
		TraceStore.putIfAbsent("webclient.diagnostics.targetHash", SafeRedactor.hash12(target));
		TraceStore.putIfAbsent("webclient.diagnostics.targetLength", target == null ? 0 : target.length());
	}

    @Bean
    public WebClientCustomizer debugWebClientCustomizer() {
        ExchangeFilterFunction req = requestLogger();
        ExchangeFilterFunction res = responseLogger();
        return builder -> builder.filter(req).filter(res);
    }
}
