
    package telemetry;
    import java.util.Map;
    public class SseEventPublisher {
        public void publish(String stage, Map<String,Object> attrs) {
            // placeholder: send to SSE channel /internal/stream/ops
        }
    
    // Reactive stream view for WebFlux controllers (placeholder).
    public reactor.core.publisher.Flux<java.util.Map<String,Object>> asStream() {
        return reactor.core.publisher.Flux.empty();
    }
}