package com.example.lms.cfvm.build;

import com.example.lms.cfvm.BuildLogSlotExtractor;
import com.example.lms.cfvm.RawSlot;
import com.example.lms.cfvm.io.CfvmNdjsonWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Build error reporter: reads build log lines, classifies into RawSlot events, writes NDJSON and summary. */
/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.example.lms.cfvm.build.BuildErrorReporter
 * Role: class
 * Dependencies: com.example.lms.cfvm.BuildLogSlotExtractor, com.example.lms.cfvm.RawSlot, com.example.lms.cfvm.io.CfvmNdjsonWriter
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.cfvm.build.BuildErrorReporter
role: class
*/
public class BuildErrorReporter {

    private final BuildLogSlotExtractor extractor = new BuildLogSlotExtractor();

    public Result reportFromLog(Path logFile, Path ndjsonOut, Path summaryJsonOut, String sessionId) throws IOException {
        List<String> lines = readAllLines(logFile);
        List<RawSlot> slots = new ArrayList<>();
        for (String line : lines) {
            slots.addAll(extractor.fromLine(line, RawSlot.Stage.BUILD, sessionId));
        }
        // Ensure out dir
        ndjsonOut.toFile().getParentFile().mkdirs();
        summaryJsonOut.toFile().getParentFile().mkdirs();
        // Write NDJSON
        try (CfvmNdjsonWriter writer = new CfvmNdjsonWriter(new FileOutputStream(ndjsonOut.toFile()))) {
            writer.writeAll(slots);
        }
        // Write summary
        Map<String, Long> byCode = slots.stream().collect(Collectors.groupingBy(RawSlot::code, LinkedHashMap::new, Collectors.counting()));
        writeSummary(summaryJsonOut, lines.size(), byCode);
        return new Result(slots.size(), byCode);
    }

    private static List<String> readAllLines(Path p) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(p.toFile()), StandardCharsets.UTF_8))) {
            List<String> out = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) out.add(line);
            return out;
        }
    }

    private static void writeSummary(Path summary, int totalLines, Map<String, Long> byCode) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(summary.toFile()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            w.write("  \"generatedAt\": \"" + Instant.now() + "\",\n");
            w.write("  \"totalLines\": " + totalLines + ",\n");
            w.write("  \"errors\": {\n");
            boolean first = true;
            for (Map.Entry<String, Long> e : byCode.entrySet()) {
                if (!first) w.write(",\n");
                first = false;
                w.write("    \"" + e.getKey() + "\": " + e.getValue());
            }
            w.write("\n  }\n");
            w.write("}\n");
        }
    }

    public static final class Result {
        public final int total;
        public final Map<String, Long> byCode;
        public Result(int total, Map<String, Long> byCode) {
            this.total = total; this.byCode = byCode;
        }
    }
}