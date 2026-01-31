
package com.abandonware.ai.agent.integrations;

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
                m.idToRow.put(p[0], Integer.parseInt(p[1]));
                while (m.rowToId.size() <= Integer.parseInt(p[1])) m.rowToId.add(null);
                m.rowToId.set(Integer.parseInt(p[1]), p[0]);
            }
        }
        return m;
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