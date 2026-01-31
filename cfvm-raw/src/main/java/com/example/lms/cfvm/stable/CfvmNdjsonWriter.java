package com.example.lms.cfvm.stable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CfvmNdjsonWriter implements Closeable {
    private final OutputStream out;
    public CfvmNdjsonWriter(OutputStream out){ this.out = out; }

    public void write(RawSlot slot) throws IOException {
        String json = toJson(slot);
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    private String toJson(RawSlot s){
        String safe = s.message.replace("\\", "\\\\").replace(""", "\\"");
        return String.format("{"ts":%d,"code":"%s","message":"%s","severity":"%s"}",
            s.ts.toEpochMilli(), s.code, safe, s.severity.name());
    }

    @Override public void close() throws IOException { out.flush(); }
}