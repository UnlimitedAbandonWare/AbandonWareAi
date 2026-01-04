package com.example.lms.uaw.autolearn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;

@Component
public class UawDatasetWriter {

    private final ObjectMapper om = new ObjectMapper();

    private final UawDatasetTrainingDataFilter trainingDataFilter;

    public UawDatasetWriter(UawDatasetTrainingDataFilter trainingDataFilter) {
        this.trainingDataFilter = trainingDataFilter;
    }

    public synchronized boolean append(File file,
                                       String datasetName,
                                       String question,
                                       String answer,
                                       String modelUsed,
                                       int evidenceCount,
                                       String sessionId) {
        if (file == null || answer == null || answer.isBlank()) return false;

        // Final safety: exclude degraded/fallback-only samples from entering the dataset.
        if (trainingDataFilter.shouldExclude(question, answer, modelUsed)) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();

            ObjectNode n = om.createObjectNode();
            n.put("ts", Instant.now().toString());
            n.put("source", "uaw_autolearn");
            if (sessionId != null) n.put("sessionId", sessionId);
            if (datasetName != null) n.put("dataset", datasetName);

            String q = question == null ? "" : question;
            String a = answer;
            n.put("question", q);
            n.put("answer", a);
            if (modelUsed != null) n.put("model", modelUsed);
            n.put("evidenceCount", evidenceCount);
            n.put("id", sha1((datasetName == null ? "" : datasetName) + "|" + q + "|" + a));

            try (FileOutputStream fos = new FileOutputStream(file, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(w)) {
                bw.write(om.writeValueAsString(n));
                bw.write("\n");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte v : b) sb.append(String.format("%02x", v));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
