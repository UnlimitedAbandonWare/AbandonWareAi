package com.example.lms.cfvm.io;

import com.example.lms.cfvm.RawSlot;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.io.CfvmNdjsonWriter
 * Role: config
 * Dependencies: com.example.lms.cfvm.RawSlot
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.io.CfvmNdjsonWriter
role: config
*/
public class CfvmNdjsonWriter implements Closeable {
    private final OutputStream out;

    public CfvmNdjsonWriter(OutputStream out) {
        this.out = out;
    }

    public void write(RawSlot slot) throws IOException {
        String json = toJson(slot);
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    public void writeAll(List<RawSlot> slots) throws IOException {
        for (RawSlot s : slots) write(s);
    }

    @Override public void close() throws IOException { out.close(); }

    private static String esc(String s) {
        if (s == null) return "null";
        return '"' + s.replace("\\", "\\\\").replace(""","\\"").replace("\n","\\n") + '"';
    }

    private static String toJson(RawSlot s) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append(""sessionId":").append(esc(s.sessionId())).append(',');
        sb.append(""stage":").append(esc(String.valueOf(s.stage()))).append(',');
        sb.append(""code":").append(esc(s.code())).append(',');
        sb.append(""path":").append(esc(s.path())).append(',');
        sb.append(""message":").append(esc(s.message())).append(',');
        sb.append(""ts":").append(esc(String.valueOf(s.ts())));
        Map<String,String> tags = s.tags();
        if (tags != null && !tags.isEmpty()) {
            sb.append(","tags":{");
            boolean first=true;
            for (Map.Entry<String,String> e : tags.entrySet()) {
                if(!first) sb.append(',');
                first=false;
                sb.append(esc(e.getKey())).append(':').append(esc(e.getValue()));
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }
}