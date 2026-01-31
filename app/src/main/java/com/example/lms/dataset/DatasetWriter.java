
package com.example.lms.dataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;

@Component
public class DatasetWriter {
    private final ObjectMapper om = new ObjectMapper();

    public synchronized void appendRecord(File file, String question, String answer){
        try (FileOutputStream fos = new FileOutputStream(file, true);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter bw = new BufferedWriter(w)) {
            ObjectNode n = om.createObjectNode();
            n.put("question", question);
            n.put("answer", answer);
            n.put("ts", System.currentTimeMillis());
            bw.write(om.writeValueAsString(n));
            bw.write("\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
