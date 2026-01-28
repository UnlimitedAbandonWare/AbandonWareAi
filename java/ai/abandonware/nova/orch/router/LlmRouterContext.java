package ai.abandonware.nova.orch.router;

/**
 * ThreadLocal carrier for the last llmrouter decision made during a request.
 */
public final class LlmRouterContext {

    private static final ThreadLocal<Route> ROUTE = new ThreadLocal<>();

    private LlmRouterContext() {
    }

    public static void set(String key, String baseUrl, String modelName) {
        if (key == null || key.isBlank()) {
            return;
        }
        ROUTE.set(new Route(key, baseUrl, modelName, System.currentTimeMillis()));
    }

    public static Route get() {
        return ROUTE.get();
    }

    public static void clear() {
        ROUTE.remove();
    }

    public record Route(String key, String baseUrl, String modelName, long startedAtMs) {
    }
}
