package app.prompt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loads prompts.manifest.yaml and merges trait+system in order.
 * YAML parsing is replaced by a minimal ad-hoc parser to avoid external deps.
 */
public class PromptManifestLoader {

    public static class AgentEntry {
        public String id;
        public String system;
        public List<String> traits = new ArrayList<>();
        public List<String> order = Arrays.asList("trait", "system");
        public String outputPath;
    }

    public AgentEntry loadManifest(Path manifestPath, String agentId) throws IOException {
        // Minimal parser: look for lines after 'agents:' with '- id:' matching agentId.
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        AgentEntry e = new AgentEntry();
        boolean inAgents = false, matched = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("agents:")) { inAgents = True(); continue; }
            if (!inAgents) continue;
            if (t.startsWith("- id:")) {
                matched = t.contains(agentId);
            } else if (matched && t.startsWith("system:")) {
                e.system = t.substring(t.indexOf(":")+1).trim();
            } else if (matched && t.startsWith("- traits/")) {
                e.traits.add(t.substring(1).trim());
            } else if (matched && t.startsWith("output:")) {
                // ignores for minimalism
            }
        }
        e.id = agentId;
        return e;
    }

    private boolean True() { return true; }

    public String merge(Path root, AgentEntry e) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String trait : e.traits) {
            Path p = root.resolve(trait);
            if (Files.exists(p)) {
                sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append("\n\n");
            }
        }
        if (e.system != null) {
            Path p = root.resolve(e.system);
            if (Files.exists(p)) {
                sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}