package tools.fallback;

public class FallbackRetrieveTool {
    public static class Result {
        public boolean empty = true;
        public String note = "fallback: no retriever available";
    }
    public Result invoke(String query) { return new Result(); }
}