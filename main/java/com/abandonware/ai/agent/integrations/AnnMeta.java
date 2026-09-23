
package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;



/**
 * Index metadata (very small format).
 */
public class AnnMeta {
    public final Map<String, Integer> idToRow = new HashMap<>();
    public final List<String> rowToId = new ArrayList<>();

    public static AnnMeta load(Path dir) throws IOException {
        Path meta = dir.resolve("meta.tsv");
        AnnMeta m = new AnnMeta();
        if (!Files.exists(meta)) return m;
        for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length >= 2) {
                String id = p[0] == null ? "" : p[0].trim();
                if (id.isBlank()) {
                    continue;
                }
                int row;
                try {
                    row = Integer.parseInt(p[1].trim());
                } catch (NumberFormatException ignored) {
                    traceMalformedRow(id, p[1]);
                    continue;
                }
                if (row < 0) {
                    continue;
                }
                m.idToRow.put(id, row);
                while (m.rowToId.size() <= row) m.rowToId.add(null);
                m.rowToId.set(row, id);
            }
        }
        return m;
    }

    private static void traceMalformedRow(String id, String rowValue) {
        TraceStore.put("agent.annMeta.malformedRow", true);
        TraceStore.put("agent.annMeta.malformedRow.reason", "invalid_row_index");
        TraceStore.put("agent.annMeta.malformedRow.idHash", SafeRedactor.hashValue(id));
        TraceStore.put("agent.annMeta.malformedRow.rowHash", SafeRedactor.hashValue(rowValue));
        TraceStore.put("agent.annMeta.malformedRow.rowLength", rowValue == null ? 0 : rowValue.length());
    }

    public static void save(Path dir, AnnMeta meta) throws IOException {
        Path metaFile = dir.resolve("meta.tsv");
        try (var w = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
            for (int i=0;i<meta.rowToId.size();i++) {
                String id = meta.rowToId.get(i);
                if (id != null) {
                    w.write(id + "\t" + i + "\n");
                }
            }
        }
    }
}
