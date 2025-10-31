package tools.fallback;

public class OutboxSendTool {
    public static class Result {
        public boolean queued = true;
        public String channel = "outbox";
    }
    public Result invoke(String text) { return new Result(); }
}