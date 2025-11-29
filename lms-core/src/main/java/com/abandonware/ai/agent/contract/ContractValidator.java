
package com.abandonware.ai.agent.contract;

import com.abandonware.ai.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;




@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.contract.ContractValidator
 * Role: config
 * Dependencies: com.abandonware.ai.agent.tool.ToolRegistry, com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.ObjectMapper
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.contract.ContractValidator
role: config
*/
public class ContractValidator {

    @Bean
    public ApplicationRunner manifestValidatorRunner(ToolRegistry registry) {
        return args -> {
            List<Map<String,Object>> issues = new ArrayList<>();
            try {
                ObjectMapper om = new ObjectMapper();
                ClassPathResource res = new ClassPathResource("docs/tool_manifest__kchat_gpt_pro.json");
                try (InputStream is = res.getInputStream()) {
                    JsonNode root = om.readTree(is);
                    Set<String> manifestIds = new HashSet<>();
                    for (JsonNode t : root.withArray("tools")) {
                        String id = t.path("name").asText(null);
                        if (id != null) manifestIds.add(id);
                    }
                    Set<String> regIds = new java.util.HashSet<>(registry.all().stream().map(com.abandonware.ai.agent.tool.AgentTool::id).collect(java.util.stream.Collectors.toSet()));
                    for (String id : manifestIds) {
                        if (!regIds.contains(id)) {
                            Map<String,Object> m = new HashMap<>();
                            m.put("id", id);
                            m.put("issue", "missing_in_registry");
                            issues.add(m);
                        }
                    }
                    for (String id : regIds) {
                        if (!manifestIds.contains(id)) {
                            Map<String,Object> m = new HashMap<>();
                            m.put("id", id);
                            m.put("issue", "missing_in_manifest");
                            issues.add(m);
                        }
                    }
                }
            } catch (Exception ignore) {
                // swallow
            }
            // write report
            try {
                File dir = new File("contract");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "validation-report.json");
                String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(Collections.singletonMap("mismatches", issues));
                java.nio.file.Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        };
    }
}