package telemetry;

/**
 * Minimal SSE publisher that logs core events.
 */
public class LoggingSseEventPublisher {
    public void emit(String type, String value) {
        System.out.println("sse:event " + type + " value=" + value);
    }

// (NEW) 편의 오버로드
}