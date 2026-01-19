package tools.trace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Locale;



/**
 * Writes a single-line JSON trace per invocation:
 * {"ts":"/* ... *&#47;","flow":"/* ... *&#47;","steps":["plan","retrieve",/* ... *&#47;],"sigma":[0.123456,/* ... *&#47;],"S":0.987654}
 */
public class OrchestrationTraceTool {

    public static class Result {
        public boolean ok;
        public String path;
        public Result(boolean ok, String path) { this.ok = ok; this.path = path; }
    }

    private static void appendEscapedJsonString(StringBuilder sb, String s) {
        if (s == null) {
            // write empty string instead of JSON null to keep field types stable
            sb.append("");
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    private static void appendJsonArrayStrings(StringBuilder sb, String[] arr) {
        if (arr == null) { sb.append("null"); return; }
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('\"');
            appendEscapedJsonString(sb, arr[i]);
            sb.append('\"');
        }
        sb.append(']');
    }

    private static void appendJsonArrayDoubles(StringBuilder sb, double[] arr) {
        if (arr == null) { sb.append("null"); return; }
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.6f", arr[i]));
        }
        sb.append(']');
    }

    public Result invoke(String flowId, String[] steps, double[] sigma, double score) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"ts\":\"");
        appendEscapedJsonString(sb, OffsetDateTime.now().toString());
        sb.append("\",\"flow\":\"");
        appendEscapedJsonString(sb, flowId);
        sb.append("\",\"steps\":");
        appendJsonArrayStrings(sb, steps);
        sb.append(",\"sigma\":");
        appendJsonArrayDoubles(sb, sigma);
        sb.append(",\"S\":");
        sb.append(String.format(Locale.ROOT, "%.6f", score));
        sb.append('}').append(System.lineSeparator());
        String jsonLine = sb.toString();

        Path p = Paths.get("orchestration_traces.log");
        try {
            Files.write(p, jsonLine.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new Result(true, p.toAbsolutePath().toString());
        } catch (Exception e) {
            // Swallow to avoid interrupting the main flow; report path as best-effort
            return new Result(false, p.toAbsolutePath().toString());
        }
    }
}