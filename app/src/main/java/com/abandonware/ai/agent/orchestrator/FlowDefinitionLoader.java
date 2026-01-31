package com.abandonware.ai.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.orchestrator.FlowDefinitionLoader
 * Role: config
 * Dependencies: com.fasterxml.jackson.databind.ObjectMapper, com.fasterxml.jackson.dataformat.yaml.YAMLFactory
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.FlowDefinitionLoader
role: config
*/
public class FlowDefinitionLoader {
    private final ObjectMapper mapper;

    public FlowDefinitionLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory());
    }

    public FlowDefinition load(String flowName) {
        // Support loading flows from an external directory.  If the
        // AGENT_FLOWS_PATH environment variable or the system property
        // 'agent.flows.path' is set and the corresponding YAML file exists
        // on disk then it will be used in preference to the classpath.  This
        // allows flows to be hot-reloaded in development without rebuilding
        // the jar.  If no external file is found the loader falls back to
        // the classpath resource under /flows.
        String externalDir = System.getProperty("agent.flows.path");
        if (externalDir == null || externalDir.isBlank()) {
            externalDir = System.getenv("AGENT_FLOWS_PATH");
        }
        if (externalDir != null && !externalDir.isBlank()) {
            Path candidate = Paths.get(externalDir, flowName + ".yaml");
            if (Files.isRegularFile(candidate)) {
                try {
                    return mapper.readValue(Files.newInputStream(candidate), FlowDefinition.class);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to load external flow definition: " + candidate, e);
                }
            }
        }
        // Fallback to classpath
        String path = "flows/" + flowName + ".yaml";
        Resource resource = new ClassPathResource(path);
        try {
            return mapper.readValue(resource.getInputStream(), FlowDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load flow definition: " + path, e);
        }
    }
}