package ai.abandonware.nova.autolearn;

import java.util.*;
import java.util.stream.Collectors;

public class RagJsonlRecord {
    public String query;
    public String answer;
    public List<String> sources;
    public String mode;
    public String timestamp;
    public Double finalSigmoidScore;
    public Integer citationCount;

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final RagJsonlRecord r = new RagJsonlRecord();
        public Builder query(String v) { r.query = v; return this; }
        public Builder answer(String v) { r.answer = v; return this; }
        public Builder sources(List<String> v) { r.sources = v; return this; }
        public Builder mode(String v) { r.mode = v; return this; }
        public Builder timestamp(String v) { r.timestamp = v; return this; }
        public Builder finalSigmoidScore(Double v) { r.finalSigmoidScore = v; return this; }
        public Builder citationCount(Integer v) { r.citationCount = v; return this; }
        public RagJsonlRecord build() { return r; }
    }

    public String toJson() {
        String src = (sources == null) ? "[]" : 
            "[" + sources.stream().map(s -> "\"" + escape(s) + "\"").collect(Collectors.joining(",")) + "]";
        return "{"
                + "\"query\":\"" + escape(query) + "\","
                + "\"answer\":\"" + escape(answer) + "\","
                + "\"sources\":" + src + ","
                + "\"mode\":\"" + escape(mode) + "\","
                + "\"timestamp\":\"" + escape(timestamp) + "\","
                + "\"finalSigmoidScore\":" + (finalSigmoidScore == null ? "null" : finalSigmoidScore) + ","
                + "\"citationCount\":" + (citationCount == null ? "null" : citationCount)
                + "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

public String getAnswer(){ return this.answer; }


public RagJsonlRecord(String question, String answer, java.util.List<String> usedSources, double finalSigmoidScore) {
    this.question = question;
    this.answer = answer;
    this.usedSources = usedSources;
    this.finalSigmoidScore = finalSigmoidScore;
}

}
