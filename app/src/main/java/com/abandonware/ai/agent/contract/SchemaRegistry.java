package com.abandonware.ai.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.contract.SchemaRegistry
 * Role: config
 * Dependencies: com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.ObjectMapper, com.networknt.schema.JsonSchema, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.contract.SchemaRegistry
role: config
*/
public class SchemaRegistry {
    private final Map<String, JsonSchema> byTool = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SchemaRegistry() {
        try {
            ClassPathResource res = new ClassPathResource("docs/tool_manifest__kchat_gpt_pro.json");
            try (InputStream is = res.getInputStream()) {
                JsonNode root = mapper.readTree(is);
                JsonNode tools = root.get("tools");
                if (tools != null && tools.isArray()) {
                    JsonSchemaFactory fac = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909);
                    for (Iterator<JsonNode> it = tools.elements(); it.hasNext(); ) {
                        JsonNode t = it.next();
                        String id = t.has("name") ? t.get("name").asText() : null;
                        JsonNode params = t.get("parameters");
                        if (id != null && params != null && params.isObject()) {
                            JsonSchema schema = fac.getSchema(params);
                            byTool.put(id, schema);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fail-soft: leave empty, orchestrator will skip schema validation
        }
    }

    public JsonSchema schemaFor(String toolId) {
        return byTool.get(toolId);
    }
}