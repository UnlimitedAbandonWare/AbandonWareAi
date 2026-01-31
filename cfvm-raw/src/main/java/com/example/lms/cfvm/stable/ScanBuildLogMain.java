package com.example.lms.cfvm.stable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ScanBuildLogMain {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path latest = root.resolve("BUILD_ERROR__latest.txt");
        String text = Files.exists(latest)? Files.readString(latest) : "";
        BuildLogSlotExtractor extractor = new BuildLogSlotExtractor();
        List<RawSlot> slots = extractor.extract(text);

        Map<String,Integer> counts = new LinkedHashMap<>();
        for(RawSlot s: slots) counts.put(s.code, counts.getOrDefault(s.code, 0)+1);

        Path outDir = root.resolve("analysis");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("cfvm_stable_build_patterns.json");
        String json = toJson(counts);
        Files.writeString(out, json);
        System.out.println("Wrote pattern summary: " + out);
    }

    private static String toJson(Map<String,Integer> map){
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i=0, n=map.size();
        for(var e: map.entrySet()){
            sb.append("  \"").append(e.getKey()).append("\": ").append(e.getValue());
            if(++i<n) sb.append(",\n"); else sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}