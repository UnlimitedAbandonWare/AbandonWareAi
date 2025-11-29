
package com.example.lms.service.nova;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.example.lms.service.infra.cache.SingleFlightExecutor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix="nova.expert-open", name="enabled", havingValue="true", matchIfMissing=false)
public class ExpertOpenService {
    private final HttpClient client;
    private final Duration timeout;
    private final int maxPages;
    private final SingleFlightExecutor singleFlight;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ExpertOpenService(
            @Value("{${nova.expert-open.timeout-ms:3000}}") long timeoutMs,
            @Value("{${nova.expert-open.max-pages:3}}") int maxPages,
            SingleFlightExecutor singleFlight
    ) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxPages = Math.max(1, maxPages);
        this.singleFlight = singleFlight;
    }

    public record Page(String url, int status, String body) {}

    public CompletableFuture<Page> fetch(String url) {
        if (url == null || url.isBlank()) {
            return CompletableFuture.completedFuture(new Page(url, 400, ""));
        }
        String cached = cache.get(url);
        if (cached != null) return CompletableFuture.completedFuture(new Page(url, 200, cached));
        return singleFlight.run("expert-open:"+url, () -> doFetch(url).thenApply(p -> {
            if (p.status == 200 && p.body != null && !p.body.isBlank()) {
                cache.put(url, p.body);
            }
            return CompletableFuture.completedFuture(p);
        }).thenCompose(x -> x));
    }

    private CompletableFuture<Page> doFetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout).build();
            long t0 = System.nanoTime();
            return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .handle((resp, err) -> {
                        long durMs = (System.nanoTime()-t0)/1_000_000L;
                        if (err != null) {
                            return new Page(url, 599, ""); 
                        }
                        return new Page(url, resp.statusCode(), resp.body());
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new Page(url, 500, ""));
        }
    }

    public CompletableFuture<List<Page>> fetchAll(List<String> urls) {
        if (urls == null) return CompletableFuture.completedFuture(List.of());
        List<String> list = urls.stream().filter(Objects::nonNull).limit(maxPages).collect(Collectors.toList());
        List<CompletableFuture<Page>> futures = list.stream().map(this::fetch).collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }
}
